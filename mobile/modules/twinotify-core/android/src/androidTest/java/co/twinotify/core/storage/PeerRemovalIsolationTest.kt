package co.twinotify.core.storage

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import co.twinotify.core.crypto.CryptoStore
import co.twinotify.core.crypto.NonceSource
import co.twinotify.core.pairing.PeerRemovalManager
import java.nio.ByteBuffer
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.*

@RunWith(AndroidJUnit4::class)
class PeerRemovalIsolationTest {
    @Test fun unavailableRelayLeavesOnlySelectedPeerDisabledUntilExactPairRevocationSucceeds(): Unit = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        check(context.packageName == "co.twinotify.core.test")
        PeerStore.ensureImported(context)
        PeerStore.clear(context)
        val db = NotificationDb.get(context)
        db.reliableDeliveryDao().clearReliableState()
        val local = DeviceIdentity.getOrCreate(context)
        val pairId = "11111111-1111-4111-8111-111111111111"
        RevocationServer(listOf(503, 204)).use { relay ->
            val selected = PeerStore.save(context, PeerRecord("selected", ByteArray(32) { 1 }, ByteArray(32) { 2 },
                relayRevocationRequired = true, relayUrl = relay.url, relayPairId = pairId))
            val survivor = PeerStore.save(context, PeerRecord("survivor", ByteArray(32) { 3 }, ByteArray(32) { 4 },
                relayRevocationRequired = false))
            assertFalse(PeerRemovalManager.removeLocal(context, selected.peerLinkId))
            assertEquals("REMOVING", db.peerLinkDao().get(selected.peerLinkId)?.lifecycle)
            assertEquals(listOf(survivor.peerLinkId), PeerStore.list(context).map { it.peerLinkId })
            PeerRemovalManager.resume(context)
            assertNull(db.peerLinkDao().get(selected.peerLinkId))
            assertNotNull(PeerStore.load(context, survivor.peerLinkId))
            assertEquals(local, DeviceIdentity.getOrCreate(context))
            relay.assertSelectors(pairId)
        }
        PeerStore.clear(context)
    }

    @Test fun detachedRelayRetrySurvivesWithoutChangingTheActivePeer(): Unit = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        check(context.packageName == "co.twinotify.core.test")
        PeerStore.ensureImported(context)
        PeerStore.clear(context)
        val dao = NotificationDb.get(context).peerLinkDao()
        val local = DeviceIdentity.getOrCreate(context)
        val pairId = "22222222-2222-4222-8222-222222222222"
        RevocationServer(listOf(503, 204)).use { relay ->
            val peer = PeerStore.save(context, PeerRecord("detached", ByteArray(32) { 1 }, ByteArray(32) { 2 },
                relayRevocationRequired = true, relayUrl = relay.url, relayPairId = pairId))
            dao.detachRelay(peer.peerLinkId, local)
            assertFalse(PeerRemovalManager.retryDetachedRelays(context))
            assertEquals(pairId, dao.pendingRevocations().single().relayPairId)
            assertNull(PeerStore.load(context, peer.peerLinkId)?.relayUrl)
            assertEquals("ACTIVE", PeerStore.load(context, peer.peerLinkId)?.lifecycle)
            assertTrue(PeerRemovalManager.retryDetachedRelays(context))
            assertTrue(dao.pendingRevocations().isEmpty())
            assertNotNull(PeerStore.load(context, peer.peerLinkId))
            relay.assertSelectors(pairId)
        }
        PeerStore.clear(context)
    }

    @Test fun removingEitherAndThenLastPeerPreservesIdentityNonceSourceAndOtherCiphertext(): Unit = runBlocking {
        // This is the isolated instrumentation APK's storage, never the installed Twinotify app.
        val context = ApplicationProvider.getApplicationContext<Context>()
        check(context.packageName == "co.twinotify.core.test")
        val dao = NotificationDb.get(context).reliableDeliveryDao()
        PeerStore.ensureImported(context)
        PeerStore.clear(context)
        dao.clearReliableState()
        val local = DeviceIdentity.getOrCreate(context)
        val (box, sign) = CryptoStore.loadOrGenerate(context)
        val before = NonceSource.next(context)
        val phone = PeerStore.save(context, PeerRecord("phone", ByteArray(32) { 1 }, ByteArray(32) { 2 }, relayRevocationRequired = false))
        val mac = PeerStore.save(context, PeerRecord("mac", ByteArray(32) { 3 }, ByteArray(32) { 4 }, relayRevocationRequired = false))
        val source = CanonicalNotificationState("source", local, 1, "ACTIVE", "{}", 1, "source-key", null, null, false, 1)
        val survivor = OutboundMessage("survivor", "source", 1, "notif.post", 2, "exact ciphertext bytes", "digest", 22,
            1, Long.MAX_VALUE, null, null, 0, 1, "NEW", null, true, peerLinkId = mac.peerLinkId)
        dao.commitCapturedFanout(source, listOf(survivor.copy(msgId = "removed", peerLinkId = phone.peerLinkId), survivor))
        assertTrue(PeerRemovalManager.removeLocal(context, phone.peerLinkId))
        assertEquals(survivor, dao.outboundMessage("survivor"))
        assertNull(dao.outboundMessage("removed"))
        assertEquals(source, dao.canonical("source"))
        assertEquals(listOf(mac.peerLinkId), PeerStore.list(context).map { it.peerLinkId })
        assertTrue(PeerRemovalManager.removeLocal(context, mac.peerLinkId))
        assertTrue(PeerStore.list(context, includeRemoving = true).isEmpty())
        assertEquals(source, dao.canonical("source"))
        assertEquals(2L, dao.nextCaptureSequenceForEvent("source"))
        assertEquals(local, DeviceIdentity.getOrCreate(context))
        val afterKeys = CryptoStore.loadOrGenerate(context)
        assertContentEquals(box.secretKey, afterKeys.first.secretKey)
        assertContentEquals(sign.secretKey, afterKeys.second.secretKey)
        val after = NonceSource.next(context)
        assertContentEquals(before.copyOfRange(0, 16), after.copyOfRange(0, 16))
        assertTrue(ByteBuffer.wrap(after, 16, 8).long > ByteBuffer.wrap(before, 16, 8).long)
        dao.clearReliableState()
    }
}

/** A disposable loopback relay verifies persisted selectors across a failed HTTP attempt. */
private class RevocationServer(codes: List<Int>) : java.io.Closeable {
    private val socket = java.net.ServerSocket(0, 2, java.net.InetAddress.getByName("127.0.0.1"))
    val url = "http://127.0.0.1:${socket.localPort}"
    private val selectors = java.util.Collections.synchronizedList(mutableListOf<String>())
    private val failure = java.util.concurrent.atomic.AtomicReference<Throwable?>()
    private val worker = kotlin.concurrent.thread(name = "test-revocation-relay") {
        try {
            for (code in codes) socket.accept().use { client ->
                client.soTimeout = 5_000
                val reader = client.getInputStream().bufferedReader()
                check(reader.readLine() == "POST /pair/revoke HTTP/1.1")
                var authorization: String? = null
                while (true) {
                    val line = reader.readLine() ?: error("missing headers")
                    if (line.isEmpty()) break
                    if (line.startsWith("Authorization:", ignoreCase = true)) authorization = line.substringAfter(":").trim()
                }
                val jwt = checkNotNull(authorization).removePrefix("Bearer ")
                val payload = String(java.util.Base64.getUrlDecoder().decode(jwt.split('.')[1]), Charsets.UTF_8)
                selectors += org.json.JSONObject(payload).getString("pair_id")
                client.getOutputStream().write("HTTP/1.1 $code Test\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
                client.getOutputStream().flush()
            }
        } catch (error: Throwable) { failure.set(error) }
    }
    fun assertSelectors(pairId: String) {
        worker.join(5_000)
        assertFalse(worker.isAlive)
        failure.get()?.let { throw it }
        assertEquals(listOf(pairId, pairId), selectors.toList())
    }
    override fun close() { socket.close(); worker.join(5_000) }
}
