package co.twinotify.core.service

import android.bluetooth.BluetoothManager
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.util.Log
import co.twinotify.core.bluetooth.AndroidBluetoothLinkProvider
import co.twinotify.core.bluetooth.AuthenticatedBluetoothWire
import co.twinotify.core.bluetooth.BluetoothAssociationException
import co.twinotify.core.bluetooth.BluetoothAssociationFailure
import co.twinotify.core.bluetooth.BluetoothAssociationPolicy
import co.twinotify.core.bluetooth.BluetoothAssociations
import co.twinotify.core.bluetooth.BluetoothBinding
import co.twinotify.core.bluetooth.BluetoothBindingStore
import co.twinotify.core.bluetooth.BluetoothConnectException
import co.twinotify.core.bluetooth.BluetoothConnector
import co.twinotify.core.bluetooth.BluetoothHandshake
import co.twinotify.core.bluetooth.BluetoothHandshakeException
import co.twinotify.core.bluetooth.BluetoothLinkProvider
import co.twinotify.core.bluetooth.BluetoothRole
import co.twinotify.core.bluetooth.BluetoothRoute
import co.twinotify.core.bluetooth.SignedBluetoothWireAuthenticator
import co.twinotify.core.crypto.CryptoStore
import co.twinotify.core.direct.DirectDeliveryEvent
import co.twinotify.core.lan.AuthenticatedLanConnection
import co.twinotify.core.lan.DirectLanConnector
import co.twinotify.core.lan.JsseLanDialer
import co.twinotify.core.lan.JsseLanListener
import co.twinotify.core.lan.LanAdvertisement
import co.twinotify.core.lan.LanAdvertisementMatcher
import co.twinotify.core.lan.LanConnectionRole
import co.twinotify.core.lan.LanConnectionException
import co.twinotify.core.lan.LanDialer
import co.twinotify.core.lan.LanDiscovery
import co.twinotify.core.lan.LanHandshake
import co.twinotify.core.lan.LanListener
import co.twinotify.core.lan.LanRoute
import co.twinotify.core.lan.LanTransportEvent
import co.twinotify.core.lan.SignedLanSocketHandshake
import co.twinotify.core.lan.AndroidLanDiscovery
import co.twinotify.core.pairing.lan.LanTlsContextFactory
import co.twinotify.core.pairing.lan.PairingWifiNetworkLease
import co.twinotify.core.pairing.lan.PairingWifiNetworkSelector
import co.twinotify.core.storage.DeviceIdentity
import co.twinotify.core.storage.LanBinding
import co.twinotify.core.storage.LanPairStore
import co.twinotify.core.storage.OutboundMessage
import co.twinotify.core.storage.PeerRecord
import co.twinotify.core.storage.PeerStore
import java.io.Closeable
import java.net.Inet4Address
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.SSLServerSocket
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch

data class LiveTransportRoutes(
    val lan: TransportRoute?,
    val bluetooth: TransportRoute?,
    val relay: TransportRoute?,
)

class LiveLocalRouteIdentity(
    val deviceId: String,
    signingKey: ByteArray,
) {
    private val key = signingKey.copyOf()
    val signingKey: ByteArray get() = key.copyOf()
}

class LiveLanHandshakeConfig internal constructor(
    val localDeviceId: String,
    val peerDeviceId: String,
    localSigningKey: ByteArray,
    peerSigningKey: ByteArray,
    val localRole: LanConnectionRole,
    val protocolVersion: Int,
) {
    private val localKey = localSigningKey.copyOf()
    private val peerKey = peerSigningKey.copyOf()
    val localSigningKey: ByteArray get() = localKey.copyOf()
    val peerSigningKey: ByteArray get() = peerKey.copyOf()

    internal fun socketHandshake() = SignedLanSocketHandshake(
        LanHandshake(
            localDeviceId = localDeviceId,
            peerDeviceId = peerDeviceId,
            localSigningKey = localKey,
            peerSigningKey = peerKey,
            localRole = localRole,
            protocolFloor = protocolVersion,
        ),
        protocolVersion = protocolVersion,
    )
}

class LiveLanRouteConfig(
    val localDeviceId: String,
    val peerDeviceId: String,
    localSigningKey: ByteArray,
    peerSigningKey: ByteArray,
    peerTlsSpkiSha256: ByteArray,
    lanSecret: ByteArray,
    val protocolVersion: Int,
    val utcEpochDay: Long,
) {
    private val localKey = localSigningKey.copyOf()
    private val peerKey = peerSigningKey.copyOf()
    private val tlsPin = peerTlsSpkiSha256.copyOf()
    private val secret = lanSecret.copyOf()

    val localSigningKey: ByteArray get() = localKey.copyOf()
    val peerSigningKey: ByteArray get() = peerKey.copyOf()
    val peerTlsSpkiSha256: ByteArray get() = tlsPin.copyOf()
    val lanSecret: ByteArray get() = secret.copyOf()
    val localAdvertisementId: String = LanAdvertisement.derive(secret, localDeviceId, utcEpochDay)
    private val peerAdvertisements = LanAdvertisementMatcher(secret, peerDeviceId).expectations(utcEpochDay)
    val expectedPeerAdvertisementIds: Set<String> = peerAdvertisements.acceptedIds
    val clockSkewPeerAdvertisementIds: Set<String> = peerAdvertisements.clockSkewIds

    init {
        require(localDeviceId != peerDeviceId) { "lan_identity_collision" }
        require(protocolVersion > 0) { "lan_protocol_invalid" }
    }

    fun handshake(role: LanConnectionRole) = LiveLanHandshakeConfig(
        localDeviceId,
        peerDeviceId,
        localKey,
        peerKey,
        role,
        protocolVersion,
    )

    fun forUtcEpochDay(day: Long) = LiveLanRouteConfig(
        localDeviceId = localDeviceId,
        peerDeviceId = peerDeviceId,
        localSigningKey = localKey,
        peerSigningKey = peerKey,
        peerTlsSpkiSha256 = tlsPin,
        lanSecret = secret,
        protocolVersion = protocolVersion,
        utcEpochDay = day,
    )
}

class LiveRelayRouteHooks(
    val dispatch: suspend (String) -> InboundDispatchResult?,
    val onEvent: suspend (TransportEvent) -> Unit = {},
    val onAuthenticated: suspend (Int, Set<String>) -> Unit = { _, _ -> },
    val onExpired: suspend () -> Unit = {},
)

class LiveRelayRouteConfig(
    val events: () -> Flow<TransportEvent>,
    val hooks: LiveRelayRouteHooks = LiveRelayRouteHooks(dispatch = { null }),
)

/** Trust facts one Bluetooth route needs. The association ID stands in for the address, which stays in memory. */
class LiveBluetoothRouteConfig(
    val localDeviceId: String,
    val peerDeviceId: String,
    localSigningKey: ByteArray,
    peerSigningKey: ByteArray,
    val associationId: Int,
    val protocolVersion: Int,
) {
    private val localKey = localSigningKey.copyOf()
    private val peerKey = peerSigningKey.copyOf()
    val localSigningKey: ByteArray get() = localKey.copyOf()
    val peerSigningKey: ByteArray get() = peerKey.copyOf()

    init {
        require(localDeviceId != peerDeviceId) { "bluetooth_identity_collision" }
        require(protocolVersion > 0) { "bluetooth_protocol_invalid" }
    }

    internal fun handshake(role: BluetoothRole) = BluetoothHandshake(
        localDeviceId = localDeviceId,
        peerDeviceId = peerDeviceId,
        localSigningKey = localKey,
        peerSigningKey = peerKey,
        role = role,
    )
}

class LiveTransportRouteDependencies(
    val loadPeer: suspend () -> PeerRecord?,
    val loadValidatedBinding: suspend (PeerRecord) -> LanBinding?,
    val loadLocalIdentity: suspend () -> LiveLocalRouteIdentity,
    val buildLanRoute: (LiveLanRouteConfig) -> TransportRoute,
    val buildRelayRoute: (LiveRelayRouteConfig) -> TransportRoute,
    val loadValidatedBluetoothBinding: suspend (PeerRecord) -> BluetoothBinding? = { null },
    val buildBluetoothRoute: (LiveBluetoothRouteConfig) -> TransportRoute? = { null },
)

/**
 * Loads route trust without letting unusable direct state remove a valid path. Each direct
 * binding is validated on its own: a corrupt LAN binding leaves Bluetooth and relay usable,
 * and a stale Bluetooth association leaves LAN and relay usable.
 */
class LiveTransportRoutesFactory(
    private val dependencies: LiveTransportRouteDependencies,
) {
    suspend fun create(
        relay: LiveRelayRouteConfig?,
        utcEpochDay: Long = Instant.now().atZone(ZoneOffset.UTC).toLocalDate().toEpochDay(),
    ): LiveTransportRoutes {
        val relayRoute = relay?.let(dependencies.buildRelayRoute)
        val peer = dependencies.loadPeer() ?: return LiveTransportRoutes(null, null, relayRoute)
        val lanBinding = loadTrustOrNull { dependencies.loadValidatedBinding(peer) }
        val bluetoothBinding = loadTrustOrNull { dependencies.loadValidatedBluetoothBinding(peer) }
        if (lanBinding == null && bluetoothBinding == null) return LiveTransportRoutes(null, null, relayRoute)
        val identity = loadTrustOrNull { dependencies.loadLocalIdentity() }
            ?: return LiveTransportRoutes(null, null, relayRoute)
        val lan = lanBinding?.let { binding ->
            dependencies.buildLanRoute(
                LiveLanRouteConfig(
                    localDeviceId = identity.deviceId,
                    peerDeviceId = peer.deviceId,
                    localSigningKey = identity.signingKey,
                    peerSigningKey = peer.signPubkey,
                    peerTlsSpkiSha256 = binding.peerTlsSpkiSha256,
                    lanSecret = binding.lanSecret,
                    protocolVersion = binding.protocolVersion,
                    utcEpochDay = utcEpochDay,
                ),
            )
        }
        val bluetooth = bluetoothBinding?.let { binding ->
            dependencies.buildBluetoothRoute(
                LiveBluetoothRouteConfig(
                    localDeviceId = identity.deviceId,
                    peerDeviceId = peer.deviceId,
                    localSigningKey = identity.signingKey,
                    peerSigningKey = peer.signPubkey,
                    associationId = binding.associationId,
                    protocolVersion = binding.protocolVersion,
                ),
            )
        }
        return LiveTransportRoutes(lan, bluetooth, relayRoute)
    }

    private suspend fun <T> loadTrustOrNull(load: suspend () -> T?): T? = try {
        load()
    } catch (error: CancellationException) {
        throw error
    } catch (_: Throwable) {
        null
    }

    companion object {
        fun production(
            context: Context,
            outbox: OutboxRepository,
            dispatch: suspend (String) -> InboundDispatchResult,
            onLanEvent: suspend (LanTransportEvent) -> Unit = {},
            onBluetoothEvent: suspend (DirectDeliveryEvent) -> Unit = {},
        ): LiveTransportRoutesFactory {
            val appContext = context.applicationContext
            val attemptFactory = DefaultLiveLanAttemptFactory(AndroidLiveLanPlatform(appContext))
            val bluetoothStore = BluetoothBindingStore.forContext(appContext)
            return LiveTransportRoutesFactory(
                LiveTransportRouteDependencies(
                    loadPeer = { PeerStore.load(appContext) },
                    loadValidatedBinding = { peer -> LanPairStore.loadValidated(appContext, peer) },
                    loadLocalIdentity = {
                        val deviceId = DeviceIdentity.getOrCreate(appContext)
                        val signingKeys = CryptoStore.loadOrGenerate(appContext).second
                        LiveLocalRouteIdentity(deviceId, signingKeys.secretKey)
                    },
                    loadValidatedBluetoothBinding = { peer ->
                        if (!bluetoothStore.routeEnabled() || !BluetoothAssociationPolicy.permissionsGranted(appContext)) {
                            null
                        } else {
                            bluetoothStore.loadValidated(peer, BluetoothAssociations.currentIds(appContext))
                        }
                    },
                    buildBluetoothRoute = { config ->
                        LiveBluetoothTransportRoute(
                            config,
                            linkFactory = AndroidLiveBluetoothLinkFactory(appContext),
                            sessionFactory = { wire ->
                                BluetoothRoute(
                                    connect = { wire },
                                    outbox = outbox,
                                    dispatch = dispatch,
                                    onEvent = onBluetoothEvent,
                                ).open()
                            },
                            allowAttempt = {
                                val peer = PeerStore.load(appContext)
                                debugRouteAvailable(appContext, RouteKind.BLUETOOTH) &&
                                    peer != null &&
                                    bluetoothStore.routeEnabled() &&
                                    BluetoothAssociationPolicy.permissionsGranted(appContext) &&
                                    bluetoothStore.loadValidated(peer, BluetoothAssociations.currentIds(appContext))
                                        ?.associationId == config.associationId
                            },
                        )
                    },
                    buildLanRoute = { config ->
                        LiveLanTransportRoute(
                            config,
                            attemptFactory,
                            sessionFactory = { connection ->
                                LanRoute(
                                    connect = { connection },
                                    outbox = outbox,
                                    dispatch = dispatch,
                                    onEvent = onLanEvent,
                                ).open()
                            },
                            allowAttempt = { debugRouteAvailable(appContext, RouteKind.LAN) },
                        )
                    },
                    buildRelayRoute = { config ->
                        LiveRelayTransportRoute(
                            config.events,
                            config.hooks,
                            allowAttempt = { debugRouteAvailable(appContext, RouteKind.RELAY) },
                        )
                    },
                ),
            )
        }
    }
}

/**
 * Debug-only route fault seam shared by every live route.
 *
 * A release build is never debuggable, so this is unconditionally true in
 * production and no release component can reach the preferences it reads. In a
 * debug build the E2E control surface can make exactly one route unavailable
 * for a bounded window, which is how a scenario forces the coordinator down to
 * the route it wants to prove.
 */
private fun debugRouteAvailable(context: Context, route: RouteKind): Boolean {
    val debuggable = context.applicationInfo.flags and
        android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0
    if (!debuggable) return true
    val faultUntil = context.getSharedPreferences("e2e-control", Context.MODE_PRIVATE)
        .getLong(route.name.lowercase() + "_fault_until_ms", 0L)
    return faultUntil <= System.currentTimeMillis()
}

fun liveRelayRouteConfig(
    outbox: OutboxRepository,
    url: RelayWebSocketUrl,
    authHeadersProvider: () -> Map<String, String>,
    hooks: LiveRelayRouteHooks,
): LiveRelayRouteConfig = LiveRelayRouteConfig(
    events = {
        RelayTransport(
            outbox = outbox,
            authHeadersProvider = authHeadersProvider,
            reconnect = false,
        ).run(url)
    },
    hooks = hooks,
)

interface LiveWifiLease : Closeable {
    val networkToken: Any
}

data class LiveBoundLanListener(
    val listener: LanListener,
    val port: Int,
) {
    init { require(port in 1..65535) { "lan_listener_invalid_port" } }
}

interface LiveLanPlatform {
    suspend fun acquireWifi(onLost: () -> Unit): LiveWifiLease
    fun openListener(config: LiveLanRouteConfig, lease: LiveWifiLease): LiveBoundLanListener
    fun openDiscovery(config: LiveLanRouteConfig, lease: LiveWifiLease, listenerPort: Int): LanDiscovery
    fun openDialer(config: LiveLanRouteConfig, lease: LiveWifiLease): LanDialer
}

fun interface LiveLanAttemptFactory {
    suspend fun open(config: LiveLanRouteConfig, onLost: () -> Unit): LiveLanAttempt
}

interface LiveLanAttempt {
    suspend fun connect(): AuthenticatedLanConnection
    fun abort()
    suspend fun close()
}

class DefaultLiveLanAttemptFactory(
    private val platform: LiveLanPlatform,
    private val afterListenerRegistered: () -> Unit = {},
) : LiveLanAttemptFactory {
    override suspend fun open(config: LiveLanRouteConfig, onLost: () -> Unit): LiveLanAttempt {
        val lost = AtomicBoolean(false)
        val resources = LiveLanAttemptResources()
        try {
            val lease = platform.acquireWifi {
                lost.set(true)
                onLost()
            }
            resources.installLease(lease)
            if (lost.get()) throw IllegalStateException("lan_network_lost")
            val bound = platform.openListener(config, lease)
            resources.installListener(bound.listener)
            afterListenerRegistered()
            if (lost.get()) throw IllegalStateException("lan_network_lost")
            val discovery = platform.openDiscovery(config, lease, bound.port)
            resources.installDiscovery(discovery)
            val dialer = platform.openDialer(config, lease)
            return DefaultLiveLanAttempt(
                DirectLanConnector(
                    discovery,
                    bound.listener,
                    dialer,
                    localDeviceId = config.localDeviceId,
                    peerDeviceId = config.peerDeviceId,
                    closeListener = resources::closeListenerOnce,
                    closeDiscovery = resources::closeDiscoveryOnce,
                ),
                resources,
                lost,
            )
        } catch (error: Throwable) {
            resources.closeAll()
            throw error
        }
    }
}

private class DefaultLiveLanAttempt(
    private val connector: DirectLanConnector,
    private val resources: LiveLanAttemptResources,
    private val networkLost: AtomicBoolean,
) : LiveLanAttempt {
    private val closed = AtomicBoolean(false)
    private val aborted = AtomicBoolean(false)

    override suspend fun connect(): AuthenticatedLanConnection {
        if (networkLost.get()) {
            abort()
            throw IllegalStateException("lan_network_lost")
        }
        val connection = connector.connect()
        resources.installConnection(connection)
        if (networkLost.get()) {
            resources.closeConnectionOnce()
            throw IllegalStateException("lan_network_lost")
        }
        resources.closeListenerOnce()
        resources.closeDiscoveryOnce()
        return connection
    }

    override fun abort() {
        if (!aborted.compareAndSet(false, true)) return
        resources.closeActiveOnce()
    }

    override suspend fun close() {
        if (!closed.compareAndSet(false, true)) return
        resources.closeAll()
    }
}

/** One cleanup authority shared by construction failure, network loss and session shutdown. */
private class LiveLanAttemptResources {
    private val lease = AtomicReference<LiveWifiLease?>()
    private val discovery = AtomicReference<LanDiscovery?>()
    private val listener = AtomicReference<LanListener?>()
    private val connection = AtomicReference<AuthenticatedLanConnection?>()
    private val leaseClosed = AtomicBoolean(false)
    private val discoveryClosed = AtomicBoolean(false)
    private val listenerClosed = AtomicBoolean(false)
    private val connectionClosed = AtomicBoolean(false)

    fun installLease(value: LiveWifiLease) {
        check(lease.compareAndSet(null, value)) { "lan_lease_already_installed" }
    }

    fun installDiscovery(value: LanDiscovery) {
        check(discovery.compareAndSet(null, value)) { "lan_discovery_already_installed" }
    }

    fun installListener(value: LanListener) {
        check(listener.compareAndSet(null, value)) { "lan_listener_already_installed" }
    }

    fun installConnection(value: AuthenticatedLanConnection) {
        check(connection.compareAndSet(null, value)) { "lan_connection_already_installed" }
    }

    fun closeActiveOnce() {
        closeListenerOnce()
        closeConnectionOnce()
    }

    fun closeConnectionOnce() {
        val value = connection.get() ?: return
        if (connectionClosed.compareAndSet(false, true)) value.close()
    }

    fun closeListenerOnce() {
        val value = listener.get() ?: return
        if (listenerClosed.compareAndSet(false, true)) value.close()
    }

    suspend fun closeDiscoveryOnce() {
        val value = discovery.get() ?: return
        if (discoveryClosed.compareAndSet(false, true)) value.close()
    }

    suspend fun closeAll() {
        closeConnectionOnce()
        closeListenerOnce()
        closeDiscoveryOnce()
        val value = lease.get()
        if (value != null && leaseClosed.compareAndSet(false, true)) value.close()
    }
}

/** A fresh attempt is created for every coordinator open and owns all of its resources. */
class LiveLanTransportRoute(
    private val config: LiveLanRouteConfig,
    private val attemptFactory: LiveLanAttemptFactory,
    private val sessionFactory: suspend (AuthenticatedLanConnection) -> AuthenticatedRouteSession,
    private val utcEpochDay: () -> Long = {
        Instant.now().atZone(ZoneOffset.UTC).toLocalDate().toEpochDay()
    },
    private val allowAttempt: () -> Boolean = { true },
) : TransportRoute {
    override val kind: RouteKind = RouteKind.LAN

    override suspend fun open(): AuthenticatedRouteSession {
        check(allowAttempt()) { "lan_debug_unavailable" }
        val currentConfig = config.forUtcEpochDay(utcEpochDay())
        val attemptRef = AtomicReference<LiveLanAttempt?>()
        val attempt = attemptFactory.open(currentConfig) { attemptRef.get()?.abort() }
        attemptRef.set(attempt)
        try {
            val connection = attempt.connect()
            val session = sessionFactory(connection)
            return LiveLanOwnedSession(session, attempt)
        } catch (error: Throwable) {
            val code = (error as? LanConnectionException)?.failure?.code ?: "lan_open_failed"
            runCatching { Log.w("Twinotify", code) }
            attempt.close()
            throw error
        }
    }
}

private class LiveLanOwnedSession(
    private val delegate: AuthenticatedRouteSession,
    private val attempt: LiveLanAttempt,
) : AuthenticatedRouteSession {
    private val closed = AtomicBoolean(false)
    override val kind: RouteKind = RouteKind.LAN
    override suspend fun send(message: OutboundMessage) = delegate.send(message)
    override suspend fun awaitClosed(): String = delegate.awaitClosed()
    override suspend fun close(code: String) {
        if (!closed.compareAndSet(false, true)) return
        try {
            delegate.close(code)
        } finally {
            attempt.close()
        }
    }
}

/** Converts the existing ordered relay flow into one coordinator-granted session. */
class LiveRelayTransportRoute(
    private val events: () -> Flow<TransportEvent>,
    private val hooks: LiveRelayRouteHooks,
    private val allowAttempt: () -> Boolean = { true },
) : TransportRoute {
    override val kind: RouteKind = RouteKind.RELAY

    override suspend fun open(): AuthenticatedRouteSession {
        check(allowAttempt()) { "relay_debug_unavailable" }
        val authenticated = CompletableDeferred<Unit>()
        val closed = CompletableDeferred<String>()
        val job = CoroutineScope(currentCoroutineContext()).launch {
            try {
                events().collect { event ->
                    when (event) {
                        is TransportEvent.Delivery -> {
                            if (hooks.dispatch(event.envelope) is InboundDispatchResult.Rejected) {
                                closed.complete("inbound_rejected")
                                throw IllegalStateException("inbound_rejected")
                            }
                        }
                        is TransportEvent.Authenticated -> {
                            hooks.onAuthenticated(event.floor, event.peerFeatures)
                            authenticated.complete(Unit)
                        }
                        is TransportEvent.RelayExpired -> hooks.onExpired()
                        is TransportEvent.Failed -> if (!authenticated.isCompleted) {
                            authenticated.completeExceptionally(event.error)
                        }
                        is TransportEvent.Closed -> {
                            if (!authenticated.isCompleted) {
                                authenticated.completeExceptionally(IllegalStateException("relay_closed_before_auth"))
                            }
                            closed.complete(event.reason ?: "relay_closed")
                        }
                        else -> Unit
                    }
                    hooks.onEvent(event)
                }
            } catch (error: CancellationException) {
                if (!authenticated.isCompleted) authenticated.cancel(error)
                throw error
            } catch (error: Throwable) {
                if (!authenticated.isCompleted) authenticated.completeExceptionally(error)
                closed.complete("relay_failed")
            } finally {
                if (!authenticated.isCompleted) {
                    authenticated.completeExceptionally(IllegalStateException("relay_ended_before_auth"))
                }
                closed.complete("relay_ended")
            }
        }
        try {
            authenticated.await()
        } catch (error: Throwable) {
            job.cancelAndJoin()
            throw error
        }
        return LiveRelayRouteSession(job, closed)
    }
}

private class LiveRelayRouteSession(
    private val job: Job,
    private val closed: CompletableDeferred<String>,
) : AuthenticatedRouteSession {
    private val stopped = AtomicBoolean(false)
    override val kind: RouteKind = RouteKind.RELAY
    override val selfDraining: Boolean = true
    override suspend fun send(message: OutboundMessage): Nothing =
        error("relay_session_is_self_draining")
    override suspend fun awaitClosed(): String = closed.await()
    override suspend fun close(code: String) {
        if (!stopped.compareAndSet(false, true)) return
        job.cancelAndJoin()
        closed.complete(code)
    }
}

/** Resolves the associated device into radio operations for one attempt. The address never leaves it. */
fun interface LiveBluetoothLinkFactory {
    suspend fun open(config: LiveBluetoothRouteConfig): BluetoothLinkProvider
}

/**
 * A fresh connector is created for every coordinator open. [allowAttempt] re-validates the
 * binding, enablement and permissions each time, so a route disabled or disassociated after
 * the transport generation started stops opening without a restart.
 */
class LiveBluetoothTransportRoute(
    private val config: LiveBluetoothRouteConfig,
    private val linkFactory: LiveBluetoothLinkFactory,
    private val sessionFactory: suspend (AuthenticatedBluetoothWire) -> AuthenticatedRouteSession,
    private val allowAttempt: suspend () -> Boolean = { true },
    private val connect: suspend (LiveBluetoothRouteConfig, BluetoothLinkProvider) -> AuthenticatedBluetoothWire =
        ::signedConnect,
) : TransportRoute {
    override val kind: RouteKind = RouteKind.BLUETOOTH

    override suspend fun open(): AuthenticatedRouteSession {
        check(allowAttempt()) { "bluetooth_route_unavailable" }
        val links = linkFactory.open(config)
        val wire = try {
            connect(config, links)
        } catch (error: Throwable) {
            val code = when (error) {
                is BluetoothConnectException -> error.failure.code
                is BluetoothHandshakeException -> error.failure.code
                is BluetoothAssociationException -> error.failure.code
                else -> "bluetooth_open_failed"
            }
            runCatching { Log.w("Twinotify", code) }
            throw error
        }
        try {
            return sessionFactory(wire)
        } catch (error: Throwable) {
            wire.close()
            throw error
        }
    }

    private companion object {
        suspend fun signedConnect(
            config: LiveBluetoothRouteConfig,
            links: BluetoothLinkProvider,
        ): AuthenticatedBluetoothWire = BluetoothConnector(
            localDeviceId = config.localDeviceId,
            peerDeviceId = config.peerDeviceId,
            links = links,
            authenticator = SignedBluetoothWireAuthenticator { role: BluetoothRole -> config.handshake(role) },
        ).connect()
    }
}

/** Looks the stored association up in CDM on every attempt; a removed association opens nothing. */
private class AndroidLiveBluetoothLinkFactory(
    private val context: Context,
) : LiveBluetoothLinkFactory {
    override suspend fun open(config: LiveBluetoothRouteConfig): BluetoothLinkProvider {
        val manager = BluetoothAssociations.companionDeviceManager(context)
            ?: throw BluetoothAssociationException(BluetoothAssociationFailure.BLUETOOTH_UNAVAILABLE)
        val association = manager.myAssociations.firstOrNull { it.id == config.associationId }
            ?: throw BluetoothAssociationException(BluetoothAssociationFailure.ASSOCIATION_FAILED)
        // The address is read for this attempt only; it is compared by the connector, never stored.
        val address = association.deviceMacAddress?.toString()?.uppercase()
            ?: throw BluetoothAssociationException(BluetoothAssociationFailure.ASSOCIATION_FAILED)
        val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
            ?.takeIf { it.isEnabled }
            ?: throw BluetoothAssociationException(BluetoothAssociationFailure.BLUETOOTH_UNAVAILABLE)
        return AndroidBluetoothLinkProvider(context, adapter, adapter.getRemoteDevice(address))
    }
}

private class AndroidLiveLanPlatform(
    private val context: Context,
) : LiveLanPlatform {
    override suspend fun acquireWifi(onLost: () -> Unit): LiveWifiLease =
        AndroidLiveWifiLease(PairingWifiNetworkSelector(context).acquire(onLost))

    override fun openListener(config: LiveLanRouteConfig, lease: LiveWifiLease): LiveBoundLanListener {
        val network = lease.network()
        val connectivity = context.getSystemService(ConnectivityManager::class.java)
            ?: error("lan_unavailable")
        val addresses = connectivity.getLinkProperties(network)?.linkAddresses.orEmpty()
        val bindAddress = addresses.firstOrNull { it.address is Inet4Address && !it.address.isLoopbackAddress }
            ?.address
            ?: addresses.firstOrNull { !it.address.isLoopbackAddress && !it.address.isAnyLocalAddress }?.address
            ?: error("lan_network_address_unavailable")
        val server = LanTlsContextFactory.serverContext(config.peerTlsSpkiSha256)
            .serverSocketFactory.createServerSocket(0, 1, bindAddress) as SSLServerSocket
        server.needClientAuth = true
        return LiveBoundLanListener(
            JsseLanListener(
                server,
                config.peerTlsSpkiSha256,
                handshakeFactory = { config.handshake(LanConnectionRole.ACCEPTOR).socketHandshake() },
            ),
            server.localPort,
        )
    }

    override fun openDiscovery(
        config: LiveLanRouteConfig,
        lease: LiveWifiLease,
        listenerPort: Int,
    ): LanDiscovery = AndroidLanDiscovery(
        context = context,
        network = lease.network(),
        localAdvertisementId = config.localAdvertisementId,
        expectedAdvertisementIds = config.expectedPeerAdvertisementIds,
        port = listenerPort,
        clockSkewAdvertisementIds = config.clockSkewPeerAdvertisementIds,
    )

    override fun openDialer(config: LiveLanRouteConfig, lease: LiveWifiLease): LanDialer {
        // Discovery candidates are created from this exact lease's Network and
        // JsseLanDialer opens its plain socket through that candidate.
        lease.network()
        return JsseLanDialer(
            LanTlsContextFactory.clientContext(config.peerTlsSpkiSha256),
            config.peerTlsSpkiSha256,
            handshakeFactory = { config.handshake(LanConnectionRole.INITIATOR).socketHandshake() },
        )
    }

    private fun LiveWifiLease.network(): Network = networkToken as? Network
        ?: error("lan_network_invalid")
}

private class AndroidLiveWifiLease(
    private val delegate: PairingWifiNetworkLease,
) : LiveWifiLease {
    override val networkToken: Any get() = delegate.network
    override fun close() = delegate.close()
}
