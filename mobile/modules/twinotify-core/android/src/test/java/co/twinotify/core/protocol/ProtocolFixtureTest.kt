package co.twinotify.core.protocol

import co.twinotify.core.listener.NotifPostJson
import co.twinotify.core.service.RelayFrame
import co.twinotify.core.service.RelayFrameCodec
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import org.json.JSONArray
import org.json.JSONObject

/**
 * Consumes every committed protocol fixture through the same Kotlin boundaries used by the
 * service.  The fixture manifest is the contract: tests do not silently grow a second list.
 */
class ProtocolFixtureTest {
    @Test
    fun jsonEquivalence_comparesNestedArraysStructurally() {
        val expected = JSONObject(
            """{"controls":[{"control_id":"one","kind":"answer"},{"control_id":"two","kind":"decline"}]}""",
        )
        val actual = JSONObject(expected.toString())

        assertJsonEquivalent(expected, actual)
    }

    @Test
    fun validFixtures_roundTripWithoutChangingBytesOrStructure() {
        val manifest = JSONObject(ProtocolFixtures.manifest())
        val entries = manifest.getJSONArray("fixtures")
        var validCount = 0
        for (index in 0 until entries.length()) {
            val entry = entries.getJSONObject(index)
            if (!entry.optBoolean("valid", false)) continue
            validCount += 1
            val raw = ProtocolFixtures.readPath(entry.getString("file"))
            when (entry.getString("type")) {
                "relay_control" -> {
                    val frame = RelayFrameCodec.decode(raw)
                    val encoded = RelayFrameCodec.encode(frame)
                    assertJsonEquivalent(JSONObject(raw), JSONObject(encoded))
                    if (frame is RelayFrame.Put) {
                        val envelope = ProtocolJson.decodeEnvelope(frame.envelope)
                        assertEquals(
                            JSONObject(frame.envelope).getString("nonce").let(Base64.getDecoder()::decode).toList(),
                            Base64.getDecoder().decode(envelope.nonceB64).toList(),
                        )
                        assertEquals(
                            JSONObject(frame.envelope).getString("ciphertext").let(Base64.getDecoder()::decode).toList(),
                            Base64.getDecoder().decode(envelope.ciphertextB64).toList(),
                        )
                    }
                }
                "call_state" -> {
                    val event = ProtocolJson.decodeInner(raw)
                    assertEquals("call.state", event.type)
                    assertJsonEquivalent(JSONObject(raw), JSONObject(ProtocolJson.encodeInner(event)))
                }
                "peer_receipt_inner",
                "notif_action_invoke",
                "notif_action_result",
                "lan_bootstrap_inner",
                "peer_probe_inner",
                "call_control_invoke",
                "call_control_result",
                -> {
                    val event = ProtocolJson.decodeInner(raw)
                    assertJsonEquivalent(JSONObject(raw), JSONObject(ProtocolJson.encodeInner(event)))
                }
                "notif_post_payload" -> {
                    val post = NotifPostJson.fromPayloadJson(raw)
                    assertTrue(post.canon_id.isNotEmpty())
                }
                else -> error("unsupported valid fixture type ${entry.getString("type")}")
            }
        }
        assertTrue(validCount > 0, "manifest must contain valid fixtures")
    }

    @Test
    fun invalidFixtures_failWithTheManifestCategory() {
        val manifest = JSONObject(ProtocolFixtures.manifest())
        val entries = manifest.getJSONArray("fixtures")
        var invalidCount = 0
        for (index in 0 until entries.length()) {
            val entry = entries.getJSONObject(index)
            if (entry.optBoolean("valid", false)) continue
            invalidCount += 1
            val type = entry.getString("type")
            val expectedCode = entry.getString("expected_code")
            val raw = ProtocolFixtures.readPath(entry.getString("file"))
            when (type) {
                "relay_control" -> {
                    val error = assertFailsWith<IllegalArgumentException> { RelayFrameCodec.decode(raw) }
                    assertEquals("invalid SHA-256 digest", error.message)
                    assertEquals(expectedCode, observedFixtureCode(error))
                }
                "outer_inner_pair" -> {
                    val fixture = JSONObject(raw)
                    val outer = fixture.getJSONObject("outer").toString()
                    val inner = fixture.getJSONObject("inner").toString()
                    val error = assertFailsWith<EnvelopeMismatchException> {
                        EnvelopeAuthenticator(
                            PayloadDecryptor { inner.encodeToByteArray() },
                            "dev-a",
                            clock = { 0L },
                        ).open(outer)
                    }
                    assertEquals("outer and inner msg_id differ", error.message)
                    assertEquals(expectedCode, observedFixtureCode(error))
                }
                "call_state" -> {
                    val error = assertFailsWith<IllegalArgumentException> { ProtocolJson.decodeInner(raw) }
                    assertEquals(expectedCode, observedFixtureCode(error))
                }
                "notif_action_invoke",
                "notif_action_result",
                "lan_bootstrap_inner",
                "peer_probe_inner",
                "call_control_invoke",
                "call_control_result",
                -> {
                    val error = assertFailsWith<IllegalArgumentException> { ProtocolJson.decodeInner(raw) }
                    assertEquals(expectedCode, observedFixtureCode(error))
                }
                "notif_post_payload" -> {
                    val error = assertFailsWith<IllegalArgumentException> { NotifPostJson.fromPayloadJson(raw) }
                    assertEquals(expectedCode, observedFixtureCode(error))
                }
                else -> error("unsupported invalid fixture type $type")
            }
        }
        assertTrue(invalidCount > 0, "manifest must contain invalid fixtures")
    }

    /** Converts the concrete parser/authenticator failure into the shared stable category. */
    private fun observedFixtureCode(error: Throwable): String = when {
        error is EnvelopeMismatchException && error.message == "outer and inner msg_id differ" ->
            "outer_inner_id_mismatch"
        error is IllegalArgumentException && error.message == "invalid SHA-256 digest" -> "invalid_frame"
        error is IllegalArgumentException && error.message?.contains("call.state") == true ->
            "invalid_frame"
        error is IllegalArgumentException && error.message?.contains("notif.action") == true ->
            "invalid_frame"
        error is IllegalArgumentException &&
            (error.message?.contains("call control") == true || error.message?.contains("call.control") == true) ->
            "invalid_frame"
        error is IllegalArgumentException && error.message?.contains("lan.bootstrap") == true ->
            "invalid_frame"
        error is IllegalArgumentException && error.message?.contains("peer.probe") == true ->
            "invalid_frame"
        error is IllegalArgumentException && error.message?.contains("notification payload") == true ->
            "invalid_frame"
        error is IllegalArgumentException && error.message == "inner event sequence must be positive" ->
            "invalid_frame"
        else -> error("unclassified protocol rejection: ${error::class.simpleName}: ${error.message}")
    }

    private fun assertJsonEquivalent(expected: JSONObject, actual: JSONObject) {
        val expectedKeys = expected.keys().asSequence().toSet()
        val actualKeys = actual.keys().asSequence().toSet()
        assertEquals(expectedKeys, actualKeys)
        expectedKeys.forEach { key ->
            assertJsonValueEquivalent(expected.get(key), actual.get(key), key)
        }
    }

    private fun assertJsonArrayEquivalent(expected: JSONArray, actual: JSONArray, label: String) {
        assertEquals(expected.length(), actual.length(), "$label length")
        for (index in 0 until expected.length()) {
            assertJsonValueEquivalent(expected.get(index), actual.get(index), "$label[$index]")
        }
    }

    private fun assertJsonValueEquivalent(expected: Any, actual: Any, label: String) {
        when {
            expected is JSONObject && actual is JSONObject -> assertJsonEquivalent(expected, actual)
            expected is JSONArray && actual is JSONArray -> assertJsonArrayEquivalent(expected, actual, label)
            else -> assertEquals(expected, actual, label)
        }
    }
}
