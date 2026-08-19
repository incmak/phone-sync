package co.twinotify.core.call

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue
import java.util.concurrent.CancellationException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
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

    @Test
    fun quiesceUnregistersThenWaitsForEnteredCallbackAndIgnoresStaleCallback() = runTest {
        val source = FakeSource()
        val emitEntered = CompletableDeferred<Unit>()
        val releaseEmit = CompletableDeferred<Unit>()
        val terminalizeStarted = CompletableDeferred<Unit>()
        val delivered = mutableListOf<CallStateEvent>()
        val coordinator = CallStateCoordinator(
            source = source,
            emit = { event ->
                emitEntered.complete(Unit)
                releaseEmit.await()
                delivered += event
            },
            sessionIdFactory = { SESSION_ID },
            dispatcher = StandardTestDispatcher(testScheduler),
        )

        coordinator.start()
        source.emit(CallFrameworkState.RINGING)
        testScheduler.runCurrent()
        emitEntered.await()

        val shutdown = async {
            coordinator.quiesceAndTerminalize {
                terminalizeStarted.complete(Unit)
            }
        }
        testScheduler.runCurrent()

        assertEquals(1, source.closeCount)
        assertFalse(coordinator.status.enabled)
        assertFalse(terminalizeStarted.isCompleted)

        source.emitStale(CallFrameworkState.OFFHOOK)
        releaseEmit.complete(Unit)
        shutdown.await()
        testScheduler.runCurrent()

        assertTrue(terminalizeStarted.isCompleted)
        assertEquals(listOf("ringing"), delivered.map { it.state })
        coordinator.close()
    }

    @Test
    fun quiesceDrainsPendingBeforeTerminalizingAndCleansUpAfterward() = runTest {
        val source = FakeSource()
        val order = mutableListOf<String>()
        var allowDelivery = false
        val coordinator = CallStateCoordinator(
            source = source,
            emit = {
                if (!allowDelivery) error("sink unavailable")
                order += "pending"
            },
            sessionIdFactory = { SESSION_ID },
            dispatcher = StandardTestDispatcher(testScheduler),
        )

        coordinator.start()
        coordinator.injectDebugState(CallFrameworkState.RINGING)
        allowDelivery = true

        coordinator.quiesceAndTerminalize {
            order += "terminalize"
            assertEquals(
                CallCoordinatorDebugState(registered = false, sessionId = SESSION_ID, pendingCount = 0),
                coordinator.debugState(),
            )
        }

        assertEquals(listOf("pending", "terminalize"), order)
        assertEquals(
            CallCoordinatorDebugState(registered = false, sessionId = null, pendingCount = 0),
            coordinator.debugState(),
        )
        coordinator.close()
    }

    @Test
    fun quiesceFailedPendingDeliveryRetainsStateAndCanRetryWithoutClosingTwice() = runTest {
        val source = FakeSource()
        var allowDelivery = false
        var terminalizeCalls = 0
        val coordinator = CallStateCoordinator(
            source = source,
            emit = { if (!allowDelivery) error("sink unavailable") },
            sessionIdFactory = { SESSION_ID },
            dispatcher = StandardTestDispatcher(testScheduler),
        )

        coordinator.start()
        coordinator.injectDebugState(CallFrameworkState.RINGING)

        val failure = assertFailsWith<ActiveCallRecoveryException> {
            coordinator.quiesceAndTerminalize { terminalizeCalls += 1 }
        }

        assertEquals("call_shutdown_failed", failure.code)
        assertFalse(coordinator.status.enabled)
        assertEquals(1, source.closeCount)
        assertEquals(0, terminalizeCalls)
        assertEquals(
            CallCoordinatorDebugState(registered = false, sessionId = SESSION_ID, pendingCount = 1),
            coordinator.debugState(),
        )

        allowDelivery = true
        coordinator.quiesceAndTerminalize { terminalizeCalls += 1 }

        assertEquals(1, source.closeCount)
        assertEquals(1, terminalizeCalls)
        assertEquals(
            CallCoordinatorDebugState(registered = false, sessionId = null, pendingCount = 0),
            coordinator.debugState(),
        )
        coordinator.close()
    }

    @Test
    fun quiesceFailedTerminalizerRetainsSessionAndCanRetryWithoutClosingTwice() = runTest {
        val source = FakeSource()
        val terminalFailure = IllegalStateException("terminalizer unavailable")
        val coordinator = coordinator(source, mutableListOf(), testScheduler)

        coordinator.start()
        coordinator.injectDebugState(CallFrameworkState.RINGING)

        val actual = assertFailsWith<IllegalStateException> {
            coordinator.quiesceAndTerminalize { throw terminalFailure }
        }

        assertSame(terminalFailure, actual)
        assertFalse(coordinator.status.enabled)
        assertEquals(1, source.closeCount)
        assertEquals(
            CallCoordinatorDebugState(registered = false, sessionId = SESSION_ID, pendingCount = 0),
            coordinator.debugState(),
        )

        coordinator.quiesceAndTerminalize { }

        assertEquals(1, source.closeCount)
        assertEquals(
            CallCoordinatorDebugState(registered = false, sessionId = null, pendingCount = 0),
            coordinator.debugState(),
        )
        coordinator.close()
    }

    @Test
    fun quiescePendingDeliveryCancellationPreservesIdentityEventAndState() = runTest {
        val source = FakeSource()
        val cancellation = CancellationException("cancel pending delivery")
        val attempts = mutableListOf<CallStateEvent>()
        var attempt = 0
        var terminalizeCalls = 0
        val coordinator = CallStateCoordinator(
            source = source,
            emit = { event ->
                attempts += event
                when (attempt++) {
                    0 -> error("sink unavailable")
                    1 -> throw cancellation
                }
            },
            sessionIdFactory = { SESSION_ID },
            dispatcher = StandardTestDispatcher(testScheduler),
        )

        coordinator.start()
        coordinator.injectDebugState(CallFrameworkState.RINGING)

        val actual = assertFailsWith<CancellationException> {
            coordinator.quiesceAndTerminalize { terminalizeCalls += 1 }
        }

        assertSame(cancellation, actual)
        assertSame(attempts[0], attempts[1])
        assertEquals(0, terminalizeCalls)
        assertEquals(
            CallCoordinatorDebugState(registered = false, sessionId = SESSION_ID, pendingCount = 1),
            coordinator.debugState(),
        )

        coordinator.quiesceAndTerminalize { terminalizeCalls += 1 }

        assertSame(attempts[0], attempts[2])
        assertEquals(1, terminalizeCalls)
        coordinator.close()
    }

    @Test
    fun quiesceNewEventCancellationPreservesIdentityEventAndState() = runTest {
        val source = FakeSource()
        val cancellation = CancellationException("cancel new delivery")
        val attempts = mutableListOf<CallStateEvent>()
        var cancelDelivery = true
        val coordinator = CallStateCoordinator(
            source = source,
            emit = { event ->
                attempts += event
                if (cancelDelivery) throw cancellation
            },
            sessionIdFactory = { SESSION_ID },
            dispatcher = StandardTestDispatcher(testScheduler),
        )

        coordinator.start()
        val actual = assertFailsWith<CancellationException> {
            coordinator.injectDebugState(CallFrameworkState.RINGING)
        }

        assertSame(cancellation, actual)
        assertEquals(
            CallCoordinatorDebugState(registered = true, sessionId = SESSION_ID, pendingCount = 1),
            coordinator.debugState(),
        )

        cancelDelivery = false
        coordinator.quiesceAndTerminalize { }

        assertSame(attempts[0], attempts[1])
        assertEquals(
            CallCoordinatorDebugState(registered = false, sessionId = null, pendingCount = 0),
            coordinator.debugState(),
        )
        coordinator.close()
    }

    @Test
    fun quiesceTerminalizerCancellationPreservesIdentityAndSkipsCleanup() = runTest {
        val source = FakeSource()
        val cancellation = CancellationException("cancel terminalization")
        val delivered = mutableListOf<CallStateEvent>()
        val coordinator = coordinator(source, delivered, testScheduler)

        coordinator.start()
        coordinator.injectDebugState(CallFrameworkState.RINGING)

        val actual = assertFailsWith<CancellationException> {
            coordinator.quiesceAndTerminalize { throw cancellation }
        }

        assertSame(cancellation, actual)
        assertEquals(1, delivered.size)
        assertEquals(
            CallCoordinatorDebugState(registered = false, sessionId = SESSION_ID, pendingCount = 0),
            coordinator.debugState(),
        )

        coordinator.quiesceAndTerminalize { }

        assertEquals(1, delivered.size)
        assertEquals(
            CallCoordinatorDebugState(registered = false, sessionId = null, pendingCount = 0),
            coordinator.debugState(),
        )
        coordinator.close()
    }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun quiesceInFlightCallbackFailureLeavesNoAutonomousRetry() = runTest {
        val source = FakeSource()
        val emitEntered = CompletableDeferred<Unit>()
        val releaseEmit = CompletableDeferred<Unit>()
        var attempts = 0
        var allowDelivery = false
        var terminalizeCalls = 0
        val coordinator = CallStateCoordinator(
            source = source,
            emit = {
                attempts += 1
                if (attempts == 1) {
                    emitEntered.complete(Unit)
                    releaseEmit.await()
                }
                if (!allowDelivery) error("sink unavailable")
            },
            sessionIdFactory = { SESSION_ID },
            dispatcher = StandardTestDispatcher(testScheduler),
        )

        coordinator.start()
        source.emit(CallFrameworkState.RINGING)
        testScheduler.runCurrent()
        emitEntered.await()

        val shutdownFailure = async {
            try {
                coordinator.quiesceAndTerminalize { terminalizeCalls += 1 }
                null
            } catch (error: Throwable) {
                error
            }
        }
        testScheduler.runCurrent()
        releaseEmit.complete(Unit)
        testScheduler.runCurrent()

        try {
            val failure = shutdownFailure.await()
            assertTrue(failure is ActiveCallRecoveryException)
            assertEquals("call_shutdown_failed", failure.code)
            assertFalse(coordinator.status.enabled)
            assertEquals(2, attempts)
            assertEquals(0, terminalizeCalls)
            assertEquals(
                CallCoordinatorDebugState(registered = false, sessionId = SESSION_ID, pendingCount = 1),
                coordinator.debugState(),
            )

            allowDelivery = true
            testScheduler.advanceTimeBy(251L)
            testScheduler.runCurrent()

            assertEquals(2, attempts)
            assertEquals(
                CallCoordinatorDebugState(registered = false, sessionId = SESSION_ID, pendingCount = 1),
                coordinator.debugState(),
            )

            coordinator.quiesceAndTerminalize { terminalizeCalls += 1 }

            assertEquals(3, attempts)
            assertEquals(1, terminalizeCalls)
            assertEquals(1, source.closeCount)
        } finally {
            releaseEmit.complete(Unit)
            coordinator.close()
        }
    }

    @Test
    fun quiesceStaleCallbackAfterFailedShutdownDoesNotDrainPending() = runTest {
        val source = FakeSource()
        var attempts = 0
        var allowDelivery = false
        val coordinator = CallStateCoordinator(
            source = source,
            emit = {
                attempts += 1
                if (!allowDelivery) error("sink unavailable")
            },
            sessionIdFactory = { SESSION_ID },
            dispatcher = StandardTestDispatcher(testScheduler),
        )

        coordinator.start()
        coordinator.injectDebugState(CallFrameworkState.RINGING)
        assertFailsWith<ActiveCallRecoveryException> {
            coordinator.quiesceAndTerminalize { }
        }
        assertEquals(2, attempts)
        assertEquals(
            CallCoordinatorDebugState(registered = false, sessionId = SESSION_ID, pendingCount = 1),
            coordinator.debugState(),
        )

        allowDelivery = true
        source.emitStale(CallFrameworkState.OFFHOOK)
        testScheduler.runCurrent()

        assertEquals(2, attempts)
        assertEquals(
            CallCoordinatorDebugState(registered = false, sessionId = SESSION_ID, pendingCount = 1),
            coordinator.debugState(),
        )

        coordinator.quiesceAndTerminalize { }

        assertEquals(3, attempts)
        assertEquals(
            CallCoordinatorDebugState(registered = false, sessionId = null, pendingCount = 0),
            coordinator.debugState(),
        )
        coordinator.close()
    }

    @Test
    fun quiesceConcurrentCallersCloseRegistrationOnce() = runTest {
        val firstCloseEntered = CountDownLatch(1)
        val releaseFirstClose = CountDownLatch(1)
        val secondCloseEntered = CountDownLatch(1)
        val terminalizeCalls = AtomicInteger(0)
        val source = FakeSource(closeAction = { attempt ->
            when (attempt) {
                1 -> {
                    firstCloseEntered.countDown()
                    check(releaseFirstClose.await(5, TimeUnit.SECONDS))
                }
                2 -> secondCloseEntered.countDown()
            }
        })
        val coordinator = coordinator(source, mutableListOf(), testScheduler)

        coordinator.start()
        val first = async(Dispatchers.Default) {
            coordinator.quiesceAndTerminalize { terminalizeCalls.incrementAndGet() }
        }
        assertTrue(firstCloseEntered.await(5, TimeUnit.SECONDS))
        val second = async(Dispatchers.Default) {
            coordinator.quiesceAndTerminalize { terminalizeCalls.incrementAndGet() }
        }

        val secondClosedWhileFirstWasBlocked = secondCloseEntered.await(500, TimeUnit.MILLISECONDS)
        releaseFirstClose.countDown()

        try {
            first.await()
            second.await()

            assertFalse(secondClosedWhileFirstWasBlocked)
            assertEquals(1, source.closeCount)
            assertEquals(2, terminalizeCalls.get())
        } finally {
            releaseFirstClose.countDown()
            coordinator.close()
        }
    }

    @Test
    fun quiesceThrowingRegistrationCloseRetainsHandleAndRetries() = runTest {
        val closeFailure = IllegalStateException("close failed")
        var terminalizeCalls = 0
        val source = FakeSource(closeAction = { attempt ->
            if (attempt == 1) throw closeFailure
        })
        val coordinator = coordinator(source, mutableListOf(), testScheduler)

        coordinator.start()
        coordinator.injectDebugState(CallFrameworkState.RINGING)

        val actual = assertFailsWith<IllegalStateException> {
            coordinator.quiesceAndTerminalize { terminalizeCalls += 1 }
        }

        assertSame(closeFailure, actual)
        assertFalse(coordinator.status.enabled)
        assertEquals(1, source.closeCount)
        assertEquals(0, terminalizeCalls)
        assertEquals(
            CallCoordinatorDebugState(registered = true, sessionId = SESSION_ID, pendingCount = 0),
            coordinator.debugState(),
        )

        coordinator.quiesceAndTerminalize { terminalizeCalls += 1 }

        assertEquals(2, source.closeCount)
        assertEquals(1, terminalizeCalls)
        assertEquals(
            CallCoordinatorDebugState(registered = false, sessionId = null, pendingCount = 0),
            coordinator.debugState(),
        )
        coordinator.close()
    }

    @Test
    fun quiesceRepeatedSuccessDoesNotEmitAnotherTerminalEventForEmptyJournal() = runTest {
        val source = FakeSource()
        val coordinator = coordinator(source, mutableListOf(), testScheduler)
        var committedCallPresent = true
        var terminalScans = 0
        var terminalEvents = 0

        coordinator.start()
        coordinator.injectDebugState(CallFrameworkState.RINGING)
        val terminalizeCommittedCalls: suspend () -> Unit = {
            terminalScans += 1
            if (committedCallPresent) {
                terminalEvents += 1
                committedCallPresent = false
            }
        }

        coordinator.quiesceAndTerminalize(terminalizeCommittedCalls)
        coordinator.quiesceAndTerminalize(terminalizeCommittedCalls)

        assertEquals(2, terminalScans)
        assertEquals(1, terminalEvents)
        assertEquals(1, source.closeCount)
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
        private val closeAction: ((Int) -> Unit)? = null,
    ) : CallStateSource {
        private var listener: ((CallFrameworkState) -> Unit)? = null
        private var staleListener: ((CallFrameworkState) -> Unit)? = null
        private val closeCounter = AtomicInteger(0)
        val closeCount: Int get() = closeCounter.get()
        val closed: Boolean get() = closeCount > 0

        override fun capabilities() = capability

        override fun register(listener: (CallFrameworkState) -> Unit): AutoCloseable {
            registerError?.let { throw it }
            this.listener = listener
            staleListener = listener
            return AutoCloseable {
                val attempt = closeCounter.incrementAndGet()
                closeAction?.invoke(attempt)
                this.listener = null
            }
        }

        fun emit(state: CallFrameworkState) {
            listener?.invoke(state)
        }

        fun emitStale(state: CallFrameworkState) {
            staleListener?.invoke(state)
        }
    }

    private companion object {
        const val SESSION_ID = "11111111-1111-4111-8111-111111111111"
        const val SECOND_SESSION_ID = "22222222-2222-4222-8222-222222222222"
    }
}
