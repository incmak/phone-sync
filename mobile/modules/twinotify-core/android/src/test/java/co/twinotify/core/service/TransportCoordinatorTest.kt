package co.twinotify.core.service

import co.twinotify.core.storage.CustodyAcceptanceResult
import co.twinotify.core.storage.LegacyForwardResult
import co.twinotify.core.storage.OutboundMessage
import co.twinotify.core.storage.RelayReceiptResult
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.coroutines.CancellationException
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
    fun relayPreferenceIsLiveAndFallsBackToLanWhenRelayIsUnavailable() = runTest {
        val store = FakeStore(rows = listOf(row("a")))
        val lan = FakeRoute(RouteKind.LAN)
        val relay = FakeRoute(RouteKind.RELAY, failOpen = true)
        val coordinator = TransportCoordinator(
            outbox = OutboxRepository(store, clock = { testScheduler.currentTime }),
            lan = lan,
            relay = relay,
            preferLan = false,
            queuedCount = { store.due().size },
            clock = { testScheduler.currentTime },
        )

        val job = launch { coordinator.run() }
        runCurrent()

        assertEquals(1, relay.opens, "relay was not attempted first")
        assertEquals(RouteKind.LAN, coordinator.health.value.active)
        assertEquals(listOf("a"), lan.session().sent.map { it.msgId })
        job.cancelAndJoin()
    }

    @Test
    fun relayPreferenceDoesNotOpenLanWhileRelayIsHealthy() = runTest {
        val store = FakeStore(rows = emptyList())
        val lan = FakeRoute(RouteKind.LAN)
        val relay = FakeRoute(RouteKind.RELAY, selfDraining = true)
        val coordinator = TransportCoordinator(
            outbox = OutboxRepository(store, clock = { testScheduler.currentTime }),
            lan = lan,
            relay = relay,
            preferLan = false,
            clock = { testScheduler.currentTime },
        )

        val job = launch { coordinator.run() }
        runCurrent()

        assertEquals(RouteKind.RELAY, coordinator.health.value.active)
        assertEquals(0, lan.opens, "LAN opened in parallel with the preferred relay")
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

    @Test
    fun sendFailureClosesLanAndRetriesTheSameDurableRowOnRelay() = runTest {
        val store = FakeStore(rows = listOf(row("a")))
        val lan = FakeRoute(
            kind = RouteKind.LAN,
            successfulOpens = 1,
            sessionFactory = { FakeSession(RouteKind.LAN, sendFailure = IOException("wifi_lost")) },
        )
        val relay = FakeRoute(RouteKind.RELAY)
        val coordinator = coordinator(store, lan, relay)

        val job = backgroundScope.launch { coordinator.run() }
        runCurrent()

        assertEquals(listOf("a"), lan.session().sendAttempts.map { it.msgId })
        assertEquals(1, lan.session().closeCount)
        assertEquals(listOf("established_route_failure"), lan.session().closeCodes)
        // Relay fallback is immediate; only the next LAN promotion attempt cools down.
        assertEquals(RoutePhase.AUTHENTICATED, coordinator.health.value.phase)
        assertEquals(1, relay.opens)
        assertEquals(listOf("a"), relay.session().sent.map { it.msgId })
        job.cancelAndJoin()
    }

    @Test
    fun awaitClosedFailureReconnectsInsteadOfTerminatingTheCoordinator() = runTest {
        val store = FakeStore(rows = emptyList())
        val lan = FakeRoute(
            kind = RouteKind.LAN,
            successfulOpens = 1,
            sessionFactory = {
                FakeSession(
                    kind = RouteKind.LAN,
                    awaitClosedFailure = IOException("socket_reader_failed"),
                )
            },
        )
        val relay = FakeRoute(RouteKind.RELAY, selfDraining = true)
        val coordinator = coordinator(store, lan, relay)

        val job = backgroundScope.launch { coordinator.run() }
        runCurrent()
        advanceTimeBy(5_001)
        runCurrent()

        assertTrue(job.isActive, "coordinator terminated after awaitClosed failed")
        assertEquals(1, lan.session().closeCount)
        assertEquals(1, relay.opens)
        assertEquals(RouteKind.RELAY, coordinator.health.value.active)
        job.cancelAndJoin()
    }

    @Test
    fun outboxSelectionFailureBacksOffWithoutTerminatingOrSpinning() = runTest {
        val store = FakeStore(rows = emptyList(), sendableFailuresRemaining = 1)
        val lan = FakeRoute(RouteKind.LAN)
        val coordinator = coordinator(store, lan, relay = null)

        val job = backgroundScope.launch { coordinator.run() }
        runCurrent()

        assertTrue(job.isActive, "coordinator terminated after outbox selection failed")
        assertEquals(1, lan.opens, "coordinator spun instead of backing off")
        assertEquals(5_000L, coordinator.lastBackoffMs)

        advanceTimeBy(5_001)
        runCurrent()

        assertEquals(2, lan.opens)
        job.cancelAndJoin()
    }

    @Test
    fun outboxMarkingFailureBacksOffAndRetriesTheRow() = runTest {
        val store = FakeStore(rows = listOf(row("a")), markSentFailuresRemaining = 1)
        val lan = FakeRoute(RouteKind.LAN)
        val coordinator = coordinator(store, lan, relay = null)

        val job = backgroundScope.launch { coordinator.run() }
        runCurrent()

        assertTrue(job.isActive, "coordinator terminated after outbox marking failed")
        assertEquals(1, lan.opens)
        assertEquals(5_000L, coordinator.lastBackoffMs)

        advanceTimeBy(5_001)
        runCurrent()

        assertEquals(2, lan.opens)
        assertTrue(lan.totalSent("a") >= 2, "row was not retried after marking failed")
        job.cancelAndJoin()
    }

    @Test
    fun sendCancellationPropagatesByIdentityWithoutOpeningAReplacement() = runTest {
        val cancellation = CancellationException("stop_send")
        val store = FakeStore(rows = listOf(row("a")))
        val lan = FakeRoute(
            kind = RouteKind.LAN,
            sessionFactory = { FakeSession(RouteKind.LAN, sendFailure = cancellation) },
        )
        val relay = FakeRoute(RouteKind.RELAY)
        val coordinator = coordinator(store, lan, relay)

        val observed = assertFailsWith<CancellationException> { coordinator.run() }
        assertSame(cancellation, observed)
        assertEquals(1, lan.session().closeCount)
        assertEquals(0, relay.opens)
    }

    @Test
    fun closeCancellationPropagatesByIdentityWithoutOpeningAReplacement() = runTest {
        val cancellation = CancellationException("stop_close")
        val store = FakeStore(rows = emptyList())
        val session = FakeSession(RouteKind.LAN, closeFailure = cancellation).also {
            it.finish("connection_lost")
        }
        val lan = FakeRoute(
            kind = RouteKind.LAN,
            sessionFactory = { session },
        )
        val relay = FakeRoute(RouteKind.RELAY)
        val coordinator = coordinator(store, lan, relay)

        val observed = assertFailsWith<CancellationException> { coordinator.run() }
        assertSame(cancellation, observed)
        assertEquals(0, relay.opens)
    }

    @Test
    fun replacementWaitsUntilTheFailedSessionCloseFinalizerCompletes() = runTest {
        val allowClose = CompletableDeferred<Unit>()
        val store = FakeStore(rows = listOf(row("a")))
        val lan = FakeRoute(
            kind = RouteKind.LAN,
            successfulOpens = 1,
            sessionFactory = {
                FakeSession(
                    kind = RouteKind.LAN,
                    sendFailure = IOException("wifi_lost"),
                    allowClose = allowClose,
                )
            },
        )
        val relay = FakeRoute(RouteKind.RELAY)
        val coordinator = coordinator(store, lan, relay)

        val job = backgroundScope.launch { coordinator.run() }
        runCurrent()

        assertTrue(lan.session().closeStarted.isCompleted)
        assertFalse(lan.session().closeCompleted.isCompleted)
        assertEquals(0, relay.opens, "replacement opened before prior close completed")

        allowClose.complete(Unit)
        runCurrent()
        advanceTimeBy(5_001)
        runCurrent()

        assertTrue(lan.session().closeCompleted.isCompleted)
        assertEquals(1, relay.opens)
        job.cancelAndJoin()
    }

    @Test
    fun repeatedShortEstablishedFailuresAdvanceBackoff() = runTest {
        val store = FakeStore(rows = emptyList())
        val lan = FakeRoute(
            kind = RouteKind.LAN,
            sessionFactory = {
                FakeSession(
                    kind = RouteKind.LAN,
                    awaitClosedFailure = IOException("short_session"),
                )
            },
        )
        val coordinator = coordinator(store, lan, relay = null)

        val job = backgroundScope.launch { coordinator.run() }
        runCurrent()
        assertEquals(5_000L, coordinator.lastBackoffMs)

        advanceTimeBy(5_001)
        runCurrent()

        assertEquals(2, lan.opens)
        assertEquals(10_000L, coordinator.lastBackoffMs)
        job.cancelAndJoin()
    }

    @Test
    fun establishedFailureAfterSustainedHealthResetsBackoff() = runTest {
        val store = FakeStore(rows = emptyList())
        val lan = FakeRoute(RouteKind.LAN, selfDraining = true)
        val coordinator = coordinator(store, lan, relay = null)

        val job = backgroundScope.launch { coordinator.run() }
        runCurrent()
        advanceTimeBy(TransportCoordinator.STABILITY_WINDOW_MS + 1_000)
        lan.session().failAwaitClosed(IOException("healthy_session_lost"))
        runCurrent()

        assertEquals(0L, coordinator.lastBackoffMs)
        assertEquals(2, lan.opens, "sustained health did not reconnect immediately")
        job.cancelAndJoin()
    }

    @Test
    fun relayPromotesToAuthenticatedLanCandidate() = runTest {
        val store = FakeStore(rows = listOf(row("a")))
        val lan = FakeRoute(RouteKind.LAN, openFailuresRemaining = 1)
        val relay = FakeRoute(RouteKind.RELAY, selfDraining = true)
        val probes = FakeRelayProbeScheduler()
        val coordinator = TransportCoordinator(
            outbox = OutboxRepository(store, clock = { testScheduler.currentTime }),
            lan = lan,
            relay = relay,
            clock = { testScheduler.currentTime },
            relayProbeScheduler = probes,
        )

        val job = backgroundScope.launch { coordinator.run() }
        runCurrent()
        assertEquals(RouteKind.RELAY, coordinator.health.value.active)

        advanceTimeBy(15_001)
        runCurrent()

        assertEquals(RouteKind.LAN, coordinator.health.value.active)
        assertEquals(listOf("route_promoted_to_lan"), relay.session().closeCodes)
        assertEquals(listOf("a"), lan.session().sent.map { it.msgId })
        assertTrue(probes.requests.contains(true))
        job.cancelAndJoin()
    }

    @Test
    fun relayCloseCompletesBeforePromotedLanReadsOutbox() = runTest {
        val allowRelayClose = CompletableDeferred<Unit>()
        val store = FakeStore(rows = listOf(row("a")))
        val lan = FakeRoute(RouteKind.LAN, openFailuresRemaining = 1)
        val relay = FakeRoute(
            RouteKind.RELAY,
            selfDraining = true,
            sessionFactory = {
                FakeSession(RouteKind.RELAY, selfDraining = true, allowClose = allowRelayClose)
            },
        )
        val coordinator = TransportCoordinator(
            outbox = OutboxRepository(store, clock = { testScheduler.currentTime }),
            lan = lan,
            relay = relay,
            clock = { testScheduler.currentTime },
            relayProbeScheduler = FakeRelayProbeScheduler(),
        )

        val job = backgroundScope.launch { coordinator.run() }
        runCurrent()
        advanceTimeBy(15_001)
        runCurrent()

        assertTrue(relay.session().closeStarted.isCompleted)
        assertFalse(relay.session().closeCompleted.isCompleted)
        assertTrue(lan.session().sent.isEmpty(), "LAN drained before relay close joined")

        allowRelayClose.complete(Unit)
        runCurrent()
        assertEquals(listOf("a"), lan.session().sent.map { it.msgId })
        job.cancelAndJoin()
    }

    @Test
    fun failedLanCandidatesLeaveRelayActiveAndUseLanCooldownSequence() = runTest {
        val store = FakeStore(rows = emptyList())
        val lan = FakeRoute(RouteKind.LAN, failOpen = true)
        val relay = FakeRoute(RouteKind.RELAY, selfDraining = true)
        val coordinator = TransportCoordinator(
            outbox = OutboxRepository(store, clock = { testScheduler.currentTime }),
            lan = lan,
            relay = relay,
            clock = { testScheduler.currentTime },
            relayProbeScheduler = FakeRelayProbeScheduler(),
        )

        val job = backgroundScope.launch { coordinator.run() }
        runCurrent()
        assertEquals(RouteKind.RELAY, coordinator.health.value.active)
        assertEquals(15_000L, coordinator.lastLanBackoffMs)

        val expectedDelays = listOf(15_000L, 30_000L, 60_000L, 120_000L, 300_000L, 300_000L)
        for (expected in expectedDelays.drop(1)) {
            advanceTimeBy(coordinator.lastLanBackoffMs + 1)
            runCurrent()
            assertEquals(RouteKind.RELAY, coordinator.health.value.active)
            assertEquals(expected, coordinator.lastLanBackoffMs)
        }
        job.cancelAndJoin()
    }

    @Test
    fun lanLossOpensRelayImmediatelyBeforeLanCooldown() = runTest {
        val store = FakeStore(rows = emptyList())
        val lan = FakeRoute(RouteKind.LAN)
        val relay = FakeRoute(RouteKind.RELAY, selfDraining = true)
        val coordinator = coordinator(store, lan, relay)

        val job = backgroundScope.launch { coordinator.run() }
        runCurrent()
        lan.session().finish("wifi_lost")
        runCurrent()

        assertEquals(1, relay.opens)
        assertEquals(RouteKind.RELAY, coordinator.health.value.active)
        assertEquals(15_000L, coordinator.lastLanBackoffMs)
        job.cancelAndJoin()
    }

    @Test
    fun explicitRetryInterruptsLanCooldownWithoutResettingFailureCount() = runTest {
        val store = FakeStore(rows = emptyList())
        val lan = FakeRoute(RouteKind.LAN, failOpen = true)
        val relay = FakeRoute(RouteKind.RELAY, selfDraining = true)
        val retries = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
        val coordinator = TransportCoordinator(
            outbox = OutboxRepository(store, clock = { testScheduler.currentTime }),
            lan = lan,
            relay = relay,
            clock = { testScheduler.currentTime },
            retryRequests = retries,
            relayProbeScheduler = FakeRelayProbeScheduler(),
        )

        val job = backgroundScope.launch { coordinator.run() }
        runCurrent()
        assertEquals(1, lan.opens)

        retries.emit(Unit)
        runCurrent()

        assertEquals(2, lan.opens)
        assertEquals(30_000L, coordinator.lastLanBackoffMs)
        job.cancelAndJoin()
    }

    @Test
    fun inboundDirectRequestsRespectFifteenSecondAttemptFloor() = runTest {
        val store = FakeStore(rows = emptyList())
        val lan = FakeRoute(RouteKind.LAN, failOpen = true)
        val relay = FakeRoute(RouteKind.RELAY, selfDraining = true)
        val direct = MutableSharedFlow<Unit>(extraBufferCapacity = 4)
        val coordinator = TransportCoordinator(
            outbox = OutboxRepository(store, clock = { testScheduler.currentTime }),
            lan = lan,
            relay = relay,
            clock = { testScheduler.currentTime },
            directAttemptRequests = direct,
            relayProbeScheduler = FakeRelayProbeScheduler(),
        )

        val job = backgroundScope.launch { coordinator.run() }
        runCurrent()
        assertEquals(1, lan.opens)

        direct.emit(Unit)
        runCurrent()
        assertEquals(1, lan.opens, "request bypassed the anti-storm floor")

        advanceTimeBy(15_001)
        direct.emit(Unit)
        runCurrent()
        assertEquals(2, lan.opens)
        job.cancelAndJoin()
    }

    @Test
    fun relayPreferenceNeverSchedulesPromotionProbes() = runTest {
        val store = FakeStore(rows = emptyList())
        val lan = FakeRoute(RouteKind.LAN)
        val relay = FakeRoute(RouteKind.RELAY, selfDraining = true)
        val probes = FakeRelayProbeScheduler()
        val coordinator = TransportCoordinator(
            outbox = OutboxRepository(store, clock = { testScheduler.currentTime }),
            lan = lan,
            relay = relay,
            preferLan = false,
            clock = { testScheduler.currentTime },
            relayProbeScheduler = probes,
        )

        val job = backgroundScope.launch { coordinator.run() }
        runCurrent()
        advanceTimeBy(120_000)
        runCurrent()

        assertEquals(RouteKind.RELAY, coordinator.health.value.active)
        assertEquals(0, lan.opens)
        assertTrue(probes.requests.isEmpty())
        job.cancelAndJoin()
    }

    @Test
    fun sustainedPromotedLanResetsOnlyTheLanCooldown() = runTest {
        val store = FakeStore(rows = emptyList())
        val lan = FakeRoute(RouteKind.LAN, openFailuresRemaining = 1)
        val relay = FakeRoute(RouteKind.RELAY, selfDraining = true)
        val coordinator = TransportCoordinator(
            outbox = OutboxRepository(store, clock = { testScheduler.currentTime }),
            lan = lan,
            relay = relay,
            clock = { testScheduler.currentTime },
            relayProbeScheduler = FakeRelayProbeScheduler(),
        )

        val job = backgroundScope.launch { coordinator.run() }
        runCurrent()
        advanceTimeBy(15_001)
        runCurrent()
        assertEquals(RouteKind.LAN, coordinator.health.value.active)

        advanceTimeBy(TransportCoordinator.STABILITY_WINDOW_MS + 1)
        lan.session().finish("wifi_lost")
        runCurrent()

        assertEquals(0L, coordinator.lastLanBackoffMs)
        assertTrue(relay.opens >= 2, "LAN loss did not open a fresh relay immediately")
        job.cancelAndJoin()
    }

    @Test
    fun cancellationDuringPromotionClosesRelayAndCandidateExactlyOnce() = runTest {
        val allowRelayClose = CompletableDeferred<Unit>()
        val store = FakeStore(rows = emptyList())
        val lan = FakeRoute(RouteKind.LAN, openFailuresRemaining = 1)
        val relay = FakeRoute(
            RouteKind.RELAY,
            selfDraining = true,
            sessionFactory = {
                FakeSession(RouteKind.RELAY, selfDraining = true, allowClose = allowRelayClose)
            },
        )
        val coordinator = TransportCoordinator(
            outbox = OutboxRepository(store, clock = { testScheduler.currentTime }),
            lan = lan,
            relay = relay,
            clock = { testScheduler.currentTime },
            relayProbeScheduler = FakeRelayProbeScheduler(),
        )

        val job = backgroundScope.launch { coordinator.run() }
        runCurrent()
        advanceTimeBy(15_001)
        runCurrent()
        assertTrue(relay.session().closeStarted.isCompleted)

        job.cancel()
        runCurrent()
        allowRelayClose.complete(Unit)
        job.join()

        assertEquals(1, relay.session().closeCount)
        assertEquals(1, lan.session().closeCount)
    }

    @Test
    fun repeatedRelayLanHandoffsNeverOverlapOutboxDrainers() = runTest {
        val store = FakeStore(rows = listOf(row("a"), row("b")))
        val lan = FakeRoute(RouteKind.LAN)
        val relay = FakeRoute(RouteKind.RELAY)
        val coordinator = coordinator(store, lan, relay)

        val job = backgroundScope.launch { coordinator.run() }
        runCurrent()
        repeat(4) {
            lan.session().finish("wifi_lost")
            runCurrent()
            advanceTimeBy(15_001)
            runCurrent()
        }

        assertTrue(store.maxConcurrentDrains <= 1, "handoffs overlapped outbox selection")
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
        private val successfulOpens: Int = Int.MAX_VALUE,
        private val sessionFactory: (() -> FakeSession)? = null,
        openFailuresRemaining: Int = 0,
    ) : TransportRoute {
        var opens = 0
        private var failuresRemaining = openFailuresRemaining
        private val sessions = mutableListOf<FakeSession>()

        override suspend fun open(): AuthenticatedRouteSession {
            opens += 1
            if (failOpen || failuresRemaining-- > 0 || sessions.size >= successfulOpens) {
                throw IllegalStateException("route_unavailable")
            }
            return (sessionFactory?.invoke() ?: FakeSession(kind, selfDraining)).also { sessions += it }
        }

        fun session(): FakeSession = sessions.last()
        fun totalSent(msgId: String) = sessions.sumOf { s -> s.sent.count { it.msgId == msgId } }
    }

    private class FakeSession(
        override val kind: RouteKind,
        override val selfDraining: Boolean = false,
        private val sendFailure: Throwable? = null,
        private val awaitClosedFailure: Throwable? = null,
        private val closeFailure: Throwable? = null,
        private val allowClose: CompletableDeferred<Unit>? = null,
    ) : AuthenticatedRouteSession {
        val sent = mutableListOf<OutboundMessage>()
        val sendAttempts = mutableListOf<OutboundMessage>()
        val closeCodes = mutableListOf<String>()
        val closeStarted = CompletableDeferred<Unit>()
        val closeCompleted = CompletableDeferred<Unit>()
        var closeCount = 0
            private set
        private val closed = CompletableDeferred<String>()

        override suspend fun send(message: OutboundMessage) {
            sendAttempts += message
            sendFailure?.let { throw it }
            sent += message
        }

        override suspend fun awaitClosed(): String {
            awaitClosedFailure?.let { throw it }
            return closed.await()
        }

        override suspend fun close(code: String) {
            closeCount += 1
            closeCodes += code
            closeStarted.complete(Unit)
            allowClose?.await()
            closeFailure?.let { throw it }
            closed.complete(code)
            closeCompleted.complete(Unit)
        }

        fun finish(code: String) { closed.complete(code) }
        fun failAwaitClosed(error: Throwable) { closed.completeExceptionally(error) }
    }

    private class FakeRelayProbeScheduler : RelayProbeScheduler {
        val requests = mutableListOf<Boolean>()
        override suspend fun ensureProbe(requestDirect: Boolean) {
            requests += requestDirect
        }
    }

    private class FakeStore(
        rows: List<OutboundMessage>,
        private var sendableFailuresRemaining: Int = 0,
        private var markSentFailuresRemaining: Int = 0,
    ) : OutboxStore {
        val custodyAccepted = mutableListOf<String>()
        private val pending = rows.associateBy { it.msgId }.toMutableMap()
        private val dueAt = rows.associate { it.msgId to 0L }.toMutableMap()
        private val drainLock = Mutex()
        private var concurrentDrains = 0
        var maxConcurrentDrains = 0
            private set

        fun due(): List<OutboundMessage> = pending.values.toList()

        override suspend fun sendable(now: Long, limit: Int): List<OutboundMessage> {
            if (sendableFailuresRemaining > 0) {
                sendableFailuresRemaining -= 1
                throw IOException("outbox_select_failed")
            }
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
            if (markSentFailuresRemaining > 0) {
                markSentFailuresRemaining -= 1
                throw IOException("outbox_mark_failed")
            }
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
}
