package co.twinotify.core.service

import co.twinotify.core.storage.ActivityEvent
import co.twinotify.core.storage.CustodyAcceptanceResult
import co.twinotify.core.storage.LegacyForwardResult
import co.twinotify.core.storage.OutboundMessage
import co.twinotify.core.storage.ReliableDeliveryDao
import co.twinotify.core.storage.RelayReceiptResult

/** A narrow store seam keeps custody transitions deterministic and easy to test without Room. */
interface OutboxStore {
    suspend fun expireLocal(now: Long): Int = 0
    suspend fun sendable(now: Long, limit: Int): List<OutboundMessage>
    suspend fun markSent(msgId: String, retryAt: Long): Int
    suspend fun legacyForwarded(msgId: String, forwardedAt: Long): LegacyForwardResult
    suspend fun acceptCustody(
        msgId: String,
        route: CustodyRoute,
        acceptedAt: Long,
        retryAt: Long,
    ): CustodyAcceptanceResult
    suspend fun applyPeerReceipt(
        ackedMsgId: String,
        envelopeSha256: String,
        status: String,
        reason: String?,
        occurredAt: Long,
        peerReceiptCreatedAt: Long?,
    ): RelayReceiptResult
    suspend fun rejectRelay(msgId: String, reason: String, occurredAt: Long, retryAt: Long): RelayRejectionResult
    suspend fun expireRelay(msgId: String, expiredAt: Long): RelayReceiptResult
    suspend fun readyRelayAcks(limit: Int): List<RelayAckRecord>
    suspend fun markRelayAckSent(msgId: String, envelopeSha256: String): Int
}

data class RelayAckRecord(val msgId: String, val envelopeSha256: String)

/** String-backed in Room (`custodyRoute` stores `name`); adding a value needs no migration. */
enum class CustodyRoute { LAN, BLUETOOTH, RELAY }

sealed interface CustodyResult {
    data object Missing : CustodyResult
    data object DeletedReceipt : CustodyResult
    data object Accepted : CustodyResult
    data object AlreadyAccepted : CustodyResult
}

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
 * Durable outbox state machine. Route acceptance is custody only: normal rows survive it until a
 * matching authenticated peer receipt. Receipt rows are the one exception and are removed once
 * a route has durably accepted them, preventing receipt recursion.
 */
class OutboxRepository(
    private val store: OutboxStore,
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val retryPolicy: RetryPolicy = RetryPolicy(),
) {
    suspend fun sendable(limit: Int = 32, now: Long = clock()): List<OutboundMessage> {
        store.expireLocal(now)
        return store.sendable(now, limit.coerceIn(1, 32))
    }

    suspend fun markSent(msgId: String, attempt: Int, now: Long = clock()): Boolean {
        val retryAt = now + retryPolicy.delay(attempt)
        return store.markSent(msgId, retryAt) == 1
    }

    suspend fun onLegacyForwarded(msgId: String, forwardedAt: Long = clock()): OutboxTransition =
        when (store.legacyForwarded(msgId, forwardedAt)) {
            LegacyForwardResult.Missing -> OutboxTransition.Missing
            LegacyForwardResult.Deleted -> OutboxTransition.Deleted
            LegacyForwardResult.AlreadyTerminal -> OutboxTransition.AlreadyTerminal
        }

    suspend fun onCustodyAccepted(
        msgId: String,
        route: CustodyRoute,
        acceptedAt: Long = clock(),
    ): CustodyResult {
        val retryAt = acceptedAt + retryPolicy.delay(0)
        return when (store.acceptCustody(msgId, route, acceptedAt, retryAt)) {
            CustodyAcceptanceResult.Missing -> CustodyResult.Missing
            CustodyAcceptanceResult.DeletedReceipt -> CustodyResult.DeletedReceipt
            CustodyAcceptanceResult.Accepted -> CustodyResult.Accepted
            CustodyAcceptanceResult.AlreadyAccepted -> CustodyResult.AlreadyAccepted
        }
    }

    /** Relay protocol adapter boundary; durable custody remains route-neutral below this method. */
    suspend fun onRelayAccepted(msgId: String, acceptedAt: Long = clock()): OutboxTransition =
        when (onCustodyAccepted(msgId, CustodyRoute.RELAY, acceptedAt)) {
            CustodyResult.Missing -> OutboxTransition.Missing
            CustodyResult.DeletedReceipt -> OutboxTransition.Deleted
            CustodyResult.Accepted,
            CustodyResult.AlreadyAccepted,
            -> OutboxTransition.Retained
        }

    /**
     * Direct-route adapter boundary shared by every direct transport; durable custody stays
     * route-neutral below this method. [route] names the granted direct route and must not
     * be the relay, whose acceptance carries different semantics via [onRelayAccepted].
     */
    suspend fun onDirectAccepted(
        msgId: String,
        route: CustodyRoute,
        acceptedAt: Long = clock(),
    ): OutboxTransition {
        require(route == CustodyRoute.LAN || route == CustodyRoute.BLUETOOTH) {
            "direct acceptance requires a direct custody route"
        }
        return when (onCustodyAccepted(msgId, route, acceptedAt)) {
            CustodyResult.Missing -> OutboxTransition.Missing
            CustodyResult.DeletedReceipt -> OutboxTransition.Deleted
            CustodyResult.Accepted,
            CustodyResult.AlreadyAccepted,
            -> OutboxTransition.Retained
        }
    }

    suspend fun onPeerReceipt(
        ackedMsgId: String,
        envelopeSha256: String,
        status: String,
        reason: String? = null,
        occurredAt: Long = clock(),
        peerReceiptCreatedAt: Long? = null,
    ): OutboxTransition = when (
        val result = store.applyPeerReceipt(
            ackedMsgId,
            envelopeSha256,
            status,
            reason,
            occurredAt,
            peerReceiptCreatedAt,
        )
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
    override suspend fun expireLocal(now: Long): Int = dao.expireLocal(now)
    override suspend fun sendable(now: Long, limit: Int): List<OutboundMessage> = dao.sendable(now, limit)
    override suspend fun markSent(msgId: String, retryAt: Long): Int = dao.markSent(msgId, retryAt)
    override suspend fun legacyForwarded(msgId: String, forwardedAt: Long): LegacyForwardResult = dao.markLegacyForwarded(msgId, forwardedAt)
    override suspend fun acceptCustody(
        msgId: String,
        route: CustodyRoute,
        acceptedAt: Long,
        retryAt: Long,
    ): CustodyAcceptanceResult = dao.acceptCustody(msgId, route.name, acceptedAt, retryAt)
    override suspend fun applyPeerReceipt(
        ackedMsgId: String,
        envelopeSha256: String,
        status: String,
        reason: String?,
        occurredAt: Long,
        peerReceiptCreatedAt: Long?,
    ): RelayReceiptResult = dao.applyPeerReceipt(
        ackedMsgId,
        envelopeSha256,
        status,
        reason,
        occurredAt,
        peerReceiptCreatedAt,
    )
    override suspend fun rejectRelay(msgId: String, reason: String, occurredAt: Long, retryAt: Long): RelayRejectionResult =
        dao.rejectRelay(msgId, reason, occurredAt, retryAt)
    override suspend fun expireRelay(msgId: String, expiredAt: Long): RelayReceiptResult = dao.expireRelay(msgId, expiredAt)
    override suspend fun readyRelayAcks(limit: Int): List<RelayAckRecord> = dao.readyRelayAcks(limit)
    override suspend fun markRelayAckSent(msgId: String, envelopeSha256: String): Int = dao.markRelayAckSent(msgId, envelopeSha256)
}
