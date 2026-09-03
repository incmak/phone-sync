package co.twinotify.core.protocol

import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertEquals
import org.json.JSONArray
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

    @Test
    fun callState_acceptsOnlyCompleteControlsForIncomingState() {
        ProtocolJson.decodeInner(callStateWithControlsJson("ringing", "incoming", "answer,decline"))
        ProtocolJson.decodeInner(callStateWithControlsJson("active", "incoming", "hang_up"))

        for (
            raw in listOf(
                callStateWithControlsJson("ringing", "incoming", "answer"),
                callStateWithControlsJson("ringing", "incoming", "answer,answer"),
                callStateWithControlsJson("ringing", "incoming", "answer,decline")
                    .replace(OTHER_CONTROL_ID, CONTROL_ID),
                callStateWithControlsJson("active", "incoming", "answer"),
                callStateWithControlsJson("active", "unknown", "hang_up"),
                callStateWithControlsJson("idle", "incoming", "hang_up"),
            )
        ) {
            assertFailsWith<IllegalArgumentException> { ProtocolJson.decodeInner(raw) }
        }
    }

    @Test
    fun callControlInvoke_isOneUseShortLivedAndPrivacyBounded() {
        val decoded = ProtocolJson.decodeInner(callControlInvokeJson())
        assertEquals("call.control.invoke", decoded.type)
        assertEquals(null, decoded.canonId)
        assertEquals(null, decoded.sequence)

        for (
            raw in listOf(
                callControlInvokeJson().replace("\"expires_at\":16000", "\"expires_at\":16001"),
                callControlInvokeJson()
                    .replace(CONTROL_ID, OTHER_CONTROL_ID, ignoreCase = false)
                    .replaceFirst(OTHER_CONTROL_ID, CONTROL_ID),
                callControlInvokeJson().replace("\"kind\":\"answer\"", "\"kind\":\"mute\""),
                callControlInvokeJson().replace("\"invoked_at\":1000", "\"invoked_at\":1001"),
                callControlInvokeJson().replace(
                    "\"canon_id\":\"call:$CALL_SESSION_ID\"",
                    "\"canon_id\":\"call:$OTHER_CONTROL_ID\"",
                ),
                callControlInvokeJson().replace("\"created_at\":1000", "\"canon_id\":\"call:$CALL_SESSION_ID\",\"created_at\":1000"),
                callControlInvokeJson().replace("\"created_at\":1000", "\"sequence\":1,\"created_at\":1000"),
                callControlInvokeJson().replace(
                    "\"invoked_at\":1000",
                    "\"invoked_at\":1000,\"phone_number\":\"+15551234567\"",
                ),
                callControlInvokeJson().replace("\"call_sequence\":2", "\"call_sequence\":0"),
            )
        ) {
            assertFailsWith<IllegalArgumentException> { ProtocolJson.decodeInner(raw) }
        }
    }

    @Test
    fun callControlResult_acceptsOnlyTruthfulTerminalStatuses() {
        ProtocolJson.decodeInner(callControlResultJson("dispatched"))
        for (status in listOf("outcome_unknown", "capability_gone", "call_gone", "stale_state", "expired", "failed")) {
            ProtocolJson.decodeInner(callControlResultJson(status))
        }
        for (
            raw in listOf(
                callControlResultJson("answered"),
                callControlResultJson("dispatched").replace("\"expires_at\":301000", "\"expires_at\":301001"),
                callControlResultJson("dispatched").replace("\"kind\":\"answer\"", "\"kind\":\"mute\""),
                callControlResultJson("dispatched").replace(
                    "\"canon_id\":\"call:$CALL_SESSION_ID\"",
                    "\"canon_id\":\"call:not-a-uuid\"",
                ),
                callControlResultJson("dispatched").replace(
                    "\"status\":\"dispatched\"",
                    "\"status\":\"dispatched\",\"phone_number\":\"+15551234567\"",
                ),
                callControlResultJson("dispatched").replace("\"created_at\":1000", "\"sequence\":1,\"created_at\":1000"),
            )
        ) {
            assertFailsWith<IllegalArgumentException> { ProtocolJson.decodeInner(raw) }
        }
    }

    @Test
    fun notificationActionInvoke_isAControlEventWithStrictPayload() {
        val decoded = ProtocolJson.decodeInner(actionInvokeJson())

        assertEquals("notif.action.invoke", decoded.type)
        assertEquals(null, decoded.canonId)
        assertEquals(null, decoded.sequence)
        assertEquals(decoded, ProtocolJson.decodeInner(ProtocolJson.encodeInner(decoded)))
    }

    @Test
    fun notificationActionInvoke_enforcesUtf8ReplyLimitAndExactKeys() {
        ProtocolJson.decodeInner(actionInvokeJson(replyText = "a".repeat(4096)))

        val invalid = listOf(
            actionInvokeJson(replyText = "a".repeat(4097)),
            actionInvokeJson(replyText = "\uD83D\uDE80".repeat(1025)),
            actionInvokeJson().replace("\"invoked_at\":1000", "\"invoked_at\":1000,\"component\":\"private\""),
            actionInvokeJson().replace(ACTION_ID, "not-a-uuid"),
        )
        invalid.forEach { raw ->
            assertFailsWith<IllegalArgumentException> { ProtocolJson.decodeInner(raw) }
        }
    }

    @Test
    fun notificationActionResult_rejectsUnknownStatusAndKeys() {
        ProtocolJson.decodeInner(actionResultJson())

        val invalid = listOf(
            actionResultJson().replace("\"dispatched\"", "\"applied\""),
            actionResultJson().replace("\"status\":\"dispatched\"", "\"status\":\"dispatched\",\"error\":\"private\""),
        )
        invalid.forEach { raw ->
            assertFailsWith<IllegalArgumentException> { ProtocolJson.decodeInner(raw) }
        }
    }

    @Test
    fun lanBootstrapAndPeerProbe_roundTripAsStrictReceiptBackedControls() {
        val bootstrap = ProtocolJson.decodeInner(lanBootstrapJson())
        val probe = ProtocolJson.decodeInner(peerProbeJson())

        assertEquals("lan.bootstrap", bootstrap.type)
        assertEquals(null, bootstrap.canonId)
        assertEquals(null, bootstrap.sequence)
        assertEquals("peer.probe", probe.type)
        assertEquals(probe.msgId, JSONObject(probe.payloadJson).getString("probe_id"))
        assertEquals(bootstrap, ProtocolJson.decodeInner(ProtocolJson.encodeInner(bootstrap)))
        assertEquals(probe, ProtocolJson.decodeInner(ProtocolJson.encodeInner(probe)))
    }

    @Test
    fun lanBootstrap_rejectsInvalidShapeIdentityAndLifetime() {
        val invalid = listOf(
            lanBootstrapJson().replace("\"protocol_version\":1,", ""),
            lanBootstrapJson().replace("\"protocol_version\":1", "\"protocol_version\":2"),
            lanBootstrapJson().replace("a".repeat(64), "A".repeat(64)),
            lanBootstrapJson().replace("b".repeat(64), "bad"),
            lanBootstrapJson().replace("\"binding_context_sha256\":\"${"b".repeat(64)}\"", "\"binding_context_sha256\":\"${"b".repeat(64)}\",\"address\":\"192.0.2.1\""),
            lanBootstrapJson().replace("\"expires_at\":601000", "\"expires_at\":600999"),
            lanBootstrapJson().replace("\"created_at\":1000", "\"canon_id\":\"not-allowed\",\"created_at\":1000"),
            lanBootstrapJson().replace("\"created_at\":1000", "\"sequence\":1,\"created_at\":1000"),
        )

        invalid.forEach { raw -> assertFailsWith<IllegalArgumentException> { ProtocolJson.decodeInner(raw) } }
    }

    @Test
    fun peerProbe_rejectsInvalidShapeCorrelationAndLifetime() {
        val invalid = listOf(
            peerProbeJson().replace(",\"request_direct\":true", ""),
            peerProbeJson().replace(
                "\"probe_id\":\"$PROBE_ID\"",
                "\"probe_id\":\"66666666-6666-4666-8666-666666666666\"",
            ),
            peerProbeJson().replace("\"sent_at\":1000", "\"sent_at\":-1"),
            peerProbeJson().replace("\"request_direct\":true", "\"request_direct\":\"true\""),
            peerProbeJson().replace("\"request_direct\":true", "\"request_direct\":true,\"ssid\":\"private\""),
            peerProbeJson().replace("\"expires_at\":121000", "\"expires_at\":120999"),
            peerProbeJson().replace("\"created_at\":1000", "\"canon_id\":\"not-allowed\",\"created_at\":1000"),
            peerProbeJson().replace(PROBE_ID, PROBE_ID.uppercase()),
        )

        invalid.forEach { raw -> assertFailsWith<IllegalArgumentException> { ProtocolJson.decodeInner(raw) } }
    }

    private companion object {
        fun callStateJson() = """
            {"v":2,"msg_id":"22222222-2222-4222-8222-222222222222","origin_device":"dev-a","type":"call.state","canon_id":"call:11111111-1111-4111-8111-111111111111","sequence":1,"created_at":1000,"expires_at":2000,"payload":{"call_session_id":"11111111-1111-4111-8111-111111111111","state":"ringing","direction":"incoming"}}
        """.trimIndent()

        const val CALL_SESSION_ID = "11111111-1111-4111-8111-111111111111"
        const val CONTROL_ID = "2a846785-e576-47d0-8c4b-e4fba30d88bd"
        const val OTHER_CONTROL_ID = "0d47171d-c1ae-463a-bae7-3e8778517c0f"

        fun callStateWithControlsJson(state: String, direction: String, kindsCsv: String): String {
            val controls = JSONArray()
            kindsCsv.split(',').forEachIndexed { index, kind ->
                controls.put(
                    JSONObject()
                        .put("control_id", if (index == 0) CONTROL_ID else OTHER_CONTROL_ID)
                        .put("kind", kind),
                )
            }
            return JSONObject(callStateJson())
                .put("payload", JSONObject().apply {
                    put("call_session_id", CALL_SESSION_ID)
                    put("state", state)
                    put("direction", direction)
                    put("controls", controls)
                })
                .toString()
        }

        fun callControlInvokeJson() = """
            {"v":2,"msg_id":"33333333-3333-4333-8333-333333333333","origin_device":"mirror-device","type":"call.control.invoke","created_at":1000,"expires_at":16000,"payload":{"invocation_id":"$CONTROL_ID","canon_id":"call:$CALL_SESSION_ID","call_session_id":"$CALL_SESSION_ID","call_sequence":2,"control_id":"$CONTROL_ID","kind":"answer","invoked_at":1000}}
        """.trimIndent()

        fun callControlResultJson(status: String) = """
            {"v":2,"msg_id":"44444444-4444-4444-8444-444444444444","origin_device":"origin-device","type":"call.control.result","created_at":1000,"expires_at":301000,"payload":{"invocation_id":"$CONTROL_ID","canon_id":"call:$CALL_SESSION_ID","kind":"answer","status":"$status"}}
        """.trimIndent()

        const val ACTION_ID = "b6d3142a-e936-4d7d-b15a-bdf318bb0539"

        fun actionInvokeJson(replyText: String? = "On my way"): String {
            val reply = replyText?.let { ",\"reply_text\":${JSONObject.quote(it)}" }.orEmpty()
            return """
                {"v":2,"msg_id":"8ac240b7-8b89-4b41-80bf-d96424c654ec","origin_device":"mirror-device","type":"notif.action.invoke","created_at":1000,"expires_at":121000,"payload":{"invocation_id":"fd2fb70b-829a-4701-8956-61611bc9c701","canon_id":"origin-device:com.example.chat:42:thread-7","action_id":"$ACTION_ID","notification_sequence":17$reply,"invoked_at":1000}}
            """.trimIndent()
        }

        fun actionResultJson() = """
            {"v":2,"msg_id":"7ddc4c03-951f-4e7b-ad09-6c5b1c1df6f5","origin_device":"origin-device","type":"notif.action.result","created_at":1000,"expires_at":601000,"payload":{"invocation_id":"fd2fb70b-829a-4701-8956-61611bc9c701","canon_id":"origin-device:com.example.chat:42:thread-7","status":"dispatched"}}
        """.trimIndent()

        fun lanBootstrapJson() = """
            {"v":2,"msg_id":"61111111-1111-4111-8111-111111111111","origin_device":"dev-a","type":"lan.bootstrap","created_at":1000,"expires_at":601000,"payload":{"protocol_version":1,"tls_spki_sha256":"${"a".repeat(64)}","binding_context_sha256":"${"b".repeat(64)}"}}
        """.trimIndent()

        const val PROBE_ID = "62aaaaaa-2222-4222-8222-222222222222"

        fun peerProbeJson() = """
            {"v":2,"msg_id":"$PROBE_ID","origin_device":"dev-a","type":"peer.probe","created_at":1000,"expires_at":121000,"payload":{"probe_id":"$PROBE_ID","sent_at":1000,"request_direct":true}}
        """.trimIndent()

        const val validReceipt = """
            {"v":2,"msg_id":"11111111-1111-4111-8111-111111111111","origin_device":"dev-a","type":"peer.receipt","created_at":1000,"expires_at":2000,"payload":{"acked_msg_id":"22222222-2222-4222-8222-222222222222","envelope_sha256":"0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef","status":"applied"}}
        """
    }
}
