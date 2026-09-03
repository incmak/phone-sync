package co.twinotify.core.pairing

import android.content.Context
import co.twinotify.core.bluetooth.BluetoothAssociations
import co.twinotify.core.bluetooth.BluetoothBindingStore
import co.twinotify.core.bluetooth.ProvisionalBluetoothAssociations
import co.twinotify.core.crypto.CryptoStore
import co.twinotify.core.crypto.NonceSource
import co.twinotify.core.filter.AppFilterStore
import co.twinotify.core.pairing.lan.LanIdentityStore
import co.twinotify.core.service.ServiceConfigStore
import co.twinotify.core.service.SyncServiceStatus
import co.twinotify.core.storage.LanPairStore
import co.twinotify.core.storage.NotificationDb
import co.twinotify.core.storage.PeerStore
import co.twinotify.core.storage.ReplayGuard
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/** Bounded health code: the local binding is gone but the system association may remain. */
internal const val BLUETOOTH_DISASSOCIATION_REQUIRED = "bluetooth_disassociation_required"

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
            // The SyncService has already been cancelled and joined by UnpairWorkflow, so every
            // granted route session is closed. Delete direct-route trust before rotating
            // application identity.
            UnpairWipeOrder(
                clearLanBinding = { LanPairStore.clear(ctx) },
                deleteLanIdentity = { LanIdentityStore.delete() },
                clearBluetoothBinding = { BluetoothUnpairOps.clearBindingAndDisassociate(ctx) },
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
    private val clearBluetoothBinding: suspend () -> Unit = {},
) {
    suspend fun beforeApplicationKeyRotation(rotateApplicationKeys: suspend () -> Unit) {
        clearLanBinding()
        deleteLanIdentity()
        clearBluetoothBinding()
        rotateApplicationKeys()
    }
}

/**
 * Bluetooth half of the wipe. The local binding is always cleared; the Companion Device
 * Manager association is removed only for the exact stored ID. A removal that fails or
 * cannot be confirmed is reported as [BLUETOOTH_DISASSOCIATION_REQUIRED] and never holds the
 * key wipe open or restores anything.
 */
internal object BluetoothUnpairOps {
    suspend fun clearBindingAndDisassociate(ctx: Context) {
        val store = BluetoothBindingStore.forContext(ctx)
        clearBindingAndDisassociate(
            storedAssociationId = { store.storedAssociationId() },
            clearBinding = {
                store.clear()
                ProvisionalBluetoothAssociations.clear()
            },
            disassociate = { id ->
                val manager = BluetoothAssociations.companionDeviceManager(ctx) ?: return@clearBindingAndDisassociate false
                manager.disassociate(id)
                manager.myAssociations.none { it.id == id }
            },
            report = SyncServiceStatus::setLastError,
        )
    }

    suspend fun clearBindingAndDisassociate(
        storedAssociationId: suspend () -> Int?,
        clearBinding: suspend () -> Unit,
        disassociate: suspend (Int) -> Boolean,
        report: (String) -> Unit,
    ) {
        val stored = try {
            Stored(storedAssociationId())
        } catch (error: CancellationException) {
            throw error
        } catch (_: Throwable) {
            null
        }
        clearBinding()
        val removed = when {
            stored == null -> false
            stored.id == null -> true
            else -> try {
                disassociate(stored.id)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Throwable) {
                false
            }
        }
        if (!removed) report(BLUETOOTH_DISASSOCIATION_REQUIRED)
    }

    private class Stored(val id: Int?)
}
