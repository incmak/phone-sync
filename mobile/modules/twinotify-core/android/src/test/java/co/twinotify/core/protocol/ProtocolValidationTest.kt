package co.twinotify.core.protocol

import kotlin.test.Test
import kotlin.test.assertFailsWith

class ProtocolValidationTest {
    @Test
    fun peerReceipt_requiresTheCommittedReceiptPayloadContract() {
        val malformedDigest = validReceipt.replace("0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef", "bad")

        assertFailsWith<IllegalArgumentException> { ProtocolJson.decodeInner(malformedDigest) }
    }

    @Test
    fun peerReceipt_failureStatusesRequireABoundedReason() {
        val rejectedWithoutReason = validReceipt.replace("\"status\":\"applied\"", "\"status\":\"rejected\"")
        val decryptFailedWithoutReason = validReceipt.replace("\"status\":\"applied\"", "\"status\":\"decrypt_failed\"")
        val rejectedWithOverlongReason = validReceipt.replace(
            "\"status\":\"applied\"",
            "\"status\":\"rejected\",\"reason\":\"${"x".repeat(129)}\"",
        )
        val rejectedWith128EmojiReason = validReceipt.replace(
            "\"status\":\"applied\"",
            "\"status\":\"rejected\",\"reason\":\"${"\uD83D\uDE80".repeat(128)}\"",
        )
        val rejectedWith129EmojiReason = validReceipt.replace(
            "\"status\":\"applied\"",
            "\"status\":\"rejected\",\"reason\":\"${"\uD83D\uDE80".repeat(129)}\"",
        )

        assertFailsWith<IllegalArgumentException> { ProtocolJson.decodeInner(rejectedWithoutReason) }
        assertFailsWith<IllegalArgumentException> { ProtocolJson.decodeInner(decryptFailedWithoutReason) }
        assertFailsWith<IllegalArgumentException> { ProtocolJson.decodeInner(rejectedWithOverlongReason) }
        assertFailsWith<IllegalArgumentException> { ProtocolJson.decodeInner(rejectedWith129EmojiReason) }
        ProtocolJson.decodeInner(rejectedWith128EmojiReason)
        ProtocolJson.decodeInner(
            validReceipt.replace(
                "\"status\":\"applied\"",
                "\"status\":\"rejected\",\"reason\":\"invalid_payload\"",
            ),
        )
    }

    @Test
    fun encoder_rejectsEnvelopeOverTheCommittedOneMiBBound() {
        val enormousCiphertext = "A".repeat(ProtocolJson.MAX_ENVELOPE_BYTES)
        val envelope = EncryptedEnvelope(
            version = 2,
            msgId = "11111111-1111-4111-8111-111111111111",
            originDevice = "dev-a",
            createdAt = 1,
            nonceB64 = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA",
            ciphertextB64 = enormousCiphertext,
        )

        assertFailsWith<IllegalArgumentException> { ProtocolJson.encodeEnvelope(envelope) }
    }

    private companion object {
        const val validReceipt = """
            {"v":2,"msg_id":"11111111-1111-4111-8111-111111111111","origin_device":"dev-a","type":"peer.receipt","created_at":1000,"expires_at":2000,"payload":{"acked_msg_id":"22222222-2222-4222-8222-222222222222","envelope_sha256":"0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef","status":"applied"}}
        """
    }
}
