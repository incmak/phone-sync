package co.twinotify.core.bluetooth

import co.twinotify.core.direct.DirectDeliveryEvent
import co.twinotify.core.service.CustodyRoute
import co.twinotify.core.service.InboundDispatchResult
import co.twinotify.core.service.OutboxRepository
import co.twinotify.core.service.OutboxStore
import co.twinotify.core.service.RelayAckRecord
import co.twinotify.core.service.RelayRejectionResult
import co.twinotify.core.service.RouteKind
import co.twinotify.core.storage.CustodyAcceptanceResult
import co.twinotify.core.storage.LegacyForwardResult
import co.twinotify.core.storage.OutboundMessage
import co.twinotify.core.storage.RelayReceiptResult
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.LinkedBlockingQueue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/**
 * Runs the real L2CAP wire over an in-memory stream pair. Real time is used on purpose:
 * the wire's blocking reads run on IO threads, and a virtual-time scheduler would fire the
 * wire's own deadlines while a test thread is legitimately blocked.
 */
class BluetoothTransportTest {
    @Test
    fun outboundRowIsWrittenAsOneExactBluetoothPutFrame() = runBlocking {
        val socket = FakeStreamSocket()
        val transport = transport(socket)
        val collected = async { transport.run().toList() }

        transport.send(row(MSG_A, "{\"v\":2,\"body\":\"a\"}"))
        socket.endStream()
        awaitWithin { collected.await() }

        assertEquals(listOf<BluetoothFrame>(BluetoothFrame.Put("{\"v\":2,\"body\":\"a\"}".encodeToByteArray())), socket.written())
    }

    @Test
    fun inboundAcceptedTakesBluetoothCustodyAndMapsPeerAccepted() = runBlocking {
        val store = FakeStore()
        val socket = FakeStreamSocket()
        val transport = transport(socket, store)
        val collected = async { transport.run().toList() }

        transport.send(row(MSG_A, "{\"v\":2}"))
        socket.deliver(BluetoothFrame.Accepted(MSG_A, DIGEST_A))
        awaitWithin { while (store.accepted.isEmpty()) delay(5) }
        socket.endStream()
        val events = awaitWithin { collected.await() }

        assertEquals(listOf(MSG_A to CustodyRoute.BLUETOOTH), store.accepted)
        assertTrue(events.contains(DirectDeliveryEvent.PeerAccepted(MSG_A, "notif.post")))
        assertEquals(DirectDeliveryEvent.Closed("peer_closed"), events.last())
    }

    @Test
    fun routeSessionIsBluetoothAndNotSelfDraining() = runBlocking {
        val socket = FakeStreamSocket()
        val route = route(socket)

        val session = route.open()

        assertEquals(RouteKind.BLUETOOTH, route.kind)
        assertEquals(RouteKind.BLUETOOTH, session.kind)
        assertFalse(session.selfDraining, "a Bluetooth session must leave outbox selection to the coordinator")
        session.close("test_complete")
    }

    @Test
    fun routeSessionCloseWritesBoundedCloseFrameClosesSocketAndCompletesOneStableCode() = runBlocking {
        val socket = FakeStreamSocket()
        val events = mutableListOf<DirectDeliveryEvent>()
        val session = route(socket, onEvent = { events += it }).open()

        session.close("route_promoted_to_lan")

        assertEquals("route_promoted_to_lan", awaitWithin { session.awaitClosed() })
        assertEquals(BluetoothFrame.Close("route_promoted_to_lan"), socket.written().last())
        assertTrue(socket.closed, "session close must close the L2CAP socket")
        val writesAfterFirstClose = socket.written().size

        session.close("second_close_ignored")

        assertEquals("route_promoted_to_lan", session.awaitClosed())
        assertEquals(writesAfterFirstClose, socket.written().size, "a second close wrote to the wire")
    }

    @Test
    fun authenticatedRouteSessionOutlivesTheTemporaryOpeningScope() = runBlocking {
        val socket = FakeStreamSocket()
        val route = route(socket)

        // If the worker were a child of this scope, coroutineScope could never return while
        // the reader blocks, and the coordinator could never close relay to grant Bluetooth.
        val session = awaitWithin { coroutineScope { route.open() } }

        session.close("test_complete")
        assertEquals("test_complete", session.awaitClosed())
    }

    @Test
    fun peerCloseEndsTheSessionWithThePeerCodeAndSurfacesIt() = runBlocking {
        val socket = FakeStreamSocket()
        val events = mutableListOf<DirectDeliveryEvent>()
        val session = route(socket, onEvent = { events += it }).open()

        socket.deliver(BluetoothFrame.Close("peer_going_away"))

        assertEquals("peer_going_away", awaitWithin { session.awaitClosed() })
        assertTrue(events.contains(DirectDeliveryEvent.Closed("peer_going_away")))
        session.close("late_close")
        assertEquals("peer_going_away", session.awaitClosed())
    }

    // ---- helpers ---------------------------------------------------------

    private suspend fun <T> awaitWithin(block: suspend () -> T): T = withTimeout(10_000L) { block() }

    private fun wire(socket: FakeStreamSocket) =
        AuthenticatedBluetoothWire(PEER, ByteArray(32) { it.toByte() }, BluetoothSocketWire(socket))

    private fun transport(socket: FakeStreamSocket, store: FakeStore = FakeStore()) = BluetoothTransport(
        wire = wire(socket),
        outbox = outbox(store),
        heartbeatIntervalMillis = 60_000L,
        dispatch = { InboundDispatchResult.Accepted(MSG_A, DIGEST_A) },
    )

    private fun route(
        socket: FakeStreamSocket,
        onEvent: suspend (DirectDeliveryEvent) -> Unit = {},
    ) = BluetoothRoute(
        connect = { wire(socket) },
        outbox = outbox(FakeStore()),
        dispatch = { InboundDispatchResult.Accepted(MSG_A, DIGEST_A) },
        onEvent = onEvent,
        heartbeatIntervalMillis = 60_000L,
    )

    private fun outbox(store: FakeStore) = OutboxRepository(store, clock = { 1_000L })

    private fun row(msgId: String, envelope: String, digest: String = DIGEST_A) = OutboundMessage(
        msgId = msgId,
        canonId = null,
        sequence = null,
        eventType = "notif.post",
        protocolVersion = 2,
        envelopeJson = envelope,
        envelopeSha256 = digest,
        byteSize = envelope.length.toLong(),
        createdAt = 0,
        expiresAt = Long.MAX_VALUE,
        custodyAcceptedAt = null,
        custodyRoute = null,
        attempts = 0,
        nextAttemptAt = 0,
        state = "NEW",
        lastError = null,
        requiresPeerReceipt = true,
    )

    /** Blocking byte source fed whole frames; close or end-of-stream releases a blocked read. */
    private class ChunkInputStream : InputStream() {
        private val chunks = LinkedBlockingQueue<ByteArray>()
        private var current = ByteArray(0)
        private var position = 0
        private var ended = false

        fun push(bytes: ByteArray) = chunks.put(bytes)
        fun end() = chunks.put(END)

        override fun read(): Int {
            while (position >= current.size) {
                if (ended) return -1
                val next = chunks.take()
                if (next === END) {
                    ended = true
                    return -1
                }
                current = next
                position = 0
            }
            return current[position++].toInt() and 0xff
        }

        override fun close() = end()

        private companion object {
            val END = ByteArray(0)
        }
    }

    private class FakeStreamSocket : BluetoothStreamSocket {
        private val input = ChunkInputStream()
        private val output = ByteArrayOutputStream()
        @Volatile
        var closed = false
            private set
        override val remoteAddress: String = "AA:BB:CC:DD:EE:FF"
        override val inputStream: InputStream = input
        override val outputStream: OutputStream = output

        fun deliver(frame: BluetoothFrame) = input.push(BluetoothFrameCodec.encode(frame))
        fun endStream() = input.end()

        fun written(): List<BluetoothFrame> {
            val bytes = output.toByteArray()
            val frames = mutableListOf<BluetoothFrame>()
            var offset = 0
            while (offset < bytes.size) {
                val prefix = bytes.copyOfRange(offset, offset + 4)
                val length = BluetoothFrameCodec.bodyLength(prefix, 1_064_996, BluetoothFrameFailure.FRAME_TOO_LARGE)
                frames += BluetoothFrameCodec.decode(bytes.copyOfRange(offset, offset + 4 + length))
                offset += 4 + length
            }
            return frames
        }

        override fun close() {
            closed = true
            input.end()
        }
    }

    private class FakeStore : OutboxStore {
        val accepted = mutableListOf<Pair<String, CustodyRoute>>()

        override suspend fun acceptCustody(
            msgId: String,
            route: CustodyRoute,
            acceptedAt: Long,
            retryAt: Long,
        ): CustodyAcceptanceResult {
            accepted += msgId to route
            return CustodyAcceptanceResult.Accepted
        }

        override suspend fun sendable(now: Long, limit: Int): List<OutboundMessage> = emptyList()
        override suspend fun markSent(msgId: String, retryAt: Long): Int = 1
        override suspend fun legacyForwarded(msgId: String, forwardedAt: Long): LegacyForwardResult =
            LegacyForwardResult.Missing
        override suspend fun applyPeerReceipt(
            ackedMsgId: String,
            envelopeSha256: String,
            status: String,
            reason: String?,
            occurredAt: Long,
            peerReceiptCreatedAt: Long?,
        ): RelayReceiptResult = RelayReceiptResult.Missing
        override suspend fun rejectRelay(
            msgId: String,
            reason: String,
            occurredAt: Long,
            retryAt: Long,
        ): RelayRejectionResult = RelayRejectionResult.Missing
        override suspend fun expireRelay(msgId: String, expiredAt: Long): RelayReceiptResult =
            RelayReceiptResult.Missing
        override suspend fun readyRelayAcks(limit: Int): List<RelayAckRecord> = emptyList()
        override suspend fun markRelayAckSent(msgId: String, envelopeSha256: String): Int = 0
    }

    private companion object {
        const val PEER = "dev-00000000-0000-0000-0000-000000000002"
        const val MSG_A = "33333333-3333-4333-8333-333333333333"
        const val DIGEST_A = "aa11bb22cc33dd44ee55ff6600778899aabbccddeeff00112233445566778899"
    }
}
