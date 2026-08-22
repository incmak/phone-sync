package co.twinotify.core.lan

import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest

@OptIn(ExperimentalCoroutinesApi::class)
class LanPinnedTlsTest {
    @Test
    fun wrongTlsPinClosesBeforeAnySignedHelloBytesAreParsed() = runTest {
        val socket = FakeTlsSocket(peerPin = bytes(2))
        val factory = factory(socket, expectedPin = bytes(1))

        val error = assertFailsWith<LanConnectionException> { factory.connect() }

        assertEquals(LanConnectionFailure.TLS_PIN_MISMATCH, error.failure)
        assertTrue(socket.handshakeStarted)
        assertFalse(socket.helloRead)
        assertTrue(socket.closed)
    }

    @Test
    fun timeoutClosesSocketAndReturnsBoundedFailure() = runTest {
        val socket = FakeTlsSocket(peerPin = bytes(1), blockHandshake = true)
        val factory = factory(socket, expectedPin = bytes(1), timeoutMillis = 100)
        val result = async { runCatching { factory.connect() }.exceptionOrNull() }

        advanceTimeBy(101)

        val error = result.await() as LanConnectionException
        assertEquals(LanConnectionFailure.TIMEOUT, error.failure)
        assertTrue(socket.closed)
    }

    @Test
    fun cancellationClosesSocketAndJoinsHandshakeWork() = runTest {
        val socket = FakeTlsSocket(peerPin = bytes(1), blockHandshake = true)
        val factory = factory(socket, expectedPin = bytes(1), timeoutMillis = 10_000)
        val job = async { factory.connect() }

        socket.started.await()
        job.cancelAndJoin()

        assertTrue(socket.closed)
        assertTrue(socket.handshakeFinished)
    }

    @Test
    fun unreachablePeerFailsAsABoundedTypedConnectFailure() = runTest {
        val factory = LanConnectionFactory(
            socketProvider = { throw java.net.ConnectException("no route to host") },
            expectedPeerTlsPin = bytes(1),
            handshake = FakeHandshake,
        )

        val error = assertFailsWith<LanConnectionException> { factory.connect() }

        assertEquals(LanConnectionFailure.CONNECT_FAILED, error.failure)
    }

    @Test
    fun incomingRejectsASecondCollectorSoOneReaderOwnsFrameOrder() = runTest {
        val socket = FakeTlsSocket(peerPin = bytes(1))
        val connection = factory(socket, expectedPin = bytes(1)).connect()

        val frames = connection.incoming.toList()
        val error = assertFailsWith<LanConnectionException> { connection.incoming.toList() }

        assertEquals(1, frames.size)
        assertEquals(LanConnectionFailure.ALREADY_COLLECTED, error.failure)
    }

    private fun factory(
        socket: FakeTlsSocket,
        expectedPin: ByteArray,
        timeoutMillis: Long = 5_000,
    ) = LanConnectionFactory(
        socketProvider = { socket },
        expectedPeerTlsPin = expectedPin,
        handshake = FakeHandshake,
        timeoutMillis = timeoutMillis,
    )

    private object FakeHandshake : LanSocketHandshake {
        override suspend fun authenticate(socket: LanTlsSocket): LanAuthenticatedSession {
            socket.readHello()
            return LanAuthenticatedSession(
                "dev-00000000-0000-0000-0000-000000000002",
                "dev-00000000-0000-0000-0000-000000000001",
                ByteArray(32) { (3 + it).toByte() },
            )
        }
    }

    private class FakeTlsSocket(
        private val peerPin: ByteArray,
        private val blockHandshake: Boolean = false,
    ) : LanTlsSocket {
        val started = CompletableDeferred<Unit>()
        var handshakeStarted = false
        var handshakeFinished = false
        var helloRead = false
        var closed = false

        override suspend fun startHandshake() {
            handshakeStarted = true
            started.complete(Unit)
            try {
                if (blockHandshake) CompletableDeferred<Unit>().await()
            } finally {
                handshakeFinished = true
            }
        }

        override fun peerSpkiSha256(): ByteArray = peerPin.copyOf()
        override fun tlsSessionContext(): ByteArray = ByteArray(32) { (8 + it).toByte() }
        override suspend fun readHello(): LanFrame {
            helloRead = true
            return LanFrame.Hello(byteArrayOf(1))
        }
        override suspend fun writeHello(frame: LanFrame) = Unit
        override suspend fun readFrame(): LanFrame = LanFrame.Close("closed")
        override suspend fun writeFrame(frame: LanFrame) = Unit
        override fun close() { closed = true }
    }

    private fun bytes(seed: Int) = ByteArray(32) { (seed + it).toByte() }
    private fun device(value: Int) = "dev-00000000-0000-0000-0000-${value.toString().padStart(12, '0')}"
}
