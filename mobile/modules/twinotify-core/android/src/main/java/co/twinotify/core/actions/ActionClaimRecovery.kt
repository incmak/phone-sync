package co.twinotify.core.actions

import co.twinotify.core.storage.ActionCompletionOutboxCommitResult
import co.twinotify.core.storage.ActionExecution
import co.twinotify.core.storage.OutboundMessage
import co.twinotify.core.storage.ReliableDeliveryDao
import kotlinx.coroutines.CancellationException

interface ActionClaimRecoveryStore {
    suspend fun dueClaims(cutoffClaimedAt: Long): List<ActionExecution>
    suspend fun earliestClaimedAt(): Long?
    suspend fun completeOutcomeUnknown(
        execution: ActionExecution,
        now: Long,
        result: OutboundMessage,
    ): Boolean
}

class DaoActionClaimRecoveryStore(
    private val dao: ReliableDeliveryDao,
) : ActionClaimRecoveryStore {
    override suspend fun dueClaims(cutoffClaimedAt: Long): List<ActionExecution> =
        dao.dueActionExecutionClaims(cutoffClaimedAt)

    override suspend fun earliestClaimedAt(): Long? = dao.earliestActionExecutionClaimedAt()

    override suspend fun completeOutcomeUnknown(
        execution: ActionExecution,
        now: Long,
        result: OutboundMessage,
    ): Boolean = dao.completeActionExecutionAndEnqueue(
        invocationId = execution.invocationId,
        status = "outcome_unknown",
        now = now,
        result = result,
    ) == ActionCompletionOutboxCommitResult.Committed
}

data class ActionClaimRecoverySummary(
    val finalized: Int,
    val nextDueAt: Long?,
)

class ActionClaimRecovery(
    private val store: ActionClaimRecoveryStore,
    private val resultEncoder: ActionResultRowEncoder,
    private val scheduler: ActionClaimWakeScheduler,
    private val signalTransport: () -> Unit,
    private val clock: () -> Long = { System.currentTimeMillis().coerceAtLeast(0L) },
) {
    suspend fun recover(): ActionClaimRecoverySummary {
        val now = clock()
        val cutoff = if (now >= CLAIM_GRACE_MS) now - CLAIM_GRACE_MS else Long.MIN_VALUE
        var finalized = 0
        for (execution in store.dueClaims(cutoff)) {
            val result = try {
                resultEncoder.encode(
                    ActionResultInput(
                        invocationId = execution.invocationId,
                        canonId = execution.canonId,
                        status = "outcome_unknown",
                    ),
                )
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Exception) {
                continue
            }
            if (store.completeOutcomeUnknown(execution, now, result)) finalized += 1
        }
        if (finalized > 0) signalTransport()

        val nextDueAt = store.earliestClaimedAt()?.let { claimedAt ->
            val claimDeadline = saturatingAdd(claimedAt, CLAIM_GRACE_MS)
            if (claimDeadline <= now) saturatingAdd(now, RETRY_DELAY_MS) else claimDeadline
        }
        nextDueAt?.let(scheduler::schedule)
        return ActionClaimRecoverySummary(finalized, nextDueAt)
    }

    private fun saturatingAdd(value: Long, delta: Long): Long =
        if (value > Long.MAX_VALUE - delta) Long.MAX_VALUE else value + delta

    private companion object {
        const val CLAIM_GRACE_MS = 60_000L
        const val RETRY_DELAY_MS = 5_000L
    }
}

class EarliestActionClaimWake {
    private var dueAt: Long? = null

    @Synchronized
    fun claim(candidateDueAt: Long): Boolean {
        val current = dueAt
        if (current != null && current <= candidateDueAt) return false
        dueAt = candidateDueAt
        return true
    }

    @Synchronized
    fun consume(candidateDueAt: Long): Boolean {
        if (dueAt != candidateDueAt) return false
        dueAt = null
        return true
    }
}

internal inline fun armProcessDeadlineThenPersistAlarm(
    armProcessDeadline: () -> Unit,
    persistAlarm: () -> Unit,
) {
    armProcessDeadline()
    try {
        persistAlarm()
    } catch (_: RuntimeException) {
        // The process timer remains armed; service startup covers process death.
    }
}
