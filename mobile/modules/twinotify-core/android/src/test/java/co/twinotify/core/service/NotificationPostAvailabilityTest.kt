package co.twinotify.core.service

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NotificationPostAvailabilityTest {
    @Test
    fun runtimePermissionAndGlobalNotificationEnablementAreBothRequired() {
        assertTrue(effectivePostAvailability(runtimePermissionGranted = true, notificationsEnabled = true))
        assertFalse(effectivePostAvailability(runtimePermissionGranted = false, notificationsEnabled = true))
        assertFalse(effectivePostAvailability(runtimePermissionGranted = true, notificationsEnabled = false))
    }
}
