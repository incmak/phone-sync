package co.twinotify.core.metrics

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first
import java.util.concurrent.TimeUnit

private val Context.metricsDs by preferencesDataStore("twinotify_metrics")

/**
 * Local counters for the Home screen. Daily counters reset at UTC midnight.
 * Latency is a rolling average over the most recent 10 samples.
 */
object MetricsStore {
    private val KEY_EPOCH_DAY     = longPreferencesKey("epoch_day")
    private val KEY_MIRRORED      = intPreferencesKey("mirrored_today")
    private val KEY_BLOCKED       = intPreferencesKey("blocked_today")
    private val KEY_LATENCIES_CSV = stringPreferencesKey("latency_samples_csv")

    private fun currentEpochDay(): Long =
        TimeUnit.MILLISECONDS.toDays(System.currentTimeMillis())

    /**
     * Reset daily counters if the stored epoch day is older than today's.
     * Latency samples are kept across days — they're a rolling signal, not a daily one.
     */
    private suspend fun maybeResetForNewDay(ctx: Context) {
        val today = currentEpochDay()
        val prefs = ctx.metricsDs.data.first()
        val stored = prefs[KEY_EPOCH_DAY] ?: -1L
        if (stored != today) {
            ctx.metricsDs.edit { e ->
                e[KEY_EPOCH_DAY] = today
                e[KEY_MIRRORED] = 0
                e[KEY_BLOCKED] = 0
            }
        }
    }

    suspend fun incrementMirrored(ctx: Context) {
        maybeResetForNewDay(ctx)
        ctx.metricsDs.edit { e -> e[KEY_MIRRORED] = (e[KEY_MIRRORED] ?: 0) + 1 }
    }

    suspend fun incrementBlocked(ctx: Context) {
        maybeResetForNewDay(ctx)
        ctx.metricsDs.edit { e -> e[KEY_BLOCKED] = (e[KEY_BLOCKED] ?: 0) + 1 }
    }

    suspend fun recordLatency(ctx: Context, latencyMs: Long) {
        if (latencyMs < 0 || latencyMs > 5 * 60_000L) return   // drop implausible values
        ctx.metricsDs.edit { e ->
            val existing = e[KEY_LATENCIES_CSV]?.split(',')
                ?.mapNotNull { it.toLongOrNull() } ?: emptyList()
            val next = (existing + latencyMs).takeLast(10)
            e[KEY_LATENCIES_CSV] = next.joinToString(",")
        }
    }

    data class Snapshot(val mirroredToday: Int, val blockedToday: Int, val latencyMs: Int)

    suspend fun snapshot(ctx: Context): Snapshot {
        maybeResetForNewDay(ctx)
        val prefs = ctx.metricsDs.data.first()
        val mirrored = prefs[KEY_MIRRORED] ?: 0
        val blocked  = prefs[KEY_BLOCKED] ?: 0
        val samples  = prefs[KEY_LATENCIES_CSV]?.split(',')
            ?.mapNotNull { it.toLongOrNull() } ?: emptyList()
        val avg = if (samples.isEmpty()) 0 else (samples.sum() / samples.size).toInt()
        return Snapshot(mirrored, blocked, avg)
    }

    suspend fun clear(ctx: Context) {
        ctx.metricsDs.edit { it.clear() }
    }
}
