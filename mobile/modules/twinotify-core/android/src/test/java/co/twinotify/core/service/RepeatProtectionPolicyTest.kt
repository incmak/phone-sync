package co.twinotify.core.service

import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Test

class RepeatProtectionPolicyTest {
    private fun observe(times: List<Long>): RepeatProtectionState = times.fold(RepeatProtectionState()) { state, time ->
        RepeatProtectionPolicy.observe(state, "$time", time)
    }

    @Test fun threeRevisionsInsideFifteenSecondsSnoozeForOneMinute() {
        val state = observe(listOf(1_000, 8_000, 16_000))
        assertEquals(76_000, state.snoozeUntil)
        assertTrue(state.suppress)
        assertFalse(observe(listOf(1_000, 8_000, 16_001)).suppress)
    }

    @Test fun retriesAndActionRefreshesDoNotCountAsNewNotifications() {
        val state = observe(listOf(1_000))
        assertEquals(state, RepeatProtectionPolicy.observe(state, "1000", 2_000))
    }

    @Test fun uninterruptedUpdatesBlockAtTheEndOfTheMinute() {
        val state = observe((0L..65_000L step 5_000L).toList())
        assertFalse(state.blocked)
        assertTrue(RepeatProtectionPolicy.expire(state, 70_000).blocked)
    }

    @Test fun stoppingOrPausingDuringSnoozeRestoresTheNotification() {
        assertFalse(RepeatProtectionPolicy.expire(observe(listOf(0, 5_000, 10_000)), 70_000).suppress)
        val paused = observe(listOf(0, 5_000, 10_000, 40_000, 50_000, 60_000, 70_000))
        assertFalse(paused.suppress)
    }

    @Test fun lateWakeDoesNotBlockAnOldBurst() {
        val state = observe((0L..65_000L step 5_000L).toList())
        assertFalse(RepeatProtectionPolicy.expire(state, 100_000).suppress)
    }

    @Test fun unrelatedIdentitiesAndManualOverridesRemainIndependent() {
        val blocked = RepeatProtectionPolicy.expire(observe((0L..70_000L step 5_000L).toList()), 70_000)
        assertTrue(blocked.blocked)
        assertFalse(observe(listOf(70_000)).suppress)
        val exempt = RepeatProtectionState(exempt = true)
        assertEquals(exempt, RepeatProtectionPolicy.observe(exempt, "new", 80_000))
    }

    @Test fun staleRestorationCannotOverwriteANewerPostAndProtocolUpgradeIsAllowed() {
        assertTrue(RepeatProtectionPolicy.isOlderRevision("7", "6"))
        assertFalse(RepeatProtectionPolicy.isOlderRevision("7", "7"))
        assertTrue(RepeatProtectionPolicy.isOlderRevision("legacy:2000", "legacy:1000"))
        assertFalse(RepeatProtectionPolicy.isOlderRevision("legacy:2000", "1"))
    }

    @Test fun clockRollbackStartsAFreshWindow() {
        val snoozed = observe(listOf(100_000, 105_000, 110_000))
        assertFalse(RepeatProtectionPolicy.observe(snoozed, "new", 1_000).suppress)
    }
}
