package co.twinotify.core.listener

import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.assertFalse
import kotlin.test.assertEquals
import kotlin.test.BeforeTest

class PendingPeerCancelTest {
    @BeforeTest fun clean() { PendingPeerCancel.clearForTest() }

    @Test fun add_and_consume_within_ttl() {
        val now = 1_000L
        PendingPeerCancel.add("canon1", now)
        assertTrue(PendingPeerCancel.consume("canon1", now + 1000))
        assertFalse(PendingPeerCancel.consume("canon1", now + 2000), "second consume finds nothing")
    }

    @Test fun expires_after_ttl() {
        val now = 1_000L
        PendingPeerCancel.add("canon2", now)
        assertFalse(PendingPeerCancel.consume("canon2", now + 31_000))
    }

    @Test fun sweep_removes_expired() {
        PendingPeerCancel.add("alive", 1_000L)
        PendingPeerCancel.add("dead", 1_000L)
        PendingPeerCancel.sweep(1_000L + 31_000)
        assertEquals(0, PendingPeerCancel.sizeForTest())
    }

    @Test fun contains_does_not_consume() {
        val now = 1_000L
        PendingPeerCancel.add("canon3", now)
        assertTrue(PendingPeerCancel.contains("canon3", now + 1000))
        assertTrue(PendingPeerCancel.contains("canon3", now + 1000), "contains is idempotent")
    }
}
