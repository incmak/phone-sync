package co.twinotify.core.bluetooth

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.test.runTest

class BluetoothHandshakeTest {
    @Test
    fun clientAndServerAuthenticateOneTranscript() = runTest {
        val pair = inMemoryDuplex()
        val client = async { clientHandshake().authenticate(pair.client) }
        val server = async { serverHandshake().authenticate(pair.server) }

        val clientResult = client.await()
        val serverResult = server.await()

        assertContentEquals(clientResult.sessionId, serverResult.sessionId)
        assertEquals(SERVER_ID, clientResult.peerDeviceId)
        assertEquals(CLIENT_ID, serverResult.peerDeviceId)
        assertEquals(clientResult, serverResult.copy(peerDeviceId = SERVER_ID))
    }

    @Test
    fun sessionIdIsSha256OfTheExactPlanTranscript() = runTest {
        val pair = inMemoryDuplex()
        val client = async { clientHandshake(nonceSeed = 31).authenticate(pair.client) }
        val server = async { serverHandshake(nonceSeed = 32).authenticate(pair.server) }
        val expectedTranscript = ByteArrayOutputStream().use { bytes ->
            DataOutputStream(bytes).use { out ->
                out.write("twinotify-bluetooth-handshake-v1".encodeToByteArray())
                out.writeShort(CLIENT_ID.length)
                out.write(CLIENT_ID.encodeToByteArray())
                out.writeShort(SERVER_ID.length)
                out.write(SERVER_ID.encodeToByteArray())
                out.write(bytes(31))
                out.write(bytes(32))
                out.writeInt(BluetoothConstants.PROTOCOL_VERSION)
                out.writeShort(BluetoothConstants.ROUTE_LABEL.length)
                out.write(BluetoothConstants.ROUTE_LABEL.encodeToByteArray())
            }
            bytes.toByteArray()
        }

        val sessionId = client.await().sessionId
        server.await()

        assertContentEquals(MessageDigest.getInstance("SHA-256").digest(expectedTranscript), sessionId)
        assertContentEquals(
            expectedTranscript,
            BluetoothHandshake.transcript(CLIENT_ID, SERVER_ID, bytes(31), bytes(32), BluetoothConstants.PROTOCOL_VERSION),
        )
    }

    @Test
    fun wrongPeerReplayRoleAndProtocolFailClosed() = runTest {
        assertFailsWithCode("bluetooth_identity_mismatch") { serverAgainst(serverHandshake(peerDeviceId = "wrong")) }
        assertFailsWithCode("bluetooth_replayed_nonce") { replayCapturedHandshake() }
        assertFailsWithCode("bluetooth_role_mismatch") { bothPeersClaimClient() }
        assertFailsWithCode("bluetooth_protocol_downgrade") { serverReceives(BluetoothHello.ClientHello(CLIENT_ID, bytes(31), 0)) }
        assertFailsWithCode("bluetooth_signature_invalid") { serverAgainst(serverHandshake(peerSigningKey = bytes(99))) }
    }

    @Test
    fun clientRejectsWrongServerIdentityAndReflectedRole() = runTest {
        assertFailsWithCode("bluetooth_identity_mismatch") { clientAgainst(clientHandshake(peerDeviceId = "wrong")) }
        assertFailsWithCode("bluetooth_signature_invalid") { clientAgainst(clientHandshake(peerSigningKey = bytes(99))) }
        assertFailsWithCode("bluetooth_role_mismatch") {
            val pair = inMemoryDuplex()
            val client = async { runCatching { clientHandshake().authenticate(pair.client) } }
            // A second client answers the hello with a hello of its own instead of a server hello.
            pair.server.readMessage()
            pair.server.writeMessage(BluetoothHelloCodec.encode(BluetoothHello.ClientHello(SERVER_ID, bytes(32), 1)))
            client.await().getOrThrow()
        }
    }

    @Test
    fun serverRejectsPeerClaimingTheLocalIdentityAndMalformedNonces() = runTest {
        assertFailsWithCode("bluetooth_identity_mismatch") { serverReceives(BluetoothHello.ClientHello(SERVER_ID, bytes(31), 1)) }
        assertFailsWithCode("bluetooth_handshake_invalid") { serverReceivesRaw(byteArrayOf(1, 0, 0)) }
        assertFailsWith<IllegalArgumentException> { BluetoothHello.ClientHello(CLIENT_ID, ByteArray(31), 1) }
    }

    @Test
    fun replayGuardHoldsAtMostTwoHundredFiftySixEntries() {
        val guard = BluetoothReplayGuard()
        fun nonce(index: Int) = ByteArray(32).also { java.nio.ByteBuffer.wrap(it).putInt(index) }
        repeat(BluetoothReplayGuard.MAX_ENTRIES + 10) { index ->
            assertTrue(guard.record(CLIENT_ID, nonce(index), nonce(index + 1)), "entry $index")
        }
        assertEquals(BluetoothReplayGuard.MAX_ENTRIES, guard.size)
        val newest = BluetoothReplayGuard.MAX_ENTRIES + 9
        assertFalse(guard.record(CLIENT_ID, nonce(newest), nonce(newest + 1)), "the newest entry is still remembered")
        assertTrue(guard.record(CLIENT_ID, nonce(0), nonce(1)), "the oldest entry was evicted")
    }

    @Test
    fun handshakeValuesRedactSecretsAndCompareByValue() {
        val clientHello = BluetoothHello.ClientHello(CLIENT_ID, bytes(1), 1)
        val serverHello = BluetoothHello.ServerHello(SERVER_ID, bytes(2), bytes(3))
        val finish = BluetoothHello.ClientFinish(bytes(4))
        val result = BluetoothHandshakeResult(SERVER_ID, bytes(5))

        listOf(clientHello, serverHello, finish, result).forEach { value ->
            val text = value.toString()
            assertTrue(text.contains("redacted"), text)
            assertFalse(text.contains(bytes(1).joinToString("") { "%02x".format(it) }))
        }
        assertEquals(result, BluetoothHandshakeResult(SERVER_ID, bytes(5)))
        assertEquals(result.hashCode(), BluetoothHandshakeResult(SERVER_ID, bytes(5)).hashCode())
        assertNotEquals(result, BluetoothHandshakeResult(SERVER_ID, bytes(6)))
        assertEquals(clientHello, BluetoothHelloCodec.decode(BluetoothHelloCodec.encode(clientHello)))
        assertEquals(serverHello, BluetoothHelloCodec.decode(BluetoothHelloCodec.encode(serverHello)))
        assertEquals(finish, BluetoothHelloCodec.decode(BluetoothHelloCodec.encode(finish)))
    }

    private suspend fun kotlinx.coroutines.CoroutineScope.serverAgainst(server: BluetoothHandshake): BluetoothHandshakeResult {
        val pair = inMemoryDuplex()
        val client = async { runCatching { clientHandshake().authenticate(pair.client) } }
        try {
            return server.authenticate(pair.server)
        } finally {
            client.cancelAndJoin()
        }
    }

    private suspend fun kotlinx.coroutines.CoroutineScope.clientAgainst(client: BluetoothHandshake): BluetoothHandshakeResult {
        val pair = inMemoryDuplex()
        val server = async { runCatching { serverHandshake().authenticate(pair.server) } }
        try {
            return client.authenticate(pair.client)
        } finally {
            server.cancelAndJoin()
        }
    }

    private suspend fun serverReceives(hello: BluetoothHello): BluetoothHandshakeResult =
        serverReceivesRaw(BluetoothHelloCodec.encode(hello))

    private suspend fun serverReceivesRaw(bytes: ByteArray): BluetoothHandshakeResult {
        val pair = inMemoryDuplex()
        pair.server.inbound.send(bytes)
        return serverHandshake().authenticate(pair.server)
    }

    private suspend fun kotlinx.coroutines.CoroutineScope.bothPeersClaimClient(): BluetoothHandshakeResult {
        val pair = inMemoryDuplex()
        val other = async { runCatching { clientHandshake(localDeviceId = SERVER_ID, peerDeviceId = CLIENT_ID).authenticate(pair.server) } }
        try {
            return clientHandshake().authenticate(pair.client)
        } finally {
            other.cancelAndJoin()
        }
    }

    /**
     * A server whose nonce source repeats sees the identical transcript twice. The
     * recorded client messages verify a second time, so only the replay guard can refuse.
     */
    private suspend fun kotlinx.coroutines.CoroutineScope.replayCapturedHandshake(): BluetoothHandshakeResult {
        val guard = BluetoothReplayGuard()
        val first = inMemoryDuplex()
        val recorded = mutableListOf<ByteArray>()
        val recorder = object : BluetoothHandshakeChannel {
            override suspend fun readMessage(): ByteArray = first.client.readMessage()
            override suspend fun writeMessage(bytes: ByteArray) {
                recorded += bytes
                first.client.writeMessage(bytes)
            }
        }
        val firstServer = async { serverHandshake(nonceSeed = 32, replayGuard = guard).authenticate(first.server) }
        clientHandshake(nonceSeed = 31).authenticate(recorder)
        firstServer.await()
        assertEquals(2, recorded.size)

        val second = inMemoryDuplex()
        val replayer = async {
            // Replays the captured client hello and finish without reading the server hello.
            second.client.writeMessage(recorded[0])
            second.client.readMessage()
            second.client.writeMessage(recorded[1])
        }
        try {
            return serverHandshake(nonceSeed = 32, replayGuard = guard).authenticate(second.server)
        } finally {
            replayer.cancelAndJoin()
        }
    }

    private suspend fun assertFailsWithCode(code: String, block: suspend () -> Any?) {
        val error = assertFailsWith<BluetoothHandshakeException> { block() }
        assertEquals(code, error.failure.code)
        assertEquals(code, error.message)
    }

    private fun clientHandshake(
        localDeviceId: String = CLIENT_ID,
        peerDeviceId: String = SERVER_ID,
        peerSigningKey: ByteArray = SERVER_KEY,
        nonceSeed: Int = 31,
    ) = BluetoothHandshake(
        localDeviceId = localDeviceId,
        peerDeviceId = peerDeviceId,
        localSigningKey = CLIENT_KEY,
        peerSigningKey = peerSigningKey,
        role = BluetoothRole.CLIENT,
        signer = HmacSigner,
        secureRandom = FixedRandom(nonceSeed),
    )

    private fun serverHandshake(
        peerDeviceId: String = CLIENT_ID,
        peerSigningKey: ByteArray = CLIENT_KEY,
        nonceSeed: Int = 32,
        replayGuard: BluetoothReplayGuard = BluetoothReplayGuard(),
    ) = BluetoothHandshake(
        localDeviceId = SERVER_ID,
        peerDeviceId = peerDeviceId,
        localSigningKey = SERVER_KEY,
        peerSigningKey = peerSigningKey,
        role = BluetoothRole.SERVER,
        replayGuard = replayGuard,
        signer = HmacSigner,
        secureRandom = FixedRandom(nonceSeed),
    )

    private class Duplex(val client: ChannelEnd, val server: ChannelEnd)

    private class ChannelEnd : BluetoothHandshakeChannel {
        val inbound = Channel<ByteArray>(Channel.UNLIMITED)
        lateinit var peer: ChannelEnd
        override suspend fun readMessage(): ByteArray = inbound.receive()
        override suspend fun writeMessage(bytes: ByteArray) {
            peer.inbound.send(bytes.copyOf())
        }
    }

    private fun inMemoryDuplex(): Duplex {
        val client = ChannelEnd()
        val server = ChannelEnd()
        client.peer = server
        server.peer = client
        return Duplex(client, server)
    }

    /** Symmetric stand-in for Ed25519: "public" and "secret" are the same HMAC key. */
    private object HmacSigner : BluetoothHandshakeSigner {
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

    private companion object {
        const val CLIENT_ID = "dev-00000000-0000-0000-0000-000000000001"
        const val SERVER_ID = "dev-00000000-0000-0000-0000-000000000002"
        val CLIENT_KEY = bytes(11)
        val SERVER_KEY = bytes(22)

        fun bytes(seed: Int) = ByteArray(32) { (seed + it).toByte() }
    }
}
