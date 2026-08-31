package co.twinotify.core.service

import co.twinotify.core.storage.CustodyAcceptanceResult
import co.twinotify.core.storage.LegacyForwardResult
import co.twinotify.core.storage.OutboundMessage
import co.twinotify.core.storage.RelayReceiptResult
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

@OptIn(ExperimentalCoroutinesApi::class)
class LiveServiceTransportTest {
    @Test
    fun transportSideEffectsRethrowCancellationByIdentity() = runTest {
        val cancellation = CancellationException("stop")

        val observed = assertFailsWith<CancellationException> {
            runTransportSideEffect(
                block = { throw cancellation },
                onFailure = { error("cancellation was reported as an ordinary failure") },
            )
        }

        assertSame(cancellation, observed)
    }

    @Test
    fun serviceLoopLoadsProductionRoutesOnceAndStartsOneCoordinator() = runTest {
        var routeLoads = 0
        val lan = FakeRoute(RouteKind.LAN)
        val statuses = mutableListOf<SyncRouteStatus>()
        val authenticatedRoutes = mutableListOf<RouteKind>()
        val loop = LiveServiceTransportLoop(
            outbox = OutboxRepository(EmptyStore()),
            loadRoutes = {
                routeLoads += 1
                LiveTransportRoutes(lan = lan, relay = null)
            },
            queuedCount = { 0 },
            retryRequests = SyncServiceStatus.routeRetryRequested,
            onAuthenticatedRoute = { authenticatedRoutes += it },
            publishHealth = { statuses += it.toSyncRouteStatus() },
        )

        val job = launch { loop.run(preferLan = true) }
        runCurrent()

        assertEquals(1, routeLoads)
        assertEquals(1, lan.opens)
        assertEquals(RouteKind.LAN, statuses.last().route)
        assertEquals(RoutePhase.AUTHENTICATED, statuses.last().phase)
        assertEquals(listOf(RouteKind.LAN), authenticatedRoutes)
        job.cancelAndJoin()
        assertEquals(1, lan.session().closeCount)
    }

    @Test
    fun serviceLoopSurvivesEstablishedLanFailureAndFallsBackToRelay() = runTest {
        val lan = FakeRoute(
            kind = RouteKind.LAN,
            successfulOpens = 1,
            sessionFactory = {
                FakeSession(
                    kind = RouteKind.LAN,
                    awaitClosedFailure = IOException("lan_reader_failed"),
                )
            },
        )
        val relay = FakeRoute(RouteKind.RELAY)
        val loop = LiveServiceTransportLoop(
            outbox = OutboxRepository(EmptyStore()),
            loadRoutes = { LiveTransportRoutes(lan = lan, relay = relay) },
            queuedCount = { 0 },
            publishHealth = {},
        )

        val job = backgroundScope.launch { loop.run(preferLan = true) }
        runCurrent()
        advanceTimeBy(5_001)
        runCurrent()

        assertTrue(job.isActive, "service transport job terminated after established route failure")
        assertEquals(1, relay.opens)
        assertEquals(RouteKind.RELAY, loop.health.value.active)
        job.cancelAndJoin()
    }

    @Test
    fun lanOnlyServiceLoopRunsWithoutRelayConfiguration() = runTest {
        val lan = FakeRoute(RouteKind.LAN)
        val loop = LiveServiceTransportLoop(
            outbox = OutboxRepository(EmptyStore()),
            loadRoutes = { LiveTransportRoutes(lan = lan, relay = null) },
            queuedCount = { 0 },
            publishHealth = {},
        )

        val job = launch { loop.run(preferLan = true) }
        runCurrent()

        assertEquals(1, lan.opens)
        assertEquals(RouteKind.LAN, loop.health.value.active)
        job.cancelAndJoin()
    }

    @Test
    fun coordinatorHealthIsTheOnlySourceOfPublicConnectionTruth() {
        val connecting = RouteHealth(RouteKind.NONE, RoutePhase.CONNECTING, 2).toSyncRouteStatus()
        val lan = RouteHealth(RouteKind.LAN, RoutePhase.AUTHENTICATED, 1).toSyncRouteStatus()
        val relay = RouteHealth(RouteKind.RELAY, RoutePhase.AUTHENTICATED, 0).toSyncRouteStatus()
        val retrying = RouteHealth(RouteKind.NONE, RoutePhase.RECONNECTING, 3).toSyncRouteStatus()

        assertEquals(SyncRouteStatus(RouteKind.NONE, RoutePhase.CONNECTING, 2), connecting)
        assertEquals(SyncRouteStatus(RouteKind.LAN, RoutePhase.AUTHENTICATED, 1), lan)
        assertEquals(SyncRouteStatus(RouteKind.RELAY, RoutePhase.AUTHENTICATED, 0), relay)
        assertEquals(SyncRouteStatus(RouteKind.NONE, RoutePhase.RECONNECTING, 3), retrying)
        assertEquals(SyncState.CONNECTING, connecting.toSyncState())
        assertEquals(SyncState.CONNECTED, lan.toSyncState())
        assertEquals(SyncState.CONNECTED, relay.toSyncState())
        assertEquals(SyncState.OFFLINE_QUEUED, retrying.toSyncState())
    }

    @Test
    fun retryRequestIsRoutedToTheLiveCoordinator() = runTest {
        val retries = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
        val lan = FakeRoute(RouteKind.LAN, failOpen = true)
        val loop = LiveServiceTransportLoop(
            outbox = OutboxRepository(EmptyStore()),
            loadRoutes = { LiveTransportRoutes(lan = lan, relay = null) },
            queuedCount = { 0 },
            retryRequests = retries,
            publishHealth = {},
        )

        val job = backgroundScope.launch { loop.run(preferLan = true) }
        runCurrent()
        val opensBeforeRetry = lan.opens
        retries.emit(Unit)
        runCurrent()

        assertEquals(opensBeforeRetry + 1, lan.opens)
        job.cancelAndJoin()
    }

    @Test
    fun transportSelfStopSkipsSelfJoinButExternalStopJoins() = runTest {
        val never = CompletableDeferred<Unit>()
        val active = backgroundScope.launch { never.await() }
        var childrenStopped = 0
        var scopeStopped = 0

        quiesceServiceJobsAfterCallShutdown(
            fromRelayJob = true,
            activeRelay = active,
            stopOtherChildren = { childrenStopped += 1 },
            cancelAndJoinServiceScope = { scopeStopped += 1 },
        )

        assertTrue(active.isActive, "a transport callback joined its own parent")
        assertEquals(1, childrenStopped)
        assertEquals(0, scopeStopped)
        active.cancelAndJoin()

        val external = backgroundScope.launch { never.await() }
        quiesceServiceJobsAfterCallShutdown(
            fromRelayJob = false,
            activeRelay = external,
            stopOtherChildren = {},
            cancelAndJoinServiceScope = { scopeStopped += 1 },
        )
        assertFalse(external.isActive)
        assertEquals(1, scopeStopped)

        var ownerCancels = 0
        var ownerJoins = 0
        stopRoutePreferenceOwner(
            fromTransportJob = true,
            cancelOwner = { ownerCancels += 1 },
            cancelAndJoinOwner = { ownerJoins += 1 },
        )
        assertEquals(1, ownerCancels)
        assertEquals(0, ownerJoins, "transport callback joined the owner waiting on it")
    }

    @Test
    fun initialStartAndPreferenceRestartShareOneOwnerWithoutOverlappingGenerations() = runTest {
        val order = mutableListOf<String>()
        var preferred = true
        var activeGenerations = 0
        var maxActiveGenerations = 0
        fun startGeneration(name: String) = backgroundScope.launch {
            activeGenerations += 1
            maxActiveGenerations = maxOf(maxActiveGenerations, activeGenerations)
            order += "$name-open"
            try {
                awaitCancellation()
            } finally {
                order += "$name-closed"
                activeGenerations -= 1
            }
        }
        var current: kotlinx.coroutines.Job? = null
        val restarter = SerializedTransportRestarter(
            isCurrentActive = { current?.isActive == true },
            stopCurrent = {
                current?.cancelAndJoin()
                if (current != null) order += "old-closed-and-joined"
            },
            readPreference = { preferred },
            startCurrent = {
                order += "new-open-prefer-lan-$it"
                current = startGeneration(if (it) "lan" else "relay")
            },
        )
        val worker = backgroundScope.launch { restarter.run() }

        // This is the same request used by SyncService.onStartCommand.
        restarter.ensureStarted()
        runCurrent()
        preferred = false
        // This is the request used after TwinotifyCoreModule persists a new preference.
        restarter.forceRestart()
        runCurrent()

        assertEquals(
            listOf(
                "new-open-prefer-lan-true",
                "lan-open",
                "lan-closed",
                "old-closed-and-joined",
                "new-open-prefer-lan-false",
                "relay-open",
            ),
            order,
        )
        assertEquals(1, maxActiveGenerations, "coordinator generations overlapped")
        current?.cancelAndJoin()
        worker.cancelAndJoin()
    }

    @Test
    fun repeatedPreferenceTogglesAreSerializedAndReadTheLatestPersistedValue() = runTest {
        val order = mutableListOf<String>()
        var preferred = true
        val firstStopEntered = CompletableDeferred<Unit>()
        val allowFirstStop = CompletableDeferred<Unit>()
        var stopCalls = 0
        var active = true
        val restarter = SerializedTransportRestarter(
            isCurrentActive = { active },
            stopCurrent = {
                stopCalls += 1
                order += "stop-$stopCalls"
                if (stopCalls == 1) {
                    firstStopEntered.complete(Unit)
                    allowFirstStop.await()
                }
                active = false
            },
            readPreference = { preferred },
            startCurrent = {
                order += "start-$it"
                active = true
            },
        )
        val worker = backgroundScope.launch { restarter.run() }

        restarter.forceRestart()
        firstStopEntered.await()
        preferred = false
        repeat(8) { restarter.forceRestart() }
        preferred = true
        allowFirstStop.complete(Unit)
        runCurrent()

        assertEquals(
            listOf("stop-1", "start-true", "stop-2", "start-true"),
            order,
            "toggles were not conflated or a stale preference was started",
        )
        worker.cancelAndJoin()
    }

    @Test
    fun duplicateServiceStartsDoNotTearDownAHealthyGeneration() = runTest {
        var active = false
        var stops = 0
        var starts = 0
        val restarter = SerializedTransportRestarter(
            isCurrentActive = { active },
            stopCurrent = {
                stops += 1
                active = false
            },
            readPreference = { true },
            startCurrent = {
                starts += 1
                active = true
            },
        )
        val worker = backgroundScope.launch { restarter.run() }

        restarter.ensureStarted()
        runCurrent()
        repeat(8) { restarter.ensureStarted() }
        runCurrent()

        assertEquals(1, starts)
        assertEquals(0, stops, "duplicate ACTION_START tore down the healthy coordinator")
        worker.cancelAndJoin()
    }

    @Test
    fun compatibleRelayPeerEnablesBootstrapAndCoordinatorProbes() = runTest {
        var bootstraps = 0
        val probes = mutableListOf<Boolean>()
        val conditions = mutableListOf<DeliveryConditions>()
        val session = RelayPeerFeatureSession(
            ensureBootstrap = {
                bootstraps += 1
                DeliveryConditions(bootstrapWaiting = true)
            },
            ensureProbe = probes::add,
            publishConditions = conditions::add,
            onFailure = { error("unexpected failure: $it") },
        )

        session.onAuthenticated(2, RelayFeatures.CURRENT)
        session.ensureProbe(requestDirect = false)
        session.ensureProbe(requestDirect = true)

        assertEquals(1, bootstraps)
        assertEquals(listOf(false, true), probes)
        assertEquals(DeliveryConditions(bootstrapWaiting = true), conditions.last())
    }

    @Test
    fun incompatibleRelayPeerNeverReceivesUnknownBootstrapOrProbeControls() = runTest {
        var bootstraps = 0
        var probes = 0
        val conditions = mutableListOf<DeliveryConditions>()
        val session = RelayPeerFeatureSession(
            ensureBootstrap = {
                bootstraps += 1
                DeliveryConditions()
            },
            ensureProbe = { probes += 1 },
            publishConditions = conditions::add,
            onFailure = { error("unexpected failure: $it") },
        )

        session.onAuthenticated(2, setOf(RelayFeatures.LAN_BOOTSTRAP_V1))
        session.ensureProbe(requestDirect = true)

        assertEquals(0, bootstraps)
        assertEquals(0, probes)
        assertEquals(DeliveryConditions(peerVersionIncompatible = true), conditions.last())
    }

    private class FakeRoute(
        override val kind: RouteKind,
        private val failOpen: Boolean = false,
        private val successfulOpens: Int = Int.MAX_VALUE,
        private val sessionFactory: (() -> FakeSession)? = null,
    ) : TransportRoute {
        var opens = 0
        private val sessions = mutableListOf<FakeSession>()
        override suspend fun open(): AuthenticatedRouteSession {
            opens += 1
            if (failOpen || sessions.size >= successfulOpens) error("route_unavailable")
            return (sessionFactory?.invoke() ?: FakeSession(kind)).also(sessions::add)
        }
        fun session() = sessions.last()
    }

    private class FakeSession(
        override val kind: RouteKind,
        private val awaitClosedFailure: Throwable? = null,
    ) : AuthenticatedRouteSession {
        private val closed = CompletableDeferred<String>()
        var closeCount = 0
        override suspend fun send(message: OutboundMessage) = Unit
        override suspend fun awaitClosed(): String {
            awaitClosedFailure?.let { throw it }
            return closed.await()
        }
        override suspend fun close(code: String) {
            closeCount += 1
            closed.complete(code)
        }
    }

    private class EmptyStore : OutboxStore {
        override suspend fun sendable(now: Long, limit: Int): List<OutboundMessage> = emptyList()
        override suspend fun acceptCustody(msgId: String, route: CustodyRoute, acceptedAt: Long, retryAt: Long) =
            CustodyAcceptanceResult.Missing
        override suspend fun markSent(msgId: String, retryAt: Long): Int = 0
        override suspend fun legacyForwarded(msgId: String, forwardedAt: Long) = LegacyForwardResult.Missing
        override suspend fun applyPeerReceipt(ackedMsgId: String, envelopeSha256: String, status: String, reason: String?, occurredAt: Long) =
            RelayReceiptResult.Missing
        override suspend fun rejectRelay(msgId: String, reason: String, occurredAt: Long, retryAt: Long) =
            RelayRejectionResult.Missing
        override suspend fun expireRelay(msgId: String, expiredAt: Long) = RelayReceiptResult.Missing
        override suspend fun readyRelayAcks(limit: Int): List<RelayAckRecord> = emptyList()
        override suspend fun markRelayAckSent(msgId: String, envelopeSha256: String): Int = 0
    }
}
