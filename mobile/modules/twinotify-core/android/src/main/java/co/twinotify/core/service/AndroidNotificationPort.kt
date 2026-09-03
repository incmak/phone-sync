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
import co.twinotify.core.storage.PeerStore
import co.twinotify.core.actions.ProcessMirrorAdvertisedActions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking

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

internal fun peerDisplayNameForCall(
    callOriginDevice: String,
    peerDeviceId: String?,
    peerDisplayName: String?,
): String? = peerDisplayName?.takeIf { peerDeviceId == callOriginDevice }

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
            val detailId = reliableDao?.notificationDetailForCanonNow(state.canonId)?.detailId
                ?: return@runCatching NotificationPostOutcome.RetryableFailure
            val invocations = reliableDao?.actionInvocationsForNotification(
                state.canonId,
                state.latestSequence,
            ).orEmpty()
            NotifChannelSetup.ensureChannels(appContext)
            ProcessMirrorAdvertisedActions.install(
                state.canonId,
                state.latestSequence,
                tag,
                id,
                post.actions,
            )
            NotificationManagerCompat.from(appContext).notify(
                tag,
                id,
                MirrorPoster.buildNotification(appContext, post, id, tag, invocations, detailId),
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
        val dao = reliableDao ?: return NotificationPostOutcome.RetryableFailure
        if (!notificationsAvailable()) return NotificationPostOutcome.PermissionBlocked
        return runCatching {
            val invocations = dao.actionInvocationsForNotification(state.canonId, state.latestSequence)
            val peerName = runBlocking(Dispatchers.IO) {
                val peer = PeerStore.load(appContext)
                peerDisplayNameForCall(state.originDevice, peer?.deviceId, peer?.displayName)
            }
            NotifChannelSetup.ensureChannels(appContext)
            val notification = CallStateMaterializer.build(appContext, state, id, invocations, peerName)
            // CallStyle is only admitted from a foreground service; the running sync service
            // owns that slot. Without it the desired state stays unapplied and retries later.
            val host = SyncService.callMirrorForegroundHost()
                ?: return@runCatching NotificationPostOutcome.RetryableFailure
            if (host.postCallMirror(id, notification)) {
                NotificationPostOutcome.Applied
            } else {
                NotificationPostOutcome.RetryableFailure
            }
        }.getOrDefault(NotificationPostOutcome.RetryableFailure)
    }

    override fun cancelCallMirror(localTag: String, localId: Int): Boolean {
        val host = SyncService.callMirrorForegroundHost()
        if (host != null) return host.cancelCallMirror(localId)
        // No live service: a foreground-slot notification cannot outlive the service that
        // posted it, so only a stale plain notification could remain.
        return runCatching {
            NotificationManagerCompat.from(appContext).cancel(localTag, localId)
            NotificationManagerCompat.from(appContext).cancel(localId)
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
        return effectivePostAvailability(appContext)
    }
}
