package co.twinotify.core.pairing

import android.content.Context
import co.twinotify.core.crypto.CryptoStore
import co.twinotify.core.crypto.NonceSource
import co.twinotify.core.filter.AppFilterStore
import co.twinotify.core.pairing.lan.LanIdentityStore
import co.twinotify.core.storage.NotificationDb
import co.twinotify.core.storage.LanPairStore
import co.twinotify.core.storage.PeerStore
import co.twinotify.core.storage.ReplayGuard
import co.twinotify.core.service.ServiceConfigStore
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

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
        withContext(NonCancellable) {
            PeerStore.clear(ctx)
            AppFilterStore.clear(ctx)
            val db = NotificationDb.get(ctx)
            db.notificationMapDao().clearAll()
            db.notificationMapDao().sweepExpired(Long.MAX_VALUE)
            db.reliableDeliveryDao().clearReliableState()
            co.twinotify.core.actions.ProcessNotificationActionRegistry.registry.clear()
            db.outboundEventDao().clearAll()
            // The SyncService has already been cancelled and joined by UnpairWorkflow.
            // Delete LAN TLS material before rotating application identity.
            UnpairWipeOrder(
                clearLanBinding = { LanPairStore.clear(ctx) },
                deleteLanIdentity = { LanIdentityStore.delete() },
            ).beforeApplicationKeyRotation {
                CryptoStore.rotate(ctx)
            }
            // Rotate crypto state last (allows any in-flight decrypt to finish with old keys)
            NonceSource.regenerate(ctx)
            ReplayGuard.clear(ctx)
            ServiceConfigStore.clear(ctx)
            co.twinotify.core.metrics.MetricsStore.clear(ctx)
        }
    }
}

/** Local ordering boundary used by the full wipe; it has no lifecycle responsibilities. */
internal class UnpairWipeOrder(
    private val clearLanBinding: suspend () -> Unit = {},
    private val deleteLanIdentity: suspend () -> Unit,
) {
    suspend fun beforeApplicationKeyRotation(rotateApplicationKeys: suspend () -> Unit) {
        clearLanBinding()
        deleteLanIdentity()
        rotateApplicationKeys()
    }
}
