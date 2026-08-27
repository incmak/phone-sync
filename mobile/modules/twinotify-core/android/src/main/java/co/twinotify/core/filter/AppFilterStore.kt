package co.twinotify.core.filter

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

private val Context.appFilterDs by preferencesDataStore("twinotify_app_filter")

internal class AppFilterSnapshot {
    private val mutationOwner = Mutex()

    @Volatile private var cached: Set<String>? = null

    fun cachedOrEmpty(): Set<String> = cached ?: emptySet()

    suspend fun load(readCommitted: suspend () -> Set<String>): Set<String> =
        mutationOwner.withLock {
            cached ?: readCommitted().toSet().also { cached = it }
        }

    suspend fun mutate(commit: suspend () -> Set<String>): Set<String> =
        mutationOwner.withLock {
            commit().toSet().also { cached = it }
        }
}

/**
 * User-controlled per-package deny overrides. Augments the compiled-in default denylist
 * (see DenylistLoader). Packages in this set are suppressed by NotifPostBuilder regardless
 * of the hardcoded default list.
 */
object AppFilterStore {
    private val KEY_DENY = stringSetPreferencesKey("user_denylist")
    private val snapshot = AppFilterSnapshot()

    /** Non-blocking callback-path snapshot. The listener preloads this during onCreate. */
    fun cachedOrEmpty(): Set<String> = snapshot.cachedOrEmpty()

    suspend fun load(ctx: Context): Set<String> =
        snapshot.load { ctx.appFilterDs.data.first()[KEY_DENY] ?: emptySet() }

    suspend fun add(ctx: Context, pkg: String) {
        update(ctx) { it + pkg }
    }

    suspend fun remove(ctx: Context, pkg: String) {
        update(ctx) { it - pkg }
    }

    suspend fun clear(ctx: Context) {
        update(ctx) { emptySet() }
    }

    private suspend fun update(ctx: Context, transform: (Set<String>) -> Set<String>) {
        snapshot.mutate {
            ctx.appFilterDs.edit { preferences ->
                val current = preferences[KEY_DENY] ?: emptySet()
                preferences[KEY_DENY] = transform(current).toSet()
            }[KEY_DENY]?.toSet() ?: emptySet()
        }
    }
}
