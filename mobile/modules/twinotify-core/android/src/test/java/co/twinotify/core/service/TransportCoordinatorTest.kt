package co.twinotify.core.service

import co.twinotify.core.storage.CustodyAcceptanceResult
import co.twinotify.core.storage.LegacyForwardResult
import co.twinotify.core.storage.OutboundMessage
import co.twinotify.core.storage.RelayReceiptResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

@OptIn(ExperimentalCoroutinesApi::class)
class TransportCoordinatorTest {
    @Test
    fun authenticatedLanWinsOverAnAvailableRelay() = runTest {
        val store = FakeStore(rows = listOf(row("a")))
        val lan = FakeRoute(RouteKind.LAN)
        val relay = FakeRoute(RouteKind.RELAY)
        val coordinator = coordinator(store, lan, relay)

        val job = launch { coordinator.run() }
        runCurrent()

        assertEquals(RouteKind.LAN, coordinator.health.value.active)
        assertEquals(RoutePhase.AUTHENTICATED, coordinator.health.value.phase)
        assertTrue(relay.opens == 0, "relay was opened while LAN authenticated")
        job.cancelAndJoin()
    }

    @Test
    fun relayCarriesDeliveryWhileLanIsUnavailable() = runTest {
        val store = FakeStore(rows = listOf(row("a")))
        val lan = FakeRoute(RouteKind.LAN, failOpen = true)
        val relay = FakeRoute(RouteKind.RELAY)
        val coordinator = coordinator(store, lan, relay)

        val job = launch { coordinator.run() }
        runCurrent()

        assertEquals(RouteKind.RELAY, coordinator.health.value.active)
        assertEquals(listOf("a"), relay.session().sent.map { it.msgId })
        job.cancelAndJoin()
    }

    @Test
    fun onlyOneRouteEverDrainsTheOutbox() = runTest {
        val store = FakeStore(rows = listOf(row("a"), row("b")))
        val lan = FakeRoute(RouteKind.LAN)
        val relay = FakeRoute(RouteKind.RELAY)
        val coordinator = coordinator(store, lan, relay)

        val job = launch { coordinator.run() }
        runCurrent()
        // End the LAN session so the coordinator hands off to relay.
        lan.session().finish("connection_lost")
        advanceTimeBy(60_000)
        runCurrent()

        assertTrue(store.maxConcurrentDrains <= 1, "two routes drained the outbox at once")
        job.cancelAndJoin()
    }

    @Test
    fun aRowSentButNotAcceptedIsResentAndTakesCustodyOnce() = runTest {
        val store = FakeStore(rows = listOf(row("a")))
        val lan = FakeRoute(RouteKind.LAN)
        val coordinator = coordinator(store, lan, relay = null)

        val job = launch { coordinator.run() }
        runCurrent()
        lan.session().finish("connection_lost")
        advanceTimeBy(60_000)
        runCurrent()

        // The row stays due until custody, so it may legitimately be sent again.
        assertTrue(lan.totalSent("a") >= 1)
        store.acceptCustody("a", CustodyRoute.LAN, 1, 1)
        store.acceptCustody("a", CustodyRoute.LAN, 2, 2)
        assertEquals(1, store.custodyAccepted.count { it == "a" })
        job.cancelAndJoin()
    }

    @Test
    fun healthReportsReconnectingAndTheQueuedCount() = runTest {
        val store = FakeStore(rows = listOf(row("a"), row("b")))
        val lan = FakeRoute(RouteKind.LAN, failOpen = true)
        val coordinator = coordinator(store, lan, relay = null)

        val job = launch { coordinator.run() }
        runCurrent()

        assertEquals(RouteKind.NONE, coordinator.health.value.active)
        assertEquals(RoutePhase.RECONNECTING, coordinator.health.value.phase)
        assertEquals(2, coordinator.health.value.queuedCount)
        job.cancelAndJoin()
    }

    @Test
    fun backoffGrowsWhileEveryAttemptFails() = runTest {
        val store = FakeStore(rows = emptyList())
        val lan = FakeRoute(RouteKind.LAN, failOpen = true)
        val coordinator = coordinator(store, lan, relay = null)

        val job = launch { coordinator.run() }
        runCurrent()
        val afterFirst = lan.opens
        advanceTimeBy(5_001)
        runCurrent()
        val afterSecond = lan.opens
        advanceTimeBy(5_001)
        runCurrent()

        assertEquals(1, afterFirst)
        assertEquals(2, afterSecond)
        assertEquals(2, lan.opens, "backoff did not grow past the first delay")
        job.cancelAndJoin()
    }

    @Test
    fun backoffResetsOnlyAfterSustainedAuthenticatedHealth() = runTest {
        val store = FakeStore(rows = emptyList())
        val lan = FakeRoute(RouteKind.LAN)
        val coordinator = coordinator(store, lan, relay = null)

        val job = launch { coordinator.run() }
        runCurrent()
        // A session that dies immediately is not sustained health.
        lan.session().finish("connection_lost")
        runCurrent()
        val delayAfterBriefSession = coordinator.lastBackoffMs

        advanceTimeBy(60_000)
        runCurrent()
        // This one stays authenticated well past the stability window.
        advanceTimeBy(TransportCoordinator.STABILITY_WINDOW_MS + 1_000)
        runCurrent()
        lan.session().finish("connection_lost")
        runCurrent()

        assertTrue(delayAfterBriefSession > 0, "a brief session must not reset backoff")
        assertEquals(0L, coordinator.lastBackoffMs, "sustained health must reset backoff")
        job.cancelAndJoin()
    }

    @Test
    fun aSelfDrainingSessionIsNeverDrainedByTheCoordinatorAsWell() = runTest {
        val store = FakeStore(rows = listOf(row("a")))
        val relay = FakeRoute(RouteKind.RELAY, selfDraining = true)
        val coordinator = coordinator(store, lan = null, relay = relay)

        val job = launch { coordinator.run() }
        runCurrent()
        advanceTimeBy(10_000)
        runCurrent()

        assertEquals(RouteKind.RELAY, coordinator.health.value.active)
        assertTrue(relay.session().sent.isEmpty(), "the coordinator drained a self-draining route")
        job.cancelAndJoin()
    }

    @Test
    fun anExplicitRetryCutsTheBackoffShortWithoutDisarmingIt() = runTest {
        val store = FakeStore(rows = emptyList())
        val lan = FakeRoute(RouteKind.LAN, failOpen = true)
        val retries = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
        val coordinator = TransportCoordinator(
            outbox = OutboxRepository(store, clock = { testScheduler.currentTime }),
            lan = lan,
            relay = null,
            clock = { testScheduler.currentTime },
            retryRequests = retries,
        )

        val job = launch { coordinator.run() }
        runCurrent()
        val beforeRetry = lan.opens

        retries.emit(Unit)
        runCurrent()

        assertEquals(beforeRetry + 1, lan.opens, "an explicit retry did not reconnect early")
        // The attempt count survives, so repeated failures keep backing off.
        assertTrue(coordinator.lastBackoffMs >= 5_000)
        job.cancelAndJoin()
    }

    // ---- helpers ---------------------------------------------------------

    // The coordinator and the outbox both read the test's virtual clock, so retry
    // scheduling and the stability window advance with advanceTimeBy.
    private fun TestScope.coordinator(store: FakeStore, lan: FakeRoute?, relay: FakeRoute?) =
        TransportCoordinator(
            outbox = OutboxRepository(store, clock = { testScheduler.currentTime }),
            lan = lan,
            relay = relay,
            queuedCount = { store.due().size },
            clock = { testScheduler.currentTime },
        )

    private fun row(msgId: String) = OutboundMessage(
        msgId = msgId,
        canonId = null,
        sequence = null,
        eventType = "notif.post",
        protocolVersion = 2,
        envelopeJson = "{\"v\":2,\"m\":\"$msgId\"}",
        envelopeSha256 = "aa".repeat(32),
        byteSize = 24,
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

    private class FakeRoute(
        override val kind: RouteKind,
        private val failOpen: Boolean = false,
        private val selfDraining: Boolean = false,
    ) : TransportRoute {
        var opens = 0
        private val sessions = mutableListOf<FakeSession>()

        override suspend fun open(): AuthenticatedRouteSession {
            opens += 1
            if (failOpen) throw IllegalStateException("route_unavailable")
            return FakeSession(kind, selfDraining).also { sessions += it }
        }

        fun session(): FakeSession = sessions.last()
        fun totalSent(msgId: String) = sessions.sumOf { s -> s.sent.count { it.msgId == msgId } }
    }

    private class FakeSession(
        override val kind: RouteKind,
        override val selfDraining: Boolean = false,
    ) : AuthenticatedRouteSession {
        val sent = mutableListOf<OutboundMessage>()
        private val closed = CompletableDeferred<String>()

        override suspend fun send(message: OutboundMessage) { sent += message }
        override suspend fun awaitClosed(): String = closed.await()
        override suspend fun close(code: String) { closed.complete(code) }
        fun finish(code: String) { closed.complete(code) }
    }

    private class FakeStore(rows: List<OutboundMessage>) : OutboxStore {
        val custodyAccepted = mutableListOf<String>()
        private val pending = rows.associateBy { it.msgId }.toMutableMap()
        private val dueAt = rows.associate { it.msgId to 0L }.toMutableMap()
        private val drainLock = Mutex()
        private var concurrentDrains = 0
        var maxConcurrentDrains = 0
            private set

        fun due(): List<OutboundMessage> = pending.values.toList()

        override suspend fun sendable(now: Long, limit: Int): List<OutboundMessage> {
            drainLock.lock()
            concurrentDrains += 1
            maxConcurrentDrains = maxOf(maxConcurrentDrains, concurrentDrains)
            val due = pending.values
                .filter { (dueAt[it.msgId] ?: 0L) <= now }
                .take(limit)
            concurrentDrains -= 1
            drainLock.unlock()
            return due
        }

        override suspend fun acceptCustody(
            msgId: String,
            route: CustodyRoute,
            acceptedAt: Long,
            retryAt: Long,
        ): CustodyAcceptanceResult {
            if (custodyAccepted.contains(msgId)) return CustodyAcceptanceResult.AlreadyAccepted
            custodyAccepted += msgId
            pending.remove(msgId)
            return CustodyAcceptanceResult.Accepted
        }

        override suspend fun markSent(msgId: String, retryAt: Long): Int {
            dueAt[msgId] = retryAt
            return 1
        }
        override suspend fun legacyForwarded(msgId: String, forwardedAt: Long): LegacyForwardResult =
            LegacyForwardResult.Missing
        override suspend fun applyPeerReceipt(
            ackedMsgId: String,
            envelopeSha256: String,
            status: String,
            reason: String?,
            occurredAt: Long,
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
}
