package co.twinotify.core.pairing.lan

import android.content.Context
import android.net.Network
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import java.util.UUID
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resume
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * API 34+ temporary pairing discovery. The caller supplies the Wi-Fi Network;
 * this adapter never asks for INTERNET or VALIDATED capability.
 */
@Suppress("DEPRECATION")
class AndroidPairingNsdAdapter(
    context: Context,
    private val network: Network,
    private val executor: Executor = context.mainExecutor,
) : PairingNsdAdapter {
    private val appContext = context.applicationContext
    private val nsd = appContext.getSystemService(NsdManager::class.java)
        ?: throw PairingTransportException(PairingTransportFailure.NSD_FAILED)
    private val wifi = appContext.getSystemService(WifiManager::class.java)
        ?: throw PairingTransportException(PairingTransportFailure.NSD_FAILED)
    private val activeDiscovery = AtomicReference<NsdManager.DiscoveryListener?>()
    private val activeResolution = AtomicReference<NsdManager.ResolveListener?>()

    override suspend fun register(sessionId: String, port: Int): PairingAdvertisement {
        validateSessionId(sessionId)
        if (port !in 1..65535) throw PairingTransportException(PairingTransportFailure.NSD_FAILED)
        val listener = AndroidRegistrationListener(nsd)
        val info = NsdServiceInfo().apply {
            serviceName = "twinotify-pair-${UUID.randomUUID()}"
            serviceType = PairingNsdContract.SERVICE_TYPE
            this.port = port
            this.network = this@AndroidPairingNsdAdapter.network
            PairingNsdContract.txt(sessionId).forEach { (key, value) ->
                setAttribute(key, value.decodeToString())
            }
        }
        return suspendCancellableCoroutine { continuation ->
            listener.beginRegister(continuation)
            continuation.invokeOnCancellation { listener.cancelRegistration() }
            try {
                nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, executor, listener)
            } catch (error: Throwable) {
                listener.failRegistration(mapPairingNsdThrowable(error))
            }
        }
    }

    override suspend fun resolve(sessionId: String): PairingNsdEndpoint {
        validateSessionId(sessionId)
        val multicastLock = try {
            wifi.createMulticastLock("twinotify-pair-discovery").apply {
                setReferenceCounted(false)
                acquire()
            }
        } catch (error: Throwable) {
            throw mapPairingNsdThrowable(error)
        }
        try {
            return suspendCancellableCoroutine { continuation ->
                val finished = AtomicBoolean(false)
                lateinit var discovery: NsdManager.DiscoveryListener

                fun stopListeners() {
                    if (!finished.compareAndSet(false, true)) return
                    activeResolution.getAndSet(null)?.let { listener ->
                        runCatching { nsd.stopServiceResolution(listener) }
                    }
                    activeDiscovery.compareAndSet(discovery, null)
                    runCatching { nsd.stopServiceDiscovery(discovery) }
                }

                fun fail(error: Throwable = PairingTransportException(PairingTransportFailure.NSD_FAILED)) {
                    continuation.resumeFailure(error)
                    stopListeners()
                }

                discovery = object : NsdManager.DiscoveryListener {
                    override fun onDiscoveryStarted(serviceType: String) = Unit

                    override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                        if (finished.get() || serviceInfo.serviceType != PairingNsdContract.SERVICE_TYPE) return
                        lateinit var resolver: NsdManager.ResolveListener
                        resolver = object : NsdManager.ResolveListener {
                            override fun onServiceResolved(resolved: NsdServiceInfo) {
                                activeResolution.compareAndSet(resolver, null)
                                if (!PairingNsdContract.matchesSession(resolved.attributes, sessionId)) {
                                    return
                                }
                                val address = resolved.hostAddresses.firstOrNull() ?: resolved.host
                                if (address == null || resolved.port !in 1..65535) {
                                    fail()
                                    return
                                }
                                val endpoint = PairingNsdEndpoint(
                                    address = address,
                                    port = resolved.port,
                                    network = PairingNetwork {
                                        network.socketFactory.createSocket()
                                    },
                                )
                                continuation.resumeSafely(endpoint)
                                stopListeners()
                            }

                            override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                                activeResolution.compareAndSet(resolver, null)
                            }
                        }
                        if (!activeResolution.compareAndSet(null, resolver)) return
                        try {
                            nsd.resolveService(serviceInfo, executor, resolver)
                        } catch (error: Throwable) {
                            fail(mapPairingNsdThrowable(error))
                        }
                    }

                    override fun onServiceLost(serviceInfo: NsdServiceInfo) = Unit

                    override fun onDiscoveryStopped(serviceType: String) = Unit

                    override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) = fail()

                    override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
                }

                if (!activeDiscovery.compareAndSet(null, discovery)) {
                    fail()
                    return@suspendCancellableCoroutine
                }
                continuation.invokeOnCancellation { stopListeners() }
                try {
                    nsd.discoverServices(
                        PairingNsdContract.SERVICE_TYPE,
                        NsdManager.PROTOCOL_DNS_SD,
                        network,
                        executor,
                        discovery,
                    )
                } catch (error: Throwable) {
                    fail(mapPairingNsdThrowable(error))
                }
            }
        } finally {
            if (multicastLock.isHeld) multicastLock.release()
        }
    }

    override suspend fun unregister(advertisement: PairingAdvertisement) {
        val listener = advertisement.listener as? AndroidRegistrationListener
            ?: throw PairingTransportException(PairingTransportFailure.NSD_FAILED)
        suspendCancellableCoroutine { continuation ->
            listener.beginUnregister(continuation)
            continuation.invokeOnCancellation { listener.cancelUnregistration() }
            try {
                nsd.unregisterService(listener)
            } catch (_: Throwable) {
                listener.failUnregistration()
            }
        }
    }

    override suspend fun stopDiscovery() {
        activeResolution.getAndSet(null)?.let { listener ->
            runCatching { nsd.stopServiceResolution(listener) }
        }
        activeDiscovery.getAndSet(null)?.let { listener ->
            runCatching { nsd.stopServiceDiscovery(listener) }
        }
    }
}

private class AndroidRegistrationListener(
    private val nsd: NsdManager,
) : NsdManager.RegistrationListener {
    private val registration = AtomicReference<CancellableContinuation<PairingAdvertisement>?>()
    private val unregistration = AtomicReference<CancellableContinuation<Unit>?>()
    private val registered = AtomicBoolean(false)
    private val registrationCancelled = AtomicBoolean(false)

    fun beginRegister(continuation: CancellableContinuation<PairingAdvertisement>) {
        if (!registration.compareAndSet(null, continuation)) failRegistration()
    }

    fun cancelRegistration() {
        registrationCancelled.set(true)
        registration.set(null)
        if (registered.get()) runCatching { nsd.unregisterService(this) }
    }

    fun failRegistration(
        error: Throwable = PairingTransportException(PairingTransportFailure.NSD_FAILED),
    ) {
        registration.getAndSet(null)?.resumeFailure(error)
    }

    fun beginUnregister(continuation: CancellableContinuation<Unit>) {
        if (!registered.get() || !unregistration.compareAndSet(null, continuation)) {
            continuation.resumeFailure()
        }
    }

    fun cancelUnregistration() {
        unregistration.set(null)
    }

    fun failUnregistration() {
        unregistration.getAndSet(null)?.resumeFailure()
    }

    override fun onServiceRegistered(serviceInfo: NsdServiceInfo) {
        registered.set(true)
        if (registrationCancelled.get()) {
            runCatching { nsd.unregisterService(this) }
            return
        }
        registration.getAndSet(null)?.let { continuation ->
            continuation.resumeSafely(PairingAdvertisement(this))
        }
    }

    override fun onRegistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) = failRegistration()

    override fun onServiceUnregistered(serviceInfo: NsdServiceInfo) {
        registered.set(false)
        unregistration.getAndSet(null)?.let { continuation ->
            continuation.resumeSafely(Unit)
        }
    }

    override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) = failUnregistration()
}

private fun <T> CancellableContinuation<T>.resumeFailure(
    error: Throwable = PairingTransportException(PairingTransportFailure.NSD_FAILED),
) {
    if (isActive) runCatching {
        resumeWith(Result.failure(error))
    }
}

private fun <T> CancellableContinuation<T>.resumeSafely(value: T) {
    if (isActive) runCatching { resume(value) }
}
