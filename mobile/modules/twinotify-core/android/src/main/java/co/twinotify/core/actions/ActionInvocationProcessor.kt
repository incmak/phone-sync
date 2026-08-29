package co.twinotify.core.actions

import co.twinotify.core.storage.ActionExecution
import co.twinotify.core.storage.ActionCompletionOutboxCommitResult
import co.twinotify.core.storage.InboundMessage
import co.twinotify.core.storage.OutboundMessage
import co.twinotify.core.storage.ReliableDeliveryDao
import kotlinx.coroutines.CancellationException

data class ActionInvokeRequest(
    val inbound: InboundMessage,
    val invocationId: String,
    val canonId: String,
    val actionId: String,
    val notificationSequence: Long,
    val replyText: String?,
    val invokedAt: Long,
) {
    override fun toString(): String =
        "ActionInvokeRequest(inbound=$inbound, invocationId=$invocationId, canonId=$canonId, " +
            "actionId=$actionId, notificationSequence=$notificationSequence, " +
            "replyText=${if (replyText == null) "null" else "<redacted>"}, invokedAt=$invokedAt)"
}

sealed interface ActionClaimDecision {
    data object Execute : ActionClaimDecision
    data object InFlight : ActionClaimDecision
    data class Replay(val status: String) : ActionClaimDecision
    data object IdConflict : ActionClaimDecision
}

sealed interface ActionProcessResult {
    data object InFlight : ActionProcessResult
    data object IdConflict : ActionProcessResult
    data class Replayed(val status: String) : ActionProcessResult
    data class Completed(val status: String) : ActionProcessResult
    data object CompletionLost : ActionProcessResult
}

interface ActionClaimJournal {
    suspend fun claim(
        inbound: InboundMessage,
        execution: ActionExecution,
        now: Long,
    ): ActionClaimDecision

    suspend fun completeAndEnqueue(
        invocationId: String,
        status: String,
        now: Long,
        result: OutboundMessage,
    ): Boolean

    suspend fun replayResult(
        invocationId: String,
        status: String,
        result: OutboundMessage,
    ): Boolean
}

class DaoActionClaimJournal(
    private val dao: ReliableDeliveryDao,
) : ActionClaimJournal {
    override suspend fun claim(
        inbound: InboundMessage,
        execution: ActionExecution,
        now: Long,
    ): ActionClaimDecision = dao.claimActionInvocation(inbound, execution, now)

    override suspend fun completeAndEnqueue(
        invocationId: String,
        status: String,
        now: Long,
        result: OutboundMessage,
    ): Boolean = dao.completeActionExecutionAndEnqueue(invocationId, status, now, result) ==
        ActionCompletionOutboxCommitResult.Committed

    override suspend fun replayResult(
        invocationId: String,
        status: String,
        result: OutboundMessage,
    ): Boolean = dao.enqueueCompletedActionResult(invocationId, status, result)
}

fun interface RegisteredActionExecutor<T> {
    suspend fun dispatch(handle: T, replyText: String?): Boolean
}

fun interface ActionResultRowEncoder {
    suspend fun encode(input: ActionResultInput): OutboundMessage
}

fun interface ActionClaimWakeScheduler {
    fun schedule(dueAt: Long)
}

class ActionInvocationProcessor<T>(
    private val journal: ActionClaimJournal,
    private val registryLookup: (canonId: String, sequence: Long, actionId: String) -> ActionLookup,
    private val sourceActive: (sourceKey: String) -> Boolean,
    private val supportsReply: (handle: T) -> Boolean,
    private val executor: RegisteredActionExecutor<T>,
    private val resultEncoder: ActionResultRowEncoder,
    private val wakeScheduler: ActionClaimWakeScheduler,
    private val clock: () -> Long = { System.currentTimeMillis().coerceAtLeast(0L) },
) {
    suspend fun process(request: ActionInvokeRequest): ActionProcessResult {
        val now = clock()
        val execution = ActionExecution(
            invocationId = request.invocationId,
            canonId = request.canonId,
            actionId = request.actionId,
            state = "CLAIMED",
            resultStatus = null,
            claimedAt = now,
            completedAt = null,
        )
        return when (val claim = journal.claim(request.inbound, execution, now)) {
            ActionClaimDecision.IdConflict -> ActionProcessResult.IdConflict
            ActionClaimDecision.InFlight -> ActionProcessResult.InFlight
            is ActionClaimDecision.Replay -> replay(request, claim.status)
            ActionClaimDecision.Execute -> executeClaimed(request, now)
        }
    }

    private suspend fun replay(request: ActionInvokeRequest, status: String): ActionProcessResult {
        val result = resultEncoder.encode(
            ActionResultInput(request.invocationId, request.canonId, status),
        )
        return if (journal.replayResult(request.invocationId, status, result)) {
            ActionProcessResult.Replayed(status)
        } else {
            ActionProcessResult.CompletionLost
        }
    }

    private suspend fun executeClaimed(request: ActionInvokeRequest, claimedAt: Long): ActionProcessResult {
        wakeScheduler.schedule(saturatingAdd(claimedAt, CLAIM_GRACE_MS))
        val validation = validate(request, claimedAt)
        if (validation is Validation.Failed) return complete(request, validation.status, clock())

        val found = validation as Validation.Ready<T>
        val dispatched = try {
            executor.dispatch(found.handle, request.replyText)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            false
        }
        return complete(request, if (dispatched) "dispatched" else "failed", clock())
    }

    private fun validate(request: ActionInvokeRequest, now: Long): Validation<T> {
        if (request.invokedAt > now || elapsedAfter(request.invokedAt, now) > INVOKE_TTL_MS) {
            return Validation.Failed("expired")
        }
        if (request.replyText != null && request.replyText.toByteArray(Charsets.UTF_8).size > MAX_REPLY_BYTES) {
            return Validation.Failed("failed")
        }
        val lookup = registryLookup(request.canonId, request.notificationSequence, request.actionId)
        if (lookup !is ActionLookup.Found<*>) return Validation.Failed("action_gone")
        @Suppress("UNCHECKED_CAST")
        val found = lookup as ActionLookup.Found<T>
        if (found.generation.sourceKey.isEmpty() || !sourceActive(found.generation.sourceKey)) {
            return Validation.Failed("notification_gone")
        }
        if (request.replyText != null && !supportsReply(found.handle)) {
            return Validation.Failed("failed")
        }
        return Validation.Ready(found.handle)
    }

    private suspend fun complete(
        request: ActionInvokeRequest,
        status: String,
        now: Long,
    ): ActionProcessResult {
        val result = resultEncoder.encode(
            ActionResultInput(request.invocationId, request.canonId, status),
        )
        return if (journal.completeAndEnqueue(request.invocationId, status, now, result)) {
            ActionProcessResult.Completed(status)
        } else {
            ActionProcessResult.CompletionLost
        }
    }

    private sealed interface Validation<out T> {
        data class Ready<T>(val handle: T) : Validation<T>
        data class Failed(val status: String) : Validation<Nothing>
    }

    private fun elapsedAfter(start: Long, end: Long): Long =
        if (end >= start) end - start else Long.MAX_VALUE

    private fun saturatingAdd(value: Long, delta: Long): Long =
        if (value > Long.MAX_VALUE - delta) Long.MAX_VALUE else value + delta

    private companion object {
        const val INVOKE_TTL_MS = 120_000L
        const val CLAIM_GRACE_MS = 60_000L
        const val MAX_REPLY_BYTES = 4_096
    }
}
