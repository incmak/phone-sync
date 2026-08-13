package co.twinotify.core.service

import android.content.Context
import androidx.core.app.NotificationManagerCompat
import co.twinotify.core.listener.PendingPeerCancel
import co.twinotify.core.listener.NotificationListenerBridge
import co.twinotify.core.storage.CanonicalNotificationState
import co.twinotify.core.storage.NotificationDb

object MirrorDismisser {
    /** v2 exact cancellation using the canonical row's persisted mirror identity. */
    suspend fun dismiss(ctx: Context, state: CanonicalNotificationState): Boolean {
        val localId = state.mirrorLocalId ?: return true
        val localTag = state.mirrorLocalTag ?: return true
        PendingPeerCancel.add(state.canonId)
        NotificationDb.get(ctx).reliableDeliveryDao().markPeerCancelPending(state.canonId)
        return runCatching {
            NotificationManagerCompat.from(ctx).cancel(localTag, localId)
            true
        }.getOrDefault(false)
    }

    /** v2 source cancellation always uses the exact listener key, never package/id matching. */
    fun dismissSource(notificationKey: String): Boolean =
        NotificationListenerBridge.cancelSource(notificationKey)

    suspend fun dismiss(ctx: Context, canonId: String) {
        val dao = NotificationDb.get(ctx).notificationMapDao()
        val local = dao.lookupLocalByCanonId(canonId) ?: return
        // Tombstone BEFORE cancel — prevents the listener's onNotificationRemoved from
        // emitting a spurious cancel back to the origin device. Order is critical.
        PendingPeerCancel.add(canonId)
        NotificationManagerCompat.from(ctx).cancel(local.localTag, local.localId)
        // deleteByCanonId will happen in the listener's own-pkg branch after the cancel echoes back.
    }
}
