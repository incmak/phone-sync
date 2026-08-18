package co.twinotify.core.pairing.lan

import java.io.Closeable
import java.io.InputStream
import java.io.OutputStream
import java.net.Socket
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.security.cert.X509Certificate
import java.util.Base64
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLSocket
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import org.json.JSONTokener

enum class PairingTransportFailure(val code: String) {
    INVALID_FRAME("invalid_frame"),
    FRAME_BUDGET_EXCEEDED("frame_budget_exceeded"),
    NSD_FAILED("nsd_failed"),
    CONNECT_TIMEOUT("connect_timeout"),
    ACCEPT_TIMEOUT("accept_timeout"),
    TLS_PIN_MISMATCH("tls_pin_mismatch"),
    TLS_FAILED("tls_failed"),
}

class PairingTransportException(val failure: PairingTransportFailure) : Exception(failure.code)

class FrameBudget(private val maxFrames: Int, private val maxBytes: Int) {
    private var frames = 0
    private var bytes = 0

    init {
        require(maxFrames > 0 && maxBytes > 0) { "invalid_frame_budget" }
    }

    @Synchronized
    internal fun consume(frameBytes: Int) {
        if (frameBytes <= 0 || frames >= maxFrames || frameBytes > maxBytes - bytes) {
            throw PairingTransportException(PairingTransportFailure.FRAME_BUDGET_EXCEEDED)
        }
        frames++
        bytes += frameBytes
    }
}

internal object OfflinePairingFrameCodec {
    const val MAX_FRAME_BYTES = 64 * 1024
    private const val PREFIX_BYTES = 4

    fun write(output: OutputStream, frame: OfflinePairingFrame, budget: FrameBudget) {
        val payload = encode(frame).encodeToByteArray()
        if (payload.isEmpty() || payload.size > MAX_FRAME_BYTES) invalidFrame()
        budget.consume(PREFIX_BYTES + payload.size)
        output.write(ByteBuffer.allocate(PREFIX_BYTES).putInt(payload.size).array())
        output.write(payload)
        output.flush()
    }

    fun read(input: InputStream, budget: FrameBudget): OfflinePairingFrame {
        val prefix = ByteArray(PREFIX_BYTES)
        readFully(input, prefix)
        val length = ByteBuffer.wrap(prefix).int
        if (length <= 0 || length > MAX_FRAME_BYTES) invalidFrame()
        budget.consume(PREFIX_BYTES + length)
        val payload = ByteArray(length)
        readFully(input, payload)
        val raw = payload.decodeStrictUtf8() ?: invalidFrame()
        return decode(raw)
    }

    private fun encode(frame: OfflinePairingFrame): String = when (frame) {
        is OfflinePairingFrame.Hello -> JSONObject().apply {
            put("type", "pair.hello")
            put("session", frame.sessionId)
            put("lifetime_ms", frame.lifetimeMillis)
            put("hello", JSONObject().apply {
                put("device_id", frame.hello.deviceId)
                put("encryption_key", base64(frame.hello.encryptionPublicKey.copy()))
                put("signing_key", base64(frame.hello.signingPublicKey.copy()))
                put("tls_pin", base64(frame.hello.tlsSpkiSha256.copy()))
                put("nonce", base64(frame.hello.nonce.copy()))
            })
        }.toString()
        is OfflinePairingFrame.Signature -> JSONObject().apply {
            put("type", "pair.signature")
            put("session", frame.sessionId)
            put("signature", base64(frame.signature))
        }.toString()
    }

    private fun decode(raw: String): OfflinePairingFrame = try {
        val tokener = JSONTokener(raw)
        val value = tokener.nextValue()
        if (value !is JSONObject || tokener.nextClean() != 0.toChar()) invalidFrame()
        when (value.requiredString("type")) {
            "pair.hello" -> {
                value.requireKeys(setOf("type", "session", "lifetime_ms", "hello"))
                val hello = value.get("hello") as? JSONObject ?: invalidFrame()
                hello.requireKeys(setOf("device_id", "encryption_key", "signing_key", "tls_pin", "nonce"))
                val lifetime = value.get("lifetime_ms") as? Number ?: invalidFrame()
                val lifetimeLong = lifetime.toLong()
                if (lifetime.toDouble() != lifetimeLong.toDouble()) invalidFrame()
                OfflinePairingFrame.Hello(
                    sessionId = value.requiredString("session"),
                    lifetimeMillis = lifetimeLong,
                    hello = LanPairingHello(
                        deviceId = hello.requiredString("device_id"),
                        encryptionPublicKey = LanPairingBytes(hello.requiredBytes("encryption_key", 32)),
                        signingPublicKey = LanPairingBytes(hello.requiredBytes("signing_key", 32)),
                        tlsSpkiSha256 = LanPairingBytes(hello.requiredBytes("tls_pin", 32)),
                        nonce = LanPairingBytes(hello.requiredBytes("nonce", 32)),
                    ),
                )
            }
            "pair.signature" -> {
                value.requireKeys(setOf("type", "session", "signature"))
                OfflinePairingFrame.Signature(
                    value.requiredString("session"),
                    value.requiredBytes("signature", 64),
                )
            }
            else -> invalidFrame()
        }
    } catch (error: PairingTransportException) {
        throw error
    } catch (_: Throwable) {
        invalidFrame()
    }

    private fun JSONObject.requireKeys(expected: Set<String>) {
        if (keys().asSequence().toSet() != expected) invalidFrame()
    }

    private fun JSONObject.requiredString(key: String): String = get(key) as? String ?: invalidFrame()

    private fun JSONObject.requiredBytes(key: String, size: Int): ByteArray {
        val encoded = requiredString(key)
        if (encoded.length > ((size + 2) / 3) * 4) invalidFrame()
        val decoded = try { Base64.getDecoder().decode(encoded) } catch (_: IllegalArgumentException) { invalidFrame() }
        if (decoded.size != size) invalidFrame()
        return decoded
    }

    private fun readFully(input: InputStream, target: ByteArray) {
        var offset = 0
        while (offset < target.size) {
            val read = input.read(target, offset, target.size - offset)
            if (read < 0) throw PairingTransportException(PairingTransportFailure.INVALID_FRAME)
            if (read == 0) continue
            offset += read
        }
    }

    private fun base64(value: ByteArray): String = Base64.getEncoder().encodeToString(value)

    private fun invalidFrame(): Nothing = throw PairingTransportException(PairingTransportFailure.INVALID_FRAME)
}

class PairingConnection internal constructor(
    private val socket: Socket,
    peerSpkiSha256: ByteArray?,
    inboundBudget: FrameBudget = FrameBudget(DEFAULT_MAX_FRAMES, DEFAULT_MAX_BYTES),
    outboundBudget: FrameBudget = FrameBudget(DEFAULT_MAX_FRAMES, DEFAULT_MAX_BYTES),
) : Closeable {
    private val peerPin = peerSpkiSha256?.copyOf()
    private val inbound = inboundBudget
    private val outbound = outboundBudget

    val peerSpkiSha256: ByteArray? get() = peerPin?.copyOf()

    fun read(): OfflinePairingFrame = OfflinePairingFrameCodec.read(socket.getInputStream(), inbound)

    fun write(frame: OfflinePairingFrame) = OfflinePairingFrameCodec.write(socket.getOutputStream(), frame, outbound)

    override fun close() = socket.close()

    private companion object {
        const val DEFAULT_MAX_FRAMES = 16
        const val DEFAULT_MAX_BYTES = 256 * 1024
    }
}

internal fun interface PairingTlsClient {
    suspend fun handshake(rawSocket: Socket, host: String, port: Int, expectedPin: ByteArray): PairingConnection
}

internal interface PairingTlsServer : Closeable {
    val localPort: Int
    suspend fun accept(): PairingConnection
}

internal class JssePairingTlsClient(
    private val contextFactory: (ByteArray) -> SSLContext = LanTlsContextFactory::clientContext,
) : PairingTlsClient {
    override suspend fun handshake(rawSocket: Socket, host: String, port: Int, expectedPin: ByteArray): PairingConnection {
        if (expectedPin.size != 32) throw PairingTransportException(PairingTransportFailure.TLS_PIN_MISMATCH)
        val sslSocket = try {
            contextFactory(expectedPin.copyOf()).socketFactory.createSocket(rawSocket, host, port, true) as SSLSocket
        } catch (_: Throwable) {
            rawSocket.close()
            throw PairingTransportException(PairingTransportFailure.TLS_FAILED)
        }
        try {
            sslSocket.useClientMode = true
            runInterruptible(Dispatchers.IO) { sslSocket.startHandshake() }
            val certificate = sslSocket.session.peerCertificates.firstOrNull() as? X509Certificate
                ?: throw PairingTransportException(PairingTransportFailure.TLS_FAILED)
            val actualPin = MessageDigest.getInstance("SHA-256").digest(certificate.publicKey.encoded)
            if (!MessageDigest.isEqual(expectedPin, actualPin)) {
                throw PairingTransportException(PairingTransportFailure.TLS_PIN_MISMATCH)
            }
            return PairingConnection(sslSocket, actualPin)
        } catch (error: PairingTransportException) {
            sslSocket.close()
            throw error
        } catch (error: CancellationException) {
            sslSocket.close()
            throw error
        } catch (_: Throwable) {
            sslSocket.close()
            throw PairingTransportException(PairingTransportFailure.TLS_PIN_MISMATCH)
        }
    }
}

internal class JssePairingTlsServer private constructor(
    private val socket: SSLServerSocket,
) : PairingTlsServer {
    override val localPort: Int get() = socket.localPort

    override suspend fun accept(): PairingConnection {
        val accepted = runInterruptible(Dispatchers.IO) { socket.accept() as SSLSocket }
        try {
            accepted.useClientMode = false
            runInterruptible(Dispatchers.IO) { accepted.startHandshake() }
            return PairingConnection(accepted, null)
        } catch (error: CancellationException) {
            accepted.close()
            throw error
        } catch (_: Throwable) {
            accepted.close()
            throw PairingTransportException(PairingTransportFailure.TLS_FAILED)
        }
    }

    override fun close() = socket.close()

    companion object {
        fun open(context: SSLContext = LanTlsContextFactory.serverContext()): JssePairingTlsServer {
            val socket = context.serverSocketFactory.createServerSocket(0) as SSLServerSocket
            socket.useClientMode = false
            socket.needClientAuth = false
            return JssePairingTlsServer(socket)
        }
    }
}

class OfflinePairingTransport internal constructor(
    private val nsd: PairingNsdAdapter,
    private val tlsClient: PairingTlsClient = JssePairingTlsClient(),
    private val tlsServer: PairingTlsServer? = null,
    private val acceptTimeoutMillis: Long = DEFAULT_TIMEOUT_MILLIS,
    private val connectTimeoutMillis: Long = DEFAULT_TIMEOUT_MILLIS,
    private val cleanupTimeoutMillis: Long = DEFAULT_CLEANUP_TIMEOUT_MILLIS,
) {
    suspend fun connect(sessionId: String, expectedPin: ByteArray): PairingConnection {
        validateSessionId(sessionId)
        if (expectedPin.size != 32) throw PairingTransportException(PairingTransportFailure.TLS_PIN_MISMATCH)
        var rawSocket: Socket? = null
        try {
            return withTimeout(connectTimeoutMillis) {
                val endpoint = nsd.resolve(sessionId)
                runInterruptible(Dispatchers.IO) {
                    val socket = endpoint.network.openSocket()
                    rawSocket = socket
                    socket.connect(
                        InetSocketAddress(endpoint.address, endpoint.port),
                        connectTimeoutMillis.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
                    )
                }
                tlsClient.handshake(
                    rawSocket = rawSocket ?: throw PairingTransportException(PairingTransportFailure.TLS_FAILED),
                    host = endpoint.address.hostAddress ?: throw PairingTransportException(PairingTransportFailure.NSD_FAILED),
                    port = endpoint.port,
                    expectedPin = expectedPin.copyOf(),
                )
            }
        } catch (_: TimeoutCancellationException) {
            rawSocket?.close()
            throw PairingTransportException(PairingTransportFailure.CONNECT_TIMEOUT)
        } catch (_: SocketTimeoutException) {
            rawSocket?.close()
            throw PairingTransportException(PairingTransportFailure.CONNECT_TIMEOUT)
        } catch (error: PairingTransportException) {
            rawSocket?.close()
            throw error
        } catch (error: CancellationException) {
            rawSocket?.close()
            throw error
        } catch (_: Throwable) {
            rawSocket?.close()
            throw PairingTransportException(PairingTransportFailure.NSD_FAILED)
        } finally {
            runCatching { nsd.stopDiscovery() }
        }
    }

    suspend fun accept(sessionId: String): PairingConnection {
        validateSessionId(sessionId)
        val server = tlsServer ?: JssePairingTlsServer.open()
        var advertisement: PairingAdvertisement? = null
        try {
            advertisement = nsd.register(sessionId, server.localPort)
            return withTimeout(acceptTimeoutMillis) { server.accept() }
        } catch (_: TimeoutCancellationException) {
            throw PairingTransportException(PairingTransportFailure.ACCEPT_TIMEOUT)
        } catch (error: PairingTransportException) {
            throw error
        } catch (error: CancellationException) {
            throw error
        } catch (_: Throwable) {
            throw PairingTransportException(PairingTransportFailure.NSD_FAILED)
        } finally {
            server.close()
            advertisement?.let { handle ->
                withContext(NonCancellable) {
                    withTimeoutOrNull(cleanupTimeoutMillis) {
                        runCatching { nsd.unregister(handle) }
                    }
                }
            }
        }
    }

    private companion object {
        const val DEFAULT_TIMEOUT_MILLIS = 30_000L
        const val DEFAULT_CLEANUP_TIMEOUT_MILLIS = 1_000L
    }
}
