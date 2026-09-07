package co.twinotify.core.crypto

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.byteArrayPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
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

    suspend fun next(ctx: Context): ByteArray = next(ctx.nonceDs)

    internal suspend fun next(store: DataStore<Preferences>): ByteArray {
        var allocated: ByteArray? = null
        store.edit { prefs ->
            val savedPrefix = prefs[KEY_PREFIX]
            val savedCounter = prefs[KEY_COUNTER]
            check((savedPrefix == null) == (savedCounter == null)) { "nonce_storage_inconsistent" }
            val prefix = savedPrefix ?: ByteArray(16).also { SecureRandom().nextBytes(it) }
            check(prefix.size == 16) { "nonce_prefix_invalid" }
            val previous = savedCounter ?: 0L
            check(previous >= 0L && previous < Long.MAX_VALUE) { "nonce_counter_exhausted" }
            val next = previous + 1L
            prefs[KEY_PREFIX] = prefix
            prefs[KEY_COUNTER] = next
            allocated = encode(prefix, next)
        }
        return checkNotNull(allocated)
    }

    /** Shared wire layout, also exercised by the cross-platform known-answer test. */
    internal fun encode(prefix: ByteArray, counter: Long): ByteArray {
        val nonce = ByteArray(24)
        System.arraycopy(prefix, 0, nonce, 0, 16)
        ByteBuffer.wrap(nonce, 16, 8).putLong(counter)
        return nonce
    }

    /** Full identity reset only. Ordinary peer removal must preserve this allocator, even for the last peer. */
    suspend fun regenerate(ctx: Context) {
        ctx.nonceDs.edit { it.clear() }
    }
}
