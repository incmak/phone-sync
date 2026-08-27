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

    @Test
    fun overflowRecoveryUsesTheFullListenerSnapshotAndPeerMirrorRows() {
        val source = File(
            System.getProperty("user.dir"),
            "src/main/java/co/twinotify/core/listener/TwinotifyNotificationListener.kt",
        ).readText()
        val recovery = source
            .substringAfter("private suspend fun reconcileCaptureOverflow")
            .substringBefore("private fun capturePosted")

        assertContains(recovery, "NotificationListenerBridge.activeCaptureSnapshot")
        assertContains(recovery, "reliableDao.activePeerMirrorStates(originDevice)")
        assertContains(recovery, "liveMirrorIdentities = listenerSnapshot.liveMirrorIdentities")
        assertContains(recovery, "val recoveryGeneration = coordinator.reconciliationGeneration()")
        assertContains(recovery, "clearReconciliationLatchIfCurrent(recoveryGeneration)")
        assertFalse(
            recovery.indexOf("val recoveryGeneration") > recovery.indexOf("activeCaptureSnapshot"),
            "the reconciliation lease must precede the platform snapshot",
        )
        assertFalse(recovery.contains("activeSourceSnapshots("))
    }
}
