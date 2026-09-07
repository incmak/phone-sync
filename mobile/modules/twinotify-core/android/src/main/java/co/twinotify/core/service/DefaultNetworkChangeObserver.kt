package co.twinotify.core.service

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import java.io.Closeable
import java.util.concurrent.atomic.AtomicBoolean

internal class DefaultNetworkChangeGate(
    initialNetwork: Any?,
) {
    private val monitor = Any()
    private var currentNetwork: Any? = initialNetwork
    private var validated: Boolean? = null
    private var blocked: Boolean? = null

    // Unknown initial callback state must not restart an already running connection.
    private fun usable(): Boolean = validated != false && blocked != true

    fun onAvailable(network: Any): Boolean = synchronized(monitor) {
        if (currentNetwork == network) return@synchronized false
        currentNetwork = network
        validated = null
        blocked = null
        true
    }

    fun onValidated(network: Any, value: Boolean): Boolean = synchronized(monitor) {
        if (currentNetwork != network) return@synchronized false
        val wasUsable = usable()
        validated = value
        !wasUsable && usable()
    }

    fun onBlocked(network: Any, value: Boolean): Boolean = synchronized(monitor) {
        if (currentNetwork != network) return@synchronized false
        val wasUsable = usable()
        blocked = value
        !wasUsable && usable()
    }

    fun onLost(network: Any) = synchronized(monitor) {
        if (currentNetwork == network) currentNetwork = null
    }
}

/** Restarts on default-network replacement or recovery of access on the same network. */
internal fun observeDefaultNetworkChanges(
    context: Context,
    onNetworkChanged: () -> Unit,
): Closeable {
    val connectivity = context.applicationContext
        .getSystemService(ConnectivityManager::class.java)
        ?: return Closeable {}
    return try {
        val gate = DefaultNetworkChangeGate(connectivity.activeNetwork)
        val closed = AtomicBoolean(false)
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                val changed = !closed.get() && gate.onAvailable(network)
                if (changed && !closed.get()) {
                    onNetworkChanged()
                }
            }

            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                if (!closed.get() && gate.onValidated(
                        network,
                        capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED),
                    ) && !closed.get()
                ) {
                    onNetworkChanged()
                }
            }

            override fun onBlockedStatusChanged(network: Network, blocked: Boolean) {
                if (!closed.get() && gate.onBlocked(network, blocked) && !closed.get()) {
                    onNetworkChanged()
                }
            }

            override fun onLost(network: Network) {
                if (!closed.get()) gate.onLost(network)
            }
        }
        connectivity.registerDefaultNetworkCallback(callback)
        Closeable {
            if (closed.compareAndSet(false, true)) {
                runCatching { connectivity.unregisterNetworkCallback(callback) }
            }
        }
    } catch (_: Throwable) {
        Closeable {}
    }
}
