package co.twinotify.core.bluetooth

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest

@OptIn(ExperimentalCoroutinesApi::class)
class BluetoothConnectorTest {
    @Test
    fun smallerDeviceIdIsTheClientAndDialsAfterCancellingDiscovery() = runTest {
        val socket = FakeSocket(PEER_ADDRESS)
        val links = FakeLinks(connect = { socket })
        val authenticator = RecordingAuthenticator(LARGER)
        val connector = connector(localDeviceId = SMALLER, peerDeviceId = LARGER, links = links, authenticator = authenticator)

        assertEquals(BluetoothRole.CLIENT, connector.normalRole)
        val wire = connector.connect()

        assertEquals(listOf("cancelDiscovery", "connect"), links.calls)
        assertEquals(listOf(BluetoothRole.CLIENT), authenticator.roles)
        assertEquals(LARGER, wire.peerDeviceId)
        assertEquals(0, currentTime)
        assertFalse(socket.closed)
    }

    @Test
    fun largerDeviceIdListensAndAcceptsOnlyTheResolvedDevice() = runTest {
        val stranger = FakeSocket("AA:BB:CC:DD:EE:01")
        val expected = FakeSocket(PEER_ADDRESS)
        val listener = FakeListener(listOf(stranger, expected))
        val links = FakeLinks(listen = { listener })
        val authenticator = RecordingAuthenticator(SMALLER)
        val connector = connector(localDeviceId = LARGER, peerDeviceId = SMALLER, links = links, authenticator = authenticator)

        assertEquals(BluetoothRole.SERVER, connector.normalRole)
        val wire = connector.connect()

        assertEquals(listOf("cancelDiscovery", "listen"), links.calls)
        assertTrue(stranger.closed, "a socket from another address must be closed before any byte is parsed")
        assertFalse(stranger.read, "a socket from another address must not be read")
        assertFalse(expected.closed)
        assertEquals(listOf(BluetoothRole.SERVER), authenticator.roles)
        assertSame(expected, authenticator.sockets.single())
        assertTrue(listener.closed, "the listener must close once a socket is chosen")
        assertEquals(SMALLER, wire.peerDeviceId)
    }

    @Test
    fun clientThatCannotConnectListensForTheReverseAttemptAfterFifteenSeconds() = runTest {
        val expected = FakeSocket(PEER_ADDRESS)
        val links = FakeLinks(
            connect = { throw IOException("refused") },
            listen = { FakeListener(listOf(expected)) },
        )
        val authenticator = RecordingAuthenticator(LARGER)
        val connector = connector(localDeviceId = SMALLER, peerDeviceId = LARGER, links = links, authenticator = authenticator)

        val wire = connector.connect()

        assertEquals(listOf("cancelDiscovery", "connect", "listen"), links.calls)
        assertEquals(15_000, currentTime)
        assertEquals(listOf(BluetoothRole.SERVER), authenticator.roles)
        assertEquals(LARGER, wire.peerDeviceId)
    }

    @Test
    fun serverThatHearsNothingDialsTheReverseAttemptAfterFifteenSeconds() = runTest {
        val expected = FakeSocket(PEER_ADDRESS)
        val listener = FakeListener(emptyList())
        val links = FakeLinks(listen = { listener }, connect = { expected })
        val authenticator = RecordingAuthenticator(SMALLER)
        val connector = connector(localDeviceId = LARGER, peerDeviceId = SMALLER, links = links, authenticator = authenticator)

        val wire = connector.connect()

        assertEquals(listOf("cancelDiscovery", "listen", "connect"), links.calls)
        assertTrue(listener.closed)
        assertEquals(15_000, currentTime)
        assertEquals(listOf(BluetoothRole.CLIENT), authenticator.roles)
        assertEquals(SMALLER, wire.peerDeviceId)
    }

    @Test
    fun connectCeilingBoundsBothDirectionsAndReportsOneCode() = runTest {
        val links = FakeLinks(connect = { awaitCancellation() }, listen = { FakeListener(emptyList()) })
        val connector = connector(localDeviceId = SMALLER, peerDeviceId = LARGER, links = links)

        val error = assertFailsWith<BluetoothConnectException> { connector.connect() }

        assertEquals(BluetoothConnectFailure.CONNECT_TIMEOUT, error.failure)
        assertEquals("bluetooth_connect_timeout", error.message)
        assertEquals(listOf("cancelDiscovery", "connect", "listen"), links.calls)
        // 12 s normal ceiling, reverse attempt at 15 s, 12 s reverse ceiling.
        assertEquals(27_000, currentTime)
    }

    @Test
    fun handshakeCeilingClosesTheSocket() = runTest {
        val socket = FakeSocket(PEER_ADDRESS)
        val links = FakeLinks(connect = { socket })
        val connector = connector(
            localDeviceId = SMALLER,
            peerDeviceId = LARGER,
            links = links,
            authenticator = BluetoothWireAuthenticator { _, _ -> awaitCancellation() },
        )

        val error = assertFailsWith<BluetoothConnectException> { connector.connect() }

        assertEquals(BluetoothConnectFailure.HANDSHAKE_TIMEOUT, error.failure)
        assertEquals(10_000, currentTime)
        assertTrue(socket.closed)
    }

    @Test
    fun handshakeFailureClosesTheSocketAndKeepsItsBoundedCode() = runTest {
        val socket = FakeSocket(PEER_ADDRESS)
        val links = FakeLinks(connect = { socket })
        val connector = connector(
            localDeviceId = SMALLER,
            peerDeviceId = LARGER,
            links = links,
            authenticator = BluetoothWireAuthenticator { _, _ ->
                throw BluetoothHandshakeException(BluetoothHandshakeFailure.IDENTITY_MISMATCH)
            },
        )

        val error = assertFailsWith<BluetoothHandshakeException> { connector.connect() }

        assertEquals("bluetooth_identity_mismatch", error.message)
        assertTrue(socket.closed)
    }

    @Test
    fun unexpectedFailuresBecomeOneBoundedConnectCode() = runTest {
        val links = FakeLinks(connect = { throw IllegalStateException("adapter detail") }, listen = { throw IOException("no radio") })
        val connector = connector(localDeviceId = SMALLER, peerDeviceId = LARGER, links = links)

        val error = assertFailsWith<BluetoothConnectException> { connector.connect() }

        assertEquals(BluetoothConnectFailure.CONNECT_FAILED, error.failure)
        assertFalse(error.message!!.contains("adapter"))
        BluetoothConnectFailure.entries.forEach { assertTrue(it.code.startsWith("bluetooth_"), it.code) }
        assertFailsWith<IllegalArgumentException> { connector(localDeviceId = SMALLER, peerDeviceId = SMALLER, links = links) }
    }

    private fun connector(
        localDeviceId: String,
        peerDeviceId: String,
        links: BluetoothLinkProvider,
        authenticator: BluetoothWireAuthenticator = RecordingAuthenticator(peerDeviceId),
    ) = BluetoothConnector(
        localDeviceId = localDeviceId,
        peerDeviceId = peerDeviceId,
        links = links,
        authenticator = authenticator,
    )

    private class FakeLinks(
        private val connect: suspend () -> BluetoothStreamSocket = { error("unexpected connect") },
        private val listen: suspend () -> BluetoothLinkListener = { error("unexpected listen") },
    ) : BluetoothLinkProvider {
        val calls = mutableListOf<String>()
        override val peerAddress: String = PEER_ADDRESS
        override suspend fun cancelDiscovery() { calls += "cancelDiscovery" }
        override suspend fun listen(): BluetoothLinkListener { calls += "listen"; return listen.invoke() }
        override suspend fun connect(): BluetoothStreamSocket { calls += "connect"; return connect.invoke() }
    }

    private class FakeListener(sockets: List<FakeSocket>) : BluetoothLinkListener {
        private val pending = ArrayDeque(sockets)
        var closed = false
        override suspend fun accept(): BluetoothStreamSocket = pending.removeFirstOrNull() ?: awaitCancellation()
        override fun close() { closed = true }
    }

    private class FakeSocket(override val remoteAddress: String) : BluetoothStreamSocket {
        var closed = false
        var read = false
        override val inputStream: InputStream = object : InputStream() {
            override fun read(): Int { read = true; return -1 }
        }
        override val outputStream: OutputStream = ByteArrayOutputStream()
        override fun close() { closed = true }
    }

    /** Stands in for the signed handshake; the peer it reports is whoever the connector was built for. */
    private class RecordingAuthenticator(private val peerDeviceId: String) : BluetoothWireAuthenticator {
        val roles = mutableListOf<BluetoothRole>()
        val sockets = mutableListOf<BluetoothStreamSocket>()
        override suspend fun authenticate(wire: BluetoothSocketWire, role: BluetoothRole): AuthenticatedBluetoothWire {
            roles += role
            sockets += wire.socket
            return AuthenticatedBluetoothWire(peerDeviceId, ByteArray(32), wire)
        }
    }

    private companion object {
        const val SMALLER = "dev-00000000-0000-0000-0000-000000000001"
        const val LARGER = "dev-00000000-0000-0000-0000-000000000002"
        const val PEER_ADDRESS = "AA:BB:CC:DD:EE:FF"
    }
}
