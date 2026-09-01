package co.twinotify.core.listener

import java.io.File
import org.junit.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TwinotifyNotificationListenerFilterTest {
    @Test
    fun outboundCaptureFailsClosedForEveryTwinotifyAuthoredNotification() {
        assertFalse(shouldCaptureOutbound("co.twinotify.app", "co.twinotify.app"))
        assertTrue(shouldCaptureOutbound("com.example.messages", "co.twinotify.app"))

        val bridge = File(
            System.getProperty("user.dir"),
            "src/main/java/co/twinotify/core/listener/NotificationListenerBridge.kt",
        ).readText()
        assertContains(bridge, "shouldCaptureOutbound(it.packageName, selfPackage)")

        val listener = File(
            System.getProperty("user.dir"),
            "src/main/java/co/twinotify/core/listener/TwinotifyNotificationListener.kt",
        ).readText()
        val capture = listener.substringAfter("private fun capturePosted").substringBefore("override fun onNotificationRemoved")
        assertContains(capture, "if (!shouldCaptureOutbound(sbn.packageName, packageName)) return")
    }

    @Test
    fun callbackUnionsCompiledAndUserSnapshotsWithoutReadingPersistence() {
        val source = File(
            System.getProperty("user.dir"),
            "src/main/java/co/twinotify/core/listener/TwinotifyNotificationListener.kt",
        ).readText()
        val callback = source
            .substringAfter("private fun capturePosted")
            .substringBefore("override fun onNotificationRemoved")

        assertContains(callback, "denylist = denylist + AppFilterStore.cachedOrEmpty()")
        assertFalse(callback.contains("AppFilterStore.load"))
        assertFalse(callback.contains("runBlocking"))
        assertFalse(callback.contains("appFilterDs"))
    }

}
