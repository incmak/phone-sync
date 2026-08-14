package co.twinotify.core.service

import co.twinotify.core.call.CallCaptureDecision
import co.twinotify.core.call.CallCapturePolicy
import co.twinotify.core.call.CallSourceCapabilities
import co.twinotify.core.call.CallFrameworkState
import co.twinotify.core.call.CallStateCoordinator
import co.twinotify.core.call.CallStateSource
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.CompletableDeferred
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class CallCaptureLifecycleTest {
    @Test
    fun disablingRuntimeStopsCoordinatorBeforeQueuedCallbackCanPersist() = runTest {
        val source = FakeSource()
        val events = mutableListOf<co.twinotify.core.call.CallStateEvent>()
        val coordinator = CallStateCoordinator(source, { events += it }, dispatcher = StandardTestDispatcher(testScheduler))
        coordinator.start()
        source.emit(CallFrameworkState.RINGING)
        coordinator.stop()
        testScheduler.runCurrent()
        assertEquals(emptyList(), events)
        coordinator.close()
    }

    @Test
    fun disablingDuringInFlightPersistenceCancelsRetryAndReportsHealth() = runTest {
        val source = FakeSource()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val events = mutableListOf<co.twinotify.core.call.CallStateEvent>()
        lateinit var coordinator: CallStateCoordinator
        coordinator = CallStateCoordinator(
            source,
            emit = {
                entered.complete(Unit)
                release.await()
                if (coordinator.status.enabled) events += it
            },
            dispatcher = StandardTestDispatcher(testScheduler),
        )
        coordinator.start()
        source.emit(CallFrameworkState.RINGING)
        testScheduler.runCurrent()
        assertTrue(entered.isCompleted)

        SyncServiceStatus.setCallCapture(true, "call_style_deferred_no_controls")
        coordinator.close()
        SyncService.stopActiveCallCapture()
        release.complete(Unit)
        testScheduler.runCurrent()

        assertFalse(coordinator.status.enabled)
        assertEquals(emptyList(), events)
        assertFalse(SyncServiceStatus.health.value.callCaptureEnabled)
        assertEquals("call_capture_disabled", SyncServiceStatus.health.value.callCaptureHealthCode)
    }
    @Test
    fun disabledSettingNeverStartsSource() {
        assertIs<CallCaptureDecision.Disabled>(
            CallCapturePolicy.decide(false, CallSourceCapabilities(supported = true, permissionGranted = true)),
        ).also { assertEquals("call_capture_disabled", it.code) }
    }

    @Test
    fun enabledSettingRequiresSupportedTelephonyAndPermission() {
        assertEquals(
            "call_telephony_unsupported",
            assertIs<CallCaptureDecision.Disabled>(
                CallCapturePolicy.decide(true, CallSourceCapabilities(supported = false, permissionGranted = true)),
            ).code,
        )
        assertEquals(
            "call_permission_denied",
            assertIs<CallCaptureDecision.Disabled>(
                CallCapturePolicy.decide(true, CallSourceCapabilities(supported = true, permissionGranted = false)),
            ).code,
        )
        assertIs<CallCaptureDecision.Start>(
            CallCapturePolicy.decide(true, CallSourceCapabilities(supported = true, permissionGranted = true)),
        )
    }

    private class FakeSource : CallStateSource {
        private var callback: ((CallFrameworkState) -> Unit)? = null
        override fun capabilities() = CallSourceCapabilities(true, true)
        override fun register(listener: (CallFrameworkState) -> Unit): AutoCloseable {
            callback = listener
            return AutoCloseable { callback = null }
        }
        fun emit(state: CallFrameworkState) { callback?.invoke(state) }
    }
}
