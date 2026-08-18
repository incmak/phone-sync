package co.twinotify.core.pairing.lan

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketAddress
import java.nio.ByteBuffer
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.rules.Timeout
import org.junit.Rule
import kotlin.test.assertFailsWith

class OfflinePairingTransportTest {
    @get:Rule
    val testTimeout: Timeout = Timeout.seconds(10)

    private val sessionId = UUID.randomUUID().toString()

    @Test
    fun temporaryAdvertisementUsesExactServiceTypeAndOpaqueTxtOnly() {
        val txt = PairingNsdContract.txt(sessionId)

        assertEquals("_twinotify-pair._tcp.", PairingNsdContract.SERVICE_TYPE)
        assertEquals(setOf("session", "version"), txt.keys)
        assertEquals(sessionId, txt.getValue("session").decodeToString())
        assertEquals("1", txt.getValue("version").decodeToString())
    }

    @Test
    fun discoveryAcceptsOnlyTheExactQrSession() {
        val other = UUID.randomUUID().toString()

        assertTrue(PairingNsdContract.matchesSession(PairingNsdContract.txt(sessionId), sessionId))
        assertFalse(PairingNsdContract.matchesSession(PairingNsdContract.txt(other), sessionId))
        assertFalse(
            PairingNsdContract.matchesSession(
                PairingNsdContract.txt(sessionId) + ("device" to "leak".encodeToByteArray()),
                sessionId,
            ),
        )
    }

    @Test
    fun resolverUsesEndpointNetworkWithoutInternetOrValidatedCapabilityChecks() = runBlocking {
        val socket = RecordingSocket()
        val network = RecordingPairingNetwork(socket)
        val endpoint = PairingNsdEndpoint(InetAddress.getLoopbackAddress(), 4455, network)
        val adapter = FakeNsdAdapter(endpoint)
        val tls = FakeTlsClient(socket)
        val transport = OfflinePairingTransport(adapter, tlsClient = tls)

        transport.connect(sessionId, ByteArray(32) { 7 }).close()

        assertEquals(1, network.opens)
        assertEquals(endpoint.address, socket.address)
        assertEquals(endpoint.port, socket.port)
        assertEquals(0, adapter.capabilityQueries)
    }

    @Test
    fun pinMismatchFailsBeforeAnyPairingPayloadRead() = runBlocking {
        val input = CountingInputStream(byteArrayOf(1, 2, 3, 4))
        val socket = RecordingSocket(input)
        val endpoint = PairingNsdEndpoint(
            InetAddress.getLoopbackAddress(),
            4455,
            RecordingPairingNetwork(socket),
        )
        val adapter = FakeNsdAdapter(endpoint)
        val tls = FakeTlsClient(socket, handshakeFailure = PairingTransportException(PairingTransportFailure.TLS_PIN_MISMATCH))

        val error = assertFailsWith<PairingTransportException> {
            OfflinePairingTransport(adapter, tlsClient = tls).connect(sessionId, ByteArray(32))
        }

        assertEquals(PairingTransportFailure.TLS_PIN_MISMATCH, error.failure)
        assertEquals(0, input.readCount)
    }

    @Test
    fun lengthPrefixedFramesRoundTripKnownPairingTypes() {
        val frame = OfflinePairingFrame.Signature(sessionId, ByteArray(64) { it.toByte() })
        val bytes = ByteArrayOutputStream().also {
            OfflinePairingFrameCodec.write(it, frame, FrameBudget(4, 4096))
        }.toByteArray()

        val decoded = OfflinePairingFrameCodec.read(ByteArrayInputStream(bytes), FrameBudget(4, 4096))

        assertTrue(decoded is OfflinePairingFrame.Signature)
        decoded as OfflinePairingFrame.Signature
        assertEquals(sessionId, decoded.sessionId)
        assertArrayEquals(frame.signature, decoded.signature)
    }

    @Test
    fun frameParserRejectsZeroOversizeInvalidUtf8UnknownAndTrailingData() {
        val invalid = listOf(
            prefixed(byteArrayOf()),
            ByteBuffer.allocate(4).putInt(OfflinePairingFrameCodec.MAX_FRAME_BYTES + 1).array(),
            prefixed(byteArrayOf(0xc3.toByte(), 0x28)),
            prefixed("{\"type\":\"pair.unknown\",\"session\":\"$sessionId\"}".encodeToByteArray()),
            prefixed("{\"type\":\"pair.signature\",\"session\":\"$sessionId\",\"signature\":\"${java.util.Base64.getEncoder().encodeToString(ByteArray(64))}\"}x".encodeToByteArray()),
        )

        invalid.forEach { bytes ->
            assertFailsWith<PairingTransportException> {
                OfflinePairingFrameCodec.read(ByteArrayInputStream(bytes), FrameBudget(8, 128 * 1024))
            }
        }
    }

    @Test
    fun frameParserRejectsUnknownFieldsAndTruncatedPayloads() {
        val unknownField = "{\"type\":\"pair.signature\",\"session\":\"$sessionId\",\"signature\":\"${java.util.Base64.getEncoder().encodeToString(ByteArray(64))}\",\"extra\":true}"
        val truncated = ByteBuffer.allocate(7).putInt(8).put(byteArrayOf(1, 2, 3)).array()

        assertFailsWith<PairingTransportException> {
            OfflinePairingFrameCodec.read(ByteArrayInputStream(prefixed(unknownField.encodeToByteArray())), FrameBudget(8, 128 * 1024))
        }
        assertFailsWith<PairingTransportException> {
            OfflinePairingFrameCodec.read(ByteArrayInputStream(truncated), FrameBudget(8, 128 * 1024))
        }
    }

    @Test
    fun inboundAndOutboundBudgetsBoundFrameCountAndTotalBytes() {
        val frame = OfflinePairingFrame.Signature(sessionId, ByteArray(64))
        val encoded = ByteArrayOutputStream().also {
            OfflinePairingFrameCodec.write(it, frame, FrameBudget(1, 4096))
        }.toByteArray()

        val inboundCountBudget = FrameBudget(1, 4096)
        OfflinePairingFrameCodec.read(ByteArrayInputStream(encoded), inboundCountBudget)
        assertFailsWith<PairingTransportException> {
            OfflinePairingFrameCodec.read(ByteArrayInputStream(encoded), inboundCountBudget)
        }

        val outboundByteBudget = FrameBudget(4, encoded.size - 1)
        assertFailsWith<PairingTransportException> {
            OfflinePairingFrameCodec.write(ByteArrayOutputStream(), frame, outboundByteBudget)
        }
    }

    @Test
    fun connectTimeoutClosesSocketAndStopsDiscovery() = runBlocking {
        val socket = RecordingSocket()
        val endpoint = PairingNsdEndpoint(InetAddress.getLoopbackAddress(), 4455, RecordingPairingNetwork(socket))
        val adapter = FakeNsdAdapter(endpoint)
        val never = CompletableDeferred<Unit>()
        val tls = FakeTlsClient(socket, handshakeGate = never)
        val transport = OfflinePairingTransport(adapter, tlsClient = tls, connectTimeoutMillis = 25)

        assertFailsWith<PairingTransportException> {
            transport.connect(sessionId, ByteArray(32))
        }

        assertTrue(socket.closed)
        assertEquals(1, adapter.stopCalls)
    }

    @Test
    fun networkConnectTimeoutClosesTheAlreadyNetworkBoundSocket() = runBlocking {
        val socket = RecordingSocket(connectDelayMillis = 10_000)
        val endpoint = PairingNsdEndpoint(InetAddress.getLoopbackAddress(), 4455, RecordingPairingNetwork(socket))
        val adapter = FakeNsdAdapter(endpoint)

        assertFailsWith<PairingTransportException> {
            OfflinePairingTransport(adapter, tlsClient = FakeTlsClient(socket), connectTimeoutMillis = 25)
                .connect(sessionId, ByteArray(32))
        }

        assertTrue(socket.closed)
        assertEquals(1, adapter.stopCalls)
    }

    @Test
    fun acceptTimeoutClosesListenerAndUnregistersExactAdvertisement() = runBlocking {
        val adapter = FakeNsdAdapter(null)
        val server = FakeTlsServer()
        val transport = OfflinePairingTransport(adapter, tlsServer = server, acceptTimeoutMillis = 25)

        assertFailsWith<PairingTransportException> { transport.accept(sessionId) }

        assertTrue(server.closed)
        assertEquals(1, adapter.unregisterCalls)
        assertTrue(adapter.registeredHandle === adapter.unregisteredHandle)
    }

    @Test
    fun acceptTimeoutRemainsBoundedWhenUnregisterCallbackNeverArrives() = runBlocking {
        val adapter = FakeNsdAdapter(null, unregisterGate = CompletableDeferred())
        val server = FakeTlsServer()
        val transport = OfflinePairingTransport(
            adapter,
            tlsServer = server,
            acceptTimeoutMillis = 25,
            cleanupTimeoutMillis = 25,
        )

        val startedAt = System.nanoTime()
        assertFailsWith<PairingTransportException> { transport.accept(sessionId) }

        assertTrue((System.nanoTime() - startedAt) / 1_000_000 < 500)
        assertTrue(server.closed)
        assertEquals(1, adapter.unregisterCalls)
        assertTrue(adapter.registeredHandle === adapter.unregisteredHandle)
    }

    private fun prefixed(payload: ByteArray): ByteArray =
        ByteBuffer.allocate(4 + payload.size).putInt(payload.size).put(payload).array()

    private class FakeNsdAdapter(
        private val endpoint: PairingNsdEndpoint?,
        private val unregisterGate: CompletableDeferred<Unit>? = null,
    ) : PairingNsdAdapter {
        var stopCalls = 0
        var unregisterCalls = 0
        var capabilityQueries = 0
        var registeredHandle: PairingAdvertisement? = null
        var unregisteredHandle: PairingAdvertisement? = null

        override suspend fun register(sessionId: String, port: Int): PairingAdvertisement =
            PairingAdvertisement(Any()).also { registeredHandle = it }

        override suspend fun resolve(sessionId: String): PairingNsdEndpoint = endpoint ?: error("not resolving")

        override suspend fun unregister(advertisement: PairingAdvertisement) {
            unregisterCalls++
            unregisteredHandle = advertisement
            unregisterGate?.await()
        }

        override suspend fun stopDiscovery() {
            stopCalls++
        }
    }

    private class RecordingPairingNetwork(private val socket: RecordingSocket) : PairingNetwork {
        var opens = 0
        override fun openSocket(): Socket {
            opens++
            return socket
        }
    }

    private class RecordingSocket(
        private val source: java.io.InputStream = ByteArrayInputStream(byteArrayOf()),
        private val connectDelayMillis: Long = 0,
    ) : Socket() {
        var closed = false
        var address: InetAddress? = null
        var port: Int? = null
        override fun connect(endpoint: SocketAddress, timeout: Int) {
            if (connectDelayMillis > 0) Thread.sleep(connectDelayMillis)
            endpoint as InetSocketAddress
            address = endpoint.address
            port = endpoint.port
        }
        override fun getInputStream() = source
        override fun getOutputStream() = ByteArrayOutputStream()
        override fun close() { closed = true }
    }

    private class CountingInputStream(bytes: ByteArray) : ByteArrayInputStream(bytes) {
        var readCount = 0
        override fun read(): Int { readCount++; return super.read() }
        override fun read(b: ByteArray, off: Int, len: Int): Int { readCount++; return super.read(b, off, len) }
    }

    private class FakeTlsClient(
        private val socket: RecordingSocket,
        private val handshakeFailure: PairingTransportException? = null,
        private val handshakeGate: CompletableDeferred<Unit>? = null,
    ) : PairingTlsClient {
        override suspend fun handshake(rawSocket: Socket, host: String, port: Int, expectedPin: ByteArray): PairingConnection {
            handshakeGate?.await()
            handshakeFailure?.let { throw it }
            return PairingConnection(socket, ByteArray(32) { 9 })
        }
    }

    private class FakeTlsServer : PairingTlsServer {
        var closed = false
        override val localPort: Int = 0
        override suspend fun accept(): PairingConnection = CompletableDeferred<PairingConnection>().await()
        override fun close() { closed = true }
    }
}
