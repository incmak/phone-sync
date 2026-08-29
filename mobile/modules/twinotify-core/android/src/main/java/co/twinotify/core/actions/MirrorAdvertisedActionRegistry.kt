package co.twinotify.core.actions

import co.twinotify.core.listener.NotifActionJson

/**
 * Keeps the generation bound to each PendingIntent that Android may still deliver after an
 * in-place notification update or cancellation. Android may deliver an already-issued
 * PendingIntent after either transition, so entries are memory-only capabilities that expire with
 * the wire invoke window; the origin remains authoritative and rejects stale generations.
 */
internal class MirrorAdvertisedActionRegistry(
    private val maxEntries: Int = MAX_ENTRIES,
    private val clock: () -> Long = { System.currentTimeMillis().coerceAtLeast(0L) },
) {
    private data class Key(val localTag: String, val localId: Int, val actionId: String)
    private data class Entry(val target: MirrorActionTarget, val expiresAt: Long)

    private val entries = LinkedHashMap<Key, Entry>()

    init {
        require(maxEntries > 0)
    }

    @Synchronized
    fun install(
        canonId: String,
        sequence: Long,
        localTag: String,
        localId: Int,
        actions: List<NotifActionJson>,
    ) {
        require(canonId.isNotEmpty() && sequence > 0 && localTag.isNotEmpty() && localId > 0)
        val now = clock()
        removeExpired(now)
        actions.forEach { action ->
            val key = Key(localTag, localId, action.action_id)
            entries.remove(key)
            entries[key] = Entry(
                target = MirrorActionTarget(canonId, sequence, localTag, localId, action),
                expiresAt = saturatingAdd(now, INVOKE_TTL_MS),
            )
        }
        while (entries.size > maxEntries) {
            entries.remove(entries.keys.first())
        }
    }

    @Synchronized
    fun lookup(identity: ActionInvokeIdentity): MirrorActionTarget? {
        val now = clock()
        removeExpired(now)
        return entries[Key(identity.mirrorTag, identity.mirrorId, identity.actionId)]?.target
    }

    @Synchronized
    fun purge(localTag: String, localId: Int) {
        entries.keys.removeAll { it.localTag == localTag && it.localId == localId }
    }

    private fun removeExpired(now: Long) {
        entries.entries.removeAll { it.value.expiresAt <= now }
    }

    private fun saturatingAdd(value: Long, delta: Long): Long =
        if (value > Long.MAX_VALUE - delta) Long.MAX_VALUE else value + delta

    private companion object {
        const val INVOKE_TTL_MS = 120_000L
        const val MAX_ENTRIES = 6_000
    }
}

internal object ProcessMirrorAdvertisedActions {
    private val registry = MirrorAdvertisedActionRegistry()

    fun install(
        canonId: String,
        sequence: Long,
        localTag: String,
        localId: Int,
        actions: List<NotifActionJson>,
    ) = registry.install(canonId, sequence, localTag, localId, actions)

    fun lookup(identity: ActionInvokeIdentity): MirrorActionTarget? = registry.lookup(identity)

    fun purge(localTag: String, localId: Int) = registry.purge(localTag, localId)
}
