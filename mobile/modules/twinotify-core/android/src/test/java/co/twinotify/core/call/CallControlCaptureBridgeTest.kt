package co.twinotify.core.call

import android.app.Notification
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

class CallControlCaptureBridgeTest {
    @Test
    fun notificationBeforeTelephonyIsRetainedThenPublished() {
        val harness = Harness()

        assertFalse(harness.session.onPosted(harness.ringingCandidate()))
        assertTrue(harness.publications.isEmpty())

        harness.current = CallControlSessionSnapshot(CallFrameworkState.RINGING, CallDirection.INCOMING)
        harness.session.onCallStateChanged()

        assertEquals(listOf("dialer-key"), harness.publications.map { it.first })
    }

    @Test
    fun telephonyBeforeNotificationAcceptsExactCandidateAndDuplicateDoesNotRepublish() {
        val harness = Harness().apply {
            current = CallControlSessionSnapshot(CallFrameworkState.RINGING, CallDirection.INCOMING)
        }
        val candidate = harness.ringingCandidate()

        assertTrue(harness.session.onPosted(candidate))
        assertTrue(harness.session.onPosted(candidate))

        assertEquals(1, harness.publications.size)
    }

    @Test
    fun ambiguityRemovalIdleDisableAndProcessClearPublishNoStaleGeneration() {
        val harness = Harness().apply {
            current = CallControlSessionSnapshot(CallFrameworkState.RINGING, CallDirection.INCOMING)
        }
        assertTrue(harness.session.onPosted(harness.ringingCandidate()))

        assertFalse(harness.session.onPosted(harness.ringingCandidate("second-key")))
        assertEquals(1, harness.clearCount)

        harness.session.onRemoved("second-key")
        assertEquals(2, harness.publications.size)

        harness.current = null
        harness.session.onCallStateChanged()
        assertEquals(2, harness.clearCount)

        harness.session.clear()
        assertEquals(3, harness.clearCount)
        assertFalse(harness.session.onPosted(harness.ringingCandidate()))
        harness.session.clear()
    }

    @Test
    fun bridgeAttachDetachRoutesOnlyToCurrentSink() {
        val first = RecordingSink()
        val second = RecordingSink()
        val candidate = CallCapabilityCandidate<android.app.PendingIntent>(
            sourceKey = "dialer-key",
            packageName = "com.android.dialer",
            category = Notification.CATEGORY_CALL,
            answer = null,
            decline = null,
            hangUp = null,
        )
        try {
            CallControlCaptureBridge.attach(first)
            assertTrue(CallControlCaptureBridge.posted(candidate))
            CallControlCaptureBridge.attach(second)
            CallControlCaptureBridge.removed("dialer-key")
            CallControlCaptureBridge.detach(first)
            assertTrue(CallControlCaptureBridge.posted(candidate))

            assertEquals(1, first.posts)
            assertEquals(listOf("dialer-key"), second.removals)
            assertEquals(1, second.posts)
        } finally {
            CallControlCaptureBridge.detach(first)
            CallControlCaptureBridge.detach(second)
        }
    }

    @Test
    fun captureGateRequiresServiceCallStateAndControlConsent() {
        assertTrue(callControlCaptureEnabled(serviceEnabled = true, callStateEnabled = true, controlsEnabled = true))
        assertFalse(callControlCaptureEnabled(serviceEnabled = false, callStateEnabled = true, controlsEnabled = true))
        assertFalse(callControlCaptureEnabled(serviceEnabled = true, callStateEnabled = false, controlsEnabled = true))
        assertFalse(callControlCaptureEnabled(serviceEnabled = true, callStateEnabled = true, controlsEnabled = false))
    }

    @Test
    fun orderedMutationLaunchQueuesLaterClearBehindEnteredRefresh() = runTest {
        val mutex = Mutex()
        val refreshEntered = CompletableDeferred<Unit>()
        val releaseRefresh = CompletableDeferred<Unit>()
        val order = mutableListOf<String>()

        launchOrderedCallControlMutation(this) {
            mutex.withLock {
                order += "refresh"
                refreshEntered.complete(Unit)
                releaseRefresh.await()
            }
        }
        refreshEntered.await()
        launchOrderedCallControlMutation(this) {
            mutex.withLock { order += "clear" }
        }
        runCurrent()
        assertEquals(listOf("refresh"), order)

        releaseRefresh.complete(Unit)
        runCurrent()
        assertEquals(listOf("refresh", "clear"), order)
    }

    @Test
    fun terminallyRejectedStateAllowsCurrentCandidateToRepublish() {
        val harness = Harness().apply {
            current = CallControlSessionSnapshot(CallFrameworkState.RINGING, CallDirection.INCOMING)
        }
        harness.session.onPosted(harness.ringingCandidate())

        harness.session.onStateCommitRejected()

        assertEquals(2, harness.publications.size)
    }

    private class RecordingSink : CallControlCaptureSink {
        var posts = 0
        val removals = mutableListOf<String>()
        override fun onPosted(snapshot: CallCapabilityCandidate<android.app.PendingIntent>): Boolean {
            posts += 1
            return true
        }

        override fun onRemoved(sourceKey: String) {
            removals += sourceKey
        }
    }

    private class Harness {
        var current: CallControlSessionSnapshot? = null
        val publications = mutableListOf<Pair<String, Map<CallControlKind, String>>>()
        var clearCount = 0
        val session = CallControlCaptureSession(
            defaultDialerPackage = { "com.android.dialer" },
            currentCall = { current },
            publish = { source, handles -> publications += source to handles },
            clearPublished = { clearCount += 1 },
        )

        fun ringingCandidate(sourceKey: String = "dialer-key") = CallCapabilityCandidate(
            sourceKey = sourceKey,
            packageName = "com.android.dialer",
            category = Notification.CATEGORY_CALL,
            answer = "answer-$sourceKey",
            decline = "decline-$sourceKey",
            hangUp = null,
        )
    }
}
