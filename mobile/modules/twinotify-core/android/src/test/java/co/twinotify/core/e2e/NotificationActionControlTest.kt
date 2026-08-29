package co.twinotify.core.e2e

import android.app.Notification
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NotificationActionControlTest {
    @Test
    fun debugTapEmulatesOnlySystemUiAutoCancelNotifications() {
        assertTrue(shouldEmulateSystemTapCancellation(Notification.FLAG_AUTO_CANCEL))
        assertTrue(shouldEmulateSystemTapCancellation(Notification.FLAG_AUTO_CANCEL or Notification.FLAG_ONLY_ALERT_ONCE))
        assertFalse(shouldEmulateSystemTapCancellation(Notification.FLAG_NO_CLEAR))
        assertFalse(shouldEmulateSystemTapCancellation(0))
    }
}
