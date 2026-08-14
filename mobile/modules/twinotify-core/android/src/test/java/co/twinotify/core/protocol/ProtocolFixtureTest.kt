package co.twinotify.core.protocol

import co.twinotify.core.service.RelayFrame
import co.twinotify.core.service.RelayFrameCodec
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue
import org.json.JSONObject

/**
 * Consumes every committed protocol fixture through the same Kotlin boundaries used by the
 * service.  The fixture manifest is the contract: tests do not silently grow a second list.
 */
class ProtocolFixtureTest {
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
                    val put = assertIs<RelayFrame.Put>(frame)
                    val envelope = ProtocolJson.decodeEnvelope(put.envelope)
                    assertEquals(
                        JSONObject(put.envelope).getString("nonce").let(Base64.getDecoder()::decode).toList(),
                        Base64.getDecoder().decode(envelope.nonceB64).toList(),
                    )
                    assertEquals(
                        JSONObject(put.envelope).getString("ciphertext").let(Base64.getDecoder()::decode).toList(),
                        Base64.getDecoder().decode(envelope.ciphertextB64).toList(),
                    )
                }
                "peer_receipt_inner" -> {
                    val event = ProtocolJson.decodeInner(raw)
                    assertJsonEquivalent(JSONObject(raw), JSONObject(ProtocolJson.encodeInner(event)))
                }
                "call_state" -> {
                    val event = ProtocolJson.decodeInner(raw)
                    assertEquals("call.state", event.type)
                    assertJsonEquivalent(JSONObject(raw), JSONObject(ProtocolJson.encodeInner(event)))
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
                        EnvelopeAuthenticator(PayloadDecryptor { inner.encodeToByteArray() }, "dev-a").open(outer)
                    }
                    assertEquals("outer and inner msg_id differ", error.message)
                    assertEquals(expectedCode, observedFixtureCode(error))
                }
                "call_state" -> {
                    val error = assertFailsWith<IllegalArgumentException> { ProtocolJson.decodeInner(raw) }
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
        error is IllegalArgumentException && error.message == "inner event sequence must be positive" ->
            "invalid_frame"
        else -> error("unclassified protocol rejection: ${error::class.simpleName}: ${error.message}")
    }

    private fun assertJsonEquivalent(expected: JSONObject, actual: JSONObject) {
        val expectedKeys = expected.keys().asSequence().toSet()
        val actualKeys = actual.keys().asSequence().toSet()
        assertEquals(expectedKeys, actualKeys)
        expectedKeys.forEach { key ->
            val expectedValue = expected.get(key)
            val actualValue = actual.get(key)
            if (expectedValue is JSONObject && actualValue is JSONObject) {
                assertJsonEquivalent(expectedValue, actualValue)
            } else {
                assertEquals(expectedValue, actualValue, key)
            }
        }
    }
}
