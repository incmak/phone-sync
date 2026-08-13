package co.twinotify.core.protocol

import java.util.Base64
import org.json.JSONObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ProtocolV2Test {
    @Test
    fun optionalJsonNull_decodesAsNullNotLiteralNull() {
        val event = ProtocolJson.decodeInner(validPostJson.replace("\"text\":\"hello\"", "\"text\":null"))

        assertNull(event.payloadObject().optNullableString("text"))
    }

    @Test
    fun peerReceiptFixture_roundTripsWithEquivalentJsonStructure() {
        val fixture = ProtocolFixtures.read(type = "peer_receipt_inner", valid = true)

        val event = ProtocolJson.decodeInner(fixture)
        val encoded = ProtocolJson.encodeInner(event)

        assertJsonEquivalent(JSONObject(fixture), JSONObject(encoded))
    }

    @Test
    fun relayPutFixture_decodesTheExactV2Envelope() {
        val relayPut = JSONObject(ProtocolFixtures.read(type = "relay_control", valid = true))
        val fixtureEnvelope = relayPut.getJSONObject("envelope")
        val envelope = ProtocolJson.decodeEnvelope(fixtureEnvelope.toString())
        val encoded = ProtocolJson.encodeEnvelope(envelope)

        assertEquals(2, envelope.version)
        assertEquals("11111111-1111-4111-8111-111111111111", envelope.msgId)
        assertEquals("dev-a", envelope.originDevice)
        assertEquals(1_786_267_348_000L, envelope.createdAt)
        assertJsonEquivalent(fixtureEnvelope, JSONObject(encoded))
        assertEquals(
            fixtureEnvelope.getString("nonce").let(Base64.getDecoder()::decode).toList(),
            envelope.nonceB64.let(Base64.getDecoder()::decode).toList(),
        )
        assertEquals(
            fixtureEnvelope.getString("ciphertext").let(Base64.getDecoder()::decode).toList(),
            envelope.ciphertextB64.let(Base64.getDecoder()::decode).toList(),
        )
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

    private companion object {
        const val validPostJson = """
            {"v":2,"msg_id":"11111111-1111-4111-8111-111111111111","origin_device":"dev-a","type":"notif.post","canon_id":"dev-a:app:1:","sequence":1,"created_at":1000,"expires_at":2000,"payload":{"text":"hello"}}
        """
    }
}
