package co.twinotify.core.service

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DefaultNetworkChangeObserverTest {
    @Test
    fun initialDefaultNetworkCallbackDoesNotRequestRestart() {
        val initial = Any()
        val gate = DefaultNetworkChangeGate(initial)

        assertFalse(gate.onAvailable(initial))
    }

    @Test
    fun newDefaultNetworkRequestsExactlyOneRestart() {
        val gate = DefaultNetworkChangeGate(Any())
        val replacement = Any()

        assertTrue(gate.onAvailable(replacement))
        assertFalse(gate.onAvailable(replacement))
    }

    @Test
    fun firstNetworkAfterOfflineRequestsRestart() {
        val initial = Any()
        val gate = DefaultNetworkChangeGate(initial)
        gate.onLost(initial)

        assertTrue(gate.onAvailable(Any()))
    }
    @Test
    fun initialHealthyStateDoesNotRestart() {
        val network = Any()
        val gate = DefaultNetworkChangeGate(network)
        assertFalse(gate.onValidated(network, true))
        assertFalse(gate.onBlocked(network, false))
    }

    @Test
    fun internetRecoveryOnSameNetworkRestartsOnce() {
        val network = Any()
        val gate = DefaultNetworkChangeGate(network)
        assertFalse(gate.onValidated(network, false))
        assertTrue(gate.onValidated(network, true))
        assertFalse(gate.onValidated(network, true))
    }

    @Test
    fun liftingBackgroundNetworkBlockRestartsOnce() {
        val network = Any()
        val gate = DefaultNetworkChangeGate(network)
        assertFalse(gate.onBlocked(network, true))
        assertTrue(gate.onBlocked(network, false))
        assertFalse(gate.onBlocked(network, false))
    }

    @Test
    fun recoveryWaitsUntilBothRestrictionsClearInEitherOrder() {
        for (validationFirst in listOf(true, false)) {
            val network = Any()
            val gate = DefaultNetworkChangeGate(network)
            assertFalse(gate.onValidated(network, false))
            assertFalse(gate.onBlocked(network, true))
            if (validationFirst) {
                assertFalse(gate.onValidated(network, true))
                assertTrue(gate.onBlocked(network, false))
            } else {
                assertFalse(gate.onBlocked(network, false))
                assertTrue(gate.onValidated(network, true))
            }
        }
    }

    @Test
    fun aNewAddressOnTheSameNetworkRestartsOnceAndTheFirstReportDoesNot() {
        // A DHCP re-lease keeps the Network object; only the address changes, and every socket
        // on the old address is already dead. One phone re-leased twenty-one times in a day.
        val network = Any()
        val gate = DefaultNetworkChangeGate(network)
        assertFalse(gate.onAddresses(network, setOf("192.168.29.83")), "first report only records")
        assertFalse(gate.onAddresses(network, setOf("192.168.29.83")), "same address is not a change")
        assertTrue(gate.onAddresses(network, setOf("192.168.29.63")), "a new lease is a network change")
        assertFalse(gate.onAddresses(network, setOf("192.168.29.63")))
        assertFalse(gate.onAddresses(network, emptySet()), "losing the address is reported by onLost, not here")
        assertFalse(gate.onAddresses(Any(), setOf("10.0.0.1")), "a report for another network is ignored")
    }

    @Test
    fun replacementDiscardsOldNetworkRestrictionsAndCallbacks() {
        val old = Any()
        val replacement = Any()
        val gate = DefaultNetworkChangeGate(old)
        assertFalse(gate.onBlocked(old, true))
        assertTrue(gate.onAvailable(replacement))
        assertFalse(gate.onBlocked(old, false))
        assertFalse(gate.onValidated(old, false))
        gate.onLost(old)
        assertFalse(gate.onValidated(replacement, true))
        assertFalse(gate.onBlocked(replacement, false))
        assertFalse(gate.onAvailable(replacement))
    }

    @Test
    fun lostNetworkCannotTriggerRecovery() {
        val network = Any()
        val gate = DefaultNetworkChangeGate(network)
        assertFalse(gate.onBlocked(network, true))
        gate.onLost(network)
        assertFalse(gate.onBlocked(network, false))
        assertFalse(gate.onValidated(network, true))
        assertTrue(gate.onAvailable(network))
    }
}
