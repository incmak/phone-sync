package co.twinotify.core.pairing

import org.junit.Assert.assertArrayEquals
import org.junit.Test

class PairConfirmationTest {
    @Test
    fun responderMessageUsesDomainSeparatedInitiatorBoundTranscript() {
        val token = "pair-token"
        val aEnc = byteArrayOf(1, 2)
        val aSign = byteArrayOf(3, 4)
        val bEnc = byteArrayOf(5, 6)
        val bSign = byteArrayOf(7, 8)
        val initiatorSignature = byteArrayOf(9, 10)

        val expected = "twinotify-pair-confirm-b-v1\n".toByteArray() +
            token.toByteArray() + aEnc + aSign + bEnc + bSign + initiatorSignature

        assertArrayEquals(
            expected,
            PairConfirmation.responderMessage(
                token,
                aEnc,
                aSign,
                bEnc,
                bSign,
                initiatorSignature,
            ),
        )
    }
}
