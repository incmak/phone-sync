package co.twinotify.core.service

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import java.io.Closeable
import java.util.concurrent.atomic.AtomicBoolean

internal class DefaultNetworkChangeGate(
    initialNetwork: Any?,
) {
    private val monitor = Any()
    private var currentNetwork: Any? = initialNetwork

    fun onAvailable(network: Any): Boolean = synchronized(monitor) {
        if (currentNetwork == network) return@synchronized false
        currentNetwork = network
        true
    }

    fun onLost(network: Any) = synchronized(monitor) {
        if (currentNetwork == network) currentNetwork = null
    }
}

/** Restarts transport only after Android selects a different usable default network. */
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
