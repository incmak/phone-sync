package co.twinotify.core.lan

import co.twinotify.core.service.CustodyRoute
import co.twinotify.core.service.InboundDispatchResult
import co.twinotify.core.service.OutboxRepository
import co.twinotify.core.service.OutboxStore
import co.twinotify.core.service.RelayAckRecord
import co.twinotify.core.service.RelayRejectionResult
import co.twinotify.core.storage.CustodyAcceptanceResult
import co.twinotify.core.storage.LegacyForwardResult
import co.twinotify.core.storage.OutboundMessage
import co.twinotify.core.storage.RelayReceiptResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.consumeAsFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.withContext

@OptIn(ExperimentalCoroutinesApi::class)
class LanTransportTest {
    // ---- LanTransport is a thin DirectWire adapter: prove exact LanFrame mapping ----

    @Test
    fun outboundRowIsWrittenAsOneExactLanPutFrame() = runTest {
        val connection = FakeConnection()
        val transport = transport(connection)
        val collected = collectEvents(transport)

        transport.send(row("11111111-1111-4111-8111-111111111111", "{\"v\":2,\"body\":\"a\"}"))
        connection.closeSession()
        collected.await()

        assertEquals(LanFrame.Put("{\"v\":2,\"body\":\"a\"}".encodeToByteArray()), connection.written.single())
    }

    @Test
    fun inboundPutIsCommittedAndAnsweredWithOneExactLanAcceptedFrame() = runTest {
        var dispatched: String? = null
        val connection = FakeConnection()
        val transport = LanTransport(connection, outbox(FakeStore())) { raw ->
            dispatched = raw
            InboundDispatchResult.Accepted(MSG_A, DIGEST_A)
        }
        val collected = collectEvents(transport)

        connection.deliver(LanFrame.Put("{\"v\":2}".encodeToByteArray()))
        connection.closeSession()
        val events = collected.await()

        assertEquals("{\"v\":2}", dispatched)
        assertEquals(LanFrame.Accepted(MSG_A, DIGEST_A), connection.written.single())
        assertEquals(
            listOf(LanTransportEvent.Committed(MSG_A, duplicate = false), LanTransportEvent.Closed("peer_closed")),
            events,
        )
    }

    @Test
    fun inboundAcceptedTakesLanCustodyAndMapsPeerAccepted() = runTest {
        val store = FakeStore()
        val connection = FakeConnection()
        val transport = transport(connection, store)
        val collected = collectEvents(transport)

        transport.send(row(MSG_A, "{\"v\":2}", digest = DIGEST_A))
        connection.deliver(LanFrame.Accepted(MSG_A, DIGEST_A))
        connection.closeSession()
        val events = collected.await()

        assertEquals(listOf(MSG_A to CustodyRoute.LAN), store.accepted)
        assertTrue(events.contains(LanTransportEvent.PeerAccepted(MSG_A, "notif.post")))
    }

    @Test
    fun mismatchedAcceptedDigestWritesOneExactLanCloseFrame() = runTest {
        val store = FakeStore()
        val connection = FakeConnection()
        val transport = transport(connection, store)
        val collected = collectEvents(transport)

        transport.send(row(MSG_A, "{\"v\":2}", digest = DIGEST_A))
        connection.deliver(LanFrame.Accepted(MSG_A, DIGEST_B))
        val events = collected.await()

        assertTrue(store.accepted.isEmpty(), "custody taken for a digest we never sent")
        assertEquals(LanFrame.Close("ack_digest_mismatch"), connection.written.last())
        assertEquals(LanTransportEvent.Closed("ack_digest_mismatch"), events.last())
    }

    @Test
    fun rejectedInboundWritesOneExactLanCloseFrame() = runTest {
        val connection = FakeConnection()
        val transport = LanTransport(connection, outbox(FakeStore())) { InboundDispatchResult.Rejected("id_conflict") }
        val collected = collectEvents(transport)

        connection.deliver(LanFrame.Put("{\"v\":2}".encodeToByteArray()))
        val events = collected.await()

        assertEquals(LanFrame.Close("id_conflict"), connection.written.single())
        assertEquals(LanTransportEvent.Closed("id_conflict"), events.last())
    }

    @Test
    fun lanPingIsAnsweredWithTheMatchingLanPong() = runTest {
        val connection = FakeConnection()
        val transport = transport(connection)
        val collected = collectEvents(transport)

        connection.deliver(LanFrame.Ping(7))
        connection.deliver(LanFrame.Pong(9))
        connection.closeSession()
        collected.await()

        assertEquals(LanFrame.Pong(7), connection.written.single())
    }

    @Test
    fun lanCloseEndsTheSessionWithThePeerCode() = runTest {
        val connection = FakeConnection()
        val transport = transport(connection)
        val collected = collectEvents(transport)

        connection.deliver(LanFrame.Close("peer_going_away"))

        assertEquals(listOf<LanTransportEvent>(LanTransportEvent.Closed("peer_going_away")), collected.await())
        assertTrue(connection.written.isEmpty())
    }

    @Test
    fun handshakeFramesAfterAuthenticationEndTheSessionWithoutAWrite() = runTest {
        listOf(LanFrame.Hello(ByteArray(4)), LanFrame.HelloAck(ByteArray(4))).forEach { frame ->
            val connection = FakeConnection()
            val transport = transport(connection)
            val collected = collectEvents(transport)

            connection.deliver(frame)

            assertEquals(
                listOf<LanTransportEvent>(LanTransportEvent.Closed("unexpected_handshake_frame")),
                collected.await(),
            )
            assertTrue(connection.written.isEmpty(), "answered a handshake frame")
        }
    }

    @Test
    fun closeWritesOneExactLanCloseFrame() = runTest {
        val connection = FakeConnection()
        val transport = transport(connection)

        transport.close("route_replaced")

        assertEquals(LanFrame.Close("route_replaced"), connection.written.single())
    }

    @Test
    fun heartbeatIsALanPingFrame() = runTest {
        val connection = FakeConnection()
        val transport = transport(connection)
        val collected = collectEvents(transport)
        runCurrent()

        advanceTimeBy(3_001)
        runCurrent()

        assertEquals(LanFrame.Ping(0), connection.written.single())
        connection.closeSession()
        collected.await()
    }

    @Test
    fun aSecondSessionOnOneTransportIsRefused() = runTest {
        val connection = FakeConnection()
        val transport = transport(connection)
        val first = collectEvents(transport)
        connection.closeSession()
        first.await()

        val second = transport.run().toList()

        assertEquals(listOf(LanTransportEvent.Closed("session_already_started")), second)
    }

    // ---- LanRoute --------------------------------------------------------

    @Test
    fun authenticatedRouteSessionOutlivesTheTemporaryOpeningScope() = runTest {
        val connection = FakeConnection()
        val route = LanRoute(
            connect = { connection },
            outbox = outbox(FakeStore()),
            dispatch = { InboundDispatchResult.Accepted(MSG_A, DIGEST_A) },
        )

        val opened = backgroundScope.async {
            coroutineScope { route.open() }
        }
        runCurrent()

        assertTrue(
            opened.isCompleted,
            "authenticated LAN worker kept the relay-promotion scope from returning",
        )
        opened.await().close("test_complete")
    }

    @Test
    fun lanGenerationJoinWaitsForActualRouteWorkerFinalizerBeforeReplacementOpens() = runTest {
        val workerStarted = CompletableDeferred<Unit>()
        val workerFinalizerEntered = CompletableDeferred<Unit>()
        val releaseWorkerFinalizer = CompletableDeferred<Unit>()
        val replacementOpened = CompletableDeferred<Unit>()
        val connection = FinalizingConnection(
            workerStarted,
            workerFinalizerEntered,
            releaseWorkerFinalizer,
        )
        val route = LanRoute(
            connect = { connection },
            outbox = outbox(FakeStore()),
            dispatch = { InboundDispatchResult.Accepted(MSG_A, DIGEST_A) },
        )
        val generation = backgroundScope.launch {
            val session = route.open()
            try {
                awaitCancellation()
            } finally {
                session.close("generation_replaced")
            }
        }
        workerStarted.await()
        val replacement = backgroundScope.launch {
            generation.join()
            replacementOpened.complete(Unit)
        }

        generation.cancel()
        runCurrent()
        try {
            assertTrue(workerFinalizerEntered.isCompleted)
            assertTrue(!generation.isCompleted, "generation joined before LAN worker finalized")
            assertTrue(!replacementOpened.isCompleted, "replacement opened beside old LAN worker")
        } finally {
            releaseWorkerFinalizer.complete(Unit)
        }
        generation.join()
        replacement.join()
        assertTrue(generation.isCompleted)
        assertTrue(replacementOpened.isCompleted)
        assertEquals(1, connection.closes)
    }

    @Test
    fun realRouteCloseCancellationPropagatesByIdentityBeforeReplacementCanOpen() = runTest {
        val cancellation = CancellationException("close_cancelled")
        val connection = ObservingConnection { frame ->
            if (frame is LanFrame.Close) throw cancellation
        }
        val route = LanRoute(
            connect = { connection },
            outbox = outbox(FakeStore()),
            dispatch = { InboundDispatchResult.Accepted(MSG_A, DIGEST_A) },
        )
        val session = route.open()
        runCurrent()
        var replacementOpened = false

        val observed = assertFailsWith<CancellationException> {
            session.close("route_failed")
            replacementOpened = true
        }

        assertSame(cancellation, observed)
        assertFalse(replacementOpened, "replacement opened after close cancellation was swallowed")
        assertEquals(1, connection.closes, "route worker was not fully joined before cancellation escaped")
    }

    // ---- helpers ---------------------------------------------------------

    private fun CoroutineScope.collectEvents(
        transport: LanTransport,
    ): Deferred<List<LanTransportEvent>> = async { transport.run().toList() }

    private fun transport(
        connection: FakeConnection,
        store: FakeStore = FakeStore(),
    ) = LanTransport(
        connection = connection,
        outbox = outbox(store),
        dispatch = { InboundDispatchResult.Accepted(MSG_A, DIGEST_A) },
    )

    private fun outbox(store: FakeStore) = OutboxRepository(store, clock = { 1_000L })

    private fun row(
        msgId: String,
        envelope: String,
        digest: String = DIGEST_A,
        eventType: String = "notif.post",
    ) = OutboundMessage(
        msgId = msgId,
        canonId = null,
        sequence = null,
        eventType = eventType,
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

    private class FakeConnection(blockWrites: Boolean = false) : AuthenticatedLanConnection {
        override val session = LanAuthenticatedSession(
            peerDeviceId = "dev-00000000-0000-0000-0000-000000000002",
            initiatorDeviceId = "dev-00000000-0000-0000-0000-000000000001",
            sessionId = ByteArray(32) { it.toByte() },
        )
        private val frames = Channel<LanFrame>(Channel.UNLIMITED)
        private val gate = CompletableDeferred<Unit>().also { if (!blockWrites) it.complete(Unit) }
        val written = mutableListOf<LanFrame>()

        override val incoming: Flow<LanFrame> = frames.consumeAsFlow()

        override suspend fun send(frame: LanFrame) {
            gate.await()
            written += frame
        }

        suspend fun deliver(frame: LanFrame) = frames.send(frame)
        fun releaseWrites() = gate.complete(Unit)
        fun closeSession() = frames.close()
        override fun close() { frames.close() }
    }

    private class ObservingConnection(
        private val onSend: suspend (LanFrame) -> Unit,
    ) : AuthenticatedLanConnection {
        override val session = LanAuthenticatedSession(
            peerDeviceId = "dev-00000000-0000-0000-0000-000000000002",
            initiatorDeviceId = "dev-00000000-0000-0000-0000-000000000001",
            sessionId = ByteArray(32),
        )
        private val frames = Channel<LanFrame>(Channel.UNLIMITED)
        val written = mutableListOf<LanFrame>()
        var closes = 0
        override val incoming: Flow<LanFrame> = frames.consumeAsFlow()
        override suspend fun send(frame: LanFrame) {
            onSend(frame)
            written += frame
        }
        override fun close() {
            closes += 1
            frames.close()
        }
        suspend fun deliver(frame: LanFrame) { frames.send(frame) }
        fun closeSession() { frames.close() }
    }

    private class FinalizingConnection(
        private val workerStarted: CompletableDeferred<Unit>,
        private val workerFinalizerEntered: CompletableDeferred<Unit>,
        private val releaseWorkerFinalizer: CompletableDeferred<Unit>,
    ) : AuthenticatedLanConnection {
        override val session = LanAuthenticatedSession(
            peerDeviceId = "dev-00000000-0000-0000-0000-000000000002",
            initiatorDeviceId = "dev-00000000-0000-0000-0000-000000000001",
            sessionId = ByteArray(32) { it.toByte() },
        )
        var closes = 0
        override val incoming: Flow<LanFrame> = flow {
            try {
                workerStarted.complete(Unit)
                awaitCancellation()
            } finally {
                withContext(NonCancellable) {
                    workerFinalizerEntered.complete(Unit)
                    releaseWorkerFinalizer.await()
                }
            }
        }
        override suspend fun send(frame: LanFrame) = Unit
        override fun close() { closes += 1 }
    }

    private class FakeStore(
        private val acceptance: CustodyAcceptanceResult = CustodyAcceptanceResult.Accepted,
    ) : OutboxStore {
        val accepted = mutableListOf<Pair<String, CustodyRoute>>()

        override suspend fun acceptCustody(
            msgId: String,
            route: CustodyRoute,
            acceptedAt: Long,
            retryAt: Long,
        ): CustodyAcceptanceResult {
            accepted += msgId to route
            return acceptance
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
        const val MSG_A = "33333333-3333-4333-8333-333333333333"
        const val DIGEST_A = "aa11bb22cc33dd44ee55ff6600778899aabbccddeeff00112233445566778899"
        const val DIGEST_B = "bb11bb22cc33dd44ee55ff6600778899aabbccddeeff00112233445566778899"
    }
}
