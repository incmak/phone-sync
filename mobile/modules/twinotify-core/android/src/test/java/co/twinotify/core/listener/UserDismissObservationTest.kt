package co.twinotify.core.listener

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class UserDismissObservationTest {
    private val command = RemoveCommand("canon", "source", "user_swipe", 1L)

    @Test
    fun `records only an accepted own-package mirror removal`() {
        var submitted = 0
        var observed = 0

        val result = submitRemovalWithObservation(
            ownPackage = true,
            durablePeerCancelConsumed = false,
            inMemoryPeerCancelConsumed = false,
            removalReason = 2,
            command = command,
            submit = { submitted += 1; true },
            recordUserDismiss = { observed += 1 },
        )

        assertEquals(FilterResult.Emit("user_swipe"), result)
        assertEquals(1, submitted)
        assertEquals(1, observed)
    }

    @Test
    fun `durable and in-memory peer tombstones never submit or record`() {
        for ((durable, inMemory) in listOf(true to false, false to true)) {
            var submitted = 0
            var observed = 0

            val result = submitRemovalWithObservation(
                ownPackage = true,
                durablePeerCancelConsumed = durable,
                inMemoryPeerCancelConsumed = inMemory,
                removalReason = 2,
                command = command,
                submit = { submitted += 1; true },
                recordUserDismiss = { observed += 1 },
            )

            assertEquals(FilterResult.Suppress, result)
            assertEquals(0, submitted)
            assertEquals(0, observed)
        }
    }

    @Test
    fun `source removal and no-emit never record user mirror dismissal`() {
        var observed = 0
        var sourceSubmissions = 0

        assertEquals(
            FilterResult.Emit("user_swipe"),
            submitRemovalWithObservation(
                ownPackage = false,
                durablePeerCancelConsumed = false,
                inMemoryPeerCancelConsumed = false,
                removalReason = 2,
                command = command,
                submit = { sourceSubmissions += 1; true },
                recordUserDismiss = { observed += 1 },
            ),
        )
        assertEquals(
            FilterResult.NoEmit,
            submitRemovalWithObservation(
                ownPackage = false,
                durablePeerCancelConsumed = false,
                inMemoryPeerCancelConsumed = false,
                removalReason = 4,
                command = command,
                submit = { sourceSubmissions += 1; true },
                recordUserDismiss = { observed += 1 },
            ),
        )
        assertEquals(1, sourceSubmissions)
        assertEquals(0, observed)
    }

    @Test
    fun `rejected and throwing submission never record`() {
        var observed = 0

        assertFailsWith<IllegalStateException> {
            submitRemovalWithObservation(
                ownPackage = true,
                durablePeerCancelConsumed = false,
                inMemoryPeerCancelConsumed = false,
                removalReason = 2,
                command = command,
                submit = { false },
                recordUserDismiss = { observed += 1 },
            )
        }
        assertFailsWith<IllegalArgumentException> {
            submitRemovalWithObservation(
                ownPackage = true,
                durablePeerCancelConsumed = false,
                inMemoryPeerCancelConsumed = false,
                removalReason = 2,
                command = command,
                submit = { throw IllegalArgumentException("rejected") },
                recordUserDismiss = { observed += 1 },
            )
        }
        assertEquals(0, observed)
    }
}
