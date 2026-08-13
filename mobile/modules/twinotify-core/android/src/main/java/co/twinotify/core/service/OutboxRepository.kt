package co.twinotify.core.service

import co.twinotify.core.storage.ActivityEvent
import co.twinotify.core.storage.LegacyForwardResult
import co.twinotify.core.storage.OutboundMessage
import co.twinotify.core.storage.ReliableDeliveryDao
import co.twinotify.core.storage.RelayAcceptanceResult
import co.twinotify.core.storage.RelayReceiptResult

/** A narrow store seam keeps custody transitions deterministic and easy to test without Room. */
interface OutboxStore {
    suspend fun sendable(now: Long, limit: Int): List<OutboundMessage>
    suspend fun markRelaySent(msgId: String, retryAt: Long): Int
    suspend fun legacyForwarded(msgId: String, forwardedAt: Long): LegacyForwardResult
    suspend fun acceptRelay(msgId: String, acceptedAt: Long, retryAt: Long): RelayAcceptanceResult
    suspend fun applyPeerReceipt(
        ackedMsgId: String,
        envelopeSha256: String,
        status: String,
        reason: String?,
        occurredAt: Long,
    ): RelayReceiptResult
    suspend fun rejectRelay(msgId: String, reason: String, occurredAt: Long, retryAt: Long): RelayRejectionResult
    suspend fun expireRelay(msgId: String, expiredAt: Long): RelayReceiptResult
    suspend fun readyRelayAcks(limit: Int): List<RelayAckRecord>
    suspend fun markRelayAckSent(msgId: String, envelopeSha256: String): Int
}

data class RelayAckRecord(val msgId: String, val envelopeSha256: String)

sealed interface RelayRejectionResult {
    data object Missing : RelayRejectionResult
    data object Retained : RelayRejectionResult
    data object Terminal : RelayRejectionResult
    data object AlreadyTerminal : RelayRejectionResult
}

sealed interface OutboxTransition {
    data object Missing : OutboxTransition
    data object Retained : OutboxTransition
    data object Deleted : OutboxTransition
    data object AlreadyTerminal : OutboxTransition
    data class ReadyForRelayAck(val msgId: String, val envelopeSha256: String) : OutboxTransition
    data class Conflict(val existingDigest: String) : OutboxTransition
}

/**
 * Durable outbox state machine. Relay acceptance is custody only: normal rows survive it until a
 * matching authenticated peer receipt. Receipt rows are the one exception and are removed once
 * the relay has durably accepted them, preventing receipt recursion.
 */
class OutboxRepository(
    private val store: OutboxStore,
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val retryPolicy: RetryPolicy = RetryPolicy(),
) {
    suspend fun sendable(limit: Int = 32, now: Long = clock()): List<OutboundMessage> =
        store.sendable(now, limit.coerceIn(1, 32))

    suspend fun markSent(msgId: String, attempt: Int, now: Long = clock()): Boolean {
        val retryAt = now + retryPolicy.delay(attempt)
        return store.markRelaySent(msgId, retryAt) == 1
    }

    suspend fun onLegacyForwarded(msgId: String, forwardedAt: Long = clock()): OutboxTransition =
        when (store.legacyForwarded(msgId, forwardedAt)) {
            LegacyForwardResult.Missing -> OutboxTransition.Missing
            LegacyForwardResult.Deleted -> OutboxTransition.Deleted
            LegacyForwardResult.AlreadyTerminal -> OutboxTransition.AlreadyTerminal
        }

    suspend fun onRelayAccepted(msgId: String, acceptedAt: Long = clock()): OutboxTransition {
        val retryAt = acceptedAt + retryPolicy.delay(0)
        return when (store.acceptRelay(msgId, acceptedAt, retryAt)) {
            RelayAcceptanceResult.Missing -> OutboxTransition.Missing
            RelayAcceptanceResult.DeletedReceipt -> OutboxTransition.Deleted
            RelayAcceptanceResult.Accepted -> OutboxTransition.Retained
            RelayAcceptanceResult.AlreadyAccepted -> OutboxTransition.Retained
        }
    }

    suspend fun onPeerReceipt(
        ackedMsgId: String,
        envelopeSha256: String,
        status: String,
        reason: String? = null,
        occurredAt: Long = clock(),
    ): OutboxTransition = when (
        val result = store.applyPeerReceipt(ackedMsgId, envelopeSha256, status, reason, occurredAt)
    ) {
        RelayReceiptResult.Missing -> OutboxTransition.Missing
        RelayReceiptResult.Deleted -> OutboxTransition.Deleted
        RelayReceiptResult.AlreadyTerminal -> OutboxTransition.AlreadyTerminal
        is RelayReceiptResult.Conflict -> OutboxTransition.Conflict(result.existingDigest)
    }

    suspend fun onRelayRejected(
        msgId: String,
        reason: String,
        attempt: Int,
        occurredAt: Long = clock(),
    ): OutboxTransition {
        val result = store.rejectRelay(msgId, reason, occurredAt, occurredAt + retryPolicy.delay(attempt))
        return when (result) {
            RelayRejectionResult.Missing -> OutboxTransition.Missing
            RelayRejectionResult.Retained -> OutboxTransition.Retained
            RelayRejectionResult.Terminal -> OutboxTransition.Deleted
            RelayRejectionResult.AlreadyTerminal -> OutboxTransition.AlreadyTerminal
        }
    }

    suspend fun onRelayExpired(msgId: String, expiredAt: Long = clock()): OutboxTransition {
        val result = store.expireRelay(msgId, expiredAt)
        return when (result) {
        RelayReceiptResult.Missing -> OutboxTransition.Missing
        RelayReceiptResult.Deleted -> OutboxTransition.Deleted
        RelayReceiptResult.AlreadyTerminal -> OutboxTransition.AlreadyTerminal
            is RelayReceiptResult.Conflict -> OutboxTransition.Conflict(result.existingDigest)
        }
    }

    suspend fun readyRelayAcks(limit: Int = 32): List<RelayAckRecord> = store.readyRelayAcks(limit.coerceIn(1, 32))

    suspend fun markRelayAckSent(record: RelayAckRecord): Boolean =
        store.markRelayAckSent(record.msgId, record.envelopeSha256) == 1
}

data class RetryPolicy(
    val initialMs: Long = 5_000L,
    val maxMs: Long = 60_000L,
) {
    init { require(initialMs > 0 && maxMs >= initialMs) }
    fun delay(attempt: Int): Long {
        val exponent = attempt.coerceIn(0, 30)
        return (initialMs * (1L shl exponent)).coerceAtMost(maxMs)
    }
}

/** Room adapter; all multi-field custody changes are transactional DAO operations. */
class DaoOutboxStore(private val dao: ReliableDeliveryDao) : OutboxStore {
    override suspend fun sendable(now: Long, limit: Int): List<OutboundMessage> = dao.sendable(now, limit)
    override suspend fun markRelaySent(msgId: String, retryAt: Long): Int = dao.markRelaySent(msgId, retryAt)
    override suspend fun legacyForwarded(msgId: String, forwardedAt: Long): LegacyForwardResult = dao.markLegacyForwarded(msgId, forwardedAt)
    override suspend fun acceptRelay(msgId: String, acceptedAt: Long, retryAt: Long): RelayAcceptanceResult =
        dao.acceptRelay(msgId, acceptedAt, retryAt)
    override suspend fun applyPeerReceipt(
        ackedMsgId: String,
        envelopeSha256: String,
        status: String,
        reason: String?,
        occurredAt: Long,
    ): RelayReceiptResult = dao.applyPeerReceipt(ackedMsgId, envelopeSha256, status, reason, occurredAt)
    override suspend fun rejectRelay(msgId: String, reason: String, occurredAt: Long, retryAt: Long): RelayRejectionResult =
        dao.rejectRelay(msgId, reason, occurredAt, retryAt)
    override suspend fun expireRelay(msgId: String, expiredAt: Long): RelayReceiptResult = dao.expireRelay(msgId, expiredAt)
    override suspend fun readyRelayAcks(limit: Int): List<RelayAckRecord> = dao.readyRelayAcks(limit)
    override suspend fun markRelayAckSent(msgId: String, envelopeSha256: String): Int = dao.markRelayAckSent(msgId, envelopeSha256)
}
