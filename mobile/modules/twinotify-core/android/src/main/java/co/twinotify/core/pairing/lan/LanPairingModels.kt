package co.twinotify.core.pairing.lan

import java.security.MessageDigest
import java.text.Normalizer
import java.util.UUID

private const val LAN_PAIRING_VERSION = 1
private const val MAX_LIFETIME_MILLIS = 5 * 60 * 1000L
private const val APPLICATION_KEY_BYTES = 32
private const val TLS_PIN_BYTES = 32
private const val SESSION_TOKEN_BYTES = 32
private const val NONCE_BYTES = 32
private const val MAX_DISPLAY_NAME_CODE_POINTS = 128

/** An immutable byte value. Its accessor returns a fresh copy and its text form is redacted. */
class LanPairingBytes(value: ByteArray) {
    private val stored = value.copyOf()

    fun copy(): ByteArray = stored.copyOf()

    override fun equals(other: Any?): Boolean =
        other is LanPairingBytes && MessageDigest.isEqual(stored, other.stored)

    override fun hashCode(): Int = 0

    override fun toString(): String = "<redacted:${stored.size} bytes>"
}

data class LanPairingQr(
    val version: Int,
    val sessionId: String,
    val createdAtHintMillis: Long,
    val lifetimeMillis: Long,
    val deviceId: String,
    val displayName: String,
    val encryptionPublicKey: LanPairingBytes,
    val signingPublicKey: LanPairingBytes,
    val tlsSpkiSha256: LanPairingBytes,
    val sessionToken: LanPairingBytes,
) {
    init {
        require(version == LAN_PAIRING_VERSION) { "unsupported LAN pairing QR version" }
        validateSessionId(sessionId)
        require(createdAtHintMillis >= 0) { "LAN pairing creation hint must be non-negative" }
        validateLifetime(lifetimeMillis)
        validateDeviceId(deviceId)
        validateDisplayName(displayName)
        require(encryptionPublicKey.copy().size == APPLICATION_KEY_BYTES) { "invalid LAN encryption public key" }
        require(signingPublicKey.copy().size == APPLICATION_KEY_BYTES) { "invalid LAN signing public key" }
        require(tlsSpkiSha256.copy().size == TLS_PIN_BYTES) { "invalid LAN TLS pin" }
        require(sessionToken.copy().size == SESSION_TOKEN_BYTES) { "invalid LAN session token" }
    }
}

data class LanPairingHello(
    val deviceId: String,
    val displayName: String,
    val encryptionPublicKey: LanPairingBytes,
    val signingPublicKey: LanPairingBytes,
    val tlsSpkiSha256: LanPairingBytes,
    val nonce: LanPairingBytes,
) {
    init {
        validateDeviceId(deviceId)
        require(displayName == normalizeLanDisplayName(displayName)) { "invalid LAN pairing display name" }
        require(encryptionPublicKey.copy().size == APPLICATION_KEY_BYTES) { "invalid LAN encryption public key" }
        require(signingPublicKey.copy().size == APPLICATION_KEY_BYTES) { "invalid LAN signing public key" }
        require(tlsSpkiSha256.copy().size == TLS_PIN_BYTES) { "invalid LAN TLS pin" }
        require(nonce.copy().size == NONCE_BYTES) { "invalid LAN pairing nonce" }
    }
}

data class LanPairingTranscript(
    val sessionId: String,
    val lifetimeMillis: Long,
    val negotiatedVersion: Int,
    val first: LanPairingHello,
    val second: LanPairingHello,
) {
    init {
        validateSessionId(sessionId)
        validateLifetime(lifetimeMillis)
        require(negotiatedVersion == LAN_PAIRING_VERSION) { "unsupported LAN pairing transcript version" }
        require(first.deviceId != second.deviceId) { "LAN pairing participants must differ" }
    }
}

internal fun validateSessionId(value: String) {
    val parsed = try {
        UUID.fromString(value)
    } catch (_: IllegalArgumentException) {
        throw IllegalArgumentException("invalid LAN pairing session ID")
    }
    require(parsed.toString() == value) { "invalid LAN pairing session ID" }
}

internal fun validateDeviceId(value: String) {
    require(value.startsWith("dev-")) { "invalid LAN pairing device ID" }
    val rawUuid = value.removePrefix("dev-")
    val parsed = try {
        UUID.fromString(rawUuid)
    } catch (_: IllegalArgumentException) {
        throw IllegalArgumentException("invalid LAN pairing device ID")
    }
    require(parsed.toString() == rawUuid) { "invalid LAN pairing device ID" }
}

internal fun validateLifetime(value: Long) {
    require(value in 1..MAX_LIFETIME_MILLIS) { "invalid LAN pairing lifetime" }
}

private fun validateDisplayName(value: String) {
    require(value == normalizeLanDisplayName(value)) {
        "invalid LAN pairing display name"
    }
}

internal fun normalizeLanDisplayName(value: String): String {
    val normalized = Normalizer.normalize(value.trim(), Normalizer.Form.NFC)
    require(normalized.isNotBlank() && normalized.codePointCount(0, normalized.length) <= MAX_DISPLAY_NAME_CODE_POINTS) {
        "invalid LAN pairing display name"
    }
    require(normalized.encodeToByteArray().size <= 256 && normalized.none { Character.isISOControl(it) }) {
        "invalid LAN pairing display name"
    }
    return normalized
}
