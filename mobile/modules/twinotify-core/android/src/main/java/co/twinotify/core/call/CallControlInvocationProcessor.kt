package co.twinotify.core.call

import android.app.ActivityOptions
import android.app.PendingIntent
import android.content.Context
import android.os.Bundle
import co.twinotify.core.actions.ActionClaimDecision
import co.twinotify.core.actions.PersistentActionClaimWakeScheduler
import co.twinotify.core.storage.ActionCompletionOutboxCommitResult
import co.twinotify.core.storage.ActionExecution
import co.twinotify.core.storage.InboundMessage
import co.twinotify.core.storage.OutboundMessage
import co.twinotify.core.storage.ReliableDeliveryDao
import kotlinx.coroutines.CancellationException

data class CallControlInvokeRequest(
    val inbound: InboundMessage,
    val invocationId: String,
    val canonId: String,
    val callSessionId: String,
    val callSequence: Long,
    val controlId: String,
    val kind: CallControlKind,
    val invokedAt: Long,
)

data class LocalCallControlState(val sequence: Long, val direction: CallDirection)

sealed interface CallControlProcessResult {
    data object InFlight : CallControlProcessResult
    data object IdConflict : CallControlProcessResult
    data class Replayed(val status: String) : CallControlProcessResult
    data class Completed(val status: String) : CallControlProcessResult
    data object CompletionLost : CallControlProcessResult
}

interface CallControlClaimJournal {
    suspend fun claim(inbound: InboundMessage, execution: ActionExecution, now: Long): ActionClaimDecision
    suspend fun completeAndEnqueue(invocationId: String, status: String, now: Long, result: OutboundMessage): Boolean
    suspend fun replayResult(invocationId: String, status: String, result: OutboundMessage): Boolean
}

class DaoCallControlClaimJournal(private val dao: ReliableDeliveryDao) : CallControlClaimJournal {
    override suspend fun claim(inbound: InboundMessage, execution: ActionExecution, now: Long) =
        dao.claimCallControlInvocation(inbound, execution, now)
    override suspend fun completeAndEnqueue(invocationId: String, status: String, now: Long, result: OutboundMessage) =
        dao.completeCallControlExecutionAndEnqueue(invocationId, status, now, result) ==
            ActionCompletionOutboxCommitResult.Committed
    override suspend fun replayResult(invocationId: String, status: String, result: OutboundMessage) =
        dao.enqueueCompletedCallControlResult(invocationId, status, result)
}

fun interface CallControlExecutor<T> { suspend fun dispatch(handle: T): Boolean }
fun interface CallControlResultRowEncoder { suspend fun encode(input: CallControlResultInput): OutboundMessage }
fun interface CallControlClaimWakeScheduler { fun schedule(dueAt: Long) }

class CallControlInvocationProcessor<T>(
    private val journal: CallControlClaimJournal,
    private val currentLocalCallState: suspend (canonId: String) -> LocalCallControlState?,
    private val registryLookup: (String, Long, String, CallControlKind) -> CallCapabilityLookup<T>,
    private val executor: CallControlExecutor<T>,
    private val resultEncoder: CallControlResultRowEncoder,
    private val wakeScheduler: CallControlClaimWakeScheduler,
    private val beforeDispatch: suspend () -> Unit = {},
    private val clock: () -> Long = { System.currentTimeMillis().coerceAtLeast(0L) },
) {
    suspend fun process(request: CallControlInvokeRequest): CallControlProcessResult {
        val now = clock()
        val execution = ActionExecution(
            request.invocationId, request.canonId, "${request.kind.wire}:${request.callSequence}",
            "CLAIMED", null, now, null,
        )
        return when (val claim = journal.claim(request.inbound, execution, now)) {
            ActionClaimDecision.IdConflict -> CallControlProcessResult.IdConflict
            ActionClaimDecision.InFlight -> CallControlProcessResult.InFlight
            is ActionClaimDecision.Replay -> replay(request, claim.status)
            ActionClaimDecision.Execute -> executeClaimed(request, now)
        }
    }

    private suspend fun replay(request: CallControlInvokeRequest, status: String): CallControlProcessResult {
        val row = try {
            resultEncoder.encode(CallControlResultInput(request.invocationId, request.canonId, request.kind, status))
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            return CallControlProcessResult.CompletionLost
        }
        return if (journal.replayResult(request.invocationId, status, row)) {
            CallControlProcessResult.Replayed(status)
        } else CallControlProcessResult.CompletionLost
    }

    private suspend fun executeClaimed(request: CallControlInvokeRequest, claimedAt: Long): CallControlProcessResult {
        wakeScheduler.schedule(saturatingAdd(claimedAt, CLAIM_GRACE_MS))
        val failed = validate(request, clock())
        if (failed != null) return complete(request, failed, clock())
        val handle = when (
            val lookup = registryLookup(request.canonId, request.callSequence, request.controlId, request.kind)
        ) {
            is CallCapabilityLookup.Found -> lookup.handle
            else -> return complete(request, "capability_gone", clock())
        }
        beforeDispatch()
        val dispatched = try {
            executor.dispatch(handle)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            false
        }
        return complete(request, if (dispatched) "dispatched" else "failed", clock())
    }

    private suspend fun validate(request: CallControlInvokeRequest, now: Long): String? {
        if (request.invocationId != request.controlId) return "failed"
        val expired = if (request.invokedAt > now) request.invokedAt - now > MAX_FUTURE_SKEW_MS
        else now - request.invokedAt > CallControlEncoder.INVOKE_TTL_MS
        if (expired) return "expired"
        if (request.canonId != "call:${request.callSessionId}") return "call_gone"
        val state = currentLocalCallState(request.canonId) ?: return "call_gone"
        if (state.sequence != request.callSequence) return "stale_state"
        if (state.direction != CallDirection.INCOMING) return "call_gone"
        return null
    }

    private suspend fun complete(request: CallControlInvokeRequest, status: String, now: Long): CallControlProcessResult {
        val row = try {
            resultEncoder.encode(CallControlResultInput(request.invocationId, request.canonId, request.kind, status))
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            return CallControlProcessResult.CompletionLost
        }
        return if (journal.completeAndEnqueue(request.invocationId, status, now, row)) {
            CallControlProcessResult.Completed(status)
        } else CallControlProcessResult.CompletionLost
    }

    private fun saturatingAdd(value: Long, delta: Long) = if (value > Long.MAX_VALUE - delta) Long.MAX_VALUE else value + delta

    companion object {
        const val CLAIM_GRACE_MS = 60_000L
        const val MAX_FUTURE_SKEW_MS = 30_000L

        /**
         * Dialers differ: decline and hang-up are usually broadcasts, but an answer handle can be an
         * activity launch. Sending it from this process needs an explicit opt-in before Android will
         * even consider this app's own background-launch privilege; without one it is always blocked.
         */
        fun pendingIntentExecutor(context: Context): CallControlExecutor<PendingIntent> = CallControlExecutor { handle ->
            handle.send(context.applicationContext, 0, null, null, null, null, callControlSendOptions())
            true
        }

        fun callControlSendOptions(): Bundle = ActivityOptions.makeBasic()
            .setPendingIntentBackgroundActivityStartMode(ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED)
            .toBundle()
    }
}
