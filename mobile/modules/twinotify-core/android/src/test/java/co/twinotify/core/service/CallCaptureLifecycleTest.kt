package co.twinotify.core.service

import co.twinotify.core.call.CallCaptureDecision
import co.twinotify.core.call.CallCapturePolicy
import co.twinotify.core.call.CallCaptureStatus
import co.twinotify.core.call.CallShutdownConfigIntent
import co.twinotify.core.call.CallSourceCapabilities
import co.twinotify.core.call.GracefulCallShutdownGate
import co.twinotify.core.call.GracefulCallShutdownResult
import co.twinotify.core.call.CallFrameworkState
import co.twinotify.core.call.ActiveCallRecoveryException
import co.twinotify.core.call.CallCaptureStartupGate
import co.twinotify.core.call.CallStateCoordinator
import co.twinotify.core.call.CallStateSource
import co.twinotify.core.call.recoverCallsBeforeCapture
import co.twinotify.core.call.startNormalCallCapture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertFailsWith
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotSame
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class CallCaptureLifecycleTest {
    @Test
    fun normalRegistrationFailureClearsCoordinatorThenRetriesAndStartsOnce() = runTest {
        val source = FailsFirstRegistrationSource()
        val dispatcher = StandardTestDispatcher(testScheduler)
        val healthCodes = mutableListOf<String>()
        val fence = CallCaptureLifecycleFence()
        var enabledStarts = 0
        var captureAttempts = 0
        val job = launch {
            recoverCallsBeforeCapture(
                recover = {},
                startCapture = {
                    captureAttempts += 1
                    fence.start { install ->
                        val status = startNormalCallCapture(
                            coordinator = CallStateCoordinator(source, emit = {}, dispatcher = dispatcher),
                            install = install,
                            reportRegistrationFailure = healthCodes::add,
                        )
                        if (status.enabled) enabledStarts += 1
                    }
                },
                reportFailure = healthCodes::add,
            )
        }

        testScheduler.runCurrent()
        assertEquals(1, source.registerAttempts)
        assertEquals(1, captureAttempts)
        assertNull(fence.status())
        assertEquals(
            listOf("call_callback_registration_failed", "call_recovery_failed"),
            healthCodes,
        )

        advanceTimeBy(999)
        testScheduler.runCurrent()
        assertEquals(1, source.registerAttempts)
        advanceTimeBy(1)
        testScheduler.runCurrent()
        job.join()

        assertEquals(2, source.registerAttempts)
        assertEquals(2, captureAttempts)
        assertEquals(1, enabledStarts)
        assertTrue(fence.status()?.enabled == true)
        fence.stop(terminal = false)
    }

    @Test
    fun recoveryFailureKeepsCaptureDisabledThenStartsExactlyOnceAfterOneSecond() = runTest {
        var recoveries = 0
        var captureStarts = 0
        val healthCodes = mutableListOf<String>()
        val job = launch {
            recoverCallsBeforeCapture(
                recover = {
                    recoveries += 1
                    if (recoveries == 1) throw IllegalStateException("raw database detail")
                },
                startCapture = { captureStarts += 1 },
                reportFailure = healthCodes::add,
            )
        }

        testScheduler.runCurrent()
        assertEquals(1, recoveries)
        assertEquals(0, captureStarts)
        assertEquals(listOf("call_recovery_failed"), healthCodes)

        advanceTimeBy(999)
        testScheduler.runCurrent()
        assertEquals(1, recoveries)
        assertEquals(0, captureStarts)

        advanceTimeBy(1)
        testScheduler.runCurrent()
        job.join()
        assertEquals(2, recoveries)
        assertEquals(1, captureStarts)
    }

    @Test
    fun recoveryFailureUsesTypedBoundedHealthCode() = runTest {
        val healthCodes = mutableListOf<String>()
        var attempts = 0
        val job = launch {
            recoverCallsBeforeCapture(
                recover = {
                    attempts += 1
                    if (attempts == 1) throw ActiveCallRecoveryException("call_recovery_stale")
                },
                startCapture = {},
                reportFailure = healthCodes::add,
            )
        }

        testScheduler.runCurrent()
        assertEquals(listOf("call_recovery_stale"), healthCodes)
        advanceTimeBy(1_000)
        testScheduler.runCurrent()
        job.join()
    }

    @Test
    fun recoveryFailureMapsUnknownAndOversizedTypedCodesToGeneric() = runTest {
        val reportedCodes = mutableListOf<String>()
        val invalidCodes = listOf(
            "call_recovery_private_detail",
            "x".repeat(65),
        )

        for (invalidCode in invalidCodes) {
            var attempts = 0
            val job = launch {
                recoverCallsBeforeCapture(
                    recover = {
                        attempts += 1
                        if (attempts == 1) throw ActiveCallRecoveryException(invalidCode)
                    },
                    startCapture = {},
                    reportFailure = reportedCodes::add,
                )
            }
            testScheduler.runCurrent()
            advanceTimeBy(1_000)
            testScheduler.runCurrent()
            job.join()
        }

        assertEquals(
            listOf("call_recovery_failed", "call_recovery_failed"),
            reportedCodes,
        )
    }

    @Test
    fun ordinaryReporterFailureStillRetriesRecoveryAfterOneSecond() = runTest {
        var recoveries = 0
        var captureStarts = 0
        var reports = 0
        val job = launch {
            recoverCallsBeforeCapture(
                recover = {
                    recoveries += 1
                    if (recoveries == 1) throw IllegalStateException("recovery failed")
                },
                startCapture = { captureStarts += 1 },
                reportFailure = {
                    reports += 1
                    throw IllegalStateException("health sink failed")
                },
            )
        }

        testScheduler.runCurrent()
        assertEquals(1, recoveries)
        assertEquals(1, reports)
        assertEquals(0, captureStarts)
        advanceTimeBy(999)
        testScheduler.runCurrent()
        assertEquals(1, recoveries)
        advanceTimeBy(1)
        testScheduler.runCurrent()
        job.join()

        assertEquals(2, recoveries)
        assertEquals(1, captureStarts)
    }

    @Test
    fun reporterCancellationPropagatesUnchangedAndNeverStartsCapture() = runTest {
        val cancellation = CancellationException("stop reporter")
        var captureStarts = 0

        val thrown = assertFailsWith<CancellationException> {
            recoverCallsBeforeCapture(
                recover = { throw IllegalStateException("recovery failed") },
                startCapture = { captureStarts += 1 },
                reportFailure = { throw cancellation },
            )
        }

        assertSame(cancellation, thrown)
        assertEquals(0, captureStarts)
    }

    @Test
    fun ordinaryCaptureConfigurationFailureRetriesRecoveryBeforeStartingOnce() = runTest {
        val order = mutableListOf<String>()
        val reportedCodes = mutableListOf<String>()
        var recoveries = 0
        var captureAttempts = 0
        var registered = false
        val job = launch {
            recoverCallsBeforeCapture(
                recover = {
                    recoveries += 1
                    order += "recover-$recoveries"
                },
                startCapture = {
                    captureAttempts += 1
                    order += "capture-$captureAttempts"
                    if (captureAttempts == 1) throw IllegalStateException("config read failed")
                    registered = true
                },
                reportFailure = reportedCodes::add,
            )
        }

        testScheduler.runCurrent()
        assertEquals(listOf("recover-1", "capture-1"), order)
        assertFalse(registered)
        assertEquals(listOf("call_recovery_failed"), reportedCodes)
        advanceTimeBy(1_000)
        testScheduler.runCurrent()
        job.join()

        assertEquals(listOf("recover-1", "capture-1", "recover-2", "capture-2"), order)
        assertEquals(2, recoveries)
        assertTrue(registered)
    }

    @Test
    fun captureConfigurationCancellationPropagatesUnchanged() = runTest {
        val cancellation = CancellationException("stop capture configuration")
        var reports = 0

        val thrown = assertFailsWith<CancellationException> {
            recoverCallsBeforeCapture(
                recover = {},
                startCapture = { throw cancellation },
                reportFailure = { reports += 1 },
            )
        }

        assertSame(cancellation, thrown)
        assertEquals(0, reports)
    }

    @Test
    fun successfulRecoveryReadsLatestConfigurationOnlyAfterRecoveryCompletes() = runTest {
        val recoveryEntered = CompletableDeferred<Unit>()
        val releaseRecovery = CompletableDeferred<Unit>()
        var latestCallCaptureEnabled = false
        val configuredValues = mutableListOf<Boolean>()
        suspend fun readLatestCallCaptureEnabled(): Boolean = latestCallCaptureEnabled
        val job = launch {
            recoverCallsBeforeCapture(
                recover = {
                    recoveryEntered.complete(Unit)
                    releaseRecovery.await()
                },
                startCapture = { configuredValues += readLatestCallCaptureEnabled() },
                reportFailure = { error("unexpected recovery failure: $it") },
            )
        }

        testScheduler.runCurrent()
        assertTrue(recoveryEntered.isCompleted)
        assertEquals(emptyList(), configuredValues)
        latestCallCaptureEnabled = true
        releaseRecovery.complete(Unit)
        testScheduler.runCurrent()
        job.join()

        assertEquals(listOf(true), configuredValues)
    }

    @Test
    fun ordinaryPostRecoveryConfigurationReadFailureRetriesSafely() = runTest {
        var recoveries = 0
        var reads = 0
        var configured = false
        val healthCodes = mutableListOf<String>()
        suspend fun readLatestCallCaptureEnabled(): Boolean {
            reads += 1
            if (reads == 1) throw IllegalStateException("config read failure")
            return true
        }
        val job = launch {
            recoverCallsBeforeCapture(
                recover = { recoveries += 1 },
                startCapture = { configured = readLatestCallCaptureEnabled() },
                reportFailure = healthCodes::add,
            )
        }

        testScheduler.runCurrent()
        assertEquals(1, recoveries)
        assertFalse(configured)
        assertEquals(listOf("call_recovery_failed"), healthCodes)
        advanceTimeBy(1_000)
        testScheduler.runCurrent()
        job.join()

        assertEquals(2, recoveries)
        assertTrue(configured)
    }

    @Test
    fun postRecoveryConfigurationReadCancellationPropagatesUnchanged() = runTest {
        val cancellation = CancellationException("stop config read")
        var reports = 0
        suspend fun readLatestCallCaptureEnabled(): Boolean = throw cancellation

        val thrown = assertFailsWith<CancellationException> {
            recoverCallsBeforeCapture(
                recover = {},
                startCapture = { readLatestCallCaptureEnabled() },
                reportFailure = { reports += 1 },
            )
        }

        assertSame(cancellation, thrown)
        assertEquals(0, reports)
    }

    @Test
    fun recoveryCompletesBeforeDisabledCaptureConfigurationIsInvoked() = runTest {
        val order = mutableListOf<String>()

        recoverCallsBeforeCapture(
            recover = {
                order += "recover"
                order += "recovered"
            },
            startCapture = { order += "capture-disabled" },
            reportFailure = { error("unexpected recovery failure: $it") },
        )

        assertEquals(listOf("recover", "recovered", "capture-disabled"), order)
    }

    @Test
    fun captureDoesNotStartBeforeRecoveryCompletes() = runTest {
        val recoveryEntered = CompletableDeferred<Unit>()
        val releaseRecovery = CompletableDeferred<Unit>()
        var captureStarts = 0
        val job = launch {
            recoverCallsBeforeCapture(
                recover = {
                    recoveryEntered.complete(Unit)
                    releaseRecovery.await()
                },
                startCapture = { captureStarts += 1 },
                reportFailure = { error("unexpected recovery failure: $it") },
            )
        }

        testScheduler.runCurrent()
        assertTrue(recoveryEntered.isCompleted)
        assertEquals(0, captureStarts)

        releaseRecovery.complete(Unit)
        testScheduler.runCurrent()
        job.join()
        assertEquals(1, captureStarts)
    }

    @Test
    fun cancellationPropagatesUnchangedAndNeverStartsCapture() = runTest {
        val cancellation = CancellationException("stop recovery")
        var captureStarts = 0

        val thrown = assertFailsWith<CancellationException> {
            recoverCallsBeforeCapture(
                recover = { throw cancellation },
                startCapture = { captureStarts += 1 },
                reportFailure = { error("cancellation must not be reported") },
            )
        }

        assertSame(cancellation, thrown)
        assertEquals(0, captureStarts)
    }

    @Test
    fun startupGateDeduplicatesConcurrentStartThenRerunsRecoveryWithLatestConfiguration() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        val gate = CallCaptureStartupGate()
        val releaseRecovery = CompletableDeferred<Unit>()
        var recoveries = 0
        var latestCallCaptureEnabled = false
        var captureStatus: CallCaptureStatus? = null
        val configuredValues = mutableListOf<Boolean>()

        val first = assertNotNull(startCallCaptureRecoveryForServiceStart(captureStatus) {
            gate.start(
                scope = scope,
                recover = {
                    recoveries += 1
                    releaseRecovery.await()
                },
                startCapture = {
                    configuredValues += latestCallCaptureEnabled
                    captureStatus = CallCaptureStatus(enabled = latestCallCaptureEnabled)
                },
                reportFailure = { error("unexpected recovery failure: $it") },
            )
        })
        val second = assertNotNull(startCallCaptureRecoveryForServiceStart(captureStatus) {
            gate.start(
                scope = scope,
                recover = { recoveries += 1 },
                startCapture = { configuredValues += latestCallCaptureEnabled },
                reportFailure = { error("unexpected recovery failure: $it") },
            )
        })

        assertSame(first, second)
        testScheduler.runCurrent()
        assertEquals(1, recoveries)
        assertEquals(emptyList(), configuredValues)

        releaseRecovery.complete(Unit)
        testScheduler.runCurrent()
        first.join()
        assertEquals(listOf(false), configuredValues)

        latestCallCaptureEnabled = true
        val afterCompletion = assertNotNull(startCallCaptureRecoveryForServiceStart(captureStatus) {
            gate.start(
                scope = scope,
                recover = { recoveries += 1 },
                startCapture = {
                    configuredValues += latestCallCaptureEnabled
                    captureStatus = CallCaptureStatus(enabled = latestCallCaptureEnabled)
                },
                reportFailure = { error("unexpected recovery failure: $it") },
            )
        })
        assertNotSame(first, afterCompletion)
        testScheduler.runCurrent()
        afterCompletion.join()
        assertEquals(2, recoveries)
        assertEquals(listOf(false, true), configuredValues)
        scope.cancel()
    }

    @Test
    fun laterServiceStartSkipsRecoveryWhileCaptureIsActivelyRegistered() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        val gate = CallCaptureStartupGate()
        var captureStatus: CallCaptureStatus? = null
        var recoveryAttempts = 0
        var configurationReads = 0
        var liveActiveRow = false

        fun actionStart() = startCallCaptureRecoveryForServiceStart(captureStatus) {
            gate.start(
                scope = scope,
                recover = {
                    recoveryAttempts += 1
                    if (liveActiveRow) liveActiveRow = false
                },
                startCapture = {
                    configurationReads += 1
                    captureStatus = CallCaptureStatus(enabled = true)
                    liveActiveRow = true
                },
                reportFailure = { error("unexpected recovery failure: $it") },
            )
        }

        val initialStart = assertNotNull(actionStart())
        testScheduler.runCurrent()
        initialStart.join()
        assertEquals(1, recoveryAttempts)
        assertEquals(1, configurationReads)
        assertTrue(liveActiveRow)

        assertNull(actionStart())
        testScheduler.runCurrent()
        assertEquals(1, recoveryAttempts)
        assertEquals(1, configurationReads)
        assertTrue(liveActiveRow)
        scope.cancel()
    }

    @Test
    fun terminalShutdownWaitsForInFlightRegistrationThenClosesInstalledCoordinator() {
        val fence = CallCaptureLifecycleFence()
        val source = FencedRegistrationSource(blockRegistration = true)
        val executor = Executors.newFixedThreadPool(2)
        try {
            val startup = executor.submit<Boolean> {
                fence.start { install ->
                    val status = startNormalCallCapture(
                        coordinator = CallStateCoordinator(source, emit = {}),
                        install = install,
                        reportRegistrationFailure = { error("unexpected registration failure: $it") },
                    )
                    assertTrue(status.enabled)
                }
            }
            assertTrue(source.registrationEntered.await(5, TimeUnit.SECONDS))

            val shutdownAttempted = CountDownLatch(1)
            val shutdown = executor.submit {
                shutdownAttempted.countDown()
                fence.stop(terminal = true)
            }
            assertTrue(shutdownAttempted.await(5, TimeUnit.SECONDS))
            assertFalse(shutdown.isDone)

            source.releaseRegistration.countDown()
            assertTrue(startup.get(5, TimeUnit.SECONDS))
            shutdown.get(5, TimeUnit.SECONDS)

            assertEquals(1, source.registrationAttempts.get())
            assertEquals(1, source.closeAttempts.get())
            assertNull(fence.status())
        } finally {
            source.releaseRegistration.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun terminalShutdownBeforeStartupRejectsRegistration() {
        val fence = CallCaptureLifecycleFence()
        val source = FencedRegistrationSource(blockRegistration = false)

        fence.stop(terminal = true)
        val accepted = fence.start { install ->
            startNormalCallCapture(
                coordinator = CallStateCoordinator(source, emit = {}),
                install = install,
                reportRegistrationFailure = { error("unexpected registration failure: $it") },
            )
        }

        assertFalse(accepted)
        assertEquals(0, source.registrationAttempts.get())
        assertEquals(0, source.closeAttempts.get())
        assertNull(fence.status())
    }

    @Test
    fun reservationWinsBeforeObservationAndConcurrentNoServiceCallersShareWork() = runTest {
        val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val gate = GracefulCallShutdownGate()
        val releaseTerminalizer = CompletableDeferred<Unit>()
        var observations = 0
        var terminalizerRuns = 0
        val intent = CallShutdownConfigIntent(disableCallCapture = true, disableService = false)

        val first = startCallShutdownBeforeActiveObservation(
            gate = gate,
            scope = scope,
            intent = intent,
            activeInstance = {
                observations += 1
                null
            },
            shutdownActive = { error("no active service expected") },
            shutdownWithoutActive = {
                terminalizerRuns += 1
                releaseTerminalizer.await()
                GracefulCallShutdownResult.Completed
            },
        )
        assertTrue(gate.isReserved())
        assertEquals(0, observations)

        val second = startCallShutdownBeforeActiveObservation(
            gate = gate,
            scope = scope,
            intent = intent,
            activeInstance = { error("shared caller must not observe again") },
            shutdownActive = { error("shared caller must not run") },
            shutdownWithoutActive = { error("shared caller must not run") },
        )
        assertSame(first, second)
        testScheduler.runCurrent()
        assertEquals(1, observations)
        assertEquals(1, terminalizerRuns)

        releaseTerminalizer.complete(Unit)
        testScheduler.runCurrent()
        assertSame(first.await(), second.await())
        scope.cancel()
    }

    @Test
    fun reservationBeforeServicePublicationRoutesToPublishedServiceAndBlocksBothRegistrations() = runTest {
        val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val gate = GracefulCallShutdownGate()
        val fence = CallCaptureLifecycleFence()
        val release = CompletableDeferred<Unit>()
        var published: String? = null
        var activeRuns = 0
        var normalRegistrations = 0
        var debugRegistrations = 0

        val shutdown = startCallShutdownBeforeActiveObservation(
            gate = gate,
            scope = scope,
            intent = CallShutdownConfigIntent(true, false),
            activeInstance = { published },
            shutdownActive = {
                activeRuns += 1
                release.await()
                GracefulCallShutdownResult.Completed
            },
            shutdownWithoutActive = { error("service publishes before observation") },
        )
        published = "service"
        assertFalse(fence.start(admissionReserved = gate::isReserved) { normalRegistrations += 1 })
        assertFalse(fence.start(admissionReserved = gate::isReserved) { debugRegistrations += 1 })

        testScheduler.runCurrent()
        assertEquals(1, activeRuns)
        release.complete(Unit)
        testScheduler.runCurrent()
        shutdown.await()
        assertEquals(0, normalRegistrations)
        assertEquals(0, debugRegistrations)
        scope.cancel()
    }

    @Test
    fun servicePublicationBeforeReservationStillBlocksNormalAndDebugRegistration() = runTest {
        val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val gate = GracefulCallShutdownGate()
        val fence = CallCaptureLifecycleFence()
        val release = CompletableDeferred<Unit>()
        val published = "service"
        var activeRuns = 0
        var registrations = 0

        val shutdown = startCallShutdownBeforeActiveObservation(
            gate = gate,
            scope = scope,
            intent = CallShutdownConfigIntent(false, true),
            activeInstance = { published },
            shutdownActive = {
                activeRuns += 1
                release.await()
                GracefulCallShutdownResult.Completed
            },
            shutdownWithoutActive = { error("active service expected") },
        )

        assertFalse(fence.start(admissionReserved = gate::isReserved) { registrations += 1 })
        assertFalse(fence.start(admissionReserved = gate::isReserved) { registrations += 1 })
        testScheduler.runCurrent()
        assertEquals(1, activeRuns)
        release.complete(Unit)
        testScheduler.runCurrent()
        shutdown.await()
        assertEquals(0, registrations)
        scope.cancel()
    }

    @Test
    fun runtimeDisableClosesWithoutBlockingLaterStartup() {
        val fence = CallCaptureLifecycleFence()
        val firstSource = FencedRegistrationSource(blockRegistration = false)
        val secondSource = FencedRegistrationSource(blockRegistration = false)

        assertTrue(fence.start { install ->
            startNormalCallCapture(
                coordinator = CallStateCoordinator(firstSource, emit = {}),
                install = install,
                reportRegistrationFailure = { error("unexpected registration failure: $it") },
            )
        })
        fence.stop(terminal = false)
        assertTrue(fence.start { install ->
            startNormalCallCapture(
                coordinator = CallStateCoordinator(secondSource, emit = {}),
                install = install,
                reportRegistrationFailure = { error("unexpected registration failure: $it") },
            )
        })

        assertEquals(1, firstSource.closeAttempts.get())
        assertEquals(1, secondSource.registrationAttempts.get())
        fence.stop(terminal = false)
    }

    @Test
    fun quiesceLeaseRejectsRegistrationImmediatelyAndFailureRetainsCoordinatorForRetry() {
        val fence = CallCaptureLifecycleFence()
        val firstSource = FencedRegistrationSource(blockRegistration = false)
        val rejectedSource = FencedRegistrationSource(blockRegistration = false)
        lateinit var installed: CallStateCoordinator

        assertTrue(fence.start { install ->
            installed = CallStateCoordinator(firstSource, emit = {})
            startNormalCallCapture(
                coordinator = installed,
                install = install,
                reportRegistrationFailure = { error("unexpected registration failure: $it") },
            )
        })

        val failedLease = fence.beginQuiesce(terminal = false)
        assertSame(installed, failedLease.coordinator)
        assertFalse(fence.start { install ->
            startNormalCallCapture(
                coordinator = CallStateCoordinator(rejectedSource, emit = {}),
                install = install,
                reportRegistrationFailure = { error("unexpected registration failure: $it") },
            )
        })
        fence.finishQuiesce(failedLease, completed = false)

        val retryLease = fence.beginQuiesce(terminal = false)
        assertSame(installed, retryLease.coordinator)
        assertEquals(0, firstSource.closeAttempts.get())
        assertEquals(0, rejectedSource.registrationAttempts.get())
        fence.finishQuiesce(retryLease, completed = true)
    }

    @Test
    fun quiesceSuccessDetachesClosesAndAllowsNonTerminalRestart() {
        val fence = CallCaptureLifecycleFence()
        val firstSource = FencedRegistrationSource(blockRegistration = false)
        val secondSource = FencedRegistrationSource(blockRegistration = false)

        assertTrue(fence.start { install ->
            startNormalCallCapture(
                coordinator = CallStateCoordinator(firstSource, emit = {}),
                install = install,
                reportRegistrationFailure = { error("unexpected registration failure: $it") },
            )
        })
        val lease = fence.beginQuiesce(terminal = false)
        fence.finishQuiesce(lease, completed = true)

        assertNull(fence.current())
        assertEquals(1, firstSource.closeAttempts.get())
        assertTrue(fence.start { install ->
            startNormalCallCapture(
                coordinator = CallStateCoordinator(secondSource, emit = {}),
                install = install,
                reportRegistrationFailure = { error("unexpected registration failure: $it") },
            )
        })
        assertEquals(1, secondSource.registrationAttempts.get())
        fence.stop(terminal = false)
    }

    @Test
    fun quiesceTerminalSuccessKeepsFencePermanentlyClosed() {
        val fence = CallCaptureLifecycleFence()
        val firstSource = FencedRegistrationSource(blockRegistration = false)
        val rejectedSource = FencedRegistrationSource(blockRegistration = false)

        assertTrue(fence.start { install ->
            startNormalCallCapture(
                coordinator = CallStateCoordinator(firstSource, emit = {}),
                install = install,
                reportRegistrationFailure = { error("unexpected registration failure: $it") },
            )
        })
        val lease = fence.beginQuiesce(terminal = true)
        fence.finishQuiesce(lease, completed = true)

        assertFalse(fence.start { install ->
            startNormalCallCapture(
                coordinator = CallStateCoordinator(rejectedSource, emit = {}),
                install = install,
                reportRegistrationFailure = { error("unexpected registration failure: $it") },
            )
        })
        assertEquals(1, firstSource.closeAttempts.get())
        assertEquals(0, rejectedSource.registrationAttempts.get())
    }

    @Test
    fun mergedServiceStopPromotesCaptureOnlyLeaseBeforeAdmissionRelease() {
        val fence = CallCaptureLifecycleFence()
        val firstSource = FencedRegistrationSource(blockRegistration = false)
        val rejectedSource = FencedRegistrationSource(blockRegistration = false)

        assertTrue(fence.start { install ->
            startNormalCallCapture(
                coordinator = CallStateCoordinator(firstSource, emit = {}),
                install = install,
                reportRegistrationFailure = { error("unexpected registration failure: $it") },
            )
        })
        val captureOnlyLease = fence.beginQuiesce(terminal = false)
        fence.finishQuiesce(
            lease = captureOnlyLease,
            completed = true,
            terminal = true,
        )

        assertFalse(fence.start { install ->
            startNormalCallCapture(
                coordinator = CallStateCoordinator(rejectedSource, emit = {}),
                install = install,
                reportRegistrationFailure = { error("unexpected registration failure: $it") },
            )
        })
        assertEquals(1, firstSource.closeAttempts.get())
        assertEquals(0, rejectedSource.registrationAttempts.get())
    }

    @Test
    fun quiesceUnexpectedTerminalStopLeavesActiveJournalUntouched() = runTest {
        val source = FakeSource()
        val journal = mutableListOf<String>()
        val coordinator = CallStateCoordinator(
            source = source,
            emit = { journal += it.state.uppercase() },
            dispatcher = StandardTestDispatcher(testScheduler),
        )
        val fence = CallCaptureLifecycleFence()
        assertTrue(fence.start { install ->
            install(coordinator)
            coordinator.start()
        })
        source.emit(CallFrameworkState.RINGING)
        testScheduler.runCurrent()
        assertEquals(listOf("RINGING"), journal)

        fence.stop(terminal = true)
        testScheduler.runCurrent()

        assertEquals(listOf("RINGING"), journal)
        assertNull(fence.current())
    }

    @Test
    fun unexpectedServiceDestroyOnlyClosesFenceAndCancelsJobsLeavingActiveJournalForRecovery() = runTest {
        val source = FakeSource()
        val activeJournal = mutableListOf<String>()
        val coordinator = CallStateCoordinator(
            source = source,
            emit = { activeJournal += it.state.uppercase() },
            dispatcher = StandardTestDispatcher(testScheduler),
        )
        val fence = CallCaptureLifecycleFence()
        var jobsCancelled = false
        assertTrue(fence.start { install ->
            install(coordinator)
            coordinator.start()
        })
        source.emit(CallFrameworkState.RINGING)
        testScheduler.runCurrent()

        executeUnexpectedServiceDestroy(
            closeCallCapture = { fence.stop(terminal = true) },
            cancelServiceJobs = { jobsCancelled = true },
        )

        assertEquals(listOf("RINGING"), activeJournal)
        assertNull(fence.current())
        assertTrue(jobsCancelled)
    }

    @Test
    fun startupGateUsesLatestCaptureConfigurationAfterRecoveryRetry() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        val gate = CallCaptureStartupGate()
        var recoveries = 0
        var latestCallCaptureEnabled = false
        val configuredValues = mutableListOf<Boolean>()

        val job = gate.start(
            scope = scope,
            recover = {
                recoveries += 1
                if (recoveries == 1) throw IllegalStateException("retry")
            },
            startCapture = { configuredValues += latestCallCaptureEnabled },
            reportFailure = {},
        )
        testScheduler.runCurrent()
        latestCallCaptureEnabled = true
        advanceTimeBy(1_000)
        testScheduler.runCurrent()
        job.join()

        assertEquals(listOf(true), configuredValues)
        scope.cancel()
    }

    @Test
    fun startupGateClearsCompletedJobAfterCaptureCallbackFailureRetry() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        val gate = CallCaptureStartupGate()
        var recoveries = 0
        var captureAttempts = 0
        val job = gate.start(
            scope = scope,
            recover = {
                recoveries += 1
            },
            startCapture = {
                captureAttempts += 1
                if (captureAttempts == 1) throw IllegalStateException("configuration failure")
            },
            reportFailure = {},
        )

        testScheduler.runCurrent()
        advanceTimeBy(1_000)
        testScheduler.runCurrent()
        job.join()

        assertTrue(job.isCompleted)
        assertFalse(job.isCancelled)
        assertEquals(2, recoveries)
        assertEquals(2, captureAttempts)
        val laterStart = gate.start(
            scope = scope,
            recover = { recoveries += 1 },
            startCapture = { captureAttempts += 1 },
            reportFailure = {},
        )
        assertNotSame(job, laterStart)
        testScheduler.runCurrent()
        laterStart.join()
        assertEquals(3, recoveries)
        assertEquals(3, captureAttempts)
        scope.cancel()
    }

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

    private class FailsFirstRegistrationSource : CallStateSource {
        var registerAttempts = 0
            private set

        override fun capabilities() = CallSourceCapabilities(true, true)

        override fun register(listener: (CallFrameworkState) -> Unit): AutoCloseable {
            registerAttempts += 1
            if (registerAttempts == 1) throw IllegalStateException("framework registration failure")
            return AutoCloseable { }
        }
    }

    private class FencedRegistrationSource(
        private val blockRegistration: Boolean,
    ) : CallStateSource {
        val registrationEntered = CountDownLatch(1)
        val releaseRegistration = CountDownLatch(1)
        val registrationAttempts = AtomicInteger(0)
        val closeAttempts = AtomicInteger(0)

        override fun capabilities() = CallSourceCapabilities(true, true)

        override fun register(listener: (CallFrameworkState) -> Unit): AutoCloseable {
            registrationAttempts.incrementAndGet()
            registrationEntered.countDown()
            if (blockRegistration) {
                check(releaseRegistration.await(5, TimeUnit.SECONDS)) {
                    "timed out waiting to release registration"
                }
            }
            return AutoCloseable { closeAttempts.incrementAndGet() }
        }
    }
}
