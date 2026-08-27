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
        val authenticator = authenticator(decryptor)

        assertFailsWith<EnvelopeMismatchException> { authenticator.open(validOuterJson) }
    }

    @Test
    fun malformedBase64IsRejectedBeforeDecryptorInvocation() {
        var calls = 0
        val decryptor = PayloadDecryptor { calls += 1; byteArrayOf() }

        assertFailsWith<ProtocolException> {
            authenticator(decryptor).open(validOuterJson.replace(VALID_NONCE, "%%%"))
        }
        assertEquals(0, calls)
    }

    @Test
    fun validEnvelope_authenticatesExactBytesAndComputesExactDigest() {
        val opened = authenticator(PayloadDecryptor { validInnerJson.encodeToByteArray() })
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
            authenticator(PayloadDecryptor { inner.encodeToByteArray() }).open(outer)
        }
    }

    @Test
    fun authenticatedExpiry_acceptsExactFiveMinuteSkewBoundary() {
        val expiry = OUTER_CREATED_AT + 10_000L
        val opened = EnvelopeAuthenticator(
            decryptor = PayloadDecryptor { innerWithExpiry(expiry).encodeToByteArray() },
            peerDeviceId = "dev-a",
            clock = { expiry + 300_000L },
        ).open(validOuterJson)

        assertEquals(expiry, opened.inner.expiresAt)
    }

    @Test
    fun authenticatedExpiry_rejectsOneMillisecondBeyondSkewAfterDecryption() {
        val expiry = OUTER_CREATED_AT + 10_000L
        var decryptions = 0
        val error = assertFailsWith<ProtocolException> {
            EnvelopeAuthenticator(
                decryptor = PayloadDecryptor {
                    decryptions += 1
                    innerWithExpiry(expiry).encodeToByteArray()
                },
                peerDeviceId = "dev-a",
                clock = { expiry + 300_001L },
            ).open(validOuterJson)
        }

        assertEquals(1, decryptions)
        assertEquals("authenticated v2 envelope expired", error.message)
    }

    @Test
    fun authenticatedExpiry_saturatesNearLongMaxValue() {
        val expiry = Long.MAX_VALUE - 1L
        val opened = EnvelopeAuthenticator(
            decryptor = PayloadDecryptor { innerWithExpiry(expiry).encodeToByteArray() },
            peerDeviceId = "dev-a",
            clock = { Long.MAX_VALUE },
        ).open(validOuterJson)

        assertEquals(expiry, opened.inner.expiresAt)
    }

    private fun innerWithExpiry(expiresAt: Long): String = validInnerJson.replace(
        "\"expires_at\":1786353748000",
        "\"expires_at\":$expiresAt",
    )

    private fun authenticator(decryptor: PayloadDecryptor) = EnvelopeAuthenticator(
        decryptor = decryptor,
        peerDeviceId = "dev-a",
        clock = { OUTER_CREATED_AT },
    )

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
