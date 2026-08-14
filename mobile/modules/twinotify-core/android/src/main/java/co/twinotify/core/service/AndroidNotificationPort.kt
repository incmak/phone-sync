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

interface AndroidNotificationPort {
    fun postMirror(state: CanonicalNotificationState): Boolean
    fun cancelMirror(localTag: String, localId: Int): Boolean
    fun cancelSource(notificationKey: String): Boolean
    fun postCallMirror(state: CanonicalNotificationState): Boolean = postMirror(state)
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
        if (state.originDevice == localDeviceId || state.state != "ACTIVE") return false
        val id = state.mirrorLocalId ?: return false
        val tag = state.mirrorLocalTag ?: return false
        val payload = state.desiredPayloadJson ?: return false
        if (!notificationsAvailable()) return false
        val post = runCatching { NotifPostJson.fromPayloadJson(payload) }.getOrNull() ?: return false
        if (post.canon_id != state.canonId) return false
        return runCatching {
            NotifChannelSetup.ensureChannels(appContext)
            NotificationManagerCompat.from(appContext).notify(
                tag,
                id,
                MirrorPoster.buildNotification(appContext, post, id),
            )
            true
        }.getOrDefault(false)
    }

    @SuppressLint("MissingPermission")
    override fun postCallMirror(state: CanonicalNotificationState): Boolean {
        if (state.originDevice == localDeviceId || state.state != "ACTIVE") return false
        val id = state.mirrorLocalId ?: return false
        val tag = state.mirrorLocalTag ?: return false
        if (!notificationsAvailable()) return false
        return runCatching {
            NotifChannelSetup.ensureChannels(appContext)
            NotificationManagerCompat.from(appContext).notify(tag, id, CallStateMaterializer.build(appContext, state, id))
            true
        }.getOrDefault(false)
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
        if (!NotificationManagerCompat.from(appContext).areNotificationsEnabled()) return false
        return ContextCompat.checkSelfPermission(
            appContext,
            Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED
    }
}
