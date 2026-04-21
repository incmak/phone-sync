package co.twinotify.core.service

import kotlin.test.Test
import kotlin.test.assertEquals

class EncryptedEnvelopeTest {
    @Test fun roundtrip() {
        val e = EncryptedEnvelope(
            msgId = "00000000-0000-4000-8000-000000000000",
            originDevice = "devA",
            ts = 1_700_000_000_000L,
            nonceB64 = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA",
            ciphertextB64 = "Y2lwaGVydGV4dA==",
        )
        val json = e.toJson()
        val parsed = EncryptedEnvelope.fromJson(json)
        assertEquals(e, parsed)
    }

    @Test fun toJson_contains_expected_keys() {
        val e = EncryptedEnvelope(
            msgId = "test-msg-id",
            originDevice = "device-1",
            ts = 12345L,
            nonceB64 = "nonce",
            ciphertextB64 = "ciphertext",
        )
        val json = e.toJson()
        assert(json.contains("\"msg_id\"")) { "expected msg_id key in $json" }
        assert(json.contains("\"origin_device\"")) { "expected origin_device key in $json" }
        assert(json.contains("\"nonce\"")) { "expected nonce key in $json" }
        assert(json.contains("\"ciphertext\"")) { "expected ciphertext key in $json" }
        assert(json.contains("\"type\":\"enc\"")) { "expected type=enc in $json" }
        assert(json.contains("\"v\":1")) { "expected v=1 in $json" }
    }
}
