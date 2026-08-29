package co.twinotify.core.detail

import android.content.Context
import co.twinotify.core.actions.ActionInvokeIdentity
import co.twinotify.core.actions.MirrorActionInvokeResult
import co.twinotify.core.actions.MirrorActionInvoker
import co.twinotify.core.listener.NotifPostJson
import co.twinotify.core.sourceAppArtworkDataUri
import co.twinotify.core.storage.ActionInvocation
import co.twinotify.core.storage.CanonicalNotificationState
import co.twinotify.core.storage.NotificationDb
import co.twinotify.core.storage.NotificationDetailCache
import co.twinotify.core.storage.PeerStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class NotificationDetailAction(
    val actionId: String,
    val title: String,
    val semantic: Int,
    val reply: Boolean,
    val replyLabel: String?,
    val invocationId: String?,
    val invocationState: String?,
)

data class NotificationDetail(
    val detailId: String,
    val sourceAppName: String?,
    val sourcePackage: String,
    val sourceAppIconDataUri: String?,
    val originDeviceLabel: String,
    val title: String?,
    val text: String?,
    val subText: String?,
    val bigText: String?,
    val smallIconDataUri: String?,
    val largeIconDataUri: String?,
    val receivedAt: Long,
    val updatedAt: Long,
    val state: String,
    val isAutoCancel: Boolean,
    val actions: List<NotificationDetailAction>,
)

interface NotificationDetailStore {
    suspend fun cache(detailId: String): NotificationDetailCache?
    suspend fun canonical(canonId: String): CanonicalNotificationState?
    suspend fun invocations(canonId: String, sequence: Long): List<ActionInvocation>
}

fun interface NotificationOriginLabel {
    suspend fun label(originDevice: String): String
}

fun interface NotificationDetailActionInvoker {
    suspend fun invoke(identity: ActionInvokeIdentity, replyText: String?): MirrorActionInvokeResult
}

fun interface NotificationSourceLaunchability {
    suspend fun canLaunch(packageName: String): Boolean
}

fun interface NotificationSourceOpener {
    suspend fun open(packageName: String): SourceLaunchResult
}

class NotificationDetailRepository(
    private val store: NotificationDetailStore,
    private val originLabel: NotificationOriginLabel,
    private val invokeAction: NotificationDetailActionInvoker,
    private val sourceLaunchability: NotificationSourceLaunchability,
    private val sourceOpener: NotificationSourceOpener,
    private val sourceIcon: (String) -> String? = { null },
) {
    suspend fun get(detailId: String): NotificationDetail? {
        val cached = store.cache(detailId) ?: return null
        val post = runCatching { NotifPostJson.fromPayloadJson(cached.payloadJson) }.getOrNull()
            ?: return null
        if (post.canon_id != cached.canonId) return null
        val canonical = store.canonical(cached.canonId)
        val invocations = canonical?.let { store.invocations(cached.canonId, it.latestSequence) }.orEmpty()
        val latestByAction = invocations.groupBy(ActionInvocation::actionId)
            .mapValues { (_, rows) -> rows.maxWithOrNull(compareBy<ActionInvocation> { it.updatedAt }.thenBy { it.invocationId }) }
        val state = when {
            cached.cancelledAt != null -> "CANCELLED"
            canonical == null -> "GONE"
            canonical.state == "ACTIVE" -> "ACTIVE"
            else -> "CANCELLED"
        }
        return NotificationDetail(
            detailId = cached.detailId,
            sourceAppName = post.app_name,
            sourcePackage = post.package_name,
            sourceAppIconDataUri = sourceIcon(post.package_name),
            originDeviceLabel = originLabel.label(cached.originDevice),
            title = post.title,
            text = post.text,
            subText = post.sub_text,
            bigText = post.big_text,
            smallIconDataUri = post.small_icon_png_b64.toImageDataUri(),
            largeIconDataUri = post.large_icon_png_b64.toImageDataUri(),
            receivedAt = cached.receivedAt,
            updatedAt = cached.updatedAt,
            state = state,
            isAutoCancel = post.is_auto_cancel,
            actions = post.actions.map { action ->
                val invocation = latestByAction[action.action_id]
                NotificationDetailAction(
                    actionId = action.action_id,
                    title = action.title,
                    semantic = action.semantic,
                    reply = action.reply,
                    replyLabel = action.reply_label,
                    invocationId = invocation?.invocationId,
                    invocationState = invocation?.state,
                )
            },
        )
    }

    suspend fun invoke(
        detailId: String,
        actionId: String,
        replyText: String?,
    ): MirrorActionInvokeResult {
        val cached = store.cache(detailId) ?: return MirrorActionInvokeResult.Gone
        val canonical = store.canonical(cached.canonId)
            ?.takeIf { it.state == "ACTIVE" && it.mirrorLocalTag != null && it.mirrorLocalId != null }
            ?: return MirrorActionInvokeResult.Gone
        return invokeAction.invoke(
            ActionInvokeIdentity(
                mirrorTag = requireNotNull(canonical.mirrorLocalTag),
                mirrorId = requireNotNull(canonical.mirrorLocalId),
                actionId = actionId,
            ),
            replyText,
        )
    }

    suspend fun canLaunchSourceApp(packageName: String): Boolean =
        packageName.isNotBlank() && sourceLaunchability.canLaunch(packageName)

    suspend fun openSourceApp(detailId: String): Boolean {
        val cached = store.cache(detailId) ?: return false
        val post = runCatching { NotifPostJson.fromPayloadJson(cached.payloadJson) }.getOrNull()
            ?.takeIf { it.canon_id == cached.canonId }
            ?: return false
        return sourceOpener.open(post.package_name) == SourceLaunchResult.Launched
    }

    companion object {
        fun production(context: Context): NotificationDetailRepository {
            val app = context.applicationContext
            val dao = NotificationDb.get(app).reliableDeliveryDao()
            val invoker = MirrorActionInvoker.production(app)
            val sourcePlatform = AndroidSourceAppPlatform(app)
            return NotificationDetailRepository(
                store = object : NotificationDetailStore {
                    override suspend fun cache(detailId: String) = dao.notificationDetail(detailId)
                    override suspend fun canonical(canonId: String) = dao.canonical(canonId)
                    override suspend fun invocations(canonId: String, sequence: Long) =
                        dao.actionInvocationsForNotification(canonId, sequence)
                },
                originLabel = NotificationOriginLabel { originDevice ->
                    PeerStore.load(app)
                        ?.takeIf { it.deviceId == originDevice }
                        ?.displayName
                        ?.takeIf(String::isNotBlank)
                        ?: "Paired device"
                },
                invokeAction = NotificationDetailActionInvoker(invoker::invoke),
                sourceLaunchability = NotificationSourceLaunchability { packageName ->
                    sourcePlatform.isInstalled(packageName) && sourcePlatform.hasLauncher(packageName)
                },
                sourceOpener = NotificationSourceOpener { packageName ->
                    withContext(Dispatchers.Main.immediate) {
                        SourceAppLauncher(sourcePlatform).launch(packageName)
                    }
                },
                sourceIcon = { packageName -> sourceAppArtworkDataUri(app, packageName) },
            )
        }
    }
}

internal fun NotificationDetail.toBridgeMap(): Map<String, Any?> = mapOf(
    "detailId" to detailId,
    "sourceAppName" to sourceAppName,
    "sourcePackage" to sourcePackage,
    "sourceAppIconDataUri" to sourceAppIconDataUri,
    "originDeviceLabel" to originDeviceLabel,
    "title" to title,
    "text" to text,
    "subText" to subText,
    "bigText" to bigText,
    "smallIconDataUri" to smallIconDataUri,
    "largeIconDataUri" to largeIconDataUri,
    "receivedAt" to receivedAt,
    "updatedAt" to updatedAt,
    "state" to state,
    "isAutoCancel" to isAutoCancel,
    "actions" to actions.map { action ->
        mapOf(
            "actionId" to action.actionId,
            "title" to action.title,
            "semantic" to action.semantic,
            "reply" to action.reply,
            "replyLabel" to action.replyLabel,
            "invocationId" to action.invocationId,
            "invocationState" to action.invocationState,
        )
    },
)

internal fun MirrorActionInvokeResult.toBridgeMap(): Map<String, Any?> = when (this) {
    is MirrorActionInvokeResult.Queued -> mapOf("status" to "queued", "invocationId" to invocationId)
    MirrorActionInvokeResult.Locked -> mapOf("status" to "locked", "invocationId" to null)
    MirrorActionInvokeResult.Gone -> mapOf("status" to "gone", "invocationId" to null)
    MirrorActionInvokeResult.InvalidReply -> mapOf("status" to "invalid_reply", "invocationId" to null)
    MirrorActionInvokeResult.Failed -> mapOf("status" to "failed", "invocationId" to null)
}

private fun String?.toImageDataUri(): String? =
    this?.takeIf(String::isNotBlank)?.let { "data:image/png;base64,$it" }
