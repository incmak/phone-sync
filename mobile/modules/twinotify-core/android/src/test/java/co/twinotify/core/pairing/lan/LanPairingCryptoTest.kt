package co.twinotify.core.pairing.lan

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class LanPairingCryptoTest {
    @Test
    fun sasIsSixDigitsAndDeterministicallyDerivedFromTranscriptDigest() {
        val sas = LanPairingCrypto.shortAuthenticationString("canonical transcript".encodeToByteArray())

        assertTrue(sas.matches(Regex("^[0-9]{6}$")))
        assertEquals(sas, LanPairingCrypto.shortAuthenticationString("canonical transcript".encodeToByteArray()))
        assertNotEquals(sas, LanPairingCrypto.shortAuthenticationString("canonical transcript 2".encodeToByteArray()))
    }

    @Test
    fun sasRejectsCandidatesAtTheUnbiasedBoundary() {
        val rejected = candidateMaterial(4_294_000_000L)
        val accepted = candidateMaterial(4_293_999_999L)

        assertEquals(
            "999999",
            LanPairingCrypto.reduceSasCandidateMaterials(sequenceOf(rejected, accepted)),
        )
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
        assertEquals(
            "d5788b93b1cf98c45b68ce2ea6008ff6cf42d3c1a218ed7e98953f696bdd6ad6",
            first.toHex(),
        )
    }

    @Test
    fun cryptoErrorsDoNotExposeRawSecrets() {
        val secret = ByteArray(31) { 77 }
        val error = kotlin.test.assertFailsWith<IllegalArgumentException> {
            LanPairingCrypto.derivePairSecret(secret, "transcript".encodeToByteArray())
        }

        assertFalse(error.message.orEmpty().contains(secret.joinToString()))
    }

    private fun candidateMaterial(candidate: Long): ByteArray = ByteArray(32).also { material ->
        for (offset in material.indices step 4) {
            material[offset] = (candidate ushr 24).toByte()
            material[offset + 1] = (candidate ushr 16).toByte()
            material[offset + 2] = (candidate ushr 8).toByte()
            material[offset + 3] = candidate.toByte()
        }
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
}
