package co.twinotify.core.service

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MaterializationRetryWakeTest {
    @Test
    fun earlierWakeReplacesLaterWakeButLaterWakeCannotMoveEarlierOne() {
        val wake = EarliestMaterializationWake()

        assertTrue(wake.claim(nowMs = 1_000L, delayMs = 9_000L))
        assertFalse(wake.claim(nowMs = 1_000L, delayMs = 10_000L))
        assertTrue(wake.claim(nowMs = 1_000L, delayMs = 5_000L))
    }

    @Test
    fun saturatedDueCannotWrapAnAlarmTriggerIntoThePast() {
        assertEquals(Long.MAX_VALUE, saturatingAlarmTriggerAt(Long.MAX_VALUE - 2L, 5L))
    }
}
