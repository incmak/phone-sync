package co.twinotify.core

import co.twinotify.core.call.ActiveCallRecoveryException
import co.twinotify.core.call.CALL_SHUTDOWN_FAILED
import co.twinotify.core.call.CallShutdownConfigIntent
import co.twinotify.core.call.GracefulCallShutdownGate
import co.twinotify.core.call.GracefulCallShutdownResult
import co.twinotify.core.service.executeCallCaptureStopRequest
import co.twinotify.core.service.executeCallShutdownPhases
import co.twinotify.core.service.CallCaptureStopRequestGate
import co.twinotify.core.service.CallShutdownPhaseState
import java.util.concurrent.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame

@OptIn(ExperimentalCoroutinesApi::class)
class TwinotifyCoreModuleWorkflowTest {
    @Test
    fun lanOnlyConfigIsPersistedBeforeServiceAdmission() = runTest {
        val order = mutableListOf<String>()

        persistLanOnlyConfigThenStart(
            persist = { order += "persist-direct-only" },
            start = { order += "start-service" },
        )

        assertEquals(listOf("persist-direct-only", "start-service"), order)
    }

    @Test
    fun routePreferenceIsPersistedBeforeTheLiveServiceIsNotified() = runTest {
        val order = mutableListOf<String>()

        persistRoutePreferenceThenNotifyService(
            preferLan = false,
            persist = { order += "persist-$it" },
            notifyService = { order += "notify" },
        )

        assertEquals(listOf("persist-false", "notify"), order)
    }

    @Test
    fun completedShutdownReleasesAdmissionBeforeFinalization() = runTest {
        val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val gate = GracefulCallShutdownGate()
        val order = mutableListOf<String>()
        val shared = gate.start(
            scope,
            CallShutdownConfigIntent(disableCallCapture = true, disableService = false),
        ) {
            executeCallShutdownPhases(
                gate = gate,
                terminalize = {
                    order += "unregister"
                    order += "terminal-idle"
                },
                persistIntent = {
                    order += "persist-disabled"
                    assertEquals(
                        CallShutdownConfigIntent(disableCallCapture = true, disableService = false),
                        it,
                    )
                },
                reportFailure = { error("unexpected failure: $it") },
            )
        }

        executeCallCaptureStopRequest(
            sharedShutdown = { shared.await() },
            finalizeStop = {
                assertFalse(gate.isReserved())
                order += "finalize"
            },
        )

        assertEquals(
            listOf("unregister", "terminal-idle", "persist-disabled", "finalize"),
            order,
        )
        scope.cancel()
    }

    @Test
    fun terminalFailureSkipsConfigAndFinalization() = runTest {
        val gate = GracefulCallShutdownGate()
        var configRuns = 0
        var finalizations = 0

        val result = executeCallShutdownPhases(
            gate = gate,
            terminalize = { throw IllegalStateException("private terminal detail") },
            persistIntent = { configRuns += 1 },
            reportFailure = {},
        )
        val failure = assertFailsWith<ActiveCallRecoveryException> {
            executeCallCaptureStopRequest(
                sharedShutdown = { result },
                finalizeStop = { finalizations += 1 },
            )
        }

        assertEquals(CALL_SHUTDOWN_FAILED, failure.code)
        assertEquals(0, configRuns)
        assertEquals(0, finalizations)
    }

    @Test
    fun configFailureSkipsFinalizationWithoutRepeatingTerminalWithinItsAttemptBudget() = runTest {
        val gate = GracefulCallShutdownGate()
        var terminalRuns = 0
        val configTimes = mutableListOf<Long>()
        var finalizations = 0

        val result = executeCallShutdownPhases(
            gate = gate,
            terminalize = { terminalRuns += 1 },
            persistIntent = {
                configTimes += currentTime
                throw IllegalStateException("private config detail")
            },
            reportFailure = {},
        )
        assertFailsWith<ActiveCallRecoveryException> {
            executeCallCaptureStopRequest(
                sharedShutdown = { result },
                finalizeStop = { finalizations += 1 },
            )
        }

        assertEquals(1, terminalRuns)
        assertEquals(listOf(0L, 1_000L, 2_000L), configTimes)
        assertEquals(0, finalizations)
    }

    @Test
    fun explicitRetryAfterConfigExhaustionRetriesOnlyConfigThenFinalizesTruthfully() = runTest {
        val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val gate = GracefulCallShutdownGate()
        val phaseState = CallShutdownPhaseState()
        val intent = CallShutdownConfigIntent(disableCallCapture = true, disableService = false)
        var terminalRuns = 0
        val configTimes = mutableListOf<Long>()
        var finalizations = 0

        fun request() = gate.start(scope, intent) {
            executeCallShutdownPhases(
                gate = gate,
                phaseState = phaseState,
                terminalize = { terminalRuns += 1 },
                persistIntent = {
                    configTimes += currentTime
                    if (configTimes.size <= 3) throw IllegalStateException("config unavailable")
                },
                reportFailure = {},
            )
        }

        val failed = request()
        assertEquals(
            GracefulCallShutdownResult.Failed(CALL_SHUTDOWN_FAILED),
            failed.await(),
        )
        assertEquals(1, terminalRuns)
        assertEquals(listOf(0L, 1_000L, 2_000L), configTimes)
        assertEquals(0, finalizations)
        assertEquals(true, phaseState.hasTerminalCustody())
        assertEquals(true, gate.isReserved())

        val retry = request()
        executeCallCaptureStopRequest(
            sharedShutdown = { retry.await() },
            finalizeStop = {
                assertFalse(gate.isReserved())
                finalizations += 1
            },
        )

        assertEquals(1, terminalRuns)
        assertEquals(listOf(0L, 1_000L, 2_000L, 2_000L), configTimes)
        assertEquals(1, finalizations)
        assertFalse(phaseState.hasTerminalCustody())
        scope.cancel()
    }

    @Test
    fun explicitRetryAfterTerminalExhaustionRerunsTerminalCustody() = runTest {
        val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val gate = GracefulCallShutdownGate()
        val phaseState = CallShutdownPhaseState()
        val intent = CallShutdownConfigIntent(disableCallCapture = true, disableService = false)
        var terminalRuns = 0
        var configRuns = 0

        fun request() = gate.start(scope, intent) {
            executeCallShutdownPhases(
                gate = gate,
                phaseState = phaseState,
                terminalize = {
                    terminalRuns += 1
                    if (terminalRuns <= 3) throw IllegalStateException("terminal unavailable")
                },
                persistIntent = { configRuns += 1 },
                reportFailure = {},
            )
        }

        assertEquals(
            GracefulCallShutdownResult.Failed(CALL_SHUTDOWN_FAILED),
            request().await(),
        )
        assertFalse(phaseState.hasTerminalCustody())
        assertEquals(3, terminalRuns)
        assertEquals(0, configRuns)

        assertSame(GracefulCallShutdownResult.Completed, request().await())
        assertEquals(4, terminalRuns)
        assertEquals(1, configRuns)
        assertFalse(phaseState.hasTerminalCustody())
        scope.cancel()
    }

    @Test
    fun completedServiceStopDoesNotStrengthenLaterIndependentCaptureOnlyGeneration() = runTest {
        val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val gate = GracefulCallShutdownGate()
        val persisted = mutableListOf<CallShutdownConfigIntent>()

        suspend fun request(intent: CallShutdownConfigIntent): GracefulCallShutdownResult =
            gate.start(scope, intent) {
                executeCallShutdownPhases(
                    gate = gate,
                    terminalize = {},
                    persistIntent = { persisted += it },
                    reportFailure = { error("unexpected failure: $it") },
                )
            }.await()

        val serviceStop = CallShutdownConfigIntent(
            disableCallCapture = false,
            disableService = true,
        )
        val captureOnly = CallShutdownConfigIntent(
            disableCallCapture = true,
            disableService = false,
        )

        assertSame(GracefulCallShutdownResult.Completed, request(serviceStop))
        assertFalse(gate.isReserved())
        assertSame(GracefulCallShutdownResult.Completed, request(captureOnly))
        assertFalse(gate.isReserved())

        assertEquals(listOf(serviceStop, captureOnly), persisted)
        scope.cancel()
    }

    @Test
    fun configFailOnceThenSuccessUsesOnlyZeroAndOneSecondAttempts() = runTest {
        val gate = GracefulCallShutdownGate()
        var terminalRuns = 0
        val configTimes = mutableListOf<Long>()

        val result = executeCallShutdownPhases(
            gate = gate,
            terminalize = { terminalRuns += 1 },
            persistIntent = {
                configTimes += currentTime
                if (configTimes.size == 1) throw IllegalStateException("retry")
            },
            reportFailure = {},
        )

        assertSame(GracefulCallShutdownResult.Completed, result)
        assertEquals(1, terminalRuns)
        assertEquals(listOf(0L, 1_000L), configTimes)
    }

    @Test
    fun terminalCancellationSkipsConfigAndFinalizationByIdentity() = runTest {
        val cancellation = CancellationException("cancel terminal")
        val gate = GracefulCallShutdownGate()
        var configRuns = 0
        var finalizations = 0

        val thrown = assertFailsWith<CancellationException> {
            executeCallCaptureStopRequest(
                sharedShutdown = {
                    executeCallShutdownPhases(
                        gate = gate,
                        terminalize = { throw cancellation },
                        persistIntent = { configRuns += 1 },
                        reportFailure = { error("cancellation must not be reported") },
                    )
                },
                finalizeStop = { finalizations += 1 },
            )
        }

        assertSame(cancellation, thrown)
        assertEquals(0, configRuns)
        assertEquals(0, finalizations)
    }

    @Test
    fun configCancellationSkipsFinalizationByIdentityWithoutRepeatingTerminal() = runTest {
        val cancellation = CancellationException("cancel config")
        val gate = GracefulCallShutdownGate()
        var terminalRuns = 0
        var finalizations = 0

        val thrown = assertFailsWith<CancellationException> {
            executeCallCaptureStopRequest(
                sharedShutdown = {
                    executeCallShutdownPhases(
                        gate = gate,
                        terminalize = { terminalRuns += 1 },
                        persistIntent = { throw cancellation },
                        reportFailure = { error("cancellation must not be reported") },
                    )
                },
                finalizeStop = { finalizations += 1 },
            )
        }

        assertSame(cancellation, thrown)
        assertEquals(1, terminalRuns)
        assertEquals(0, finalizations)
    }

    @Test
    fun repeatedActionStopRequestsShareOneActiveJobAndOneFinalization() = runTest {
        val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val gate = CallCaptureStopRequestGate()
        val entered = kotlinx.coroutines.CompletableDeferred<Unit>()
        val release = kotlinx.coroutines.CompletableDeferred<Unit>()
        var runs = 0
        var finalizations = 0

        val first = gate.start(scope) {
            runs += 1
            entered.complete(Unit)
            release.await()
            finalizations += 1
        }
        val repeated = gate.start(scope) { error("repeated ACTION_STOP must share") }
        assertSame(first, repeated)
        testScheduler.runCurrent()
        entered.await()
        assertEquals(1, runs)
        assertEquals(0, finalizations)

        release.complete(Unit)
        testScheduler.runCurrent()
        first.join()
        assertEquals(1, finalizations)
        scope.cancel()
    }

    @Test
    fun cancellationEscapesPromiseSettlementByIdentityWithoutSettlement() = runTest {
        val cancellation = CancellationException("cancel workflow")
        var resolves = 0
        var rejects = 0

        val thrown = assertFailsWith<CancellationException> {
            settleTwinotifyPromise(
                code = "STOP_SVC",
                boundedMessage = "Unable to stop sync service",
                operation = { throw cancellation },
                resolve = { resolves += 1 },
                reject = { _, _, _ -> rejects += 1 },
            )
        }

        assertSame(cancellation, thrown)
        assertEquals(0, resolves)
        assertEquals(0, rejects)
    }

    @Test
    fun ordinaryPromiseFailureRejectsOnceWithStaticPublicValues() = runTest {
        val rejects = mutableListOf<Triple<String, String, Throwable?>>()
        var resolves = 0

        settleTwinotifyPromise<Unit>(
            code = "CALL_CAPTURE",
            boundedMessage = "Unable to disable call capture",
            operation = { throw IllegalStateException("raw database payload") },
            resolve = { resolves += 1 },
            reject = { code, message, cause -> rejects += Triple(code, message, cause) },
        )

        assertEquals(0, resolves)
        assertEquals(
            listOf(Triple<String, String, Throwable?>("CALL_CAPTURE", "Unable to disable call capture", null)),
            rejects,
        )
    }
}
