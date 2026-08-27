package co.twinotify.core.listener

import co.twinotify.core.storage.CanonicalNotificationState
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class CaptureCoordinatorTest {
    @Test
    fun distinctCanonicalCapacityRetainsOnlyTwoLanesAndOneReconciliationLatch() = runTest {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var activePersists = 0
        val coordinator = CaptureCoordinator(
            scope = this,
            persister = CapturePersister {
                activePersists += 1
                if (activePersists == 2) entered.complete(Unit)
                release.await()
                CapturePersistResult(activePersists.toLong())
            },
            laneIdleMs = 50,
            maxCanonicalLanes = 2,
        )

        assertEquals(CaptureAdmission.Accepted, coordinator.submit(PostCommand("one", "one", post("one"))))
        assertEquals(CaptureAdmission.Accepted, coordinator.submit(PostCommand("two", "two", post("two"))))
        entered.await()

        repeat(1_000) { index ->
            assertEquals(
                CaptureAdmission.ReconcileRequired,
                coordinator.submit(PostCommand("overflow-$index", "overflow-$index", post("overflow-$index"))),
            )
        }

        assertEquals(2, coordinator.retainedCanonicalCountForTest())
        assertEquals(2, coordinator.activeLaneCountForTest())
        assertEquals(0, coordinator.deferredCountForTest())
        assertEquals(1, coordinator.reconciliationLatchCountForTest())

        release.complete(Unit)
        advanceUntilIdle()
    }

    @Test
    fun capacityRetirementWakesOneReconciliationWaiterAndDurableAdmissionDoesNotLoseItsCommand() = runTest {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var attempts = 0
        val persisted = mutableListOf<String>()
        val coordinator = CaptureCoordinator(
            scope = this,
            persister = CapturePersister { command ->
                attempts += 1
                if (attempts <= 2) {
                    if (attempts == 2) entered.complete(Unit)
                    release.await()
                }
                persisted += command.canonId
                CapturePersistResult(attempts.toLong())
            },
            laneIdleMs = 50,
            maxCanonicalLanes = 2,
        )

        coordinator.submit(PostCommand("one", "one", post("one")))
        coordinator.submit(PostCommand("two", "two", post("two")))
        entered.await()
        assertEquals(CaptureAdmission.ReconcileRequired, coordinator.submit(PostCommand("listener-overflow", "listener-overflow", post("listener-overflow"))))

        val durableThird = async {
            coordinator.submitDurably(PostCommand("durable-third", "durable-third", post("durable-third")))
        }
        assertFalse(durableThird.isCompleted, "suspend-capable callers wait rather than silently accepting overflow")

        release.complete(Unit)
        advanceTimeBy(60)
        advanceUntilIdle()

        assertEquals(CaptureAdmission.Accepted, durableThird.await())
        assertEquals(1, coordinator.reconciliationLatchCountForTest())
        assertTrue(persisted.contains("durable-third"))
    }

    @Test
    fun overflowedOwnPackageMirrorDismissalRecoversOnePeerRemovalAfterCapacityOpens() = runTest {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val persisted = mutableListOf<String>()
        val coordinator = CaptureCoordinator(
            scope = this,
            persister = CapturePersister { command ->
                if (persisted.isEmpty()) {
                    entered.complete(Unit)
                    release.await()
                }
                persisted += command.canonId
                CapturePersistResult(persisted.size.toLong())
            },
            laneIdleMs = 50,
            maxCanonicalLanes = 1,
        )
        coordinator.submit(PostCommand("source", "source", post("source")))
        entered.await()

        // A self-package mirror dismissal is a peer-origin canonical. Under saturation its
        // callback retains no command; only the single reconciliation latch remains.
        assertEquals(
            CaptureAdmission.ReconcileRequired,
            coordinator.submit(RemoveCommand("peer-mirror", "peer-source", "user_swipe", 1)),
        )
        assertEquals(1, coordinator.reconciliationLatchCountForTest())

        release.complete(Unit)
        advanceTimeBy(60)
        advanceUntilIdle()

        val peerMirror = CanonicalNotificationState(
            canonId = "peer-mirror",
            originDevice = "peer-device",
            latestSequence = 1,
            state = "ACTIVE",
            desiredPayloadJson = null,
            materializedSequence = 0,
            sourceNotificationKey = "peer-source",
            mirrorLocalId = 7,
            mirrorLocalTag = "mirror-tag",
            peerCancelPending = false,
            updatedAt = 1,
        )
        val recovered = CaptureReconciliation.recoveryCommands(
            originDevice = "local-device",
            snapshots = emptyList(),
            states = emptyList(),
            peerMirrorStates = listOf(peerMirror),
            liveMirrorIdentities = emptySet(),
            removedAt = 2,
        ).toList()
        assertEquals(listOf("peer-mirror"), recovered.filterIsInstance<RemoveCommand>().map { it.canonId })

        recovered.forEach { assertEquals(CaptureAdmission.Accepted, coordinator.submit(it)) }
        advanceUntilIdle()
        assertEquals(listOf("source", "peer-mirror"), persisted)
    }

    @Test
    fun cancellationFreesAReservedCanonicalLaneAndKeepsItsExactInstance() = runTest {
        val expected = java.util.concurrent.CancellationException("lane cancellation")
        val observed = CompletableDeferred<Throwable?>()
        var first = true
        val coordinator = CaptureCoordinator(
            scope = this,
            persister = CapturePersister {
                if (first) {
                    first = false
                    throw expected
                }
                CapturePersistResult(1)
            },
            laneIdleMs = 50,
            maxCanonicalLanes = 2,
            onLaneCompletionForTest = observed::complete,
        )

        assertEquals(CaptureAdmission.Accepted, coordinator.submit(PostCommand("cancelled", "cancelled", post("cancelled"))))
        runCurrent()
        assertSame(expected, observed.await())
        assertEquals(0, coordinator.retainedCanonicalCountForTest())
        assertEquals(CaptureAdmission.Accepted, coordinator.submit(PostCommand("replacement", "replacement", post("replacement"))))
    }

    @Test
    fun durableOnlyCapacityWaitDoesNotCreateAListenerReconciliationLatch() = runTest {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var attempts = 0
        val coordinator = CaptureCoordinator(
            scope = this,
            persister = CapturePersister {
                attempts += 1
                if (attempts <= 2) {
                    if (attempts == 2) entered.complete(Unit)
                    release.await()
                }
                CapturePersistResult(attempts.toLong())
            },
            laneIdleMs = 50,
            maxCanonicalLanes = 2,
        )

        coordinator.submit(PostCommand("one", "one", post("one")))
        coordinator.submit(PostCommand("two", "two", post("two")))
        entered.await()
        val durable = async {
            coordinator.submitDurably(PostCommand("durable", "durable", post("durable")))
        }

        assertFalse(durable.isCompleted)
        assertEquals(0, coordinator.reconciliationLatchCountForTest())

        release.complete(Unit)
        advanceTimeBy(60)
        advanceUntilIdle()
        assertEquals(CaptureAdmission.Accepted, durable.await())
    }

    @Test
    fun clearedLatchDoesNotLeaveAStaleWakeForTheNextFullRecovery() = runTest {
        val firstEntered = CompletableDeferred<Unit>()
        val firstRelease = CompletableDeferred<Unit>()
        val secondEntered = CompletableDeferred<Unit>()
        val secondRelease = CompletableDeferred<Unit>()
        var attempt = 0
        val coordinator = CaptureCoordinator(
            scope = this,
            persister = CapturePersister {
                attempt += 1
                when (attempt) {
                    1 -> {
                        firstEntered.complete(Unit)
                        firstRelease.await()
                    }
                    2 -> {
                        secondEntered.complete(Unit)
                        secondRelease.await()
                    }
                }
                CapturePersistResult(attempt.toLong())
            },
            laneIdleMs = 50,
            maxCanonicalLanes = 1,
        )

        coordinator.submit(PostCommand("first", "first", post("first")))
        firstEntered.await()
        assertEquals(CaptureAdmission.ReconcileRequired, coordinator.submit(PostCommand("first-overflow", "first-overflow", post("first-overflow"))))
        firstRelease.complete(Unit)
        advanceTimeBy(60)
        advanceUntilIdle()
        assertTrue(coordinator.clearReconciliationLatchIfCurrent(coordinator.reconciliationGeneration()))

        coordinator.submit(PostCommand("second", "second", post("second")))
        secondEntered.await()
        assertEquals(CaptureAdmission.ReconcileRequired, coordinator.submit(PostCommand("second-overflow", "second-overflow", post("second-overflow"))))
        val wake = async { coordinator.awaitReconciliationCapacity() }
        runCurrent()
        assertFalse(wake.isCompleted, "a cleared latch must not leave a stale capacity wake")

        secondRelease.complete(Unit)
        advanceTimeBy(60)
        advanceUntilIdle()
        assertTrue(wake.await())
    }

    @Test
    fun newerOverflowBetweenSnapshotCaptureAndClearKeepsTheReconciliationLatch() = runTest {
        val coordinator = CaptureCoordinator(
            scope = this,
            persister = CapturePersister { CapturePersistResult(1) },
            laneIdleMs = 50,
            maxCanonicalLanes = 1,
        )
        assertEquals(CaptureAdmission.Accepted, coordinator.submit(PostCommand("occupied", "occupied", post("occupied"))))
        assertEquals(CaptureAdmission.ReconcileRequired, coordinator.submit(PostCommand("overflow-one", "overflow-one", post("overflow-one"))))

        val snapshotCaptured = CompletableDeferred<Long>()
        val allowClear = CompletableDeferred<Unit>()
        val recovery = async {
            // Deterministic hook: the recovery has its snapshot lease but has not cleared it.
            snapshotCaptured.complete(coordinator.reconciliationGeneration())
            allowClear.await()
            coordinator.clearReconciliationLatchIfCurrent(snapshotCaptured.await())
        }
        snapshotCaptured.await()

        assertEquals(CaptureAdmission.ReconcileRequired, coordinator.submit(PostCommand("overflow-two", "overflow-two", post("overflow-two"))))
        allowClear.complete(Unit)

        assertFalse(recovery.await())
        assertTrue(coordinator.reconciliationNeeded())
        assertEquals(1, coordinator.reconciliationLatchCountForTest())
    }

    @Test
    fun commandsForSameCanonStayOrderedWhileDifferentCanonsRunConcurrently() = runTest {
        val persister = RecordingCapturePersister()
        val coordinator = CaptureCoordinator(
            scope = this,
            persister = persister,
            laneIdleMs = 100,
        )

        coordinator.submit(PostCommand("a", "ka", post("a")))
        coordinator.submit(PostCommand("b", "kb", post("b")))
        coordinator.submit(RemoveCommand("a", "ka", "app_cancel", 3_000))

        advanceUntilIdle()

        assertEquals(listOf(1L, 2L), persister.sequencesFor("a"))
        assertEquals(listOf(1L), persister.sequencesFor("b"))
        assertTrue(persister.overlapped, "independent canonical lanes should not serialize globally")
    }

    @Test
    fun laneIsReclaimedAfterIdleAndBoundarySubmitIsNotDropped() = runTest {
        val persister = RecordingCapturePersister()
        val coordinator = CaptureCoordinator(
            scope = this,
            persister = persister,
            laneIdleMs = 50,
        )

        coordinator.submit(PostCommand("a", "ka", post("a")))
        runCurrent()
        advanceTimeBy(60)
        advanceUntilIdle()
        assertEquals(0, coordinator.activeLaneCountForTest())

        coordinator.submit(RemoveCommand("a", "ka", "app_cancel", 4_000))
        advanceUntilIdle()

        assertEquals(listOf(1L, 2L), persister.sequencesFor("a"))
        assertEquals(0, coordinator.activeLaneCountForTest())
    }

    @Test
    fun transientPersistenceFailureIsRetriedWithoutDroppingTheCommand() = runTest {
        var attempts = 0
        val persister = CapturePersister {
            attempts += 1
            if (attempts == 1) error("temporary Room failure")
            CapturePersistResult(sequence = 1)
        }
        val coordinator = CaptureCoordinator(this, persister, laneIdleMs = 50)

        coordinator.submit(PostCommand("retry", "key-retry", post("retry")))
        advanceUntilIdle()

        assertEquals(2, attempts)
    }

    @Test
    fun noPeerRetainsOnlyLatestStatePerCanonicalUntilPairingResumes() = runTest {
        var paired = false
        var persisted = 0
        val persister = CapturePersister {
            if (!paired) throw CaptureNotPairedException("no peer")
            persisted += 1
            CapturePersistResult(sequence = persisted.toLong())
        }
        val coordinator = CaptureCoordinator(this, persister, laneIdleMs = 50)

        coordinator.submit(PostCommand("offline", "key-offline", post("offline")))
        coordinator.submit(RemoveCommand("offline", "key-offline", "app_cancel", 5_000))
        advanceUntilIdle()

        assertEquals(1, coordinator.deferredCountForTest())
        paired = true
        coordinator.resumeDeferred()
        advanceUntilIdle()
        assertEquals(0, coordinator.deferredCountForTest())
        assertEquals(1, persisted, "only the latest command should be replayed after pairing")
    }

    @Test
    fun retiredNoPeerLaneCannotReplayOldDeferredStateAfterNewerSameCanonSubmission() = runTest {
        var paired = false
        val persisted = mutableListOf<String>()
        val coordinator = CaptureCoordinator(
            scope = this,
            persister = CapturePersister { command ->
                if (!paired) throw CaptureNotPairedException("no peer")
                persisted += command.sourceKey
                CapturePersistResult(persisted.size.toLong())
            },
            laneIdleMs = 50,
        )

        coordinator.submit(PostCommand("offline", "old", post("offline")))
        advanceUntilIdle()
        assertEquals(1, coordinator.deferredCountForTest())
        assertEquals(0, coordinator.activeLaneCountForTest())

        // Do not run this new callback worker before pairing resumes: the old implementation
        // enqueued the deferred old command as its latest state and rolled the peer backward.
        coordinator.submit(RemoveCommand("offline", "new", "app_cancel", 2_000))
        paired = true
        coordinator.resumeDeferred()
        advanceUntilIdle()

        assertEquals(listOf("new"), persisted)
        assertEquals(0, coordinator.deferredCountForTest())
    }

    @Test
    fun permanentFailureDropsHeadAndAllowsLatestLaterStateToPersist() = runTest {
        var badAttempts = 0
        val persisted = mutableListOf<String>()
        val coordinator = CaptureCoordinator(this, CapturePersister { command ->
            if (command.sourceKey == "bad") {
                badAttempts += 1
                throw CapturePermanentException("invalid payload")
            }
            persisted += command.sourceKey
            CapturePersistResult(1)
        }, laneIdleMs = 50)

        coordinator.submit(PostCommand("same", "bad", post("same")))
        runCurrent()
        coordinator.submit(RemoveCommand("same", "good", "app_cancel", 2_000))
        advanceUntilIdle()

        assertEquals(1, badAttempts)
        assertEquals(listOf("good"), persisted)
    }

    @Test
    fun laterStateIsConflatedWhileTransientHeadFailureRetries() = runTest {
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var headAttempts = 0
        val persisted = mutableListOf<String>()
        val coordinator = CaptureCoordinator(this, CapturePersister { command ->
            if (command.sourceKey == "head") {
                headAttempts += 1
                if (headAttempts == 1) {
                    started.complete(Unit)
                    release.await()
                    throw IllegalStateException("temporary Room failure")
                }
            }
            persisted += command.sourceKey
            CapturePersistResult(persisted.size.toLong())
        }, laneIdleMs = 50)

        coordinator.submit(PostCommand("same", "head", post("same")))
        started.await()
        coordinator.submit(PostCommand("same", "stale", post("same")))
        coordinator.submit(RemoveCommand("same", "latest", "app_cancel", 2_000))
        release.complete(Unit)
        advanceUntilIdle()

        assertEquals(2, headAttempts)
        assertEquals(listOf("head", "latest"), persisted)
    }

    @Test
    fun newerStateSubmittedAfterPairDeferralIsNotReplacedByOlderBufferedState() = runTest {
        val persistStarted = CompletableDeferred<Unit>()
        val allowPairFailure = CompletableDeferred<Unit>()
        val deferralRecorded = CompletableDeferred<Unit>()
        val allowCompletion = CompletableDeferred<Unit>()
        var paired = false
        val persisted = mutableListOf<String>()
        val coordinator = CaptureCoordinator(
            scope = this,
            persister = CapturePersister { command ->
                if (!paired) {
                    persistStarted.complete(Unit)
                    allowPairFailure.await()
                    throw CaptureNotPairedException("no peer")
                }
                persisted += command.sourceKey
                CapturePersistResult(persisted.size.toLong())
            },
            laneIdleMs = 50,
            afterPairingDeferralForTest = {
                deferralRecorded.complete(Unit)
                allowCompletion.await()
            },
        )

        coordinator.submit(PostCommand("offline", "head", post("offline")))
        persistStarted.await()
        coordinator.submit(PostCommand("offline", "older", post("offline")))
        allowPairFailure.complete(Unit)
        deferralRecorded.await()
        coordinator.submit(RemoveCommand("offline", "newer", "app_cancel", 2_000))
        allowCompletion.complete(Unit)
        advanceUntilIdle()

        assertEquals(1, coordinator.deferredCountForTest())
        paired = true
        coordinator.resumeDeferred()
        advanceUntilIdle()

        assertEquals(listOf("newer"), persisted)
    }

    @Test
    fun resumeDuringPairDeferralPromotesRetainedLatestWithoutRetryingOldHead() = runTest {
        val persistStarted = CompletableDeferred<Unit>()
        val allowPairFailure = CompletableDeferred<Unit>()
        val deferralRecorded = CompletableDeferred<Unit>()
        val allowCompletion = CompletableDeferred<Unit>()
        var paired = false
        var oldHeadAttempts = 0
        val persisted = mutableListOf<String>()
        val coordinator = CaptureCoordinator(
            scope = this,
            persister = CapturePersister { command ->
                if (command.sourceKey == "head") {
                    oldHeadAttempts += 1
                    if (!paired) {
                        persistStarted.complete(Unit)
                        allowPairFailure.await()
                        throw CaptureNotPairedException("no peer")
                    }
                }
                persisted += command.sourceKey
                CapturePersistResult(persisted.size.toLong())
            },
            laneIdleMs = 50,
            afterPairingDeferralForTest = {
                deferralRecorded.complete(Unit)
                allowCompletion.await()
            },
        )

        coordinator.submit(PostCommand("offline", "head", post("offline")))
        persistStarted.await()
        coordinator.submit(RemoveCommand("offline", "latest", "app_cancel", 2_000))
        allowPairFailure.complete(Unit)
        deferralRecorded.await()
        paired = true
        coordinator.resumeDeferred()
        allowCompletion.complete(Unit)
        advanceUntilIdle()

        assertEquals(1, oldHeadAttempts)
        assertEquals(listOf("latest"), persisted)
        assertEquals(0, coordinator.deferredCountForTest())
        assertEquals(0, coordinator.activeLaneCountForTest())
    }

    @Test
    fun resumeBeforePairDeferralPublicationRetriesInsteadOfStrandingHead() = runTest {
        val beforePublication = CompletableDeferred<Unit>()
        val allowPublication = CompletableDeferred<Unit>()
        var paired = false
        var attempts = 0
        val persisted = mutableListOf<String>()
        val coordinator = CaptureCoordinator(
            scope = this,
            persister = CapturePersister { command ->
                attempts += 1
                if (!paired) throw CaptureNotPairedException("no peer")
                persisted += command.sourceKey
                CapturePersistResult(persisted.size.toLong())
            },
            laneIdleMs = 50,
            beforePairingDeferralForTest = {
                beforePublication.complete(Unit)
                allowPublication.await()
            },
        )

        coordinator.submit(PostCommand("offline", "head", post("offline")))
        beforePublication.await()
        paired = true
        coordinator.resumeDeferred()
        allowPublication.complete(Unit)
        advanceUntilIdle()

        assertEquals(2, attempts)
        assertEquals(listOf("head"), persisted)
        assertEquals(0, coordinator.deferredCountForTest())
        assertEquals(0, coordinator.activeLaneCountForTest())
    }

    @Test
    fun pendingBurstKeepsOrderedHeadAndOnlyLatestLaterState() = runTest {
        val persisted = mutableListOf<String>()
        val coordinator = CaptureCoordinator(this, CapturePersister { command ->
            persisted += command.sourceKey
            CapturePersistResult(persisted.size.toLong())
        }, laneIdleMs = 50)

        coordinator.submit(PostCommand("same", "first", post("same")))
        coordinator.submit(PostCommand("same", "stale", post("same")))
        coordinator.submit(RemoveCommand("same", "latest", "app_cancel", 2_000))
        advanceUntilIdle()

        assertEquals(listOf("first", "latest"), persisted)
    }

    @Test
    fun validationFailureKeepsItsCauseWhenClassifiedPermanent() {
        val cause = IllegalArgumentException("envelope too large")
        val actual = assertFailsWith<CapturePermanentException> {
            captureValidated { throw cause }
        }

        assertSame(cause, actual.cause)
    }

    @Test
    fun permanentFailureDiagnosticContainsOnlyTheBoundedFailureCode() {
        assertEquals(
            "capture persist discarded invalid state code=capture_validation",
            permanentCaptureFailureLogMessage(),
        )
    }

    @Test
    fun retryableFailureDiagnosticContainsOnlyTheBoundedFailureCode() {
        assertEquals(
            "capture persist failed code=retryable subsystem=capture",
            retryableCaptureFailureLogMessage(),
        )
    }

    @Test
    fun cancellationKeepsItsExactInstanceThroughValidationBoundary() {
        val expected = java.util.concurrent.CancellationException("capture cancelled")
        val actual = assertFailsWith<java.util.concurrent.CancellationException> {
            captureValidated { throw expected }
        }

        assertSame(expected, actual)
    }

    @Test
    fun laneReportsTheExactPersisterCancellationInstanceOnCompletion() = runTest {
        val expected = java.util.concurrent.CancellationException("capture cancelled")
        val observed = CompletableDeferred<Throwable?>()
        val laneScope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val coordinator = CaptureCoordinator(
            scope = laneScope,
            persister = CapturePersister { throw expected },
            laneIdleMs = 50,
            onLaneCompletionForTest = { cause -> observed.complete(cause) },
        )

        coordinator.submit(PostCommand("cancel", "cancel", post("cancel")))
        runCurrent()

        assertSame(expected, observed.await())
        laneScope.cancel()
    }

    @Test
    fun cancellationRetiresLaneSoLaterStateStartsFreshWorker() = runTest {
        val expected = java.util.concurrent.CancellationException("capture cancelled")
        val observed = CompletableDeferred<Throwable?>()
        val persisted = mutableListOf<String>()
        var first = true
        val laneScope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val coordinator = CaptureCoordinator(
            scope = laneScope,
            persister = CapturePersister { command ->
                if (first) {
                    first = false
                    throw expected
                }
                persisted += command.sourceKey
                CapturePersistResult(persisted.size.toLong())
            },
            laneIdleMs = 50,
            onLaneCompletionForTest = { cause -> observed.complete(cause) },
        )

        coordinator.submit(PostCommand("same", "cancelled", post("same")))
        runCurrent()
        assertSame(expected, observed.await())
        assertEquals(0, coordinator.activeLaneCountForTest())

        coordinator.submit(RemoveCommand("same", "later", "app_cancel", 2_000))
        advanceUntilIdle()

        assertEquals(listOf("later"), persisted)
        assertEquals(0, coordinator.activeLaneCountForTest())
        laneScope.cancel()
    }

    @Test
    fun terminalStateSubmittedBeforeCancelledLaneRetiresIsPreservedOnce() = runTest {
        val expected = java.util.concurrent.CancellationException("capture cancelled")
        val enteredRetirement = CompletableDeferred<Unit>()
        val allowRetirement = CompletableDeferred<Unit>()
        val observed = CompletableDeferred<Throwable?>()
        val persisted = mutableListOf<String>()
        var first = true
        val laneScope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val coordinator = CaptureCoordinator(
            scope = laneScope,
            persister = CapturePersister { command ->
                if (first) {
                    first = false
                    throw expected
                }
                persisted += command.sourceKey
                CapturePersistResult(persisted.size.toLong())
            },
            laneIdleMs = 50,
            onLaneCompletionForTest = { cause -> observed.complete(cause) },
            beforeLaneRetirementForTest = {
                enteredRetirement.complete(Unit)
                allowRetirement.await()
            },
        )

        coordinator.submit(PostCommand("same", "cancelled", post("same")))
        runCurrent()
        enteredRetirement.await()
        coordinator.submit(RemoveCommand("same", "terminal", "app_cancel", 2_000))
        allowRetirement.complete(Unit)
        advanceUntilIdle()

        assertSame(expected, observed.await())
        assertEquals(listOf("terminal"), persisted)
        assertEquals(0, coordinator.activeLaneCountForTest())
        laneScope.cancel()
    }

    @Test
    fun submitToCancelledScopeIsRejected() {
        val laneScope = CoroutineScope(SupervisorJob()).also { it.cancel() }
        val coordinator = CaptureCoordinator(laneScope, CapturePersister { CapturePersistResult(1) })

        assertEquals(
            CaptureAdmission.Closed,
            coordinator.submit(PostCommand("cancelled", "key", post("cancelled"))),
        )
    }

    private fun post(canonId: String) = SourceNotificationSnapshot(
        sourceKey = "key-$canonId",
        packageName = "example.$canonId",
        id = 1,
        tag = null,
        postTime = 1_000,
        flags = 0,
        category = null,
        visibility = 1,
        isGroupSummary = false,
        isOngoing = false,
        isClearable = true,
        appName = "example.$canonId",
        title = "title",
        text = "text",
        subText = null,
        bigText = null,
        smallIcon = null,
        largeIcon = null,
    )

    private class RecordingCapturePersister : CapturePersister {
        private val rows = CopyOnWriteArrayList<CapturedCommand>()
        @Volatile var overlapped = false

        override suspend fun persist(command: CaptureCommand): CapturePersistResult {
            val prior = rows.any { it.canonId != command.canonId && !it.finished }
            if (prior) overlapped = true
            val row = CapturedCommand(command.canonId, sequence = rows.count { it.canonId == command.canonId } + 1L)
            rows += row
            if (rows.size == 1) delay(10)
            row.finished = true
            return CapturePersistResult(sequence = row.sequence)
        }

        fun sequencesFor(canonId: String): List<Long> = rows.filter { it.canonId == canonId }.map { it.sequence }

        private data class CapturedCommand(val canonId: String, val sequence: Long, @Volatile var finished: Boolean = false)
    }
}
