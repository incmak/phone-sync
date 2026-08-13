package co.twinotify.core.protocol

import java.security.MessageDigest
import org.json.JSONObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class EnvelopeAuthenticatorTest {
    @Test
    fun outerAndAuthenticatedInnerMessageIdsMustMatch() {
        val decryptor = PayloadDecryptor {
            validInnerJson.replace(INNER_ID, DIFFERENT_ID).encodeToByteArray()
        }
        val authenticator = EnvelopeAuthenticator(decryptor, peerDeviceId = "dev-a")

        assertFailsWith<EnvelopeMismatchException> { authenticator.open(validOuterJson) }
    }

    @Test
    fun malformedBase64IsRejectedBeforeDecryptorInvocation() {
        var calls = 0
        val decryptor = PayloadDecryptor { calls += 1; byteArrayOf() }

        assertFailsWith<ProtocolException> {
            EnvelopeAuthenticator(decryptor, "dev-a").open(validOuterJson.replace(VALID_NONCE, "%%%"))
        }
        assertEquals(0, calls)
    }

    @Test
    fun validEnvelope_authenticatesExactBytesAndComputesExactDigest() {
        val opened = EnvelopeAuthenticator(PayloadDecryptor { validInnerJson.encodeToByteArray() }, "dev-a")
            .open(validOuterJson)

        val expectedDigest = MessageDigest.getInstance("SHA-256")
            .digest(validOuterJson.encodeToByteArray())
            .joinToString("") { "%02x".format(it) }
        assertEquals(expectedDigest, opened.envelopeSha256)
        assertEquals(INNER_ID, opened.inner.msgId)
        assertEquals(OUTER_CREATED_AT, opened.inner.createdAt)
    }

    @Test
    fun committedMismatchFixture_isRejectedAfterDecryption() {
        val fixture = JSONObject(
            ProtocolFixtures.read(
                type = "outer_inner_pair",
                valid = false,
                expectedCode = "outer_inner_id_mismatch",
            ),
        )
        val outer = fixture.getJSONObject("outer").toString()
        val inner = fixture.getJSONObject("inner").toString()

        assertFailsWith<EnvelopeMismatchException> {
            EnvelopeAuthenticator(PayloadDecryptor { inner.encodeToByteArray() }, "dev-a").open(outer)
        }
    }

    private companion object {
        const val INNER_ID = "11111111-1111-4111-8111-111111111111"
        const val DIFFERENT_ID = "22222222-2222-4222-8222-222222222222"
        const val VALID_NONCE = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"
        const val OUTER_CREATED_AT = 1_786_267_348_000L
        const val validOuterJson = """
            {"v":2,"type":"enc","msg_id":"11111111-1111-4111-8111-111111111111","origin_device":"dev-a","created_at":1786267348000,"nonce":"AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA","ciphertext":"Y2lwaGVydGV4dA=="}
        """
        const val validInnerJson = """
            {"v":2,"msg_id":"11111111-1111-4111-8111-111111111111","origin_device":"dev-a","type":"peer.receipt","created_at":1786267348000,"expires_at":1786353748000,"payload":{"acked_msg_id":"22222222-2222-4222-8222-222222222222","envelope_sha256":"0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef","status":"applied"}}
        """
    }
}
