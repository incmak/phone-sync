package co.twinotify.core.actions

import co.twinotify.core.storage.ActionInvocation

sealed interface ActionExpiryCommitResult {
    data class Expired(val repost: Boolean) : ActionExpiryCommitResult
    data object Lost : ActionExpiryCommitResult
}

interface ActionInvocationExpiryStore {
    suspend fun due(now: Long): List<ActionInvocation>
    suspend fun expire(row: ActionInvocation, now: Long): ActionExpiryCommitResult
    suspend fun earliestDueAt(): Long?
}

fun interface ActionExpiredReposter {
    suspend fun repost(row: ActionInvocation)
}

data class ActionInvocationExpirySummary(
    val expired: Int,
    val nextDueAt: Long?,
)

class ActionInvocationExpiry(
    private val store: ActionInvocationExpiryStore,
    private val repost: ActionExpiredReposter,
    private val scheduler: ActionInvocationExpiryScheduler,
    private val clock: () -> Long = { System.currentTimeMillis().coerceAtLeast(0L) },
) {
    suspend fun expireDue(): ActionInvocationExpirySummary {
        val now = clock()
        var expired = 0
        for (row in store.due(now)) {
            when (val result = store.expire(row, now)) {
                ActionExpiryCommitResult.Lost -> Unit
                is ActionExpiryCommitResult.Expired -> {
                    expired += 1
                    if (result.repost) repost.repost(row)
                }
            }
        }
        val next = store.earliestDueAt()
        next?.let(scheduler::schedule)
        return ActionInvocationExpirySummary(expired, next)
    }
}
