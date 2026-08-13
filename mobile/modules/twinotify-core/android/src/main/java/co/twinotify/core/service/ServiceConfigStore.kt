package co.twinotify.core.service

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first

private val Context.syncServiceConfigDataStore by preferencesDataStore("twinotify_service_config")

/** Native configuration that survives process death and Android's sticky service restart. */
data class ServiceConfig(
    val enabled: Boolean = false,
    val relayUrl: String? = null,
    val alwaysConnected: Boolean = true,
    val lastUserChangeAt: Long? = null,
    val revocationRequestedAt: Long? = null,
)

/** Single durable source of truth for service intent, relay endpoint, and revocation progress. */
object ServiceConfigStore {
    private val ENABLED = booleanPreferencesKey("enabled")
    private val RELAY_URL = stringPreferencesKey("relay_url")
    private val ALWAYS_CONNECTED = booleanPreferencesKey("always_connected")
    private val LAST_USER_CHANGE_AT = longPreferencesKey("last_user_change_at")
    private val REVOCATION_REQUESTED_AT = longPreferencesKey("revocation_requested_at")

    suspend fun read(ctx: Context): ServiceConfig {
        val prefs = ctx.applicationContext.syncServiceConfigDataStore.data.first()
        return ServiceConfig(
            enabled = prefs[ENABLED] ?: false,
            relayUrl = prefs[RELAY_URL],
            alwaysConnected = prefs[ALWAYS_CONNECTED] ?: true,
            lastUserChangeAt = prefs[LAST_USER_CHANGE_AT],
            revocationRequestedAt = prefs[REVOCATION_REQUESTED_AT],
        )
    }

    suspend fun setEnabled(ctx: Context, enabled: Boolean, now: Long = System.currentTimeMillis()): ServiceConfig {
        ctx.applicationContext.syncServiceConfigDataStore.edit { prefs ->
            prefs[ENABLED] = enabled
            prefs[LAST_USER_CHANGE_AT] = now
        }
        return read(ctx)
    }

    suspend fun setRelayUrl(ctx: Context, relayUrl: String, now: Long = System.currentTimeMillis()): ServiceConfig {
        val canonical = relayUrl.trim().trimEnd('/')
        require(canonical.isNotEmpty()) { "relay URL must not be empty" }
        ctx.applicationContext.syncServiceConfigDataStore.edit { prefs ->
            prefs[RELAY_URL] = canonical
            prefs[LAST_USER_CHANGE_AT] = now
        }
        return read(ctx)
    }

    suspend fun setAlwaysConnected(ctx: Context, alwaysConnected: Boolean): ServiceConfig {
        ctx.applicationContext.syncServiceConfigDataStore.edit { prefs ->
            prefs[ALWAYS_CONNECTED] = alwaysConnected
        }
        return read(ctx)
    }

    suspend fun setRevocationRequestedAt(ctx: Context, at: Long = System.currentTimeMillis()): ServiceConfig {
        ctx.applicationContext.syncServiceConfigDataStore.edit { prefs ->
            prefs[REVOCATION_REQUESTED_AT] = at
        }
        return read(ctx)
    }

    suspend fun clearRevocationRequestedAt(ctx: Context): ServiceConfig {
        ctx.applicationContext.syncServiceConfigDataStore.edit { prefs ->
            prefs.remove(REVOCATION_REQUESTED_AT)
        }
        return read(ctx)
    }

    suspend fun clear(ctx: Context) {
        ctx.applicationContext.syncServiceConfigDataStore.edit { it.clear() }
    }
}
