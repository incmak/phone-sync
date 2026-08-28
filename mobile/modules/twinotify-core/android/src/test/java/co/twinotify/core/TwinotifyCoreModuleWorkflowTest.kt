package co.twinotify.core

import co.twinotify.core.call.ActiveCallRecoveryException
import co.twinotify.core.call.CALL_SHUTDOWN_FAILED
import co.twinotify.core.call.CALL_SHUTDOWN_STALE
import co.twinotify.core.call.CallShutdownConfigIntent
import co.twinotify.core.call.CallCaptureDecision
import co.twinotify.core.call.CallCapturePolicy
import co.twinotify.core.call.CallSourceCapabilities
import co.twinotify.core.call.GracefulCallShutdownGate
import co.twinotify.core.call.GracefulCallShutdownResult
import co.twinotify.core.service.executeCallCaptureStopRequest
import co.twinotify.core.service.executeCallShutdownPhases
import co.twinotify.core.service.CallCaptureStopRequestGate
import co.twinotify.core.service.CallShutdownPhaseState
import co.twinotify.core.service.ServiceConfig
import co.twinotify.core.service.ServiceStartDecision
import co.twinotify.core.service.ServiceStartPolicy
import java.util.concurrent.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class TwinotifyCoreModuleWorkflowTest {
    @Test
    fun preMutationShutdownFailurePropagatesWithoutGenericRollback() = runTest {
        val failure = ActiveCallRecoveryException(CALL_SHUTDOWN_STALE)
        var mutationStarted = false
        var rollbackCalls = 0

        val actual = assertFailsWith<ActiveCallRecoveryException> {
            applyCallCapturePreference(
                requestedEnabled = true,
                admission = CallCaptureDecision.Start,
                rollbackOnEnableFailure = { mutationStarted },
                disableGracefully = { rollbackCalls += 1 },
                enable = {
                    orchestrateCallCaptureEnablement<String>(
                        awaitShutdownRelease = { throw failure },
                        markMutationStarted = { mutationStarted = true },
                        decideCallCaptureAdmission = { CallCaptureDecision.Start },
                        persistEnabled = { error("pre-mutation failure must not persist") },
                        decideServiceStart = { error("pre-mutation failure needs no policy") },
                        beginAdmission = { error("pre-mutation failure needs no admission") },
                        startService = { _, _ -> error("pre-mutation failure must not start") },
                        awaitAdmission = { error("pre-mutation failure has no admission result") },
                        abandonAdmission = { error("pre-mutation failure has no admission to abandon") },
                    )
                },
            )
        }

        assertSame(failure, actual)
        assertFalse(mutationStarted)
        assertEquals(0, rollbackCalls)
    }

    @Test
    fun postMutationFailureStillRollsBack() = runTest {
        val failure = IllegalStateException("service policy failed")
        var mutationStarted = false
        var rollbackCalls = 0

        val actual = assertFailsWith<IllegalStateException> {
            applyCallCapturePreference(
                requestedEnabled = true,
                admission = CallCaptureDecision.Start,
                rollbackOnEnableFailure = { mutationStarted },
                disableGracefully = { rollbackCalls += 1 },
                enable = {
                    orchestrateCallCaptureEnablement<String>(
                        awaitShutdownRelease = {},
                        markMutationStarted = { mutationStarted = true },
                        decideCallCaptureAdmission = { CallCaptureDecision.Start },
                        persistEnabled = { true },
                        decideServiceStart = { throw failure },
                        beginAdmission = { error("failed policy needs no admission") },
                        startService = { _, _ -> error("failed policy must not start") },
                        awaitAdmission = { error("failed policy has no admission result") },
                        abandonAdmission = { error("failed policy has no admission to abandon") },
                    )
                },
            )
        }

        assertSame(failure, actual)
        assertTrue(mutationStarted)
        assertEquals(1, rollbackCalls)
    }

    @Test
    fun newerDisableWinsWhenInvokedDuringOlderEnablePersistence() = runTest {
        var durableEnabled = false
        val gate = CallCapturePreferenceRequestGate()
        val enableRequest = gate.newRequest()
        val enablePersistenceStarted = CompletableDeferred<Unit>()
        val allowEnablePersistenceToFinish = CompletableDeferred<Unit>()

        val enable = async {
            gate.runLatest(
                request = enableRequest,
                readCurrent = { durableEnabled },
                operation = {
                    applyCallCapturePreference(
                        requestedEnabled = true,
                        admission = CallCaptureDecision.Start,
                        mutateIfCurrent = { mutation ->
                            gate.mutateIfCurrent(enableRequest, mutation)
                        },
                        disableGracefully = { durableEnabled = false },
                        enable = {
                            orchestrateCallCaptureEnablement(
                                awaitShutdownRelease = {},
                                mutateIfCurrent = { mutation ->
                                    gate.mutateIfCurrent(enableRequest, mutation)
                                },
                                decideCallCaptureAdmission = { CallCaptureDecision.Start },
                                persistEnabled = {
                                    enablePersistenceStarted.complete(Unit)
                                    allowEnablePersistenceToFinish.await()
                                    durableEnabled = true
                                    true
                                },
                                decideServiceStart = { ServiceStartDecision.Stop("disabled") },
                                beginAdmission = { error("deferred enable needs no admission") },
                                startService = { _, _ -> error("deferred enable must not start") },
                                awaitAdmission = { error("deferred enable has no admission result") },
                                abandonAdmission = { error("deferred enable has no admission to abandon") },
                            )
                        },
                    )
                },
            )
        }

        enablePersistenceStarted.await()
        val disableRequest = gate.newRequest()
        val disable = async {
            gate.runLatest(
                request = disableRequest,
                readCurrent = { durableEnabled },
                operation = {
                    gate.mutateIfCurrent(disableRequest) {
                        durableEnabled = false
                    }
                    false
                },
            )
        }

        runCurrent()
        assertFalse(disable.isCompleted)
        allowEnablePersistenceToFinish.complete(Unit)

        withTimeout(1_000) {
            assertFalse(enable.await())
            assertFalse(disable.await())
        }
        assertFalse(durableEnabled)
    }

    @Test
    fun newerDisableRepairsFailedShutdownWhileOlderEnableIsWaiting() = runTest {
        var durableEnabled = true
        var staleRollbackCalls = 0
        val gate = CallCapturePreferenceRequestGate()
        val enableRequest = gate.newRequest()
        val enableIsWaiting = CompletableDeferred<Unit>()
        val shutdownRelease = CompletableDeferred<Unit>()

        val enable = async {
            gate.runLatest(
                request = enableRequest,
                readCurrent = { durableEnabled },
                operation = {
                    applyCallCapturePreference(
                        requestedEnabled = true,
                        admission = CallCaptureDecision.Start,
                        mutateIfCurrent = { mutation ->
                            gate.mutateIfCurrent(enableRequest, mutation)
                        },
                        disableGracefully = {
                            staleRollbackCalls += 1
                            durableEnabled = false
                        },
                        enable = {
                            orchestrateCallCaptureEnablement(
                                awaitShutdownRelease = {
                                    enableIsWaiting.complete(Unit)
                                    shutdownRelease.await()
                                },
                                requestIsCurrent = { gate.isCurrent(enableRequest) },
                                mutateIfCurrent = { mutation ->
                                    gate.mutateIfCurrent(enableRequest, mutation)
                                },
                                decideCallCaptureAdmission = { CallCaptureDecision.Start },
                                persistEnabled = {
                                    durableEnabled = true
                                    true
                                },
                                decideServiceStart = { ServiceStartDecision.Stop("disabled") },
                                beginAdmission = { error("superseded enable needs no admission") },
                                startService = { _, _ -> error("superseded enable must not start") },
                                awaitAdmission = { error("superseded enable has no admission result") },
                                abandonAdmission = { error("superseded enable has no admission to abandon") },
                            )
                        },
                    )
                },
            )
        }

        enableIsWaiting.await()
        val disableRequest = gate.newRequest()
        val disable = async {
            gate.runLatest(
                request = disableRequest,
                readCurrent = { durableEnabled },
                operation = {
                    durableEnabled = false
                    shutdownRelease.complete(Unit)
                    false
                },
            )
        }

        withTimeout(1_000) {
            assertFalse(disable.await())
            assertFalse(enable.await())
        }
        assertFalse(durableEnabled)
        assertEquals(0, staleRollbackCalls)
    }

    @Test
    fun earlierDisableThatStartsLateCannotOverwriteNewerEnable() = runTest {
        var durableEnabled = false
        val gate = CallCapturePreferenceRequestGate()
        val earlierDisable = gate.newRequest()
        val newerEnable = gate.newRequest()

        val enableResult = gate.runLatest(
            request = newerEnable,
            readCurrent = { durableEnabled },
            operation = {
                durableEnabled = true
                true
            },
        )
        val staleDisableResult = gate.runLatest(
            request = earlierDisable,
            readCurrent = { durableEnabled },
            operation = {
                durableEnabled = false
                false
            },
        )

        assertTrue(enableResult)
        assertTrue(staleDisableResult)
        assertTrue(durableEnabled)
    }

    @Test
    fun concurrentServiceStartThatObservedFalseIsNotifiedAfterDurableEnable() = runTest {
        var durableEnabled = false
        val letConcurrentStartRead = CompletableDeferred<Unit>()
        val concurrentStartObserved = CompletableDeferred<Boolean>()
        val order = mutableListOf<String>()
        val concurrentStart = launch {
            letConcurrentStartRead.await()
            concurrentStartObserved.complete(durableEnabled)
        }

        val enabled = orchestrateCallCaptureEnablement(
            awaitShutdownRelease = { order += "await-shutdown-release" },
            decideCallCaptureAdmission = { CallCaptureDecision.Start },
            persistEnabled = {
                letConcurrentStartRead.complete(Unit)
                assertFalse(concurrentStartObserved.await())
                durableEnabled = true
                order += "persist-enabled"
                true
            },
            decideServiceStart = {
                order += "decide-service-start"
                ServiceStartPolicy.decide(
                    intentAction = null,
                    persisted = ServiceConfig(
                        enabled = true,
                        callCaptureEnabled = durableEnabled,
                    ),
                    paired = true,
                    lanBound = true,
                )
            },
            beginAdmission = {
                order += "begin-admission"
                "ticket"
            },
            startService = { start, ticket ->
                assertEquals(ServiceStartDecision.Start(relayUrl = null, lanBound = true), start)
                assertEquals("ticket", ticket)
                assertTrue(durableEnabled)
                order += "notify-service"
            },
            awaitAdmission = {
                assertEquals("ticket", it)
                order += "await-admission"
                true
            },
            abandonAdmission = { order += "abandon:$it" },
        )

        concurrentStart.join()
        assertTrue(enabled)
        assertEquals(
            listOf(
                "await-shutdown-release",
                "persist-enabled",
                "decide-service-start",
                "begin-admission",
                "notify-service",
                "await-admission",
            ),
            order,
        )
    }

    @Test
    fun newerEnablePersistsOnlyAfterEarlierDisableReleasesShutdown() = runTest {
        var durableEnabled = true
        val allowEarlierDisableToPersist = CompletableDeferred<Unit>()
        val earlierDisable = launch {
            allowEarlierDisableToPersist.await()
            durableEnabled = false
        }
        val order = mutableListOf<String>()

        val enabled = orchestrateCallCaptureEnablement(
            awaitShutdownRelease = {
                allowEarlierDisableToPersist.complete(Unit)
                earlierDisable.join()
                order += "earlier-disable-complete"
            },
            decideCallCaptureAdmission = { CallCaptureDecision.Start },
            persistEnabled = {
                durableEnabled = true
                order += "persist-newer-enable"
                true
            },
            decideServiceStart = { ServiceStartDecision.Stop("disabled") },
            beginAdmission = { error("deferred preference needs no admission") },
            startService = { _, _ -> error("deferred preference must not start service") },
            awaitAdmission = { error("deferred preference needs no admission result") },
            abandonAdmission = { error("deferred preference has no admission to abandon") },
        )

        if (!earlierDisable.isCompleted) {
            allowEarlierDisableToPersist.complete(Unit)
            earlierDisable.join()
        }
        assertTrue(enabled)
        assertTrue(durableEnabled)
        assertEquals(
            listOf("earlier-disable-complete", "persist-newer-enable"),
            order,
        )
    }

    @Test
    fun permissionRevokedDuringShutdownWaitCannotPersistDeferredOptIn() = runTest {
        var permissionGranted = true
        var persisted = false
        val order = mutableListOf<String>()

        val enabled = orchestrateCallCaptureEnablement(
            awaitShutdownRelease = {
                order += "await-shutdown-release"
                permissionGranted = false
            },
            decideCallCaptureAdmission = {
                order += "revalidate-capability"
                CallCapturePolicy.decide(
                    enabled = true,
                    capabilities = CallSourceCapabilities(
                        supported = true,
                        permissionGranted = permissionGranted,
                    ),
                )
            },
            persistEnabled = {
                persisted = true
                true
            },
            decideServiceStart = { ServiceStartDecision.Stop("disabled") },
            beginAdmission = { error("denied capture must not begin admission") },
            startService = { _, _ -> error("denied capture must not start service") },
            awaitAdmission = { error("denied capture has no admission result") },
            abandonAdmission = { error("denied capture has no admission to abandon") },
        )

        assertFalse(enabled)
        assertFalse(persisted)
        assertEquals(listOf("await-shutdown-release", "revalidate-capability"), order)
    }

    @Test
    fun durableFalseSkipsDecisionAdmissionAndServiceStart() = runTest {
        val order = mutableListOf<String>()

        val admitted = orchestrateCallCaptureEnablement(
            awaitShutdownRelease = { order += "await-shutdown-release" },
            decideCallCaptureAdmission = { CallCaptureDecision.Start },
            persistEnabled = { order += "persist"; false },
            decideServiceStart = { error("durable false must skip the start decision") },
            beginAdmission = { error("durable false must skip admission") },
            startService = { _, _ -> error("durable false must skip service start") },
            awaitAdmission = { error("durable false must skip admission result") },
            abandonAdmission = { error("durable false has no admission to abandon") },
        )

        assertFalse(admitted)
        assertEquals(listOf("await-shutdown-release", "persist"), order)
    }

    @Test
    fun durableExceptionSkipsAdmissionAndPreservesFailure() = runTest {
        val order = mutableListOf<String>()
        val failure = IllegalStateException("durable write failed")

        val actual = assertFailsWith<IllegalStateException> {
            orchestrateCallCaptureEnablement(
                awaitShutdownRelease = { order += "await-shutdown-release" },
                decideCallCaptureAdmission = { CallCaptureDecision.Start },
                persistEnabled = { throw failure },
                decideServiceStart = { error("failed persistence must skip the start decision") },
                beginAdmission = { error("failed persistence must skip admission") },
                startService = { _, _ -> error("failed persistence must skip service start") },
                awaitAdmission = { error("failed persistence must skip admission result") },
                abandonAdmission = { error("failed persistence has no admission to abandon") },
            )
        }

        assertSame(failure, actual)
        assertEquals(listOf("await-shutdown-release"), order)
    }

    @Test
    fun permissionDenialCannotPersistEnabledAndUsesGracefulDisable() = runTest {
        val order = mutableListOf<String>()

        val enabled = applyCallCapturePreference(
            requestedEnabled = true,
            admission = CallCaptureDecision.Disabled("call_permission_denied"),
            disableGracefully = { order += "disable" },
            enable = { order += "enable"; true },
        )

        assertFalse(enabled)
        assertEquals(listOf("disable"), order)
    }

    @Test
    fun permissionGrantedEnablementReturnsDurableNativeTruth() = runTest {
        val order = mutableListOf<String>()

        val enabled = applyCallCapturePreference(
            requestedEnabled = true,
            admission = CallCaptureDecision.Start,
            disableGracefully = { order += "disable" },
            enable = { order += "enable"; true },
        )

        assertEquals(true, enabled)
        assertEquals(listOf("enable"), order)
    }

    @Test
    fun rejectedAsyncAdmissionRollsDurablePreferenceBackOff() = runTest {
        val order = mutableListOf<String>()

        val enabled = applyCallCapturePreference(
            requestedEnabled = true,
            admission = CallCaptureDecision.Start,
            disableGracefully = { order += "disable" },
            enable = { order += "await-admission"; false },
        )

        assertFalse(enabled)
        assertEquals(listOf("await-admission", "disable"), order)
    }

    @Test
    fun serviceDisabledPersistsAndReportsDeferredCallCapturePreference() = runTest {
        val order = mutableListOf<String>()

        val enabled = applyCallCapturePreference(
            requestedEnabled = true,
            admission = CallCaptureDecision.Start,
            disableGracefully = { order += "disable" },
            enable = {
                orchestrateCallCaptureEnablement(
                    awaitShutdownRelease = { order += "await-shutdown-release" },
                    decideCallCaptureAdmission = { CallCaptureDecision.Start },
                    persistEnabled = { order += "persist-deferred-preference"; true },
                    decideServiceStart = {
                        order += "decide-service-start"
                        ServiceStartPolicy.decide(
                            intentAction = null,
                            persisted = ServiceConfig(
                                enabled = false,
                                callCaptureEnabled = true,
                            ),
                            paired = true,
                            lanBound = true,
                        )
                    },
                    beginAdmission = { error("disabled service must defer admission") },
                    startService = { _, _ -> error("disabled service must not start") },
                    awaitAdmission = { error("disabled service has no admission result") },
                    abandonAdmission = { error("disabled service has no admission to abandon") },
                )
            },
        )

        assertEquals(true, enabled)
        assertEquals(
            listOf(
                "await-shutdown-release",
                "persist-deferred-preference",
                "decide-service-start",
            ),
            order,
        )
    }

    @Test
    fun getCallCaptureEnabledReturnsDurablePreferenceWithoutLifecycleMutation() = runTest {
        var reads = 0

        val enabled = readCallCapturePreference {
            reads += 1
            true
        }

        assertTrue(enabled)
        assertEquals(1, reads)
    }

    @Test
    fun unsupportedTelephonyCannotPersistOrReportCallCaptureEnabled() = runTest {
        val order = mutableListOf<String>()

        val enabled = applyCallCapturePreference(
            requestedEnabled = true,
            admission = CallCaptureDecision.Disabled("call_telephony_unsupported"),
            disableGracefully = { order += "disable" },
            enable = { order += "enable"; true },
        )

        assertFalse(enabled)
        assertEquals(listOf("disable"), order)
    }

    @Test
    fun missingConfiguredRoutePersistsDeferredCallCapturePreference() = runTest {
        val order = mutableListOf<String>()

        val enabled = applyCallCapturePreference(
            requestedEnabled = true,
            admission = CallCaptureDecision.Start,
            disableGracefully = { order += "disable" },
            enable = {
                orchestrateCallCaptureEnablement(
                    awaitShutdownRelease = { order += "await-shutdown-release" },
                    decideCallCaptureAdmission = { CallCaptureDecision.Start },
                    persistEnabled = { order += "persist-deferred-preference"; true },
                    decideServiceStart = {
                        order += "decide-service-start"
                        ServiceStartPolicy.decide(
                            intentAction = null,
                            persisted = ServiceConfig(
                                enabled = true,
                                callCaptureEnabled = true,
                            ),
                            paired = true,
                            lanBound = false,
                        )
                    },
                    beginAdmission = { error("missing route must defer admission") },
                    startService = { _, _ -> error("missing route must not start service") },
                    awaitAdmission = { error("missing route has no admission result") },
                    abandonAdmission = { error("missing route has no admission to abandon") },
                )
            },
        )

        assertEquals(true, enabled)
        assertEquals(
            listOf(
                "await-shutdown-release",
                "persist-deferred-preference",
                "decide-service-start",
            ),
            order,
        )
    }

    @Test
    fun failedServiceRestartAbandonsAdmissionAndRollsPreferenceBackOff() = runTest {
        val order = mutableListOf<String>()

        assertFailsWith<IllegalStateException> {
            applyCallCapturePreference(
                requestedEnabled = true,
                admission = CallCaptureDecision.Start,
                disableGracefully = { order += "disable" },
                enable = {
                    orchestrateCallCaptureEnablement(
                        awaitShutdownRelease = { order += "await-shutdown-release" },
                        decideCallCaptureAdmission = { CallCaptureDecision.Start },
                        persistEnabled = { order += "persist-enabled"; true },
                        decideServiceStart = {
                            order += "decide-service-start"
                            ServiceStartDecision.Start(relayUrl = null, lanBound = true)
                        },
                        beginAdmission = { order += "begin-admission"; "ticket" },
                        startService = { _, _ ->
                            order += "start-service"
                            throw IllegalStateException("service start rejected")
                        },
                        awaitAdmission = { error("failed start has no admission result") },
                        abandonAdmission = { order += "abandon:$it" },
                    )
                },
            )
        }

        assertEquals(
            listOf(
                "await-shutdown-release",
                "persist-enabled",
                "decide-service-start",
                "begin-admission",
                "start-service",
                "abandon:ticket",
                "disable",
            ),
            order,
        )
    }

    @Test
    fun enableCancellationKeepsIdentityEvenWhenRollbackAlsoFails() = runTest {
        val cancellation = CancellationException("cancel enable")
        val order = mutableListOf<String>()

        val actual = assertFailsWith<CancellationException> {
            applyCallCapturePreference(
                requestedEnabled = true,
                admission = CallCaptureDecision.Start,
                disableGracefully = { throw IllegalStateException("rollback failed") },
                enable = {
                    orchestrateCallCaptureEnablement(
                        awaitShutdownRelease = { order += "await-shutdown-release" },
                        decideCallCaptureAdmission = { CallCaptureDecision.Start },
                        persistEnabled = { order += "persist-enabled"; true },
                        decideServiceStart = {
                            order += "decide-service-start"
                            ServiceStartDecision.Start(relayUrl = null, lanBound = true)
                        },
                        beginAdmission = { order += "begin-admission"; "ticket" },
                        startService = { _, _ -> throw cancellation },
                        awaitAdmission = { error("cancelled start has no admission result") },
                        abandonAdmission = { order += "abandon:$it" },
                    )
                },
            )
        }

        assertSame(cancellation, actual)
        assertEquals("rollback failed", actual.suppressed.single().message)
        assertEquals(
            listOf(
                "await-shutdown-release",
                "persist-enabled",
                "decide-service-start",
                "begin-admission",
                "abandon:ticket",
            ),
            order,
        )
    }
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
