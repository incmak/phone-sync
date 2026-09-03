package co.twinotify.core.service

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CallMirrorForegroundSlotTest {
    private val rendered = mutableListOf<Pair<Int, String>>()
    private val slot = CallMirrorForegroundSlot<String>(statusId = 9_001) { id, n -> rendered += id to n }

    @Test
    fun statusRendersDirectlyWhenNoCallHoldsTheSlot() {
        slot.renderStatus("connected")
        assertEquals(listOf(9_001 to "connected"), rendered)
        assertNull(slot.heldBy())
    }

    @Test
    fun callMirrorTakesTheSlotAndDefersStatusUntilReleased() {
        slot.renderStatus("connected")
        assertTrue(slot.hold(7341, "ringing"))
        slot.renderStatus("degraded")
        assertTrue(slot.hold(7341, "attempted"))
        assertEquals(7341, slot.heldBy())
        assertEquals(
            listOf(9_001 to "connected", 7341 to "ringing", 7341 to "attempted"),
            rendered,
        )

        assertTrue(slot.release(7341) { "stale" })

        assertEquals(9_001 to "degraded", rendered.last())
        assertNull(slot.heldBy())
    }

    @Test
    fun releaseWithoutDeferredStatusRebuildsCurrentStatusAndFailsClosedWithoutOne() {
        assertTrue(slot.hold(7341, "ringing"))
        assertFalse(slot.release(7341) { null })
        assertEquals(7341, slot.heldBy())

        assertTrue(slot.release(7341) { "connected" })
        assertEquals(9_001 to "connected", rendered.last())
        assertNull(slot.heldBy())
    }

    @Test
    fun releaseByAnotherCallOrAfterReleaseIsIdempotentAndStatusIdIsNeverACall() {
        assertTrue(slot.release(1) { "connected" })
        assertTrue(rendered.isEmpty())
        assertFalse(slot.hold(9_001, "never"))
        assertTrue(slot.hold(7341, "ringing"))
        assertTrue(slot.hold(7342, "second"))
        assertTrue(slot.release(7341) { "connected" })
        assertEquals(7342, slot.heldBy())
        assertEquals(7342 to "second", rendered.last())
    }
}
