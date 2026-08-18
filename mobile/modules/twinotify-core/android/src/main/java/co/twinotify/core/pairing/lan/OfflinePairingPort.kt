package co.twinotify.core.pairing.lan

/**
 * Bounded, Android-free pairing transport. Implementations may bridge NSD and
 * TLS, but may not persist peer state: the coordinator is the sole commit owner.
 */
interface OfflinePairingPort {
    fun monotonicMillis(): Long
    fun advertise(sessionId: String)
    fun resolve(sessionId: String, expectedTlsSpkiSha256: ByteArray)
    fun send(frame: OfflinePairingFrame)
    fun close()
}

sealed class OfflinePairingFrame {
    abstract val sessionId: String

    class Hello(
        override val sessionId: String,
        val lifetimeMillis: Long,
        val hello: LanPairingHello,
    ) : OfflinePairingFrame() {
        init {
            validateSessionId(sessionId)
            validateLifetime(lifetimeMillis)
        }
    }

    class Signature(
        override val sessionId: String,
        signature: ByteArray,
    ) : OfflinePairingFrame() {
        private val storedSignature = signature.copyOf()
        init {
            validateSessionId(sessionId)
            require(storedSignature.size == 64) { "invalid LAN pairing signature" }
        }
        val signature: ByteArray get() = storedSignature.copyOf()
    }
}

enum class OfflinePairingRole { INITIATOR, JOINER }

enum class OfflinePairingState(val code: String) {
    IDLE("idle"),
    ADVERTISING("advertising"),
    RESOLVING("resolving"),
    TLS_AUTHENTICATED("tls_authenticated"),
    VERIFY_CODE("verify_code"),
    LOCAL_CONFIRMED("local_confirmed"),
    MUTUALLY_SIGNED("mutually_signed"),
    COMMITTED("committed"),
    COMPLETE("complete"),
}

enum class OfflinePairingError(val code: String) {
    EXPIRED("expired"),
    IDENTITY_MISMATCH("identity_mismatch"),
    INVALID_FRAME("invalid_frame"),
    COMMIT_FAILED("commit_failed"),
    CANCELLED("cancelled"),
}

/** A secret-free status intended for UI and logs. */
data class OfflinePairingStatus(
    val state: OfflinePairingState,
    val error: OfflinePairingError? = null,
    val sas: String? = null,
)

class OfflinePairingIdentity(
    val deviceId: String,
    val displayName: String,
    encryptionPublicKey: ByteArray,
    signingPublicKey: ByteArray,
    signingSecretKey: ByteArray,
    tlsSpkiSha256: ByteArray,
) {
    private val encryption = encryptionPublicKey.copyOf()
    private val signing = signingPublicKey.copyOf()
    private val signingSecret = signingSecretKey.copyOf()
    private val tlsPin = tlsSpkiSha256.copyOf()

    init {
        validateDeviceId(deviceId)
        require(displayName.isNotBlank()) { "invalid LAN pairing display name" }
        require(encryption.size == 32 && signing.size == 32 && signingSecret.size == 64 && tlsPin.size == 32) {
            "invalid LAN pairing identity"
        }
    }

    val encryptionPublicKey: ByteArray get() = encryption.copyOf()
    val signingPublicKey: ByteArray get() = signing.copyOf()
    internal val signingSecretKey: ByteArray get() = signingSecret.copyOf()
    val tlsSpkiSha256: ByteArray get() = tlsPin.copyOf()
}

class OfflinePairingExistingPeer(deviceId: String, encryptionPublicKey: ByteArray, signingPublicKey: ByteArray) {
    val deviceId = deviceId
    private val encryption = encryptionPublicKey.copyOf()
    private val signing = signingPublicKey.copyOf()

    init {
        validateDeviceId(deviceId)
        require(encryption.size == 32 && signing.size == 32) { "invalid existing peer identity" }
    }

    internal fun exactlyMatches(hello: LanPairingHello): Boolean =
        deviceId == hello.deviceId && encryption.contentEquals(hello.encryptionPublicKey.copy()) &&
            signing.contentEquals(hello.signingPublicKey.copy())
}

class OfflinePairingCommit(
    val peerDeviceId: String,
    peerEncryptionPublicKey: ByteArray,
    peerSigningPublicKey: ByteArray,
    peerTlsSpkiSha256: ByteArray,
    lanSecret: ByteArray,
    val protocolVersion: Int,
) {
    private val encryption = peerEncryptionPublicKey.copyOf()
    private val signing = peerSigningPublicKey.copyOf()
    private val tlsPin = peerTlsSpkiSha256.copyOf()
    private val secret = lanSecret.copyOf()

    init {
        validateDeviceId(peerDeviceId)
        require(encryption.size == 32 && signing.size == 32 && tlsPin.size == 32 && secret.size == 32 && protocolVersion == 1) {
            "invalid LAN pairing commit"
        }
    }

    val peerEncryptionPublicKey: ByteArray get() = encryption.copyOf()
    val peerSigningPublicKey: ByteArray get() = signing.copyOf()
    val peerTlsSpkiSha256: ByteArray get() = tlsPin.copyOf()
    val lanSecret: ByteArray get() = secret.copyOf()
}

interface OfflinePairingCommitter {
    fun existingPeer(): OfflinePairingExistingPeer?
    fun commit(value: OfflinePairingCommit): Boolean
}

interface OfflinePairingCrypto {
    fun canonicalTranscript(value: LanPairingTranscript): ByteArray
    fun shortAuthenticationString(transcript: ByteArray): String
    fun derivePairSecret(sessionToken: ByteArray, transcript: ByteArray): ByteArray
    fun signTranscript(transcript: ByteArray, secretKey: ByteArray): ByteArray
    fun verifyTranscript(transcript: ByteArray, signature: ByteArray, publicKey: ByteArray): Boolean
}

object ProductionOfflinePairingCrypto : OfflinePairingCrypto {
    override fun canonicalTranscript(value: LanPairingTranscript): ByteArray = LanPairingCodec.canonicalTranscript(value)
    override fun shortAuthenticationString(transcript: ByteArray): String = LanPairingCrypto.shortAuthenticationString(transcript)
    override fun derivePairSecret(sessionToken: ByteArray, transcript: ByteArray): ByteArray = LanPairingCrypto.derivePairSecret(sessionToken, transcript)
    override fun signTranscript(transcript: ByteArray, secretKey: ByteArray): ByteArray = LanPairingCrypto.signTranscript(transcript, secretKey)
    override fun verifyTranscript(transcript: ByteArray, signature: ByteArray, publicKey: ByteArray): Boolean =
        LanPairingCrypto.verifyTranscript(transcript, signature, publicKey)
}
