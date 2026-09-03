package co.twinotify.core.bluetooth

import androidx.test.ext.junit.runners.AndroidJUnit4
import co.twinotify.core.direct.DirectCommand
import co.twinotify.core.lan.LanFrameLimits
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.ByteBuffer
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.runner.RunWith

/**
 * Emulators expose no usable Bluetooth radio, so the wire is proven over loopback TCP
 * streams. Everything under test (deadlines, single collector, frame bounds, close
 * semantics) lives above the stream boundary; only the thin BluetoothSocket adapter is
 * left to hardware.
 */
@RunWith(AndroidJUnit4::class)
class BluetoothSocketWireTest {
    @Test
    fun readTimeoutClosesTheSocketAndUnblocksTheReader() = runBlocking {
        val pair = loopback()
        val wire = BluetoothSocketWire(pair.a, readTimeoutMillis = 300, writeTimeoutMillis = 1_000)
        try {
            val error = withTimeout(5_000) {
                assertFailsWith<BluetoothWireException> { wire.incoming.toList() }
            }
            assertEquals(BluetoothWireFailure.TIMEOUT, error.failure)
            assertTrue(wire.closed)
            assertTrue(pair.a.closed)
            // The peer observes the close, which is only possible if the blocking reader released the socket.
            val eof = withTimeout(5_000) { async(Dispatchers.IO) { pair.b.inputStream.read() }.await() }
            assertEquals(-1, eof)
        } finally {
            pair.close()
        }
    }

    @Test
    fun onlyOneCollectorMayReadTheStream() = runBlocking {
        val pair = loopback()
        val wire = BluetoothSocketWire(pair.a, readTimeoutMillis = 5_000, writeTimeoutMillis = 1_000)
        try {
            val first = launch(Dispatchers.IO) { runCatching { wire.incoming.toList() } }
            delay(200)
            val error = assertFailsWith<BluetoothWireException> { wire.incoming.toList() }
            assertEquals(BluetoothWireFailure.ALREADY_COLLECTED, error.failure)
            wire.close()
            withTimeout(5_000) { first.join() }
            wire.close()
            assertTrue(wire.closed)
        } finally {
            pair.close()
        }
    }

    @Test
    fun framesArriveInOrderAndCloseEndsTheFlow() = runBlocking {
        val pair = loopback()
        val reader = BluetoothSocketWire(pair.a, readTimeoutMillis = 5_000, writeTimeoutMillis = 5_000)
        val writer = BluetoothSocketWire(pair.b, readTimeoutMillis = 5_000, writeTimeoutMillis = 5_000)
        try {
            val sent = listOf(
                DirectCommand.Put("{\"v\":2}".encodeToByteArray()),
                DirectCommand.Accepted(MSG_ID, DIGEST),
                DirectCommand.Ping(1),
                DirectCommand.Pong(1),
                DirectCommand.Close("peer_closed"),
            )
            val collected = async(Dispatchers.IO) { reader.incoming.toList() }
            sent.forEach { writer.send(it) }
            val received = withTimeout(5_000) { collected.await() }
            assertEquals(sent, received)
            assertTrue(reader.closed, "a close frame ends the reader's session")
            val afterClose = assertFailsWith<BluetoothWireException> { reader.send(DirectCommand.Ping(2)) }
            assertEquals(BluetoothWireFailure.CLOSED, afterClose.failure)
        } finally {
            pair.close()
        }
    }

    @Test
    fun peerEndOfStreamCompletesTheFlowWithoutFrames() = runBlocking {
        val pair = loopback()
        val wire = BluetoothSocketWire(pair.a, readTimeoutMillis = 5_000, writeTimeoutMillis = 1_000)
        try {
            val collected = async(Dispatchers.IO) { wire.incoming.toList() }
            delay(100)
            pair.b.close()
            assertEquals(emptyList(), withTimeout(5_000) { collected.await() })
            assertTrue(wire.closed)
        } finally {
            pair.close()
        }
    }

    @Test
    fun oversizedLengthPrefixIsRejectedBeforeAnyBodyIsRead() = runBlocking {
        val pair = loopback()
        val wire = BluetoothSocketWire(pair.a, readTimeoutMillis = 5_000, writeTimeoutMillis = 1_000)
        try {
            val collected = async(Dispatchers.IO) { runCatching { wire.incoming.toList() } }
            pair.b.outputStream.write(ByteBuffer.allocate(5).putInt(LanFrameLimits.MAX_FRAME_BYTES + 1).put(1).array())
            pair.b.outputStream.flush()
            val error = withTimeout(5_000) { collected.await() }.exceptionOrNull()
            assertTrue(error is BluetoothFrameException, "expected a frame failure, got $error")
            assertEquals(BluetoothFrameFailure.FRAME_TOO_LARGE, error.failure)
            assertTrue(wire.closed)
        } finally {
            pair.close()
        }
    }

    @Test
    fun writeTimeoutClosesTheSocketWhenThePeerStopsReading() = runBlocking {
        val pair = loopback(bufferBytes = 4_096)
        val wire = BluetoothSocketWire(pair.a, readTimeoutMillis = 5_000, writeTimeoutMillis = 300)
        try {
            val payload = DirectCommand.Put(ByteArray(LanFrameLimits.MAX_ENVELOPE_BYTES) { 'a'.code.toByte() })
            val error = withTimeout(20_000) {
                assertFailsWith<BluetoothWireException> {
                    repeat(8) { wire.send(payload) }
                }
            }
            assertEquals(BluetoothWireFailure.TIMEOUT, error.failure)
            assertTrue(wire.closed)
            assertTrue(pair.a.closed)
        } finally {
            pair.close()
        }
    }

    @Test
    fun handshakeMessagesAreBoundedAndValuesAreRedacted() = runBlocking {
        val pair = loopback()
        val a = BluetoothSocketWire(pair.a, readTimeoutMillis = 5_000, writeTimeoutMillis = 1_000)
        val b = BluetoothSocketWire(pair.b, readTimeoutMillis = 5_000, writeTimeoutMillis = 1_000)
        try {
            val message = ByteArray(64) { it.toByte() }
            a.writeMessage(message)
            assertContentEquals(message, withTimeout(5_000) { b.readMessage() })

            val sessionId = ByteArray(32) { 0x5a }
            val authenticated = AuthenticatedBluetoothWire("dev-peer", sessionId, a)
            assertEquals("AuthenticatedBluetoothWire(peerDeviceId=dev-peer, sessionId=<redacted>)", authenticated.toString())
            assertFalse(a.toString().contains("loopback"))
            assertFalse(a.toString().contains("127.0.0.1"))

            pair.a.outputStream.write(ByteBuffer.allocate(5).putInt(LanFrameLimits.MAX_CONTROL_BYTES + 1).put(1).array())
            pair.a.outputStream.flush()
            val error = withTimeout(5_000) { assertFailsWith<BluetoothFrameException> { b.readMessage() } }
            assertEquals(BluetoothFrameFailure.CONTROL_TOO_LARGE, error.failure)
            assertTrue(b.closed)
        } finally {
            pair.close()
        }
    }

    private class LoopbackSocket(private val socket: Socket) : BluetoothStreamSocket {
        @Volatile var closed = false
        override val remoteAddress: String = "loopback"
        override val inputStream: InputStream get() = socket.getInputStream()
        override val outputStream: OutputStream get() = socket.getOutputStream()
        override fun close() {
            closed = true
            runCatching { socket.close() }
        }
    }

    private class LoopbackPair(val a: LoopbackSocket, val b: LoopbackSocket, private val server: ServerSocket) {
        fun close() {
            a.close()
            b.close()
            runCatching { server.close() }
        }
    }

    private fun loopback(bufferBytes: Int? = null): LoopbackPair {
        val server = ServerSocket()
        bufferBytes?.let { server.receiveBufferSize = it }
        server.bind(java.net.InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 1)
        val client = Socket()
        bufferBytes?.let { client.sendBufferSize = it; client.receiveBufferSize = it }
        client.connect(java.net.InetSocketAddress(InetAddress.getLoopbackAddress(), server.localPort), 5_000)
        val accepted = server.accept()
        bufferBytes?.let { accepted.sendBufferSize = it }
        return LoopbackPair(LoopbackSocket(client), LoopbackSocket(accepted), server)
    }

    private companion object {
        const val MSG_ID = "9f633ff1-0bdd-4a95-bb9e-5d9e0ef8f6af"
        const val DIGEST = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
    }
}
