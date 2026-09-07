package co.twinotify.core.storage

import android.content.Context
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import java.util.UUID
import kotlinx.coroutines.flow.first

private val Context.identityDs by preferencesDataStore("twinotify_identity")

object DeviceIdentity {
    private val KEY_DEVICE_ID = stringPreferencesKey("device_id")

    suspend fun getOrCreate(ctx: Context): String {
        ctx.identityDs.data.first()[KEY_DEVICE_ID]?.let { return it }
        var identity: String? = null
        ctx.identityDs.edit { prefs ->
            identity = prefs[KEY_DEVICE_ID] ?: ("dev-" + UUID.randomUUID().toString()).also {
                prefs[KEY_DEVICE_ID] = it
            }
        }
        return checkNotNull(identity)
    }
}
