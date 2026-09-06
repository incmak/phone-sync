package co.twinotify.core.pairing.lan

import android.content.Context
import android.net.ConnectivityManager
import android.net.wifi.WifiManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import java.io.Closeable
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resume
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout

enum class PairingWifiNetworkFailure(val code: String) {
    PERMISSION_DENIED("wifi_permission_denied"),
    UNAVAILABLE("wifi_unavailable"),
}

class PairingWifiNetworkException(
    val failure: PairingWifiNetworkFailure,
) : RuntimeException(failure.code)

class PairingWifiNetworkLease internal constructor(
    val network: Network,
    private val release: () -> Unit,
) : Closeable {
    private val closed = AtomicBoolean(false)
    override fun close() {
        if (closed.compareAndSet(false, true)) release()
    }
}

/**
 * Whether a Wi-Fi request can be satisfied at all right now.
 *
 * Separated from the selector so the rule is testable without a radio. The distinction that
 * matters: a disabled radio can never produce the network, while an enabled but unassociated one
 * may produce it within the timeout, and failing early there would push out the route's retry
 * cooldown for nothing.
 */
internal object PairingWifiPrecondition {
    fun failureOrNull(radioEnabled: Boolean): PairingWifiNetworkFailure? =
        if (radioEnabled) null else PairingWifiNetworkFailure.UNAVAILABLE
}

/** A cancellable lease on an already available local Wi-Fi transport. */
class PairingWifiNetworkSelector(
    context: Context,
    private val timeoutMillis: Long = DEFAULT_TIMEOUT_MILLIS,
) {
    private val application = context.applicationContext
    private val connectivity = application.getSystemService(ConnectivityManager::class.java)
        ?: throw PairingWifiNetworkException(PairingWifiNetworkFailure.UNAVAILABLE)

    /** False also when the state cannot be read, which is the same thing for our purposes. */
    private fun wifiRadioEnabled(): Boolean = runCatching {
        application.getSystemService(WifiManager::class.java)?.isWifiEnabled == true
    }.getOrDefault(false)

    suspend fun acquire(onLost: () -> Unit): PairingWifiNetworkLease {
        // Report what is already known instead of spending the whole rendezvous timeout on a
        // callback that cannot fire. Measured with Wi-Fi off: 15,010ms per attempt, every cycle.
        PairingWifiPrecondition.failureOrNull(wifiRadioEnabled())?.let { throw PairingWifiNetworkException(it) }
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            .build()
        return try {
            withTimeout(timeoutMillis) {
                suspendCancellableCoroutine { continuation ->
                    val released = AtomicBoolean(false)
                    val selected = AtomicReference<Network?>(null)
                    lateinit var callback: ConnectivityManager.NetworkCallback
                    fun unregister() {
                        if (released.compareAndSet(false, true)) {
                            runCatching { connectivity.unregisterNetworkCallback(callback) }
                        }
                    }
                    callback = object : ConnectivityManager.NetworkCallback() {
                        override fun onAvailable(network: Network) {
                            if (!continuation.isActive || !selected.compareAndSet(null, network)) return
                            continuation.resume(PairingWifiNetworkLease(network, ::unregister))
                        }

                        override fun onLost(network: Network) {
                            if (released.get() || selected.get() != network) return
                            runCatching(onLost)
                        }
                    }
                    continuation.invokeOnCancellation { unregister() }
                    try {
                        connectivity.registerNetworkCallback(request, callback)
                    } catch (_: SecurityException) {
                        unregister()
                        continuation.resumeWith(
                            Result.failure(PairingWifiNetworkException(PairingWifiNetworkFailure.PERMISSION_DENIED)),
                        )
                    } catch (_: Throwable) {
                        unregister()
                        continuation.resumeWith(
                            Result.failure(PairingWifiNetworkException(PairingWifiNetworkFailure.UNAVAILABLE)),
                        )
                    }
                }
            }
        } catch (error: PairingWifiNetworkException) {
            throw error
        } catch (error: CancellationException) {
            if (error !is TimeoutCancellationException) throw error
            throw PairingWifiNetworkException(PairingWifiNetworkFailure.UNAVAILABLE)
        }
    }

    private companion object {
        const val DEFAULT_TIMEOUT_MILLIS = 15_000L
    }
}
