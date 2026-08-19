package co.twinotify.core.call

import co.twinotify.core.storage.CanonicalNotificationState
import java.util.concurrent.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class GracefulCallCaptureShutdownTest {
    @Test
    fun successfulTerminalCustodyUsesOneImmediateAttempt() = runTest {
        var attempts = 0

        val result = gracefullyShutdownCallCapture(
            quiesceAndTerminalize = { attempts += 1 },
            reportFailure = { error("unexpected failure: $it") },
        )

        assertSame(GracefulCallShutdownResult.Completed, result)
        assertEquals(1, attempts)
    }

    @Test
    fun terminalFailuresUseExactlyThreeTimedAttemptsAndOnlyAllowlistedCodes() = runTest {
        val attemptTimes = mutableListOf<Long>()
        val reported = mutableListOf<String>()

        val result = gracefullyShutdownCallCapture(
            quiesceAndTerminalize = {
                attemptTimes += currentTime
                throw ActiveCallRecoveryException("call_recovery_stale")
            },
            reportFailure = reported::add,
        )

        assertEquals(listOf(0L, 1_000L, 2_000L), attemptTimes)
        assertEquals(List(3) { CALL_SHUTDOWN_STALE }, reported)
        assertEquals(GracefulCallShutdownResult.Failed(CALL_SHUTDOWN_STALE), result)
    }

    @Test
    fun terminalFailureCodesOutsideTheShutdownAllowlistMapToGeneric() = runTest {
        val reported = mutableListOf<String>()
        val failures = listOf(
            ActiveCallRecoveryException("call_recovery_private_detail"),
            ActiveCallRecoveryException("x".repeat(65)),
            IllegalStateException("raw database detail"),
        )

        failures.forEach { failure ->
            val result = gracefullyShutdownCallCapture(
                quiesceAndTerminalize = { throw failure },
                reportFailure = reported::add,
                delayBeforeRetry = {},
            )
            assertEquals(GracefulCallShutdownResult.Failed(CALL_SHUTDOWN_FAILED), result)
        }

        assertEquals(List(9) { CALL_SHUTDOWN_FAILED }, reported)
    }

    @Test
    fun reporterFailureIsAdvisoryAndDoesNotStopTerminalRetry() = runTest {
        var attempts = 0
        var reports = 0

        val result = gracefullyShutdownCallCapture(
            quiesceAndTerminalize = {
                attempts += 1
                if (attempts == 1) throw IllegalStateException("retry")
            },
            reportFailure = {
                reports += 1
                throw IllegalStateException("health sink unavailable")
            },
            delayBeforeRetry = {},
        )

        assertSame(GracefulCallShutdownResult.Completed, result)
        assertEquals(2, attempts)
        assertEquals(1, reports)
    }

    @Test
    fun configGetsItsOwnThreeAttemptBudgetWithoutRepeatingTerminalOrFinalization() = runTest {
        var terminalAttempts = 0
        var configAttempts = 0
        var finalizations = 0
        val configAttemptTimes = mutableListOf<Long>()

        val terminal = gracefullyShutdownCallCapture(
            quiesceAndTerminalize = { terminalAttempts += 1 },
            reportFailure = { error("unexpected terminal failure: $it") },
        )
        val config = if (terminal == GracefulCallShutdownResult.Completed) {
            persistDisabledForCallShutdown(
                persistDisabled = {
                    configAttempts += 1
                    configAttemptTimes += currentTime
                    throw IllegalStateException("config unavailable")
                },
                reportFailure = {},
            )
        } else {
            terminal
        }
        if (config == GracefulCallShutdownResult.Completed) finalizations += 1

        assertEquals(1, terminalAttempts)
        assertEquals(listOf(0L, 1_000L, 2_000L), configAttemptTimes)
        assertEquals(3, configAttempts)
        assertEquals(0, finalizations)
        assertEquals(GracefulCallShutdownResult.Failed(CALL_SHUTDOWN_FAILED), config)
    }

    @Test
    fun configFailOnceThenSuccessCompletesWithoutRepeatingTerminal() = runTest {
        var terminalAttempts = 0
        var configAttempts = 0

        val terminal = gracefullyShutdownCallCapture(
            quiesceAndTerminalize = { terminalAttempts += 1 },
            reportFailure = {},
        )
        val config = persistDisabledForCallShutdown(
            persistDisabled = {
                configAttempts += 1
                if (configAttempts == 1) throw IllegalStateException("retry")
            },
            reportFailure = {},
        )

        assertSame(GracefulCallShutdownResult.Completed, terminal)
        assertSame(GracefulCallShutdownResult.Completed, config)
        assertEquals(1, terminalAttempts)
        assertEquals(2, configAttempts)
        assertEquals(1_000L, currentTime)
    }

    @Test
    fun cancellationFromQuiescePropagatesByIdentity() = runTest {
        val cancellation = CancellationException("cancel quiesce")

        val thrown = assertFailsWith<CancellationException> {
            gracefullyShutdownCallCapture(
                quiesceAndTerminalize = { throw cancellation },
                reportFailure = { error("cancellation must not be reported") },
            )
        }

        assertSame(cancellation, thrown)
    }

    @Test
    fun cancellationFromTerminalizerPropagatesByIdentity() = runTest {
        val cancellation = CancellationException("cancel terminalizer")
        val coordinator = CallStateCoordinator(IdleSource(), emit = {})
        coordinator.start()

        val thrown = assertFailsWith<CancellationException> {
            gracefullyShutdownCallCapture(
                quiesceAndTerminalize = {
                    coordinator.quiesceAndTerminalize { throw cancellation }
                },
                reportFailure = { error("cancellation must not be reported") },
            )
        }

        assertSame(cancellation, thrown)
        coordinator.close()
    }

    @Test
    fun cancellationFromReporterPropagatesByIdentity() = runTest {
        val cancellation = CancellationException("cancel reporter")

        val thrown = assertFailsWith<CancellationException> {
            gracefullyShutdownCallCapture(
                quiesceAndTerminalize = { throw IllegalStateException("failure") },
                reportFailure = { throw cancellation },
            )
        }

        assertSame(cancellation, thrown)
    }

    @Test
    fun cancellationFromRetryDelayPropagatesByIdentity() = runTest {
        val cancellation = CancellationException("cancel delay")

        val thrown = assertFailsWith<CancellationException> {
            gracefullyShutdownCallCapture(
                quiesceAndTerminalize = { throw IllegalStateException("failure") },
                reportFailure = {},
                delayBeforeRetry = { throw cancellation },
            )
        }

        assertSame(cancellation, thrown)
    }

    @Test
    fun cancellationFromConfigPersistencePropagatesByIdentity() = runTest {
        val cancellation = CancellationException("cancel config")

        val thrown = assertFailsWith<CancellationException> {
            persistDisabledForCallShutdown(
                persistDisabled = { throw cancellation },
                reportFailure = { error("cancellation must not be reported") },
            )
        }

        assertSame(cancellation, thrown)
    }

    @Test
    fun concurrentGateCallersShareActiveDeferredAcrossObservationPaths() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        val gate = GracefulCallShutdownGate()
        val release = CompletableDeferred<Unit>()
        var activeServiceRuns = 0
        var noServiceRuns = 0

        val activeService = gate.start(
            scope,
            CallShutdownConfigIntent(disableCallCapture = true, disableService = false),
        ) {
            activeServiceRuns += 1
            release.await()
            GracefulCallShutdownResult.Completed
        }
        val noServiceFallback = gate.start(
            scope,
            CallShutdownConfigIntent(disableCallCapture = false, disableService = true),
        ) {
            noServiceRuns += 1
            GracefulCallShutdownResult.Completed
        }

        assertSame(activeService, noServiceFallback)
        testScheduler.runCurrent()
        assertEquals(1, activeServiceRuns)
        assertEquals(0, noServiceRuns)
        release.complete(Unit)
        testScheduler.runCurrent()
        assertSame(GracefulCallShutdownResult.Completed, activeService.await())
        scope.cancel()
    }

    @Test
    fun failedGateResultClearsDeferredButRetainsAdmissionUntilSuccessfulRetry() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        val gate = GracefulCallShutdownGate()
        val intent = CallShutdownConfigIntent(disableCallCapture = true, disableService = false)

        val first = gate.start(scope, intent) {
            GracefulCallShutdownResult.Failed(CALL_SHUTDOWN_FAILED)
        }
        assertTrue(gate.isReserved())
        testScheduler.runCurrent()
        assertEquals(GracefulCallShutdownResult.Failed(CALL_SHUTDOWN_FAILED), first.await())
        assertTrue(gate.isReserved())

        val releaseWaiter = async { gate.awaitRelease() }
        testScheduler.runCurrent()
        assertFalse(releaseWaiter.isCompleted)

        val retry = gate.start(scope, intent) { GracefulCallShutdownResult.Completed }
        assertNotSame(first, retry)
        testScheduler.runCurrent()
        assertSame(GracefulCallShutdownResult.Completed, retry.await())
        releaseWaiter.await()
        assertFalse(gate.isReserved())
        scope.cancel()
    }

    @Test
    fun gateReservationPrecedesWorkAndSurvivesCancellation() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        val gate = GracefulCallShutdownGate()
        val cancellation = CancellationException("cancel shared shutdown")
        var workStarted = false

        val active = gate.start(
            scope,
            CallShutdownConfigIntent(disableCallCapture = true, disableService = false),
        ) {
            workStarted = true
            throw cancellation
        }

        assertTrue(gate.isReserved())
        assertFalse(workStarted)
        testScheduler.runCurrent()
        assertTrue(active.isCancelled)
        assertTrue(gate.isReserved())
        scope.cancel()
    }

    @Test
    fun strongerConcurrentIntentRunsOneAdditionalConfigPhaseWithoutHoldingMonitor() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        val gate = GracefulCallShutdownGate()
        val firstWriteEntered = CompletableDeferred<Unit>()
        val releaseFirstWrite = CompletableDeferred<Unit>()
        val persisted = mutableListOf<CallShutdownConfigIntent>()

        val captureDisable = gate.start(
            scope,
            CallShutdownConfigIntent(disableCallCapture = true, disableService = false),
        ) {
            gate.persistMergedIntent { snapshot ->
                persisted += snapshot
                if (persisted.size == 1) {
                    firstWriteEntered.complete(Unit)
                    releaseFirstWrite.await()
                }
                GracefulCallShutdownResult.Completed
            }
        }
        testScheduler.runCurrent()
        firstWriteEntered.await()

        val serviceStop = gate.start(
            scope,
            CallShutdownConfigIntent(disableCallCapture = false, disableService = true),
        ) { error("must share the active deferred") }
        assertSame(captureDisable, serviceStop)
        assertTrue(gate.isReserved())

        releaseFirstWrite.complete(Unit)
        testScheduler.runCurrent()
        assertSame(GracefulCallShutdownResult.Completed, captureDisable.await())
        assertEquals(
            listOf(
                CallShutdownConfigIntent(disableCallCapture = true, disableService = false),
                CallShutdownConfigIntent(disableCallCapture = true, disableService = true),
            ),
            persisted,
        )
        assertFalse(gate.isReserved())
        scope.cancel()
    }

    @Test
    fun requestAfterGenerationSealQueuesOneSharedSuccessorWithoutConcurrentShutdown() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        val firstGenerationSealed = CompletableDeferred<Unit>()
        val releaseFirstCompletion = CompletableDeferred<Unit>()
        var sealedGenerations = 0
        val gate = GracefulCallShutdownGate(
            afterGenerationSealed = {
                sealedGenerations += 1
                if (sealedGenerations == 1) {
                    firstGenerationSealed.complete(Unit)
                    releaseFirstCompletion.await()
                }
            },
        )
        val persisted = mutableListOf<CallShutdownConfigIntent>()
        var firstRuns = 0
        var successorRuns = 0

        val first = gate.start(
            scope,
            CallShutdownConfigIntent(disableCallCapture = true, disableService = false),
        ) {
            firstRuns += 1
            gate.persistMergedIntent { snapshot ->
                persisted += snapshot
                GracefulCallShutdownResult.Completed
            }
        }
        testScheduler.runCurrent()
        firstGenerationSealed.await()

        val successor = gate.start(
            scope,
            CallShutdownConfigIntent(disableCallCapture = false, disableService = true),
        ) {
            successorRuns += 1
            gate.persistMergedIntent { snapshot ->
                persisted += snapshot
                GracefulCallShutdownResult.Completed
            }
        }
        val concurrentLateCaller = gate.start(
            scope,
            CallShutdownConfigIntent(disableCallCapture = false, disableService = true),
        ) { error("late callers must share the queued successor") }

        assertNotSame(first, successor)
        assertSame(successor, concurrentLateCaller)
        testScheduler.runCurrent()
        assertEquals(1, firstRuns)
        assertEquals(0, successorRuns)
        assertFalse(first.isCompleted)
        assertFalse(successor.isCompleted)
        assertTrue(gate.isReserved())

        releaseFirstCompletion.complete(Unit)
        testScheduler.runCurrent()

        assertSame(GracefulCallShutdownResult.Completed, first.await())
        assertSame(GracefulCallShutdownResult.Completed, successor.await())
        assertEquals(1, firstRuns)
        assertEquals(1, successorRuns)
        assertEquals(
            listOf(
                CallShutdownConfigIntent(disableCallCapture = true, disableService = false),
                CallShutdownConfigIntent(disableCallCapture = true, disableService = true),
            ),
            persisted,
        )
        assertFalse(gate.isReserved())
        scope.cancel()
    }

    @Test
    fun mergedIntentAlreadyCoveredDoesNotRepeatConfigPhase() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        val gate = GracefulCallShutdownGate()
        val persisted = mutableListOf<CallShutdownConfigIntent>()

        val result = gate.start(
            scope,
            CallShutdownConfigIntent(disableCallCapture = true, disableService = true),
        ) {
            gate.persistMergedIntent { snapshot ->
                persisted += snapshot
                GracefulCallShutdownResult.Completed
            }
        }
        testScheduler.runCurrent()

        assertSame(GracefulCallShutdownResult.Completed, result.await())
        assertEquals(1, persisted.size)
        scope.cancel()
    }

    @Test
    fun repeatedSuccessfulGateWorkUsesNewDeferredAndRemainsIdempotent() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        val gate = GracefulCallShutdownGate()
        val intent = CallShutdownConfigIntent(disableCallCapture = true, disableService = false)
        var terminalRows = 1
        var terminalized = 0

        suspend fun emptyJournalShutdown(): GracefulCallShutdownResult {
            terminalized += terminalRows
            terminalRows = 0
            return GracefulCallShutdownResult.Completed
        }

        val first = gate.start(scope, intent, ::emptyJournalShutdown)
        testScheduler.runCurrent()
        first.await()
        val repeated = gate.start(scope, intent, ::emptyJournalShutdown)
        assertNotSame(first, repeated)
        testScheduler.runCurrent()
        repeated.await()

        assertEquals(1, terminalized)
        scope.cancel()
    }

    @Test
    fun gracefulBoundaryCompletesOnlyAfterEveryTerminalizerRowAndRepeatFindsEmptyJournal() = runTest {
        val first = terminalState(
            "call:11111111-1111-4111-8111-111111111111",
            sequence = 2,
        )
        val second = terminalState(
            "call:22222222-2222-4222-8222-222222222222",
            sequence = 8,
        )
        val store = MutableTerminalRecoveryStore(listOf(first, second))
        val events = mutableListOf<CallStateEvent>()
        val persister = CallStatePersister(
            sink = CallStateSink { error("ordinary capture sink must not run") },
            recoverySink = CallRecoveryStateSink { event, origin ->
                events += event
                store.commitIdle(event, origin)
                CallStatePersistResult.Persisted(event.sequence, "idle-${event.sequence}")
            },
        )
        val terminalizer = ActiveCallTerminalizer(store, persister)
        var eventsSeenAtCompletion = -1

        val firstResult = gracefullyShutdownCallCapture(
            quiesceAndTerminalize = {
                terminalizer.recover(TERMINAL_ORIGIN)
                eventsSeenAtCompletion = events.size
            },
            reportFailure = { error("unexpected terminal failure: $it") },
        )
        val repeatedResult = gracefullyShutdownCallCapture(
            quiesceAndTerminalize = { terminalizer.recover(TERMINAL_ORIGIN) },
            reportFailure = { error("unexpected repeated failure: $it") },
        )

        assertSame(GracefulCallShutdownResult.Completed, firstResult)
        assertSame(GracefulCallShutdownResult.Completed, repeatedResult)
        assertEquals(2, eventsSeenAtCompletion)
        assertEquals(listOf(3L, 9L), events.map { it.sequence })
        assertTrue(store.activeRows().isEmpty())
    }

    private class IdleSource : CallStateSource {
        override fun capabilities() = CallSourceCapabilities(supported = true, permissionGranted = true)
        override fun register(listener: (CallFrameworkState) -> Unit): AutoCloseable = AutoCloseable { }
    }

    private class MutableTerminalRecoveryStore(
        rows: List<CanonicalNotificationState>,
    ) : ActiveCallRecoveryStore {
        private val rowsByCanonical = LinkedHashMap<String, CanonicalNotificationState>().apply {
            rows.forEach { put(it.canonId, it) }
        }

        override suspend fun activeLocalCalls(originDevice: String): List<CanonicalNotificationState> =
            rowsByCanonical.values.filter { it.originDevice == originDevice && it.state == "ACTIVE" }

        override suspend fun canonical(canonId: String): CanonicalNotificationState? = rowsByCanonical[canonId]

        override suspend fun nextSequence(canonId: String): Long =
            requireNotNull(rowsByCanonical[canonId]).latestSequence + 1L

        fun commitIdle(event: CallStateEvent, expectedOrigin: String) {
            val canonId = "call:${event.callSessionId}"
            val current = requireNotNull(rowsByCanonical[canonId])
            require(current.originDevice == expectedOrigin)
            require(current.state == "ACTIVE")
            require(event.sequence == current.latestSequence + 1L)
            rowsByCanonical[canonId] = current.copy(
                latestSequence = event.sequence,
                state = "CANCELLED",
                desiredPayloadJson = null,
            )
        }

        fun activeRows(): List<CanonicalNotificationState> =
            rowsByCanonical.values.filter { it.state == "ACTIVE" }
    }

    private fun terminalState(
        canonId: String,
        sequence: Long,
    ) = CanonicalNotificationState(
        canonId = canonId,
        originDevice = TERMINAL_ORIGIN,
        latestSequence = sequence,
        state = "ACTIVE",
        desiredPayloadJson =
            """{"call_session_id":"${canonId.removePrefix("call:")}","state":"ringing","direction":"incoming"}""",
        materializedSequence = sequence,
        sourceNotificationKey = null,
        mirrorLocalId = null,
        mirrorLocalTag = null,
        peerCancelPending = false,
        updatedAt = sequence,
    )

    private companion object {
        const val TERMINAL_ORIGIN = "local-device"
    }
}
