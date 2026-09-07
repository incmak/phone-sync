package co.twinotify.core.pairing

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import co.twinotify.core.BuildConfig
import co.twinotify.core.auth.JwtMinter
import co.twinotify.core.bluetooth.BluetoothAssociations
import co.twinotify.core.bluetooth.BluetoothBindingStore
import co.twinotify.core.crypto.CryptoStore
import co.twinotify.core.protocol.InnerEventV2
import co.twinotify.core.service.DurablePeerControlSealer
import co.twinotify.core.service.MirrorDismisser
import co.twinotify.core.service.ProcessMaterializationPassCoordinator
import co.twinotify.core.service.SyncService
import co.twinotify.core.service.SyncServiceStatus
import co.twinotify.core.storage.DeviceIdentity
import co.twinotify.core.storage.LanPairStore
import co.twinotify.core.storage.NotificationDb
import co.twinotify.core.storage.OutboundCapacityException
import co.twinotify.core.storage.PeerStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.util.UUID

/** REMOVING survives process death; identity and the global nonce allocator never participate. */
internal object PeerRemovalManager {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val requests = mutableMapOf<String, Deferred<Boolean>>()

    /** True means cleanup is complete; false leaves the disabled peer available for retry. */
    suspend fun removeLocal(context: Context, link: String): Boolean = request(context.applicationContext, link, notifyPeer = true).await()

    suspend fun beginIncoming(context: Context, link: String) {
        NotificationDb.get(context).reliableDeliveryDao().beginPeerRemoval(link)
    }

    fun finishIncoming(context: Context, link: String) {
        request(context.applicationContext, link, notifyPeer = false)
    }

    fun resumeInBackground(context: Context) { scope.launch { resume(context.applicationContext) } }

    suspend fun resume(context: Context) {
        retryDetachedRelays(context)
        PeerStore.list(context, includeRemoving = true).filter { it.lifecycle == "REMOVING" }
            .map { request(context.applicationContext, it.peerLinkId, notifyPeer = false) }.awaitAll()
    }

    private val detachedRetryMutex = kotlinx.coroutines.sync.Mutex()

    suspend fun retryDetachedRelays(context: Context): Boolean {
        detachedRetryMutex.lock()
        try {
            val dao = NotificationDb.get(context).peerLinkDao()
            var complete = true
            for (pending in dao.pendingRevocations()) {
                try {
                    val local = DeviceIdentity.getOrCreate(context)
                    check(local == pending.localDeviceId) { "revocation_identity_changed" }
                    val sign = CryptoStore.loadOrGenerate(context).second
                    PairProtocol.revoke(pending.relayUrl, JwtMinter.mint(local, sign.secretKey, pairId = pending.relayPairId),
                        debug = BuildConfig.DEBUG, revocationMarkerPresent = true)
                    dao.finishRevocation(pending.revocationId)
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Throwable) { complete = false }
            }
            if (!complete) scheduleRetry(context)
            return complete
        } finally { detachedRetryMutex.unlock() }
    }

    @Synchronized
    private fun request(context: Context, link: String, notifyPeer: Boolean): Deferred<Boolean> {
        requests[link]?.takeUnless { it.isCompleted }?.let { return it }
        val job = scope.async(start = CoroutineStart.LAZY) { execute(context, link, notifyPeer) }
        requests[link] = job
        job.invokeOnCompletion { synchronized(this) { if (requests[link] === job) requests.remove(link) } }
        job.start()
        return job
    }

    private suspend fun execute(context: Context, link: String, notifyPeer: Boolean): Boolean {
        val db = NotificationDb.get(context)
        val dao = db.reliableDeliveryDao()
        val peer = PeerStore.list(context, includeRemoving = true).singleOrNull { it.peerLinkId == link } ?: return true
        if (peer.lifecycle == "ACTIVE") {
            val control = if (notifyPeer) try {
                val now = System.currentTimeMillis().coerceAtLeast(0L)
                DurablePeerControlSealer(context, link).seal(InnerEventV2(
                    msgId = UUID.randomUUID().toString(), originDevice = DeviceIdentity.getOrCreate(context), type = "unpair",
                    canonId = null, sequence = null, createdAt = now, expiresAt = now + 86_400_000L,
                    payloadJson = JSONObject(mapOf("reason" to "local_user")).toString(),
                ), requiresPeerReceipt = false)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Throwable) { null } else null
            val reservation = control?.let { SyncService.reservePeerRemovalCustody(link, it.msgId) }
            try {
                try { dao.beginPeerRemoval(link, control) }
                catch (_: OutboundCapacityException) { dao.beginPeerRemoval(link) }
                // After the marker commits, only the unpair control is eligible for this drainer.
                val custody = if (control != null && dao.outboundMessage(control.msgId) != null) reservation?.await(5_000L) else null
                if (notifyPeer) LocalUnpairStatus.record(when (custody) {
                    co.twinotify.core.service.CustodyRoute.LAN -> LocalUnpairCustodyOutcome.LAN
                    co.twinotify.core.service.CustodyRoute.BLUETOOTH -> LocalUnpairCustodyOutcome.BLUETOOTH
                    co.twinotify.core.service.CustodyRoute.RELAY -> LocalUnpairCustodyOutcome.RELAY
                    null -> if (reservation == null) LocalUnpairCustodyOutcome.UNAVAILABLE else LocalUnpairCustodyOutcome.TIMEOUT
                })
            } finally { reservation?.close() }
        }
        SyncService.stopPeerAndAwait(link)
        var clean = true
        try {
            ProcessMaterializationPassCoordinator.serialize {
                for (state in dao.mirrorStatesForPeer(link)) {
                    val removed = if (state.canonId.startsWith("call:") && state.mirrorLocalTag != null && state.mirrorLocalId != null) {
                        co.twinotify.core.service.DefaultAndroidNotificationPort(context, DeviceIdentity.getOrCreate(context), dao)
                            .cancelCallMirror(state.mirrorLocalTag, state.mirrorLocalId)
                    } else MirrorDismisser.dismiss(context, state)
                    check(removed) { "mirror_removal_failed" }
                    db.notificationMapDao().deleteByCanonId(state.canonId)
                }
                dao.purgePeerDelivery(link)
            }
            peer.lanBindingId?.let { LanPairStore.clearBinding(context, it) }
            val bluetooth = BluetoothBindingStore.forContext(context, link)
            val association = bluetooth.prepareRemoval(peer)
            if (association != null) {
                val manager = checkNotNull(BluetoothAssociations.companionDeviceManager(context)) { "bluetooth_manager_unavailable" }
                if (manager.myAssociations.any { it.id == association }) manager.disassociate(association)
                check(manager.myAssociations.none { it.id == association }) { "bluetooth_disassociation_required" }
            }
            bluetooth.clear()
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Throwable) { clean = false }

        val current = db.peerLinkDao().get(link) ?: return true
        if (current.relayRevocationRequired) {
            try {
                val url = checkNotNull(current.relayUrl) { "missing_relay_url" }
                val sign = CryptoStore.loadOrGenerate(context).second
                PairProtocol.revoke(url, JwtMinter.mint(DeviceIdentity.getOrCreate(context), sign.secretKey,
                    pairId = current.relayPairId), debug = BuildConfig.DEBUG, revocationMarkerPresent = true)
                db.peerLinkDao().relayRevoked(link)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Throwable) { clean = false }
        }
        if (clean) {
            db.peerLinkDao().finishRemoval(link)
            SyncServiceStatus.notifyPeerUnpaired()
        } else scheduleRetry(context)
        // A prior relay-only detach has its own selector and survives deletion of the peer row.
        retryDetachedRelays(context)
        return clean
    }

    private fun scheduleRetry(context: Context) {
        val alarm = context.getSystemService(AlarmManager::class.java) ?: return
        val intent = Intent(context, PeerRemovalRetryReceiver::class.java)
        val pending = PendingIntent.getBroadcast(context, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        alarm.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, System.currentTimeMillis() + 60_000L, pending)
    }
}

class PeerRemovalRetryReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val result = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try { PeerRemovalManager.resume(context.applicationContext) }
            finally { result.finish() }
        }
    }
}
