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
    private var addresses: Set<String>? = null

    // Unknown initial callback state must not restart an already running connection.
    private fun usable(): Boolean = validated != false && blocked != true

    fun onAvailable(network: Any): Boolean = synchronized(monitor) {
        if (currentNetwork == network) return@synchronized false
        currentNetwork = network
        validated = null
        blocked = null
        addresses = null
        true
    }

    /**
     * A new address on the same network is a network change for every socket this app holds.
     *
     * A DHCP re-lease keeps the Network object and fires only onLinkPropertiesChanged, which
     * nothing observed: every connection on the old address died silently and the app learned
     * of it one liveness window later, then fell back to the relay and re-queued its rows. One
     * phone had re-leased twenty-one times in a day. The first report only records the
     * addresses; a restart is asked for when they differ from what was recorded.
     */
    fun onAddresses(network: Any, current: Set<String>): Boolean = synchronized(monitor) {
        if (currentNetwork != network) return@synchronized false
        val previous = addresses
        addresses = current
        previous != null && previous != current && current.isNotEmpty()
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

            override fun onLinkPropertiesChanged(network: Network, properties: android.net.LinkProperties) {
                val current = properties.linkAddresses
                    .map { it.address }
                    .filter { it is java.net.Inet4Address && !it.isLoopbackAddress && !it.isAnyLocalAddress }
                    .map { it.hostAddress ?: "" }
                    .filter { it.isNotEmpty() }
                    .toSet()
                if (!closed.get() && gate.onAddresses(network, current) && !closed.get()) {
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
