package co.twinotify.core.service

import co.twinotify.core.storage.OutboundMessage
import co.twinotify.core.storage.RelayAcceptanceResult
import co.twinotify.core.storage.RelayReceiptResult
import co.twinotify.core.storage.LegacyForwardResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.withTimeout

/** Control-lane ordering test: no relay ACK is legal before the receipt's relay acceptance. */
class RelayTransportTest {
    private val id = "11111111-1111-4111-8111-111111111111"
    private val digest = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"

    @Test
    fun relayAckWaitsUntilReceiptRelayAccepted() = runTest {
        val store = OrderStore()
        val repo = OutboxRepository(store, clock = { 1_000L })
        val socket = RecordingSocket()
        store.ready = RelayAckRecord(id, digest)

        // Before relay.accepted for the receipt, the control lane emits no original ACK.
        socket.sendAcks(repo.readyRelayAcks())
        assertFalse(socket.frames.isNotEmpty())

        store.receiptAccepted = true
        repo.onRelayAccepted("receipt-msg", 1_001L)
        socket.sendAcks(repo.readyRelayAcks())
        assertEquals(listOf("relay.ack:$id:$digest"), socket.frames)
        assertTrue(repo.markRelayAckSent(store.ready!!))
    }

    @Test
    fun floorOneUsesExplicitLegacyForwardAndRetiresOnlyAfterForwarded() = runTest {
        val store = LegacyStore()
        val endpoint = RelayUrlPolicy.parse("wss://relay.example/ws", debug = false).webSocket
        lateinit var listener: RelaySocketListener
        val socket = object : RelaySocket {
            val sent = mutableListOf<String>()
            override fun send(text: String): Boolean {
                sent += text
                val frame = RelayFrameCodec.decode(text)
                if (frame is RelayFrame.Put) {
                    listener.onText(RelayFrameCodec.encode(RelayFrame.LegacyForwarded(store.id)))
                    listener.onClosed("done")
                }
                return true
            }
            override fun close(code: Int, reason: String) = Unit
        }
        val connector = RelaySocketConnector { _, next ->
            listener = next
            next.onOpen(socket)
            next.onText(RelayFrameCodec.encode(RelayFrame.Capabilities(listOf(2, 1), listOf(1), 1)))
            socket
        }
        val events = withTimeout(5_000L) {
            RelayTransport(store.repo, connector = connector, reconnect = false).run(endpoint).toList()
        }
        assertTrue(events.any { it is TransportEvent.LegacyOnlineOnly })
        assertTrue(events.any { it is TransportEvent.LegacyForwarded })
        assertTrue(socket.sent.any { RelayFrameCodec.decode(it) is RelayFrame.Put })
        assertFalse(store.present)
    }

    private class RecordingSocket {
        val frames = mutableListOf<String>()
        fun sendAcks(acks: List<RelayAckRecord>) {
            acks.forEach { frames += "relay.ack:${it.msgId}:${it.envelopeSha256}" }
        }
    }

    private class OrderStore : OutboxStore {
        var ready: RelayAckRecord? = null
        var receiptAccepted = false
        override suspend fun sendable(now: Long, limit: Int) = emptyList<OutboundMessage>()
        override suspend fun markRelaySent(msgId: String, retryAt: Long) = 0
        override suspend fun legacyForwarded(msgId: String, forwardedAt: Long) = LegacyForwardResult.Missing
        override suspend fun acceptRelay(msgId: String, acceptedAt: Long, retryAt: Long) = RelayAcceptanceResult.Accepted
        override suspend fun applyPeerReceipt(ackedMsgId: String, envelopeSha256: String, status: String, reason: String?, occurredAt: Long) = RelayReceiptResult.Deleted
        override suspend fun rejectRelay(msgId: String, reason: String, occurredAt: Long, retryAt: Long) = RelayRejectionResult.Retained
        override suspend fun expireRelay(msgId: String, expiredAt: Long) = RelayReceiptResult.Deleted
        override suspend fun readyRelayAcks(limit: Int) = if (receiptAccepted) listOfNotNull(ready) else emptyList()
        override suspend fun markRelayAckSent(msgId: String, envelopeSha256: String) = 1
    }

    private class LegacyStore : OutboxStore {
        val id = "22222222-2222-4222-8222-222222222222"
        private val digest = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
        var present = true
        val repo = OutboxRepository(this, clock = { 1_000L })
        private val row = OutboundMessage(id, null, null, "enc", 1, "{\"v\":1,\"type\":\"enc\",\"msg_id\":\"$id\",\"origin_device\":\"dev-a\",\"ts\":1000,\"nonce\":\"AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA\",\"ciphertext\":\"Y2lwaGVydGV4dA==\"}", digest, 1, 1, 10_000, null, 0, 1, "NEW", null, true)
        override suspend fun sendable(now: Long, limit: Int) = if (present) listOf(row) else emptyList()
        override suspend fun markRelaySent(msgId: String, retryAt: Long) = 1
        override suspend fun legacyForwarded(msgId: String, forwardedAt: Long) = if (present) { present = false; LegacyForwardResult.Deleted } else LegacyForwardResult.Missing
        override suspend fun acceptRelay(msgId: String, acceptedAt: Long, retryAt: Long) = RelayAcceptanceResult.Accepted
        override suspend fun applyPeerReceipt(ackedMsgId: String, envelopeSha256: String, status: String, reason: String?, occurredAt: Long) = RelayReceiptResult.Deleted
        override suspend fun rejectRelay(msgId: String, reason: String, occurredAt: Long, retryAt: Long) = RelayRejectionResult.Retained
        override suspend fun expireRelay(msgId: String, expiredAt: Long) = RelayReceiptResult.Deleted
        override suspend fun readyRelayAcks(limit: Int) = emptyList<RelayAckRecord>()
        override suspend fun markRelayAckSent(msgId: String, envelopeSha256: String) = 0
    }
}
