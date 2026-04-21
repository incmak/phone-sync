package co.twinotify.core.crypto

import android.content.Context
import androidx.datastore.preferences.core.byteArrayPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first
import java.nio.ByteBuffer
import java.security.SecureRandom

private val Context.nonceDs by preferencesDataStore("twinotify_nonce")

/**
 * 24-byte hybrid nonce for libsodium crypto_box_easy.
 * Layout: 16 random bytes (persisted prefix, set once at install time or on rotate)
 *       || 8 big-endian counter bytes (incremented + fsync'd before every encrypt).
 *
 * Why hybrid: pure-random 24-byte nonces would suffice in a single CSPRNG lifetime, but
 * a device restored from backup can replay CSPRNG state and produce colliding nonces,
 * which breaks XSalsa20-Poly1305 (leaks plaintext XOR + breaks MAC). The counter
 * guarantees uniqueness across restores; the random prefix prevents collision when
 * DataStore is wiped / rotated (fresh prefix → fresh counter space).
 */
object NonceSource {
    private val KEY_PREFIX = byteArrayPreferencesKey("prefix")
    private val KEY_COUNTER = longPreferencesKey("counter")

    suspend fun next(ctx: Context): ByteArray {
        var prefix = ctx.nonceDs.data.first()[KEY_PREFIX]
        if (prefix == null) {
            prefix = ByteArray(16).also { SecureRandom().nextBytes(it) }
            ctx.nonceDs.edit { e ->
                e[KEY_PREFIX] = prefix
                e[KEY_COUNTER] = 0L
            }
        }
        // Atomically bump counter
        var counter = 0L
        ctx.nonceDs.edit { e ->
            counter = (e[KEY_COUNTER] ?: 0L) + 1
            e[KEY_COUNTER] = counter
        }
        val nonce = ByteArray(24)
        System.arraycopy(prefix, 0, nonce, 0, 16)
        ByteBuffer.wrap(nonce, 16, 8).putLong(counter)
        return nonce
    }

    suspend fun regenerate(ctx: Context) {
        ctx.nonceDs.edit { it.clear() }
    }
}
