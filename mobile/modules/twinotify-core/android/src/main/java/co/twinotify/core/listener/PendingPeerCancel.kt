package co.twinotify.core.listener

import java.util.concurrent.ConcurrentHashMap

object PendingPeerCancel {
    private const val TTL_MS = 30_000L
    private val entries = ConcurrentHashMap<String, Long>()

    fun add(canonId: String, nowMs: Long = System.currentTimeMillis()) {
        entries[canonId] = nowMs + TTL_MS
    }

    /** Returns true if canonId was present and not yet expired; removes on consumption. */
    fun consume(canonId: String, nowMs: Long = System.currentTimeMillis()): Boolean {
        val expiry = entries.remove(canonId) ?: return false
        return expiry > nowMs
    }

    /**
     * Non-destructive read for live entries, but silently evicts expired entries as a side effect.
     * Do not call immediately before [consume] on the same key — `contains` may evict and make
     * `consume` return false. Use `consume` directly if you plan to act on the result.
     */
    fun contains(canonId: String, nowMs: Long = System.currentTimeMillis()): Boolean {
        val expiry = entries[canonId] ?: return false
        if (expiry <= nowMs) {
            entries.remove(canonId)
            return false
        }
        return true
    }

    /** Called periodically from a coroutine in SyncService to reclaim memory. */
    fun sweep(nowMs: Long = System.currentTimeMillis()) {
        val stale = entries.entries.filter { it.value <= nowMs }.map { it.key }
        stale.forEach { entries.remove(it) }
    }

    // Test hook
    internal fun sizeForTest(): Int = entries.size
    internal fun clearForTest() { entries.clear() }
}
