package co.twinotify.core.service

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class UnpairCustodyTrackerTest {
    @Test
    fun acceptsOnlyTheReservedMessageIdAndRemovesCompletedWaiter() = runTest {
        val tracker = UnpairCustodyTracker()
        val reservation = tracker.reserve("expected")

        assertFalse(tracker.accept("stale", CustodyRoute.LAN))
        assertEquals(1, tracker.pendingCount())
        assertTrue(tracker.accept("expected", CustodyRoute.RELAY))
        assertEquals(CustodyRoute.RELAY, reservation.await(5_000L))
        assertEquals(0, tracker.pendingCount())
        assertFalse(tracker.accept("expected", CustodyRoute.LAN))
    }

    @Test
    fun timeoutRemovesExactWaiterAndStaleAcceptanceCannotReleaseReplacement() = runTest {
        val tracker = UnpairCustodyTracker()
        val first = tracker.reserve("same")
        val timedOut = async { first.await(5_000L) }

        advanceTimeBy(5_000L)
        runCurrent()
        assertNull(timedOut.await())
        assertEquals(0, tracker.pendingCount())

        val second = tracker.reserve("same")
        first.close()
        assertEquals(1, tracker.pendingCount())
        assertTrue(tracker.accept("same", CustodyRoute.LAN))
        assertEquals(CustodyRoute.LAN, second.await(5_000L))
    }

    @Test
    fun preparedHandleIsUnavailableOnlyWithoutActiveTransportAndQuiescesOnce() = runTest {
        var quiesces = 0
        val active = Job()
        val inactive = Job().apply { complete() }
        val tracker = UnpairCustodyTracker()

        val available = preparedLocalUnpairService(active, tracker) { quiesces += 1 }
        val unavailable = preparedLocalUnpairService(inactive, tracker) { quiesces += 1 }
        val absent = preparedLocalUnpairService(null, tracker) { quiesces += 1 }

        assertTrue(available.transportAvailable)
        assertFalse(unavailable.transportAvailable)
        assertFalse(absent.transportAvailable)
        available.quiesceAndAwait()
        available.quiesceAndAwait()
        assertEquals(1, quiesces)
    }

    @Test
    fun bluetoothCustodyReleasesTheReservedUnpairMessage() = runTest {
        val tracker = UnpairCustodyTracker()
        val reservation = tracker.reserve("unpair")

        assertFalse(tracker.accept("other", CustodyRoute.BLUETOOTH))
        assertTrue(tracker.accept("unpair", CustodyRoute.BLUETOOTH))

        assertEquals(CustodyRoute.BLUETOOTH, reservation.await(5_000L))
        assertEquals(0, tracker.pendingCount())
    }

    @Test
    fun closedReservationRemovesItsOwnEntry() = runTest {
        val tracker = UnpairCustodyTracker()
        val reservation = tracker.reserve("message")

        reservation.close()

        assertEquals(0, tracker.pendingCount())
        assertFalse(tracker.accept("message", CustodyRoute.LAN))
    }
}
