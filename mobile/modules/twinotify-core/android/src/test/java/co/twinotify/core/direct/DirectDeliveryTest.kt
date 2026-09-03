package co.twinotify.core.direct

import co.twinotify.core.lan.LanFrameLimits
import co.twinotify.core.protocol.EnvelopeAuthenticator
import co.twinotify.core.protocol.PayloadDecryptor
import co.twinotify.core.protocol.ProtocolException
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
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.consumeAsFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield

/**
 * Route-neutral direct-delivery invariants. Every LAN transport invariant lives here
 * against a fake [DirectWire]; the LAN adapter test only proves frame mapping.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DirectDeliveryTest {
    @Test
    fun sendsTheExactStoredEnvelopeForOneDueRow() = runTest {
        val wire = FakeWire()
        val delivery = delivery(wire)
        val collected = collectEvents(delivery)

        delivery.send(row("11111111-1111-4111-8111-111111111111", "{\"v\":2,\"body\":\"a\"}"))
        wire.closeSession()
        collected.await()

        val put = wire.written.filterIsInstance<DirectCommand.Put>().single()
        assertEquals("{\"v\":2,\"body\":\"a\"}", put.envelope.decodeToString())
    }

    @Test
    fun inboundIsDurablyCommittedBeforeAcceptedIsWritten() = runTest {
        val order = mutableListOf<String>()
        val wire = FakeWire { command -> if (command is DirectCommand.Accepted) order += "accepted" }
        val delivery = DirectDelivery(
            wire = wire,
            outbox = outbox(FakeStore()),
            custodyRoute = CustodyRoute.LAN,
            dispatch = {
                order += "committed"
                InboundDispatchResult.Accepted(MSG_A, DIGEST_A)
            },
        )
        val collected = collectEvents(delivery)

        wire.deliver(DirectCommand.Put("{\"v\":2}".encodeToByteArray()))
        wire.closeSession()
        collected.await()

        assertEquals(listOf("committed", "accepted"), order)
    }

    @Test
    fun acknowledgesInboundOnlyAfterDurableCommitReturns() = runTest {
        val commitGate = CompletableDeferred<Unit>()
        val wire = FakeWire()
        val delivery = DirectDelivery(
            wire = wire,
            outbox = outbox(FakeStore()),
            custodyRoute = CustodyRoute.LAN,
            dispatch = {
                commitGate.await()
                InboundDispatchResult.Accepted(MSG_A, DIGEST_A)
            },
        )
        val collected = collectEvents(delivery)

        wire.deliver(DirectCommand.Put("{\"v\":2}".encodeToByteArray()))
        assertTrue(wire.written.isEmpty(), "acknowledged before the commit returned")

        commitGate.complete(Unit)
        wire.closeSession()
        val events = collected.await()

        assertEquals(DirectCommand.Accepted(MSG_A, DIGEST_A), wire.written.single())
        assertTrue(events.contains(DirectDeliveryEvent.Committed(MSG_A, duplicate = false)))
    }

    @Test
    fun postCustodyFinalizerRunsAfterAcceptanceWriteExactlyOnce() = runTest {
        val order = mutableListOf<String>()
        val wire = FakeWire { command -> order += "write:${command::class.simpleName}" }
        val result = InboundDispatchResult.AcceptedAfterCustody(MSG_A, DIGEST_A) { order += "finalize" }
        val delivery = delivery(wire) { result }
        val collected = collectEvents(delivery)

        wire.deliver(DirectCommand.Put("{}".encodeToByteArray()))
        wire.closeSession()
        collected.await()
        result.finalizeAfterCustody()

        assertEquals(listOf("write:Accepted", "finalize"), order)
    }

    @Test
    fun postCustodyFinalizerRunsNonCancellableWhenAcceptanceWriteFails() = runTest {
        var finalizers = 0
        val wire = FakeWire { throw IllegalStateException("write failed") }
        val delivery = delivery(wire) {
            InboundDispatchResult.AcceptedAfterCustody(MSG_A, DIGEST_A) { finalizers += 1 }
        }
        val collected = collectEvents(delivery)

        wire.deliver(DirectCommand.Put("{}".encodeToByteArray()))
        collected.await()

        assertEquals(1, finalizers)
        assertTrue(wire.written.none { it is DirectCommand.Accepted })
    }

    @Test
    fun postCustodyFinalizerRunsWhenAcceptanceWriteIsCancelled() = runTest {
        val writeStarted = CompletableDeferred<Unit>()
        var finalizers = 0
        val wire = FakeWire { writeStarted.complete(Unit); awaitCancellation() }
        val delivery = delivery(wire) {
            InboundDispatchResult.AcceptedAfterCustody(MSG_A, DIGEST_A) { finalizers += 1 }
        }
        val collector = backgroundScope.launch { delivery.run().toList() }

        wire.deliver(DirectCommand.Put("{}".encodeToByteArray()))
        writeStarted.await()
        collector.cancel()
        collector.join()

        assertEquals(1, finalizers)
    }

    @Test
    fun durableDuplicateReplaysTheSameAcceptanceWithoutRematerializing() = runTest {
        var dispatches = 0
        val wire = FakeWire()
        val delivery = delivery(wire) {
            dispatches += 1
            InboundDispatchResult.Duplicate(MSG_A, DIGEST_A)
        }
        val collected = collectEvents(delivery)

        wire.deliver(DirectCommand.Put("{\"v\":2}".encodeToByteArray()))
        wire.closeSession()
        val events = collected.await()

        assertEquals(1, dispatches)
        assertEquals(DirectCommand.Accepted(MSG_A, DIGEST_A), wire.written.single())
        assertTrue(events.contains(DirectDeliveryEvent.Committed(MSG_A, duplicate = true)))
    }

    @Test
    fun digestConflictEmitsNoAcceptanceAndClosesWithAStableCode() = runTest {
        val wire = FakeWire()
        val delivery = delivery(wire) { InboundDispatchResult.Rejected("id_conflict") }
        val collected = collectEvents(delivery)

        wire.deliver(DirectCommand.Put("{\"v\":2}".encodeToByteArray()))
        val events = collected.await()

        assertTrue(wire.written.none { it is DirectCommand.Accepted })
        assertEquals(DirectCommand.Close("id_conflict"), wire.written.single())
        assertEquals(DirectDeliveryEvent.Closed("id_conflict"), events.last())
        assertEquals(1, wire.closes)
    }

    @Test
    fun expiredAuthenticatedInboundEmitsNoAcceptanceAndCannotMaterialize() = runTest {
        var materialized = false
        val wire = FakeWire()
        val outer = """{"v":2,"type":"enc","msg_id":"$MSG_A","origin_device":"dev-a","created_at":1000,"nonce":"AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA","ciphertext":"YQ=="}"""
        val inner = """{"v":2,"msg_id":"$MSG_A","origin_device":"dev-a","type":"notif.cancel","created_at":1000,"expires_at":2000,"canon_id":"canon-a","sequence":1,"payload":{}}"""
        val delivery = delivery(wire) { raw ->
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
        val collected = collectEvents(delivery)

        wire.deliver(DirectCommand.Put(outer.encodeToByteArray()))
        val events = collected.await()

        assertFalse(materialized)
        assertTrue(wire.written.none { it is DirectCommand.Accepted })
        assertEquals(DirectCommand.Close("auth_failed"), wire.written.single())
        assertEquals(DirectDeliveryEvent.Closed("auth_failed"), events.last())
    }

    @Test
    fun ordinaryRowIsRetainedUnderTheGrantedRouteUntilPeerReceipt() = runTest {
        val store = FakeStore()
        val wire = FakeWire()
        val delivery = delivery(wire, store)
        val collected = collectEvents(delivery)

        delivery.send(row(MSG_A, "{\"v\":2}", digest = DIGEST_A))
        wire.deliver(DirectCommand.Accepted(MSG_A, DIGEST_A))
        wire.closeSession()
        val events = collected.await()

        assertEquals(listOf(MSG_A to CustodyRoute.LAN), store.accepted)
        assertTrue(events.contains(DirectDeliveryEvent.PeerAccepted(MSG_A, "notif.post")))
    }

    @Test
    fun peerAcceptanceUsesTheGrantedDirectCustodyRoute() = runTest {
        val store = FakeStore()
        val delivery = DirectDelivery(FakeWire(), outbox(store), CustodyRoute.BLUETOOTH, dispatch = { error("unused") })

        delivery.recordSent(row(MSG_A, "{\"v\":2}", digest = DIGEST_A))
        val event = delivery.accept(DirectCommand.Accepted(MSG_A, DIGEST_A))

        assertEquals(listOf(MSG_A to CustodyRoute.BLUETOOTH), store.accepted)
        assertEquals(DirectDeliveryEvent.PeerAccepted(MSG_A, "notif.post"), event)
    }

    @Test
    fun bluetoothSessionRecordsBluetoothCustodyThroughTheOrderedProcessor() = runTest {
        val store = FakeStore()
        val wire = FakeWire()
        val delivery = DirectDelivery(wire, outbox(store), CustodyRoute.BLUETOOTH, dispatch = { error("unused") })
        val collected = collectEvents(delivery)

        delivery.send(row(MSG_A, "{\"v\":2}", digest = DIGEST_A))
        wire.deliver(DirectCommand.Accepted(MSG_A, DIGEST_A))
        wire.closeSession()
        val events = collected.await()

        assertEquals(listOf(MSG_A to CustodyRoute.BLUETOOTH), store.accepted)
        assertTrue(events.contains(DirectDeliveryEvent.PeerAccepted(MSG_A, "notif.post")))
    }

    @Test
    fun relayIsNotADirectCustodyRoute() {
        assertFailsWith<IllegalArgumentException> {
            DirectDelivery(FakeWire(), outbox(FakeStore()), CustodyRoute.RELAY, dispatch = { error("unused") })
        }
    }

    @Test
    fun receiptRowIsDeletedAfterDirectAcceptance() = runTest {
        val store = FakeStore(acceptance = CustodyAcceptanceResult.DeletedReceipt)
        val wire = FakeWire()
        val delivery = delivery(wire, store)
        val collected = collectEvents(delivery)

        delivery.send(row(MSG_A, "{\"v\":2}", digest = DIGEST_A, eventType = "peer.receipt"))
        wire.deliver(DirectCommand.Accepted(MSG_A, DIGEST_A))
        wire.closeSession()
        val events = collected.await()

        assertEquals(listOf(MSG_A to CustodyRoute.LAN), store.accepted)
        assertTrue(events.contains(DirectDeliveryEvent.PeerAccepted(MSG_A, "peer.receipt")))
    }

    @Test
    fun staleAcceptanceDoesNotFabricateAnEventType() = runTest {
        val wire = FakeWire()
        val delivery = delivery(wire, FakeStore(acceptance = CustodyAcceptanceResult.Missing))
        val collected = collectEvents(delivery)

        wire.deliver(DirectCommand.Accepted(MSG_A, DIGEST_A))
        wire.closeSession()
        val events = collected.await()

        assertTrue(events.none { it is DirectDeliveryEvent.PeerAccepted })
        assertNull(delivery.accept(DirectCommand.Accepted(MSG_A, DIGEST_A)))
    }

    @Test
    fun acknowledgementForADifferentDigestTakesNoCustodyAndClosesTheSession() = runTest {
        val store = FakeStore()
        val wire = FakeWire()
        val delivery = delivery(wire, store)
        val collected = collectEvents(delivery)

        delivery.send(row(MSG_A, "{\"v\":2}", digest = DIGEST_A))
        wire.deliver(DirectCommand.Accepted(MSG_A, DIGEST_B))
        val events = collected.await()

        assertTrue(store.accepted.isEmpty(), "custody taken for a digest we never sent")
        assertEquals(DirectCommand.Close("ack_digest_mismatch"), wire.written.last())
        assertEquals(DirectDeliveryEvent.Closed("ack_digest_mismatch"), events.last())
    }

    @Test
    fun mismatchedAcceptedDigestClosesWithoutTakingCustody() = runTest {
        val store = FakeStore()
        val wire = FakeWire()
        val delivery = delivery(wire, store)
        delivery.recordSent(row(MSG_A, "{\"v\":2}", digest = DIGEST_A))

        val event = delivery.accept(DirectCommand.Accepted(MSG_A, "b".repeat(64)))

        assertEquals(DirectDeliveryEvent.Closed("ack_digest_mismatch"), event)
        assertEquals(DirectCommand.Close("ack_digest_mismatch"), wire.written.single())
        assertTrue(store.accepted.isEmpty())
    }

    @Test
    fun dispatchFailureRefusesRatherThanAcknowledging() = runTest {
        val wire = FakeWire()
        val delivery = delivery(wire) { error("platform commit exploded") }
        val collected = collectEvents(delivery)

        wire.deliver(DirectCommand.Put("{\"v\":2}".encodeToByteArray()))
        val events = collected.await()

        assertTrue(wire.written.none { it is DirectCommand.Accepted })
        assertEquals(DirectCommand.Close("dispatch_failed"), wire.written.single())
        assertEquals(DirectDeliveryEvent.Closed("dispatch_failed"), events.last())
    }

    @Test
    fun aBurstOfConcurrentSendsSerializesAndLosesNothing() = runTest {
        val wire = FakeWire(blockWrites = true)
        val delivery = delivery(wire)
        val collected = collectEvents(delivery)
        val burst = LanFrameLimits.MAX_BUFFERED_FRAMES + 3

        val senders = (0 until burst).map { index ->
            async { delivery.send(row(uuid(index), "{\"v\":2,\"n\":$index}")) }
        }
        yield()
        assertTrue(senders.all { it.isActive }, "a send completed while the wire was blocked")

        wire.releaseWrites()
        senders.forEach { it.await() }
        wire.closeSession()
        collected.await()

        val puts = wire.written.filterIsInstance<DirectCommand.Put>()
        assertEquals(burst, puts.size)
        assertEquals(burst, puts.map { it.envelope.decodeToString() }.toSet().size)
    }

    @Test
    fun pingIsAnsweredInOrderWithoutDisturbingCustody() = runTest {
        val store = FakeStore()
        val wire = FakeWire()
        val delivery = delivery(wire, store)
        val collected = collectEvents(delivery)

        wire.deliver(DirectCommand.Ping(7))
        wire.deliver(DirectCommand.Pong(9))
        wire.closeSession()
        collected.await()

        assertEquals(DirectCommand.Pong(7), wire.written.single())
        assertTrue(store.accepted.isEmpty())
    }

    @Test
    fun peerCloseEndsTheSessionWithThePeerCodeAndClosesTheWire() = runTest {
        val wire = FakeWire()
        val delivery = delivery(wire)
        val collected = collectEvents(delivery)

        wire.deliver(DirectCommand.Close("peer_going_away"))
        val events = collected.await()

        assertEquals(listOf<DirectDeliveryEvent>(DirectDeliveryEvent.Closed("peer_going_away")), events)
        assertTrue(wire.written.isEmpty(), "answered a peer close with more frames")
        assertEquals(1, wire.closes)
    }

    @Test
    fun endOfIncomingReportsPeerClosed() = runTest {
        val wire = FakeWire()
        val delivery = delivery(wire)
        val collected = collectEvents(delivery)

        wire.closeSession()

        assertEquals(listOf<DirectDeliveryEvent>(DirectDeliveryEvent.Closed("peer_closed")), collected.await())
        assertEquals(1, wire.closes)
    }

    @Test
    fun brokenIncomingReportsConnectionLost() = runTest {
        val wire = FakeWire(incomingFailure = IllegalStateException("socket reset"))
        val delivery = delivery(wire)

        val events = delivery.run().toList()

        assertEquals(listOf<DirectDeliveryEvent>(DirectDeliveryEvent.Closed("connection_lost")), events)
        assertEquals(1, wire.closes)
    }

    @Test
    fun closeWritesTheCloseCommandToThePeer() = runTest {
        val wire = FakeWire()
        val delivery = delivery(wire)

        delivery.close("route_replaced")

        assertEquals(DirectCommand.Close("route_replaced"), wire.written.single())
    }

    @Test
    fun idleSessionSendsHeartbeatBeforeTheSocketReadDeadline() = runTest {
        val wire = FakeWire()
        val delivery = delivery(wire)
        val collected = collectEvents(delivery)
        runCurrent()

        advanceTimeBy(3_001)
        runCurrent()

        assertEquals(DirectCommand.Ping(0), wire.written.single())
        wire.closeSession()
        collected.await()
    }

    @Test
    fun heartbeatWriteFailureClosesTheWireSoTheSessionEnds() = runTest {
        val wire = FakeWire { command -> if (command is DirectCommand.Ping) throw IllegalStateException("write failed") }
        val delivery = delivery(wire)
        val collected = collectEvents(delivery)
        runCurrent()

        advanceTimeBy(3_001)
        runCurrent()

        assertTrue(wire.closes >= 1, "heartbeat failure left the wire open")
        val events = collected.await()
        assertTrue(events.last() is DirectDeliveryEvent.Closed)
    }

    @Test
    fun heartbeatIntervalMustBePositive() {
        assertFailsWith<IllegalArgumentException> {
            DirectDelivery(
                wire = FakeWire(),
                outbox = outbox(FakeStore()),
                custodyRoute = CustodyRoute.LAN,
                heartbeatIntervalMillis = 0,
                dispatch = { error("unused") },
            )
        }
    }

    @Test
    fun aSecondSessionOnOneDeliveryIsRefused() = runTest {
        val wire = FakeWire()
        val delivery = delivery(wire)
        val first = collectEvents(delivery)
        wire.closeSession()
        first.await()

        val second = delivery.run().toList()

        assertEquals(listOf<DirectDeliveryEvent>(DirectDeliveryEvent.Closed("session_already_started")), second)
    }

    @Test
    fun cancellationClosesTheWireNonCancellablyAndJoinsTheHeartbeat() = runTest {
        val workerStarted = CompletableDeferred<Unit>()
        val finalizerEntered = CompletableDeferred<Unit>()
        val releaseFinalizer = CompletableDeferred<Unit>()
        val wire = FinalizingWire(workerStarted, finalizerEntered, releaseFinalizer)
        val delivery = delivery(wire)
        val collector = backgroundScope.launch { delivery.run().toList() }
        workerStarted.await()

        collector.cancel()
        runCurrent()

        assertTrue(finalizerEntered.isCompleted)
        assertFalse(collector.isCompleted, "session completed before its incoming finalizer released")
        releaseFinalizer.complete(Unit)
        collector.join()
        assertEquals(1, wire.closes)
    }

    // ---- helpers ---------------------------------------------------------

    private fun CoroutineScope.collectEvents(
        delivery: DirectDelivery,
    ): Deferred<List<DirectDeliveryEvent>> = async { delivery.run().toList() }

    private fun delivery(
        wire: DirectWire,
        store: FakeStore = FakeStore(),
        dispatch: suspend (String) -> InboundDispatchResult = { InboundDispatchResult.Accepted(MSG_A, DIGEST_A) },
    ) = DirectDelivery(
        wire = wire,
        outbox = outbox(store),
        custodyRoute = CustodyRoute.LAN,
        dispatch = dispatch,
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

    private class FakeWire(
        blockWrites: Boolean = false,
        private val incomingFailure: Throwable? = null,
        private val onSend: suspend (DirectCommand) -> Unit = {},
    ) : DirectWire {
        override val peerDeviceId = "dev-00000000-0000-0000-0000-000000000002"
        private val commands = Channel<DirectCommand>(Channel.UNLIMITED)
        private val gate = CompletableDeferred<Unit>().also { if (!blockWrites) it.complete(Unit) }
        val written = mutableListOf<DirectCommand>()
        var closes = 0

        override val incoming: Flow<DirectCommand> = flow {
            incomingFailure?.let { throw it }
            commands.consumeAsFlow().collect { emit(it) }
        }

        override suspend fun send(command: DirectCommand) {
            gate.await()
            onSend(command)
            written += command
        }

        suspend fun deliver(command: DirectCommand) = commands.send(command)
        fun releaseWrites() = gate.complete(Unit)
        fun closeSession() = commands.close()
        override fun close() {
            closes += 1
            commands.close()
        }
    }

    private class FinalizingWire(
        private val workerStarted: CompletableDeferred<Unit>,
        private val finalizerEntered: CompletableDeferred<Unit>,
        private val releaseFinalizer: CompletableDeferred<Unit>,
    ) : DirectWire {
        override val peerDeviceId = "dev-00000000-0000-0000-0000-000000000002"
        var closes = 0
        override val incoming: Flow<DirectCommand> = flow {
            try {
                workerStarted.complete(Unit)
                awaitCancellation()
            } finally {
                withContext(NonCancellable) {
                    finalizerEntered.complete(Unit)
                    releaseFinalizer.await()
                }
            }
        }
        override suspend fun send(command: DirectCommand) = Unit
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
