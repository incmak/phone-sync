package co.twinotify.core.lan

import java.io.Closeable
import java.net.SocketTimeoutException
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.security.cert.X509Certificate
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.ssl.SSLSocket
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout

interface AuthenticatedLanConnection : Closeable {
    /** What authentication produced. Simultaneous-connection arbitration reads it. */
    val session: LanAuthenticatedSession
    val peerDeviceId: String get() = session.peerDeviceId
    val incoming: Flow<LanFrame>
    suspend fun send(frame: LanFrame)
}

interface LanTlsSocket : Closeable {
    suspend fun startHandshake()
    fun peerSpkiSha256(): ByteArray
    fun tlsSessionContext(): ByteArray
    suspend fun readHello(): LanFrame
    suspend fun writeHello(frame: LanFrame)
    suspend fun readFrame(): LanFrame
    suspend fun writeFrame(frame: LanFrame)
}

enum class LanConnectionFailure(val code: String) {
    CONNECT_FAILED("lan_connect_failed"),
    TLS_PIN_MISMATCH("lan_tls_pin_mismatch"),
    TLS_FAILED("lan_tls_failed"),
    AUTHENTICATION_FAILED("lan_authentication_failed"),
    TIMEOUT("lan_connection_timeout"),
    CLOSED("lan_connection_closed"),
    ALREADY_COLLECTED("lan_incoming_already_collected"),
}

class LanConnectionException(val failure: LanConnectionFailure) : Exception(failure.code)

class LanConnectionFactory(
    private val socketProvider: suspend () -> LanTlsSocket,
    expectedPeerTlsPin: ByteArray,
    private val handshake: LanSocketHandshake,
    private val timeoutMillis: Long = DEFAULT_HANDSHAKE_TIMEOUT_MILLIS,
) {
    private val expectedPin = expectedPeerTlsPin.copyOf()

    init {
        require(expectedPin.size == SHA256_BYTES) { "lan_tls_invalid_pin" }
        require(timeoutMillis > 0) { "lan_connection_invalid_timeout" }
    }

    suspend fun connect(): AuthenticatedLanConnection {
        val socket = try {
            socketProvider()
        } catch (error: CancellationException) {
            throw error
        } catch (error: LanConnectionException) {
            throw error
        } catch (_: Throwable) {
            throw LanConnectionException(LanConnectionFailure.CONNECT_FAILED)
        }
        try {
            return withTimeout(timeoutMillis) {
                socket.startHandshake()
                if (!MessageDigest.isEqual(expectedPin, socket.peerSpkiSha256())) {
                    throw LanConnectionException(LanConnectionFailure.TLS_PIN_MISMATCH)
                }
                val session = try {
                    handshake.authenticate(socket)
                } catch (error: LanHandshakeException) {
                    throw LanConnectionException(LanConnectionFailure.AUTHENTICATION_FAILED)
                }
                DefaultAuthenticatedLanConnection(socket, session)
            }
        } catch (_: TimeoutCancellationException) {
            socket.close()
            throw LanConnectionException(LanConnectionFailure.TIMEOUT)
        } catch (error: CancellationException) {
            socket.close()
            throw error
        } catch (error: LanConnectionException) {
            socket.close()
            throw error
        } catch (_: Throwable) {
            socket.close()
            throw LanConnectionException(LanConnectionFailure.TLS_FAILED)
        }
    }

    private companion object {
        const val SHA256_BYTES = 32
        const val DEFAULT_HANDSHAKE_TIMEOUT_MILLIS = 10_000L
    }
}

/** A connection-local JSSE adapter. It never installs a process-wide trust or hostname policy. */
class JsseLanTlsSocket(
    private val socket: SSLSocket,
    private val readTimeoutMillis: Int = DEFAULT_IO_TIMEOUT_MILLIS,
    private val writeTimeoutMillis: Long = DEFAULT_IO_TIMEOUT_MILLIS.toLong(),
) : LanTlsSocket {
    private val closed = AtomicBoolean(false)

    init {
        require(readTimeoutMillis > 0 && writeTimeoutMillis > 0)
    }

    override suspend fun startHandshake() {
        try {
            runInterruptible(Dispatchers.IO) { socket.startHandshake() }
        } catch (error: CancellationException) {
            close()
            throw error
        } catch (_: Throwable) {
            close()
            throw LanConnectionException(LanConnectionFailure.TLS_FAILED)
        }
    }

    override fun peerSpkiSha256(): ByteArray = try {
        val certificate = socket.session.peerCertificates.firstOrNull() as? X509Certificate
            ?: throw LanConnectionException(LanConnectionFailure.TLS_FAILED)
        MessageDigest.getInstance("SHA-256").digest(certificate.publicKey.encoded)
    } catch (error: LanConnectionException) {
        throw error
    } catch (_: Throwable) {
        throw LanConnectionException(LanConnectionFailure.TLS_FAILED)
    }

    override fun tlsSessionContext(): ByteArray = try {
        val session = socket.session
        val localPin = (session.localCertificates?.firstOrNull() as? X509Certificate)?.let {
            MessageDigest.getInstance("SHA-256").digest(it.publicKey.encoded)
        } ?: throw LanConnectionException(LanConnectionFailure.TLS_FAILED)
        val peerPin = peerSpkiSha256()
        val pins = listOf(localPin, peerPin).sortedWith(::compareUnsignedBytes)
        MessageDigest.getInstance("SHA-256").digest(
            TLS_CONTEXT_DOMAIN +
                lengthDelimited(session.id) +
                lengthDelimited(session.protocol.encodeToByteArray()) +
                lengthDelimited(session.cipherSuite.encodeToByteArray()) +
                lengthDelimited(pins[0]) +
                lengthDelimited(pins[1]),
        )
    } catch (error: LanConnectionException) {
        throw error
    } catch (_: Throwable) {
        throw LanConnectionException(LanConnectionFailure.TLS_FAILED)
    }

    override suspend fun readHello(): LanFrame = readFrame()

    override suspend fun writeHello(frame: LanFrame) = writeFrame(frame)

    override suspend fun readFrame(): LanFrame {
        try {
            socket.soTimeout = readTimeoutMillis
            return runInterruptible(Dispatchers.IO) {
                val input = socket.inputStream
                val prefix = ByteArray(LanFrameLimits.PREFIX_BYTES)
                readFully(input, prefix)
                val size = ByteBuffer.wrap(prefix).int
                if (size <= 0 || size > LanFrameLimits.MAX_FRAME_BYTES) {
                    throw LanFrameException(
                        if (size <= 0) LanFrameFailure.INVALID_LENGTH else LanFrameFailure.FRAME_TOO_LARGE,
                    )
                }
                val body = ByteArray(size)
                readFully(input, body)
                LanFrameCodec.decode(prefix + body)
            }
        } catch (error: CancellationException) {
            close()
            throw error
        } catch (_: SocketTimeoutException) {
            close()
            throw LanConnectionException(LanConnectionFailure.TIMEOUT)
        } catch (error: LanFrameException) {
            close()
            throw error
        } catch (_: Throwable) {
            close()
            throw LanConnectionException(LanConnectionFailure.CLOSED)
        }
    }

    override suspend fun writeFrame(frame: LanFrame) {
        try {
            val encoded = LanFrameCodec.encode(frame)
            withTimeout(writeTimeoutMillis) {
                runInterruptible(Dispatchers.IO) {
                    socket.outputStream.write(encoded)
                    socket.outputStream.flush()
                }
            }
        } catch (error: CancellationException) {
            close()
            throw error
        } catch (error: LanFrameException) {
            close()
            throw error
        } catch (_: Throwable) {
            close()
            throw LanConnectionException(LanConnectionFailure.CLOSED)
        }
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) runCatching { socket.close() }
    }

    private fun readFully(input: java.io.InputStream, target: ByteArray) {
        var offset = 0
        while (offset < target.size) {
            val read = input.read(target, offset, target.size - offset)
            if (read < 0) throw LanConnectionException(LanConnectionFailure.CLOSED)
            if (read > 0) offset += read
        }
    }

    companion object { const val DEFAULT_IO_TIMEOUT_MILLIS = 10_000 }
}

private fun lengthDelimited(value: ByteArray): ByteArray =
    ByteBuffer.allocate(Int.SIZE_BYTES + value.size).putInt(value.size).put(value).array()

private fun compareUnsignedBytes(first: ByteArray, second: ByteArray): Int {
    for (index in 0 until minOf(first.size, second.size)) {
        val compared = (first[index].toInt() and 0xff).compareTo(second[index].toInt() and 0xff)
        if (compared != 0) return compared
    }
    return first.size.compareTo(second.size)
}

private val TLS_CONTEXT_DOMAIN = "twinotify-lan-tls-session-context-v1".encodeToByteArray()

private class DefaultAuthenticatedLanConnection(
    private val socket: LanTlsSocket,
    override val session: LanAuthenticatedSession,
) : AuthenticatedLanConnection {
    private val closed = AtomicBoolean(false)
    private val collecting = AtomicBoolean(false)
    private val sendMutex = Mutex()

    /**
     * One ordered reader, enforced. Two collectors would interleave reads of the
     * same socket and silently reorder or split the frame stream, and a second
     * collection after the first ends would resume a stream already consumed.
     */
    override val incoming: Flow<LanFrame> = flow {
        if (!collecting.compareAndSet(false, true)) {
            throw LanConnectionException(LanConnectionFailure.ALREADY_COLLECTED)
        }
        while (!closed.get()) {
            val frame = try {
                socket.readFrame()
            } catch (error: CancellationException) {
                close()
                throw error
            } catch (_: Throwable) {
                close()
                return@flow
            }
            if (frame is LanFrame.Hello || frame is LanFrame.HelloAck) {
                close()
                throw LanConnectionException(LanConnectionFailure.AUTHENTICATION_FAILED)
            }
            emit(frame)
            if (frame is LanFrame.Close) close()
        }
    }

    override suspend fun send(frame: LanFrame) = sendMutex.withLock {
        if (closed.get()) throw LanConnectionException(LanConnectionFailure.CLOSED)
        if (frame is LanFrame.Hello || frame is LanFrame.HelloAck) {
            throw LanConnectionException(LanConnectionFailure.AUTHENTICATION_FAILED)
        }
        try {
            socket.writeFrame(frame)
        } catch (error: CancellationException) {
            close()
            throw error
        } catch (_: Throwable) {
            close()
            throw LanConnectionException(LanConnectionFailure.CLOSED)
        }
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) socket.close()
    }
}
