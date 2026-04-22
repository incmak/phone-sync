package co.twinotify.core.crypto

import com.goterl.lazysodium.LazySodiumAndroid
import com.goterl.lazysodium.SodiumAndroid
import com.goterl.lazysodium.interfaces.Box
import com.goterl.lazysodium.interfaces.Sign
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec

data class BoxKeyPair(val publicKey: ByteArray, val secretKey: ByteArray)
data class SignKeyPair(val publicKey: ByteArray, val secretKey: ByteArray)
data class Sealed(val ciphertext: ByteArray, val iv: ByteArray)

/**
 * libsodium X25519 (Box) and Ed25519 (Sign) keypair generation + Keystore AES-GCM seal/unseal.
 * Box public keys are 32 bytes, secret keys are 32 bytes.
 * Sign public keys are 32 bytes, secret keys are 64 bytes (seed+pubkey — libsodium format).
 */
object WrappedKeys {
    private val ls = LazySodiumAndroid(SodiumAndroid())
    private val sodium = ls.sodium

    fun generateBox(): BoxKeyPair {
        val pk = ByteArray(Box.PUBLICKEYBYTES)
        val sk = ByteArray(Box.SECRETKEYBYTES)
        val rc = sodium.crypto_box_keypair(pk, sk)
        check(rc == 0) { "crypto_box_keypair rc=$rc" }
        return BoxKeyPair(pk, sk)
    }

    fun generateSign(): SignKeyPair {
        val pk = ByteArray(Sign.PUBLICKEYBYTES)
        val sk = ByteArray(Sign.SECRETKEYBYTES)
        val rc = sodium.crypto_sign_keypair(pk, sk)
        check(rc == 0) { "crypto_sign_keypair rc=$rc" }
        return SignKeyPair(pk, sk)
    }

    fun seal(plaintext: ByteArray): Sealed {
        // Android Keystore requires Keystore-generated IVs for AES-GCM on API 28+
        // (caller-provided IVs raise "caller-provided IV not permitted" on newer MIUI/AOSP).
        // Let Keystore generate the IV by calling init() without GCMParameterSpec, then read it back.
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, KeystoreMaster.getOrCreate())
        val ct = cipher.doFinal(plaintext)
        return Sealed(ct, cipher.iv)
    }

    fun unseal(sealed: Sealed): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, KeystoreMaster.getOrCreate(), GCMParameterSpec(128, sealed.iv))
        return cipher.doFinal(sealed.ciphertext)
    }
}
