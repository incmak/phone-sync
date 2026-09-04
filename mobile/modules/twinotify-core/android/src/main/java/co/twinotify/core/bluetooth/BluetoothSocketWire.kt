package co.twinotify.core.bluetooth

import co.twinotify.core.direct.DirectCommand
import co.twinotify.core.direct.DirectWire
import co.twinotify.core.lan.LanFrameLimits
import java.io.Closeable
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout

/**
 * The stream pair one Bluetooth socket exposes. The wire owns it and never learns anything
 * beyond the peer address it uses for the resolved-device check.
 */
interface BluetoothStreamSocket : Closeable {
    val remoteAddress: String
    val inputStream: InputStream
    val outputStream: OutputStream
}

enum class BluetoothWireFailure(val code: String) {
    TIMEOUT("bluetooth_wire_timeout"),
    CLOSED("bluetooth_wire_closed"),
    ALREADY_COLLECTED("bluetooth_incoming_already_collected"),
}

class BluetoothWireException(val failure: BluetoothWireFailure) : Exception(failure.code)

/**
 * Runs one blocking socket call on the IO dispatcher. Bluetooth streams ignore thread
 * interrupts, so cancellation (a deadline included) first calls [release], which makes the
 * blocked call return, and then joins it. No worker thread is ever left behind.
 */
internal suspend fun <T> blockingUntilReleased(release: () -> Unit, block: () -> T): T = coroutineScope {
    val worker = async(Dispatchers.IO) { runCatching { runInterruptible { block() } } }
    val outcome = try {
        worker.await()
    } catch (error: CancellationException) {
        runCatching(release)
        throw error
    }
    outcome.getOrThrow()
}

/**
 * One LE L2CAP stream as a [DirectWire]: exactly one collector, one mutexed writer, every
 * read and write under a deadline, and any timeout or protocol failure closes the socket.
 * Before the handshake it is also the [BluetoothHandshakeChannel]; only
 * [AuthenticatedBluetoothWire] is ever handed to a route.
 */
class BluetoothSocketWire(
    internal val socket: BluetoothStreamSocket,
    private val readTimeoutMillis: Long = DEFAULT_IO_TIMEOUT_MILLIS,
    private val writeTimeoutMillis: Long = DEFAULT_IO_TIMEOUT_MILLIS,
) : DirectWire, BluetoothHandshakeChannel {
    init {
        require(readTimeoutMillis > 0 && writeTimeoutMillis > 0)
    }

    private val closedFlag = AtomicBoolean(false)
    private val collecting = AtomicBoolean(false)
    private val sendMutex = Mutex()

    val closed: Boolean get() = closedFlag.get()

    /** Unauthenticated by construction; [AuthenticatedBluetoothWire] supplies the verified peer. */
    override val peerDeviceId: String get() = UNAUTHENTICATED_PEER

    /**
     * One ordered reader, enforced. It completes when the peer ends the stream or after a
     * close frame, and fails with a bounded code on a deadline or a malformed frame.
     */
    override val incoming: Flow<DirectCommand> = flow {
        if (!collecting.compareAndSet(false, true)) throw BluetoothWireException(BluetoothWireFailure.ALREADY_COLLECTED)
        while (!closedFlag.get()) {
            val frame = readFrame() ?: return@flow
            emit(frame.toCommand())
            if (frame is BluetoothFrame.Close) {
                close()
                return@flow
            }
        }
    }

    override suspend fun send(command: DirectCommand) = sendMutex.withLock {
        if (closedFlag.get()) throw BluetoothWireException(BluetoothWireFailure.CLOSED)
        val encoded = try {
            BluetoothFrameCodec.encode(command.toFrame())
        } catch (error: BluetoothFrameException) {
            close()
            throw error
        }
        write(encoded)
    }

    override suspend fun readMessage(): ByteArray = try {
        guarded(readTimeoutMillis) {
            val prefix = readExactly(LanFrameLimits.PREFIX_BYTES) ?: throw BluetoothWireException(BluetoothWireFailure.CLOSED)
            val length = BluetoothFrameCodec.bodyLength(prefix, LanFrameLimits.MAX_CONTROL_BYTES, BluetoothFrameFailure.CONTROL_TOO_LARGE)
            readExactly(length) ?: throw BluetoothFrameException(BluetoothFrameFailure.TRUNCATED)
        }
    } catch (_: EndedByLocalClose) {
        throw BluetoothWireException(BluetoothWireFailure.CLOSED)
    }

    override suspend fun writeMessage(bytes: ByteArray) = sendMutex.withLock {
        if (closedFlag.get()) throw BluetoothWireException(BluetoothWireFailure.CLOSED)
        if (bytes.isEmpty() || bytes.size > LanFrameLimits.MAX_CONTROL_BYTES) {
            close()
            throw BluetoothFrameException(BluetoothFrameFailure.CONTROL_TOO_LARGE)
        }
        write(BluetoothFrameCodec.prefixed(bytes))
    }

    override fun close() {
        if (closedFlag.compareAndSet(false, true)) runCatching { socket.close() }
    }

    override fun toString(): String = "BluetoothSocketWire(closed=${closedFlag.get()})"

    /** Null means the peer ended the stream cleanly at a frame boundary, or we closed it ourselves. */
    private suspend fun readFrame(): BluetoothFrame? = try {
        guarded(readTimeoutMillis) {
            val prefix = readExactly(LanFrameLimits.PREFIX_BYTES)
            if (prefix == null) {
                null
            } else {
                val length = BluetoothFrameCodec.bodyLength(prefix, LanFrameLimits.MAX_FRAME_BYTES, BluetoothFrameFailure.FRAME_TOO_LARGE)
                val body = readExactly(length) ?: throw BluetoothFrameException(BluetoothFrameFailure.TRUNCATED)
                BluetoothFrameCodec.decode(prefix + body)
            }
        }.also { if (it == null) close() }
    } catch (_: EndedByLocalClose) {
        // A stream error after our own close is the end of the session, not a fault.
        null
    }

    private suspend fun write(bytes: ByteArray) = try {
        guarded(writeTimeoutMillis) {
            socket.outputStream.write(bytes)
            socket.outputStream.flush()
        }
    } catch (_: EndedByLocalClose) {
        throw BluetoothWireException(BluetoothWireFailure.CLOSED)
    }

    /**
     * Runs one blocking exchange under [timeoutMillis]. Every exit that is not a clean
     * result closes the socket; cancellation and the deadline release the blocked call
     * through that same close.
     */
    private suspend fun <T> guarded(timeoutMillis: Long, block: () -> T): T {
        if (closedFlag.get()) throw BluetoothWireException(BluetoothWireFailure.CLOSED)
        try {
            return withTimeout(timeoutMillis) { blockingUntilReleased(release = ::close, block = block) }
        } catch (error: TimeoutCancellationException) {
            // Only this wire's own deadline maps to a timeout; an outer cancellation stays one.
            if (!currentCoroutineContext().isActive) {
                close()
                throw error
            }
            close()
            throw BluetoothWireException(BluetoothWireFailure.TIMEOUT)
        } catch (error: CancellationException) {
            close()
            throw error
        } catch (error: BluetoothFrameException) {
            close()
            throw error
        } catch (error: BluetoothWireException) {
            close()
            throw error
        } catch (_: Throwable) {
            val endedByLocalClose = closedFlag.get()
            close()
            if (endedByLocalClose) throw EndedByLocalClose()
            throw BluetoothWireException(BluetoothWireFailure.CLOSED)
        }
    }

    /** A stream fault observed after [close] ran: the session ended on purpose, so it is not reported as a fault. */
    private class EndedByLocalClose : Exception(null, null, false, false)

    /** Null only at end of stream before the first byte; a partial read is a truncated frame. */
    private fun readExactly(size: Int): ByteArray? {
        val target = ByteArray(size)
        var offset = 0
        val input = socket.inputStream
        while (offset < size) {
            val read = input.read(target, offset, size - offset)
            if (read < 0) {
                if (offset == 0) return null
                throw BluetoothFrameException(BluetoothFrameFailure.TRUNCATED)
            }
            offset += read
        }
        return target
    }

    companion object {
        const val DEFAULT_IO_TIMEOUT_MILLIS = 10_000L
        private const val UNAUTHENTICATED_PEER = "unauthenticated"
    }
}

/** A wire whose peer the signed handshake has verified. The only Bluetooth wire a route may use. */
class AuthenticatedBluetoothWire(
    override val peerDeviceId: String,
    sessionId: ByteArray,
    private val delegate: BluetoothSocketWire,
) : DirectWire by delegate {
    private val storedSessionId = sessionId.copyOf()
    val sessionId: ByteArray get() = storedSessionId.copyOf()
    val closed: Boolean get() = delegate.closed

    override fun toString(): String = "AuthenticatedBluetoothWire(peerDeviceId=$peerDeviceId, sessionId=<redacted>)"
}
