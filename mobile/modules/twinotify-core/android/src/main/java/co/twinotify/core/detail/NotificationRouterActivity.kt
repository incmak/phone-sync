package co.twinotify.core.detail

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import androidx.core.net.toUri
import co.twinotify.core.listener.NotifPostJson
import co.twinotify.core.storage.NotificationDb
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class NotificationRouteDetail(val packageName: String)

enum class NotificationTapResult {
    SourceOpened,
    FallbackOpened,
    InvalidDetail,
    Unavailable,
}

fun interface NotificationRouteLoader {
    suspend fun load(detailId: String): NotificationRouteDetail?
}

fun interface NotificationSourceLauncher {
    suspend fun launch(packageName: String): SourceLaunchResult
}

fun interface NotificationFallbackLauncher {
    suspend fun launch(detailId: String): Boolean
}

class NotificationTapRouter(
    private val load: NotificationRouteLoader,
    private val source: NotificationSourceLauncher,
    private val fallback: NotificationFallbackLauncher,
) {
    suspend fun route(
        detailId: String,
        openInTwinotify: Boolean = false,
    ): NotificationTapResult {
        if (!isOpaqueNotificationDetailId(detailId)) return NotificationTapResult.InvalidDetail
        if (!openInTwinotify) {
            val detail = load.load(detailId)
            if (detail != null && source.launch(detail.packageName) == SourceLaunchResult.Launched) {
                return NotificationTapResult.SourceOpened
            }
        }
        return if (fallback.launch(detailId)) {
            NotificationTapResult.FallbackOpened
        } else {
            NotificationTapResult.Unavailable
        }
    }
}

internal fun isOpaqueNotificationDetailId(value: String): Boolean = runCatching {
    UUID.fromString(value).toString() == value.lowercase()
}.getOrDefault(false)

internal fun notificationRouteUri(
    detailId: String,
    openInTwinotify: Boolean = false,
    sourceLaunchFailed: Boolean = false,
) = requireNotNull(detailId.takeIf(::isOpaqueNotificationDetailId)) {
    "notification detail ID must be an opaque UUID"
}.let { validDetailId ->
    "${NotificationRouterActivity.SCHEME}://${NotificationRouterActivity.NOTIFICATION_HOST}/$validDetailId"
        .toUri()
        .buildUpon()
        .apply {
            if (openInTwinotify) {
                appendQueryParameter(
                    NotificationRouterActivity.OPEN_IN_TWINOTIFY_PARAM,
                    NotificationRouterActivity.OPEN_IN_TWINOTIFY_VALUE,
                )
            }
            if (sourceLaunchFailed) appendQueryParameter(SOURCE_LAUNCH_FAILED_PARAM, "1")
        }
        .build()
}

class NotificationRouterActivity : Activity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var routingJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val route = intent.data
            ?.takeIf { it.scheme == SCHEME && it.host == NOTIFICATION_HOST && it.pathSegments.size == 1 }
        val detailId = route?.lastPathSegment
        if (detailId == null) {
            finish()
            return
        }
        val openInTwinotify = route.getQueryParameter(OPEN_IN_TWINOTIFY_PARAM) == OPEN_IN_TWINOTIFY_VALUE
        val dao = NotificationDb.get(applicationContext).reliableDeliveryDao()
        val router = NotificationTapRouter(
            load = NotificationRouteLoader { id ->
                dao.notificationDetail(id)?.let { cached ->
                    val post = runCatching { NotifPostJson.fromPayloadJson(cached.payloadJson) }.getOrNull()
                        ?: return@NotificationRouteLoader null
                    NotificationRouteDetail(post.package_name)
                }
            },
            source = NotificationSourceLauncher { packageName ->
                withContext(Dispatchers.Main.immediate) {
                    SourceAppLauncher(AndroidSourceAppPlatform(applicationContext)).launch(packageName)
                }
            },
            fallback = NotificationFallbackLauncher { id ->
                withContext(Dispatchers.Main.immediate) {
                    runCatching {
                        startActivity(
                            Intent(
                                Intent.ACTION_VIEW,
                                notificationRouteUri(id, sourceLaunchFailed = true),
                            ).apply {
                                setPackage(packageName)
                                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                            },
                        )
                        true
                    }.getOrDefault(false)
                }
            },
        )
        routingJob = scope.launch {
            try {
                router.route(detailId, openInTwinotify)
            } finally {
                withContext(Dispatchers.Main.immediate) { finish() }
            }
        }
    }

    override fun onDestroy() {
        routingJob?.cancel()
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        const val SCHEME = "twinotify"
        const val NOTIFICATION_HOST = "notification"
        const val OPEN_IN_TWINOTIFY_PARAM = "openInTwinotify"
        const val OPEN_IN_TWINOTIFY_VALUE = "1"
    }
}

private const val SOURCE_LAUNCH_FAILED_PARAM = "sourceLaunchFailed"
