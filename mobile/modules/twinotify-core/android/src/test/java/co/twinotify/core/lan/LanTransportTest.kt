package co.twinotify.core.lan

import co.twinotify.core.service.CustodyRoute
import co.twinotify.core.service.InboundDispatchResult
import co.twinotify.core.service.OutboxRepository
import co.twinotify.core.service.OutboxStore
import co.twinotify.core.service.RelayAckRecord
import co.twinotify.core.service.RelayRejectionResult
import co.twinotify.core.protocol.EnvelopeAuthenticator
import co.twinotify.core.protocol.PayloadDecryptor
import co.twinotify.core.protocol.ProtocolException
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
import kotlinx.coroutines.yield

@OptIn(ExperimentalCoroutinesApi::class)
class LanTransportTest {
    @Test
    fun sendsTheExactStoredEnvelopeForOneDueRow() = runTest {
        val connection = FakeConnection()
        val transport = transport(connection)
        val collected = collectEvents(transport)

        transport.send(row("11111111-1111-4111-8111-111111111111", "{\"v\":2,\"body\":\"a\"}"))
        connection.closeSession()
        collected.await()


        val put = connection.written.filterIsInstance<LanFrame.Put>().single()
        assertEquals("{\"v\":2,\"body\":\"a\"}", put.envelope.decodeToString())
    }

    @Test
    fun acknowledgesInboundOnlyAfterDurableCommitReturns() = runTest {
        val commitGate = CompletableDeferred<Unit>()
        val connection = FakeConnection()
        val transport = LanTransport(
            connection = connection,
            outbox = outbox(FakeStore()),
            dispatch = {
                commitGate.await()
                InboundDispatchResult.Accepted(MSG_A, DIGEST_A)
            },
        )
        val collected = collectEvents(transport)

        connection.deliver(LanFrame.Put("{\"v\":2}".encodeToByteArray()))
        assertTrue(connection.written.isEmpty(), "acknowledged before the commit returned")

        commitGate.complete(Unit)
        connection.closeSession()
        val events = collected.await()

        assertEquals(LanFrame.Accepted(MSG_A, DIGEST_A), connection.written.single())
        assertTrue(events.contains(LanTransportEvent.Committed(MSG_A, duplicate = false)))
    }

    @Test
    fun postCustodyFinalizerRunsAfterAcceptanceWriteExactlyOnce() = runTest {
        val order = mutableListOf<String>()
        val connection = ObservingConnection { frame -> order += "write:${frame::class.simpleName}" }
        val result = InboundDispatchResult.AcceptedAfterCustody(MSG_A, DIGEST_A) { order += "finalize" }
        val transport = LanTransport(connection, outbox(FakeStore())) { result }
        val collected = collectEvents(transport)

        connection.deliver(LanFrame.Put("{}".encodeToByteArray()))
        connection.closeSession()
        collected.await()
        result.finalizeAfterCustody()

        assertEquals(listOf("write:Accepted", "finalize"), order)
    }

    @Test
    fun postCustodyFinalizerRunsNonCancellableWhenAcceptanceWriteFails() = runTest {
        var finalizers = 0
        val connection = ObservingConnection { throw IllegalStateException("write failed") }
        val transport = LanTransport(connection, outbox(FakeStore())) {
            InboundDispatchResult.AcceptedAfterCustody(MSG_A, DIGEST_A) { finalizers += 1 }
        }
        val collected = collectEvents(transport)

        connection.deliver(LanFrame.Put("{}".encodeToByteArray()))
        collected.await()

        assertEquals(1, finalizers)
        assertTrue(connection.written.none { it is LanFrame.Accepted })
    }

    @Test
    fun postCustodyFinalizerRunsWhenAcceptanceWriteIsCancelled() = runTest {
        val writeStarted = CompletableDeferred<Unit>()
        var finalizers = 0
        val connection = ObservingConnection { writeStarted.complete(Unit); awaitCancellation() }
        val transport = LanTransport(connection, outbox(FakeStore())) {
            InboundDispatchResult.AcceptedAfterCustody(MSG_A, DIGEST_A) { finalizers += 1 }
        }
        val collector = backgroundScope.launch { transport.run().toList() }

        connection.deliver(LanFrame.Put("{}".encodeToByteArray()))
        writeStarted.await()
        collector.cancel()
        collector.join()

        assertEquals(1, finalizers)
    }

    @Test
    fun durableDuplicateReplaysTheSameAcceptanceWithoutRematerializing() = runTest {
        var dispatches = 0
        val connection = FakeConnection()
        val transport = LanTransport(
            connection = connection,
            outbox = outbox(FakeStore()),
            dispatch = {
                dispatches += 1
                InboundDispatchResult.Duplicate(MSG_A, DIGEST_A)
            },
        )
        val collected = collectEvents(transport)

        connection.deliver(LanFrame.Put("{\"v\":2}".encodeToByteArray()))
        connection.closeSession()
        val events = collected.await()

        assertEquals(1, dispatches)
        assertEquals(LanFrame.Accepted(MSG_A, DIGEST_A), connection.written.single())
        assertTrue(events.contains(LanTransportEvent.Committed(MSG_A, duplicate = true)))
    }

    @Test
    fun digestConflictEmitsNoAcceptanceAndClosesWithAStableCode() = runTest {
        val connection = FakeConnection()
        val transport = LanTransport(
            connection = connection,
            outbox = outbox(FakeStore()),
            dispatch = { InboundDispatchResult.Rejected("id_conflict") },
        )
        val collected = collectEvents(transport)

        connection.deliver(LanFrame.Put("{\"v\":2}".encodeToByteArray()))
        val events = collected.await()

        assertTrue(connection.written.none { it is LanFrame.Accepted })
        assertEquals(LanFrame.Close("id_conflict"), connection.written.single())
        assertEquals(LanTransportEvent.Closed("id_conflict"), events.last())
    }

    @Test
    fun expiredAuthenticatedInboundEmitsNoAcceptanceAndCannotMaterialize() = runTest {
        var materialized = false
        val connection = FakeConnection()
        val outer = """{"v":2,"type":"enc","msg_id":"$MSG_A","origin_device":"dev-a","created_at":1000,"nonce":"AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA","ciphertext":"YQ=="}"""
        val inner = """{"v":2,"msg_id":"$MSG_A","origin_device":"dev-a","type":"notif.cancel","created_at":1000,"expires_at":2000,"canon_id":"canon-a","sequence":1,"payload":{}}"""
        val transport = LanTransport(connection, outbox(FakeStore())) { raw ->
            try {
                val opened = EnvelopeAuthenticator(
                    PayloadDecryptor { inner.encodeToByteArray() },
                    peerDeviceId = "dev-a",
                    clock = { 302_001L },
                ).open(raw)
                materialized = true
                InboundDispatchResult.Accepted(opened.inner.msgId, opened.envelopeSha256)
            } catch (_: ProtocolException) {
                InboundDispatchResult.Rejected("auth_failed")
            }
        }
        val collected = collectEvents(transport)

        connection.deliver(LanFrame.Put(outer.encodeToByteArray()))
        val events = collected.await()

        assertFalse(materialized)
        assertTrue(connection.written.none { it is LanFrame.Accepted })
        assertEquals(LanFrame.Close("auth_failed"), connection.written.single())
        assertEquals(LanTransportEvent.Closed("auth_failed"), events.last())
    }

    @Test
    fun ordinaryRowIsRetainedUnderRouteLanUntilPeerReceipt() = runTest {
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
    fun receiptRowIsDeletedAfterDirectAcceptance() = runTest {
        val store = FakeStore(acceptance = CustodyAcceptanceResult.DeletedReceipt)
        val connection = FakeConnection()
        val transport = transport(connection, store)
        val collected = collectEvents(transport)

        transport.send(row(MSG_A, "{\"v\":2}", digest = DIGEST_A, eventType = "peer.receipt"))
        connection.deliver(LanFrame.Accepted(MSG_A, DIGEST_A))
        connection.closeSession()
        val events = collected.await()

        assertEquals(listOf(MSG_A to CustodyRoute.LAN), store.accepted)
        assertTrue(events.contains(LanTransportEvent.PeerAccepted(MSG_A, "peer.receipt")))
    }

    @Test
    fun staleAcceptanceDoesNotFabricateAnEventType() = runTest {
        val connection = FakeConnection()
        val transport = transport(connection, FakeStore(acceptance = CustodyAcceptanceResult.Missing))
        val collected = collectEvents(transport)

        connection.deliver(LanFrame.Accepted(MSG_A, DIGEST_A))
        connection.closeSession()
        val events = collected.await()

        assertTrue(events.none { it is LanTransportEvent.PeerAccepted })
    }

    @Test
    fun acknowledgementForADifferentDigestTakesNoCustodyAndClosesTheSession() = runTest {
        val store = FakeStore()
        val connection = FakeConnection()
        val transport = transport(connection, store)
        val collected = collectEvents(transport)

        transport.send(row(MSG_A, "{\"v\":2}", digest = DIGEST_A))
        connection.deliver(LanFrame.Accepted(MSG_A, DIGEST_B))
        val events = collected.await()

        assertTrue(store.accepted.isEmpty(), "custody taken for a digest we never sent")
        assertEquals(LanTransportEvent.Closed("ack_digest_mismatch"), events.last())
    }

    @Test
    fun dispatchFailureRefusesRatherThanAcknowledging() = runTest {
        val connection = FakeConnection()
        val transport = LanTransport(
            connection = connection,
            outbox = outbox(FakeStore()),
            dispatch = { error("platform commit exploded") },
        )
        val collected = collectEvents(transport)

        connection.deliver(LanFrame.Put("{\"v\":2}".encodeToByteArray()))
        val events = collected.await()

        assertTrue(connection.written.none { it is LanFrame.Accepted })
        assertEquals(LanTransportEvent.Closed("dispatch_failed"), events.last())
    }

    @Test
    fun aBurstOfConcurrentSendsSerializesAndLosesNothing() = runTest {
        val connection = FakeConnection(blockWrites = true)
        val transport = transport(connection)
        val collected = collectEvents(transport)
        val burst = LanFrameLimits.MAX_BUFFERED_FRAMES + 3

        val senders = (0 until burst).map { index ->
            async { transport.send(row(uuid(index), "{\"v\":2,\"n\":$index}")) }
        }
        yield()
        assertTrue(senders.all { it.isActive }, "a send completed while the connection was blocked")

        connection.releaseWrites()
        senders.forEach { it.await() }
        connection.closeSession()
        collected.await()

        val puts = connection.written.filterIsInstance<LanFrame.Put>()
        assertEquals(burst, puts.size)
        assertEquals(burst, puts.map { it.envelope.decodeToString() }.toSet().size)
    }

    @Test
    fun pingIsAnsweredInOrderWithoutDisturbingCustody() = runTest {
        val store = FakeStore()
        val connection = FakeConnection()
        val transport = transport(connection, store)
        val collected = collectEvents(transport)

        connection.deliver(LanFrame.Ping(7))
        connection.closeSession()
        collected.await()

        assertEquals(LanFrame.Pong(7), connection.written.single())
        assertTrue(store.accepted.isEmpty())
    }

    @Test
    fun idleSessionSendsHeartbeatBeforeTheSocketReadDeadline() = runTest {
        val connection = FakeConnection()
        val transport = transport(connection)
        val collected = collectEvents(transport)
        runCurrent()

        advanceTimeBy(3_001)
        runCurrent()

        assertTrue(connection.written.single() is LanFrame.Ping)
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

    private fun uuid(index: Int) = "22222222-2222-4222-8222-%012d".format(index)

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
