package co.twinotify.core.service

import co.twinotify.core.call.CallShutdownConfigIntent
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Policy-only lifecycle tests.  These deliberately do not construct an Android Service so the
 * restart/boot contract remains executable on the JVM and cannot be hidden behind framework
 * behavior.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ServiceLifecycleTest {
    private val paired = true
    private val relayUrl = "wss://relay.example.test/ws"

    @Test
    fun nullStickyRestart_enabledPairedAndConfigured_starts() {
        val decision = ServiceStartPolicy.decide(
            intentAction = null,
            persisted = ServiceConfig(enabled = true, relayUrl = relayUrl),
            paired = paired,
        )

        assertIs<ServiceStartDecision.Start>(decision)
        assertEquals(relayUrl, decision.relayUrl)
    }

    @Test
    fun nullStickyRestart_disabled_stops() {
        val decision = ServiceStartPolicy.decide(
            intentAction = null,
            persisted = ServiceConfig(enabled = false, relayUrl = relayUrl),
            paired = paired,
        )

        assertIs<ServiceStartDecision.Stop>(decision)
    }

    @Test
    fun bootWhileDisabled_doesNotStart() {
        val decision = ServiceStartPolicy.decide(
            intentAction = ServiceStartPolicy.BOOT_ACTION,
            persisted = ServiceConfig(enabled = false, relayUrl = relayUrl),
            paired = paired,
        )

        assertIs<ServiceStartDecision.Stop>(decision)
    }

    @Test
    fun userStop_persistsDisabledBeforeShutdown() {
        val stopped = ServiceStartPolicy.applyUserStop(
            ServiceConfig(enabled = true, relayUrl = relayUrl),
        )

        assertFalse(stopped.enabled)
        assertEquals(relayUrl, stopped.relayUrl)
    }

    @Test
    fun userStart_persistsUrlAndEnabledIndependentlyOfSocket() {
        val started = ServiceStartPolicy.applyUserStart(
            persisted = ServiceConfig(enabled = false, relayUrl = null),
            relayUrl = relayUrl,
        )

        assertTrue(started.enabled)
        assertEquals(relayUrl, started.relayUrl)
    }

    @Test
    fun captureOnlyShutdownIntentPreservesServiceAndUnrelatedConfiguration() {
        val existing = ServiceConfig(
            enabled = true,
            relayUrl = relayUrl,
            alwaysConnected = false,
            callCaptureEnabled = true,
            lastUserChangeAt = 17L,
            revocationRequestedAt = 19L,
        )

        val updated = mergeCallShutdownIntent(
            existing,
            CallShutdownConfigIntent(disableCallCapture = true, disableService = false),
            now = 23L,
        )

        assertEquals(existing.copy(callCaptureEnabled = false), updated)
    }

    @Test
    fun serviceStopIntentChangesOnlyServiceBitAndUserTimestamp() {
        val existing = ServiceConfig(
            enabled = true,
            relayUrl = relayUrl,
            alwaysConnected = false,
            callCaptureEnabled = true,
            lastUserChangeAt = 17L,
            revocationRequestedAt = 19L,
        )

        val updated = mergeCallShutdownIntent(
            existing,
            CallShutdownConfigIntent(disableCallCapture = false, disableService = true),
            now = 23L,
        )

        assertEquals(existing.copy(enabled = false, lastUserChangeAt = 23L), updated)
    }

    @Test
    fun mergedShutdownIntentAtomicallyAppliesBothFalseBits() {
        val existing = ServiceConfig(
            enabled = true,
            relayUrl = relayUrl,
            callCaptureEnabled = true,
            lastUserChangeAt = 17L,
        )

        val updated = mergeCallShutdownIntent(
            existing,
            CallShutdownConfigIntent(disableCallCapture = true, disableService = true),
            now = 23L,
        )

        assertEquals(
            existing.copy(enabled = false, callCaptureEnabled = false, lastUserChangeAt = 23L),
            updated,
        )
    }

    @Test
    fun normalCaptureResumeWaitsThenRereadsPersistedFalseConfiguration() = runTest {
        val release = CompletableDeferred<Unit>()
        val order = mutableListOf<String>()
        val configured = mutableListOf<Boolean>()
        val resume = backgroundScope.launch {
            resumeNormalCallCaptureAfterShutdown(
                awaitRelease = {
                    order += "await-release"
                    release.await()
                },
                readConfig = {
                    order += "read-config"
                    ServiceConfig(enabled = true, callCaptureEnabled = false)
                },
                configure = {
                    order += "configure"
                    configured += it
                },
            )
        }

        testScheduler.runCurrent()
        assertEquals(listOf("await-release"), order)
        release.complete(Unit)
        resume.join()

        assertEquals(listOf("await-release", "read-config", "configure"), order)
        assertEquals(listOf(false), configured)
    }

    @Test
    fun pairingSuccess_isNotRolledBackWhenServiceStartupFails() {
        val pairedConfig = ServiceStartPolicy.applyUserStart(
            persisted = ServiceConfig(enabled = false, relayUrl = null),
            relayUrl = relayUrl,
        )

        // Pairing owns the peer record; a later socket failure only changes health.
        assertTrue(pairedConfig.enabled)
        assertEquals(relayUrl, pairedConfig.relayUrl)
        assertIs<ServiceStartDecision.Start>(
            ServiceStartPolicy.decide(null, pairedConfig, paired = true),
        )
    }

    @Test
    fun healthSnapshot_neverReportsConnectedWhenTransportIsOffline() {
        SyncServiceStatus.setState(SyncState.CONNECTED)
        SyncServiceStatus.setState(SyncState.OFFLINE_QUEUED)
        SyncServiceStatus.setQueueStats(count = 3, bytes = 42)
        SyncServiceStatus.setLastError("socket_closed")

        val health = SyncServiceStatus.health.value
        assertEquals("degraded", health.service)
        assertEquals("offline", health.transport)
        assertEquals(3, health.queuedCount)
        assertEquals(42, health.queuedBytes)
        assertEquals("socket_closed", health.lastErrorCode)
        assertEquals("OFFLINE_QUEUED", health.toEventMap()["state"])
    }

    @Test
    fun authenticatedRecovery_clearsStaleTransportError() {
        SyncServiceStatus.setLastError("invalid_relay_url")
        SyncServiceStatus.setState(SyncState.CONNECTED)

        assertEquals("connected", SyncServiceStatus.health.value.service)
        assertEquals("online", SyncServiceStatus.health.value.transport)
        assertEquals(null, SyncServiceStatus.health.value.lastErrorCode)
    }

    // ---- Task 7: route-aware lifecycle -----------------------------------

    @Test
    fun lanBoundPeerStartsWithoutARelayUrl() {
        val decision = ServiceStartPolicy.decide(
            intentAction = null,
            persisted = ServiceConfig(enabled = true, relayUrl = null),
            paired = true,
            lanBound = true,
        )

        val start = assertIs<ServiceStartDecision.Start>(decision)
        assertNull(start.relayUrl)
        assertTrue(start.lanBound)
    }

    @Test
    fun relayOnlyPeerStaysRelayOnly() {
        val decision = ServiceStartPolicy.decide(
            intentAction = null,
            persisted = ServiceConfig(enabled = true, relayUrl = "wss://relay.example"),
            paired = true,
            lanBound = false,
        )

        val start = assertIs<ServiceStartDecision.Start>(decision)
        assertEquals("wss://relay.example", start.relayUrl)
        assertFalse(start.lanBound)
    }

    @Test
    fun aPairedPeerWithNeitherRouteDoesNotStart() {
        val decision = ServiceStartPolicy.decide(
            intentAction = null,
            persisted = ServiceConfig(enabled = true, relayUrl = null),
            paired = true,
            lanBound = false,
        )

        assertEquals("no_route_available", assertIs<ServiceStartDecision.Stop>(decision).reason)
    }

    @Test
    fun aUserStoppedServiceStaysStoppedEvenWhenLanBound() {
        val decision = ServiceStartPolicy.decide(
            intentAction = ServiceStartPolicy.BOOT_ACTION,
            persisted = ServiceConfig(enabled = false, relayUrl = null),
            paired = true,
            lanBound = true,
        )

        assertEquals("disabled", assertIs<ServiceStartDecision.Stop>(decision).reason)
    }

    @Test
    fun aLanOnlyPeerCanBeEnabledWithoutEverSupplyingARelayUrl() {
        val enabled = ServiceStartPolicy.applyLanOnlyStart(ServiceConfig())

        assertTrue(enabled.enabled)
        assertNull(enabled.relayUrl)
        assertIs<ServiceStartDecision.Start>(
            ServiceStartPolicy.decide(null, enabled, paired = true, lanBound = true),
        )
    }

    @Test
    fun routeStatusReportsEachPhaseTruthfully() {
        SyncServiceStatus.setRouteStatus(SyncRouteStatus(RouteKind.LAN, RoutePhase.AUTHENTICATED, 0))
        assertEquals(RouteKind.LAN, SyncServiceStatus.routeStatus.value.route)

        SyncServiceStatus.setRouteStatus(SyncRouteStatus(RouteKind.RELAY, RoutePhase.AUTHENTICATED, 2))
        assertEquals(RouteKind.RELAY, SyncServiceStatus.routeStatus.value.route)
        assertEquals(2, SyncServiceStatus.routeStatus.value.queuedCount)

        SyncServiceStatus.setRouteStatus(SyncRouteStatus(RouteKind.NONE, RoutePhase.RECONNECTING, 5))
        assertEquals(RoutePhase.RECONNECTING, SyncServiceStatus.routeStatus.value.phase)
        assertEquals(5, SyncServiceStatus.routeStatus.value.queuedCount)
    }

    @Test
    fun custodyQueueUpdatesKeepAuthenticatedLanAndRelayPublicTruthInSync() {
        for (route in listOf(RouteKind.LAN, RouteKind.RELAY)) {
            SyncServiceStatus.setRouteStatus(
                SyncRouteStatus(route, RoutePhase.AUTHENTICATED, queuedCount = 3),
            )

            SyncServiceStatus.setQueueStats(count = 1, bytes = 42)

            assertEquals(1, SyncServiceStatus.health.value.queuedCount)
            assertEquals(1, SyncServiceStatus.routeStatus.value.queuedCount)
            assertEquals(route, SyncServiceStatus.routeStatus.value.route)
            assertEquals(RoutePhase.AUTHENTICATED, SyncServiceStatus.routeStatus.value.phase)
        }
    }

    @Test
    fun authenticatedFloorOneRelayPreservesLegacyOnlineOnlyState() {
        val relay = SyncRouteStatus(RouteKind.RELAY, RoutePhase.AUTHENTICATED, 0)
        val lan = SyncRouteStatus(RouteKind.LAN, RoutePhase.AUTHENTICATED, 0)

        assertEquals(SyncState.LEGACY_ONLINE_ONLY, relay.toSyncState(protocolFloor = 1))
        assertEquals(SyncState.CONNECTED, relay.toSyncState(protocolFloor = 2))
        assertEquals(SyncState.CONNECTED, lan.toSyncState(protocolFloor = 1))
    }

    @Test
    fun routeStatusCarriesNoNetworkDetail() {
        SyncServiceStatus.setRouteStatus(SyncRouteStatus(RouteKind.LAN, RoutePhase.AUTHENTICATED, 1))

        val rendered = SyncServiceStatus.routeStatus.value.toPublicMap()

        assertEquals(setOf("route", "phase", "queued_count"), rendered.keys)
        assertEquals("lan", rendered["route"])
        assertEquals("authenticated", rendered["phase"])
    }

    @Test
    fun stoppingClearsTheRouteStatusSoNoStaleRouteIsShown() {
        SyncServiceStatus.setRouteStatus(SyncRouteStatus(RouteKind.LAN, RoutePhase.AUTHENTICATED, 3))

        SyncServiceStatus.clearRouteStatus()

        assertEquals(RouteKind.NONE, SyncServiceStatus.routeStatus.value.route)
        assertEquals(RoutePhase.IDLE, SyncServiceStatus.routeStatus.value.phase)
    }
}
