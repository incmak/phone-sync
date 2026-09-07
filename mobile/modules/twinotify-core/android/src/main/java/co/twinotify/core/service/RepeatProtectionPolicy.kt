package co.twinotify.core.service

/** Receiver-local policy. A revision is counted once, including after retries/restarts. */
internal data class RepeatProtectionState(
    val revision: String = "",
    val recent: List<Long> = emptyList(),
    val lastAt: Long = 0,
    val snoozeUntil: Long = 0,
    val continuous: Boolean = true,
    val blocked: Boolean = false,
    val exempt: Boolean = false,
    val hidden: Boolean = false,
    val noticeSent: Boolean = false,
) {
    val suppress: Boolean get() = blocked || snoozeUntil != 0L
}

internal object RepeatProtectionPolicy {
    const val WINDOW_MS = 15_000L
    const val SNOOZE_MS = 60_000L

    fun isOlderRevision(current: String, incoming: String): Boolean {
        if (current.startsWith("legacy:") != incoming.startsWith("legacy:")) return false
        val before = current.substringAfter(':').toLongOrNull() ?: return false
        val after = incoming.substringAfter(':').toLongOrNull() ?: return false
        return after < before
    }

    fun observe(state: RepeatProtectionState, revision: String, now: Long): RepeatProtectionState {
        if (state.blocked || state.exempt || state.revision == revision) return state
        // Clock rollback must never create a permanent block or an arbitrarily long snooze.
        val previous = if (now < state.lastAt) RepeatProtectionState(hidden = state.hidden) else state
        if (previous.snoozeUntil != 0L) {
            val updated = previous.copy(
                revision = revision,
                lastAt = now,
                continuous = previous.continuous && now - previous.lastAt <= WINDOW_MS,
            )
            return expire(updated, now)
        }
        val recent = (previous.recent.filter { now - it <= WINDOW_MS } + now).takeLast(3)
        return previous.copy(
            revision = revision,
            recent = recent,
            lastAt = now,
            snoozeUntil = if (recent.size == 3) now + SNOOZE_MS else 0,
            continuous = true,
        )
    }

    fun expire(state: RepeatProtectionState, now: Long): RepeatProtectionState {
        if (state.snoozeUntil == 0L || now < state.snoozeUntil) return state
        val keepsRepeating = state.continuous &&
            state.lastAt >= state.snoozeUntil - WINDOW_MS &&
            now - state.lastAt <= WINDOW_MS
        return state.copy(snoozeUntil = 0, blocked = keepsRepeating, recent = emptyList())
    }
}
