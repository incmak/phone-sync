package co.twinotify.core.storage

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "outbound_message",
    indices = [Index("state"), Index("nextAttemptAt"), Index("canonId")],
)
data class OutboundMessage(
    @PrimaryKey val msgId: String,
    val canonId: String?,
    val sequence: Long?,
    val eventType: String,
    val protocolVersion: Int,
    val envelopeJson: String,
    val envelopeSha256: String,
    val byteSize: Long,
    val createdAt: Long,
    val expiresAt: Long,
    val relayAcceptedAt: Long?,
    val attempts: Int,
    val nextAttemptAt: Long,
    val state: String,
    val lastError: String?,
    val requiresPeerReceipt: Boolean,
)

@Entity(
    tableName = "inbound_message",
    indices = [Index("outcome"), Index("canonId")],
)
data class InboundMessage(
    @PrimaryKey val msgId: String,
    val originDevice: String,
    val envelopeSha256: String,
    val eventType: String,
    val canonId: String?,
    val sequence: Long?,
    val outcome: String,
    val committedAt: Long,
    val appliedAt: Long?,
    val receiptMsgId: String?,
    val relayAckState: String,
)

@Entity(
    tableName = "canonical_notification_state",
    indices = [Index(value = ["mirrorLocalTag", "mirrorLocalId"], unique = true)],
)
data class CanonicalNotificationState(
    @PrimaryKey val canonId: String,
    val originDevice: String,
    val latestSequence: Long,
    val state: String,
    val desiredPayloadJson: String?,
    val materializedSequence: Long,
    val sourceNotificationKey: String?,
    val mirrorLocalId: Int?,
    val mirrorLocalTag: String?,
    val peerCancelPending: Boolean,
    val updatedAt: Long,
)

@Entity(tableName = "origin_sequence")
data class OriginSequence(
    @PrimaryKey val canonId: String,
    val nextSequence: Long,
)

@Entity(tableName = "activity_event", indices = [Index("occurredAt")])
data class ActivityEvent(
    @PrimaryKey val eventId: String,
    val msgId: String?,
    val packageName: String?,
    val eventType: String,
    val status: String,
    val byteSize: Long,
    val occurredAt: Long,
    val detailCode: String?,
)

@Entity(tableName = "snapshot_stage", primaryKeys = ["snapshotId", "canonId"])
data class SnapshotStage(
    val snapshotId: String,
    val canonId: String,
    val sequence: Long,
    val payloadJson: String,
    val receivedAt: Long,
)
