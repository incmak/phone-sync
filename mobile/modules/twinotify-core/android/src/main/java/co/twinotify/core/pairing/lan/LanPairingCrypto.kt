package co.twinotify.core.pairing.lan

import com.goterl.lazysodium.LazySodiumAndroid
import com.goterl.lazysodium.SodiumAndroid
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

object LanPairingCrypto {
    private const val SHA_256_BYTES = 32
    private const val SAS_MODULUS = 1_000_000L
    private const val SAS_LIMIT = 4_294_000_000L
    private val signatureLock = Any()
    @Volatile
    private var signatureOperationsForTests: LanTranscriptSignatureOperations? = null
    private val secretInfo = "twinotify-lan-pair-secret-v1".encodeToByteArray()
    private val sasLabel = "twinotify-lan-sas-v1".encodeToByteArray()

    fun signTranscript(transcript: ByteArray, secretKey: ByteArray): ByteArray {
        require(secretKey.size == ED25519_SECRET_KEY_BYTES) { "invalid Ed25519 signing key" }
        return signatureOperations().sign(transcript, secretKey)
    }

    fun verifyTranscript(transcript: ByteArray, signature: ByteArray, publicKey: ByteArray): Boolean {
        if (signature.size != ED25519_SIGNATURE_BYTES || publicKey.size != ED25519_PUBLIC_KEY_BYTES) return false
        return signatureOperations().verify(transcript, signature, publicKey)
    }

    fun shortAuthenticationString(transcript: ByteArray): String {
        val transcriptDigest = sha256(transcript)
        var round = 0
        while (true) {
            val material = sha256(sasLabel + transcriptDigest + intBytes(round++))
            for (offset in material.indices step 4) {
                val candidate = ((material[offset].toLong() and 0xff) shl 24) or
                    ((material[offset + 1].toLong() and 0xff) shl 16) or
                    ((material[offset + 2].toLong() and 0xff) shl 8) or
                    (material[offset + 3].toLong() and 0xff)
                if (candidate < SAS_LIMIT) return (candidate % SAS_MODULUS).toString().padStart(6, '0')
            }
        }
    }

    fun derivePairSecret(sessionToken: ByteArray, transcript: ByteArray): ByteArray {
        require(sessionToken.size == SHA_256_BYTES) { "invalid LAN session token" }
        val transcriptDigest = sha256(transcript)
        val prk = hmac(transcriptDigest, sessionToken)
        return hmac(prk, secretInfo + byteArrayOf(1))
    }

    private fun sha256(value: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(value)

    private fun hmac(key: ByteArray, value: ByteArray): ByteArray = Mac.getInstance("HmacSHA256").run {
        init(SecretKeySpec(key, "HmacSHA256"))
        doFinal(value)
    }

    private fun intBytes(value: Int): ByteArray = byteArrayOf(
        (value ushr 24).toByte(), (value ushr 16).toByte(), (value ushr 8).toByte(), value.toByte(),
    )

    internal fun <T> withSignatureOperationsForTests(
        operations: LanTranscriptSignatureOperations,
        block: () -> T,
    ): T = synchronized(signatureLock) {
        check(signatureOperationsForTests == null) { "LAN signature test override already installed" }
        signatureOperationsForTests = operations
        try {
            block()
        } finally {
            signatureOperationsForTests = null
        }
    }

    private fun signatureOperations(): LanTranscriptSignatureOperations =
        signatureOperationsForTests ?: SodiumTranscriptSignatureOperations
}

internal interface LanTranscriptSignatureOperations {
    fun sign(transcript: ByteArray, secretKey: ByteArray): ByteArray
    fun verify(transcript: ByteArray, signature: ByteArray, publicKey: ByteArray): Boolean
}

private object SodiumTranscriptSignatureOperations : LanTranscriptSignatureOperations {
    private val sodium by lazy { LazySodiumAndroid(SodiumAndroid()).sodium }

    override fun sign(transcript: ByteArray, secretKey: ByteArray): ByteArray {
        val signature = ByteArray(ED25519_SIGNATURE_BYTES)
        check(sodium.crypto_sign_detached(signature, null, transcript, transcript.size.toLong(), secretKey) == 0) {
            "LAN transcript signing failed"
        }
        return signature
    }

    override fun verify(transcript: ByteArray, signature: ByteArray, publicKey: ByteArray): Boolean =
        sodium.crypto_sign_verify_detached(signature, transcript, transcript.size.toLong(), publicKey) == 0
}

private const val ED25519_PUBLIC_KEY_BYTES = 32
private const val ED25519_SECRET_KEY_BYTES = 64
private const val ED25519_SIGNATURE_BYTES = 64
