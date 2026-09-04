package co.twinotify.core.service

import co.twinotify.core.direct.DirectDeliveryEvent
import co.twinotify.core.lan.AuthenticatedLanConnection
import co.twinotify.core.lan.LanCandidate
import co.twinotify.core.lan.LanConnectionRole
import co.twinotify.core.lan.LanDialer
import co.twinotify.core.lan.LanDiscovery
import co.twinotify.core.lan.LanFrame
import co.twinotify.core.lan.LanListener
import co.twinotify.core.lan.LanNetwork
import co.twinotify.core.storage.LanBinding
import co.twinotify.core.storage.OutboundMessage
import co.twinotify.core.storage.PeerRecord
import java.net.InetAddress
import java.net.Socket
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext

@OptIn(ExperimentalCoroutinesApi::class)
class LiveTransportRoutesTest {
    @Test
    fun custodyObservationsUseTypedEventsAndIgnoreUnknownTypes() {
        ProductObservationTracker.clear()

        recordRelayCustodyObservation(TransportEvent.RelayAccepted("relay", 1L, "unpair"))
        recordLanCustodyObservation(
            co.twinotify.core.lan.LanTransportEvent.PeerAccepted("lan", "peer.receipt"),
        )
        recordRelayCustodyObservation(TransportEvent.RelayAccepted("stale", 2L, null))
        recordBluetoothCustodyObservation(DirectDeliveryEvent.PeerAccepted("bt", "notif.post"))

        val snapshot = ProductObservationTracker.snapshot()
        assertEquals(1L, snapshot.custodyCounts.getValue("relay").getValue("unpair"))
        assertEquals(1L, snapshot.custodyCounts.getValue("lan").getValue("peer_receipt"))
        assertEquals(1L, snapshot.custodyCounts.getValue("bluetooth").getValue("notif_post"))
        assertEquals(0L, snapshot.custodyCounts.getValue("relay").getValue("notif_post"))
    }

    @Test
    fun relayDeliveryFinalizesPostCustodyResultImmediately() = runTest {
        val order = mutableListOf<String>()
        dispatchRelayDeliveryWithFinalization {
            order += "dispatch"
            InboundDispatchResult.AcceptedAfterCustody("msg", "a".repeat(64)) {
                order += "finalize"
            }
        }
        assertEquals(listOf("dispatch", "finalize"), order)
    }

    @Test
    fun rejectedRelayDeliveryClosesTheRouteInsteadOfBecomingUnitSuccess() = runTest {
        var dispatches = 0
        val route = LiveRelayTransportRoute(
            events = {
                flow {
                    emit(TransportEvent.Authenticated(2))
                    emit(TransportEvent.Delivery(1, "conflict"))
                    emit(TransportEvent.Delivery(2, "must-not-run"))
                }
            },
            hooks = LiveRelayRouteHooks(
                dispatch = {
                    dispatches += 1
                    InboundDispatchResult.Rejected("id_conflict")
                },
            ),
        )

        val session = route.open()

        assertEquals("inbound_rejected", session.awaitClosed())
        assertEquals(1, dispatches)
    }

    @Test
    fun authenticatedRouteAcceptanceCompletesOnlyMatchingUnpairReservations() = runTest {
        val tracker = UnpairCustodyTracker()
        val relay = tracker.reserve("relay-unpair")
        val lan = tracker.reserve("lan-unpair")
        val bluetooth = tracker.reserve("bluetooth-unpair")

        assertFalse(acceptRelayUnpairCustody(TransportEvent.RelayAccepted("stale", 1L), tracker))
        assertTrue(acceptRelayUnpairCustody(TransportEvent.RelayAccepted("relay-unpair", 2L), tracker))
        assertTrue(
            acceptLanUnpairCustody(
                co.twinotify.core.lan.LanTransportEvent.PeerAccepted("lan-unpair"),
                tracker,
            ),
        )
        assertFalse(acceptBluetoothUnpairCustody(DirectDeliveryEvent.PeerAccepted("stale", null), tracker))
        assertTrue(acceptBluetoothUnpairCustody(DirectDeliveryEvent.PeerAccepted("bluetooth-unpair", "unpair"), tracker))

        assertEquals(CustodyRoute.RELAY, relay.await(5_000L))
        assertEquals(CustodyRoute.LAN, lan.await(5_000L))
        assertEquals(CustodyRoute.BLUETOOTH, bluetooth.await(5_000L))
        assertEquals(0, tracker.pendingCount())
    }

    @Test
    fun validatedBluetoothBindingBuildsBluetoothRouteIndependentlyOfLan() = runTest {
        val expectedBluetooth = route(RouteKind.BLUETOOTH)
        val relay = route(RouteKind.RELAY)
        var config: LiveBluetoothRouteConfig? = null
        val routes = LiveTransportRoutesFactory(
            LiveTransportRouteDependencies(
                loadPeer = { PEER },
                loadValidatedBinding = { throw IllegalStateException("sealed record invalid") },
                loadLocalIdentity = { LiveLocalRouteIdentity(LOCAL, LOCAL_SIGNING_KEY) },
                buildLanRoute = { error("invalid LAN trust must not build LAN") },
                buildRelayRoute = { relay },
                loadValidatedBluetoothBinding = { peer -> BLUETOOTH_BINDING.takeIf { it.peerDeviceId == peer.deviceId } },
                buildBluetoothRoute = { built -> config = built; expectedBluetooth },
            ),
        ).create(relay = LiveRelayRouteConfig(events = { emptyFlow() }), utcEpochDay = DAY)

        assertNull(routes.lan, "a corrupt LAN binding must not disable Bluetooth")
        assertSame(expectedBluetooth, routes.bluetooth)
        assertSame(relay, routes.relay)
        assertEquals(BLUETOOTH_BINDING.associationId, config?.associationId)
        assertEquals(LOCAL, config?.localDeviceId)
        assertEquals(PEER.deviceId, config?.peerDeviceId)
        assertContentEquals(LOCAL_SIGNING_KEY, config?.localSigningKey)
        assertContentEquals(PEER.signPubkey, config?.peerSigningKey)
        assertEquals(BLUETOOTH_BINDING.protocolVersion, config?.protocolVersion)
    }

    @Test
    fun staleBluetoothAssociationDisablesOnlyBluetoothAndPreservesLanAndRelay() = runTest {
        val expectedLan = route(RouteKind.LAN)
        val relay = route(RouteKind.RELAY)
        for (bluetoothLoader in listOf<suspend (PeerRecord) -> co.twinotify.core.bluetooth.BluetoothBinding?>(
            { null },
            { throw IllegalStateException("association store unavailable") },
        )) {
            val routes = LiveTransportRoutesFactory(
                LiveTransportRouteDependencies(
                    loadPeer = { PEER },
                    loadValidatedBinding = { BINDING },
                    loadLocalIdentity = { LiveLocalRouteIdentity(LOCAL, LOCAL_SIGNING_KEY) },
                    buildLanRoute = { expectedLan },
                    buildRelayRoute = { relay },
                    loadValidatedBluetoothBinding = bluetoothLoader,
                    buildBluetoothRoute = { error("stale association must not build Bluetooth") },
                ),
            ).create(relay = LiveRelayRouteConfig(events = { emptyFlow() }), utcEpochDay = DAY)

            assertSame(expectedLan, routes.lan)
            assertNull(routes.bluetooth)
            assertSame(relay, routes.relay)
        }
    }

    @Test
    fun noDirectBindingAtAllSkipsIdentityAndKeepsRelay() = runTest {
        val relay = route(RouteKind.RELAY)
        var identityLoads = 0
        val routes = LiveTransportRoutesFactory(
            LiveTransportRouteDependencies(
                loadPeer = { PEER },
                loadValidatedBinding = { null },
                loadLocalIdentity = { identityLoads++; LiveLocalRouteIdentity(LOCAL, LOCAL_SIGNING_KEY) },
                buildLanRoute = { error("no LAN trust") },
                buildRelayRoute = { relay },
                loadValidatedBluetoothBinding = { null },
                buildBluetoothRoute = { error("no Bluetooth trust") },
            ),
        ).create(relay = LiveRelayRouteConfig(events = { emptyFlow() }), utcEpochDay = DAY)

        assertNull(routes.lan)
        assertNull(routes.bluetooth)
        assertSame(relay, routes.relay)
        assertEquals(0, identityLoads)
    }

    @Test
    fun bluetoothRouteRefusesToOpenWhenTheBindingNoLongerValidates() = runTest {
        var linkOpens = 0
        val route = LiveBluetoothTransportRoute(
            bluetoothConfig(),
            linkFactory = { linkOpens++; error("links must not open") },
            sessionFactory = { error("nothing to authenticate") },
            allowAttempt = { false },
        )

        assertFailsWith<IllegalStateException> { route.open() }
        assertEquals(0, linkOpens)
    }

    @Test
    fun relayRouteRefusesToOpenWhenTheDebugFaultIsArmed() = runTest {
        var eventReads = 0
        val route = LiveRelayTransportRoute(
            events = { eventReads++; error("relay events must not be collected") },
            hooks = LiveRelayRouteHooks(dispatch = { null }),
            allowAttempt = { false },
        )

        assertEquals(RouteKind.RELAY, route.kind)
        assertFailsWith<IllegalStateException> { route.open() }
        assertEquals(0, eventReads)
    }

    @Test
    fun bluetoothRouteRevalidatesAndBuildsOneConnectorPerOpen() = runTest {
        val validations = mutableListOf<Boolean>()
        val links = mutableListOf<LiveBluetoothRouteConfig>()
        val opened = mutableListOf<RecordingLinks>()
        val wires = mutableListOf<co.twinotify.core.bluetooth.AuthenticatedBluetoothWire>()
        val route = LiveBluetoothTransportRoute(
            bluetoothConfig(),
            linkFactory = { config -> links += config; RecordingLinks().also(opened::add) },
            sessionFactory = { wire -> wires += wire; RecordingSession(RouteKind.BLUETOOTH) },
            allowAttempt = { validations += true; true },
            connect = { config, _ -> authenticatedWire(config.peerDeviceId) },
        )

        assertEquals(RouteKind.BLUETOOTH, route.kind)
        route.open().close("first")
        route.open().close("second")

        assertEquals(listOf(true, true), validations)
        assertEquals(2, links.size)
        assertEquals(listOf(PEER.deviceId, PEER.deviceId), wires.map { it.peerDeviceId })
        assertEquals(listOf(1, 1), opened.map { it.closed }, "a won attempt must still stop advertising and scanning")
    }

    @Test
    fun aFailedBluetoothAttemptStopsAdvertisingAndScanning() = runTest {
        val opened = mutableListOf<RecordingLinks>()
        val route = LiveBluetoothTransportRoute(
            bluetoothConfig(),
            linkFactory = { RecordingLinks().also(opened::add) },
            sessionFactory = { error("no session for a failed attempt") },
            allowAttempt = { true },
            connect = { _, _ -> throw co.twinotify.core.bluetooth.BluetoothConnectException(
                co.twinotify.core.bluetooth.BluetoothConnectFailure.CONNECT_TIMEOUT,
            ) },
        )

        val error = assertFailsWith<co.twinotify.core.bluetooth.BluetoothConnectException> { route.open() }

        assertEquals("bluetooth_connect_timeout", error.message)
        assertEquals(listOf(1), opened.map { it.closed })
    }

    @Test
    fun validatedBindingBuildsLanRouteFromStoredPeerAndLocalIdentity() = runTest {
        val expectedLan = route(RouteKind.LAN)
        var loadedPeer: PeerRecord? = null
        var config: LiveLanRouteConfig? = null
        val factory = LiveTransportRoutesFactory(
            LiveTransportRouteDependencies(
                loadPeer = { PEER },
                loadValidatedBinding = { peer -> loadedPeer = peer; BINDING },
                loadLocalIdentity = { LiveLocalRouteIdentity(LOCAL, LOCAL_SIGNING_KEY) },
                buildLanRoute = { built -> config = built; expectedLan },
                buildRelayRoute = { error("relay should not be built") },
            ),
        )

        val routes = factory.create(relay = null, utcEpochDay = DAY)

        assertSame(PEER, loadedPeer)
        assertSame(expectedLan, routes.lan)
        assertNull(routes.relay)
        assertEquals(LOCAL, config?.localDeviceId)
        assertEquals(PEER.deviceId, config?.peerDeviceId)
        assertContentEquals(LOCAL_SIGNING_KEY, config?.localSigningKey)
        assertContentEquals(PEER.signPubkey, config?.peerSigningKey)
        assertContentEquals(BINDING.peerTlsSpkiSha256, config?.peerTlsSpkiSha256)
    }

    @Test
    fun missingOrCorruptBindingDisablesOnlyLanAndPreservesRelayFallback() = runTest {
        val relay = route(RouteKind.RELAY)
        for (bindingLoader in listOf<suspend (PeerRecord) -> LanBinding?>(
            { null },
            { throw IllegalStateException("sealed record invalid") },
        )) {
            var identityLoads = 0
            val routes = LiveTransportRoutesFactory(
                LiveTransportRouteDependencies(
                    loadPeer = { PEER },
                    loadValidatedBinding = bindingLoader,
                    loadLocalIdentity = { identityLoads++; LiveLocalRouteIdentity(LOCAL, LOCAL_SIGNING_KEY) },
                    buildLanRoute = { error("invalid trust must not build LAN") },
                    buildRelayRoute = { relay },
                ),
            ).create(relay = LiveRelayRouteConfig(events = { emptyFlow() }), utcEpochDay = DAY)

            assertNull(routes.lan)
            assertSame(relay, routes.relay)
            assertEquals(0, identityLoads)
        }
    }

    @Test
    fun advertisementIdsUseTodayAndAdjacentDaysForTheCorrectAdvertiser() {
        val config = config()

        assertEquals(
            co.twinotify.core.lan.LanAdvertisement.derive(BINDING.lanSecret, LOCAL, DAY),
            config.localAdvertisementId,
        )
        assertEquals(
            (-1L..1L).mapTo(linkedSetOf()) {
                co.twinotify.core.lan.LanAdvertisement.derive(BINDING.lanSecret, PEER.deviceId, DAY + it)
            },
            config.expectedPeerAdvertisementIds,
        )
        assertFalse(config.clockSkewPeerAdvertisementIds.any { it in config.expectedPeerAdvertisementIds })
        assertEquals(12, config.clockSkewPeerAdvertisementIds.size)
    }

    @Test
    fun longLivedRouteRecomputesAdvertisementDayForEveryOpen() = runTest {
        val openedDays = mutableListOf<Long>()
        var day = DAY
        val route = LiveLanTransportRoute(
            config(),
            attemptFactory = LiveLanAttemptFactory { opened, _ ->
                openedDays += opened.utcEpochDay
                RecordingAttempt(CompletableDeferred(FakeConnection()))
            },
            sessionFactory = { RecordingSession(RouteKind.LAN) },
            utcEpochDay = { day },
        )

        route.open().close("first")
        day += 1
        route.open().close("next_day")

        assertEquals(listOf(DAY, DAY + 1), openedDays)
    }

    @Test
    fun listenerEphemeralPortIsPublishedOnTheSelectedWifiLease() = runTest {
        val platform = RecordingPlatform()
        val attempt = DefaultLiveLanAttemptFactory(platform).open(config()) {}

        assertEquals(43127, platform.discoveryPort)
        assertSame(platform.lease, platform.listenerLease)
        assertSame(platform.lease, platform.discoveryLease)
        assertSame(platform.lease, platform.dialerLease)

        attempt.close()
        attempt.close()
        assertEquals(1, platform.listenerCloses)
        assertEquals(1, platform.discoveryCloses)
        assertEquals(1, platform.leaseCloses)
    }

    @Test
    fun listenerConstructionWindowLossClosesEveryCreatedResourceExactlyOnce() = runTest {
        val platform = RecordingPlatform()
        val factory = DefaultLiveLanAttemptFactory(
            platform = platform,
            afterListenerRegistered = platform::loseWifi,
        )

        assertFailsWith<IllegalStateException> { factory.open(config()) {} }

        assertEquals(1, platform.listenerCloses)
        assertEquals(0, platform.discoveryCloses)
        assertEquals(1, platform.leaseCloses)
    }

    @Test
    fun initiatorAndAcceptorAgreeOnRolesAndUseTheStoredPeerSigningKey() {
        val config = config()

        val initiator = config.handshake(LanConnectionRole.INITIATOR)
        val acceptor = config.handshake(LanConnectionRole.ACCEPTOR)

        assertEquals(LanConnectionRole.INITIATOR, initiator.localRole)
        assertEquals(LanConnectionRole.ACCEPTOR, acceptor.localRole)
        assertEquals(initiator.localDeviceId, acceptor.localDeviceId)
        assertEquals(initiator.peerDeviceId, acceptor.peerDeviceId)
        assertContentEquals(PEER.signPubkey, initiator.peerSigningKey)
        assertContentEquals(PEER.signPubkey, acceptor.peerSigningKey)
        assertContentEquals(LOCAL_SIGNING_KEY, initiator.localSigningKey)
    }

    @Test
    fun wifiLeaseLossAbortsAnOpeningAttemptAndCleansItOnce() = runTest {
        val attempt = RecordingAttempt(connectResult = CompletableDeferred())
        var onLost: (() -> Unit)? = null
        val route = LiveLanTransportRoute(
            config(),
            attemptFactory = LiveLanAttemptFactory { _, callback -> onLost = callback; attempt },
            sessionFactory = { error("connection must not authenticate") },
        )
        val opening = async { runCatching { route.open() } }
        advanceUntilIdle()

        onLost?.invoke()

        assertTrue(opening.await().exceptionOrNull() is IllegalStateException)
        assertEquals(1, attempt.aborts)
        assertEquals(1, attempt.closes)
    }

    @Test
    fun defaultAttemptClosesConcreteWifiResourcesOnceWhenLeaseIsLostDuringConnect() = runTest {
        val platform = RecordingPlatform()
        val route = LiveLanTransportRoute(
            config(),
            attemptFactory = DefaultLiveLanAttemptFactory(platform),
            sessionFactory = { error("lost network must not authenticate") },
        )
        val opening = async { runCatching { route.open() } }
        runCurrent()

        platform.loseWifi()
        runCurrent()

        assertTrue(opening.await().exceptionOrNull() is IllegalStateException)
        assertEquals(1, platform.listenerCloses)
        assertEquals(1, platform.discoveryCloses)
        assertEquals(1, platform.leaseCloses)
    }

    @Test
    fun routeSessionCleanupIsExactlyOnce() = runTest {
        val connection = FakeConnection()
        val attempt = RecordingAttempt(CompletableDeferred(connection))
        val baseSession = RecordingSession(RouteKind.LAN)
        val route = LiveLanTransportRoute(
            config(),
            attemptFactory = LiveLanAttemptFactory { _, _ -> attempt },
            sessionFactory = { baseSession },
        )

        val session = route.open()
        session.close("first")
        session.close("second")

        assertEquals(1, baseSession.closes)
        assertEquals(1, attempt.closes)
        assertEquals(0, attempt.aborts)
    }

    @Test
    fun relayAdapterOrdersDeliveriesRunsSnapshotHooksAndSelfDrains() = runTest {
        val observed = mutableListOf<String>()
        val lifecycle = mutableListOf<String>()
        var authenticatedFloor = 0
        var authenticatedFeatures = emptySet<String>()
        var expiries = 0
        var upstreamCleanups = 0
        val events = flow {
            try {
                emit(TransportEvent.Connected)
                emit(TransportEvent.Authenticated(2, RelayFeatures.CURRENT))
                emit(TransportEvent.RelayAccepted("out", 1))
                emit(TransportEvent.Delivery(1, "first"))
                emit(TransportEvent.Delivery(2, "second"))
                emit(TransportEvent.RelayExpired("m", 3))
                awaitCancellation()
            } finally {
                upstreamCleanups++
            }
        }
        val route = LiveRelayTransportRoute(
            events = { events },
            hooks = LiveRelayRouteHooks(
                dispatch = { observed += it; null },
                onAuthenticated = { floor, features ->
                    authenticatedFloor = floor
                    authenticatedFeatures = features
                },
                onExpired = { expiries++ },
                onEvent = { lifecycle += it.javaClass.simpleName },
            ),
        )

        val session = route.open()
        advanceUntilIdle()

        assertTrue(session.selfDraining)
        assertEquals(2, authenticatedFloor)
        assertEquals(RelayFeatures.CURRENT, authenticatedFeatures)
        assertEquals(listOf("first", "second"), observed)
        assertEquals(1, expiries)
        assertEquals(
            listOf("Connected", "Authenticated", "RelayAccepted", "Delivery", "Delivery", "RelayExpired"),
            lifecycle,
        )
        assertFailsWith<IllegalStateException> { session.send(message()) }
        session.close("done")
        session.close("again")
        assertEquals(1, upstreamCleanups)
    }

    @Test
    fun relayGenerationJoinWaitsForActualAdapterWorkerFinalizerBeforeReplacementOpens() = runTest {
        val workerFinalizerEntered = CompletableDeferred<Unit>()
        val releaseWorkerFinalizer = CompletableDeferred<Unit>()
        val sessionOpened = CompletableDeferred<Unit>()
        val replacementOpened = CompletableDeferred<Unit>()
        val route = LiveRelayTransportRoute(
            events = {
                flow {
                    try {
                        emit(TransportEvent.Authenticated(2))
                        awaitCancellation()
                    } finally {
                        withContext(NonCancellable) {
                            workerFinalizerEntered.complete(Unit)
                            releaseWorkerFinalizer.await()
                        }
                    }
                }
            },
            hooks = LiveRelayRouteHooks(dispatch = { null }),
        )
        val generation = backgroundScope.launch {
            val session = route.open()
            sessionOpened.complete(Unit)
            try {
                awaitCancellation()
            } finally {
                session.close("generation_replaced")
            }
        }
        sessionOpened.await()
        val replacement = backgroundScope.launch {
            generation.join()
            replacementOpened.complete(Unit)
        }

        generation.cancel()
        runCurrent()
        try {
            assertTrue(workerFinalizerEntered.isCompleted)
            assertFalse(generation.isCompleted, "generation joined before relay worker finalized")
            assertFalse(replacementOpened.isCompleted, "replacement opened beside old relay worker")
        } finally {
            releaseWorkerFinalizer.complete(Unit)
        }
        generation.join()
        replacement.join()
        assertTrue(generation.isCompleted)
        assertTrue(replacementOpened.isCompleted)
    }

    private fun config() = LiveLanRouteConfig(
        localDeviceId = LOCAL,
        peerDeviceId = PEER.deviceId,
        localSigningKey = LOCAL_SIGNING_KEY,
        peerSigningKey = PEER.signPubkey,
        peerTlsSpkiSha256 = BINDING.peerTlsSpkiSha256,
        lanSecret = BINDING.lanSecret,
        protocolVersion = BINDING.protocolVersion,
        utcEpochDay = DAY,
    )

    private fun bluetoothConfig() = LiveBluetoothRouteConfig(
        localDeviceId = LOCAL,
        peerDeviceId = PEER.deviceId,
        localSigningKey = LOCAL_SIGNING_KEY,
        peerSigningKey = PEER.signPubkey,
        associationId = BLUETOOTH_BINDING.associationId,
        protocolVersion = BLUETOOTH_BINDING.protocolVersion,
    )

    private fun authenticatedWire(peerDeviceId: String) = co.twinotify.core.bluetooth.AuthenticatedBluetoothWire(
        peerDeviceId,
        ByteArray(32),
        co.twinotify.core.bluetooth.BluetoothSocketWire(NoopSocket),
    )

    private object NoopSocket : co.twinotify.core.bluetooth.BluetoothStreamSocket {
        override val remoteAddress: String = "AA:BB:CC:DD:EE:FF"
        override val inputStream = java.io.ByteArrayInputStream(ByteArray(0))
        override val outputStream = java.io.ByteArrayOutputStream()
        override fun close() = Unit
    }

    /** A rendezvous stands in for the radio; [closed] proves the attempt stops advertising. */
    private class RecordingLinks : co.twinotify.core.bluetooth.BluetoothLinkProvider {
        var closed = 0
        override val peerAddress: String? = null
        override suspend fun listen(): co.twinotify.core.bluetooth.BluetoothLinkListener = error("not listening")
        override suspend fun connect(): co.twinotify.core.bluetooth.BluetoothStreamSocket = error("not dialing")
        override fun close() { closed += 1 }
    }

    private fun route(kind: RouteKind) = object : TransportRoute {
        override val kind = kind
        override suspend fun open(): AuthenticatedRouteSession = error("not opened")
    }

    private fun message() = OutboundMessage(
        msgId = "m",
        canonId = "notif:m",
        sequence = 1,
        eventType = "notif.post",
        protocolVersion = 2,
        envelopeJson = "{}",
        envelopeSha256 = "0".repeat(64),
        byteSize = 2,
        createdAt = 1,
        expiresAt = 2,
        custodyAcceptedAt = null,
        custodyRoute = null,
        attempts = 0,
        nextAttemptAt = 1,
        state = "PENDING",
        lastError = null,
        requiresPeerReceipt = true,
    )

    private class RecordingPlatform : LiveLanPlatform {
        val lease = object : LiveWifiLease {
            override val networkToken: Any = Any()
            override fun close() { leaseCloses++ }
        }
        var leaseCloses = 0
        var listenerCloses = 0
        var discoveryCloses = 0
        var discoveryPort = 0
        var listenerLease: LiveWifiLease? = null
        var discoveryLease: LiveWifiLease? = null
        var dialerLease: LiveWifiLease? = null
        private var onLost: (() -> Unit)? = null
        private val connectionFailed = CompletableDeferred<Unit>()

        override suspend fun acquireWifi(onLost: () -> Unit): LiveWifiLease {
            this.onLost = onLost
            return lease
        }
        override fun openListener(config: LiveLanRouteConfig, lease: LiveWifiLease): LiveBoundLanListener {
            listenerLease = lease
            return LiveBoundLanListener(
                listener = object : LanListener {
                    override suspend fun accept(): AuthenticatedLanConnection {
                        connectionFailed.await()
                        error("listener closed")
                    }
                    override fun close() {
                        listenerCloses++
                        connectionFailed.complete(Unit)
                    }
                },
                port = 43127,
            )
        }
        override fun openDiscovery(config: LiveLanRouteConfig, lease: LiveWifiLease, listenerPort: Int): LanDiscovery {
            discoveryPort = listenerPort
            discoveryLease = lease
            return object : LanDiscovery {
                override fun candidates(): Flow<LanCandidate> = flowOf(
                    LanCandidate(
                        InetAddress.getLoopbackAddress(),
                        listenerPort,
                        LanNetwork { Socket() },
                    ),
                )
                override suspend fun close() { discoveryCloses++ }
            }
        }
        override fun openDialer(config: LiveLanRouteConfig, lease: LiveWifiLease): LanDialer {
            dialerLease = lease
            return LanDialer {
                connectionFailed.await()
                error("dial aborted")
            }
        }

        fun loseWifi() = onLost?.invoke()
    }

    private class RecordingAttempt(
        private val connectResult: CompletableDeferred<AuthenticatedLanConnection>,
    ) : LiveLanAttempt {
        var aborts = 0
        var closes = 0
        override suspend fun connect(): AuthenticatedLanConnection = connectResult.await()
        override fun abort() {
            aborts++
            connectResult.completeExceptionally(IllegalStateException("wifi lost"))
        }
        override suspend fun close() { closes++ }
    }

    private class RecordingSession(override val kind: RouteKind) : AuthenticatedRouteSession {
        var closes = 0
        override suspend fun send(message: OutboundMessage) = Unit
        override suspend fun awaitClosed(): String = awaitCancellation()
        override suspend fun close(code: String) { closes++ }
    }

    private class FakeConnection : AuthenticatedLanConnection {
        override val session = co.twinotify.core.lan.LanAuthenticatedSession(PEER.deviceId, LOCAL, ByteArray(32))
        override val incoming: Flow<LanFrame> = emptyFlow()
        override suspend fun send(frame: LanFrame) = Unit
        override fun close() = Unit
    }

    private companion object {
        const val LOCAL = "dev-00000000-0000-0000-0000-000000000001"
        const val DAY = 20_331L
        val LOCAL_SIGNING_KEY = ByteArray(64) { (it + 1).toByte() }
        val PEER = PeerRecord(
            "dev-00000000-0000-0000-0000-000000000002",
            ByteArray(32) { 3 },
            ByteArray(32) { 4 },
            "Peer",
            "binding",
        )
        val BINDING = LanBinding(ByteArray(32) { 5 }, ByteArray(32) { 6 }, 1, 123)
        val BLUETOOTH_BINDING = co.twinotify.core.bluetooth.BluetoothBinding(
            associationId = 41,
            peerDeviceId = PEER.deviceId,
            peerSigningKeySha256 = co.twinotify.core.bluetooth.BluetoothBinding.signingKeyDigest(PEER.signPubkey),
        )
    }
}
