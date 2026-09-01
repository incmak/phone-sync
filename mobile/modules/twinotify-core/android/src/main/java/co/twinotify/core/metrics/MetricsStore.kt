package co.twinotify.core.metrics

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import co.twinotify.core.storage.NotificationDb
import kotlinx.coroutines.flow.first

private val Context.metricsDs by preferencesDataStore("twinotify_metrics")

/**
 * Home metrics backed by durable delivery proof plus a local blocked counter.
 * “Today” follows the phone's current local civil day.
 */
object MetricsStore {
    private val KEY_LOCAL_DATE = stringPreferencesKey("local_date")
    private val KEY_BLOCKED = intPreferencesKey("blocked_today")

    private suspend fun rolloverBlocked(ctx: Context, dateKey: String) {
        ctx.metricsDs.edit { e ->
            if (e[KEY_LOCAL_DATE] != dateKey) {
                e[KEY_LOCAL_DATE] = dateKey
                e[KEY_BLOCKED] = 0
            }
        }
    }

    suspend fun incrementBlocked(ctx: Context) {
        val dateKey = localDayWindow().dateKey
        ctx.metricsDs.edit { e ->
            if (e[KEY_LOCAL_DATE] != dateKey) {
                e[KEY_LOCAL_DATE] = dateKey
                e[KEY_BLOCKED] = 0
            }
            e[KEY_BLOCKED] = (e[KEY_BLOCKED] ?: 0) + 1
        }
    }

    data class Snapshot(val mirroredToday: Int, val blockedToday: Int, val latencyMs: Int?)

    suspend fun snapshot(ctx: Context): Snapshot {
        val window = localDayWindow()
        rolloverBlocked(ctx, window.dateKey)
        val prefs = ctx.metricsDs.data.first()
        val verified = NotificationDb.get(ctx).reliableDeliveryDao().verifiedDeliverySnapshot(
            window.startInclusive,
            window.endExclusive,
        )
        return Snapshot(
            mirroredToday = verified.mirroredToday,
            blockedToday = prefs[KEY_BLOCKED] ?: 0,
            latencyMs = verified.latencyMs,
        )
    }

    suspend fun clear(ctx: Context) {
        ctx.metricsDs.edit { it.clear() }
    }
}
