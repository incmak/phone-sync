package co.twinotify.core.crypto

import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Log
import java.security.KeyStore
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

/**
 * Provides a hardware-backed AES-256-GCM key stored in the Android Keystore.
 * Used to wrap libsodium secret-key material at rest (see WrappedKeys).
 *
 * The Keystore key itself never leaves the Keystore. StrongBox is attempted
 * when available (Pixel 6+ etc.) and silently falls back to TEE-backed storage.
 * On devices without even a TEE (rare — very cheap hardware, x86 emulators),
 * falls through to software Keystore.
 */
object KeystoreMaster {
    private const val KEYSTORE_NAME = "AndroidKeyStore"
    private const val ALIAS_MASTER = "twinotify.master"
    private const val TAG = "Twinotify.Keystore"

    fun getOrCreate(): SecretKey {
        val ks = KeyStore.getInstance(KEYSTORE_NAME).apply { load(null) }
        (ks.getKey(ALIAS_MASTER, null) as? SecretKey)?.let { return it }

        fun newBuilder() = KeyGenParameterSpec.Builder(
            ALIAS_MASTER,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .setUserAuthenticationRequired(false)

        // 1) Try StrongBox (tamper-resistant element — Pixel 3+/6+, some Samsung).
        //    API 28+. Some OEMs report available but throw HARDWARE_TYPE_UNAVAILABLE at generateKey().
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            runCatching {
                return generate(newBuilder().setIsStrongBoxBacked(true).build())
            }.onFailure { e ->
                Log.w(TAG, "StrongBox generation failed, trying TEE: ${e.javaClass.simpleName}: ${e.message}")
            }
            // Important: builder may retain setIsStrongBoxBacked(true) internally when reused.
            // Build a FRESH builder each attempt to guarantee the fallback doesn't re-request StrongBox.
        }

        // 2) Try hardware-backed (TEE). Default for most modern Android devices.
        runCatching {
            return generate(newBuilder().build())
        }.onFailure { e ->
            Log.w(TAG, "TEE generation failed, attempt software fallback: ${e.javaClass.simpleName}: ${e.message}")
        }

        // 3) As a last-resort fallback, retry once after clearing any partial alias state.
        //    Some OEM Keystore daemons leave a half-created alias after a failed generateKey,
        //    which then causes subsequent attempts to fail with HARDWARE_TYPE_UNAVAILABLE.
        runCatching { ks.deleteEntry(ALIAS_MASTER) }
        return try {
            generate(newBuilder().build())
        } catch (e: Throwable) {
            Log.e(TAG, "Final AES-GCM key generation failed — no supported Keystore backend on this device", e)
            throw KeyGenerationException(
                "Could not generate hardware-backed AES-GCM key. " +
                    "Cause: ${e.javaClass.simpleName}: ${e.message}. " +
                    "This is usually a device-specific Keystore/Keymaster daemon issue " +
                    "(common on some OEM MIUI/ColorOS/OxygenOS builds after OTA updates).",
                e,
            )
        }
    }

    private fun generate(spec: KeyGenParameterSpec): SecretKey {
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE_NAME).run {
            init(spec)
            generateKey()
        }
    }
}

class KeyGenerationException(message: String, cause: Throwable?) : RuntimeException(message, cause)
