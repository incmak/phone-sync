package co.twinotify.core.listener

import java.io.File
import org.junit.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse

class TwinotifyNotificationListenerFilterTest {
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
