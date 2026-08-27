package co.twinotify.core.service

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MaterializationRequestGateTest {
    @Test
    fun requestDuringActivePassCoalescesExactlyOneSubsequentPass() {
        val gate = MaterializationRequestGate()

        val lease = requireNotNull(gate.claimInitialPass())
        assertNull(gate.claimInitialPass())
        assertTrue(gate.completePass(lease))
        assertFalse(gate.completePass(lease))
    }

    @Test
    fun staleWorkerRetirementCannotClearASuccessorLease() {
        val gate = MaterializationRequestGate()

        val first = requireNotNull(gate.claimInitialPass())
        assertFalse(gate.completePass(first))
        val successor = requireNotNull(gate.claimInitialPass())

        gate.cancel(first)

        assertNull(gate.claimInitialPass())
        assertTrue(gate.completePass(successor))
        assertFalse(gate.completePass(successor))
    }

    @Test
    fun exceptionalPassStillRunsTheCoalescedFollowUp() = runBlocking {
        val gate = MaterializationRequestGate()
        val lease = requireNotNull(gate.claimInitialPass())
        var passes = 0

        runMaterializationPassLoop(gate, lease, isShuttingDown = { false }) { _ ->
            passes += 1
            if (passes == 1) {
                assertNull(gate.claimInitialPass())
                error("transient pass failure")
            }
        }

        assertEquals(2, passes)
    }

    @Test
    fun restorationRequestDuringRoutinePassRerunsOnceWithRestorationTrigger() = runBlocking {
        val gate = MaterializationRequestGate()
        val lease = requireNotNull(gate.claimInitialPass(MaterializationTrigger.ROUTINE))
        val triggers = mutableListOf<MaterializationTrigger>()

        runMaterializationPassLoop(
            gate = gate,
            lease = lease,
            isShuttingDown = { false },
        ) { trigger ->
            triggers += trigger
            if (triggers.size == 1) {
                assertNull(gate.claimInitialPass(MaterializationTrigger.POST_PERMISSION_AVAILABLE))
            }
        }

        assertEquals(
            listOf(MaterializationTrigger.ROUTINE, MaterializationTrigger.POST_PERMISSION_AVAILABLE),
            triggers,
        )
    }
}
