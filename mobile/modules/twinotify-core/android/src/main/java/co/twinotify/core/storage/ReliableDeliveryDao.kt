package co.twinotify.core.storage

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import co.twinotify.core.actions.ActionClaimDecision
import co.twinotify.core.actions.ActionExpiryCommitResult
import co.twinotify.core.actions.ActionResultCommitResult
import co.twinotify.core.actions.ActionResultRepost
import co.twinotify.core.actions.ActionResultRequest
import co.twinotify.core.call.CallControlResultRequest
import co.twinotify.core.metrics.DeliveryLatencyEvidence
import co.twinotify.core.metrics.deliveryLatencyEvidence
import co.twinotify.core.service.DirectControlCommitResult
import co.twinotify.core.service.DirectControlProcessingResult
import co.twinotify.core.service.ReceiptBackedControlResult
import co.twinotify.core.service.CallRejectionCommitResult
import co.twinotify.core.service.ActionInvokeRejectionCommitResult
import org.json.JSONObject

internal const val MAX_OUTBOUND_MESSAGES = 2_000
internal const val MAX_OUTBOUND_BYTES = 128L * 1024L * 1024L

class OutboundCapacityException : IllegalStateException("outbound_capacity")

internal fun isNotificationSnapshotCanonical(canonId: String): Boolean = !canonId.startsWith("call:")

sealed interface SequenceReservationResult {
    data class Reserved(val sequence: Long) : SequenceReservationResult
}

sealed interface InboundDesiredCommitResult {
    data object Committed : InboundDesiredCommitResult
    data class Duplicate(val outcome: String, val receiptMsgId: String?) : InboundDesiredCommitResult
    data class IdConflict(val existingSha256: String) : InboundDesiredCommitResult
    data class Stale(val latestSequence: Long) : InboundDesiredCommitResult
    data object SupersessionUnavailable : InboundDesiredCommitResult
    data class ReceiptConflict(val existingSha256: String) : InboundDesiredCommitResult
    data class MirrorIdentityCollision(val existingCanonId: String) : InboundDesiredCommitResult
}

data class SnapshotSessionKey(val peerLinkId: String, val snapshotId: String)

data class SupersessionEntry(
    val inboundMsgId: String,
    val envelopeSha256: String,
    val receipt: OutboundMessage,
)

data class SupersessionBundle(val entries: List<SupersessionEntry>)

private sealed interface SupersessionMutationResult {
    data object Applied : SupersessionMutationResult
    data object Invalid : SupersessionMutationResult
    data class ReceiptConflict(val existingSha256: String) : SupersessionMutationResult
}

sealed interface MaterializationResult {
    data object Completed : MaterializationResult
    data object AlreadyCompleted : MaterializationResult
    data object Superseded : MaterializationResult
    data object Missing : MaterializationResult
    data class ReceiptConflict(val existingSha256: String) : MaterializationResult
}

sealed interface MaterializationReceiptResult {
    data object NotNeeded : MaterializationReceiptResult
    data object Unavailable : MaterializationReceiptResult
    data class Prepared(val receipt: OutboundMessage) : MaterializationReceiptResult
    data class Conflict(val existingSha256: String) : MaterializationReceiptResult
}

sealed interface MaterializationRetryWriteResult {
    data class RetryableScheduled(val dueAt: Long) : MaterializationRetryWriteResult
    data object PermissionBlocked : MaterializationRetryWriteResult
    data object Superseded : MaterializationRetryWriteResult
}

internal fun boundedMaterializationRetryDelay(attempt: Int): Long {
    require(attempt > 0)
    var delay = 5_000L
    repeat(attempt - 1) {
        if (delay >= 300_000L / 2L) return 300_000L
        delay *= 2L
    }
    return minOf(delay, 300_000L)
}

internal fun saturatingMaterializationRetryDue(nowMs: Long, delayMs: Long): Long =
    if (nowMs > Long.MAX_VALUE - delayMs) Long.MAX_VALUE else nowMs + delayMs

enum class UserContentKind { NOTIFICATIONS, SYNC_UPDATES }

data class DeliveryQueueSnapshot(
    val pendingLocal: Int,
    val awaitingPeer: Int,
    val heldByRelay: Int,
    val internalActive: Int,
    val totalActive: Int,
    val totalActiveBytes: Long,
    val userContentKind: UserContentKind,
)

data class DeliveryQueueProjection(
    val pendingLocal: Int,
    val awaitingPeer: Int,
    val heldByRelay: Int,
    val internalActive: Int,
    val totalActive: Int,
    val totalActiveBytes: Long,
    val nonNotificationUser: Int,
)

sealed interface ReceiptTransitionResult {
    data object ReadyForRelayAck : ReceiptTransitionResult
    data object AlreadyTransitioned : ReceiptTransitionResult
    data object Missing : ReceiptTransitionResult
    data object NotReceipt : ReceiptTransitionResult
}

sealed interface OutboundStateCommitResult {
    data class Committed(val compacted: Int) : OutboundStateCommitResult
    data class Stale(val latestSequence: Long) : OutboundStateCommitResult
    data object NotStateEvent : OutboundStateCommitResult
}

sealed interface CallRecoveryCommitResult {
    data class Committed(val compacted: Int) : CallRecoveryCommitResult
    data class Stale(val latestSequence: Long) : CallRecoveryCommitResult
    data object OwnershipLost : CallRecoveryCommitResult
    data object NotStateEvent : CallRecoveryCommitResult
}

sealed interface SnapshotCommitResult {
    data class Committed(val upserted: Int, val cancelled: Int) : SnapshotCommitResult
    data class Incomplete(val expected: Int, val staged: Int) : SnapshotCommitResult
    data class DigestMismatch(val expected: String, val actual: String) : SnapshotCommitResult
    data class InvalidItem(val canonId: String) : SnapshotCommitResult
    data class Expired(val snapshotAgeMs: Long) : SnapshotCommitResult
    data object MissingBegin : SnapshotCommitResult
}

sealed interface SnapshotBeginResult {
    data class Started(val baselineCount: Int) : SnapshotBeginResult
}

sealed interface SnapshotStageResult {
    data object Staged : SnapshotStageResult
    data object MissingBegin : SnapshotStageResult
    data object OriginMismatch : SnapshotStageResult
}

sealed interface TerminalMovementResult {
    data object Moved : TerminalMovementResult
    data object AlreadyMoved : TerminalMovementResult
    data object Missing : TerminalMovementResult
}

sealed interface LegacyConversionResult {
    data object Converted : LegacyConversionResult
    data object AlreadyConverted : LegacyConversionResult
    data class Conflict(val existingSha256: String) : LegacyConversionResult
}

sealed interface ActionInvocationOutboxCommitResult {
    data object Committed : ActionInvocationOutboxCommitResult
    data object AlreadyCommitted : ActionInvocationOutboxCommitResult
    data object InvocationConflict : ActionInvocationOutboxCommitResult
    data object OutboundConflict : ActionInvocationOutboxCommitResult
}

sealed interface ActionCompletionOutboxCommitResult {
    data object Committed : ActionCompletionOutboxCommitResult
    data class AlreadyCompleted(val status: String) : ActionCompletionOutboxCommitResult
    data object MissingClaim : ActionCompletionOutboxCommitResult
    data object OutboundConflict : ActionCompletionOutboxCommitResult
}

sealed interface CustodyAcceptanceResult {
    data object Missing : CustodyAcceptanceResult
    data object DeletedReceipt : CustodyAcceptanceResult
    data object Accepted : CustodyAcceptanceResult
    data object AlreadyAccepted : CustodyAcceptanceResult
}

sealed interface RelayReceiptResult {
    data object Missing : RelayReceiptResult
    data object Deleted : RelayReceiptResult
    data object AlreadyTerminal : RelayReceiptResult
    data class Conflict(val existingDigest: String) : RelayReceiptResult
}

sealed interface LegacyForwardResult {
    data object Missing : LegacyForwardResult
    data object Deleted : LegacyForwardResult
    data object AlreadyTerminal : LegacyForwardResult
}

interface LegacyOutboxStore {
    suspend fun legacyBatch(limit: Int): List<LegacyOutboundEvent>
    suspend fun convertLegacy(legacyId: Long, row: OutboundMessage): LegacyConversionResult
}

@Dao
abstract class ReliableDeliveryDao : LegacyOutboxStore, UiActivityStore {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    abstract suspend fun insertActionInvocation(row: ActionInvocation)

    @Query("SELECT * FROM action_invocation WHERE invocationId=:invocationId")
    abstract suspend fun actionInvocation(invocationId: String): ActionInvocation?

    @Query(
        "UPDATE action_invocation SET state=:state, replyText=NULL, updatedAt=:now " +
            "WHERE invocationId=:invocationId AND state='PENDING'",
    )
    abstract suspend fun terminalizeActionInvocation(
        invocationId: String,
        state: String,
        now: Long,
    ): Int

    @Query(
        "SELECT * FROM action_invocation WHERE canonId=:canonId AND notificationSequence=:sequence " +
            "ORDER BY updatedAt DESC, invocationId DESC",
    )
    abstract fun actionInvocationsForNotification(
        canonId: String,
        sequence: Long,
    ): List<ActionInvocation>

    @Query(
        "SELECT * FROM action_invocation WHERE state='PENDING' AND expiresAt <= :now " +
            "ORDER BY expiresAt, invocationId",
    )
    abstract suspend fun dueActionInvocations(now: Long): List<ActionInvocation>

    @Query("SELECT MIN(expiresAt) FROM action_invocation WHERE state='PENDING'")
    abstract suspend fun earliestPendingActionInvocationAt(): Long?

    @Transaction
    open suspend fun expireActionInvocation(
        expected: ActionInvocation,
        now: Long,
    ): ActionExpiryCommitResult {
        val current = actionInvocation(expected.invocationId) ?: return ActionExpiryCommitResult.Lost
        if (current != expected || current.state != "PENDING" || current.expiresAt > now) {
            return ActionExpiryCommitResult.Lost
        }
        if (terminalizeActionInvocation(current.invocationId, "EXPIRED", now) != 1) {
            return ActionExpiryCommitResult.Lost
        }
        val canonical = canonical(current.canonId)
        return ActionExpiryCommitResult.Expired(
            repost = canonical?.state == "ACTIVE" &&
                canonical.latestSequence == current.notificationSequence &&
                canonical.mirrorLocalTag != null && canonical.mirrorLocalId != null,
        )
    }

    @Transaction
    open suspend fun commitActionResult(
        row: InboundMessage,
        invocationId: String,
        canonId: String,
        status: String,
    ): ActionResultCommitResult = commitActionResult(
        ActionResultRequest(row, invocationId, canonId, status),
    )

    @Transaction
    open suspend fun commitActionResult(request: ActionResultRequest): ActionResultCommitResult =
        commitControlResultBody(
            row = request.inbound,
            invocationId = request.invocationId,
            canonId = request.canonId,
            status = request.status,
            expectedEventType = "notif.action.result",
            allowedStatuses = ACTION_RESULT_STATUSES,
            expectedActionId = null,
            terminalState = ::notificationActionTerminalState,
        )

    @Transaction
    open suspend fun commitCallControlResult(request: CallControlResultRequest): ActionResultCommitResult =
        commitControlResultBody(
            row = request.inbound,
            invocationId = request.invocationId,
            canonId = request.canonId,
            status = request.status,
            expectedEventType = "call.control.result",
            allowedStatuses = CALL_CONTROL_RESULT_STATUSES,
            expectedActionId = request.kind.wire,
            terminalState = ::callControlTerminalState,
        )

    private suspend fun commitControlResultBody(
        row: InboundMessage,
        invocationId: String,
        canonId: String,
        status: String,
        expectedEventType: String,
        allowedStatuses: Set<String>,
        expectedActionId: String?,
        terminalState: (String) -> String,
    ): ActionResultCommitResult {
        require(row.eventType == expectedEventType)
        require(row.canonId == null && row.sequence == null)
        require(row.outcome == "APPLIED" && row.appliedAt != null && row.relayAckState == "READY")
        require(status in allowedStatuses)

        inbound(row.msgId, row.peerLinkId)?.let { existing ->
            return if (existing.envelopeSha256 == row.envelopeSha256) {
                ActionResultCommitResult.Duplicate
            } else {
                ActionResultCommitResult.IdConflict
            }
        }
        insertInbound(row)

        val invocation = actionInvocation(invocationId)
            ?: return ActionResultCommitResult.Committed(repost = null)
        if (
            invocation.peerLinkId != row.peerLinkId || invocation.canonId != canonId || invocation.state != "PENDING" ||
            (expectedActionId != null && invocation.actionId != expectedActionId)
        ) {
            return ActionResultCommitResult.Committed(repost = null)
        }
        if (terminalizeActionInvocation(invocation.invocationId, terminalState(status), row.committedAt) != 1) {
            return ActionResultCommitResult.Committed(repost = null)
        }
        val canonical = canonical(invocation.canonId)
        val repost = if (
            canonical?.state == "ACTIVE" &&
            canonical.latestSequence == invocation.notificationSequence &&
            canonical.mirrorLocalTag != null && canonical.mirrorLocalId != null
        ) {
            ActionResultRepost(
                canonId = invocation.canonId,
                notificationSequence = invocation.notificationSequence,
                localTag = canonical.mirrorLocalTag,
                localId = canonical.mirrorLocalId,
            )
        } else {
            null
        }
        return ActionResultCommitResult.Committed(repost)
    }

    private fun notificationActionTerminalState(status: String): String = when (status) {
        "dispatched" -> "DISPATCHED"
        "outcome_unknown" -> "OUTCOME_UNKNOWN"
        "action_gone" -> "ACTION_GONE"
        "notification_gone" -> "NOTIFICATION_GONE"
        "expired" -> "EXPIRED"
        "failed" -> "FAILED"
        else -> error("validated action result status drift")
    }

    private fun callControlTerminalState(status: String): String = when (status) {
        "dispatched" -> "DISPATCHED"
        "outcome_unknown" -> "OUTCOME_UNKNOWN"
        "capability_gone" -> "ACTION_GONE"
        "call_gone" -> "NOTIFICATION_GONE"
        "stale_state", "failed" -> "FAILED"
        "expired" -> "EXPIRED"
        else -> error("validated call-control result status drift")
    }

    @Insert(onConflict = OnConflictStrategy.ABORT)
    abstract suspend fun insertActionExecution(row: ActionExecution)

    @Query("SELECT * FROM action_execution WHERE invocationId=:invocationId AND peerLinkId=:peerLinkId")
    abstract suspend fun actionExecution(invocationId: String, peerLinkId: String = LEGACY_PEER_LINK_ID): ActionExecution?

    @Query(
        "SELECT * FROM action_execution WHERE state='CLAIMED' AND claimedAt <= :cutoffClaimedAt AND (:peerLinkId IS NULL OR peerLinkId=:peerLinkId) " +
            "ORDER BY claimedAt, invocationId",
    )
    abstract suspend fun dueActionExecutionClaims(cutoffClaimedAt: Long, peerLinkId: String? = null): List<ActionExecution>

    @Query("SELECT MIN(claimedAt) FROM action_execution WHERE state='CLAIMED' AND (:peerLinkId IS NULL OR peerLinkId=:peerLinkId)")
    abstract suspend fun earliestActionExecutionClaimedAt(peerLinkId: String? = null): Long?

    @Query(
        "UPDATE action_execution SET state='COMPLETED', resultStatus=:status, completedAt=:now " +
            "WHERE invocationId=:invocationId AND peerLinkId=:peerLinkId AND state='CLAIMED'",
    )
    abstract suspend fun completeActionExecutionClaim(
        invocationId: String,
        status: String,
        now: Long,
        peerLinkId: String = LEGACY_PEER_LINK_ID,
    ): Int

    @Transaction
    open suspend fun claimActionInvocation(
        row: InboundMessage,
        execution: ActionExecution,
        now: Long,
    ): ActionClaimDecision = claimControlInvocationBody(row, execution, now, "notif.action.invoke")

    @Transaction
    open suspend fun claimCallControlInvocation(
        row: InboundMessage,
        execution: ActionExecution,
        now: Long,
    ): ActionClaimDecision = claimControlInvocationBody(row, execution, now, "call.control.invoke")

    private suspend fun claimControlInvocationBody(
        row: InboundMessage,
        execution: ActionExecution,
        now: Long,
        expectedEventType: String,
    ): ActionClaimDecision {
        require(row.eventType == expectedEventType)
        require(row.canonId == null && row.sequence == null)
        require(row.outcome == "APPLIED" && row.appliedAt != null)
        require(row.relayAckState == "READY")
        require(execution.state == "CLAIMED")
        if (row.peerLinkId != LEGACY_PEER_LINK_ID) {
            check(deliveryPeerLink(row.peerLinkId)?.let { it.lifecycle == "ACTIVE" && it.deviceId == row.originDevice } == true) {
                "peer_link_inactive"
            }
        }

        val existingInbound = inbound(row.msgId, row.peerLinkId)
        if (existingInbound != null && existingInbound.envelopeSha256 != row.envelopeSha256) {
            return ActionClaimDecision.IdConflict
        }
        val existingExecution = actionExecution(execution.invocationId, row.peerLinkId)
        if (existingExecution != null && (
                existingExecution.canonId != execution.canonId ||
                    existingExecution.actionId != execution.actionId
                )
        ) {
            return ActionClaimDecision.IdConflict
        }

        if (existingExecution == null) {
            if (existingInbound == null) insertInbound(row)
            insertActionExecution(execution.copy(claimedAt = now, peerLinkId = row.peerLinkId))
            return ActionClaimDecision.Execute
        }

        if (existingInbound == null) insertInbound(row)
        if (existingExecution.state == "COMPLETED") {
            return ActionClaimDecision.Replay(requireNotNull(existingExecution.resultStatus))
        }
        if (claimGraceElapsed(existingExecution.claimedAt, now)) {
            check(
                completeActionExecutionClaim(
                    existingExecution.invocationId,
                    "outcome_unknown",
                    now,
                    row.peerLinkId,
                ) == 1,
            )
            return ActionClaimDecision.Replay("outcome_unknown")
        }
        return ActionClaimDecision.InFlight
    }

    @Transaction
    open suspend fun commitActionInvocationAndOutbound(
        invocation: ActionInvocation,
        invoke: OutboundMessage,
    ): ActionInvocationOutboxCommitResult =
        commitControlInvocationAndOutboundBody(invocation, invoke, "notif.action.invoke")

    @Transaction
    open suspend fun commitCallControlInvocationAndOutbound(
        invocation: ActionInvocation,
        invoke: OutboundMessage,
    ): ActionInvocationOutboxCommitResult =
        commitControlInvocationAndOutboundBody(invocation, invoke, "call.control.invoke")

    private suspend fun commitControlInvocationAndOutboundBody(
        invocation: ActionInvocation,
        invoke: OutboundMessage,
        eventType: String,
    ): ActionInvocationOutboxCommitResult {
        require(invocation.state == "PENDING")
        require(invoke.eventType == eventType)
        require(invoke.canonId == null && invoke.sequence == null)
        require(!invoke.requiresPeerReceipt)
        require(invocation.peerLinkId == invoke.peerLinkId)
        if (invoke.peerLinkId != LEGACY_PEER_LINK_ID) {
            val target = canonical(invocation.canonId)
            if (target == null || target.peerLinkId != invoke.peerLinkId || target.state != "ACTIVE" ||
                target.latestSequence != invocation.notificationSequence) return ActionInvocationOutboxCommitResult.InvocationConflict
        }

        val existingInvocation = actionInvocation(invocation.invocationId)
        if (existingInvocation != null) {
            return if (existingInvocation == invocation && outboundMessage(invoke.msgId) == invoke) {
                ActionInvocationOutboxCommitResult.AlreadyCommitted
            } else {
                ActionInvocationOutboxCommitResult.InvocationConflict
            }
        }
        if (
            eventType == "call.control.invoke" &&
            actionInvocationsForNotification(invocation.canonId, invocation.notificationSequence).isNotEmpty()
        ) {
            return ActionInvocationOutboxCommitResult.InvocationConflict
        }
        if (outboundMessage(invoke.msgId) != null) {
            return ActionInvocationOutboxCommitResult.OutboundConflict
        }
        insertActionInvocation(invocation)
        insertOutbound(invoke)
        return ActionInvocationOutboxCommitResult.Committed
    }

    @Transaction
    open suspend fun completeActionExecutionAndEnqueue(
        invocationId: String,
        status: String,
        now: Long,
        result: OutboundMessage,
    ): ActionCompletionOutboxCommitResult = completeControlExecutionAndEnqueueBody(
        invocationId, status, now, result, "notif.action.result", ACTION_RESULT_STATUSES,
    )

    @Transaction
    open suspend fun completeCallControlExecutionAndEnqueue(
        invocationId: String,
        status: String,
        now: Long,
        result: OutboundMessage,
    ): ActionCompletionOutboxCommitResult = completeControlExecutionAndEnqueueBody(
        invocationId, status, now, result, "call.control.result", CALL_CONTROL_RESULT_STATUSES,
    )

    private suspend fun completeControlExecutionAndEnqueueBody(
        invocationId: String,
        status: String,
        now: Long,
        result: OutboundMessage,
        expectedEventType: String,
        allowedStatuses: Set<String>,
    ): ActionCompletionOutboxCommitResult {
        require(status in allowedStatuses)
        require(result.eventType == expectedEventType)
        require(result.canonId == null && result.sequence == null)
        require(!result.requiresPeerReceipt)

        val execution = actionExecution(invocationId, result.peerLinkId)
            ?: return ActionCompletionOutboxCommitResult.MissingClaim
        if (execution.state == "COMPLETED") {
            return ActionCompletionOutboxCommitResult.AlreadyCompleted(requireNotNull(execution.resultStatus))
        }
        if (outboundMessage(result.msgId) != null) {
            return ActionCompletionOutboxCommitResult.OutboundConflict
        }
        check(completeActionExecutionClaim(invocationId, status, now, result.peerLinkId) == 1)
        insertOutbound(result)
        return ActionCompletionOutboxCommitResult.Committed
    }

    @Transaction
    open suspend fun enqueueCompletedActionResult(
        invocationId: String,
        status: String,
        result: OutboundMessage,
    ): Boolean = enqueueCompletedControlResultBody(
        invocationId, status, result, "notif.action.result", ACTION_RESULT_STATUSES,
    )

    @Transaction
    open suspend fun enqueueCompletedCallControlResult(
        invocationId: String,
        status: String,
        result: OutboundMessage,
    ): Boolean = enqueueCompletedControlResultBody(
        invocationId, status, result, "call.control.result", CALL_CONTROL_RESULT_STATUSES,
    )

    private suspend fun enqueueCompletedControlResultBody(
        invocationId: String,
        status: String,
        result: OutboundMessage,
        expectedEventType: String,
        allowedStatuses: Set<String>,
    ): Boolean {
        require(status in allowedStatuses)
        require(result.eventType == expectedEventType)
        require(result.canonId == null && result.sequence == null)
        require(!result.requiresPeerReceipt)
        val execution = actionExecution(invocationId, result.peerLinkId) ?: return false
        if (execution.state != "COMPLETED" || execution.resultStatus != status) return false
        val existing = outboundMessage(result.msgId)
        if (existing != null) return existing == result
        insertOutbound(result)
        return true
    }

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun putNotificationDetail(row: NotificationDetailCache)

    @Query("SELECT * FROM notification_detail_cache WHERE detailId=:detailId")
    abstract suspend fun notificationDetail(detailId: String): NotificationDetailCache?

    @Query("SELECT * FROM notification_detail_cache WHERE canonId=:canonId")
    abstract suspend fun notificationDetailForCanon(canonId: String): NotificationDetailCache?

    @Query("SELECT * FROM notification_detail_cache WHERE canonId=:canonId")
    abstract fun notificationDetailForCanonNow(canonId: String): NotificationDetailCache?

    @Query("SELECT COUNT(*) FROM notification_detail_cache WHERE cancelledAt IS NOT NULL")
    abstract suspend fun cancelledNotificationDetailCount(): Int

    @Query("DELETE FROM notification_detail_cache WHERE cancelledAt IS NOT NULL AND cancelledAt < :cutoff")
    protected abstract suspend fun deleteCancelledNotificationDetailsBefore(cutoff: Long): Int

    @Query(
        "DELETE FROM notification_detail_cache WHERE cancelledAt IS NOT NULL AND detailId NOT IN (" +
            "SELECT detailId FROM notification_detail_cache WHERE cancelledAt IS NOT NULL " +
            "ORDER BY cancelledAt DESC, updatedAt DESC, detailId DESC LIMIT :limit)",
    )
    protected abstract suspend fun trimCancelledNotificationDetails(limit: Int): Int

    @Transaction
    open suspend fun sweepNotificationDetailCache(now: Long): Int {
        require(now >= 0) { "notification detail sweep time must be non-negative" }
        var removed = deleteCancelledNotificationDetailsBefore(now - NOTIFICATION_DETAIL_CANCELLED_RETENTION_MS)
        removed += trimCancelledNotificationDetails(MAX_CANCELLED_NOTIFICATION_DETAILS)
        return removed
    }

    @Insert(onConflict = OnConflictStrategy.ABORT)
    protected abstract suspend fun insertOutboundRaw(row: OutboundMessage)

    @Query("SELECT * FROM peer_link WHERE peerLinkId=:link")
    protected abstract suspend fun deliveryPeerLink(link: String): PeerLink?

    @Query("SELECT peerLinkId FROM peer_link WHERE lifecycle='ACTIVE' ORDER BY peerLinkId")
    protected abstract suspend fun activePeerLinkIds(): List<String>

    /** Every writer shares atomic device-wide admission; no accepted row is evicted. */
    @Transaction
    open suspend fun insertOutbound(row: OutboundMessage) {
        if (row.peerLinkId != LEGACY_PEER_LINK_ID) {
            check(deliveryPeerLink(row.peerLinkId)?.lifecycle == "ACTIVE") { "peer_link_inactive" }
        }
        require(row.byteSize >= 0L)
        if (activeOutboundCount() >= MAX_OUTBOUND_MESSAGES || row.byteSize > MAX_OUTBOUND_BYTES - activeOutboundBytes()) {
            throw OutboundCapacityException()
        }
        insertOutboundRaw(row)
    }

    @Query(
        "SELECT * FROM outbound_message WHERE state IN ('NEW','ACCEPTED') " +
            "AND peerLinkId=:peerLinkId AND (peerLinkId='legacy' OR eventType='unpair' OR EXISTS " +
            "(SELECT 1 FROM peer_link p WHERE p.peerLinkId=outbound_message.peerLinkId AND p.lifecycle='ACTIVE')) " +
            "AND nextAttemptAt <= :now ORDER BY createdAt, rowid LIMIT :limit",
    )
    abstract suspend fun sendable(now: Long, limit: Int, peerLinkId: String = LEGACY_PEER_LINK_ID): List<OutboundMessage>

    @Query(
        "SELECT * FROM outbound_message WHERE protocolVersion=2 AND expiresAt <= :now AND " +
            "relayCustodyState='NONE' AND ((state='NEW' AND custodyAcceptedAt IS NULL) OR " +
            "state='ACCEPTED') ORDER BY createdAt, rowid",
    )
    protected abstract suspend fun locallyExpired(now: Long): List<OutboundMessage>

    @Query(
        "UPDATE outbound_message SET attempts=attempts + 1, nextAttemptAt=:retryAt " +
            "WHERE msgId=:msgId AND peerLinkId=:peerLinkId AND state IN ('NEW','ACCEPTED')",
    )
    abstract suspend fun markSent(msgId: String, retryAt: Long, peerLinkId: String = LEGACY_PEER_LINK_ID): Int

    @Query("DELETE FROM outbound_message WHERE msgId=:msgId")
    abstract suspend fun deleteOutbound(msgId: String): Int

    @Query("SELECT * FROM outbound_message WHERE msgId=:msgId")
    abstract suspend fun outboundMessage(msgId: String): OutboundMessage?

    @Query(
        "SELECT * FROM outbound_message WHERE peerLinkId=:peerLinkId AND eventType=:eventType AND " +
            "state NOT IN ('TERMINAL','EXPIRED') AND expiresAt > :now " +
            "ORDER BY createdAt DESC, rowid DESC LIMIT 1",
    )
    abstract suspend fun activeOutboundControl(eventType: String, now: Long, peerLinkId: String = LEGACY_PEER_LINK_ID): OutboundMessage?

    @Query("SELECT COUNT(*) FROM outbound_message WHERE state NOT IN ('TERMINAL','EXPIRED')")
    abstract suspend fun activeOutboundCount(): Int

    @Query("SELECT COALESCE(SUM(byteSize), 0) FROM outbound_message WHERE state NOT IN ('TERMINAL','EXPIRED')")
    abstract suspend fun activeOutboundBytes(): Long

    @Query(
        "SELECT " +
            "COUNT(CASE WHEN eventType IN ('notif.post','notif.update','notif.cancel','notif.action.invoke','notif.action.result','call.state','call.control.invoke','call.control.result') " +
            "AND state IN ('NEW','PENDING_PLATFORM') AND custodyAcceptedAt IS NULL THEN 1 END) AS pendingLocal, " +
            "COUNT(CASE WHEN eventType IN ('notif.post','notif.update','notif.cancel','notif.action.invoke','notif.action.result','call.state','call.control.invoke','call.control.result') " +
            "AND state='ACCEPTED' AND custodyAcceptedAt IS NOT NULL THEN 1 END) AS awaitingPeer, " +
            "COUNT(CASE WHEN eventType IN ('notif.post','notif.update','notif.cancel','notif.action.invoke','notif.action.result','call.state','call.control.invoke','call.control.result') " +
            "AND state='ACCEPTED' AND custodyAcceptedAt IS NOT NULL AND relayCustodyState='ACCEPTED' THEN 1 END) AS heldByRelay, " +
            "COUNT(CASE WHEN eventType NOT IN ('notif.post','notif.update','notif.cancel','notif.action.invoke','notif.action.result','call.state','call.control.invoke','call.control.result') THEN 1 END) AS internalActive, " +
            "COUNT(*) AS totalActive, COALESCE(SUM(byteSize), 0) AS totalActiveBytes, " +
            "COUNT(CASE WHEN eventType IN ('notif.action.invoke','notif.action.result','call.state','call.control.invoke','call.control.result') THEN 1 END) AS nonNotificationUser " +
            "FROM outbound_message WHERE state NOT IN ('TERMINAL','EXPIRED') AND (:peerLinkId IS NULL OR peerLinkId=:peerLinkId)",
    )
    protected abstract suspend fun deliveryQueueProjection(peerLinkId: String?): DeliveryQueueProjection

    @Transaction
    open suspend fun deliveryQueueSnapshot(peerLinkId: String? = null): DeliveryQueueSnapshot {
        val value = deliveryQueueProjection(peerLinkId)
        return DeliveryQueueSnapshot(
            pendingLocal = value.pendingLocal,
            awaitingPeer = value.awaitingPeer,
            heldByRelay = value.heldByRelay,
            internalActive = value.internalActive,
            totalActive = value.totalActive,
            totalActiveBytes = value.totalActiveBytes,
            userContentKind = if (value.nonNotificationUser == 0) {
                UserContentKind.NOTIFICATIONS
            } else {
                UserContentKind.SYNC_UPDATES
            },
        )
    }

    @Query("DELETE FROM activity_event WHERE occurredAt < :cutoff")
    protected abstract suspend fun deleteActivityBefore(cutoff: Long): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract override suspend fun upsertUiActivity(row: UiActivityEvent)

    @Query("SELECT * FROM ui_activity_event WHERE msgId=:msgId LIMIT 1")
    abstract override suspend fun uiActivityForMessage(msgId: String): UiActivityEvent?

    @Query("SELECT * FROM ui_activity_event ORDER BY occurredAt DESC, eventId DESC LIMIT :limit")
    abstract override suspend fun recentUiActivity(limit: Int): List<UiActivityEvent>

    @Query("SELECT * FROM verified_delivery_metric WHERE msgId=:msgId LIMIT 1")
    abstract suspend fun verifiedDeliveryMetric(msgId: String): VerifiedDeliveryMetric?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    protected abstract suspend fun insertVerifiedDeliveryMetric(row: VerifiedDeliveryMetric)

    @Query(
        "SELECT COUNT(*) FROM verified_delivery_metric " +
            "WHERE verifiedAt >= :startInclusive AND verifiedAt < :endExclusive",
    )
    protected abstract suspend fun verifiedDeliveryCount(startInclusive: Long, endExclusive: Long): Int

    @Query(
        "SELECT latencyMs FROM verified_delivery_metric WHERE latencyMs IS NOT NULL " +
            "ORDER BY verifiedAt DESC, msgId DESC LIMIT 10",
    )
    protected abstract suspend fun recentVerifiedDeliveryLatencies(): List<Long>

    @Query("DELETE FROM verified_delivery_metric WHERE verifiedAt < :cutoff")
    protected abstract suspend fun deleteVerifiedDeliveryMetricsBefore(cutoff: Long): Int

    @Query(
        "DELETE FROM verified_delivery_metric WHERE msgId NOT IN " +
            "(SELECT msgId FROM verified_delivery_metric ORDER BY verifiedAt DESC, msgId DESC LIMIT :limit)",
    )
    protected abstract suspend fun trimVerifiedDeliveryMetrics(limit: Int): Int

    @Transaction
    open suspend fun verifiedDeliverySnapshot(
        startInclusive: Long,
        endExclusive: Long,
    ): VerifiedDeliverySnapshot {
        require(startInclusive <= endExclusive)
        val latencies = recentVerifiedDeliveryLatencies()
        return VerifiedDeliverySnapshot(
            mirroredToday = verifiedDeliveryCount(startInclusive, endExclusive),
            latencyMs = if (latencies.isEmpty()) null else (latencies.sum() / latencies.size).toInt(),
        )
    }

    @Query("DELETE FROM ui_activity_event WHERE occurredAt < :cutoff")
    abstract override suspend fun deleteUiActivityBefore(cutoff: Long): Int

    @Query("DELETE FROM ui_activity_event WHERE msgId=:msgId")
    protected abstract suspend fun deleteUiActivityForMessage(msgId: String): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    protected abstract suspend fun putUiActivityContent(row: UiActivityContent)

    @Query("SELECT * FROM ui_history_policy WHERE id=0")
    abstract suspend fun uiHistoryPolicy(): UiHistoryPolicy?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    protected abstract suspend fun putUiHistoryPolicy(row: UiHistoryPolicy)

    @Query("DELETE FROM ui_activity_content")
    protected abstract suspend fun clearUiActivityContent(): Int

    @Query("DELETE FROM ui_activity_content WHERE eventId=:eventId")
    protected abstract suspend fun deleteUiActivityContent(eventId: String): Int

    @Query("DELETE FROM ui_activity_content WHERE eventId IN (SELECT eventId FROM ui_activity_event WHERE packageName=:packageName)")
    protected abstract suspend fun deleteUiActivityContentForPackage(packageName: String): Int

    @Query("DELETE FROM ui_activity_event WHERE packageName=:packageName")
    protected abstract suspend fun deleteUiActivityForPackage(packageName: String): Int

    @Query("SELECT DISTINCT packageName FROM ui_activity_event WHERE packageName IS NOT NULL")
    abstract suspend fun uiHistoryPackages(): List<String>

    @Query("SELECT eventId, byteSize FROM ui_activity_content ORDER BY createdAt ASC, eventId ASC")
    protected abstract suspend fun uiHistoryContentOldestFirst(): List<UiHistoryContentSize>

    @Query(
        "SELECT e.eventId, e.packageName, e.appName, e.direction, e.kind, e.status, e.route, e.occurredAt, " +
            "c.ciphertext AS contentCiphertext, c.iv AS contentIv " +
            "FROM ui_activity_event e LEFT JOIN ui_activity_content c ON c.eventId=e.eventId " +
            "ORDER BY e.occurredAt DESC, e.eventId DESC LIMIT :limit",
    )
    abstract suspend fun uiHistoryRows(limit: Int): List<UiHistoryStoredRow>

    @Transaction
    open suspend fun retainUiHistoryContent(row: UiActivityContent): Boolean {
        if (uiHistoryPolicy()?.contentEnabled == false) return false
        putUiActivityContent(row)
        maintainUiHistory(row.createdAt)
        return true
    }

    @Transaction
    open suspend fun setUiHistoryContentEnabled(enabled: Boolean) {
        val current = uiHistoryPolicy()
        putUiHistoryPolicy(
            UiHistoryPolicy(contentEnabled = enabled, retentionDays = current?.retentionDays ?: 30),
        )
        if (!enabled) clearUiActivityContent()
    }

    @Transaction
    open suspend fun setUiHistoryRetentionDays(days: Int, now: Long) {
        require(days in setOf(7, 30))
        val current = uiHistoryPolicy()
        putUiHistoryPolicy(
            UiHistoryPolicy(contentEnabled = current?.contentEnabled ?: true, retentionDays = days),
        )
        maintainUiHistory(now)
    }

    @Transaction
    open suspend fun maintainUiHistory(now: Long) {
        require(now >= 0)
        val days = uiHistoryPolicy()?.retentionDays ?: 30
        deleteUiActivityBefore(now - days * 24L * 60L * 60L * 1_000L)
        trimUiActivityToLimit(UiActivityJournal.MAX_ROWS)
        var totalBytes = uiHistoryContentOldestFirst().sumOf(UiHistoryContentSize::byteSize)
        if (totalBytes <= UI_HISTORY_CONTENT_MAX_BYTES) return
        for (row in uiHistoryContentOldestFirst()) {
            if (totalBytes <= UI_HISTORY_CONTENT_MAX_BYTES) break
            if (deleteUiActivityContent(row.eventId) == 1) totalBytes -= row.byteSize
        }
    }

    @Transaction
    open suspend fun clearUiHistory() {
        clearUiActivityContent()
        clearUiActivityEvents()
    }

    @Transaction
    open suspend fun clearUiHistoryForPackage(packageName: String) {
        deleteUiActivityContentForPackage(packageName)
        deleteUiActivityForPackage(packageName)
    }

    @Query(
        "DELETE FROM ui_activity_event WHERE eventId NOT IN " +
            "(SELECT eventId FROM ui_activity_event ORDER BY occurredAt DESC, eventId DESC LIMIT :limit)",
    )
    abstract override suspend fun trimUiActivityToLimit(limit: Int): Int

    @Query("DELETE FROM inbound_message WHERE committedAt < :cutoff AND outcome IN ('APPLIED','STALE','REJECTED')")
    protected abstract suspend fun deleteInboundBefore(cutoff: Long): Int

    @Query("DELETE FROM canonical_notification_state WHERE state='CANCELLED' AND peerCancelPending=0 AND updatedAt < :cutoff AND canonId NOT IN (SELECT canonId FROM inbound_message WHERE outcome='PENDING_PLATFORM')")
    protected abstract suspend fun deleteCancelledBefore(cutoff: Long): Int

    @Query("DELETE FROM materialization_retry WHERE canonId NOT IN (SELECT canonId FROM canonical_notification_state)")
    protected abstract suspend fun deleteOrphanMaterializationRetries(): Int

    @Query("DELETE FROM outbound_message WHERE state IN ('TERMINAL','EXPIRED') AND createdAt < :cutoff")
    protected abstract suspend fun deleteTerminalOutboundBefore(cutoff: Long): Int

    @Query("DELETE FROM action_execution WHERE state='COMPLETED' AND completedAt < :cutoff")
    protected abstract suspend fun deleteCompletedActionExecutionsBefore(cutoff: Long): Int

    /** Bounded, idempotent maintenance for terminal history and persisted cancellation tombstones. */
    @Transaction
    open suspend fun sweepRetention(now: Long, activityRetentionMs: Long, tombstoneRetentionMs: Long): Int {
        require(activityRetentionMs >= 0)
        require(tombstoneRetentionMs >= 0)
        var removed = 0
        removed += expireLocal(now)
        removed += deleteActivityBefore(now - activityRetentionMs)
        removed += deleteInboundBefore(now - activityRetentionMs)
        removed += deleteTerminalOutboundBefore(now - activityRetentionMs)
        removed += deleteCompletedActionExecutionsBefore(now - ACTION_EXECUTION_RETENTION_MS)
        removed += deleteCancelledBefore(now - tombstoneRetentionMs)
        removed += sweepNotificationDetailCache(now)
        removed += deleteOrphanMaterializationRetries()
        removed += expireSnapshotStages(now - SNAPSHOT_TTL_MS)
        return removed
    }

    @Query("SELECT * FROM canonical_notification_state WHERE peerLinkId=:link")
    abstract suspend fun mirrorStatesForPeer(link: String): List<CanonicalNotificationState>

    @Query("UPDATE peer_link SET lifecycle='REMOVING' WHERE peerLinkId=:link")
    protected abstract suspend fun markPeerRemoving(link: String): Int

    @Transaction
    open suspend fun beginPeerRemoval(link: String, control: OutboundMessage? = null) {
        val peer = deliveryPeerLink(link) ?: return
        if (peer.lifecycle == "REMOVING") return
        if (control != null) {
            require(control.peerLinkId == link && control.eventType == "unpair" && !control.requiresPeerReceipt)
            insertOutbound(control)
        }
        check(markPeerRemoving(link) == 1)
    }

    @Query("DELETE FROM outbound_message WHERE peerLinkId=:link")
    protected abstract suspend fun deletePeerOutbound(link: String)
    @Query("DELETE FROM inbound_message WHERE peerLinkId=:link")
    protected abstract suspend fun deletePeerInbound(link: String)
    @Query("DELETE FROM snapshot_stage WHERE peerLinkId=:link")
    protected abstract suspend fun deletePeerSnapshots(link: String)
    @Query("DELETE FROM action_invocation WHERE peerLinkId=:link")
    protected abstract suspend fun deletePeerInvocations(link: String)
    @Query("DELETE FROM action_execution WHERE peerLinkId=:link")
    protected abstract suspend fun deletePeerExecutions(link: String)
    @Query("DELETE FROM materialization_retry WHERE canonId IN (SELECT canonId FROM canonical_notification_state WHERE peerLinkId=:link)")
    protected abstract suspend fun deletePeerRetries(link: String)
    @Query("DELETE FROM notification_detail_cache WHERE canonId IN (SELECT canonId FROM canonical_notification_state WHERE peerLinkId=:link)")
    protected abstract suspend fun deletePeerDetails(link: String)
    @Query("DELETE FROM canonical_notification_state WHERE peerLinkId=:link")
    protected abstract suspend fun deletePeerMirrors(link: String)

    /** Source state, origin counters, and every other peer's ciphertext survive removal. */
    @Transaction
    open suspend fun purgePeerDelivery(link: String) {
        val peer = deliveryPeerLink(link) ?: return
        check(peer.lifecycle == "REMOVING") { "peer_removal_not_started" }
        deletePeerOutbound(link)
        deletePeerInbound(link)
        deletePeerSnapshots(link)
        deletePeerInvocations(link)
        deletePeerExecutions(link)
        deletePeerRetries(link)
        deletePeerDetails(link)
        deletePeerMirrors(link)
    }

    @Transaction
    open suspend fun clearReliableState() {
        clearOutboundMessages()
        clearInboundMessages()
        clearCanonicalStates()
        clearOriginSequences()
        clearActivityEvents()
        clearUiActivityEvents()
        clearVerifiedDeliveryMetrics()
        clearSnapshotStages()
        clearMaterializationRetries()
        clearActionInvocations()
        clearActionExecutions()
        clearNotificationDetails()
    }

    @Query("DELETE FROM outbound_message")
    protected abstract suspend fun clearOutboundMessages()
    @Query("DELETE FROM inbound_message")
    protected abstract suspend fun clearInboundMessages()
    @Query("DELETE FROM canonical_notification_state")
    protected abstract suspend fun clearCanonicalStates()
    @Query("DELETE FROM origin_sequence")
    protected abstract suspend fun clearOriginSequences()
    @Query("DELETE FROM activity_event")
    protected abstract suspend fun clearActivityEvents()
    @Query("DELETE FROM ui_activity_event")
    protected abstract suspend fun clearUiActivityEvents()
    @Query("DELETE FROM verified_delivery_metric")
    protected abstract suspend fun clearVerifiedDeliveryMetrics()
    @Query("DELETE FROM snapshot_stage")
    protected abstract suspend fun clearSnapshotStages()
    @Query("DELETE FROM materialization_retry")
    protected abstract suspend fun clearMaterializationRetries()
    @Query("DELETE FROM action_invocation")
    protected abstract suspend fun clearActionInvocations()
    @Query("DELETE FROM action_execution")
    protected abstract suspend fun clearActionExecutions()
    @Query("DELETE FROM notification_detail_cache")
    protected abstract suspend fun clearNotificationDetails()

    @Query(
        "UPDATE outbound_message SET state='ACCEPTED', custodyAcceptedAt=:acceptedAt, " +
            "custodyRoute=:route, " +
            "nextAttemptAt=:retryAt WHERE msgId=:msgId AND state='NEW'",
    )
    protected abstract suspend fun acceptNewCustody(
        msgId: String,
        route: String,
        acceptedAt: Long,
        retryAt: Long,
    ): Int

    @Query(
        "UPDATE outbound_message SET relayCustodyState='ACCEPTED' WHERE msgId=:msgId " +
            "AND state IN ('NEW','ACCEPTED') AND relayCustodyState IN ('NONE','UNKNOWN')",
    )
    protected abstract suspend fun markRelayCustodyAccepted(msgId: String): Int

    @Query("SELECT msgId, envelopeSha256 FROM inbound_message WHERE peerLinkId=:peerLinkId AND relayAckState='READY' ORDER BY committedAt LIMIT :limit")
    abstract suspend fun readyRelayAcks(limit: Int, peerLinkId: String = LEGACY_PEER_LINK_ID): List<co.twinotify.core.service.RelayAckRecord>

    @Query("UPDATE inbound_message SET relayAckState='SENT' WHERE msgId=:msgId AND peerLinkId=:peerLinkId AND envelopeSha256=:envelopeSha256 AND relayAckState='READY'")
    abstract suspend fun markRelayAckSent(msgId: String, envelopeSha256: String, peerLinkId: String = LEGACY_PEER_LINK_ID): Int

    @Transaction
    open suspend fun markLegacyForwarded(msgId: String, forwardedAt: Long, peerLinkId: String = LEGACY_PEER_LINK_ID): LegacyForwardResult {
        if (outboundMessage(msgId)?.peerLinkId?.let { it != peerLinkId } == true) return LegacyForwardResult.Missing
        val row = outboundMessage(msgId) ?: return if (activityForMessage(msgId) != null) {
            LegacyForwardResult.AlreadyTerminal
        } else {
            LegacyForwardResult.Missing
        }
        if (row.protocolVersion != 1) return LegacyForwardResult.Missing
        return when (moveToTerminalActivity(
            msgId,
            ActivityEvent(
                eventId = java.util.UUID.randomUUID().toString(),
                msgId = msgId,
                packageName = null,
                eventType = "relay.legacy_forwarded",
                status = "forwarded",
                byteSize = row.byteSize,
                occurredAt = forwardedAt,
                detailCode = "online_only",
            ),
        )) {
            TerminalMovementResult.Moved -> LegacyForwardResult.Deleted
            TerminalMovementResult.AlreadyMoved -> LegacyForwardResult.AlreadyTerminal
            TerminalMovementResult.Missing -> LegacyForwardResult.Missing
        }
    }

    @Query("SELECT * FROM inbound_message WHERE msgId=:msgId AND peerLinkId=:peerLinkId")
    abstract suspend fun inbound(msgId: String, peerLinkId: String = LEGACY_PEER_LINK_ID): InboundMessage?

    @Transaction
    open suspend fun commitDirectControl(
        row: InboundMessage,
        process: suspend () -> DirectControlProcessingResult,
    ): DirectControlCommitResult {
        if (row.eventType !in DIRECT_ACK_CONTROL_TYPES) return DirectControlCommitResult.NotEligible
        require(row.canonId == null && row.sequence == null)
        require(row.outcome == "APPLIED" && row.appliedAt != null && row.relayAckState == "READY")
        val existing = inbound(row.msgId, row.peerLinkId)
        if (existing != null) {
            return if (existing.envelopeSha256 == row.envelopeSha256) {
                DirectControlCommitResult.Duplicate
            } else {
                DirectControlCommitResult.IdConflict
            }
        }
        return when (val processed = process()) {
            DirectControlProcessingResult.Applied -> {
                insertInbound(row)
                DirectControlCommitResult.Committed
            }
            is DirectControlProcessingResult.Rejected -> DirectControlCommitResult.Rejected(processed.code)
        }
    }

    @Transaction
    open suspend fun commitReceiptBackedControl(
        inbound: InboundMessage,
        receipt: OutboundMessage,
        process: suspend () -> ReceiptBackedControlResult,
    ): DirectControlCommitResult {
        if (inbound.eventType !in RECEIPT_BACKED_CONTROL_TYPES) return DirectControlCommitResult.NotEligible
        require(inbound.canonId == null && inbound.sequence == null)
        require(inbound.outcome == "APPLIED" && inbound.appliedAt != null && inbound.relayAckState == "NONE")
        require(inbound.receiptMsgId == receipt.msgId)
        require(inbound.peerLinkId == receipt.peerLinkId)
        require(receipt.eventType == "peer.receipt" && !receipt.requiresPeerReceipt)

        inbound(inbound.msgId, inbound.peerLinkId)?.let { existing ->
            return if (existing.envelopeSha256 == inbound.envelopeSha256) {
                DirectControlCommitResult.Duplicate
            } else {
                DirectControlCommitResult.IdConflict
            }
        }
        val existingReceipt = outbound(receipt.msgId)
        if (existingReceipt != null && existingReceipt.envelopeSha256 != receipt.envelopeSha256) {
            return DirectControlCommitResult.Rejected("receipt_conflict")
        }
        when (val result = process()) {
            ReceiptBackedControlResult.Applied -> Unit
            is ReceiptBackedControlResult.Rejected -> return DirectControlCommitResult.Rejected(result.code)
        }
        if (existingReceipt == null) insertOutbound(receipt)
        insertInbound(inbound)
        return DirectControlCommitResult.Committed
    }

    @Insert(onConflict = OnConflictStrategy.ABORT)
    protected abstract suspend fun insertInboundRaw(row: InboundMessage)

    @Transaction
    open suspend fun insertInbound(row: InboundMessage) {
        if (row.peerLinkId != LEGACY_PEER_LINK_ID) {
            val peer = deliveryPeerLink(row.peerLinkId)
            check(peer?.lifecycle == "ACTIVE" && peer.deviceId == row.originDevice) { "peer_link_inactive" }
        }
        insertInboundRaw(row)
    }

    @Transaction
    open suspend fun commitActionInvokeRejection(row: InboundMessage): ActionInvokeRejectionCommitResult {
        return commitControlInvokeRejectionBody(row, "notif.action.invoke")
    }

    @Transaction
    open suspend fun commitCallControlInvokeRejection(row: InboundMessage): ActionInvokeRejectionCommitResult {
        return commitControlInvokeRejectionBody(row, "call.control.invoke")
    }

    @Transaction
    open suspend fun commitCallControlResultRejection(row: InboundMessage): ActionInvokeRejectionCommitResult {
        return commitControlInvokeRejectionBody(row, "call.control.result")
    }

    private suspend fun commitControlInvokeRejectionBody(
        row: InboundMessage,
        expectedEventType: String,
    ): ActionInvokeRejectionCommitResult {
        require(row.eventType == expectedEventType)
        require(row.canonId == null && row.sequence == null)
        require(row.outcome == "REJECTED" && row.appliedAt != null && row.relayAckState == "READY")
        val existing = inbound(row.msgId, row.peerLinkId)
        if (existing != null) {
            return if (existing.envelopeSha256 == row.envelopeSha256) {
                ActionInvokeRejectionCommitResult.Duplicate
            } else {
                ActionInvokeRejectionCommitResult.IdConflict
            }
        }
        insertInbound(row)
        return ActionInvokeRejectionCommitResult.Committed
    }

    @Transaction
    open suspend fun commitInboundRejection(
        row: InboundMessage,
        receipt: OutboundMessage,
    ): CallRejectionCommitResult {
        require(row.outcome == "REJECTED")
        require(row.receiptMsgId == receipt.msgId)
        require(row.peerLinkId == receipt.peerLinkId)
        require(receipt.eventType == "peer.receipt" && !receipt.requiresPeerReceipt)
        val existing = inbound(row.msgId, row.peerLinkId)
        if (existing != null) {
            return if (existing.envelopeSha256 == row.envelopeSha256) {
                CallRejectionCommitResult.Duplicate
            } else {
                CallRejectionCommitResult.IdConflict
            }
        }
        val existingReceipt = outbound(receipt.msgId)
        if (existingReceipt != null && existingReceipt.envelopeSha256 != receipt.envelopeSha256) {
            return CallRejectionCommitResult.ReceiptConflict
        }
        if (existingReceipt == null) insertOutbound(receipt)
        insertInbound(row)
        return CallRejectionCommitResult.Committed
    }

    @Transaction
    open suspend fun commitCallRejection(row: InboundMessage, receipt: OutboundMessage): CallRejectionCommitResult {
        require(row.eventType == "call.state")
        return commitInboundRejection(row, receipt)
    }

    @Query("SELECT * FROM canonical_notification_state WHERE canonId=:canonId")
    abstract suspend fun canonical(canonId: String): CanonicalNotificationState?

    @Query(
        "SELECT canonId FROM canonical_notification_state " +
            "WHERE mirrorLocalTag=:tag AND mirrorLocalId=:id LIMIT 1",
    )
    abstract suspend fun canonicalForMirrorIdentity(tag: String, id: Int): String?

    @Query("SELECT canonId FROM canonical_notification_state WHERE sourceNotificationKey=:key LIMIT 1")
    abstract suspend fun canonicalForSourceKey(key: String): String?

    /** Consumes a persisted v2 mirror-cancel tombstone atomically with the echo decision. */
    @Query(
        "UPDATE canonical_notification_state SET peerCancelPending=0 " +
            "WHERE canonId=:canonId AND peerCancelPending=1",
    )
    abstract suspend fun consumePeerCancel(canonId: String): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun putCanonical(row: CanonicalNotificationState)

    @Query(
        "SELECT state.* FROM canonical_notification_state AS state " +
            "WHERE state.latestSequence > state.materializedSequence AND (state.peerLinkId IS NULL OR state.peerLinkId='legacy' OR " +
            "EXISTS (SELECT 1 FROM peer_link p WHERE p.peerLinkId=state.peerLinkId AND p.lifecycle='ACTIVE')) AND (" +
            "NOT EXISTS (SELECT 1 FROM materialization_retry AS retry WHERE retry.canonId=state.canonId) OR " +
            "EXISTS (SELECT 1 FROM materialization_retry AS retry WHERE retry.canonId=state.canonId AND (" +
            "retry.sequence < state.latestSequence OR " +
            "(:includePermissionBlocked AND retry.disposition='PERMISSION_BLOCKED') OR " +
            "retry.nextAttemptAt <= :now))) " +
            "ORDER BY state.updatedAt",
    )
    abstract suspend fun pendingMaterialization(
        now: Long,
        includePermissionBlocked: Boolean = false,
    ): List<CanonicalNotificationState>

    @Query("SELECT * FROM materialization_retry WHERE canonId=:canonId")
    abstract suspend fun materializationRetry(canonId: String): MaterializationRetry?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun putMaterializationRetry(row: MaterializationRetry)

    @Query(
        "INSERT OR REPLACE INTO materialization_retry(canonId,sequence,nextAttemptAt,attempts,disposition,lastError) " +
            "SELECT :canonId,:sequence,:nextAttemptAt,:attempts,:disposition,:lastError " +
            "WHERE EXISTS (SELECT 1 FROM canonical_notification_state " +
            "WHERE canonId=:canonId AND latestSequence=:sequence)",
    )
    protected abstract suspend fun putMaterializationRetryIfCurrent(
        canonId: String,
        sequence: Long,
        nextAttemptAt: Long?,
        attempts: Int,
        disposition: MaterializationRetryDisposition,
        lastError: String,
    ): Long

    @Query("DELETE FROM materialization_retry WHERE canonId=:canonId")
    abstract suspend fun clearMaterializationRetry(canonId: String)

    @Query("DELETE FROM materialization_retry WHERE canonId=:canonId AND sequence=:sequence")
    abstract suspend fun clearMaterializationRetry(canonId: String, sequence: Long): Int

    @Query("DELETE FROM materialization_retry WHERE canonId=:canonId AND sequence <= :sequence")
    abstract suspend fun clearMaterializationRetriesThrough(canonId: String, sequence: Long): Int

    @Query(
        "SELECT MIN(retry.nextAttemptAt) FROM materialization_retry AS retry " +
            "INNER JOIN canonical_notification_state AS state ON state.canonId=retry.canonId " +
            "AND state.latestSequence=retry.sequence " +
            "WHERE retry.disposition='RETRYABLE' AND retry.nextAttemptAt IS NOT NULL " +
            "AND state.latestSequence > state.materializedSequence",
    )
    abstract suspend fun earliestRetryableMaterializationAt(): Long?

    @Transaction
    open suspend fun recordMaterializationRetry(
        canonId: String,
        sequence: Long,
        nowMs: Long,
        disposition: MaterializationRetryDisposition,
        lastError: String,
    ): MaterializationRetryWriteResult {
        val current = canonical(canonId) ?: return MaterializationRetryWriteResult.Superseded
        if (current.latestSequence != sequence) return MaterializationRetryWriteResult.Superseded
        val previous = materializationRetry(canonId)
        val attempts = if (previous?.sequence == sequence && previous.disposition == disposition) {
            previous.attempts.coerceAtMost(Int.MAX_VALUE - 1) + 1
        } else {
            1
        }
        val dueAt = if (disposition == MaterializationRetryDisposition.RETRYABLE) {
            saturatingMaterializationRetryDue(nowMs, boundedMaterializationRetryDelay(attempts))
        } else {
            null
        }
        val written = putMaterializationRetryIfCurrent(
            canonId = canonId,
            sequence = sequence,
            nextAttemptAt = dueAt,
            attempts = attempts,
            disposition = disposition,
            lastError = lastError,
        )
        if (written == -1L) return MaterializationRetryWriteResult.Superseded
        return if (dueAt == null) {
            MaterializationRetryWriteResult.PermissionBlocked
        } else {
            MaterializationRetryWriteResult.RetryableScheduled(dueAt)
        }
    }

    @Query(
        "SELECT * FROM inbound_message WHERE canonId=:canonId AND sequence=:sequence " +
            "AND outcome='PENDING_PLATFORM' ORDER BY committedAt",
    )
    abstract suspend fun pendingInboundForMaterialization(
        canonId: String,
        sequence: Long,
    ): List<InboundMessage>

    @Query(
        "UPDATE canonical_notification_state SET peerCancelPending=1 " +
            "WHERE canonId=:canonId AND state='CANCELLED'",
    )
    abstract suspend fun markPeerCancelPending(canonId: String): Int

    @Query("UPDATE canonical_notification_state SET peerCancelPending=0 WHERE canonId=:canonId")
    abstract suspend fun clearPeerCancelPending(canonId: String): Int

    @Query("SELECT * FROM canonical_notification_state WHERE originDevice=:originDevice AND state='ACTIVE'")
    abstract suspend fun activeOriginStates(originDevice: String): List<CanonicalNotificationState>

    @Query(
        "SELECT * FROM canonical_notification_state WHERE originDevice != :originDevice AND state='ACTIVE' " +
            "AND mirrorLocalTag IS NOT NULL AND mirrorLocalId IS NOT NULL " +
            "AND canonId NOT LIKE 'call:%'",
    )
    abstract suspend fun activePeerMirrorStates(originDevice: String): List<CanonicalNotificationState>

    @Query(
        "SELECT * FROM canonical_notification_state " +
            "WHERE originDevice=:originDevice AND state='ACTIVE' " +
            "AND substr(canonId, 1, 5)='call:' ORDER BY updatedAt, canonId",
    )
    abstract suspend fun activeLocalCallStates(
        originDevice: String,
    ): List<CanonicalNotificationState>

    @Query(
        "SELECT COALESCE(MAX(mirrorLocalId), 0) + 1 FROM canonical_notification_state " +
            "WHERE mirrorLocalId IS NOT NULL",
    )
    abstract suspend fun nextMirrorLocalId(): Int

    @Query("SELECT * FROM outbound_queue ORDER BY id ASC LIMIT :limit")
    abstract override suspend fun legacyBatch(limit: Int): List<LegacyOutboundEvent>

    @Query("SELECT * FROM outbound_message WHERE msgId=:msgId")
    protected abstract suspend fun outbound(msgId: String): OutboundMessage?

    @Query("DELETE FROM outbound_queue WHERE id=:legacyId")
    protected abstract suspend fun deleteLegacy(legacyId: Long): Int

    @Query("SELECT * FROM outbound_queue WHERE id=:legacyId")
    protected abstract suspend fun legacy(legacyId: Long): LegacyOutboundEvent?

    @Query("SELECT * FROM origin_sequence WHERE canonId=:canonId")
    protected abstract suspend fun originSequence(canonId: String): OriginSequence?

    /** Read-only hint used to prepare the encrypted payload before the atomic capture commit. */
    @Query("SELECT nextSequence FROM origin_sequence WHERE canonId=:canonId")
    abstract suspend fun nextCaptureSequence(canonId: String): Long?

    @Transaction
    open suspend fun nextCaptureSequenceForEvent(canonId: String): Long {
        val latest = canonical(canonId)?.latestSequence ?: 0L
        check(latest < Long.MAX_VALUE) { "origin_sequence_exhausted" }
        return maxOf(originSequence(canonId)?.nextSequence ?: 1L, latest + 1L)
    }

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    protected abstract suspend fun putOriginSequence(row: OriginSequence)

    @Query(
        "SELECT * FROM inbound_message WHERE canonId=:canonId AND sequence=:sequence " +
            "AND outcome='PENDING_PLATFORM' ORDER BY committedAt",
    )
    protected abstract suspend fun pendingInbound(
        canonId: String,
        sequence: Long,
    ): List<InboundMessage>

    @Query(
        "SELECT * FROM inbound_message WHERE canonId=:canonId AND outcome='PENDING_PLATFORM' " +
            "AND sequence < :sequence ORDER BY committedAt, msgId",
    )
    protected abstract suspend fun pendingSupersededInbound(canonId: String, sequence: Long): List<InboundMessage>

    suspend fun pendingSupersededInboundPreflight(canonId: String, sequence: Long): List<InboundMessage> =
        pendingSupersededInbound(canonId, sequence)

    @Query(
        "SELECT state.* FROM canonical_notification_state AS state WHERE EXISTS (SELECT 1 FROM inbound_message AS inbound " +
            "WHERE inbound.canonId=state.canonId AND inbound.outcome='PENDING_PLATFORM' AND inbound.sequence < state.latestSequence) " +
            "ORDER BY state.updatedAt, state.canonId LIMIT :limit",
    )
    abstract suspend fun strandedSupersededCanonicalGroups(limit: Int): List<CanonicalNotificationState>

    @Query("SELECT COUNT(*) FROM inbound_message WHERE receiptMsgId=:receiptMsgId")
    protected abstract suspend fun inboundReceiptReferenceCount(receiptMsgId: String): Int

    @Query("DELETE FROM outbound_message WHERE msgId=:msgId AND state='PENDING_PLATFORM' AND eventType='peer.receipt' AND requiresPeerReceipt=0")
    protected abstract suspend fun deletePrivateStagedReceipt(msgId: String): Int

    @Query("UPDATE inbound_message SET outcome='REJECTED', appliedAt=:at, receiptMsgId=:receiptMsgId WHERE msgId=:msgId AND peerLinkId=:peerLinkId AND outcome='PENDING_PLATFORM'")
    protected abstract suspend fun markInboundRejected(msgId: String, at: Long, receiptMsgId: String, peerLinkId: String): Int

    @Query(
        "UPDATE inbound_message SET outcome='APPLIED', appliedAt=:appliedAt, receiptMsgId=:receiptMsgId " +
            "WHERE msgId=:msgId AND peerLinkId=:peerLinkId AND outcome='PENDING_PLATFORM'",
    )
    protected abstract suspend fun markInboundApplied(
        msgId: String,
        appliedAt: Long,
        receiptMsgId: String?,
        peerLinkId: String,
    ): Int

    @Query("SELECT * FROM inbound_message WHERE receiptMsgId=:receiptMsgId LIMIT 1")
    protected abstract suspend fun inboundForReceipt(receiptMsgId: String): InboundMessage?

    @Query("UPDATE inbound_message SET relayAckState='READY' WHERE receiptMsgId=:receiptMsgId")
    protected abstract suspend fun markRelayAckReady(receiptMsgId: String): Int

    @Query(
        "UPDATE inbound_message SET receiptMsgId=:receiptMsgId " +
            "WHERE msgId=:msgId AND peerLinkId=:peerLinkId AND outcome='PENDING_PLATFORM' AND receiptMsgId IS NULL",
    )
    protected abstract suspend fun linkMaterializationReceipt(msgId: String, receiptMsgId: String, peerLinkId: String): Int

    @Query("UPDATE outbound_message SET state='NEW' WHERE msgId=:msgId AND state='PENDING_PLATFORM'")
    protected abstract suspend fun activateMaterializationReceipt(msgId: String): Int

    @Query(
        "SELECT * FROM outbound_message WHERE canonId=:canonId AND peerLinkId=:peerLinkId AND state='NEW' " +
            "AND eventType IN ('notif.post','notif.update','notif.cancel') ORDER BY createdAt",
    )
    protected abstract suspend fun compactableState(canonId: String, peerLinkId: String): List<OutboundMessage>

    @Query("DELETE FROM outbound_message WHERE msgId IN (:msgIds)")
    protected abstract suspend fun deleteOutboundIds(msgIds: List<String>): Int

    @Insert(onConflict = OnConflictStrategy.ABORT)
    protected abstract suspend fun insertActivity(row: ActivityEvent)

    @Query("SELECT * FROM activity_event WHERE msgId=:msgId LIMIT 1")
    protected abstract suspend fun activityForMessage(msgId: String): ActivityEvent?

    @Query("SELECT * FROM snapshot_stage WHERE peerLinkId=:peerLinkId AND snapshotId=:snapshotId ORDER BY canonId")
    protected abstract suspend fun stagedSnapshot(snapshotId: String, peerLinkId: String = LEGACY_PEER_LINK_ID): List<SnapshotStage>

    @Query("SELECT DISTINCT peerLinkId, snapshotId FROM snapshot_stage WHERE receivedAt < :cutoff")
    protected abstract suspend fun expiredSnapshotIds(cutoff: Long): List<SnapshotSessionKey>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    protected abstract suspend fun putSnapshotStages(rows: List<SnapshotStage>)

    @Query("SELECT * FROM canonical_notification_state WHERE originDevice=:originDevice")
    protected abstract suspend fun canonicalsForOrigin(
        originDevice: String,
    ): List<CanonicalNotificationState>

    @Query("DELETE FROM snapshot_stage WHERE peerLinkId=:peerLinkId AND snapshotId=:snapshotId")
    protected abstract suspend fun deleteSnapshot(snapshotId: String, peerLinkId: String = LEGACY_PEER_LINK_ID): Int

    /** Public read surface for the snapshot coordinator and deterministic tests. */
    suspend fun snapshotRows(snapshotId: String, peerLinkId: String = LEGACY_PEER_LINK_ID): List<SnapshotStage> = stagedSnapshot(snapshotId, peerLinkId)

    /** Removes complete snapshot staging sessions, never individual rows. */
    @Transaction
    open suspend fun expireSnapshotStages(cutoff: Long): Int {
        require(cutoff >= 0) { "snapshot expiry cutoff must be non-negative" }
        val ids = expiredSnapshotIds(cutoff)
        ids.forEach { deleteSnapshot(it.snapshotId, it.peerLinkId) }
        return ids.size
    }

    @Transaction
    open suspend fun reserveSequence(canonId: String): SequenceReservationResult {
        val sequence = originSequence(canonId)?.nextSequence ?: 1L
        putOriginSequence(OriginSequence(canonId, sequence + 1L))
        return SequenceReservationResult.Reserved(sequence)
    }

    @Query("DELETE FROM outbound_message WHERE canonId=:canonId AND sequence < :sequence AND state='PENDING_PLATFORM' AND requiresPeerReceipt=1 AND eventType='notif.cancel'")
    protected abstract suspend fun discardSupersededSourceCancels(canonId: String, sequence: Long)

    @Query("UPDATE outbound_message SET state='NEW' WHERE canonId=:canonId AND sequence=:sequence AND state='PENDING_PLATFORM' AND requiresPeerReceipt=1 AND eventType='notif.cancel'")
    protected abstract suspend fun activateSourceCancels(canonId: String, sequence: Long)

    /** Origin owns sequencing and encryption; a mirror dismissal is only a request. */
    @Transaction
    open suspend fun commitOriginCancelRequest(
        request: InboundMessage,
        requestSequence: Long,
        expectedCurrentSequence: Long,
        localDeviceId: String,
        desired: CanonicalNotificationState,
        fanout: List<OutboundMessage>,
        supersession: SupersessionBundle = SupersessionBundle(emptyList()),
    ): InboundDesiredCommitResult {
        inbound(request.msgId, request.peerLinkId)?.let {
            return if (it.envelopeSha256 == request.envelopeSha256) {
                InboundDesiredCommitResult.Duplicate(it.outcome, it.receiptMsgId)
            } else InboundDesiredCommitResult.IdConflict(it.envelopeSha256)
        }
        val current = checkNotNull(canonical(desired.canonId)) { "source_missing" }
        check(current.originDevice == localDeviceId && current.peerLinkId == null) { "not_source_owner" }
        val sender = checkNotNull(deliveryPeerLink(request.peerLinkId)) { "peer_removed" }
        check(sender.lifecycle == "ACTIVE" && sender.deviceId == request.originDevice) { "cancel_sender_mismatch" }
        if (current.latestSequence != expectedCurrentSequence || requestSequence <= current.latestSequence) {
            return InboundDesiredCommitResult.Stale(current.latestSequence)
        }
        check(requestSequence < Long.MAX_VALUE && desired.latestSequence < Long.MAX_VALUE) { "origin_sequence_exhausted" }
        val next = maxOf(nextCaptureSequenceForEvent(desired.canonId), requestSequence + 1L)
        if (desired.latestSequence != next) return InboundDesiredCommitResult.Stale(current.latestSequence)
        require(request.eventType == "notif.cancel" && request.outcome == "PENDING_PLATFORM")
        require(desired.originDevice == localDeviceId && desired.peerLinkId == null && desired.state == "CANCELLED")
        require(fanout.isNotEmpty() && fanout.map { it.peerLinkId }.toSet() == activePeerLinkIds().toSet())
        require(fanout.map { it.peerLinkId }.distinct().size == fanout.size && fanout.map { it.msgId }.distinct().size == fanout.size)
        require(fanout.all { it.canonId == desired.canonId && it.sequence == next && it.eventType == "notif.cancel" &&
            it.state == "PENDING_PLATFORM" && it.requiresPeerReceipt })
        val result = commitInboundDesired(request, desired, supersession)
        if (result is InboundDesiredCommitResult.Committed) {
            // Capacity failure rolls back journal, desired state, compaction and every recipient.
            for (row in fanout) {
                val obsolete = compactableState(desired.canonId, row.peerLinkId).filter { (it.sequence ?: Long.MAX_VALUE) < next }
                deleteOutboundIds(obsolete.map { it.msgId })
                obsolete.forEach { deleteUiActivityForMessage(it.msgId) }
                insertOutbound(row)
            }
            putOriginSequence(OriginSequence(desired.canonId, next + 1L))
        }
        return result
    }

    @Transaction
    open suspend fun commitInboundDesired(
        row: InboundMessage,
        desired: CanonicalNotificationState?,
        supersession: SupersessionBundle = SupersessionBundle(emptyList()),
    ): InboundDesiredCommitResult {
        val existing = inbound(row.msgId, row.peerLinkId)
        if (existing != null) {
            return if (existing.envelopeSha256 == row.envelopeSha256) {
                InboundDesiredCommitResult.Duplicate(existing.outcome, existing.receiptMsgId)
            } else {
                InboundDesiredCommitResult.IdConflict(existing.envelopeSha256)
            }
        }

        if (desired != null) {
            require(row.canonId == desired.canonId)
            require(row.sequence == desired.latestSequence)
            val current = canonical(desired.canonId)
            val mirrorOwner = if (desired.mirrorLocalTag != null && desired.mirrorLocalId != null) {
                canonicalForMirrorIdentity(desired.mirrorLocalTag, desired.mirrorLocalId)
            } else {
                null
            }
            if (mirrorOwner != null && mirrorOwner != desired.canonId) {
                return InboundDesiredCommitResult.MirrorIdentityCollision(mirrorOwner)
            }
            if (current != null && desired.latestSequence <= current.latestSequence) {
                return InboundDesiredCommitResult.Stale(current.latestSequence)
            }
            when (val terminalized = applySupersessionBundle(
                pendingSupersededInbound(desired.canonId, desired.latestSequence), supersession, row.committedAt,
            )) {
                SupersessionMutationResult.Applied -> Unit
                SupersessionMutationResult.Invalid ->
                    return InboundDesiredCommitResult.SupersessionUnavailable
                is SupersessionMutationResult.ReceiptConflict ->
                    return InboundDesiredCommitResult.ReceiptConflict(terminalized.existingSha256)
            }
        }

        insertInbound(row)
        if (desired != null) {
            maintainNotificationDetail(row, desired)
            discardSupersededSourceCancels(desired.canonId, desired.latestSequence)
            putCanonical(desired.copy(peerLinkId = if (desired.originDevice == row.originDevice) row.peerLinkId else desired.peerLinkId))
        }
        return InboundDesiredCommitResult.Committed
    }

    private suspend fun maintainNotificationDetail(
        row: InboundMessage,
        desired: CanonicalNotificationState,
    ) {
        if (!isNotificationSnapshotCanonical(desired.canonId)) return
        val existing = notificationDetailForCanon(desired.canonId)
        when (row.eventType) {
            "notif.post", "notif.update" -> {
                val payload = requireNotNull(desired.desiredPayloadJson) {
                    "active notification detail requires desired payload"
                }
                upsertActiveNotificationDetail(desired.canonId, desired.originDevice, payload, row.committedAt)
            }
            "notif.cancel" -> cancelNotificationDetailIfPresent(desired.canonId, row.committedAt, existing)
        }
    }

    private suspend fun cancelNotificationDetailIfPresent(
        canonId: String,
        cancelledAt: Long,
        existing: NotificationDetailCache? = null,
    ) {
        val detail = existing ?: notificationDetailForCanon(canonId) ?: return
        putNotificationDetail(detail.copy(updatedAt = cancelledAt, cancelledAt = cancelledAt))
        sweepNotificationDetailCache(cancelledAt)
    }

    private suspend fun upsertActiveNotificationDetail(
        canonId: String,
        originDevice: String,
        payloadJson: String,
        updatedAt: Long,
    ) {
        val existing = notificationDetailForCanon(canonId)
        putNotificationDetail(
            NotificationDetailCache(
                detailId = existing?.detailId ?: java.util.UUID.randomUUID().toString(),
                canonId = canonId,
                payloadJson = payloadJson,
                originDevice = originDevice,
                receivedAt = existing?.receivedAt ?: updatedAt,
                updatedAt = updatedAt,
                cancelledAt = null,
            ),
        )
    }

    /** Repairs a complete canonical group; partial bundles are rejected without mutation. */
    @Transaction
    open suspend fun terminalizeSupersededInbound(
        canonId: String,
        sequence: Long,
        supersession: SupersessionBundle,
        terminalAt: Long,
    ): Boolean {
        return applySupersessionBundle(
            pendingSupersededInbound(canonId, sequence), supersession, terminalAt,
        ) is SupersessionMutationResult.Applied
    }

    /** Validates the full canonical group before changing any old row or receipt. */
    private suspend fun applySupersessionBundle(
        older: List<InboundMessage>,
        supersession: SupersessionBundle,
        terminalAt: Long,
    ): SupersessionMutationResult {
        if (older.isEmpty()) return SupersessionMutationResult.Applied
        val byId = supersession.entries.associateBy { it.inboundMsgId }
        // The bundle was prepared outside this transaction. A row that was applied in the
        // meantime simply no longer needs its prepared receipt; a row that appeared in the
        // meantime has no receipt and forces the caller to prepare again.
        if (byId.size != supersession.entries.size || !byId.keys.containsAll(older.map { it.msgId }) ||
            older.any { byId[it.msgId]?.envelopeSha256 != it.envelopeSha256 } ||
            supersession.entries.map { it.receipt.msgId }.distinct().size != supersession.entries.size
        ) return SupersessionMutationResult.Invalid
        for (olderRow in older) {
            val entry = byId[olderRow.msgId] ?: return SupersessionMutationResult.Invalid
            if (entry.receipt.peerLinkId != olderRow.peerLinkId || entry.receipt.eventType != "peer.receipt" || entry.receipt.requiresPeerReceipt) {
                return SupersessionMutationResult.Invalid
            }
            outbound(entry.receipt.msgId)?.let { return SupersessionMutationResult.ReceiptConflict(it.envelopeSha256) }
            olderRow.receiptMsgId?.let { stagedId ->
                val staged = outbound(stagedId)
                if (staged == null || staged.state != "PENDING_PLATFORM" ||
                    staged.eventType != "peer.receipt" || staged.requiresPeerReceipt ||
                    inboundReceiptReferenceCount(stagedId) != 1
                ) return SupersessionMutationResult.Invalid
            }
        }
        for (olderRow in older) {
            val entry = requireNotNull(byId[olderRow.msgId])
            olderRow.receiptMsgId?.let { stagedId -> deletePrivateStagedReceipt(stagedId) }
            insertOutbound(entry.receipt.copy(state = "NEW", requiresPeerReceipt = false))
            markInboundRejected(olderRow.msgId, terminalAt, entry.receipt.msgId, olderRow.peerLinkId)
        }
        return SupersessionMutationResult.Applied
    }

    @Transaction
    open suspend fun completeMaterialization(
        canonId: String,
        sequence: Long,
        appliedAt: Long,
        receipt: OutboundMessage?,
    ): MaterializationResult {
        val state = canonical(canonId) ?: return MaterializationResult.Missing
        if (sequence < state.latestSequence) return MaterializationResult.Superseded
        if (sequence <= state.materializedSequence) return MaterializationResult.AlreadyCompleted
        if (sequence != state.latestSequence) return MaterializationResult.Missing

        if (receipt != null) {
            val existingReceipt = outbound(receipt.msgId)
            if (existingReceipt != null && existingReceipt.envelopeSha256 != receipt.envelopeSha256) {
                return MaterializationResult.ReceiptConflict(existingReceipt.envelopeSha256)
            }
            if (existingReceipt == null) insertOutbound(receipt)
            activateMaterializationReceipt(receipt.msgId)
        }

        val pending = pendingInbound(canonId, sequence)
        require(receipt == null || pending.all { it.peerLinkId == receipt.peerLinkId })
        pending.forEach { markInboundApplied(it.msgId, appliedAt, receipt?.msgId, it.peerLinkId) }
        activateSourceCancels(canonId, sequence)
        putCanonical(state.copy(materializedSequence = sequence, updatedAt = appliedAt))
        pending.maxByOrNull { it.committedAt }?.let { inbound ->
            val payload = state.desiredPayloadJson?.let { raw ->
                runCatching { JSONObject(raw) }.getOrNull()
            }
            upsertUiActivity(
                UiActivityEvent(
                    eventId = "inbound:${inbound.msgId}",
                    msgId = inbound.msgId,
                    packageName = payload?.optString("package_name")?.takeIf { it.isNotBlank() },
                    appName = if (state.canonId.startsWith("call:")) {
                        "Phone"
                    } else {
                        payload?.optString("app_name")?.takeIf { it.isNotBlank() }
                    },
                    direction = UiActivityDirection.RECEIVED.name,
                    kind = when {
                        state.canonId.startsWith("call:") -> UiActivityKind.CALL
                        state.state == "CANCELLED" -> UiActivityKind.DISMISSAL
                        else -> UiActivityKind.NOTIFICATION
                    }.name,
                    status = if (state.state == "CANCELLED") {
                        UiActivityStatus.DISMISSED.name
                    } else {
                        UiActivityStatus.APPLIED.name
                    },
                    route = null,
                    occurredAt = appliedAt,
                ),
            )
            maintainUiHistory(appliedAt)
        }
        return MaterializationResult.Completed
    }

    /** Persist a receipt identity before invoking Android, so a crash can reuse it safely. */
    @Transaction
    open suspend fun prepareMaterializationReceipt(
        canonId: String,
        sequence: Long,
        candidate: OutboundMessage?,
    ): MaterializationReceiptResult {
        val pending = pendingInbound(canonId, sequence)
        if (pending.isEmpty()) return MaterializationReceiptResult.NotNeeded
        val existingIds = pending.mapNotNull { it.receiptMsgId }.distinct()
        if (existingIds.size > 1) return MaterializationReceiptResult.Conflict("multiple receipt IDs")
        val existingId = existingIds.singleOrNull()
        if (existingId != null) {
            val existing = outbound(existingId)
                ?: return MaterializationReceiptResult.Conflict("missing receipt $existingId")
            return MaterializationReceiptResult.Prepared(existing)
        }
        val receipt = candidate ?: return MaterializationReceiptResult.Unavailable
        require(pending.all { it.peerLinkId == receipt.peerLinkId })
        val existing = outbound(receipt.msgId)
        if (existing != null && existing.envelopeSha256 != receipt.envelopeSha256) {
            return MaterializationReceiptResult.Conflict(existing.envelopeSha256)
        }
        if (existing == null) {
            insertOutbound(receipt.copy(state = "PENDING_PLATFORM", requiresPeerReceipt = false))
        }
        pending.forEach { linkMaterializationReceipt(it.msgId, receipt.msgId, it.peerLinkId) }
        return MaterializationReceiptResult.Prepared(existing ?: receipt.copy(state = "PENDING_PLATFORM"))
    }

    @Transaction
    open suspend fun acceptReceipt(receiptMsgId: String): ReceiptTransitionResult {
        val inbound = inboundForReceipt(receiptMsgId)
            ?: return if (outbound(receiptMsgId) == null) {
                ReceiptTransitionResult.Missing
            } else {
                ReceiptTransitionResult.NotReceipt
            }
        if (inbound.relayAckState == "READY" || inbound.relayAckState == "SENT") {
            return ReceiptTransitionResult.AlreadyTransitioned
        }
        val receipt = outbound(receiptMsgId) ?: return ReceiptTransitionResult.Missing
        if (receipt.eventType != "peer.receipt" || receipt.requiresPeerReceipt) {
            return ReceiptTransitionResult.NotReceipt
        }
        deleteOutbound(receiptMsgId)
        markRelayAckReady(receiptMsgId)
        return ReceiptTransitionResult.ReadyForRelayAck
    }

    /** Route-neutral custody transition. Normal rows remain durable until a peer receipt; receipt rows do not. */
    @Transaction
    open suspend fun acceptCustody(
        msgId: String,
        route: String,
        acceptedAt: Long,
        retryAt: Long,
        peerLinkId: String = LEGACY_PEER_LINK_ID,
    ): CustodyAcceptanceResult {
        if (outboundMessage(msgId)?.peerLinkId?.let { it != peerLinkId } == true) return CustodyAcceptanceResult.Missing
        require(route == "LAN" || route == "BLUETOOTH" || route == "RELAY")
        val row = outboundMessage(msgId) ?: return CustodyAcceptanceResult.Missing
        if (!row.requiresPeerReceipt) {
            return when (acceptReceipt(msgId)) {
                ReceiptTransitionResult.ReadyForRelayAck,
                ReceiptTransitionResult.AlreadyTransitioned,
                -> CustodyAcceptanceResult.DeletedReceipt
                ReceiptTransitionResult.Missing -> CustodyAcceptanceResult.Missing
                ReceiptTransitionResult.NotReceipt -> {
                    deleteOutbound(msgId)
                    CustodyAcceptanceResult.DeletedReceipt
                }
            }
        }
        if (route == "RELAY") markRelayCustodyAccepted(msgId)
        if (row.state == "ACCEPTED") return CustodyAcceptanceResult.AlreadyAccepted
        if (row.state != "NEW") return CustodyAcceptanceResult.AlreadyAccepted
        check(acceptNewCustody(msgId, route, acceptedAt, retryAt) == 1)
        return CustodyAcceptanceResult.Accepted
    }

    /** Client-clock expiry is authoritative only while relay custody is known absent. */
    @Transaction
    open suspend fun expireLocal(now: Long): Int {
        var expired = 0
        locallyExpired(now).forEach { row ->
            if (
                moveToTerminalActivity(
                    row.msgId,
                    ActivityEvent(
                        eventId = java.util.UUID.randomUUID().toString(),
                        msgId = row.msgId,
                        packageName = null,
                        eventType = "delivery.expired",
                        status = "expired",
                        byteSize = row.byteSize,
                        occurredAt = now,
                        detailCode = "local_expired",
                    ),
                ) == TerminalMovementResult.Moved
            ) {
                expired += 1
            }
        }
        return expired
    }

    /** Apply an authenticated peer receipt with digest equality and terminal metadata only. */
    @Transaction
    open suspend fun applyPeerReceipt(
        ackedMsgId: String,
        envelopeSha256: String,
        status: String,
        reason: String?,
        occurredAt: Long,
        peerReceiptCreatedAt: Long? = null,
        peerLinkId: String = LEGACY_PEER_LINK_ID,
    ): RelayReceiptResult {
        if (outboundMessage(ackedMsgId)?.peerLinkId?.let { it != peerLinkId } == true) return RelayReceiptResult.Missing
        require(status in setOf("applied", "expired", "rejected", "decrypt_failed"))
        val row = outboundMessage(ackedMsgId)
            ?: return if (activityForMessage(ackedMsgId) != null) RelayReceiptResult.AlreadyTerminal
            else RelayReceiptResult.Missing
        if (row.envelopeSha256 != envelopeSha256) return RelayReceiptResult.Conflict(row.envelopeSha256)
        val verifiedDelivery = if (
            status == "applied" && row.eventType in VERIFIED_NOTIFICATION_EVENT_TYPES
        ) {
            val evidence = deliveryLatencyEvidence(row.createdAt, peerReceiptCreatedAt)
            VerifiedDeliveryMetric(
                msgId = row.msgId,
                verifiedAt = occurredAt,
                latencyMs = (evidence as? DeliveryLatencyEvidence.Measured)?.milliseconds,
                latencyStatus = when (evidence) {
                    is DeliveryLatencyEvidence.Measured -> "MEASURED"
                    DeliveryLatencyEvidence.ClockSkew -> "CLOCK_SKEW"
                    DeliveryLatencyEvidence.Implausible -> "IMPLAUSIBLE"
                    DeliveryLatencyEvidence.Unavailable -> "UNAVAILABLE"
                },
            )
        } else {
            null
        }
        val movement = moveToTerminalActivity(
            msgId = ackedMsgId,
            activity = ActivityEvent(
                eventId = java.util.UUID.randomUUID().toString(),
                msgId = ackedMsgId,
                packageName = null,
                eventType = "peer.receipt",
                status = status,
                byteSize = row.byteSize,
                occurredAt = occurredAt,
                detailCode = reason?.take(128),
            ),
            verifiedDelivery = verifiedDelivery,
        )
        return when (movement) {
            TerminalMovementResult.Moved -> RelayReceiptResult.Deleted
            TerminalMovementResult.AlreadyMoved -> RelayReceiptResult.AlreadyTerminal
            TerminalMovementResult.Missing -> RelayReceiptResult.Missing
        }
    }

    @Transaction
    open suspend fun rejectRelay(
        msgId: String,
        reason: String,
        occurredAt: Long,
        retryAt: Long,
        peerLinkId: String = LEGACY_PEER_LINK_ID,
    ): co.twinotify.core.service.RelayRejectionResult {
        if (outboundMessage(msgId)?.peerLinkId?.let { it != peerLinkId } == true) return co.twinotify.core.service.RelayRejectionResult.Missing
        val row = outboundMessage(msgId) ?: return if (activityForMessage(msgId) != null) {
            co.twinotify.core.service.RelayRejectionResult.AlreadyTerminal
        } else {
            co.twinotify.core.service.RelayRejectionResult.Missing
        }
        if (reason == "mailbox_full" || reason == "peer_legacy" || reason == "server_capacity") {
            updateRelayRetry(msgId, retryAt, reason)
            return co.twinotify.core.service.RelayRejectionResult.Retained
        }
        return when (
            moveToTerminalActivity(
                msgId,
                ActivityEvent(
                    eventId = java.util.UUID.randomUUID().toString(),
                    msgId = msgId,
                    packageName = null,
                    eventType = "relay.rejected",
                    status = reason,
                    byteSize = row.byteSize,
                    occurredAt = occurredAt,
                    detailCode = reason.take(128),
                ),
            )
        ) {
            TerminalMovementResult.Moved -> co.twinotify.core.service.RelayRejectionResult.Terminal
            TerminalMovementResult.AlreadyMoved -> co.twinotify.core.service.RelayRejectionResult.AlreadyTerminal
            TerminalMovementResult.Missing -> co.twinotify.core.service.RelayRejectionResult.Missing
        }
    }

    @Transaction
    open suspend fun expireRelay(msgId: String, expiredAt: Long, peerLinkId: String = LEGACY_PEER_LINK_ID): RelayReceiptResult {
        if (outboundMessage(msgId)?.peerLinkId?.let { it != peerLinkId } == true) return RelayReceiptResult.Missing
        val row = outboundMessage(msgId)
            ?: return if (activityForMessage(msgId) != null) RelayReceiptResult.AlreadyTerminal else RelayReceiptResult.Missing
        return when (
            moveToTerminalActivity(
                msgId,
                ActivityEvent(
                    eventId = java.util.UUID.randomUUID().toString(),
                    msgId = msgId,
                    packageName = null,
                    eventType = "relay.expired",
                    status = "expired",
                    byteSize = row.byteSize,
                    occurredAt = expiredAt,
                    detailCode = "relay_expired",
                ),
            )
        ) {
            TerminalMovementResult.Moved -> RelayReceiptResult.Deleted
            TerminalMovementResult.AlreadyMoved -> RelayReceiptResult.AlreadyTerminal
            TerminalMovementResult.Missing -> RelayReceiptResult.Missing
        }
    }

    @Query("UPDATE outbound_message SET attempts=attempts + 1, nextAttemptAt=:retryAt, lastError=:reason WHERE msgId=:msgId")
    protected abstract suspend fun updateRelayRetry(msgId: String, retryAt: Long, reason: String): Int

    @Transaction
    open suspend fun commitOutboundState(desired: CanonicalNotificationState, incoming: OutboundMessage): OutboundStateCommitResult =
        commitOutboundFanout(desired, listOf(incoming))

    @Transaction
    open suspend fun commitOutboundFanout(desired: CanonicalNotificationState, incoming: List<OutboundMessage>): OutboundStateCommitResult {
        require(incoming.isNotEmpty())
        val first = incoming.first()
        if (first.eventType !in STATE_EVENT_TYPES) return OutboundStateCommitResult.NotStateEvent
        val canonId = requireNotNull(first.canonId)
        val sequence = requireNotNull(first.sequence)
        require(desired.canonId == canonId && desired.latestSequence == sequence)
        require(incoming.map { it.peerLinkId }.distinct().size == incoming.size)
        require(incoming.map { it.msgId }.distinct().size == incoming.size)
        require(incoming.all { it.canonId == canonId && it.sequence == sequence && it.eventType == first.eventType && it.state == "NEW" })
        val latest = canonical(canonId)?.latestSequence
        if (latest != null && sequence <= latest) return OutboundStateCommitResult.Stale(latest)
        // A link added/removed while crypto was prepared requires fresh preparation.
        if (incoming.none { it.peerLinkId == LEGACY_PEER_LINK_ID }) {
            val intended = if (desired.peerLinkId == null) activePeerLinkIds().toSet() else setOf(desired.peerLinkId)
            check(incoming.map { it.peerLinkId }.toSet() == intended) { "peer_membership_changed" }
        }
        var compacted = 0
        for (row in incoming) {
            val candidates = compactableState(canonId, row.peerLinkId)
            val removable = when (row.eventType) {
                "notif.post", "notif.update" -> candidates.filter { it.sequence != null && it.sequence < sequence && it.eventType in POST_OR_UPDATE_EVENT_TYPES }
                "notif.cancel" -> candidates.filter { it.sequence != null && it.sequence < sequence }
                else -> emptyList()
            }
            if (removable.isNotEmpty()) {
                compacted += deleteOutboundIds(removable.map { it.msgId })
                removable.forEach { deleteUiActivityForMessage(it.msgId) }
            }
        }
        // Every insert shares this transaction: failure for recipient two rolls back
        // compaction, recipient one, canonical state, and the source sequence.
        discardSupersededSourceCancels(canonId, sequence)
        incoming.forEach { insertOutbound(it) }
        putCanonical(desired)
        return OutboundStateCommitResult.Committed(compacted)
    }

    /**
     * Capture-specific commit: the prepared row may enter the durable state only when its
     * sequence reservation is still present. This closes the reservation/commit interleaving
     * without weakening the lower-level transaction helper used by migration tests.
     */
    @Transaction
    open suspend fun commitCapturedState(desired: CanonicalNotificationState, incoming: OutboundMessage, uiActivity: UiActivityEvent? = null): OutboundStateCommitResult =
        commitCapturedFanout(desired, listOf(incoming), listOfNotNull(uiActivity))

    @Transaction
    open suspend fun commitCapturedFanout(desired: CanonicalNotificationState, incoming: List<OutboundMessage>, activities: List<UiActivityEvent> = emptyList()): OutboundStateCommitResult {
        require(incoming.isNotEmpty())
        val sequence = requireNotNull(incoming.first().sequence)
        val canonId = incoming.first().canonId ?: return OutboundStateCommitResult.NotStateEvent
        val next = nextCaptureSequenceForEvent(canonId)
        if (sequence != next) return OutboundStateCommitResult.Stale(next - 1L)
        check(sequence < Long.MAX_VALUE) { "origin_sequence_exhausted" }
        val result = commitOutboundFanout(desired, incoming)
        if (result is OutboundStateCommitResult.Committed) {
            putOriginSequence(OriginSequence(canonId, sequence + 1L))
            if (incoming.first().eventType == "notif.cancel" && isNotificationSnapshotCanonical(canonId)) {
                cancelNotificationDetailIfPresent(canonId, desired.updatedAt)
            }
            activities.forEach { upsertUiActivity(it) }
            if (activities.isNotEmpty()) maintainUiHistory(activities.maxOf { it.occurredAt })
        }
        return result
    }

    /** Recovery commit fenced to the local call ownership selected before capture starts. */
    @Transaction
    open suspend fun commitRecoveredCallState(desired: CanonicalNotificationState, incoming: OutboundMessage, expectedLocalOrigin: String, uiActivity: UiActivityEvent? = null): CallRecoveryCommitResult =
        commitRecoveredCallFanout(desired, listOf(incoming), expectedLocalOrigin, listOfNotNull(uiActivity))

    @Transaction
    open suspend fun commitRecoveredCallFanout(
        desired: CanonicalNotificationState,
        incoming: List<OutboundMessage>,
        expectedLocalOrigin: String,
        activities: List<UiActivityEvent> = emptyList(),
    ): CallRecoveryCommitResult {
        val sequence = requireNotNull(incoming.first().sequence)
        val canonId = incoming.first().canonId ?: return CallRecoveryCommitResult.NotStateEvent
        val current = canonical(canonId)
        if (
            current == null ||
            current.state != "ACTIVE" ||
            current.originDevice != expectedLocalOrigin ||
            desired.originDevice != expectedLocalOrigin
        ) {
            return CallRecoveryCommitResult.OwnershipLost
        }
        val nextSequence = originSequence(canonId)?.nextSequence
            ?: current.latestSequence.plus(1L)
        if (sequence != nextSequence) return CallRecoveryCommitResult.Stale(nextSequence - 1L)
        check(nextSequence < Long.MAX_VALUE) { "origin_sequence_exhausted" }
        return when (val result = commitOutboundFanout(desired, incoming)) {
            is OutboundStateCommitResult.Committed -> {
                putOriginSequence(OriginSequence(canonId, nextSequence + 1L))
                activities.forEach { upsertUiActivity(it) }
                if (activities.isNotEmpty()) maintainUiHistory(activities.maxOf { it.occurredAt })
                CallRecoveryCommitResult.Committed(result.compacted)
            }
            is OutboundStateCommitResult.Stale -> CallRecoveryCommitResult.Stale(result.latestSequence)
            OutboundStateCommitResult.NotStateEvent -> CallRecoveryCommitResult.NotStateEvent
        }
    }

    @Query("SELECT COUNT(*) FROM snapshot_stage WHERE canonId=:marker")
    protected abstract suspend fun snapshotSessionCount(marker: String = SNAPSHOT_BEGIN_MARKER_CANON_ID): Int

    @Query("SELECT COUNT(*) FROM snapshot_stage")
    protected abstract suspend fun snapshotStageCount(): Int

    @Query("SELECT COALESCE(SUM(LENGTH(CAST(payloadJson AS BLOB))),0) FROM snapshot_stage")
    protected abstract suspend fun snapshotStageBytes(): Long

    @Transaction
    open suspend fun beginSnapshot(
        snapshotId: String,
        originDevice: String,
        expectedItemCount: Int,
        receivedAt: Long,
        expectedDigest: String? = null,
        peerLinkId: String = LEGACY_PEER_LINK_ID,
    ): SnapshotBeginResult {
        require(snapshotId.isNotEmpty())
        require(originDevice.isNotEmpty())
        require(expectedItemCount in 0..MAX_SNAPSHOT_ITEMS)
        expectedDigest?.let {
            require(it.matches(Regex("^[0-9a-f]{64}$"))) { "snapshot digest must be lower-case SHA-256" }
        }

        val existingBegin = stagedSnapshot(snapshotId, peerLinkId).singleOrNull { it.canonId == SNAPSHOT_BEGIN_MARKER_CANON_ID }
        if (existingBegin != null) {
            val existingMarker = parseSnapshotBeginMarker(existingBegin.payloadJson)
            // Redelivered begin frames are idempotent. Keep already staged items so a duplicate
            // control frame cannot erase a snapshot currently converging.
            if (
                existingMarker.originDevice == originDevice &&
                existingBegin.sequence == expectedItemCount.toLong() &&
                (expectedDigest == null || existingMarker.expectedDigest == expectedDigest)
            ) return SnapshotBeginResult.Started(
                stagedSnapshot(snapshotId, peerLinkId).count { !it.canonId.startsWith(SNAPSHOT_RESERVED_CANON_PREFIX) },
            )
            error("snapshot_begin_conflict")
        }
        check(snapshotSessionCount() < MAX_SNAPSHOT_SESSIONS) { "snapshot_session_capacity" }
        val baseline = canonicalsForOrigin(originDevice).filter {
            it.state != "CANCELLED" && isNotificationSnapshotCanonical(it.canonId)
        }
        check(baseline.size <= MAX_SNAPSHOT_ITEMS && snapshotStageCount() + baseline.size + 1 <= MAX_SNAPSHOT_STAGE_ROWS) {
            "snapshot_stage_capacity"
        }
        check(snapshotStageBytes() + baseline.sumOf { it.canonId.toByteArray(Charsets.UTF_8).size.toLong() } + 1024 <= MAX_SNAPSHOT_STAGE_BYTES) {
            "snapshot_stage_byte_capacity"
        }
        putSnapshotStages(
            buildList {
                add(
                    SnapshotStage(
                        snapshotId = snapshotId,
                        peerLinkId = peerLinkId,
                        canonId = SNAPSHOT_BEGIN_MARKER_CANON_ID,
                        sequence = expectedItemCount.toLong(),
                        payloadJson = snapshotBeginMarker(originDevice, expectedDigest),
                        receivedAt = receivedAt,
                    ),
                )
                baseline.forEach { current ->
                    add(
                        SnapshotStage(
                            snapshotId = snapshotId,
                        peerLinkId = peerLinkId,
                            canonId = SNAPSHOT_BASELINE_MARKER_PREFIX + current.canonId,
                            sequence = current.latestSequence,
                            payloadJson = current.canonId,
                            receivedAt = receivedAt,
                        ),
                    )
                }
            },
        )
        return SnapshotBeginResult.Started(baseline.size)
    }

    @Transaction
    open suspend fun stageSnapshotItem(row: SnapshotStage): SnapshotStageResult {
        return stageSnapshotItem(row, expectedOriginDevice = null)
    }

    @Transaction
    open suspend fun stageSnapshotItem(
        row: SnapshotStage,
        expectedOriginDevice: String?,
    ): SnapshotStageResult {
        require(!row.canonId.startsWith(SNAPSHOT_RESERVED_CANON_PREFIX))
        require(isNotificationSnapshotCanonical(row.canonId)) {
            "snapshot item canonical ID must be notification scoped"
        }
        require(row.sequence > 0) { "snapshot sequence must be positive" }
        require(row.payloadJson.toByteArray(Charsets.UTF_8).size <= MAX_SNAPSHOT_ITEM_BYTES) {
            "snapshot item payload exceeds bounded size"
        }
        val begin = stagedSnapshot(row.snapshotId, row.peerLinkId).singleOrNull {
            it.canonId == SNAPSHOT_BEGIN_MARKER_CANON_ID
        }
        if (begin == null) return SnapshotStageResult.MissingBegin
        if (expectedOriginDevice != null && parseSnapshotBeginMarker(begin.payloadJson).originDevice != expectedOriginDevice) {
            return SnapshotStageResult.OriginMismatch
        }
        val staged = stagedSnapshot(row.snapshotId, row.peerLinkId)
        staged.singleOrNull { it.canonId == row.canonId }?.let { existing ->
            check(existing.sequence == row.sequence && existing.payloadJson == row.payloadJson) { "snapshot_item_conflict" }
            return SnapshotStageResult.Staged
        }
        check(staged.count { !it.canonId.startsWith(SNAPSHOT_RESERVED_CANON_PREFIX) } < begin.sequence) { "snapshot_item_count_exceeded" }
        check(snapshotStageCount() < MAX_SNAPSHOT_STAGE_ROWS &&
            snapshotStageBytes() + row.payloadJson.toByteArray(Charsets.UTF_8).size <= MAX_SNAPSHOT_STAGE_BYTES) { "snapshot_stage_capacity" }
        putSnapshotStages(listOf(row))
        return SnapshotStageResult.Staged
    }

    @Transaction
    open suspend fun commitSnapshot(
        snapshotId: String,
        committedAt: Long,
    ): SnapshotCommitResult {
        return commitSnapshot(snapshotId, expectedDigest = null, committedAt = committedAt)
    }

    /**
     * Validates a complete staged snapshot before any desired-state mutation.  The overload
     * retains the original Task 5 API while allowing the authenticated end frame to supply the
     * digest that protects the atomic reconciliation boundary.
     */
    @Transaction
    open suspend fun commitSnapshot(
        snapshotId: String,
        expectedDigest: String?,
        committedAt: Long,
    ): SnapshotCommitResult {
        return commitSnapshot(snapshotId, expectedDigest, committedAt, expectedOriginDevice = null)
    }

    @Transaction
    open suspend fun commitSnapshot(
        snapshotId: String,
        expectedDigest: String?,
        committedAt: Long,
        expectedOriginDevice: String?,
        peerLinkId: String = LEGACY_PEER_LINK_ID,
    ): SnapshotCommitResult {
        val rows = stagedSnapshot(snapshotId, peerLinkId)
        val begin = rows.singleOrNull { it.canonId == SNAPSHOT_BEGIN_MARKER_CANON_ID }
            ?: return SnapshotCommitResult.MissingBegin
        val expectedItemCount = begin.sequence.toInt()
        check(expectedItemCount >= 0 && expectedItemCount.toLong() == begin.sequence)
        val marker = parseSnapshotBeginMarker(begin.payloadJson)
        val originDevice = marker.originDevice
        val snapshotAge = committedAt - begin.receivedAt
        if (snapshotAge > SNAPSHOT_TTL_MS) {
            deleteSnapshot(snapshotId, peerLinkId)
            return SnapshotCommitResult.Expired(snapshotAge)
        }
        if (expectedOriginDevice != null && expectedOriginDevice != originDevice) {
            return SnapshotCommitResult.DigestMismatch(expectedOriginDevice, originDevice)
        }
        val baselineByCanonId = rows.asSequence()
            .filter { it.canonId.startsWith(SNAPSHOT_BASELINE_MARKER_PREFIX) }
            .associate { it.payloadJson to it.sequence }
        val staged = rows.filter { !it.canonId.startsWith(SNAPSHOT_RESERVED_CANON_PREFIX) }
        val invalidItem = staged.firstOrNull { !isNotificationSnapshotCanonical(it.canonId) }
        if (invalidItem != null) {
            deleteSnapshot(snapshotId, peerLinkId)
            return SnapshotCommitResult.InvalidItem(invalidItem.canonId)
        }
        if (staged.size != expectedItemCount) {
            return SnapshotCommitResult.Incomplete(expectedItemCount, staged.size)
        }

        val actualDigest = snapshotDigest(staged)
        val digest = expectedDigest ?: marker.expectedDigest
        if (digest != null && digest != actualDigest) {
            return SnapshotCommitResult.DigestMismatch(digest, actualDigest)
        }

        var upserted = 0
        for (item in staged) {
            val current = canonical(item.canonId)
            // Canonical ownership is immutable. A snapshot from one origin cannot overwrite
            // state belonging to another origin, even when its sequence is numerically newer.
            if (current != null && current.originDevice != originDevice) continue
            if (current == null || item.sequence > current.latestSequence) {
                val mirrorId = current?.mirrorLocalId ?: nextMirrorLocalId()
                upsertActiveNotificationDetail(item.canonId, originDevice, item.payloadJson, committedAt)
                putCanonical(
                    CanonicalNotificationState(
                        canonId = item.canonId,
                        peerLinkId = peerLinkId,
                        originDevice = originDevice,
                        latestSequence = item.sequence,
                        state = "ACTIVE",
                        desiredPayloadJson = item.payloadJson,
                        materializedSequence = current?.materializedSequence ?: 0,
                        sourceNotificationKey = current?.sourceNotificationKey,
                        mirrorLocalId = mirrorId,
                        mirrorLocalTag = current?.mirrorLocalTag ?: stableSnapshotMirrorTag(item.canonId),
                        peerCancelPending = current?.peerCancelPending ?: false,
                        updatedAt = committedAt,
                    ),
                )
                upserted += 1
            }
        }

        val stagedIds = staged.mapTo(hashSetOf()) { it.canonId }
        var cancelled = 0
        for (current in canonicalsForOrigin(originDevice).filter {
            isNotificationSnapshotCanonical(it.canonId)
        }) {
            val beginSequence = baselineByCanonId[current.canonId]
            if (
                current.canonId !in stagedIds &&
                current.state != "CANCELLED" &&
                beginSequence != null &&
                current.latestSequence <= beginSequence
            ) {
                notificationDetailForCanon(current.canonId)?.let { detail ->
                    putNotificationDetail(detail.copy(updatedAt = committedAt, cancelledAt = committedAt))
                }
                putCanonical(
                    current.copy(
                        latestSequence = current.latestSequence + 1,
                        state = "CANCELLED",
                        desiredPayloadJson = null,
                        updatedAt = committedAt,
                    ),
                )
                cancelled += 1
            }
        }
        if (cancelled > 0) sweepNotificationDetailCache(committedAt)
        deleteSnapshot(snapshotId, peerLinkId)
        return SnapshotCommitResult.Committed(upserted, cancelled)
    }

    @Transaction
    open suspend fun moveToTerminalActivity(
        msgId: String,
        activity: ActivityEvent,
        verifiedDelivery: VerifiedDeliveryMetric? = null,
    ): TerminalMovementResult {
        require(activity.msgId == msgId)
        val outbound = outbound(msgId)
        if (outbound == null) {
            return if (activityForMessage(msgId) != null) {
                TerminalMovementResult.AlreadyMoved
            } else {
                TerminalMovementResult.Missing
            }
        }
        insertActivity(activity)
        verifiedDelivery?.let {
            require(it.msgId == msgId)
            insertVerifiedDeliveryMetric(it)
            deleteVerifiedDeliveryMetricsBefore((it.verifiedAt - VERIFIED_METRIC_RETENTION_MS).coerceAtLeast(0L))
            trimVerifiedDeliveryMetrics(VERIFIED_METRIC_MAX_ROWS)
        }
        uiActivityForMessage(msgId)?.let { ui ->
            val terminalStatus = when (activity.status) {
                "applied" -> UiActivityStatus.DELIVERED
                "expired" -> UiActivityStatus.EXPIRED
                else -> UiActivityStatus.FAILED
            }
            upsertUiActivity(
                ui.copy(
                    status = terminalStatus.name,
                    route = outbound.custodyRoute ?: ui.route,
                    occurredAt = activity.occurredAt,
                ),
            )
        }
        deleteOutbound(msgId)
        return TerminalMovementResult.Moved
    }

    @Transaction
    override suspend fun convertLegacy(
        legacyId: Long,
        row: OutboundMessage,
    ): LegacyConversionResult {
        if (legacy(legacyId) == null) return LegacyConversionResult.AlreadyConverted
        val existing = outbound(row.msgId)
        if (existing != null && existing.envelopeSha256 != row.envelopeSha256) {
            return LegacyConversionResult.Conflict(existing.envelopeSha256)
        }
        if (existing == null) {
            val owners = activePeerLinkIds()
            check(owners.size <= 1) { "legacy_queue_ownership_ambiguous" }
            insertOutbound(if (owners.isEmpty()) row else row.copy(peerLinkId = owners.single()))
        }
        check(deleteLegacy(legacyId) == 1)
        return if (existing == null) {
            LegacyConversionResult.Converted
        } else {
            LegacyConversionResult.AlreadyConverted
        }
    }

    private companion object {
        val VERIFIED_NOTIFICATION_EVENT_TYPES = setOf("notif.post", "notif.update")
        const val VERIFIED_METRIC_RETENTION_MS = 32L * 24L * 60L * 60L * 1_000L
        const val VERIFIED_METRIC_MAX_ROWS = 10_000
        val DIRECT_ACK_CONTROL_TYPES = setOf(
            "peer.receipt",
            "state.digest",
            "state.snapshot.begin",
            "state.snapshot.item",
            "state.snapshot.end",
        )
        val RECEIPT_BACKED_CONTROL_TYPES = setOf("lan.bootstrap", "peer.probe", "relay.attach")
        val ACTION_RESULT_STATUSES = setOf(
            "dispatched",
            "outcome_unknown",
            "action_gone",
            "notification_gone",
            "expired",
            "failed",
        )
        val CALL_CONTROL_RESULT_STATUSES = setOf(
            "dispatched",
            "outcome_unknown",
            "capability_gone",
            "call_gone",
            "stale_state",
            "expired",
            "failed",
        )
        val POST_OR_UPDATE_EVENT_TYPES = setOf("notif.post", "notif.update")
        val STATE_EVENT_TYPES = POST_OR_UPDATE_EVENT_TYPES + setOf("notif.cancel", "call.state")
        const val SNAPSHOT_RESERVED_CANON_PREFIX = "\u0000"
        const val SNAPSHOT_BEGIN_MARKER_CANON_ID = "${SNAPSHOT_RESERVED_CANON_PREFIX}begin"
        const val SNAPSHOT_BASELINE_MARKER_PREFIX = "${SNAPSHOT_RESERVED_CANON_PREFIX}baseline:"
        const val MAX_SNAPSHOT_SESSIONS = 4
        const val MAX_SNAPSHOT_STAGE_ROWS = 16_388
        const val MAX_SNAPSHOT_STAGE_BYTES = 32L * 1024 * 1024
        const val MAX_SNAPSHOT_ITEMS = 4_096
        const val MAX_SNAPSHOT_ITEM_BYTES = 512 * 1024
        const val UI_HISTORY_CONTENT_MAX_BYTES = 2L * 1024L * 1024L
        const val SNAPSHOT_TTL_MS = 10 * 60 * 1_000L
        const val ACTION_EXECUTION_RETENTION_MS = 24 * 60 * 60 * 1_000L
        const val NOTIFICATION_DETAIL_CANCELLED_RETENTION_MS = 10 * 60 * 1_000L
        const val MAX_CANCELLED_NOTIFICATION_DETAILS = 500

        private data class SnapshotBeginMarker(val originDevice: String, val expectedDigest: String?)

        fun snapshotBeginMarker(originDevice: String, expectedDigest: String?): String =
            org.json.JSONObject().apply {
                put("origin_device", originDevice)
                put("expected_digest", expectedDigest ?: org.json.JSONObject.NULL)
            }.toString()

        fun parseSnapshotBeginMarker(raw: String): SnapshotBeginMarker = runCatching {
            val json = org.json.JSONObject(raw)
            SnapshotBeginMarker(
                originDevice = json.getString("origin_device"),
                expectedDigest = if (json.isNull("expected_digest")) null else json.getString("expected_digest"),
            )
        }.getOrElse {
            // Rows written by the original Task 5 API stored the origin as plain text.
            SnapshotBeginMarker(raw, null)
        }

        fun snapshotDigest(rows: List<SnapshotStage>): String {
            val canonicalLines = rows
                .filterNot { it.canonId.startsWith(SNAPSHOT_RESERVED_CANON_PREFIX) }
                .sortedBy { it.canonId }
                .joinToString("\n") { "${it.canonId}\u0000${it.sequence}\u0000ACTIVE" }
            return java.security.MessageDigest.getInstance("SHA-256")
                .digest(canonicalLines.toByteArray(Charsets.UTF_8))
                .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
        }

        fun stableSnapshotMirrorTag(canonId: String): String {
            val digest = java.security.MessageDigest.getInstance("SHA-256")
                .digest(canonId.toByteArray(Charsets.UTF_8))
            return "mirror-" + digest.joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }.take(24)
        }

        fun claimGraceElapsed(claimedAt: Long, now: Long): Boolean =
            now >= claimedAt && now - claimedAt >= 60_000L
    }
}
