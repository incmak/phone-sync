package co.twinotify.core.service

import kotlin.test.Test
import kotlin.test.assertEquals

class ReliableFlushSchedulerTest {
    @Test
    fun fakeClockWakesExactlyAtRetryDeadline() {
        var now = 1_000L
        val scheduler = ReliableFlushScheduler(intervalMs = 5_000L, nowMs = { now })

        assertEquals(5_000L, scheduler.delayUntil(lastWakeAtMs = now))
        now += 4_999L
        assertEquals(1L, scheduler.delayUntil(lastWakeAtMs = 1_000L))
        now += 1L
        assertEquals(0L, scheduler.delayUntil(lastWakeAtMs = 1_000L))
    }
}
