package co.twinotify.core.bluetooth

import co.twinotify.core.pairing.lan.LanPairingCrypto
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.security.MessageDigest
import java.security.SecureRandom

/** The socket role. The RFCOMM client speaks first; the roles are bound into the transcript by position. */
enum class BluetoothRole {
    CLIENT,
    SERVER;

    fun opposite(): BluetoothRole = if (this == CLIENT) SERVER else CLIENT
}

enum class BluetoothHandshakeFailure(val code: String) {
    INVALID("bluetooth_handshake_invalid"),
    IDENTITY_MISMATCH("bluetooth_identity_mismatch"),
    SIGNATURE_INVALID("bluetooth_signature_invalid"),
    REPLAYED_NONCE("bluetooth_replayed_nonce"),
    ROLE_MISMATCH("bluetooth_role_mismatch"),
    PROTOCOL_DOWNGRADE("bluetooth_protocol_downgrade"),
}

class BluetoothHandshakeException(val failure: BluetoothHandshakeFailure) : Exception(failure.code)

/**
 * Both members are abstract on purpose. A SAM-converted signer would silently
 * inherit a `verify` that rejects every peer signature.
 */
interface BluetoothHandshakeSigner {
    fun sign(message: ByteArray, secretKey: ByteArray): ByteArray

    fun verify(message: ByteArray, signature: ByteArray, publicKey: ByteArray): Boolean
}

internal object Ed25519BluetoothHandshakeSigner : BluetoothHandshakeSigner {
    override fun sign(message: ByteArray, secretKey: ByteArray): ByteArray =
        LanPairingCrypto.signTranscript(message, secretKey)

    override fun verify(message: ByteArray, signature: ByteArray, publicKey: ByteArray): Boolean =
        LanPairingCrypto.verifyTranscript(message, signature, publicKey)
}

/** Bounded, ordered message exchange the handshake runs over. The socket wire implements it. */
interface BluetoothHandshakeChannel {
    suspend fun readMessage(): ByteArray

    suspend fun writeMessage(bytes: ByteArray)
}

/** The three handshake messages. Nonces and signatures are copied in and out and never printed. */
sealed interface BluetoothHello {
    class ClientHello(val deviceId: String, nonce: ByteArray, val protocolVersion: Int) : BluetoothHello {
        private val storedNonce = nonce.copyOf()
        val nonce: ByteArray get() = storedNonce.copyOf()

        init {
            require(deviceId.isNotEmpty() && deviceId.encodeToByteArray().size <= MAX_DEVICE_ID_BYTES)
            require(storedNonce.size == NONCE_BYTES)
        }

        override fun equals(other: Any?) = other is ClientHello && deviceId == other.deviceId &&
            protocolVersion == other.protocolVersion && MessageDigest.isEqual(storedNonce, other.storedNonce)

        override fun hashCode() = 31 * deviceId.hashCode() + protocolVersion
        override fun toString() = "ClientHello(deviceId=$deviceId, nonce=<redacted>, protocolVersion=$protocolVersion)"
    }

    class ServerHello(val deviceId: String, nonce: ByteArray, signature: ByteArray) : BluetoothHello {
        private val storedNonce = nonce.copyOf()
        private val storedSignature = signature.copyOf()
        val nonce: ByteArray get() = storedNonce.copyOf()
        val signature: ByteArray get() = storedSignature.copyOf()

        init {
            require(deviceId.isNotEmpty() && deviceId.encodeToByteArray().size <= MAX_DEVICE_ID_BYTES)
            require(storedNonce.size == NONCE_BYTES)
            require(storedSignature.isNotEmpty() && storedSignature.size <= MAX_SIGNATURE_BYTES)
        }

        override fun equals(other: Any?) = other is ServerHello && deviceId == other.deviceId &&
            MessageDigest.isEqual(storedNonce, other.storedNonce) &&
            MessageDigest.isEqual(storedSignature, other.storedSignature)

        override fun hashCode() = 37 * deviceId.hashCode()
        override fun toString() = "ServerHello(deviceId=$deviceId, nonce=<redacted>, signature=<redacted>)"
    }

    class ClientFinish(signature: ByteArray) : BluetoothHello {
        private val storedSignature = signature.copyOf()
        val signature: ByteArray get() = storedSignature.copyOf()

        init {
            require(storedSignature.isNotEmpty() && storedSignature.size <= MAX_SIGNATURE_BYTES)
        }

        override fun equals(other: Any?) = other is ClientFinish && MessageDigest.isEqual(storedSignature, other.storedSignature)
        override fun hashCode() = 41
        override fun toString() = "ClientFinish(signature=<redacted>)"
    }
}

/** Fixed binary layout: one type byte, then length-prefixed or fixed-width fields, nothing trailing. */
internal object BluetoothHelloCodec {
    private const val TYPE_CLIENT_HELLO = 1
    private const val TYPE_SERVER_HELLO = 2
    private const val TYPE_CLIENT_FINISH = 3
    const val MAX_MESSAGE_BYTES = 2048

    fun encode(hello: BluetoothHello): ByteArray = ByteArrayOutputStream().use { bytes ->
        DataOutputStream(bytes).use { output ->
            when (hello) {
                is BluetoothHello.ClientHello -> {
                    output.writeByte(TYPE_CLIENT_HELLO)
                    output.writeBounded(hello.deviceId.encodeToByteArray(), MAX_DEVICE_ID_BYTES)
                    output.write(hello.nonce)
                    output.writeInt(hello.protocolVersion)
                }
                is BluetoothHello.ServerHello -> {
                    output.writeByte(TYPE_SERVER_HELLO)
                    output.writeBounded(hello.deviceId.encodeToByteArray(), MAX_DEVICE_ID_BYTES)
                    output.write(hello.nonce)
                    output.writeBounded(hello.signature, MAX_SIGNATURE_BYTES)
                }
                is BluetoothHello.ClientFinish -> {
                    output.writeByte(TYPE_CLIENT_FINISH)
                    output.writeBounded(hello.signature, MAX_SIGNATURE_BYTES)
                }
            }
        }
        bytes.toByteArray().also { if (it.size > MAX_MESSAGE_BYTES) fail(BluetoothHandshakeFailure.INVALID) }
    }

    fun decode(encoded: ByteArray): BluetoothHello = try {
        if (encoded.isEmpty() || encoded.size > MAX_MESSAGE_BYTES) fail(BluetoothHandshakeFailure.INVALID)
        DataInputStream(ByteArrayInputStream(encoded)).use { input ->
            val hello = when (input.readUnsignedByte()) {
                TYPE_CLIENT_HELLO -> BluetoothHello.ClientHello(
                    deviceId = input.readBounded(MAX_DEVICE_ID_BYTES).decodeToString(),
                    nonce = input.readFixed(NONCE_BYTES),
                    protocolVersion = input.readInt(),
                )
                TYPE_SERVER_HELLO -> BluetoothHello.ServerHello(
                    deviceId = input.readBounded(MAX_DEVICE_ID_BYTES).decodeToString(),
                    nonce = input.readFixed(NONCE_BYTES),
                    signature = input.readBounded(MAX_SIGNATURE_BYTES),
                )
                TYPE_CLIENT_FINISH -> BluetoothHello.ClientFinish(input.readBounded(MAX_SIGNATURE_BYTES))
                else -> fail(BluetoothHandshakeFailure.INVALID)
            }
            if (input.available() != 0) fail(BluetoothHandshakeFailure.INVALID)
            hello
        }
    } catch (error: BluetoothHandshakeException) {
        throw error
    } catch (_: Exception) {
        fail(BluetoothHandshakeFailure.INVALID)
    }

    private fun DataOutputStream.writeBounded(value: ByteArray, max: Int) {
        if (value.isEmpty() || value.size > max) fail(BluetoothHandshakeFailure.INVALID)
        writeShort(value.size)
        write(value)
    }

    private fun DataInputStream.readBounded(max: Int): ByteArray {
        val size = readUnsignedShort()
        if (size == 0 || size > max || size > available()) fail(BluetoothHandshakeFailure.INVALID)
        return ByteArray(size).also(::readFully)
    }

    private fun DataInputStream.readFixed(size: Int): ByteArray {
        if (size > available()) fail(BluetoothHandshakeFailure.INVALID)
        return ByteArray(size).also(::readFully)
    }
}

/** `sessionId` compares by value with a constant-time digest comparison. */
data class BluetoothHandshakeResult(val peerDeviceId: String, val sessionId: ByteArray) {
    override fun equals(other: Any?): Boolean = other is BluetoothHandshakeResult &&
        peerDeviceId == other.peerDeviceId && MessageDigest.isEqual(sessionId, other.sessionId)

    override fun hashCode(): Int = peerDeviceId.hashCode()

    override fun toString(): String =
        "BluetoothHandshakeResult(peerDeviceId=$peerDeviceId, sessionId=<redacted:${sessionId.size} bytes>)"
}

/** Last-used peer nonces per session, bounded so a long-lived process cannot grow it. */
class BluetoothReplayGuard {
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

/**
 * Three-message mutual Ed25519 challenge that binds one RFCOMM socket to the stored
 * Twinotify peer. Client hello, server hello with signature over `"server" || transcript`,
 * client finish with signature over `"client" || transcript`. Every check is against the
 * exact stored peer identity and key; the session identifier is SHA-256 of the transcript.
 */
class BluetoothHandshake(
    val localDeviceId: String,
    val peerDeviceId: String,
    private val localSigningKey: ByteArray,
    private val peerSigningKey: ByteArray,
    val role: BluetoothRole,
    val replayGuard: BluetoothReplayGuard = BluetoothReplayGuard(),
    private val signer: BluetoothHandshakeSigner = Ed25519BluetoothHandshakeSigner,
    private val secureRandom: SecureRandom = SecureRandom(),
) {
    init {
        require(localDeviceId != peerDeviceId) { "bluetooth_identity_collision" }
        require(localDeviceId.isNotEmpty() && localDeviceId.encodeToByteArray().size <= MAX_DEVICE_ID_BYTES)
        require(peerDeviceId.isNotEmpty() && peerDeviceId.encodeToByteArray().size <= MAX_DEVICE_ID_BYTES)
        require(localSigningKey.isNotEmpty() && peerSigningKey.isNotEmpty())
    }

    suspend fun authenticate(channel: BluetoothHandshakeChannel): BluetoothHandshakeResult = when (role) {
        BluetoothRole.CLIENT -> runClient(channel)
        BluetoothRole.SERVER -> runServer(channel)
    }

    private suspend fun runClient(channel: BluetoothHandshakeChannel): BluetoothHandshakeResult {
        val clientNonce = freshNonce()
        channel.writeMessage(
            BluetoothHelloCodec.encode(
                BluetoothHello.ClientHello(localDeviceId, clientNonce, BluetoothConstants.PROTOCOL_VERSION),
            ),
        )
        val serverHello = expect<BluetoothHello.ServerHello>(channel.readMessage())
        val transcript = transcript(localDeviceId, peerDeviceId, clientNonce, serverHello.nonce, BluetoothConstants.PROTOCOL_VERSION)
        if (!signer.verify(SERVER_DOMAIN + transcript, serverHello.signature, peerSigningKey)) {
            fail(BluetoothHandshakeFailure.SIGNATURE_INVALID)
        }
        val sessionId = sessionId(transcript)
        if (!replayGuard.record(peerDeviceId, serverHello.nonce, sessionId)) fail(BluetoothHandshakeFailure.REPLAYED_NONCE)
        channel.writeMessage(
            BluetoothHelloCodec.encode(BluetoothHello.ClientFinish(signer.sign(CLIENT_DOMAIN + transcript, localSigningKey))),
        )
        return BluetoothHandshakeResult(peerDeviceId, sessionId)
    }

    private suspend fun runServer(channel: BluetoothHandshakeChannel): BluetoothHandshakeResult {
        val clientHello = expect<BluetoothHello.ClientHello>(channel.readMessage())
        if (clientHello.protocolVersion != BluetoothConstants.PROTOCOL_VERSION) fail(BluetoothHandshakeFailure.PROTOCOL_DOWNGRADE)
        val serverNonce = freshNonce()
        val transcript = transcript(peerDeviceId, localDeviceId, clientHello.nonce, serverNonce, BluetoothConstants.PROTOCOL_VERSION)
        channel.writeMessage(
            BluetoothHelloCodec.encode(
                BluetoothHello.ServerHello(localDeviceId, serverNonce, signer.sign(SERVER_DOMAIN + transcript, localSigningKey)),
            ),
        )
        val finish = expect<BluetoothHello.ClientFinish>(channel.readMessage())
        if (!signer.verify(CLIENT_DOMAIN + transcript, finish.signature, peerSigningKey)) {
            fail(BluetoothHandshakeFailure.SIGNATURE_INVALID)
        }
        val sessionId = sessionId(transcript)
        if (!replayGuard.record(peerDeviceId, clientHello.nonce, sessionId)) fail(BluetoothHandshakeFailure.REPLAYED_NONCE)
        return BluetoothHandshakeResult(peerDeviceId, sessionId)
    }

    /** Identity before role: a message that names the wrong peer is refused for that reason first. */
    private inline fun <reified T : BluetoothHello> expect(encoded: ByteArray): T {
        val hello = BluetoothHelloCodec.decode(encoded)
        val claimed = when (hello) {
            is BluetoothHello.ClientHello -> hello.deviceId
            is BluetoothHello.ServerHello -> hello.deviceId
            is BluetoothHello.ClientFinish -> null
        }
        if (claimed != null && !MessageDigest.isEqual(claimed.encodeToByteArray(), peerDeviceId.encodeToByteArray())) {
            fail(BluetoothHandshakeFailure.IDENTITY_MISMATCH)
        }
        return hello as? T ?: fail(BluetoothHandshakeFailure.ROLE_MISMATCH)
    }

    private fun freshNonce(): ByteArray = ByteArray(NONCE_BYTES).also(secureRandom::nextBytes)

    companion object {
        /** The plan's canonical transcript, byte for byte. Both roles build it from the same inputs. */
        internal fun transcript(
            clientDeviceId: String,
            serverDeviceId: String,
            clientNonce: ByteArray,
            serverNonce: ByteArray,
            protocolVersion: Int,
        ): ByteArray = ByteArrayOutputStream().use { bytes ->
            DataOutputStream(bytes).use { output ->
                output.write(TRANSCRIPT_DOMAIN)
                output.writeLengthPrefixed(clientDeviceId.encodeToByteArray())
                output.writeLengthPrefixed(serverDeviceId.encodeToByteArray())
                output.write(clientNonce)
                output.write(serverNonce)
                output.writeInt(protocolVersion)
                output.writeLengthPrefixed(BluetoothConstants.ROUTE_LABEL.encodeToByteArray())
            }
            bytes.toByteArray()
        }

        private fun sessionId(transcript: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(transcript)

        private fun DataOutputStream.writeLengthPrefixed(value: ByteArray) {
            require(value.isNotEmpty() && value.size <= MAX_DEVICE_ID_BYTES)
            writeShort(value.size)
            write(value)
        }
    }
}

private fun fail(failure: BluetoothHandshakeFailure): Nothing = throw BluetoothHandshakeException(failure)

private const val NONCE_BYTES = 32
private const val MAX_DEVICE_ID_BYTES = 128
private const val MAX_SIGNATURE_BYTES = 128
private val TRANSCRIPT_DOMAIN = "twinotify-bluetooth-handshake-v1".encodeToByteArray()
private val SERVER_DOMAIN = "server".encodeToByteArray()
private val CLIENT_DOMAIN = "client".encodeToByteArray()
private val REPLAY_DOMAIN = "twinotify-bluetooth-handshake-replay-v1".encodeToByteArray()
