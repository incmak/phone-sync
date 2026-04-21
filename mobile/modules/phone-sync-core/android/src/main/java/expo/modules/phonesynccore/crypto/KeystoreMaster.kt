package expo.modules.phonesynccore.crypto

import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.security.keystore.StrongBoxUnavailableException
import java.security.KeyStore
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

/**
 * Provides a hardware-backed AES-256-GCM key stored in the Android Keystore.
 * Used to wrap libsodium secret-key material at rest (see WrappedKeys).
 *
 * The Keystore key itself never leaves the Keystore. StrongBox is attempted
 * when available (Pixel 6+ etc.) and silently falls back to TEE-backed storage
 * on devices without a secure element.
 */
object KeystoreMaster {
    private const val KEYSTORE_NAME = "AndroidKeyStore"
    private const val ALIAS_MASTER = "phonesync.master"

    fun getOrCreate(): SecretKey {
        val ks = KeyStore.getInstance(KEYSTORE_NAME).apply { load(null) }
        (ks.getKey(ALIAS_MASTER, null) as? SecretKey)?.let { return it }

        val baseBuilder = KeyGenParameterSpec.Builder(
            ALIAS_MASTER,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .setUserAuthenticationRequired(false)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            try {
                return generate(baseBuilder.setIsStrongBoxBacked(true).build())
            } catch (_: StrongBoxUnavailableException) {
                // Fall through to non-StrongBox generation
            } catch (_: Exception) {
                // Some OEMs throw IllegalStateException / ProviderException when StrongBox
                // is missing — fall through.
            }
        }
        return generate(baseBuilder.build())
    }

    private fun generate(spec: KeyGenParameterSpec): SecretKey {
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE_NAME).run {
            init(spec)
            generateKey()
        }
    }
}
