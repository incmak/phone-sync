package co.twinotify.core.call

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.runTest

class CallStateCoordinatorTest {
    @Test
    fun ordering_duplicatesAndTerminalIdleAreDeterministic() = runTest {
        val source = FakeSource()
        val events = mutableListOf<CallStateEvent>()
        val coordinator = CallStateCoordinator(
            source = source,
            emit = { events += it },
            sessionIdFactory = { SESSION_ID },
            dispatcher = StandardTestDispatcher(testScheduler),
        )

        assertTrue(coordinator.start().enabled)
        source.emit(CallFrameworkState.RINGING)
        source.emit(CallFrameworkState.RINGING)
        source.emit(CallFrameworkState.OFFHOOK)
        source.emit(CallFrameworkState.OFFHOOK)
        source.emit(CallFrameworkState.IDLE)
        source.emit(CallFrameworkState.IDLE)
        testScheduler.runCurrent()

        assertEquals(listOf("ringing", "active", "idle"), events.map { it.state })
        assertEquals(listOf(1L, 2L, 3L), events.map { it.sequence })
        assertEquals(listOf(SESSION_ID, SESSION_ID, SESSION_ID), events.map { it.callSessionId })
        assertTrue(events.all { it.direction == CallDirection.INCOMING })
        coordinator.close()
    }

    @Test
    fun idleBeforeAnySessionIsIgnoredAndNextCallStartsAtOne() = runTest {
        val source = FakeSource()
        val events = mutableListOf<CallStateEvent>()
        val coordinator = coordinator(source, events, testScheduler)

        coordinator.start()
        source.emit(CallFrameworkState.IDLE)
        source.emit(CallFrameworkState.OFFHOOK)
        testScheduler.runCurrent()

        assertEquals(listOf("active"), events.map { it.state })
        assertEquals(1L, events.single().sequence)
        assertEquals(CallDirection.OUTGOING, events.single().direction)
        coordinator.close()
    }

    @Test
    fun stopUnregistersAndDropsCallbacksAlreadyQueued() = runTest {
        val source = FakeSource()
        val events = mutableListOf<CallStateEvent>()
        val coordinator = coordinator(source, events, testScheduler)

        coordinator.start()
        source.emit(CallFrameworkState.RINGING)
        coordinator.stop()
        testScheduler.runCurrent()

        assertTrue(source.closed)
        assertTrue(events.isEmpty())
        assertFalse(coordinator.status.enabled)
        coordinator.close()
    }

    @Test
    fun permissionAndCapabilityFailuresAreTruthful() {
        val permissionDenied = FakeSource(CallSourceCapabilities(supported = true, permissionGranted = false))
        val unsupported = FakeSource(CallSourceCapabilities(supported = false, permissionGranted = true))

        assertEquals(
            CallCaptureDisabledReason.PERMISSION_DENIED,
            CallStateCoordinator(permissionDenied, {}).start().reason,
        )
        assertEquals(
            CallCaptureDisabledReason.UNSUPPORTED_TELEPHONY,
            CallStateCoordinator(unsupported, {}).start().reason,
        )
    }

    @Test
    fun registrationAndEmitterExceptionsDoNotCrashCoordinator() = runTest {
        val registrationFailure = FakeSource(registerError = IllegalStateException("register failed"))
        val failed = CallStateCoordinator(registrationFailure, {})
        assertEquals(CallCaptureDisabledReason.CALLBACK_REGISTRATION_FAILED, failed.start().reason)
        assertEquals("call_callback_registration_failed", failed.status.lastErrorCode)

        val source = FakeSource()
        var attempts = 0
        val delivered = mutableListOf<CallStateEvent>()
        val coordinator = CallStateCoordinator(
            source = source,
            emit = {
                attempts += 1
                if (attempts == 1) error("sink failed")
                delivered += it
            },
            sessionIdFactory = { SESSION_ID },
            dispatcher = StandardTestDispatcher(testScheduler),
        )
        coordinator.start()
        source.emit(CallFrameworkState.RINGING)
        source.emit(CallFrameworkState.OFFHOOK)
        testScheduler.runCurrent()
        assertEquals(3, attempts)
        assertEquals(listOf(1L, 2L), delivered.map { it.sequence })
        assertEquals(listOf("ringing", "active"), delivered.map { it.state })
        assertTrue(coordinator.status.enabled)
        assertEquals("call_callback_emit_failed", coordinator.status.lastErrorCode)
        coordinator.close()
    }

    private fun coordinator(
        source: FakeSource,
        events: MutableList<CallStateEvent>,
        scheduler: TestCoroutineScheduler,
    ) =
        CallStateCoordinator(
            source = source,
            emit = { events += it },
            sessionIdFactory = { SESSION_ID },
            dispatcher = StandardTestDispatcher(scheduler),
        )

    private class FakeSource(
        private val capability: CallSourceCapabilities = CallSourceCapabilities(true, true),
        private val registerError: Throwable? = null,
    ) : CallStateSource {
        private var listener: ((CallFrameworkState) -> Unit)? = null
        var closed = false

        override fun capabilities() = capability

        override fun register(listener: (CallFrameworkState) -> Unit): AutoCloseable {
            registerError?.let { throw it }
            this.listener = listener
            return AutoCloseable {
                closed = true
                this.listener = null
            }
        }

        fun emit(state: CallFrameworkState) {
            listener?.invoke(state)
        }
    }

    private companion object {
        const val SESSION_ID = "11111111-1111-4111-8111-111111111111"
    }
}
