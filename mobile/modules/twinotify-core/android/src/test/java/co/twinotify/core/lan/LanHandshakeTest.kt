package co.twinotify.core.lan

import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.test.runTest

class LanHandshakeTest {
    @Test
    fun signedLoopbackAuthenticatesBothRolesToTheSameSession() = runTest {
        val fixture = fixture()
        val initiatorSocket = LoopbackTlsSocket(bytes(70), bytes(71))
        val acceptorSocket = LoopbackTlsSocket(bytes(70), bytes(72))
        initiatorSocket.peer = acceptorSocket
        acceptorSocket.peer = initiatorSocket

        val initiator = async { SignedLanSocketHandshake(fixture.initiator, secureRandom = FixedRandom(31)).authenticate(initiatorSocket) }
        val acceptor = async { SignedLanSocketHandshake(fixture.acceptor, secureRandom = FixedRandom(32)).authenticate(acceptorSocket) }

        val first = initiator.await()
        val second = acceptor.await()
        assertEquals(device(2), first.peerDeviceId)
        assertEquals(device(1), second.peerDeviceId)
        assertTrue(first.sessionId.contentEquals(second.sessionId))
    }

    @Test
    fun wrongSigningKeyIsRejected() {
        val fixture = fixture()
        val hello = fixture.initiator.createHello(fixture.context)

        val error = assertFailsWith<LanHandshakeException> {
            fixture.acceptor.copy(peerSigningKey = bytes(99)).verifyPeer(hello, fixture.context)
        }

        assertEquals(LanHandshakeFailure.SIGNATURE_INVALID, error.failure)
    }

    @Test
    fun nonceReplayIsRejectedAfterFirstAuthenticatedUse() {
        val fixture = fixture()
        val hello = fixture.initiator.createHello(fixture.context)

        fixture.acceptor.verifyPeer(hello, fixture.context)
        val error = assertFailsWith<LanHandshakeException> {
            fixture.acceptor.verifyPeer(hello, fixture.context)
        }

        assertEquals(LanHandshakeFailure.REPLAYED_NONCE, error.failure)
        assertTrue(fixture.acceptor.replayGuard.size <= LanReplayGuard.MAX_ENTRIES)
    }

    @Test
    fun reflectedRoleIsRejected() {
        val fixture = fixture()
        val reflected = fixture.initiator.createHello(fixture.context.copy(peerRole = LanConnectionRole.INITIATOR))

        val error = assertFailsWith<LanHandshakeException> {
            fixture.acceptor.verifyPeer(reflected, fixture.context)
        }

        assertEquals(LanHandshakeFailure.ROLE_MISMATCH, error.failure)
    }

    @Test
    fun protocolDowngradeIsRejectedEvenWhenSignatureIsValid() {
        val fixture = fixture(protocolVersion = 2, protocolFloor = 2)
        val downgraded = fixture.context.copy(protocolVersion = 1)
        val hello = fixture.initiator.createHello(downgraded)

        val error = assertFailsWith<LanHandshakeException> {
            fixture.acceptor.verifyPeer(hello, fixture.context)
        }

        assertEquals(LanHandshakeFailure.PROTOCOL_DOWNGRADE, error.failure)
    }

    @Test
    fun transcriptBindsBothIdentitiesNoncesRolesVersionAndTlsSession() {
        val fixture = fixture()
        val hello = fixture.initiator.createHello(fixture.context)
        val mutations = listOf(
            fixture.context.copy(initiatorDeviceId = device(9)),
            fixture.context.copy(acceptorDeviceId = device(9)),
            fixture.context.copy(initiatorNonce = bytes(9)),
            fixture.context.copy(acceptorNonce = bytes(9)),
            fixture.context.copy(peerRole = LanConnectionRole.INITIATOR),
            fixture.context.copy(protocolVersion = 2),
            fixture.context.copy(tlsSessionContext = bytes(9)),
            fixture.context.copy(sessionId = bytes(9)),
        )

        mutations.forEach { changed ->
            assertFailsWith<LanHandshakeException> { fixture.acceptor.verifyPeer(hello, changed) }
        }
    }

    @Test
    fun simultaneousConnectionsRetainTheSmallerDeviceInitiatedSessionOnBothPhones() {
        val smallerInitiated = LanAuthenticatedSession(
            peerDeviceId = device(2),
            initiatorDeviceId = device(1),
            sessionId = bytes(8),
        )
        val largerInitiated = LanAuthenticatedSession(
            peerDeviceId = device(1),
            initiatorDeviceId = device(2),
            sessionId = bytes(1),
        )

        assertTrue(LanConnectionArbiter.prefer(smallerInitiated, largerInitiated))
        assertFalse(LanConnectionArbiter.prefer(largerInitiated, smallerInitiated))
    }

    @Test
    fun duplicatePreferredConnectionsUseSessionHashAsStableTieBreak() {
        val first = LanAuthenticatedSession(device(2), device(1), bytes(1))
        val second = LanAuthenticatedSession(device(2), device(1), bytes(2))

        assertTrue(LanConnectionArbiter.prefer(first, second))
        assertFalse(LanConnectionArbiter.prefer(second, first))
    }

    @Test
    fun authenticatedSessionsCompareBySessionValueNotByArrayIdentity() {
        val session = LanAuthenticatedSession(device(2), device(1), bytes(5))
        val equivalent = LanAuthenticatedSession(device(2), device(1), bytes(5))
        val other = LanAuthenticatedSession(device(2), device(1), bytes(6))

        assertEquals(session, equivalent)
        assertEquals(session.hashCode(), equivalent.hashCode())
        assertNotEquals(session, other)
        assertTrue(session.toString().contains("redacted"))
    }

    private fun fixture(protocolVersion: Int = 1, protocolFloor: Int = 1): Fixture {
        val initiatorKey = bytes(11)
        val acceptorKey = bytes(22)
        val context = LanHandshakeContext(
            initiatorDeviceId = device(1),
            acceptorDeviceId = device(2),
            initiatorNonce = bytes(31),
            acceptorNonce = bytes(32),
            localRole = LanConnectionRole.INITIATOR,
            peerRole = LanConnectionRole.ACCEPTOR,
            protocolVersion = protocolVersion,
            tlsSessionContext = bytes(41),
            sessionId = bytes(42),
        )
        return Fixture(
            initiator = LanHandshake(
                localDeviceId = device(1),
                peerDeviceId = device(2),
                localSigningKey = initiatorKey,
                peerSigningKey = acceptorKey,
                localRole = LanConnectionRole.INITIATOR,
                protocolFloor = protocolFloor,
                signer = HmacSigner,
            ),
            acceptor = LanHandshake(
                localDeviceId = device(2),
                peerDeviceId = device(1),
                localSigningKey = acceptorKey,
                peerSigningKey = initiatorKey,
                localRole = LanConnectionRole.ACCEPTOR,
                protocolFloor = protocolFloor,
                signer = HmacSigner,
            ),
            context = context,
        )
    }

    private data class Fixture(
        val initiator: LanHandshake,
        val acceptor: LanHandshake,
        val context: LanHandshakeContext,
    )

    private object HmacSigner : LanHandshakeSigner {
        override fun sign(message: ByteArray, secretKey: ByteArray): ByteArray = mac(message, secretKey)
        override fun verify(message: ByteArray, signature: ByteArray, publicKey: ByteArray): Boolean =
            MessageDigest.isEqual(signature, mac(message, publicKey))

        private fun mac(message: ByteArray, key: ByteArray): ByteArray = Mac.getInstance("HmacSHA256").run {
            init(SecretKeySpec(key, "HmacSHA256"))
            doFinal(message)
        }
    }

    private class FixedRandom(private val seed: Int) : java.security.SecureRandom() {
        override fun nextBytes(bytes: ByteArray) {
            bytes.indices.forEach { bytes[it] = (seed + it).toByte() }
        }
    }

    private class LoopbackTlsSocket(
        private val context: ByteArray,
        private val pin: ByteArray,
    ) : LanTlsSocket {
        lateinit var peer: LoopbackTlsSocket
        private val frames = Channel<LanFrame>(Channel.UNLIMITED)
        override suspend fun startHandshake() = Unit
        override fun peerSpkiSha256(): ByteArray = pin.copyOf()
        override fun tlsSessionContext(): ByteArray = context.copyOf()
        override suspend fun readHello(): LanFrame = frames.receive()
        override suspend fun writeHello(frame: LanFrame) { peer.frames.send(frame) }
        override suspend fun readFrame(): LanFrame = frames.receive()
        override suspend fun writeFrame(frame: LanFrame) { peer.frames.send(frame) }
        override fun close() { frames.close() }
    }

    private fun device(value: Int) = "dev-00000000-0000-0000-0000-${value.toString().padStart(12, '0')}"
    private fun bytes(seed: Int) = ByteArray(32) { (seed + it).toByte() }
}
