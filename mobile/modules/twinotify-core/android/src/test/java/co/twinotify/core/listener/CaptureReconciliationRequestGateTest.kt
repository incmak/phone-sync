package co.twinotify.core.listener

import java.util.concurrent.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertSame

class CaptureReconciliationRequestGateTest {
    @Test
    fun requestAfterPassEffectBeforeRetirementRunsOneFollowUpWithoutExternalTrigger() = runTest {
        val gate = CaptureReconciliationRequestGate()
        val firstPassEffectComplete = CompletableDeferred<Unit>()
        val allowFirstPassToRetire = CompletableDeferred<Unit>()
        var passes = 0
        val lease = requireNotNull(gate.claimInitialPass())

        val worker = async {
            runCaptureReconciliationPassLoop(gate, lease) {
                passes += 1
                if (passes == 1) {
                    firstPassEffectComplete.complete(Unit)
                    allowFirstPassToRetire.await()
                }
            }
        }
        firstPassEffectComplete.await()

        assertNull(
            gate.claimInitialPass(),
            "the active worker must retain a follow-up request instead of dropping it",
        )
        allowFirstPassToRetire.complete(Unit)
        worker.await()

        assertEquals(2, passes)
    }

    @Test
    fun repeatedRequestsDuringOnePassCoalesceIntoOneFollowUp() = runTest {
        val gate = CaptureReconciliationRequestGate()
        val lease = requireNotNull(gate.claimInitialPass())
        var passes = 0

        runCaptureReconciliationPassLoop(gate, lease) {
            passes += 1
            if (passes == 1) {
                repeat(100) { assertNull(gate.claimInitialPass()) }
            }
        }

        assertEquals(2, passes)
    }

    @Test
    fun ordinaryPassFailureStillRunsTheCoalescedFollowUp() = runTest {
        val gate = CaptureReconciliationRequestGate()
        val lease = requireNotNull(gate.claimInitialPass())
        var passes = 0
        var failures = 0

        runCaptureReconciliationPassLoop(
            gate = gate,
            lease = lease,
            onFailure = { failures += 1 },
        ) {
            passes += 1
            if (passes == 1) {
                assertNull(gate.claimInitialPass())
                error("snapshot query unavailable")
            }
        }

        assertEquals(2, passes)
        assertEquals(1, failures)
    }

    @Test
    fun staleWorkerCleanupCannotClearASuccessorLease() = runTest {
        val gate = CaptureReconciliationRequestGate()
        val first = requireNotNull(gate.claimInitialPass())
        runCaptureReconciliationPassLoop(gate, first) { }
        val successor = requireNotNull(gate.claimInitialPass())

        gate.cancel(first)

        assertNull(gate.claimInitialPass())
        var successorPasses = 0
        runCaptureReconciliationPassLoop(gate, successor) { successorPasses += 1 }
        assertEquals(2, successorPasses)
    }

    @Test
    fun cancellationKeepsItsExactIdentity() = runTest {
        val gate = CaptureReconciliationRequestGate()
        val lease = requireNotNull(gate.claimInitialPass())
        val expected = CancellationException("listener stopped")

        val observed = assertFailsWith<CancellationException> {
            runCaptureReconciliationPassLoop(gate, lease) { throw expected }
        }

        assertSame(expected, observed)
        gate.cancel(lease)
    }
}
