package co.twinotify.core.storage

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction

sealed interface SequenceReservationResult {
    data class Reserved(val sequence: Long) : SequenceReservationResult
}

sealed interface InboundDesiredCommitResult {
    data object Committed : InboundDesiredCommitResult
    data class Duplicate(val outcome: String, val receiptMsgId: String?) : InboundDesiredCommitResult
    data class IdConflict(val existingSha256: String) : InboundDesiredCommitResult
    data class Stale(val latestSequence: Long) : InboundDesiredCommitResult
}

sealed interface MaterializationResult {
    data object Completed : MaterializationResult
    data object AlreadyCompleted : MaterializationResult
    data object Superseded : MaterializationResult
    data object Missing : MaterializationResult
    data class ReceiptConflict(val existingSha256: String) : MaterializationResult
}

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

sealed interface SnapshotCommitResult {
    data class Committed(val upserted: Int, val cancelled: Int) : SnapshotCommitResult
    data class Incomplete(val expected: Int, val staged: Int) : SnapshotCommitResult
    data object MissingBegin : SnapshotCommitResult
}

sealed interface SnapshotBeginResult {
    data class Started(val baselineCount: Int) : SnapshotBeginResult
}

sealed interface SnapshotStageResult {
    data object Staged : SnapshotStageResult
    data object MissingBegin : SnapshotStageResult
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

interface LegacyOutboxStore {
    suspend fun legacyBatch(limit: Int): List<LegacyOutboundEvent>
    suspend fun convertLegacy(legacyId: Long, row: OutboundMessage): LegacyConversionResult
}

@Dao
abstract class ReliableDeliveryDao : LegacyOutboxStore {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    abstract suspend fun insertOutbound(row: OutboundMessage)

    @Query(
        "SELECT * FROM outbound_message WHERE state IN ('NEW','ACCEPTED') " +
            "AND nextAttemptAt <= :now ORDER BY createdAt LIMIT :limit",
    )
    abstract suspend fun sendable(now: Long, limit: Int): List<OutboundMessage>

    @Query(
        "UPDATE outbound_message SET state='ACCEPTED', relayAcceptedAt=:acceptedAt, " +
            "nextAttemptAt=:retryAt WHERE msgId=:msgId",
    )
    abstract suspend fun markRelayAccepted(msgId: String, acceptedAt: Long, retryAt: Long): Int

    @Query("DELETE FROM outbound_message WHERE msgId=:msgId")
    abstract suspend fun deleteOutbound(msgId: String): Int

    @Query("SELECT * FROM inbound_message WHERE msgId=:msgId")
    abstract suspend fun inbound(msgId: String): InboundMessage?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    abstract suspend fun insertInbound(row: InboundMessage)

    @Query("SELECT * FROM canonical_notification_state WHERE canonId=:canonId")
    abstract suspend fun canonical(canonId: String): CanonicalNotificationState?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun putCanonical(row: CanonicalNotificationState)

    @Query(
        "SELECT * FROM canonical_notification_state " +
            "WHERE latestSequence > materializedSequence ORDER BY updatedAt",
    )
    abstract suspend fun pendingMaterialization(): List<CanonicalNotificationState>

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

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    protected abstract suspend fun putSnapshotStages(rows: List<SnapshotStage>)

    @Query("SELECT * FROM canonical_notification_state WHERE originDevice=:originDevice")
    protected abstract suspend fun canonicalsForOrigin(
        originDevice: String,
    ): List<CanonicalNotificationState>

    @Query("DELETE FROM snapshot_stage WHERE snapshotId=:snapshotId")
    protected abstract suspend fun deleteSnapshot(snapshotId: String): Int

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
            if (current != null && desired.latestSequence <= current.latestSequence) {
                insertInbound(row.copy(outcome = "STALE"))
                return InboundDesiredCommitResult.Stale(current.latestSequence)
            }
        }

        insertInbound(row)
        if (desired != null) putCanonical(desired)
        return InboundDesiredCommitResult.Committed
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
        }

        val pending = pendingInbound(canonId, sequence)
        pending.forEach { markInboundApplied(it.msgId, appliedAt, receipt?.msgId) }
        putCanonical(state.copy(materializedSequence = sequence, updatedAt = appliedAt))
        return MaterializationResult.Completed
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

    @Transaction
    open suspend fun beginSnapshot(
        snapshotId: String,
        originDevice: String,
        expectedItemCount: Int,
        receivedAt: Long,
    ): SnapshotBeginResult {
        require(snapshotId.isNotEmpty())
        require(originDevice.isNotEmpty())
        require(expectedItemCount >= 0)

        deleteSnapshot(snapshotId)
        val baseline = canonicalsForOrigin(originDevice).filter { it.state != "CANCELLED" }
        putSnapshotStages(
            buildList {
                add(
                    SnapshotStage(
                        snapshotId = snapshotId,
                        canonId = SNAPSHOT_BEGIN_MARKER_CANON_ID,
                        sequence = expectedItemCount.toLong(),
                        payloadJson = originDevice,
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
        require(!row.canonId.startsWith(SNAPSHOT_RESERVED_CANON_PREFIX))
        val hasBegin = stagedSnapshot(row.snapshotId).any {
            it.canonId == SNAPSHOT_BEGIN_MARKER_CANON_ID
        }
        if (!hasBegin) return SnapshotStageResult.MissingBegin
        putSnapshotStages(listOf(row))
        return SnapshotStageResult.Staged
    }

    @Transaction
    open suspend fun commitSnapshot(
        snapshotId: String,
        committedAt: Long,
    ): SnapshotCommitResult {
        val rows = stagedSnapshot(snapshotId)
        val begin = rows.singleOrNull { it.canonId == SNAPSHOT_BEGIN_MARKER_CANON_ID }
            ?: return SnapshotCommitResult.MissingBegin
        val expectedItemCount = begin.sequence.toInt()
        check(expectedItemCount >= 0 && expectedItemCount.toLong() == begin.sequence)
        val originDevice = begin.payloadJson
        val baselineByCanonId = rows.asSequence()
            .filter { it.canonId.startsWith(SNAPSHOT_BASELINE_MARKER_PREFIX) }
            .associate { it.payloadJson to it.sequence }
        val staged = rows.filter { !it.canonId.startsWith(SNAPSHOT_RESERVED_CANON_PREFIX) }
        if (staged.size != expectedItemCount) {
            return SnapshotCommitResult.Incomplete(expectedItemCount, staged.size)
        }

        var upserted = 0
        for (item in staged) {
            val current = canonical(item.canonId)
            if (current == null || item.sequence > current.latestSequence) {
                putCanonical(
                    CanonicalNotificationState(
                        canonId = item.canonId,
                        originDevice = originDevice,
                        latestSequence = item.sequence,
                        state = "ACTIVE",
                        desiredPayloadJson = item.payloadJson,
                        materializedSequence = current?.materializedSequence ?: 0,
                        sourceNotificationKey = current?.sourceNotificationKey,
                        mirrorLocalId = current?.mirrorLocalId,
                        mirrorLocalTag = current?.mirrorLocalTag,
                        peerCancelPending = current?.peerCancelPending ?: false,
                        updatedAt = committedAt,
                    ),
                )
                upserted += 1
            }
        }

        val stagedIds = staged.mapTo(hashSetOf()) { it.canonId }
        var cancelled = 0
        for (current in canonicalsForOrigin(originDevice)) {
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
        val POST_OR_UPDATE_EVENT_TYPES = setOf("notif.post", "notif.update")
        val STATE_EVENT_TYPES = POST_OR_UPDATE_EVENT_TYPES + "notif.cancel"
        const val SNAPSHOT_RESERVED_CANON_PREFIX = "\u0000"
        const val SNAPSHOT_BEGIN_MARKER_CANON_ID = "${SNAPSHOT_RESERVED_CANON_PREFIX}begin"
        const val SNAPSHOT_BASELINE_MARKER_PREFIX = "${SNAPSHOT_RESERVED_CANON_PREFIX}baseline:"
    }
}
