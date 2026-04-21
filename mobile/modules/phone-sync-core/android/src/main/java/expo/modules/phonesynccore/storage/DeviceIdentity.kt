package expo.modules.phonesynccore.storage

import android.content.Context
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first
import java.util.UUID

private val Context.identityDs by preferencesDataStore("phonesync_identity")

object DeviceIdentity {
    private val KEY_DEVICE_ID = stringPreferencesKey("device_id")

    suspend fun getOrCreate(ctx: Context): String {
        val existing = ctx.identityDs.data.first()[KEY_DEVICE_ID]
        if (existing != null) return existing
        val id = "dev-" + UUID.randomUUID().toString()
        ctx.identityDs.edit { it[KEY_DEVICE_ID] = id }
        return id
    }
}
