package co.twinotify.core.service

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import co.twinotify.core.listener.NotificationListenerBridge
import co.twinotify.core.listener.NotifPostJson
import co.twinotify.core.call.CallStateMaterializer
import co.twinotify.core.storage.CanonicalNotificationState
import co.twinotify.core.storage.ReliableDeliveryDao

sealed interface NotificationPostOutcome {
    data object Applied : NotificationPostOutcome
    data object PermissionBlocked : NotificationPostOutcome
    data object RetryableFailure : NotificationPostOutcome
}

internal fun effectivePostAvailability(
    runtimePermissionGranted: Boolean,
    notificationsEnabled: Boolean,
): Boolean = runtimePermissionGranted && notificationsEnabled

/** Runtime permission and the system-wide notification switch must both allow posts. */
internal fun effectivePostAvailability(context: Context): Boolean {
    val appContext = context.applicationContext
    return effectivePostAvailability(
        runtimePermissionGranted = ContextCompat.checkSelfPermission(
            appContext,
            Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED,
        notificationsEnabled = NotificationManagerCompat.from(appContext).areNotificationsEnabled(),
    )
}

interface AndroidNotificationPort {
    fun postMirror(state: CanonicalNotificationState): Boolean
    fun postMirrorOutcome(state: CanonicalNotificationState): NotificationPostOutcome =
        if (postMirror(state)) NotificationPostOutcome.Applied else NotificationPostOutcome.RetryableFailure
    fun cancelMirror(localTag: String, localId: Int): Boolean
    fun cancelSource(notificationKey: String): Boolean
    fun postCallMirror(state: CanonicalNotificationState): Boolean = postMirror(state)
    fun postCallMirrorOutcome(state: CanonicalNotificationState): NotificationPostOutcome =
        if (postCallMirror(state)) NotificationPostOutcome.Applied else NotificationPostOutcome.RetryableFailure
    fun cancelCallMirror(localTag: String, localId: Int): Boolean = cancelMirror(localTag, localId)
}

/** Android side effects used by the durable materializer. */
class DefaultAndroidNotificationPort(
    context: Context,
    private val localDeviceId: String,
    private val reliableDao: ReliableDeliveryDao? = null,
) : AndroidNotificationPort {
    private val appContext = context.applicationContext

    @SuppressLint("MissingPermission")
    override fun postMirror(state: CanonicalNotificationState): Boolean {
        return postMirrorOutcome(state) == NotificationPostOutcome.Applied
    }

    @SuppressLint("MissingPermission")
    override fun postMirrorOutcome(state: CanonicalNotificationState): NotificationPostOutcome {
        if (state.originDevice == localDeviceId || state.state != "ACTIVE") return NotificationPostOutcome.RetryableFailure
        val id = state.mirrorLocalId ?: return NotificationPostOutcome.RetryableFailure
        val tag = state.mirrorLocalTag ?: return NotificationPostOutcome.RetryableFailure
        val payload = state.desiredPayloadJson ?: return NotificationPostOutcome.RetryableFailure
        if (!notificationsAvailable()) return NotificationPostOutcome.PermissionBlocked
        val post = runCatching { NotifPostJson.fromPayloadJson(payload) }.getOrNull()
            ?: return NotificationPostOutcome.RetryableFailure
        if (post.canon_id != state.canonId) return NotificationPostOutcome.RetryableFailure
        return runCatching {
            NotifChannelSetup.ensureChannels(appContext)
            NotificationManagerCompat.from(appContext).notify(
                tag,
                id,
                MirrorPoster.buildNotification(appContext, post, id),
            )
            NotificationPostOutcome.Applied
        }.getOrDefault(NotificationPostOutcome.RetryableFailure)
    }

    @SuppressLint("MissingPermission")
    override fun postCallMirror(state: CanonicalNotificationState): Boolean {
        return postCallMirrorOutcome(state) == NotificationPostOutcome.Applied
    }

    @SuppressLint("MissingPermission")
    override fun postCallMirrorOutcome(state: CanonicalNotificationState): NotificationPostOutcome {
        if (state.originDevice == localDeviceId || state.state != "ACTIVE") return NotificationPostOutcome.RetryableFailure
        val id = state.mirrorLocalId ?: return NotificationPostOutcome.RetryableFailure
        val tag = state.mirrorLocalTag ?: return NotificationPostOutcome.RetryableFailure
        if (!notificationsAvailable()) return NotificationPostOutcome.PermissionBlocked
        return runCatching {
            NotifChannelSetup.ensureChannels(appContext)
            NotificationManagerCompat.from(appContext).notify(tag, id, CallStateMaterializer.build(appContext, state, id))
            NotificationPostOutcome.Applied
        }.getOrDefault(NotificationPostOutcome.RetryableFailure)
    }

    override fun cancelMirror(localTag: String, localId: Int): Boolean {
        return runCatching {
            NotificationManagerCompat.from(appContext).cancel(localTag, localId)
            true
        }.getOrDefault(false)
    }

    override fun cancelSource(notificationKey: String): Boolean {
        if (notificationKey.isEmpty()) return false
        return NotificationListenerBridge.cancelSource(notificationKey)
    }

    private fun notificationsAvailable(): Boolean {
        return effectivePostAvailability(appContext)
    }
}
