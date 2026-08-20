package co.twinotify.core.lan

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import java.net.InetAddress
import java.util.UUID
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull

enum class LanDiscoveryFailure(val code: String) {
    PERMISSION_DENIED("lan_permission_denied"),
    UNAVAILABLE("lan_unavailable"),
    NETWORK_LOST("lan_network_lost"),
    CLOCK_SKEW("lan_clock_skew"),
}

data class LanNetworkPolicy(
    val wifi: Boolean,
    val requiresInternet: Boolean,
    val requiresValidated: Boolean,
)

data class LanDiscoveredService(
    val advertisementId: String,
    val address: InetAddress,
    val port: Int,
    val network: LanNetwork,
)

interface LanDiscoveryPlatform {
    interface Callback {
        fun onCandidate(service: LanDiscoveredService)
        fun onNetworkLost()
        fun onFailure(failure: LanDiscoveryFailure)
    }

    fun start(callback: Callback)
    fun stop()
}

/**
 * Typed discovery lifecycle. A candidate remains coupled to the Network that
 * resolved it and is invalidated immediately when that Network is lost.
 */
class AndroidLanDiscovery internal constructor(
    private val platform: LanDiscoveryPlatform,
    private val expectedAdvertisementIds: Set<String>,
) : LanDiscovery {
    private val closed = AtomicBoolean(false)
    private val current = MutableStateFlow<LanCandidate?>(null)
    private val failure = MutableStateFlow<LanDiscoveryFailure?>(null)

    constructor(
        context: Context,
        network: Network,
        localAdvertisementId: String,
        expectedAdvertisementIds: Set<String>,
        port: Int,
        capabilities: Int = LanCapabilities.DIRECT_V1,
        clockSkewAdvertisementIds: Set<String> = emptySet(),
    ) : this(
        AndroidNsdPlatform(
            context,
            network,
            localAdvertisementId,
            expectedAdvertisementIds.toSet(),
            clockSkewAdvertisementIds.toSet(),
            port,
            capabilities,
        ),
        expectedAdvertisementIds.toSet(),
    )

    init {
        require(expectedAdvertisementIds.isNotEmpty()) { "lan_expected_advertisement_missing" }
        platform.start(object : LanDiscoveryPlatform.Callback {
            override fun onCandidate(service: LanDiscoveredService) {
                if (closed.get() || service.advertisementId !in expectedAdvertisementIds) return
                current.value = LanCandidate(service.address, service.port, service.network)
            }

            override fun onNetworkLost() {
                current.value = null
                failure.value = LanDiscoveryFailure.NETWORK_LOST
                closePlatformOnce()
            }

            override fun onFailure(failure: LanDiscoveryFailure) {
                current.value = null
                this@AndroidLanDiscovery.failure.value = failure
                closePlatformOnce()
            }
        })
    }

    override fun candidates(): Flow<LanCandidate> = current.filterNotNull()

    fun failures(): Flow<LanDiscoveryFailure> = failure.filterNotNull()

    override suspend fun close() {
        current.value = null
        closePlatformOnce()
    }

    private fun closePlatformOnce() {
        if (closed.compareAndSet(false, true)) platform.stop()
    }

    companion object {
        fun networkPolicy() = LanNetworkPolicy(wifi = true, requiresInternet = false, requiresValidated = false)
    }
}

/** API 34+ executor-based, Network-bound NSD implementation. */
@Suppress("DEPRECATION")
private class AndroidNsdPlatform(
    context: Context,
    private val network: Network,
    private val localAdvertisementId: String,
    private val expectedAdvertisementIds: Set<String>,
    private val clockSkewAdvertisementIds: Set<String>,
    private val port: Int,
    private val capabilities: Int,
    private val executor: Executor = context.mainExecutor,
) : LanDiscoveryPlatform {
    private val appContext = context.applicationContext
    private val nsd = requireNotNull(appContext.getSystemService(NsdManager::class.java)) { "lan_unavailable" }
    private val wifi = requireNotNull(appContext.getSystemService(WifiManager::class.java)) { "lan_unavailable" }
    private val connectivity = requireNotNull(appContext.getSystemService(ConnectivityManager::class.java)) { "lan_unavailable" }
    private val stopped = AtomicBoolean(false)
    private val started = AtomicBoolean(false)
    private val activeResolution = AtomicReference<NsdManager.ResolveListener?>()
    private var callback: LanDiscoveryPlatform.Callback? = null
    private var multicastLock: WifiManager.MulticastLock? = null
    private var registrationStarted = false
    private var discoveryStarted = false
    private var networkCallbackStarted = false

    private val registration = object : NsdManager.RegistrationListener {
        override fun onServiceRegistered(serviceInfo: NsdServiceInfo) = Unit
        override fun onRegistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) = fail()
        override fun onServiceUnregistered(serviceInfo: NsdServiceInfo) = Unit
        override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) = Unit
    }

    private val discovery = object : NsdManager.DiscoveryListener {
        override fun onDiscoveryStarted(serviceType: String) = Unit
        override fun onServiceFound(serviceInfo: NsdServiceInfo) {
            if (stopped.get() || serviceInfo.serviceType != LanDiscoveryContract.SERVICE_TYPE) return
            val recognizedIds = expectedAdvertisementIds + clockSkewAdvertisementIds
            if (!LanDiscoveryContract.matches(serviceInfo.attributes, recognizedIds)) return
            val ad = serviceInfo.attributes.getValue("ad").decodeToString()
            if (ad in clockSkewAdvertisementIds) {
                callback?.onFailure(LanDiscoveryFailure.CLOCK_SKEW)
                return
            }
            lateinit var resolver: NsdManager.ResolveListener
            resolver = object : NsdManager.ResolveListener {
                override fun onServiceResolved(resolved: NsdServiceInfo) {
                    activeResolution.compareAndSet(resolver, null)
                    if (!LanDiscoveryContract.matches(resolved.attributes, expectedAdvertisementIds)) return
                    val address = resolved.hostAddresses.firstOrNull() ?: resolved.host ?: return
                    if (resolved.port !in 1..65535 || resolved.network != null && resolved.network != network) return
                    callback?.onCandidate(
                        LanDiscoveredService(
                            advertisementId = ad,
                            address = address,
                            port = resolved.port,
                            network = LanNetwork { network.socketFactory.createSocket() },
                        ),
                    )
                }

                override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                    activeResolution.compareAndSet(resolver, null)
                }
            }
            if (!activeResolution.compareAndSet(null, resolver)) return
            try {
                nsd.resolveService(serviceInfo, executor, resolver)
            } catch (error: SecurityException) {
                activeResolution.compareAndSet(resolver, null)
                callback?.onFailure(LanDiscoveryFailure.PERMISSION_DENIED)
            } catch (_: Throwable) {
                activeResolution.compareAndSet(resolver, null)
            }
        }

        override fun onServiceLost(serviceInfo: NsdServiceInfo) = Unit
        override fun onDiscoveryStopped(serviceType: String) = Unit
        override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) = fail()
        override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
    }

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onLost(lost: Network) {
            if (lost == network) callback?.onNetworkLost()
        }
    }

    override fun start(callback: LanDiscoveryPlatform.Callback) {
        check(started.compareAndSet(false, true)) { "lan_discovery_already_started" }
        this.callback = callback
        require(port in 1..65535) { "lan_discovery_invalid_port" }
        try {
            multicastLock = wifi.createMulticastLock("twinotify-lan-discovery").apply {
                setReferenceCounted(false)
                acquire()
            }
            val request = NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .removeCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                .build()
            connectivity.registerNetworkCallback(request, networkCallback)
            networkCallbackStarted = true

            val service = NsdServiceInfo().apply {
                serviceName = "twinotify-${UUID.randomUUID()}"
                serviceType = LanDiscoveryContract.SERVICE_TYPE
                this.port = this@AndroidNsdPlatform.port
                this.network = this@AndroidNsdPlatform.network
                LanDiscoveryContract.txt(localAdvertisementId, capabilities).forEach { (key, value) ->
                    setAttribute(key, value.decodeToString())
                }
            }
            nsd.registerService(service, NsdManager.PROTOCOL_DNS_SD, executor, registration)
            registrationStarted = true
            nsd.discoverServices(
                LanDiscoveryContract.SERVICE_TYPE,
                NsdManager.PROTOCOL_DNS_SD,
                network,
                executor,
                discovery,
            )
            discoveryStarted = true
        } catch (_: SecurityException) {
            callback.onFailure(LanDiscoveryFailure.PERMISSION_DENIED)
        } catch (_: Throwable) {
            callback.onFailure(LanDiscoveryFailure.UNAVAILABLE)
        }
    }

    override fun stop() {
        if (!stopped.compareAndSet(false, true)) return
        activeResolution.getAndSet(null)?.let { runCatching { nsd.stopServiceResolution(it) } }
        if (discoveryStarted) runCatching { nsd.stopServiceDiscovery(discovery) }
        if (registrationStarted) runCatching { nsd.unregisterService(registration) }
        if (networkCallbackStarted) runCatching { connectivity.unregisterNetworkCallback(networkCallback) }
        multicastLock?.let { lock -> if (lock.isHeld) runCatching { lock.release() } }
        callback = null
    }

    private fun fail() {
        callback?.onFailure(LanDiscoveryFailure.UNAVAILABLE)
    }
}
