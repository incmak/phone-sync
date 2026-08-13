package co.twinotify.core.service

/** Clock-isolated retry wakeup policy for an already-open relay socket. */
internal class ReliableFlushScheduler(
    private val intervalMs: Long = DEFAULT_INTERVAL_MS,
    private val nowMs: () -> Long = System::currentTimeMillis,
) {
    init { require(intervalMs > 0) { "retry interval must be positive" } }

    fun delayUntil(lastWakeAtMs: Long): Long =
        (lastWakeAtMs + intervalMs - nowMs()).coerceAtLeast(0L)

    companion object {
        const val DEFAULT_INTERVAL_MS = 5_000L
    }
}
