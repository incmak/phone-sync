package co.twinotify.core.protocol

import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertEquals
import org.json.JSONObject

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

    @Test
    fun callState_roundTripsExactPrivacyBoundedPayload() {
        val raw = callStateJson()

        val decoded = ProtocolJson.decodeInner(raw)
        assertEquals("call.state", decoded.type)
        assertEquals("call:11111111-1111-4111-8111-111111111111", decoded.canonId)
        assertEquals(1L, decoded.sequence)
        assertEquals(
            setOf("call_session_id", "state", "direction"),
            JSONObject(decoded.payloadJson).keys().asSequence().toSet(),
        )
        assertEquals(decoded, ProtocolJson.decodeInner(ProtocolJson.encodeInner(decoded)))
    }

    @Test
    fun callState_rejectsUnknownStatesDirectionsIdsSequencesAndPrivacyFields() {
        val cases = listOf(
            callStateJson().replace("\"state\":\"ringing\"", "\"state\":\"connected\""),
            callStateJson().replace("\"direction\":\"incoming\"", "\"direction\":\"internal\""),
            callStateJson().replace("11111111-1111-4111-8111-111111111111", "not-a-uuid"),
            callStateJson().replace("\"sequence\":1", "\"sequence\":0"),
            callStateJson().replace("\"direction\":\"incoming\"", "\"direction\":\"incoming\",\"phone_number\":\"+15551234567\""),
            callStateJson().replace("\"direction\":\"incoming\"", "\"direction\":\"incoming\",\"contact_name\":\"Alice\""),
            callStateJson().replace("\"canon_id\":\"call:11111111-1111-4111-8111-111111111111\"", "\"canon_id\":\"notification:wrong\""),
            callStateJson().replace("\"direction\":\"incoming\"", "\"direction\":\"incoming\",\"unexpected\":true"),
            callStateJson()
                .replace("\"canon_id\":\"call:11111111-1111-4111-8111-111111111111\"", "\"canon_id\":\"call:abcdefab-cdef-4abc-8def-abcdefabcdef\"")
                .replace("\"call_session_id\":\"11111111-1111-4111-8111-111111111111\"", "\"call_session_id\":\"ABCDEFAB-CDEF-4ABC-8DEF-ABCDEFABCDEF\""),
        )

        cases.forEach { raw ->
            assertFailsWith<IllegalArgumentException> { ProtocolJson.decodeInner(raw) }
        }
    }

    private companion object {
        fun callStateJson() = """
            {"v":2,"msg_id":"22222222-2222-4222-8222-222222222222","origin_device":"dev-a","type":"call.state","canon_id":"call:11111111-1111-4111-8111-111111111111","sequence":1,"created_at":1000,"expires_at":2000,"payload":{"call_session_id":"11111111-1111-4111-8111-111111111111","state":"ringing","direction":"incoming"}}
        """.trimIndent()

        const val validReceipt = """
            {"v":2,"msg_id":"11111111-1111-4111-8111-111111111111","origin_device":"dev-a","type":"peer.receipt","created_at":1000,"expires_at":2000,"payload":{"acked_msg_id":"22222222-2222-4222-8222-222222222222","envelope_sha256":"0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef","status":"applied"}}
        """
    }
}
