package expo.modules.phonesynccore.storage

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first

private val Context.replayDs by preferencesDataStore("phonesync_replay")
private const val TTL_MS = 48L * 60 * 60 * 1000

/**
 * msg_id dedup table with 48h TTL. First sighting → false (not seen, mark it).
 * Repeat within TTL → true (drop). After TTL → false again (replay window closed).
 *
 * Implementation: DataStore preference key "m_<msg_id>" → Long(timestamp_ms).
 * GC: opportunistic sweep on every call, removes entries older than TTL.
 */
object ReplayGuard {
    private fun keyFor(msgId: String) = longPreferencesKey("m_$msgId")

    suspend fun seenOrMark(
        ctx: Context,
        msgId: String,
        nowMs: Long = System.currentTimeMillis()
    ): Boolean {
        val key = keyFor(msgId)
        val prior = ctx.replayDs.data.first()[key]
        if (prior != null && nowMs - prior < TTL_MS) {
            // Still within replay window — DO NOT refresh timestamp; expiry based on first sighting.
            return true
        }
        ctx.replayDs.edit { e ->
            // Record new sighting
            e[key] = nowMs
            // Opportunistic GC: evict entries older than TTL
            val toEvict = e.asMap().keys
                .filterIsInstance<Preferences.Key<Long>>()
                .filter { k ->
                    k.name.startsWith("m_") &&
                        (e[longPreferencesKey(k.name)]?.let { nowMs - it > TTL_MS } ?: false)
                }
            toEvict.forEach { e.remove(it) }
        }
        return false
    }

    suspend fun clear(ctx: Context) {
        ctx.replayDs.edit { it.clear() }
    }
}
