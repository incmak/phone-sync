package co.twinotify.core.actions

import co.twinotify.core.storage.ActionInvocation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.test.runTest

class ActionInvocationExpiryTest {
    @Test
    fun dueRowsExpireRepostAndArmTheNextDeadline() = runTest {
        val events = mutableListOf<String>()
        val store = FakeExpiryStore(
            due = listOf(invocation("due", 1_000)),
            next = 9_000,
            events = events,
        )
        val expiry = ActionInvocationExpiry(
            store = store,
            repost = ActionExpiredReposter { events += "repost:${it.invocationId}" },
            scheduler = ActionInvocationExpiryScheduler { events += "schedule:$it" },
            clock = { 5_000 },
        )

        assertEquals(ActionInvocationExpirySummary(expired = 1, nextDueAt = 9_000), expiry.expireDue())
        assertEquals(listOf("expire:due", "repost:due", "schedule:9000"), events)
    }

    @Test
    fun lostCasDoesNotRepost() = runTest {
        val events = mutableListOf<String>()
        val store = FakeExpiryStore(listOf(invocation("raced", 1_000)), next = null, events = events, wins = false)
        val expiry = ActionInvocationExpiry(
            store = store,
            repost = ActionExpiredReposter { events += "repost" },
            scheduler = ActionInvocationExpiryScheduler { events += "schedule" },
            clock = { 5_000 },
        )

        assertEquals(ActionInvocationExpirySummary(0, null), expiry.expireDue())
        assertEquals(listOf("expire:raced"), events)
    }

    private class FakeExpiryStore(
        private val due: List<ActionInvocation>,
        private val next: Long?,
        private val events: MutableList<String>,
        private val wins: Boolean = true,
    ) : ActionInvocationExpiryStore {
        override suspend fun due(now: Long): List<ActionInvocation> = due
        override suspend fun expire(row: ActionInvocation, now: Long): ActionExpiryCommitResult {
            events += "expire:${row.invocationId}"
            return if (wins) ActionExpiryCommitResult.Expired(repost = true) else ActionExpiryCommitResult.Lost
        }
        override suspend fun earliestDueAt(): Long? = next
    }

    private fun invocation(id: String, expiresAt: Long) = ActionInvocation(
        invocationId = id,
        canonId = "canon",
        actionId = "33333333-3333-4333-8333-333333333333",
        notificationSequence = 7,
        replyText = "private",
        state = "PENDING",
        createdAt = 1,
        expiresAt = expiresAt,
        updatedAt = 1,
    )
}
