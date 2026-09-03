package co.twinotify.core.service

import co.twinotify.core.call.CallShutdownConfigIntent
import co.twinotify.core.storage.DeliveryQueueSnapshot
import co.twinotify.core.storage.UserContentKind
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger
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
    fun deliveryPresenterCoversForegroundAndHomeTruthWithoutFalsePeerClaims() {
        val cases = listOf(
            Triple(
                SyncRouteStatus(RouteKind.LAN, RoutePhase.AUTHENTICATED, peerEvidence = PeerEvidence.DIRECT),
                "Direct on Wi-Fi",
                "Your phones are talking directly over Wi-Fi.",
            ),
            Triple(
                SyncRouteStatus(
                    RouteKind.RELAY,
                    RoutePhase.AUTHENTICATED,
                    awaitingPeerCount = 2,
                    heldByRelayCount = 2,
                    peerEvidence = PeerEvidence.STALE,
                    deliveryReason = DeliveryReason.RELAY_HOLDING,
                ),
                "Via relay",
                "2 notifications are stored securely and waiting for your other phone.",
            ),
            Triple(
                SyncRouteStatus(
                    RouteKind.RELAY,
                    RoutePhase.AUTHENTICATED,
                    peerEvidence = PeerEvidence.RECENT,
                ),
                "Via relay",
                "Your other phone checked in recently. Delivery is encrypted end to end.",
            ),
            Triple(
                SyncRouteStatus(
                    RouteKind.RELAY,
                    RoutePhase.AUTHENTICATED,
                    peerEvidence = PeerEvidence.STALE,
                ),
                "Via relay",
                "Connected to the relay. Waiting for your other phone to check in.",
            ),
            Triple(
                SyncRouteStatus(
                    RouteKind.RELAY,
                    RoutePhase.AUTHENTICATED,
                    awaitingPeerCount = 1,
                    deliveryReason = DeliveryReason.WAITING_FOR_PEER,
                ),
                "Via relay",
                "1 notification is waiting for confirmation from your other phone.",
            ),
            Triple(
                SyncRouteStatus(
                    RouteKind.RELAY,
                    RoutePhase.AUTHENTICATED,
                    deliveryReason = DeliveryReason.LAN_BOOTSTRAP_WAITING,
                ),
                "Via relay",
                "Setting up direct Wi-Fi in the background. Delivery is encrypted end to end.",
            ),
            Triple(
                SyncRouteStatus(
                    RouteKind.RELAY,
                    RoutePhase.AUTHENTICATED,
                    deliveryReason = DeliveryReason.PEER_VERSION_INCOMPATIBLE,
                ),
                "Via relay",
                "Update Twinotify on your other phone to enable direct Wi-Fi.",
            ),
            Triple(
                SyncRouteStatus(
                    RouteKind.RELAY,
                    RoutePhase.AUTHENTICATED,
                    deliveryReason = DeliveryReason.LAN_BINDING_CONFLICT,
                ),
                "Via relay",
                "Direct Wi-Fi needs attention. Relay delivery remains encrypted end to end.",
            ),
            Triple(
                SyncRouteStatus(
                    RouteKind.NONE,
                    RoutePhase.RECONNECTING,
                    queuedCount = 1,
                    pendingLocalCount = 1,
                    deliveryReason = DeliveryReason.NO_ROUTE,
                ),
                "Queued on this phone",
                "1 notification will send when a connection is available.",
            ),
            Triple(
                SyncRouteStatus(RouteKind.NONE, RoutePhase.RECONNECTING),
                "Reconnecting",
                "Looking for your other phone. This retries on its own.",
            ),
            Triple(
                SyncRouteStatus(
                    RouteKind.NONE,
                    RoutePhase.RECONNECTING,
                    awaitingPeerCount = 3,
                    heldByRelayCount = 3,
                    deliveryReason = DeliveryReason.RELAY_HOLDING,
                    userContentKind = UserContentKind.SYNC_UPDATES,
                ),
                "Reconnecting",
                "3 sync updates are stored securely while this phone reconnects.",
            ),
            Triple(
                SyncRouteStatus(RouteKind.NONE, RoutePhase.IDLE),
                "Stopped",
                "Mirroring is stopped.",
            ),
        )

        cases.forEach { (status, label, explanation) ->
            val presentation = DeliveryStatusPresenter.present(status, paired = true, enabled = true)
            assertEquals(label, presentation.label)
            assertEquals(explanation, presentation.explanation)
            assertFalse(presentation.label.contains("connected", ignoreCase = true))
            assertFalse(presentation.label.contains("active", ignoreCase = true))
        }

        assertEquals(
            "Paused",
            DeliveryStatusPresenter.present(
                SyncRouteStatus(RouteKind.LAN, RoutePhase.AUTHENTICATED),
                paired = true,
                enabled = false,
            ).label,
        )
        assertEquals(
            "Not paired",
            DeliveryStatusPresenter.present(
                SyncRouteStatus(RouteKind.LAN, RoutePhase.AUTHENTICATED),
                paired = false,
                enabled = true,
            ).label,
        )
    }

    @Test
    fun publicRouteStatusIncludesTheNativePresentationWithoutSensitiveTransportDetails() {
        val public = SyncRouteStatus(
            route = RouteKind.RELAY,
            phase = RoutePhase.AUTHENTICATED,
            awaitingPeerCount = 1,
            heldByRelayCount = 1,
            deliveryReason = DeliveryReason.RELAY_HOLDING,
        ).toPublicMap()

        val presentation = assertIs<Map<*, *>>(public["presentation"])
        assertEquals("Via relay", presentation["label"])
        assertEquals(
            "1 notification is stored securely and waiting for your other phone.",
            presentation["explanation"],
        )
        assertFalse(presentation.values.joinToString().contains("wss://"))
        assertFalse(presentation.keys.any { it in setOf("relay_url", "peer_id", "token", "content") })
    }

    @Test
    fun foregroundWithoutEffectivePostAvailabilityUpdatesHealthButDoesNotRequestMaterialization() {
        var permission: Boolean? = null
        var requests = 0

        resumePermissionBlockedMaterializationOnForeground(
            postPermissionGranted = false,
            setPostPermission = { permission = it },
            requestActiveMaterialization = { _ -> requests += 1 },
        )

        assertEquals(false, permission)
        assertEquals(0, requests)
    }

    @Test
    fun foregroundWithEffectivePostAvailabilityRequestsOneRestorationPass() {
        val triggers = mutableListOf<MaterializationTrigger>()

        resumePermissionBlockedMaterializationOnForeground(
            postPermissionGranted = true,
            setPostPermission = {},
            requestActiveMaterialization = triggers::add,
        )

        assertEquals(listOf(MaterializationTrigger.POST_PERMISSION_AVAILABLE), triggers)
    }

    @Test
    fun postAvailabilitySelectsTheClosedWorldMaterializationTrigger() {
        assertEquals(MaterializationTrigger.ROUTINE, materializationTriggerForPostAvailability(false))
        assertEquals(MaterializationTrigger.POST_PERMISSION_AVAILABLE, materializationTriggerForPostAvailability(true))
    }

    @Test
    fun lifecycleCallsRecoveryBeforeTheTestedForegroundMaterializationContract() {
        val sourceRoot = File(System.getProperty("user.dir"), "src/main/java/co/twinotify/core")
        val moduleSource = File(sourceRoot, "TwinotifyCoreModule.kt").readText()
        val foregroundBlock = moduleSource
            .substringAfter("OnActivityEntersForeground")
            .substringBefore("OnDestroy")
        assertSourceOrder(
            foregroundBlock,
            "TransportRecoveryAuthority.recover(",
            "RecoveryTrigger.APP_FOREGROUND",
            "SyncService.onAppForeground(ctx)",
        )
        assertFalse(foregroundBlock.contains("startForegroundService"))

        val serviceSource = File(sourceRoot, "service/SyncService.kt").readText()
        val foregroundHook = serviceSource
            .substringAfter("fun onAppForeground")
            .substringBefore("private val scope")
        assertSourceOrder(
            foregroundHook,
            "effectivePostAvailability(context)",
            "resumePermissionBlockedMaterializationOnForeground(",
        )
        val serviceStartup = serviceSource
            .substringAfter("override fun onCreate()")
            .substringBefore("override fun onStartCommand")
        assertTrue(serviceStartup.contains("materializationTriggerForPostAvailability(postAvailable)"))

        val listenerSource = File(sourceRoot, "listener/TwinotifyNotificationListener.kt").readText()
        val listenerStartup = listenerSource
            .substringAfter("override fun onCreate()")
            .substringBefore("override fun onDestroy")
        assertTrue(listenerStartup.contains("materializationTriggerForPostAvailability(postAvailable)"))
    }

    @Test
    fun serviceOwnsDefaultNetworkHandoffRecoveryForItsFullTransportLifetime() {
        val sourceRoot = File(System.getProperty("user.dir"), "src/main/java/co/twinotify/core")
        val observer = File(sourceRoot, "service/DefaultNetworkChangeObserver.kt").readText()
        val service = File(sourceRoot, "service/SyncService.kt").readText()
        val start = service.substringAfter("onStart = {").substringBefore("START_STICKY")
        val destroy = service
            .substringAfter("override fun onDestroy()")
            .substringBefore("override fun onBind")
        val unpair = service
            .substringAfter("private suspend fun shutdownForUnpair")
            .substringBefore("private fun startTransport")
        val actionStop = service
            .substringAfter("private suspend fun finalizeActionStop")
            .substringBefore("private fun stopCallCapture")

        assertTrue(observer.contains("registerDefaultNetworkCallback"))
        assertSourceOrder(
            start,
            "ensureDefaultNetworkObserver()",
            "routePreferenceRestarter.ensureStarted()",
        )
        assertTrue(destroy.contains("stopDefaultNetworkObserver()"))
        assertTrue(unpair.contains("stopDefaultNetworkObserver()"))
        assertTrue(actionStop.contains("stopDefaultNetworkObserver()"))
    }

    @Test
    fun listenerHealthPermissionRemainsIndependentFromPostAvailability() {
        val sourceRoot = File(System.getProperty("user.dir"), "src/main/java/co/twinotify/core")
        val listenerSource = File(sourceRoot, "listener/TwinotifyNotificationListener.kt").readText()
        val onCreateBlock = listenerSource
            .substringAfter("override fun onCreate()")
            .substringBefore("override fun onDestroy")

        assertTrue(onCreateBlock.contains("permission = true"))
    }

    @Test
    fun mirrorCancellationRetainsAdvertisedActionsForThePendingIntentTtl() {
        val sourceRoot = File(System.getProperty("user.dir"), "src/main/java/co/twinotify/core")
        val notificationPortSource = File(sourceRoot, "service/AndroidNotificationPort.kt").readText()
        val cancelBlock = notificationPortSource
            .substringAfter("override fun cancelMirror")
            .substringBefore("override fun cancelSource")

        assertFalse(cancelBlock.contains("ProcessMirrorAdvertisedActions.purge"))
    }

    @Test
    fun callCaptureAdmissionGenerationIsWiredFromModuleIntentThroughServiceRecovery() {
        val sourceRoot = File(System.getProperty("user.dir"), "src/main/java/co/twinotify/core")
        val moduleSource = File(sourceRoot, "TwinotifyCoreModule.kt").readText()
        val enableBlock = moduleSource
            .substringAfter("AsyncFunction(\"setCallCaptureEnabled\")")
            .substringBefore("AsyncFunction(\"getCallCaptureEnabled\")")
        assertSourceOrder(
            enableBlock,
            "::beginCallCaptureAdmission",
            "co.twinotify.core.service.SyncService.EXTRA_CALL_CAPTURE_ADMISSION_GENERATION",
            "admissionTicket.generation",
            "ctx.startForegroundService(intent)",
            "::awaitCallCaptureAdmission",
        )

        val serviceSource = File(sourceRoot, "service/SyncService.kt").readText()
        val onStartBlock = serviceSource
            .substringAfter("override fun onStartCommand")
            .substringBefore("override fun onDestroy")
        assertSourceOrder(
            onStartBlock,
            "parseCallCaptureAdmissionGeneration(",
            "callCaptureAdmissionGeneration",
            "recoverCallsBeforeNormalCapture(callCaptureAdmissionGeneration)",
        )

        val recoveryBlock = serviceSource
            .substringAfter("private fun recoverCallsBeforeNormalCapture")
            .substringBefore("private fun configureCallCapture")
        assertSourceOrder(
            recoveryBlock,
            "routeCallCaptureAdmissionForServiceStart(",
            "generation = admissionGeneration",
            "startRecovery =",
            "completeCallCaptureAdmissionForServiceStart(",
        )
    }

    private fun assertSourceOrder(source: String, vararg fragments: String) {
        var cursor = -1
        for (fragment in fragments) {
            val next = source.indexOf(fragment, startIndex = cursor + 1)
            assertTrue(next >= 0, "missing production wiring fragment: $fragment")
            assertTrue(next > cursor, "production wiring fragment is out of order: $fragment")
            cursor = next
        }
    }

    @Test
    fun foregroundRequestPromotesBeforeNoRoutePolicyReadsAndStop() {
        val order = mutableListOf<String>()

        val result = executeForegroundServiceRequest(
            action = SyncService.ACTION_START,
            promote = { order += "foreground" },
            decideConfiguredStart = {
                order += "config"
                val config = ServiceStartPolicy.applyLanOnlyStart(ServiceConfig())
                order += "peer"
                order += "policy"
                ServiceStartPolicy.decide(
                    SyncService.ACTION_START,
                    config,
                    paired = true,
                    lanBound = false,
                )
            },
            onActionStop = { error("not an ACTION_STOP request") },
            onPolicyStop = {
                order += "stop:${it.reason}"
                it.reason
            },
            onStart = { error("missing LAN binding must not start") },
        )

        assertEquals("no_route_available", result)
        assertEquals(
            listOf("foreground", "config", "peer", "policy", "stop:no_route_available"),
            order,
        )
    }

    @Test
    fun freshActionStopPromotesWithoutReadingConfiguration() {
        val order = mutableListOf<String>()

        val result = executeForegroundServiceRequest(
            action = SyncService.ACTION_STOP,
            promote = { order += "foreground" },
            decideConfiguredStart = { error("ACTION_STOP must not read lifecycle state") },
            onActionStop = {
                order += "action-stop"
                "user_disabled"
            },
            onPolicyStop = { error("ACTION_STOP is not a configured policy stop") },
            onStart = { error("ACTION_STOP must not start") },
        )

        assertEquals("user_disabled", result)
        assertEquals(listOf("foreground", "action-stop"), order)
    }

    @Test
    fun validConfiguredStartPromotesExactlyOnceBeforeStarting() {
        val order = mutableListOf<String>()

        val result = executeForegroundServiceRequest(
            action = SyncService.ACTION_START,
            promote = { order += "foreground" },
            decideConfiguredStart = {
                order += "config"
                ServiceStartPolicy.decide(
                    SyncService.ACTION_START,
                    ServiceConfig(enabled = true, relayUrl = relayUrl),
                    paired = true,
                )
            },
            onActionStop = { error("not an ACTION_STOP request") },
            onPolicyStop = { error("configured relay must start") },
            onStart = {
                order += "start"
                "started"
            },
        )

        assertEquals("started", result)
        assertEquals(listOf("foreground", "config", "start"), order)
    }

    @Test
    fun foregroundNotificationOwnerSkipsSameSnapshotAndRefreshesChangedHealth() {
        val owner = ForegroundNotificationOwner<String>()
        val rendered = mutableListOf<String>()

        owner.promote("connecting", rendered::add)
        owner.promote("connecting", rendered::add)
        owner.refresh("connecting", rendered::add)
        owner.refresh("connected", rendered::add)

        assertEquals(listOf("connecting", "connected"), rendered)
    }

    @Test
    fun foregroundNotificationOwnerStopsRefreshingAfterRemoval() {
        val owner = ForegroundNotificationOwner<String>()
        val rendered = mutableListOf<String>()

        owner.promote("connecting", rendered::add)
        owner.remove()
        owner.refresh("connected", rendered::add)

        assertEquals(listOf("connecting"), rendered)
    }

    @Test
    fun duplicateActiveStartDoesNotAdvanceTransportGeneration() {
        var generations = 0

        assertFalse(admitTransportGeneration(currentActive = true) { generations++ })
        assertEquals(0, generations)
        assertTrue(admitTransportGeneration(currentActive = false) { generations++ })
        assertEquals(1, generations)
    }

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
        val enabled = ServiceStartPolicy.applyLanOnlyStart(
            ServiceConfig(enabled = false, relayUrl = "wss://stale-relay.example"),
        )

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
    fun protocolFloorUpgradeReconcilesAlreadyAuthenticatedRelayHealth() {
        val relay = SyncRouteStatus(RouteKind.RELAY, RoutePhase.AUTHENTICATED, 0)
        SyncServiceStatus.setRouteStatus(relay)
        SyncServiceStatus.setState(relay.toSyncState(protocolFloor = 1))

        SyncServiceStatus.setProtocolFloor(2)

        assertEquals(SyncState.CONNECTED, SyncServiceStatus.state.value)
        assertEquals("connected", SyncServiceStatus.health.value.service)
        assertEquals("online", SyncServiceStatus.health.value.transport)
    }

    @Test
    fun routeStatusCarriesNoNetworkDetail() {
        SyncServiceStatus.setRouteStatus(SyncRouteStatus(RouteKind.LAN, RoutePhase.AUTHENTICATED, 1))

        val rendered = SyncServiceStatus.routeStatus.value.toPublicMap()

        assertEquals(
            setOf(
                "route", "phase", "queued_count", "pending_local_count", "awaiting_peer_count",
                "held_by_relay_count", "peer_evidence", "delivery_reason", "user_content_kind",
                "route_generation", "recovery_issue", "presentation",
            ),
            rendered.keys,
        )
        assertEquals("lan", rendered["route"])
        assertEquals("authenticated", rendered["phase"])
    }

    @Test
    fun stoppingClearsTheRouteStatusSoNoStaleRouteIsShown() {
        val generation = SyncServiceStatus.beginRouteGeneration()
        SyncServiceStatus.setRouteStatus(SyncRouteStatus(RouteKind.LAN, RoutePhase.AUTHENTICATED, 3))

        SyncServiceStatus.clearRouteStatus()

        assertEquals(RouteKind.NONE, SyncServiceStatus.routeStatus.value.route)
        assertEquals(RoutePhase.IDLE, SyncServiceStatus.routeStatus.value.phase)
        assertEquals(generation, SyncServiceStatus.routeStatus.value.routeGeneration)
    }

    @Test
    fun concurrentRouteUpdatesCannotPublishAnOlderGeneration() {
        val start = CountDownLatch(1)
        val maximum = AtomicInteger(SyncServiceStatus.routeStatus.value.routeGeneration)
        val generator = Thread {
            start.await()
            repeat(500) {
                maximum.accumulateAndGet(SyncServiceStatus.beginRouteGeneration(), ::maxOf)
            }
        }
        val observer = Thread {
            start.await()
            repeat(500) {
                SyncServiceStatus.setRouteStatus(SyncRouteStatus(RouteKind.LAN, RoutePhase.AUTHENTICATED))
            }
        }
        generator.start()
        observer.start()
        start.countDown()
        generator.join()
        observer.join()

        assertEquals(maximum.get(), SyncServiceStatus.routeStatus.value.routeGeneration)
    }

    @Test
    fun deliveryReasonUsesTheApprovedPriorityOrder() {
        val waiting = DeliveryQueueSnapshot(
            pendingLocal = 1,
            awaitingPeer = 3,
            heldByRelay = 2,
            internalActive = 7,
            totalActive = 11,
            totalActiveBytes = 99,
            userContentKind = UserContentKind.SYNC_UPDATES,
        )
        val base = SyncRouteStatus(RouteKind.NONE, RoutePhase.RECONNECTING)

        assertEquals(
            DeliveryReason.LAN_BINDING_CONFLICT,
            DeliveryStatusModel.resolve(
                base,
                waiting,
                DeliveryConditions(bindingConflict = true, peerVersionIncompatible = true, bootstrapWaiting = true),
                PeerEvidence.RECENT,
            ).deliveryReason,
        )
        assertEquals(
            DeliveryReason.PEER_VERSION_INCOMPATIBLE,
            DeliveryStatusModel.resolve(
                base,
                waiting,
                DeliveryConditions(peerVersionIncompatible = true, bootstrapWaiting = true),
                PeerEvidence.RECENT,
            ).deliveryReason,
        )
        assertEquals(
            DeliveryReason.NO_ROUTE,
            DeliveryStatusModel.resolve(
                base,
                waiting,
                DeliveryConditions(bootstrapWaiting = true),
                PeerEvidence.RECENT,
            ).deliveryReason,
        )
        assertEquals(
            DeliveryReason.RELAY_HOLDING,
            DeliveryStatusModel.resolve(
                base.copy(route = RouteKind.RELAY, phase = RoutePhase.AUTHENTICATED),
                waiting.copy(pendingLocal = 0),
                DeliveryConditions(bootstrapWaiting = true),
                PeerEvidence.RECENT,
            ).deliveryReason,
        )
        assertEquals(
            DeliveryReason.WAITING_FOR_PEER,
            DeliveryStatusModel.resolve(
                base.copy(route = RouteKind.RELAY, phase = RoutePhase.AUTHENTICATED),
                waiting.copy(pendingLocal = 0, heldByRelay = 0),
                DeliveryConditions(bootstrapWaiting = true),
                PeerEvidence.RECENT,
            ).deliveryReason,
        )
        assertEquals(
            DeliveryReason.LAN_BOOTSTRAP_WAITING,
            DeliveryStatusModel.resolve(
                base.copy(route = RouteKind.RELAY, phase = RoutePhase.AUTHENTICATED),
                waiting.copy(pendingLocal = 0, awaitingPeer = 0, heldByRelay = 0),
                DeliveryConditions(bootstrapWaiting = true),
                PeerEvidence.RECENT,
            ).deliveryReason,
        )
    }

    @Test
    fun classifiedQueueKeepsProductAndEngineeringCountsSeparate() {
        val generation = SyncServiceStatus.beginRouteGeneration()
        val snapshot = DeliveryQueueSnapshot(
            pendingLocal = 2,
            awaitingPeer = 3,
            heldByRelay = 1,
            internalActive = 9,
            totalActive = 14,
            totalActiveBytes = 456,
            userContentKind = UserContentKind.NOTIFICATIONS,
        )

        SyncServiceStatus.setQueueSnapshot(snapshot, generation)

        val route = SyncServiceStatus.routeStatus.value
        assertEquals(2, route.queuedCount)
        assertEquals(route.pendingLocalCount, route.queuedCount)
        assertEquals(3, route.awaitingPeerCount)
        assertEquals(1, route.heldByRelayCount)
        assertEquals("notifications", route.toPublicMap()["user_content_kind"])
        assertEquals(2, SyncServiceStatus.health.value.queuedCount)
        assertEquals(14, SyncServiceStatus.health.value.totalActiveCount)
        assertEquals(456, SyncServiceStatus.health.value.totalActiveBytes)
    }

    @Test
    fun publicPeerEvidenceDistinguishesDirectRecentStaleAndUnknown() {
        val empty = DeliveryQueueSnapshot(0, 0, 0, 0, 0, 0, UserContentKind.NOTIFICATIONS)
        val relay = SyncRouteStatus(RouteKind.RELAY, RoutePhase.AUTHENTICATED)

        assertEquals(
            PeerEvidence.DIRECT,
            DeliveryStatusModel.resolve(
                relay.copy(route = RouteKind.LAN), empty, DeliveryConditions(), PeerEvidence.UNKNOWN,
            ).peerEvidence,
        )
        assertEquals(
            PeerEvidence.RECENT,
            DeliveryStatusModel.resolve(relay, empty, DeliveryConditions(), PeerEvidence.RECENT).peerEvidence,
        )
        assertEquals(
            PeerEvidence.STALE,
            DeliveryStatusModel.resolve(relay, empty, DeliveryConditions(), PeerEvidence.STALE).peerEvidence,
        )
        assertEquals(
            PeerEvidence.UNKNOWN,
            DeliveryStatusModel.resolve(relay, empty, DeliveryConditions(), PeerEvidence.UNKNOWN).peerEvidence,
        )
    }

    // ---- PB-008: automatic transport recovery ---------------------------

    @Test
    fun recoveryPolicyRequiresDurableIntentPairRouteAndPermissions() {
        val ready = RecoveryInputs(
            persisted = ServiceConfig(enabled = true, relayUrl = relayUrl),
            paired = true,
            lanBound = false,
            listenerPermission = true,
            postPermission = true,
            serviceActive = false,
        )

        assertIs<RecoveryDecision.Start>(RecoveryPolicy.decide(ready))
        assertEquals(
            "disabled",
            assertIs<RecoveryDecision.NoAction>(
                RecoveryPolicy.decide(ready.copy(persisted = ready.persisted.copy(enabled = false))),
            ).reason,
        )
        assertEquals(
            "not_paired",
            assertIs<RecoveryDecision.NoAction>(RecoveryPolicy.decide(ready.copy(paired = false))).reason,
        )
        assertEquals(
            "no_route_available",
            assertIs<RecoveryDecision.NoAction>(
                RecoveryPolicy.decide(ready.copy(persisted = ServiceConfig(enabled = true, relayUrl = null))),
            ).reason,
        )
        assertEquals(
            RecoveryIssue.NOTIFICATION_ACCESS_REQUIRED,
            assertIs<RecoveryDecision.Blocked>(
                RecoveryPolicy.decide(ready.copy(listenerPermission = false)),
            ).issue,
        )
        assertEquals(
            RecoveryIssue.POST_NOTIFICATIONS_REQUIRED,
            assertIs<RecoveryDecision.Blocked>(
                RecoveryPolicy.decide(ready.copy(postPermission = false)),
            ).issue,
        )
        assertIs<RecoveryDecision.AlreadyRunning>(
            RecoveryPolicy.decide(ready.copy(serviceActive = true)),
        )
        assertEquals(
            "notification_access_required",
            assertIs<ServiceStartDecision.Stop>(
                RecoveryPolicy.decideServiceStart(ready.copy(listenerPermission = false)),
            ).reason,
        )
        assertIs<ServiceStartDecision.Start>(RecoveryPolicy.decideServiceStart(ready))
    }

    @Test
    fun recoveryStartGateCoalescesLifecycleRacesButAllowsLaterRetry() {
        val gate = RecoveryStartGate(coalesceMillis = 10_000L)

        assertTrue(gate.reserve(serviceActive = false, nowMillis = 1_000L))
        assertFalse(gate.reserve(serviceActive = false, nowMillis = 1_001L))
        assertTrue(gate.reserve(serviceActive = false, nowMillis = 11_000L))

        gate.release()
        assertTrue(gate.reserve(serviceActive = false, nowMillis = 11_001L))
        gate.serviceBecameActive()
        assertFalse(gate.reserve(serviceActive = true, nowMillis = 11_002L))
    }

    @Test
    fun deniedRecoveryStartReleasesGateAndKeepsEnabledIntent() {
        val gate = RecoveryStartGate(coalesceMillis = 10_000L)
        val persisted = ServiceConfig(enabled = true, relayUrl = relayUrl)

        val result = executeRecoveryStart(
            decision = RecoveryDecision.Start(ServiceStartDecision.Start(relayUrl, lanBound = false)),
            gate = gate,
            serviceActive = false,
            nowMillis = 5_000L,
            start = { throw IllegalStateException("platform denied") },
            classifyFailure = { RecoveryIssue.BACKGROUND_START_DENIED },
        )

        assertEquals(
            RecoveryExecution.Blocked(RecoveryIssue.BACKGROUND_START_DENIED),
            result,
        )
        assertTrue(persisted.enabled)
        assertTrue(gate.reserve(serviceActive = false, nowMillis = 5_001L))
    }

    @Test
    fun recoveryPresentationExplainsPermissionAndPlatformBlocks() {
        val idle = SyncRouteStatus(route = RouteKind.NONE, phase = RoutePhase.IDLE)
        val notificationAccess = DeliveryStatusPresenter.present(
            idle.copy(recoveryIssue = RecoveryIssue.NOTIFICATION_ACCESS_REQUIRED),
            paired = true,
            enabled = true,
        )
        assertEquals("Notification access needed", notificationAccess.label)
        assertEquals("permissions", notificationAccess.action)

        val postPermission = DeliveryStatusPresenter.present(
            idle.copy(recoveryIssue = RecoveryIssue.POST_NOTIFICATIONS_REQUIRED),
            paired = true,
            enabled = true,
        )
        assertEquals("Notifications need attention", postPermission.label)
        assertEquals("permissions", postPermission.action)

        val denied = DeliveryStatusPresenter.present(
            idle.copy(recoveryIssue = RecoveryIssue.BACKGROUND_START_DENIED),
            paired = true,
            enabled = true,
        )
        assertEquals("Open Twinotify to resume", denied.label)
        assertEquals("retry", denied.action)
    }

    @Test
    fun recoveryLifecycleUsesOneAuthorityForForegroundBootReplacementAndRetry() {
        val projectDir = File(requireNotNull(System.getProperty("user.dir")))
        val sourceRoot = File(projectDir, "src/main/java/co/twinotify/core")
        val manifest = File(projectDir, "src/main/AndroidManifest.xml").readText()
        val receiver = File(sourceRoot, "service/BootReceiver.kt").readText()
        val module = File(sourceRoot, "TwinotifyCoreModule.kt").readText()
        val authority = File(sourceRoot, "service/TransportRecoveryAuthority.kt").readText()

        assertTrue(manifest.contains("android.intent.action.BOOT_COMPLETED"))
        assertTrue(manifest.contains("android.intent.action.MY_PACKAGE_REPLACED"))
        assertTrue(receiver.contains("goAsync()"))
        assertTrue(receiver.contains("TransportRecoveryAuthority.recover"))
        assertTrue(receiver.contains("RecoveryTrigger.BOOT_COMPLETED"))
        assertTrue(receiver.contains("RecoveryTrigger.PACKAGE_REPLACED"))
        assertTrue(module.contains("RecoveryTrigger.APP_FOREGROUND"))
        assertTrue(module.contains("RecoveryTrigger.USER_RETRY"))
        assertFalse(authority.contains("putExtra(SyncService.EXTRA_RELAY_URL"))
    }

    @Test
    fun staleGenerationCannotOverwriteRouteOrQueueTruth() {
        val current = SyncServiceStatus.beginRouteGeneration()
        SyncServiceStatus.setRouteStatus(
            SyncRouteStatus(RouteKind.LAN, RoutePhase.AUTHENTICATED),
            current,
        )
        val stale = current - 1
        SyncServiceStatus.setRouteStatus(
            SyncRouteStatus(RouteKind.RELAY, RoutePhase.AUTHENTICATED),
            stale,
        )
        SyncServiceStatus.setQueueSnapshot(
            DeliveryQueueSnapshot(8, 0, 0, 0, 8, 80, UserContentKind.NOTIFICATIONS),
            stale,
        )

        assertEquals(RouteKind.LAN, SyncServiceStatus.routeStatus.value.route)
        assertEquals(0, SyncServiceStatus.routeStatus.value.pendingLocalCount)
        assertEquals(current, SyncServiceStatus.routeStatus.value.routeGeneration)
    }

    @Test
    fun publicRouteStatusContainsOnlyApprovedPrivacySafeFields() {
        val rendered = DeliveryStatusModel.resolve(
            SyncRouteStatus(RouteKind.RELAY, RoutePhase.AUTHENTICATED, routeGeneration = 7),
            DeliveryQueueSnapshot(1, 2, 2, 4, 7, 700, UserContentKind.SYNC_UPDATES),
            DeliveryConditions(),
            PeerEvidence.RECENT,
        ).toPublicMap()

        assertEquals(
            setOf(
                "route", "phase", "queued_count", "pending_local_count", "awaiting_peer_count",
                "held_by_relay_count", "peer_evidence", "delivery_reason", "user_content_kind",
                "route_generation", "recovery_issue", "presentation",
            ),
            rendered.keys,
        )
        assertFalse(rendered.keys.any { it.contains("url") || it.contains("ip") || it.contains("ssid") })
    }
}
