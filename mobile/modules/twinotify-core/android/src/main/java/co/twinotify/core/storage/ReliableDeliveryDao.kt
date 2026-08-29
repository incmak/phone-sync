package co.twinotify.core.storage

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import co.twinotify.core.service.DirectControlCommitResult
import co.twinotify.core.service.DirectControlProcessingResult
import co.twinotify.core.service.CallRejectionCommitResult

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
    abstract suspend fun insertOutbound(row: OutboundMessage)

    @Query(
        "SELECT * FROM outbound_message WHERE state IN ('NEW','ACCEPTED') " +
            "AND nextAttemptAt <= :now ORDER BY createdAt, rowid LIMIT :limit",
    )
    abstract suspend fun sendable(now: Long, limit: Int): List<OutboundMessage>

    @Query(
        "SELECT * FROM outbound_message WHERE protocolVersion=2 AND expiresAt <= :now AND " +
            "relayCustodyState='NONE' AND ((state='NEW' AND custodyAcceptedAt IS NULL) OR " +
            "state='ACCEPTED') ORDER BY createdAt, rowid",
    )
    protected abstract suspend fun locallyExpired(now: Long): List<OutboundMessage>

    @Query(
        "UPDATE outbound_message SET attempts=attempts + 1, nextAttemptAt=:retryAt " +
            "WHERE msgId=:msgId AND state IN ('NEW','ACCEPTED')",
    )
    abstract suspend fun markSent(msgId: String, retryAt: Long): Int

    @Query("DELETE FROM outbound_message WHERE msgId=:msgId")
    abstract suspend fun deleteOutbound(msgId: String): Int

    @Query("SELECT * FROM outbound_message WHERE msgId=:msgId")
    abstract suspend fun outboundMessage(msgId: String): OutboundMessage?

    @Query("SELECT COUNT(*) FROM outbound_message WHERE state NOT IN ('TERMINAL','EXPIRED')")
    abstract suspend fun activeOutboundCount(): Int

    @Query("SELECT COALESCE(SUM(byteSize), 0) FROM outbound_message WHERE state NOT IN ('TERMINAL','EXPIRED')")
    abstract suspend fun activeOutboundBytes(): Long

    @Query("DELETE FROM activity_event WHERE occurredAt < :cutoff")
    protected abstract suspend fun deleteActivityBefore(cutoff: Long): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract override suspend fun upsertUiActivity(row: UiActivityEvent)

    @Query("SELECT * FROM ui_activity_event WHERE msgId=:msgId LIMIT 1")
    abstract override suspend fun uiActivityForMessage(msgId: String): UiActivityEvent?

    @Query("SELECT * FROM ui_activity_event ORDER BY occurredAt DESC, eventId DESC LIMIT :limit")
    abstract override suspend fun recentUiActivity(limit: Int): List<UiActivityEvent>

    @Query("DELETE FROM ui_activity_event WHERE occurredAt < :cutoff")
    abstract override suspend fun deleteUiActivityBefore(cutoff: Long): Int

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
        removed += deleteCancelledBefore(now - tombstoneRetentionMs)
        removed += deleteOrphanMaterializationRetries()
        removed += expireSnapshotStages(now - SNAPSHOT_TTL_MS)
        return removed
    }

    @Transaction
    open suspend fun clearReliableState() {
        clearOutboundMessages()
        clearInboundMessages()
        clearCanonicalStates()
        clearOriginSequences()
        clearActivityEvents()
        clearUiActivityEvents()
        clearSnapshotStages()
        clearMaterializationRetries()
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
    @Query("DELETE FROM snapshot_stage")
    protected abstract suspend fun clearSnapshotStages()
    @Query("DELETE FROM materialization_retry")
    protected abstract suspend fun clearMaterializationRetries()

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

    @Query("SELECT msgId, envelopeSha256 FROM inbound_message WHERE relayAckState='READY' ORDER BY committedAt LIMIT :limit")
    abstract suspend fun readyRelayAcks(limit: Int): List<co.twinotify.core.service.RelayAckRecord>

    @Query("UPDATE inbound_message SET relayAckState='SENT' WHERE msgId=:msgId AND envelopeSha256=:envelopeSha256 AND relayAckState='READY'")
    abstract suspend fun markRelayAckSent(msgId: String, envelopeSha256: String): Int

    @Transaction
    open suspend fun markLegacyForwarded(msgId: String, forwardedAt: Long): LegacyForwardResult {
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

    @Query("SELECT * FROM inbound_message WHERE msgId=:msgId")
    abstract suspend fun inbound(msgId: String): InboundMessage?

    @Transaction
    open suspend fun commitDirectControl(
        row: InboundMessage,
        process: suspend () -> DirectControlProcessingResult,
    ): DirectControlCommitResult {
        if (row.eventType !in DIRECT_ACK_CONTROL_TYPES) return DirectControlCommitResult.NotEligible
        require(row.canonId == null && row.sequence == null)
        require(row.outcome == "APPLIED" && row.appliedAt != null && row.relayAckState == "READY")
        val existing = inbound(row.msgId)
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

    @Insert(onConflict = OnConflictStrategy.ABORT)
    abstract suspend fun insertInbound(row: InboundMessage)

    @Transaction
    open suspend fun commitInboundRejection(
        row: InboundMessage,
        receipt: OutboundMessage,
    ): CallRejectionCommitResult {
        require(row.outcome == "REJECTED")
        require(row.receiptMsgId == receipt.msgId)
        require(receipt.eventType == "peer.receipt" && !receipt.requiresPeerReceipt)
        val existing = inbound(row.msgId)
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
            "WHERE state.latestSequence > state.materializedSequence AND (" +
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
    open suspend fun nextCaptureSequenceForEvent(canonId: String): Long =
        originSequence(canonId)?.nextSequence
            ?: canonical(canonId)?.latestSequence?.plus(1L)
            ?: 1L

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

    @Query("UPDATE inbound_message SET outcome='REJECTED', appliedAt=:at, receiptMsgId=:receiptMsgId WHERE msgId=:msgId AND outcome='PENDING_PLATFORM'")
    protected abstract suspend fun markInboundRejected(msgId: String, at: Long, receiptMsgId: String): Int

    @Query(
        "UPDATE inbound_message SET outcome='APPLIED', appliedAt=:appliedAt, receiptMsgId=:receiptMsgId " +
            "WHERE msgId=:msgId AND outcome='PENDING_PLATFORM'",
    )
    protected abstract suspend fun markInboundApplied(
        msgId: String,
        appliedAt: Long,
        receiptMsgId: String?,
    ): Int

    @Query("SELECT * FROM inbound_message WHERE receiptMsgId=:receiptMsgId LIMIT 1")
    protected abstract suspend fun inboundForReceipt(receiptMsgId: String): InboundMessage?

    @Query("UPDATE inbound_message SET relayAckState='READY' WHERE receiptMsgId=:receiptMsgId")
    protected abstract suspend fun markRelayAckReady(receiptMsgId: String): Int

    @Query(
        "UPDATE inbound_message SET receiptMsgId=:receiptMsgId " +
            "WHERE msgId=:msgId AND outcome='PENDING_PLATFORM' AND receiptMsgId IS NULL",
    )
    protected abstract suspend fun linkMaterializationReceipt(msgId: String, receiptMsgId: String): Int

    @Query("UPDATE outbound_message SET state='NEW' WHERE msgId=:msgId AND state='PENDING_PLATFORM'")
    protected abstract suspend fun activateMaterializationReceipt(msgId: String): Int

    @Query(
        "SELECT * FROM outbound_message WHERE canonId=:canonId AND state='NEW' " +
            "AND eventType IN ('notif.post','notif.update','notif.cancel') ORDER BY createdAt",
    )
    protected abstract suspend fun compactableState(canonId: String): List<OutboundMessage>

    @Query("DELETE FROM outbound_message WHERE msgId IN (:msgIds)")
    protected abstract suspend fun deleteOutboundIds(msgIds: List<String>): Int

    @Insert(onConflict = OnConflictStrategy.ABORT)
    protected abstract suspend fun insertActivity(row: ActivityEvent)

    @Query("SELECT * FROM activity_event WHERE msgId=:msgId LIMIT 1")
    protected abstract suspend fun activityForMessage(msgId: String): ActivityEvent?

    @Query("SELECT * FROM snapshot_stage WHERE snapshotId=:snapshotId ORDER BY canonId")
    protected abstract suspend fun stagedSnapshot(snapshotId: String): List<SnapshotStage>

    @Query("SELECT DISTINCT snapshotId FROM snapshot_stage WHERE receivedAt < :cutoff")
    protected abstract suspend fun expiredSnapshotIds(cutoff: Long): List<String>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    protected abstract suspend fun putSnapshotStages(rows: List<SnapshotStage>)

    @Query("SELECT * FROM canonical_notification_state WHERE originDevice=:originDevice")
    protected abstract suspend fun canonicalsForOrigin(
        originDevice: String,
    ): List<CanonicalNotificationState>

    @Query("DELETE FROM snapshot_stage WHERE snapshotId=:snapshotId")
    protected abstract suspend fun deleteSnapshot(snapshotId: String): Int

    /** Public read surface for the snapshot coordinator and deterministic tests. */
    suspend fun snapshotRows(snapshotId: String): List<SnapshotStage> = stagedSnapshot(snapshotId)

    /** Removes complete snapshot staging sessions, never individual rows. */
    @Transaction
    open suspend fun expireSnapshotStages(cutoff: Long): Int {
        require(cutoff >= 0) { "snapshot expiry cutoff must be non-negative" }
        val ids = expiredSnapshotIds(cutoff)
        ids.forEach { deleteSnapshot(it) }
        return ids.size
    }

    @Transaction
    open suspend fun reserveSequence(canonId: String): SequenceReservationResult {
        val sequence = originSequence(canonId)?.nextSequence ?: 1L
        putOriginSequence(OriginSequence(canonId, sequence + 1L))
        return SequenceReservationResult.Reserved(sequence)
    }

    @Transaction
    open suspend fun commitInboundDesired(
        row: InboundMessage,
        desired: CanonicalNotificationState?,
        supersession: SupersessionBundle = SupersessionBundle(emptyList()),
    ): InboundDesiredCommitResult {
        val existing = inbound(row.msgId)
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
        if (desired != null) putCanonical(desired)
        return InboundDesiredCommitResult.Committed
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
        if (older.isEmpty() && supersession.entries.isEmpty()) return SupersessionMutationResult.Applied
        val byId = supersession.entries.associateBy { it.inboundMsgId }
        if (byId.size != supersession.entries.size || byId.keys != older.map { it.msgId }.toSet() ||
            older.any { byId[it.msgId]?.envelopeSha256 != it.envelopeSha256 } ||
            supersession.entries.map { it.receipt.msgId }.distinct().size != supersession.entries.size
        ) return SupersessionMutationResult.Invalid
        for (olderRow in older) {
            val entry = byId[olderRow.msgId] ?: return SupersessionMutationResult.Invalid
            if (entry.receipt.eventType != "peer.receipt" || entry.receipt.requiresPeerReceipt) {
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
            markInboundRejected(olderRow.msgId, terminalAt, entry.receipt.msgId)
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
        pending.forEach { markInboundApplied(it.msgId, appliedAt, receipt?.msgId) }
        putCanonical(state.copy(materializedSequence = sequence, updatedAt = appliedAt))
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
        val existing = outbound(receipt.msgId)
        if (existing != null && existing.envelopeSha256 != receipt.envelopeSha256) {
            return MaterializationReceiptResult.Conflict(existing.envelopeSha256)
        }
        if (existing == null) {
            insertOutbound(receipt.copy(state = "PENDING_PLATFORM", requiresPeerReceipt = false))
        }
        pending.forEach { linkMaterializationReceipt(it.msgId, receipt.msgId) }
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
    ): CustodyAcceptanceResult {
        require(route == "LAN" || route == "RELAY")
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
    ): RelayReceiptResult {
        require(status in setOf("applied", "expired", "rejected", "decrypt_failed"))
        val row = outboundMessage(ackedMsgId)
            ?: return if (activityForMessage(ackedMsgId) != null) RelayReceiptResult.AlreadyTerminal
            else RelayReceiptResult.Missing
        if (row.envelopeSha256 != envelopeSha256) return RelayReceiptResult.Conflict(row.envelopeSha256)
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
    ): co.twinotify.core.service.RelayRejectionResult {
        val row = outboundMessage(msgId) ?: return if (activityForMessage(msgId) != null) {
            co.twinotify.core.service.RelayRejectionResult.AlreadyTerminal
        } else {
            co.twinotify.core.service.RelayRejectionResult.Missing
        }
        if (reason == "mailbox_full" || reason == "peer_legacy") {
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
    open suspend fun expireRelay(msgId: String, expiredAt: Long): RelayReceiptResult {
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
    open suspend fun commitOutboundState(
        desired: CanonicalNotificationState,
        incoming: OutboundMessage,
    ): OutboundStateCommitResult {
        if (incoming.eventType !in STATE_EVENT_TYPES) {
            return OutboundStateCommitResult.NotStateEvent
        }
        val canonId = requireNotNull(incoming.canonId)
        val incomingSequence = requireNotNull(incoming.sequence)
        require(incoming.state == "NEW")
        require(desired.canonId == canonId)
        require(desired.latestSequence == incomingSequence)

        val candidates = compactableState(canonId)
        val latestSequence = sequenceOf(
            canonical(canonId)?.latestSequence,
            candidates.mapNotNull { it.sequence }.maxOrNull(),
        ).filterNotNull().maxOrNull()
        if (latestSequence != null && incomingSequence <= latestSequence) {
            return OutboundStateCommitResult.Stale(latestSequence)
        }

        val removable = when (incoming.eventType) {
            "notif.post", "notif.update" -> candidates.filter {
                it.sequence != null &&
                    it.sequence < incomingSequence &&
                    it.eventType in POST_OR_UPDATE_EVENT_TYPES
            }
            "notif.cancel" -> candidates.filter {
                it.sequence != null && it.sequence < incomingSequence
            }
            else -> emptyList()
        }
        val compacted = if (removable.isEmpty()) {
            0
        } else {
            deleteOutboundIds(removable.map { it.msgId })
        }
        putCanonical(desired)
        insertOutbound(incoming)
        return OutboundStateCommitResult.Committed(compacted)
    }

    /**
     * Capture-specific commit: the prepared row may enter the durable state only when its
     * sequence reservation is still present. This closes the reservation/commit interleaving
     * without weakening the lower-level transaction helper used by migration tests.
     */
    @Transaction
    open suspend fun commitCapturedState(
        desired: CanonicalNotificationState,
        incoming: OutboundMessage,
    ): OutboundStateCommitResult {
        val sequence = requireNotNull(incoming.sequence)
        val canonId = incoming.canonId ?: return OutboundStateCommitResult.NotStateEvent
        // Sequence allocation and canonical/outbox mutation share this transaction. The caller
        // only reads the next value while preparing crypto; this compare-and-increment is the
        // authoritative reservation and rolls back together with the state/outbox write.
        val nextSequence = originSequence(canonId)?.nextSequence
            ?: canonical(canonId)?.latestSequence?.plus(1L)
            ?: 1L
        if (sequence != nextSequence) return OutboundStateCommitResult.Stale(nextSequence - 1L)
        putOriginSequence(OriginSequence(canonId, nextSequence + 1L))
        return commitOutboundState(desired, incoming)
    }

    /** Recovery commit fenced to the local call ownership selected before capture starts. */
    @Transaction
    open suspend fun commitRecoveredCallState(
        desired: CanonicalNotificationState,
        incoming: OutboundMessage,
        expectedLocalOrigin: String,
    ): CallRecoveryCommitResult {
        val sequence = requireNotNull(incoming.sequence)
        val canonId = incoming.canonId ?: return CallRecoveryCommitResult.NotStateEvent
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
        putOriginSequence(OriginSequence(canonId, nextSequence + 1L))
        return when (val result = commitOutboundState(desired, incoming)) {
            is OutboundStateCommitResult.Committed -> CallRecoveryCommitResult.Committed(result.compacted)
            is OutboundStateCommitResult.Stale -> CallRecoveryCommitResult.Stale(result.latestSequence)
            OutboundStateCommitResult.NotStateEvent -> CallRecoveryCommitResult.NotStateEvent
        }
    }

    @Transaction
    open suspend fun beginSnapshot(
        snapshotId: String,
        originDevice: String,
        expectedItemCount: Int,
        receivedAt: Long,
        expectedDigest: String? = null,
    ): SnapshotBeginResult {
        require(snapshotId.isNotEmpty())
        require(originDevice.isNotEmpty())
        require(expectedItemCount in 0..MAX_SNAPSHOT_ITEMS)
        expectedDigest?.let {
            require(it.matches(Regex("^[0-9a-f]{64}$"))) { "snapshot digest must be lower-case SHA-256" }
        }

        val existingBegin = stagedSnapshot(snapshotId).singleOrNull { it.canonId == SNAPSHOT_BEGIN_MARKER_CANON_ID }
        if (existingBegin != null) {
            val existingMarker = parseSnapshotBeginMarker(existingBegin.payloadJson)
            // Redelivered begin frames are idempotent. Keep already staged items so a duplicate
            // control frame cannot erase a snapshot currently converging.
            if (
                existingMarker.originDevice == originDevice &&
                existingBegin.sequence == expectedItemCount.toLong() &&
                (expectedDigest == null || existingMarker.expectedDigest == expectedDigest)
            ) return SnapshotBeginResult.Started(
                stagedSnapshot(snapshotId).count { !it.canonId.startsWith(SNAPSHOT_RESERVED_CANON_PREFIX) },
            )
            deleteSnapshot(snapshotId)
        } else {
            deleteSnapshot(snapshotId)
        }
        val baseline = canonicalsForOrigin(originDevice).filter {
            it.state != "CANCELLED" && isNotificationSnapshotCanonical(it.canonId)
        }
        putSnapshotStages(
            buildList {
                add(
                    SnapshotStage(
                        snapshotId = snapshotId,
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
        val begin = stagedSnapshot(row.snapshotId).singleOrNull {
            it.canonId == SNAPSHOT_BEGIN_MARKER_CANON_ID
        }
        if (begin == null) return SnapshotStageResult.MissingBegin
        if (expectedOriginDevice != null && parseSnapshotBeginMarker(begin.payloadJson).originDevice != expectedOriginDevice) {
            return SnapshotStageResult.OriginMismatch
        }
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
    ): SnapshotCommitResult {
        val rows = stagedSnapshot(snapshotId)
        val begin = rows.singleOrNull { it.canonId == SNAPSHOT_BEGIN_MARKER_CANON_ID }
            ?: return SnapshotCommitResult.MissingBegin
        val expectedItemCount = begin.sequence.toInt()
        check(expectedItemCount >= 0 && expectedItemCount.toLong() == begin.sequence)
        val marker = parseSnapshotBeginMarker(begin.payloadJson)
        val originDevice = marker.originDevice
        val snapshotAge = committedAt - begin.receivedAt
        if (snapshotAge > SNAPSHOT_TTL_MS) {
            deleteSnapshot(snapshotId)
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
            deleteSnapshot(snapshotId)
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
                putCanonical(
                    CanonicalNotificationState(
                        canonId = item.canonId,
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
        deleteSnapshot(snapshotId)
        return SnapshotCommitResult.Committed(upserted, cancelled)
    }

    @Transaction
    open suspend fun moveToTerminalActivity(
        msgId: String,
        activity: ActivityEvent,
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
        if (existing == null) insertOutbound(row)
        check(deleteLegacy(legacyId) == 1)
        return if (existing == null) {
            LegacyConversionResult.Converted
        } else {
            LegacyConversionResult.AlreadyConverted
        }
    }

    private companion object {
        val DIRECT_ACK_CONTROL_TYPES = setOf(
            "peer.receipt",
            "state.digest",
            "state.snapshot.begin",
            "state.snapshot.item",
            "state.snapshot.end",
        )
        val POST_OR_UPDATE_EVENT_TYPES = setOf("notif.post", "notif.update")
        val STATE_EVENT_TYPES = POST_OR_UPDATE_EVENT_TYPES + setOf("notif.cancel", "call.state")
        const val SNAPSHOT_RESERVED_CANON_PREFIX = "\u0000"
        const val SNAPSHOT_BEGIN_MARKER_CANON_ID = "${SNAPSHOT_RESERVED_CANON_PREFIX}begin"
        const val SNAPSHOT_BASELINE_MARKER_PREFIX = "${SNAPSHOT_RESERVED_CANON_PREFIX}baseline:"
        const val MAX_SNAPSHOT_ITEMS = 4_096
        const val MAX_SNAPSHOT_ITEM_BYTES = 512 * 1024
        const val SNAPSHOT_TTL_MS = 10 * 60 * 1_000L

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
    }
}
