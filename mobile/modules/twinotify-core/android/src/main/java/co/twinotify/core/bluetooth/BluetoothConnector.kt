package co.twinotify.core.bluetooth

import android.Manifest
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

/** Accepts inbound L2CAP sockets. Closing it releases a blocked accept. */
interface BluetoothLinkListener : Closeable {
    suspend fun accept(): BluetoothStreamSocket
}

/** An open LE L2CAP server socket together with the PSM its stack assigned it. */
interface BluetoothL2capListener : BluetoothLinkListener {
    val psm: Int
}

/** The radio operations the connector needs, so direction and deadlines are testable without one. */
interface BluetoothLinkProvider : Closeable {
    /**
     * Address of the peer, when one can identify it, or null when it cannot.
     *
     * Over LE the peer both advertises and dials from a rotating resolvable private address, so
     * the address observed a moment ago is not a stable identity and an accepted socket may
     * legitimately carry a different one. Production providers therefore report null and leave
     * identity entirely to the signed handshake, which runs before any application byte is read.
     * Memory only either way; it is compared, never stored or printed.
     */
    val peerAddress: String?

    suspend fun listen(): BluetoothLinkListener

    /** A connected socket to the peer only. */
    suspend fun connect(): BluetoothStreamSocket

    /** Releases discovery-side resources (advertisement, scan, unused listener). */
    override fun close() {}
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
 * Opens one authenticated LE L2CAP link to the peer.
 *
 * Direction is deterministic: the lexicographically smaller Twinotify device ID is the
 * client and dials, the other listens. Each socket attempt has a 12 s ceiling. If the
 * normal direction has not produced a socket by 15 s, both phones swap roles once for a
 * delayed reverse attempt. The wire is returned only after the signed handshake succeeds
 * within its own 10 s ceiling.
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

    // LE has no adapter-wide inquiry to cancel, so there is no discovery step before an attempt.
    suspend fun connect(): AuthenticatedBluetoothWire {
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

    /**
     * When the peer has a knowable address, a socket from any other one is closed before a byte
     * is parsed. When it does not (see [BluetoothLinkProvider.peerAddress]), the first socket is
     * taken and the signed handshake decides. The listener closes on every exit.
     */
    private suspend fun acceptResolvedDevice(): BluetoothStreamSocket = links.listen().use { listener ->
        val expected = links.peerAddress
        var socket = listener.accept()
        while (expected != null && socket.remoteAddress != expected) {
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

    companion object {
        const val DEFAULT_CONNECT_TIMEOUT_MILLIS = 12_000L
        const val DEFAULT_HANDSHAKE_TIMEOUT_MILLIS = 10_000L
        const val DEFAULT_REVERSE_ATTEMPT_DELAY_MILLIS = 15_000L

        /**
         * How long a rendezvous keeps advertising and may keep scanning: the whole connector
         * window, which is the delay before the reverse attempt plus that attempt's own ceiling.
         */
        const val RENDEZVOUS_WINDOW_MILLIS = DEFAULT_REVERSE_ATTEMPT_DELAY_MILLIS + DEFAULT_CONNECT_TIMEOUT_MILLIS
    }
}

/**
 * The real radio: LE L2CAP only, one pre-opened server socket, and every blocking call released
 * by closing its socket. Permission checks stay inline so lint can see them.
 *
 * Insecure L2CAP is deliberate. A secure channel would demand bonding, and Bluetooth link
 * encryption is defence in depth here rather than the security boundary: the Ed25519 mutual
 * challenge and the E2EE v2 envelopes are what protect the data.
 */
class AndroidBluetoothLinkProvider(
    context: Context,
    private val device: BluetoothDevice,
    private val peerPsm: Int,
    private val listener: BluetoothL2capListener,
    private val onClose: () -> Unit = {},
) : BluetoothLinkProvider {
    private val context = context.applicationContext

    /** Null on purpose: an LE peer dials from a rotating private address. See the interface. */
    override val peerAddress: String? = null

    override suspend fun listen(): BluetoothLinkListener = listener

    override suspend fun connect(): BluetoothStreamSocket = dialL2capChannel(context, device, peerPsm)

    override fun close() {
        listener.close()
        onClose()
    }
}

/** Opens one insecure LE L2CAP channel to [device] on [psm]; a cancelled dial closes the socket. */
internal suspend fun dialL2capChannel(
    context: Context,
    device: BluetoothDevice,
    psm: Int,
): BluetoothStreamSocket {
    if (ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) !=
        PackageManager.PERMISSION_GRANTED
    ) {
        throw BluetoothAssociationException(BluetoothAssociationFailure.PERMISSION_DENIED)
    }
    val socket = device.createInsecureL2capChannel(psm)
    try {
        blockingUntilReleased(release = { socket.close() }) { socket.connect() }
    } catch (error: Throwable) {
        runCatching { socket.close() }
        throw error
    }
    return AndroidStreamSocket(socket)
}

/** Wraps the LE L2CAP server socket so the connector never sees an Android type. */
internal class AndroidL2capListener(private val serverSocket: BluetoothServerSocket) : BluetoothL2capListener {
    override val psm: Int = serverSocket.psm

    override suspend fun accept(): BluetoothStreamSocket =
        AndroidStreamSocket(blockingUntilReleased(release = { serverSocket.close() }) { serverSocket.accept() })

    override fun close() {
        runCatching { serverSocket.close() }
    }
}

internal class AndroidStreamSocket(private val socket: BluetoothSocket) : BluetoothStreamSocket {
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
