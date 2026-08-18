package co.twinotify.core.pairing.lan

import androidx.test.ext.junit.runners.AndroidJUnit4
import co.twinotify.core.crypto.WrappedKeys
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@RunWith(AndroidJUnit4::class)
class LanPairingCryptoInstrumentedTest {
    @Test
    fun realEd25519SignsCanonicalTranscriptAndRejectsFieldMutation() {
        val identity = WrappedKeys.generateSign()
        val transcript = canonicalTranscript(lifetimeMillis = 120_000)
        val signature = LanPairingCrypto.signTranscript(transcript, identity.secretKey)

        assertTrue(LanPairingCrypto.verifyTranscript(transcript, signature, identity.publicKey))
        assertFalse(
            LanPairingCrypto.verifyTranscript(
                canonicalTranscript(lifetimeMillis = 120_001),
                signature,
                identity.publicKey,
            ),
        )
    }

    private fun canonicalTranscript(lifetimeMillis: Long): ByteArray = LanPairingCodec.canonicalTranscript(
        LanPairingTranscript(
            sessionId = "9f633ff1-0bdd-4a95-bb9e-5d9e0ef8f6af",
            lifetimeMillis = lifetimeMillis,
            negotiatedVersion = 1,
            first = hello("dev-9f633ff1-0bdd-4a95-bb9e-5d9e0ef8f6af", 1),
            second = hello("dev-a70446b3-a355-46cc-9e62-069a0bfe2e10", 2),
        ),
    )

    private fun hello(deviceId: String, seed: Int) = LanPairingHello(
        deviceId = deviceId,
        encryptionPublicKey = bytes(seed),
        signingPublicKey = bytes(seed + 1),
        tlsSpkiSha256 = bytes(seed + 2),
        nonce = bytes(seed + 3),
    )

    private fun bytes(seed: Int) = LanPairingBytes(ByteArray(32) { (it + seed).toByte() })
}
