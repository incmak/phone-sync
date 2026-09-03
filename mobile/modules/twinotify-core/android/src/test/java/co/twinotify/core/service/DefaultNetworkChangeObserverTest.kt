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
}
