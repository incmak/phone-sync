package co.twinotify.core.filter

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first

private val Context.appFilterDs by preferencesDataStore("twinotify_app_filter")

/**
 * User-controlled per-package deny overrides. Augments the compiled-in default denylist
 * (see DenylistLoader). Packages in this set are suppressed by NotifPostBuilder regardless
 * of the hardcoded default list.
 */
object AppFilterStore {
    private val KEY_DENY = stringSetPreferencesKey("user_denylist")

    @Volatile private var cached: Set<String>? = null

    suspend fun load(ctx: Context): Set<String> {
        cached?.let { return it }
        val set = ctx.appFilterDs.data.first()[KEY_DENY] ?: emptySet()
        cached = set
        return set
    }

    suspend fun add(ctx: Context, pkg: String) {
        ctx.appFilterDs.edit { it[KEY_DENY] = (it[KEY_DENY] ?: emptySet()) + pkg }
        cached = null
    }

    suspend fun remove(ctx: Context, pkg: String) {
        ctx.appFilterDs.edit { it[KEY_DENY] = (it[KEY_DENY] ?: emptySet()) - pkg }
        cached = null
    }

    suspend fun clear(ctx: Context) {
        ctx.appFilterDs.edit { it[KEY_DENY] = emptySet() }
        cached = null
    }
}
