package co.twinotify.core.pairing.lan

import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class LanPairingCryptoTest {
    @Test
    fun transcriptSignatureUsesExistingEd25519IdentityAndRejectsMutation() {
        val secretKey = ByteArray(64) { (it + 1).toByte() }
        val publicKey = secretKey.copyOf(32)
        val transcript = "canonical transcript".encodeToByteArray()
        LanPairingCrypto.withSignatureOperationsForTests(TestSignatureOperations) {
            val signature = LanPairingCrypto.signTranscript(transcript, secretKey)

            assertTrue(LanPairingCrypto.verifyTranscript(transcript, signature, publicKey))
            assertFalse(LanPairingCrypto.verifyTranscript("canonical transcripu".encodeToByteArray(), signature, publicKey))
            assertFalse(LanPairingCrypto.verifyTranscript(transcript, signature.copyOf().also { it[0] = (it[0].toInt() xor 1).toByte() }, publicKey))
        }
    }

    @Test
    fun sasIsSixDigitsAndDeterministicallyDerivedFromTranscriptDigest() {
        val sas = LanPairingCrypto.shortAuthenticationString("canonical transcript".encodeToByteArray())

        assertTrue(sas.matches(Regex("^[0-9]{6}$")))
        assertEquals(sas, LanPairingCrypto.shortAuthenticationString("canonical transcript".encodeToByteArray()))
        assertNotEquals(sas, LanPairingCrypto.shortAuthenticationString("canonical transcript 2".encodeToByteArray()))
    }

    @Test
    fun pairSecretUsesHkdfSha256AndDomainSeparation() {
        val token = ByteArray(32) { it.toByte() }
        val transcript = "canonical transcript".encodeToByteArray()

        val first = LanPairingCrypto.derivePairSecret(token, transcript)
        val second = LanPairingCrypto.derivePairSecret(token, transcript)

        assertEquals(32, first.size)
        assertContentEquals(first, second)
        assertFalse(first.contentEquals(LanPairingCrypto.derivePairSecret(token, "other transcript".encodeToByteArray())))
        assertFalse(first.contentEquals(MessageDigest.getInstance("SHA-256").digest(token + transcript)))
    }

    @Test
    fun cryptoErrorsDoNotExposeRawSecrets() {
        val secret = ByteArray(31) { 77 }
        val error = kotlin.test.assertFailsWith<IllegalArgumentException> {
            LanPairingCrypto.derivePairSecret(secret, "transcript".encodeToByteArray())
        }

        assertFalse(error.message.orEmpty().contains(secret.joinToString()))
    }

    private object TestSignatureOperations : LanTranscriptSignatureOperations {
        override fun sign(transcript: ByteArray, secretKey: ByteArray): ByteArray =
            MessageDigest.getInstance("SHA-512").digest(secretKey.copyOf(32) + transcript)

        override fun verify(transcript: ByteArray, signature: ByteArray, publicKey: ByteArray): Boolean =
            MessageDigest.isEqual(signature, MessageDigest.getInstance("SHA-512").digest(publicKey + transcript))
    }
}
