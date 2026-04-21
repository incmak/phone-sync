package expo.modules.phonesynccore.crypto

import com.goterl.lazysodium.LazySodiumAndroid
import com.goterl.lazysodium.SodiumAndroid
import com.goterl.lazysodium.interfaces.Box

/**
 * Thin wrappers around libsodium crypto_box_easy / crypto_box_open_easy.
 *
 * API choice: uses sodium.crypto_box_easy (snake_case JNA native binding on SodiumAndroid/Sodium)
 * rather than the camelCase LazySodiumAndroid high-level API, consistent with Task 5 keypair
 * generation (crypto_box_keypair). The JNA binding compiles reliably; the high-level API
 * requires String/Key conversions that add unnecessary abstraction for raw byte operations.
 */
object Encrypter {
    private val ls = LazySodiumAndroid(SodiumAndroid())
    private val sodium = ls.sodium

    sealed class DecryptError : Exception() {
        object AuthFailed : DecryptError() {
            override val message = "MAC verification failed — wrong key, wrong nonce, or tampered ciphertext"
        }
        data class SizeMismatch(val got: Int) : DecryptError() {
            override val message = "ciphertext too short: $got"
        }
    }

    fun encrypt(
        plain: ByteArray,
        nonce: ByteArray,
        peerPubkey: ByteArray,
        ownSecret: ByteArray
    ): ByteArray {
        require(nonce.size == Box.NONCEBYTES) {
            "nonce must be ${Box.NONCEBYTES} bytes, got ${nonce.size}"
        }
        require(peerPubkey.size == Box.PUBLICKEYBYTES) {
            "peer pubkey must be ${Box.PUBLICKEYBYTES} bytes"
        }
        require(ownSecret.size == Box.SECRETKEYBYTES) {
            "own secret must be ${Box.SECRETKEYBYTES} bytes"
        }
        val ct = ByteArray(plain.size + Box.MACBYTES)
        val rc = sodium.crypto_box_easy(ct, plain, plain.size.toLong(), nonce, peerPubkey, ownSecret)
        check(rc == 0) { "crypto_box_easy rc=$rc" }
        return ct
    }

    fun decrypt(
        ct: ByteArray,
        nonce: ByteArray,
        peerPubkey: ByteArray,
        ownSecret: ByteArray
    ): ByteArray {
        if (ct.size < Box.MACBYTES) throw DecryptError.SizeMismatch(ct.size)
        val plain = ByteArray(ct.size - Box.MACBYTES)
        val rc = sodium.crypto_box_open_easy(plain, ct, ct.size.toLong(), nonce, peerPubkey, ownSecret)
        if (rc != 0) throw DecryptError.AuthFailed
        return plain
    }
}
