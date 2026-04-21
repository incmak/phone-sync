package co.twinotify.core.service

import android.content.Context
import androidx.core.app.NotificationManagerCompat
import co.twinotify.core.listener.PendingPeerCancel
import co.twinotify.core.storage.NotificationDb

object MirrorDismisser {
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
