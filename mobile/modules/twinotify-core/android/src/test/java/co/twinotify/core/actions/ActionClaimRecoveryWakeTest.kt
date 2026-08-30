package co.twinotify.core.actions

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ActionClaimRecoveryWakeTest {
    @Test
    fun onlyAnEarlierDeadlineReplacesTheCurrentWake() {
        val wake = EarliestActionClaimWake()

        assertTrue(wake.claim(70_000))
        assertFalse(wake.claim(80_000))
        assertFalse(wake.claim(70_000))
        assertTrue(wake.claim(60_000))
    }

    @Test
    fun onlyTheCurrentDeadlineCanBeConsumed() {
        val wake = EarliestActionClaimWake()
        wake.claim(60_000)

        assertFalse(wake.consume(70_000))
        assertTrue(wake.consume(60_000))
        assertFalse(wake.consume(60_000))
    }

    @Test
    fun processDeadlineSurvivesAlarmPersistenceFailure() {
        val events = mutableListOf<String>()

        armProcessDeadlineThenPersistAlarm(
            armProcessDeadline = { events += "process" },
            persistAlarm = {
                events += "alarm"
                error("AlarmManager rejected the wake")
            },
        )

        assertEquals(listOf("process", "alarm"), events)
    }
}
