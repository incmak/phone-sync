package co.twinotify.core.bluetooth

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull

/** Accepts inbound RFCOMM sockets. Closing it releases a blocked accept. */
interface BluetoothLinkListener : Closeable {
    suspend fun accept(): BluetoothStreamSocket
}

/** The radio operations the connector needs, so direction and deadlines are testable without one. */
interface BluetoothLinkProvider {
    /** Address of the CDM-resolved device. Memory only; it is compared, never stored or printed. */
    val peerAddress: String

    suspend fun cancelDiscovery()

    suspend fun listen(): BluetoothLinkListener

    /** A connected socket to the resolved device only. */
    suspend fun connect(): BluetoothStreamSocket
}

/** Turns a raw wire into an authenticated one, or fails with a bounded handshake code. */
fun interface BluetoothWireAuthenticator {
    suspend fun authenticate(wire: BluetoothSocketWire, role: BluetoothRole): AuthenticatedBluetoothWire
}

class SignedBluetoothWireAuthenticator(
    private val handshakeFactory: (BluetoothRole) -> BluetoothHandshake,
) : BluetoothWireAuthenticator {
    override suspend fun authenticate(wire: BluetoothSocketWire, role: BluetoothRole): AuthenticatedBluetoothWire {
        val result = handshakeFactory(role).authenticate(wire)
        return AuthenticatedBluetoothWire(result.peerDeviceId, result.sessionId, wire)
    }
}

enum class BluetoothConnectFailure(val code: String) {
    CONNECT_FAILED("bluetooth_connect_failed"),
    CONNECT_TIMEOUT("bluetooth_connect_timeout"),
    HANDSHAKE_TIMEOUT("bluetooth_handshake_timeout"),
    HANDSHAKE_FAILED("bluetooth_handshake_failed"),
}

class BluetoothConnectException(val failure: BluetoothConnectFailure) : Exception(failure.code)

/**
 * Opens one authenticated RFCOMM link to the resolved peer.
 *
 * Direction is deterministic: the lexicographically smaller Twinotify device ID is the
 * client and dials, the other listens. Each socket attempt has a 12 s ceiling. If the
 * normal direction has not produced a socket by 15 s, both phones swap roles once for a
 * delayed reverse attempt. Only a socket from the resolved address is ever read, and the
 * wire is returned only after the signed handshake succeeds within its own 10 s ceiling.
 */
class BluetoothConnector(
    private val localDeviceId: String,
    private val peerDeviceId: String,
    private val links: BluetoothLinkProvider,
    private val authenticator: BluetoothWireAuthenticator,
    private val connectTimeoutMillis: Long = DEFAULT_CONNECT_TIMEOUT_MILLIS,
    private val handshakeTimeoutMillis: Long = DEFAULT_HANDSHAKE_TIMEOUT_MILLIS,
    private val reverseAttemptDelayMillis: Long = DEFAULT_REVERSE_ATTEMPT_DELAY_MILLIS,
) {
    init {
        require(localDeviceId != peerDeviceId) { "bluetooth_identity_collision" }
        require(connectTimeoutMillis > 0 && handshakeTimeoutMillis > 0)
        require(reverseAttemptDelayMillis > connectTimeoutMillis)
    }

    val normalRole: BluetoothRole = if (localDeviceId < peerDeviceId) BluetoothRole.CLIENT else BluetoothRole.SERVER

    suspend fun connect(): AuthenticatedBluetoothWire {
        try {
            links.cancelDiscovery()
        } catch (error: CancellationException) {
            throw error
        } catch (_: Throwable) {
            // Discovery state is advisory; a failure to cancel must not block the attempt.
        }
        val normal = withTimeoutOrNull(reverseAttemptDelayMillis) {
            attempt(normalRole) as? Attempt.Connected ?: awaitCancellation()
        }
        if (normal != null) return authenticate(normal.socket, normalRole)
        val reverse = normalRole.opposite()
        return when (val outcome = attempt(reverse)) {
            is Attempt.Connected -> authenticate(outcome.socket, reverse)
            is Attempt.Failed -> throw BluetoothConnectException(
                if (outcome.timedOut) BluetoothConnectFailure.CONNECT_TIMEOUT else BluetoothConnectFailure.CONNECT_FAILED,
            )
        }
    }

    private sealed interface Attempt {
        class Connected(val socket: BluetoothStreamSocket) : Attempt
        class Failed(val timedOut: Boolean) : Attempt
    }

    private suspend fun attempt(role: BluetoothRole): Attempt = try {
        Attempt.Connected(
            withTimeout(connectTimeoutMillis) {
                when (role) {
                    BluetoothRole.CLIENT -> links.connect()
                    BluetoothRole.SERVER -> acceptResolvedDevice()
                }
            },
        )
    } catch (error: TimeoutCancellationException) {
        // Only this attempt's own ceiling is a timeout; an outer cancellation stays one.
        if (!currentCoroutineContext().isActive) throw error
        Attempt.Failed(timedOut = true)
    } catch (error: CancellationException) {
        throw error
    } catch (_: Throwable) {
        Attempt.Failed(timedOut = false)
    }

    /** Sockets from any other address are closed before a byte is parsed; the listener closes on every exit. */
    private suspend fun acceptResolvedDevice(): BluetoothStreamSocket = links.listen().use { listener ->
        var socket = listener.accept()
        while (socket.remoteAddress != links.peerAddress) {
            runCatching { socket.close() }
            socket = listener.accept()
        }
        socket
    }

    private suspend fun authenticate(socket: BluetoothStreamSocket, role: BluetoothRole): AuthenticatedBluetoothWire {
        val wire = BluetoothSocketWire(socket)
        try {
            return withTimeout(handshakeTimeoutMillis) { authenticator.authenticate(wire, role) }
        } catch (error: TimeoutCancellationException) {
            wire.close()
            if (!currentCoroutineContext().isActive) throw error
            throw BluetoothConnectException(BluetoothConnectFailure.HANDSHAKE_TIMEOUT)
        } catch (error: CancellationException) {
            wire.close()
            throw error
        } catch (error: BluetoothHandshakeException) {
            wire.close()
            throw error
        } catch (_: Throwable) {
            wire.close()
            throw BluetoothConnectException(BluetoothConnectFailure.HANDSHAKE_FAILED)
        }
    }

    private companion object {
        const val DEFAULT_CONNECT_TIMEOUT_MILLIS = 12_000L
        const val DEFAULT_HANDSHAKE_TIMEOUT_MILLIS = 10_000L
        const val DEFAULT_REVERSE_ATTEMPT_DELAY_MILLIS = 15_000L
    }
}

/**
 * The real radio: secure RFCOMM only, private service UUID only, and every blocking call
 * released by closing its socket. Permission checks stay inline so lint can see them.
 */
class AndroidBluetoothLinkProvider(
    context: Context,
    private val adapter: BluetoothAdapter,
    private val device: BluetoothDevice,
) : BluetoothLinkProvider {
    private val context = context.applicationContext

    override val peerAddress: String = device.address

    override suspend fun cancelDiscovery() {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_SCAN) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        runCatching { adapter.cancelDiscovery() }
    }

    override suspend fun listen(): BluetoothLinkListener {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            throw BluetoothAssociationException(BluetoothAssociationFailure.PERMISSION_DENIED)
        }
        val serverSocket = adapter.listenUsingRfcommWithServiceRecord(SERVICE_NAME, BluetoothConstants.RFCOMM_SERVICE_UUID)
        return AndroidListener(serverSocket)
    }

    override suspend fun connect(): BluetoothStreamSocket {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            throw BluetoothAssociationException(BluetoothAssociationFailure.PERMISSION_DENIED)
        }
        val socket = device.createRfcommSocketToServiceRecord(BluetoothConstants.RFCOMM_SERVICE_UUID)
        try {
            blockingUntilReleased(release = { socket.close() }) { socket.connect() }
        } catch (error: Throwable) {
            runCatching { socket.close() }
            throw error
        }
        return AndroidStreamSocket(socket)
    }

    private class AndroidListener(private val serverSocket: BluetoothServerSocket) : BluetoothLinkListener {
        override suspend fun accept(): BluetoothStreamSocket =
            AndroidStreamSocket(blockingUntilReleased(release = { serverSocket.close() }) { serverSocket.accept() })

        override fun close() {
            runCatching { serverSocket.close() }
        }
    }

    private class AndroidStreamSocket(private val socket: BluetoothSocket) : BluetoothStreamSocket {
        override val remoteAddress: String = socket.remoteDevice.address
        override val inputStream: InputStream get() = socket.inputStream
        override val outputStream: OutputStream get() = socket.outputStream

        override fun close() {
            try {
                socket.close()
            } catch (_: IOException) {
                // Already closed by the peer or by a released blocking call.
            }
        }
    }

    private companion object {
        const val SERVICE_NAME = "Twinotify"
    }
}
