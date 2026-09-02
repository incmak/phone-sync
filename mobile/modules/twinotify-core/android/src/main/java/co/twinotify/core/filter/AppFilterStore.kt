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

    suspend fun refresh(readCommitted: suspend () -> Set<String>): Set<String> =
        mutationOwner.withLock {
            readCommitted().toSet().also { cached = it }
        }

    suspend fun mutate(commit: suspend () -> Set<String>): Set<String> =
        mutationOwner.withLock {
            commit().toSet().also { cached = it }
        }
}

internal data class AppFilterPreferences(
    val explicitlyDenied: Set<String> = emptySet(),
    val explicitlyAllowed: Set<String> = emptySet(),
) {
    fun effective(defaultFilteredPackages: Set<String>): Set<String> =
        explicitlyDenied + (defaultFilteredPackages - explicitlyAllowed)

    fun block(pkg: String): AppFilterPreferences = copy(
        explicitlyDenied = explicitlyDenied + pkg,
        explicitlyAllowed = explicitlyAllowed - pkg,
    )

    fun allow(pkg: String): AppFilterPreferences = copy(
        explicitlyDenied = explicitlyDenied - pkg,
        explicitlyAllowed = explicitlyAllowed + pkg,
    )
}

/**
 * User-controlled package decisions plus reversible defaults. Explicit denies augment the
 * compiled-in list (see DenylistLoader); explicit allows override only category-based defaults.
 * The listener consumes one resolved immutable deny snapshot.
 */
object AppFilterStore {
    private val KEY_DENY = stringSetPreferencesKey("user_denylist")
    private val KEY_ALLOW_DEFAULT = stringSetPreferencesKey("user_allowlist")
    private val snapshot = AppFilterSnapshot()

    /** Non-blocking callback-path snapshot. The listener preloads this during onCreate. */
    fun cachedOrEmpty(): Set<String> = snapshot.cachedOrEmpty()

    suspend fun load(
        ctx: Context,
        defaultFilteredPackages: Set<String> = InstalledAppCatalog.defaultFilteredPackages(ctx),
    ): Set<String> = snapshot.refresh {
        readPreferences(ctx).effective(defaultFilteredPackages)
    }

    suspend fun add(
        ctx: Context,
        pkg: String,
        defaultFilteredPackages: Set<String> = InstalledAppCatalog.defaultFilteredPackages(ctx),
    ) {
        update(ctx, defaultFilteredPackages) { it.block(pkg) }
    }

    suspend fun remove(
        ctx: Context,
        pkg: String,
        defaultFilteredPackages: Set<String> = InstalledAppCatalog.defaultFilteredPackages(ctx),
    ) {
        update(ctx, defaultFilteredPackages) { it.allow(pkg) }
    }

    suspend fun clear(
        ctx: Context,
        defaultFilteredPackages: Set<String> = InstalledAppCatalog.defaultFilteredPackages(ctx),
    ) {
        update(ctx, defaultFilteredPackages) { AppFilterPreferences() }
    }

    private suspend fun readPreferences(ctx: Context): AppFilterPreferences {
        val preferences = ctx.appFilterDs.data.first()
        return AppFilterPreferences(
            explicitlyDenied = preferences[KEY_DENY]?.toSet() ?: emptySet(),
            explicitlyAllowed = preferences[KEY_ALLOW_DEFAULT]?.toSet() ?: emptySet(),
        )
    }

    private suspend fun update(
        ctx: Context,
        defaultFilteredPackages: Set<String>,
        transform: (AppFilterPreferences) -> AppFilterPreferences,
    ) {
        snapshot.mutate {
            val committed = ctx.appFilterDs.edit { preferences ->
                val current = AppFilterPreferences(
                    explicitlyDenied = preferences[KEY_DENY]?.toSet() ?: emptySet(),
                    explicitlyAllowed = preferences[KEY_ALLOW_DEFAULT]?.toSet() ?: emptySet(),
                )
                val next = transform(current)
                preferences[KEY_DENY] = next.explicitlyDenied.toSet()
                preferences[KEY_ALLOW_DEFAULT] = next.explicitlyAllowed.toSet()
            }
            AppFilterPreferences(
                explicitlyDenied = committed[KEY_DENY]?.toSet() ?: emptySet(),
                explicitlyAllowed = committed[KEY_ALLOW_DEFAULT]?.toSet() ?: emptySet(),
            ).effective(defaultFilteredPackages)
        }
    }
}
