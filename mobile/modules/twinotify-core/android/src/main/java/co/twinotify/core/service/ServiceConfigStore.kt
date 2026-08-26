package co.twinotify.core.service

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import co.twinotify.core.call.CallShutdownConfigIntent
import kotlinx.coroutines.flow.first

private val Context.syncServiceConfigDataStore by preferencesDataStore("twinotify_service_config")

/** Native configuration that survives process death and Android's sticky service restart. */
data class ServiceConfig(
    val enabled: Boolean = false,
    val relayUrl: String? = null,
    /** Try a direct LAN route before the relay. Defaults on: it is faster and private. */
    val preferLan: Boolean = true,
    val alwaysConnected: Boolean = true,
    val callCaptureEnabled: Boolean = false,
    val lastUserChangeAt: Long? = null,
    val revocationRequestedAt: Long? = null,
)

internal fun mergeCallShutdownIntent(
    current: ServiceConfig,
    intent: CallShutdownConfigIntent,
    now: Long,
): ServiceConfig = current.copy(
    enabled = if (intent.disableService) false else current.enabled,
    callCaptureEnabled = if (intent.disableCallCapture) false else current.callCaptureEnabled,
    lastUserChangeAt = if (intent.disableService) now else current.lastUserChangeAt,
)

/** Single durable source of truth for service intent, relay endpoint, and revocation progress. */
object ServiceConfigStore {
    private val ENABLED = booleanPreferencesKey("enabled")
    private val RELAY_URL = stringPreferencesKey("relay_url")
    private val PREFER_LAN = booleanPreferencesKey("prefer_lan")
    private val ALWAYS_CONNECTED = booleanPreferencesKey("always_connected")
    private val CALL_CAPTURE_ENABLED = booleanPreferencesKey("call_capture_enabled")
    private val LAST_USER_CHANGE_AT = longPreferencesKey("last_user_change_at")
    private val REVOCATION_REQUESTED_AT = longPreferencesKey("revocation_requested_at")

    suspend fun read(ctx: Context): ServiceConfig {
        val prefs = ctx.applicationContext.syncServiceConfigDataStore.data.first()
        return ServiceConfig(
            enabled = prefs[ENABLED] ?: false,
            relayUrl = prefs[RELAY_URL],
            preferLan = prefs[PREFER_LAN] ?: true,
            alwaysConnected = prefs[ALWAYS_CONNECTED] ?: true,
            callCaptureEnabled = prefs[CALL_CAPTURE_ENABLED] ?: false,
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

    suspend fun setPreferLan(ctx: Context, preferLan: Boolean, now: Long = System.currentTimeMillis()): ServiceConfig {
        ctx.applicationContext.syncServiceConfigDataStore.edit { prefs ->
            prefs[PREFER_LAN] = preferLan
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

    /** Atomically enable direct-only delivery and remove any retained relay endpoint. */
    suspend fun setLanOnlyEnabled(ctx: Context, now: Long = System.currentTimeMillis()): ServiceConfig {
        ctx.applicationContext.syncServiceConfigDataStore.edit { prefs ->
            prefs[ENABLED] = true
            prefs.remove(RELAY_URL)
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

    suspend fun setCallCaptureEnabled(ctx: Context, enabled: Boolean): ServiceConfig {
        ctx.applicationContext.syncServiceConfigDataStore.edit { prefs ->
            prefs[CALL_CAPTURE_ENABLED] = enabled
        }
        return read(ctx)
    }

    internal suspend fun applyCallShutdownIntent(
        ctx: Context,
        intent: CallShutdownConfigIntent,
        now: Long = System.currentTimeMillis(),
    ): ServiceConfig {
        ctx.applicationContext.syncServiceConfigDataStore.edit { prefs ->
            if (intent.disableCallCapture) prefs[CALL_CAPTURE_ENABLED] = false
            if (intent.disableService) {
                prefs[ENABLED] = false
                prefs[LAST_USER_CHANGE_AT] = now
            }
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
