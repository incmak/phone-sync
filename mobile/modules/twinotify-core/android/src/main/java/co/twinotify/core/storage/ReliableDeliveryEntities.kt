package co.twinotify.core.storage

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.TypeConverter
import androidx.room.ColumnInfo

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
    val custodyAcceptedAt: Long?,
    val custodyRoute: String?,
    val attempts: Int,
    val nextAttemptAt: Long,
    val state: String,
    val lastError: String?,
    val requiresPeerReceipt: Boolean,
    @ColumnInfo(defaultValue = "'NONE'") val relayCustodyState: String = "NONE",
) {
    init {
        require(relayCustodyState in setOf("NONE", "UNKNOWN", "ACCEPTED"))
    }
}

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

enum class MaterializationRetryDisposition {
    RETRYABLE,
    PERMISSION_BLOCKED,
}

class ReliableDeliveryTypeConverters {
    @TypeConverter
    fun retryDispositionToString(value: MaterializationRetryDisposition): String = value.name

    @TypeConverter
    fun retryDispositionFromString(value: String): MaterializationRetryDisposition =
        MaterializationRetryDisposition.valueOf(value)
}

@Entity(tableName = "materialization_retry", indices = [Index("nextAttemptAt")])
data class MaterializationRetry(
    @PrimaryKey val canonId: String,
    val sequence: Long,
    val nextAttemptAt: Long?,
    val attempts: Int,
    val disposition: MaterializationRetryDisposition,
    val lastError: String?,
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

@Entity(
    tableName = "ui_activity_event",
    indices = [Index("occurredAt"), Index(value = ["msgId"], unique = true)],
)
data class UiActivityEvent(
    @PrimaryKey val eventId: String,
    val msgId: String?,
    val packageName: String?,
    val appName: String?,
    val direction: String,
    val kind: String,
    val status: String,
    val route: String?,
    val occurredAt: Long,
)

@Entity(tableName = "snapshot_stage", primaryKeys = ["snapshotId", "canonId"])
data class SnapshotStage(
    val snapshotId: String,
    val canonId: String,
    val sequence: Long,
    val payloadJson: String,
    val receivedAt: Long,
)

@Entity(
    tableName = "action_invocation",
    indices = [Index("canonId"), Index("state"), Index("expiresAt")],
)
data class ActionInvocation(
    @PrimaryKey val invocationId: String,
    val canonId: String,
    val actionId: String,
    val notificationSequence: Long,
    val replyText: String?,
    val state: String,
    val createdAt: Long,
    val expiresAt: Long,
    val updatedAt: Long,
) {
    init {
        require(state in ACTION_INVOCATION_STATES)
    }

    override fun toString(): String =
        "ActionInvocation(invocationId=$invocationId, canonId=$canonId, actionId=$actionId, " +
            "notificationSequence=$notificationSequence, replyText=${if (replyText == null) "null" else "<redacted>"}, " +
            "state=$state, createdAt=$createdAt, expiresAt=$expiresAt, updatedAt=$updatedAt)"

    private companion object {
        val ACTION_INVOCATION_STATES = setOf(
            "PENDING",
            "DISPATCHED",
            "OUTCOME_UNKNOWN",
            "FAILED",
            "ACTION_GONE",
            "NOTIFICATION_GONE",
            "EXPIRED",
        )
    }
}

@Entity(
    tableName = "action_execution",
    indices = [Index("state"), Index("claimedAt"), Index("completedAt")],
)
data class ActionExecution(
    @PrimaryKey val invocationId: String,
    val canonId: String,
    val actionId: String,
    val state: String,
    val resultStatus: String?,
    val claimedAt: Long,
    val completedAt: Long?,
) {
    init {
        require(state == "CLAIMED" || state == "COMPLETED")
        require((state == "CLAIMED") == (resultStatus == null && completedAt == null))
    }
}

@Entity(
    tableName = "notification_detail_cache",
    indices = [Index(value = ["canonId"], unique = true), Index("cancelledAt")],
)
data class NotificationDetailCache(
    @PrimaryKey val detailId: String,
    val canonId: String,
    val payloadJson: String,
    val originDevice: String,
    val receivedAt: Long,
    val updatedAt: Long,
    val cancelledAt: Long?,
)
