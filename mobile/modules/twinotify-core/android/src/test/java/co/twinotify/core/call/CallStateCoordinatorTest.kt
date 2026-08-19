package co.twinotify.core.call

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
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

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun failedIdleSealsSessionAndNextCallUsesFreshIdentity() = runTest {
        val source = FakeSource()
        val delivered = mutableListOf<CallStateEvent>()
        val ids = ArrayDeque(listOf(SESSION_ID, SECOND_SESSION_ID))
        var allowIdle = false
        val coordinator = CallStateCoordinator(
            source = source,
            emit = { event ->
                if (event.state == "idle" && !allowIdle) error("terminal sink unavailable")
                delivered += event
            },
            sessionIdFactory = { ids.removeFirst() },
            dispatcher = StandardTestDispatcher(testScheduler),
        )

        coordinator.startForDebug()
        coordinator.injectDebugState(CallFrameworkState.RINGING)
        coordinator.injectDebugState(CallFrameworkState.IDLE)
        testScheduler.runCurrent()

        val secondRinging = coordinator.injectDebugState(CallFrameworkState.RINGING)
        coordinator.injectDebugState(CallFrameworkState.OFFHOOK)
        coordinator.injectDebugState(CallFrameworkState.IDLE)
        val duplicateIdle = coordinator.injectDebugState(CallFrameworkState.IDLE)

        try {
            assertEquals(SECOND_SESSION_ID, secondRinging?.callSessionId)
            assertEquals(1L, secondRinging?.sequence)
            assertEquals(null, duplicateIdle)

            allowIdle = true
            testScheduler.advanceTimeBy(251L)
            testScheduler.runCurrent()

            assertEquals(
                listOf(
                    SESSION_ID to "ringing",
                    SESSION_ID to "idle",
                    SECOND_SESSION_ID to "ringing",
                    SECOND_SESSION_ID to "active",
                    SECOND_SESSION_ID to "idle",
                ),
                delivered.map { it.callSessionId to it.state },
            )
            assertEquals(listOf(1L, 2L, 1L, 2L, 3L), delivered.map { it.sequence })
        } finally {
            coordinator.close()
        }
    }

    @Test
    fun offhookFirstCallAfterIdleStartsFreshOutgoingSession() = runTest {
        val source = FakeSource()
        val delivered = mutableListOf<CallStateEvent>()
        val ids = ArrayDeque(listOf(SESSION_ID, SECOND_SESSION_ID))
        val coordinator = CallStateCoordinator(
            source = source,
            emit = { delivered += it },
            sessionIdFactory = { ids.removeFirst() },
            dispatcher = StandardTestDispatcher(testScheduler),
        )

        coordinator.startForDebug()
        coordinator.injectDebugState(CallFrameworkState.RINGING)
        coordinator.injectDebugState(CallFrameworkState.IDLE)
        val secondActive = coordinator.injectDebugState(CallFrameworkState.OFFHOOK)

        try {
            assertEquals(SECOND_SESSION_ID, secondActive?.callSessionId)
            assertEquals(1L, secondActive?.sequence)
            assertEquals(CallDirection.OUTGOING, secondActive?.direction)
        } finally {
            coordinator.close()
        }
    }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun overflowPreservesOldestTerminalBarrier() = runTest {
        val source = FakeSource()
        val delivered = mutableListOf<CallStateEvent>()
        val generatedIds = (1..40).map { index ->
            "00000000-0000-4000-8000-${index.toString().padStart(12, '0')}"
        }
        val ids = ArrayDeque(listOf(SESSION_ID) + generatedIds)
        var allowDelivery = true
        val coordinator = CallStateCoordinator(
            source = source,
            emit = { event ->
                if (!allowDelivery) error("sink unavailable")
                delivered += event
            },
            sessionIdFactory = { ids.removeFirst() },
            dispatcher = StandardTestDispatcher(testScheduler),
        )

        coordinator.startForDebug()
        coordinator.injectDebugState(CallFrameworkState.RINGING)
        allowDelivery = false
        coordinator.injectDebugState(CallFrameworkState.IDLE)
        repeat(33) {
            coordinator.injectDebugState(CallFrameworkState.RINGING)
            coordinator.injectDebugState(CallFrameworkState.IDLE)
        }

        try {
            allowDelivery = true
            testScheduler.advanceTimeBy(251L)
            testScheduler.runCurrent()

            assertEquals(SESSION_ID to "ringing", delivered.first().let { it.callSessionId to it.state })
            assertEquals(SESSION_ID to "idle", delivered[1].let { it.callSessionId to it.state })
        } finally {
            coordinator.close()
        }
    }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun oldIdleRetryCompletionDoesNotClearLiveNextSession() = runTest {
        val source = FakeSource()
        val delivered = mutableListOf<CallStateEvent>()
        val ids = ArrayDeque(listOf(SESSION_ID, SECOND_SESSION_ID))
        var allowIdle = false
        val coordinator = CallStateCoordinator(
            source = source,
            emit = { event ->
                if (event.state == "idle" && !allowIdle) error("terminal sink unavailable")
                delivered += event
            },
            sessionIdFactory = { ids.removeFirst() },
            dispatcher = StandardTestDispatcher(testScheduler),
        )

        coordinator.startForDebug()
        coordinator.injectDebugState(CallFrameworkState.RINGING)
        coordinator.injectDebugState(CallFrameworkState.IDLE)
        coordinator.injectDebugState(CallFrameworkState.RINGING)

        try {
            allowIdle = true
            testScheduler.advanceTimeBy(251L)
            testScheduler.runCurrent()
            val secondActive = coordinator.injectDebugState(CallFrameworkState.OFFHOOK)

            assertEquals(SECOND_SESSION_ID, secondActive?.callSessionId)
            assertEquals(2L, secondActive?.sequence)
            assertEquals(CallDirection.INCOMING, secondActive?.direction)
        } finally {
            coordinator.close()
        }
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
        const val SECOND_SESSION_ID = "22222222-2222-4222-8222-222222222222"
    }
}
