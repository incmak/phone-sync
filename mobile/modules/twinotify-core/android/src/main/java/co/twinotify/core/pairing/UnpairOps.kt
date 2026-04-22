package co.twinotify.core.pairing

import android.content.Context
import co.twinotify.core.crypto.CryptoStore
import co.twinotify.core.crypto.NonceSource
import co.twinotify.core.filter.AppFilterStore
import co.twinotify.core.storage.NotificationDb
import co.twinotify.core.storage.PeerStore
import co.twinotify.core.storage.ReplayGuard

/**
 * Atomic (in intent) reset of all paired state. Called by both sides of the unpair flow:
 *   - local user taps Unpair → TwinotifyCoreModule.unpair() calls this after flushing outbound.
 *   - peer sent `type:"unpair"` packet → InboundDispatcher calls this immediately.
 *
 * Order matters: wipe keys LAST so any in-flight decrypt on the background thread has a chance to
 * complete with the old keys. This is best-effort — a concurrent decrypt race is harmless since
 * the peer record is gone and MirrorPoster short-circuits on no peer.
 */
object UnpairOps {
    suspend fun wipeAll(ctx: Context) {
        PeerStore.clear(ctx)
        AppFilterStore.clear(ctx)
        val db = NotificationDb.get(ctx)
        db.notificationMapDao().clearAll()
        db.outboundEventDao().clearAll()
        // Rotate crypto state last (allows any in-flight decrypt to finish with old keys)
        CryptoStore.rotate(ctx)
        NonceSource.regenerate(ctx)
        ReplayGuard.clear(ctx)
        co.twinotify.core.metrics.MetricsStore.clear(ctx)
    }
}
