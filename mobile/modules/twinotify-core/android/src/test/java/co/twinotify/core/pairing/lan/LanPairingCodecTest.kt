package co.twinotify.core.pairing.lan

import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LanPairingCodecTest {
    @Test
    fun qrRoundTripUsesClosedWorldVersionOneSchema() {
        val encoded = LanPairingCodec.encodeQr(qr())

        val decoded = LanPairingCodec.decodeQr(encoded)

        assertEquals(qr(), decoded)
        assertFailsWith<IllegalArgumentException> { LanPairingCodec.decodeQr(encoded.replace("\"v\":1", "\"v\":2")) }
        assertFailsWith<IllegalArgumentException> { LanPairingCodec.decodeQr(encoded.replace("\"v\":1,", "")) }
        assertFailsWith<IllegalArgumentException> {
            LanPairingCodec.decodeQr(encoded.dropLast(1) + ",\"v\":1}")
        }
        val unknownField = assertFailsWith<IllegalArgumentException> {
            LanPairingCodec.decodeQr(encoded.dropLast(1) + ",\"unexpected\":\"x\"}")
        }
        assertEquals("invalid LAN pairing QR fields", unknownField.message)
    }

    @Test
    fun qrRejectsNonCanonicalIdentifiersInvalidLengthsAndLifetime() {
        val encoded = LanPairingCodec.encodeQr(qr())
        fun reject(field: String, value: String) = assertFailsWith<IllegalArgumentException> {
            LanPairingCodec.decodeQr(encoded.replace("\"$field\":${jsonValue(field, encoded)}", "\"$field\":$value"))
        }

        reject("sid", "\"9F633FF1-0BDD-4A95-BB9E-5D9E0EF8F6AF\"")
        reject("device_id", "\"device-a\"")
        reject("session_token", "\"${b64(31)}\"")
        reject("enc_pubkey", "\"${b64(31)}\"")
        reject("sign_pubkey", "\"${b64(31)}\"")
        reject("tls_spki_sha256", "\"${b64(31)}\"")
        reject("lifetime_ms", "0")
        reject("lifetime_ms", "300001")
    }

    @Test
    fun qrRejectsOversizedInputWithoutExposingSecrets() {
        val token = b64(32, 93)
        val oversized = "{\"v\":1,\"sid\":\"9f633ff1-0bdd-4a95-bb9e-5d9e0ef8f6af\",\"created_at_hint_ms\":1,\"lifetime_ms\":1,\"device_id\":\"dev-9f633ff1-0bdd-4a95-bb9e-5d9e0ef8f6af\",\"display_name\":\"${"x".repeat(4100)}\",\"enc_pubkey\":\"${b64(32)}\",\"sign_pubkey\":\"${b64(32)}\",\"tls_spki_sha256\":\"${b64(32)}\",\"session_token\":\"$token\"}"

        val error = assertFailsWith<IllegalArgumentException> { LanPairingCodec.decodeQr(oversized) }

        assertFalse(error.message.orEmpty().contains(token))
    }

    @Test
    fun transcriptIsLengthDelimitedDeterministicAndRoleIndependent() {
        val initiator = hello("dev-9f633ff1-0bdd-4a95-bb9e-5d9e0ef8f6af", 1)
        val joiner = hello("dev-a70446b3-a355-46cc-9e62-069a0bfe2e10", 2)
        val first = LanPairingTranscript(
            sessionId = "9f633ff1-0bdd-4a95-bb9e-5d9e0ef8f6af",
            lifetimeMillis = 300_000,
            negotiatedVersion = 1,
            first = initiator,
            second = joiner,
        )
        val reversed = first.copy(first = joiner, second = initiator)

        val canonical = LanPairingCodec.canonicalTranscript(first)

        assertContentEquals(canonical, LanPairingCodec.canonicalTranscript(first))
        assertContentEquals(canonical, LanPairingCodec.canonicalTranscript(reversed))
        assertTrue(canonical.size > 2 * 32)
        fun assertBound(mutated: LanPairingTranscript) {
            assertFalse(canonical.contentEquals(LanPairingCodec.canonicalTranscript(mutated)))
        }

        assertBound(first.copy(sessionId = "a70446b3-a355-46cc-9e62-069a0bfe2e10"))
        assertBound(first.copy(lifetimeMillis = 299_999))
        assertFailsWith<IllegalArgumentException> { first.copy(negotiatedVersion = 2) }
        assertBound(first.copy(first = initiator.copy(deviceId = "dev-b70446b3-a355-46cc-9e62-069a0bfe2e10")))
        assertBound(first.copy(second = joiner.copy(deviceId = "dev-c70446b3-a355-46cc-9e62-069a0bfe2e10")))
        assertBound(first.copy(first = initiator.copy(encryptionPublicKey = bytes(32, 17))))
        assertBound(first.copy(second = joiner.copy(encryptionPublicKey = bytes(32, 18))))
        assertBound(first.copy(first = initiator.copy(signingPublicKey = bytes(32, 19))))
        assertBound(first.copy(second = joiner.copy(signingPublicKey = bytes(32, 20))))
        assertBound(first.copy(first = initiator.copy(tlsSpkiSha256 = bytes(32, 21))))
        assertBound(first.copy(second = joiner.copy(tlsSpkiSha256 = bytes(32, 22))))
        assertBound(first.copy(first = initiator.copy(nonce = bytes(32, 23))))
        assertBound(first.copy(second = joiner.copy(nonce = bytes(32, 24))))
    }

    @Test
    fun byteFieldsAreDefensivelyCopiedAndRedacted() {
        val token = ByteArray(32) { 91 }
        val value = qr(sessionToken = bytes(token))
        token[0] = 0
        val extracted = value.sessionToken.copy()
        extracted[1] = 0

        assertEquals(91, value.sessionToken.copy()[0].toInt())
        assertEquals(91, value.sessionToken.copy()[1].toInt())
        assertFalse(value.toString().contains(Base64.getEncoder().encodeToString(ByteArray(32) { 91 })))
    }

    private fun qr(sessionToken: LanPairingBytes = bytes(32, 9)) = LanPairingQr(
        version = 1,
        sessionId = "9f633ff1-0bdd-4a95-bb9e-5d9e0ef8f6af",
        createdAtHintMillis = 1_725_000_000_000,
        lifetimeMillis = 300_000,
        deviceId = "dev-9f633ff1-0bdd-4a95-bb9e-5d9e0ef8f6af",
        displayName = "First phone",
        encryptionPublicKey = bytes(32, 1),
        signingPublicKey = bytes(32, 2),
        tlsSpkiSha256 = bytes(32, 3),
        sessionToken = sessionToken,
    )

    private fun hello(deviceId: String, seed: Int) = LanPairingHello(
        deviceId = deviceId,
        encryptionPublicKey = bytes(32, seed),
        signingPublicKey = bytes(32, seed + 1),
        tlsSpkiSha256 = bytes(32, seed + 2),
        nonce = bytes(32, seed + 3),
    )

    private fun bytes(size: Int, seed: Int = 0): LanPairingBytes = LanPairingBytes(ByteArray(size) { (it + seed).toByte() })
    private fun bytes(value: ByteArray): LanPairingBytes = LanPairingBytes(value)
    private fun b64(size: Int, seed: Int = 0): String = Base64.getEncoder().encodeToString(ByteArray(size) { (it + seed).toByte() })

    private fun jsonValue(field: String, json: String): String {
        val marker = "\"$field\":"
        val start = json.indexOf(marker) + marker.length
        return if (json[start] == '\"') {
            val end = json.indexOf('\"', start + 1) + 1
            json.substring(start, end)
        } else {
            val end = json.indexOfAny(charArrayOf(',', '}'), start)
            json.substring(start, end)
        }
    }
}
