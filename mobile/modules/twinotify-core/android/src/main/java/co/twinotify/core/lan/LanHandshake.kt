package co.twinotify.core.lan

import co.twinotify.core.pairing.lan.LanPairingCrypto
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.security.MessageDigest
import java.security.SecureRandom

enum class LanConnectionRole(val wire: Int) {
    INITIATOR(1),
    ACCEPTOR(2);

    fun opposite(): LanConnectionRole = if (this == INITIATOR) ACCEPTOR else INITIATOR

    companion object {
        fun fromWire(value: Int): LanConnectionRole = entries.firstOrNull { it.wire == value }
            ?: fail(LanHandshakeFailure.INVALID_HELLO)
    }
}

enum class LanHandshakeFailure(val code: String) {
    INVALID_HELLO("lan_handshake_invalid_hello"),
    IDENTITY_MISMATCH("lan_handshake_identity_mismatch"),
    SIGNATURE_INVALID("lan_handshake_signature_invalid"),
    REPLAYED_NONCE("lan_handshake_replayed_nonce"),
    ROLE_MISMATCH("lan_handshake_role_mismatch"),
    PROTOCOL_DOWNGRADE("lan_handshake_protocol_downgrade"),
    SESSION_MISMATCH("lan_handshake_session_mismatch"),
}

class LanHandshakeException(val failure: LanHandshakeFailure) : Exception(failure.code)

/**
 * Both members are abstract on purpose. A SAM-converted signer would silently
 * inherit a `verify` that rejects every peer signature, turning a valid
 * configuration into an unexplained authentication failure.
 */
interface LanHandshakeSigner {
    fun sign(message: ByteArray, secretKey: ByteArray): ByteArray

    fun verify(message: ByteArray, signature: ByteArray, publicKey: ByteArray): Boolean
}

internal object Ed25519LanHandshakeSigner : LanHandshakeSigner {
    override fun sign(message: ByteArray, secretKey: ByteArray): ByteArray =
        LanPairingCrypto.signTranscript(message, secretKey)

    override fun verify(message: ByteArray, signature: ByteArray, publicKey: ByteArray): Boolean =
        LanPairingCrypto.verifyTranscript(message, signature, publicKey)
}

data class LanHandshakeContext(
    val initiatorDeviceId: String,
    val acceptorDeviceId: String,
    val initiatorNonce: ByteArray,
    val acceptorNonce: ByteArray,
    val localRole: LanConnectionRole,
    val peerRole: LanConnectionRole,
    val protocolVersion: Int,
    val tlsSessionContext: ByteArray,
    val sessionId: ByteArray,
) {
    init {
        require(initiatorDeviceId != acceptorDeviceId)
        require(initiatorNonce.size == NONCE_BYTES && acceptorNonce.size == NONCE_BYTES)
        require(tlsSessionContext.size == CONTEXT_BYTES && sessionId.size == SESSION_ID_BYTES)
        require(protocolVersion > 0)
    }

    internal fun transcript(): ByteArray = ByteArrayOutputStream().use { bytes ->
        DataOutputStream(bytes).use { output ->
            output.write(DOMAIN)
            output.writeBounded(initiatorDeviceId.encodeToByteArray(), MAX_DEVICE_ID_BYTES)
            output.writeBounded(acceptorDeviceId.encodeToByteArray(), MAX_DEVICE_ID_BYTES)
            output.writeBounded(initiatorNonce, NONCE_BYTES)
            output.writeBounded(acceptorNonce, NONCE_BYTES)
            output.writeByte(localRole.wire)
            output.writeByte(peerRole.wire)
            output.writeInt(protocolVersion)
            output.writeBounded(tlsSessionContext, CONTEXT_BYTES)
            output.writeBounded(sessionId, SESSION_ID_BYTES)
        }
        bytes.toByteArray()
    }
}

class LanReplayGuard {
    private val entries = LinkedHashMap<String, Unit>(MAX_ENTRIES, 0.75f, false)

    val size: Int
        @Synchronized get() = entries.size

    @Synchronized
    fun record(peerDeviceId: String, peerNonce: ByteArray, sessionId: ByteArray): Boolean {
        val key = MessageDigest.getInstance("SHA-256").digest(
            REPLAY_DOMAIN + peerDeviceId.encodeToByteArray() + peerNonce + sessionId,
        ).joinToString("") { "%02x".format(it) }
        if (entries.containsKey(key)) return false
        while (entries.size >= MAX_ENTRIES) entries.remove(entries.keys.first())
        entries[key] = Unit
        return true
    }

    companion object { const val MAX_ENTRIES = 256 }
}

data class LanHandshake(
    val localDeviceId: String,
    val peerDeviceId: String,
    private val localSigningKey: ByteArray,
    val peerSigningKey: ByteArray,
    val localRole: LanConnectionRole,
    val protocolFloor: Int,
    val replayGuard: LanReplayGuard = LanReplayGuard(),
    private val signer: LanHandshakeSigner = Ed25519LanHandshakeSigner,
) {
    init {
        require(localDeviceId != peerDeviceId)
        require(localSigningKey.isNotEmpty() && peerSigningKey.isNotEmpty())
        require(protocolFloor > 0)
    }

    fun createHello(context: LanHandshakeContext): ByteArray {
        val transcript = context.transcript()
        val signed = signedMessage(transcript, localRole)
        return HelloCodec.encode(
            SignedHello(
                signerDeviceId = localDeviceId,
                signerRole = localRole,
                context = context,
                signature = signer.sign(signed, localSigningKey),
            ),
        )
    }

    fun verifyPeer(encoded: ByteArray, expected: LanHandshakeContext) {
        val hello = HelloCodec.decode(encoded)
        if (hello.signerDeviceId != peerDeviceId) fail(LanHandshakeFailure.IDENTITY_MISMATCH)
        if (hello.signerRole != localRole.opposite() || hello.context.localRole != LanConnectionRole.INITIATOR ||
            hello.context.peerRole != LanConnectionRole.ACCEPTOR
        ) fail(LanHandshakeFailure.ROLE_MISMATCH)
        if (hello.context.protocolVersion < protocolFloor || expected.protocolVersion < protocolFloor ||
            hello.context.protocolVersion != expected.protocolVersion
        ) fail(LanHandshakeFailure.PROTOCOL_DOWNGRADE)
        if (!hello.context.sameTranscript(expected)) fail(LanHandshakeFailure.SESSION_MISMATCH)
        val signed = signedMessage(hello.context.transcript(), hello.signerRole)
        if (!signer.verify(signed, hello.signature, peerSigningKey)) fail(LanHandshakeFailure.SIGNATURE_INVALID)
        val peerNonce = if (hello.signerRole == LanConnectionRole.INITIATOR) {
            hello.context.initiatorNonce
        } else {
            hello.context.acceptorNonce
        }
        if (!replayGuard.record(peerDeviceId, peerNonce, hello.context.sessionId)) {
            fail(LanHandshakeFailure.REPLAYED_NONCE)
        }
    }
}

/**
 * `sessionId` is compared by value with a constant-time digest comparison. A
 * generated `equals` would compare the array by reference, so two records of the
 * same authenticated session would not match.
 */
data class LanAuthenticatedSession(
    val peerDeviceId: String,
    val initiatorDeviceId: String,
    val sessionId: ByteArray,
) {
    override fun equals(other: Any?): Boolean = other is LanAuthenticatedSession &&
        peerDeviceId == other.peerDeviceId && initiatorDeviceId == other.initiatorDeviceId &&
        MessageDigest.isEqual(sessionId, other.sessionId)

    override fun hashCode(): Int = 31 * peerDeviceId.hashCode() + initiatorDeviceId.hashCode()

    override fun toString(): String =
        "LanAuthenticatedSession(peer=$peerDeviceId, initiator=$initiatorDeviceId, " +
            "sessionId=<redacted:${sessionId.size} bytes>)"
}

object LanConnectionArbiter {
    fun prefer(candidate: LanAuthenticatedSession, current: LanAuthenticatedSession): Boolean {
        val preferredInitiator = minOf(candidate.peerDeviceId, current.peerDeviceId,
            candidate.initiatorDeviceId, current.initiatorDeviceId)
        val candidatePreferred = candidate.initiatorDeviceId == preferredInitiator
        val currentPreferred = current.initiatorDeviceId == preferredInitiator
        if (candidatePreferred != currentPreferred) return candidatePreferred
        return compareUnsigned(candidate.sessionId, current.sessionId) < 0
    }

    private fun compareUnsigned(first: ByteArray, second: ByteArray): Int {
        for (index in 0 until minOf(first.size, second.size)) {
            val compared = (first[index].toInt() and 0xff).compareTo(second[index].toInt() and 0xff)
            if (compared != 0) return compared
        }
        return first.size.compareTo(second.size)
    }
}

interface LanSocketHandshake {
    suspend fun authenticate(socket: LanTlsSocket): LanAuthenticatedSession
}

class SignedLanSocketHandshake(
    private val handshake: LanHandshake,
    private val protocolVersion: Int = LanFrame.VERSION,
    private val secureRandom: SecureRandom = SecureRandom(),
) : LanSocketHandshake {
    override suspend fun authenticate(socket: LanTlsSocket): LanAuthenticatedSession {
        if (protocolVersion < handshake.protocolFloor) fail(LanHandshakeFailure.PROTOCOL_DOWNGRADE)
        val localNonce = ByteArray(NONCE_BYTES).also(secureRandom::nextBytes)
        val peerNonce: ByteArray
        if (handshake.localRole == LanConnectionRole.INITIATOR) {
            socket.writeHello(LanFrame.Hello(localNonce))
            peerNonce = (socket.readHello() as? LanFrame.HelloAck)?.data
                ?: fail(LanHandshakeFailure.INVALID_HELLO)
        } else {
            peerNonce = (socket.readHello() as? LanFrame.Hello)?.data
                ?: fail(LanHandshakeFailure.INVALID_HELLO)
            socket.writeHello(LanFrame.HelloAck(localNonce))
        }
        if (peerNonce.size != NONCE_BYTES) fail(LanHandshakeFailure.INVALID_HELLO)
        val initiatorNonce = if (handshake.localRole == LanConnectionRole.INITIATOR) localNonce else peerNonce
        val acceptorNonce = if (handshake.localRole == LanConnectionRole.ACCEPTOR) localNonce else peerNonce
        val initiator = if (handshake.localRole == LanConnectionRole.INITIATOR) handshake.localDeviceId else handshake.peerDeviceId
        val acceptor = if (handshake.localRole == LanConnectionRole.ACCEPTOR) handshake.localDeviceId else handshake.peerDeviceId
        val tlsContext = socket.tlsSessionContext()
        if (tlsContext.size != CONTEXT_BYTES) fail(LanHandshakeFailure.SESSION_MISMATCH)
        val sessionId = MessageDigest.getInstance("SHA-256").digest(
            SESSION_DOMAIN + tlsContext + initiatorNonce + acceptorNonce,
        )
        val context = LanHandshakeContext(
            initiator,
            acceptor,
            initiatorNonce,
            acceptorNonce,
            LanConnectionRole.INITIATOR,
            LanConnectionRole.ACCEPTOR,
            protocolVersion,
            tlsContext,
            sessionId,
        )
        if (handshake.localRole == LanConnectionRole.INITIATOR) {
            socket.writeHello(LanFrame.Hello(handshake.createHello(context)))
            val peer = (socket.readHello() as? LanFrame.HelloAck)?.data
                ?: fail(LanHandshakeFailure.INVALID_HELLO)
            handshake.verifyPeer(peer, context)
        } else {
            val peer = (socket.readHello() as? LanFrame.Hello)?.data
                ?: fail(LanHandshakeFailure.INVALID_HELLO)
            handshake.verifyPeer(peer, context)
            socket.writeHello(LanFrame.HelloAck(handshake.createHello(context)))
        }
        return LanAuthenticatedSession(handshake.peerDeviceId, initiator, sessionId.copyOf())
    }
}

private data class SignedHello(
    val signerDeviceId: String,
    val signerRole: LanConnectionRole,
    val context: LanHandshakeContext,
    val signature: ByteArray,
)

private object HelloCodec {
    private const val MAX_HELLO_BYTES = 2048

    fun encode(hello: SignedHello): ByteArray = ByteArrayOutputStream().use { bytes ->
        DataOutputStream(bytes).use { output ->
            output.writeInt(HELLO_VERSION)
            output.writeBounded(hello.signerDeviceId.encodeToByteArray(), MAX_DEVICE_ID_BYTES)
            output.writeByte(hello.signerRole.wire)
            output.writeBounded(hello.context.transcript(), 1024)
            output.writeBounded(hello.signature, 128)
        }
        bytes.toByteArray().also { if (it.size > MAX_HELLO_BYTES) fail(LanHandshakeFailure.INVALID_HELLO) }
    }

    fun decode(encoded: ByteArray): SignedHello = try {
        if (encoded.isEmpty() || encoded.size > MAX_HELLO_BYTES) fail(LanHandshakeFailure.INVALID_HELLO)
        DataInputStream(ByteArrayInputStream(encoded)).use { input ->
            if (input.readInt() != HELLO_VERSION) fail(LanHandshakeFailure.INVALID_HELLO)
            val signerDeviceId = input.readBounded(MAX_DEVICE_ID_BYTES).decodeToString()
            val signerRole = LanConnectionRole.fromWire(input.readUnsignedByte())
            val transcript = decodeTranscript(input.readBounded(1024))
            val signature = input.readBounded(128)
            if (input.available() != 0 || signature.isEmpty()) fail(LanHandshakeFailure.INVALID_HELLO)
            SignedHello(signerDeviceId, signerRole, transcript, signature)
        }
    } catch (error: LanHandshakeException) {
        throw error
    } catch (_: Exception) {
        fail(LanHandshakeFailure.INVALID_HELLO)
    }

    private fun decodeTranscript(encoded: ByteArray): LanHandshakeContext =
        DataInputStream(ByteArrayInputStream(encoded)).use { input ->
            val domain = ByteArray(DOMAIN.size).also(input::readFully)
            if (!MessageDigest.isEqual(domain, DOMAIN)) fail(LanHandshakeFailure.INVALID_HELLO)
            val context = LanHandshakeContext(
                initiatorDeviceId = input.readBounded(MAX_DEVICE_ID_BYTES).decodeToString(),
                acceptorDeviceId = input.readBounded(MAX_DEVICE_ID_BYTES).decodeToString(),
                initiatorNonce = input.readBounded(NONCE_BYTES),
                acceptorNonce = input.readBounded(NONCE_BYTES),
                localRole = LanConnectionRole.fromWire(input.readUnsignedByte()),
                peerRole = LanConnectionRole.fromWire(input.readUnsignedByte()),
                protocolVersion = input.readInt(),
                tlsSessionContext = input.readBounded(CONTEXT_BYTES),
                sessionId = input.readBounded(SESSION_ID_BYTES),
            )
            if (input.available() != 0) fail(LanHandshakeFailure.INVALID_HELLO)
            context
        }
}

private fun LanHandshakeContext.sameTranscript(other: LanHandshakeContext): Boolean =
    initiatorDeviceId == other.initiatorDeviceId && acceptorDeviceId == other.acceptorDeviceId &&
        MessageDigest.isEqual(initiatorNonce, other.initiatorNonce) &&
        MessageDigest.isEqual(acceptorNonce, other.acceptorNonce) &&
        localRole == other.localRole && peerRole == other.peerRole &&
        protocolVersion == other.protocolVersion &&
        MessageDigest.isEqual(tlsSessionContext, other.tlsSessionContext) &&
        MessageDigest.isEqual(sessionId, other.sessionId)

private fun signedMessage(transcript: ByteArray, role: LanConnectionRole): ByteArray =
    SIGNATURE_DOMAIN + byteArrayOf(role.wire.toByte()) + transcript

private fun DataOutputStream.writeBounded(value: ByteArray, max: Int) {
    if (value.isEmpty() || value.size > max) fail(LanHandshakeFailure.INVALID_HELLO)
    writeShort(value.size)
    write(value)
}

private fun DataInputStream.readBounded(max: Int): ByteArray {
    val size = readUnsignedShort()
    if (size == 0 || size > max || size > available()) fail(LanHandshakeFailure.INVALID_HELLO)
    return ByteArray(size).also(::readFully)
}

private fun fail(failure: LanHandshakeFailure): Nothing = throw LanHandshakeException(failure)

private const val HELLO_VERSION = 1
private const val NONCE_BYTES = 32
private const val CONTEXT_BYTES = 32
private const val SESSION_ID_BYTES = 32
private const val MAX_DEVICE_ID_BYTES = 128
private val DOMAIN = "twinotify-lan-handshake-transcript-v1".encodeToByteArray()
private val SIGNATURE_DOMAIN = "twinotify-lan-handshake-signature-v1".encodeToByteArray()
private val SESSION_DOMAIN = "twinotify-lan-handshake-session-v1".encodeToByteArray()
private val REPLAY_DOMAIN = "twinotify-lan-handshake-replay-v1".encodeToByteArray()
