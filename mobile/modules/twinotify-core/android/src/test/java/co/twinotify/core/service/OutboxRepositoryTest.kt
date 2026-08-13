package co.twinotify.core.service

import co.twinotify.core.storage.OutboundMessage
import co.twinotify.core.storage.RelayAcceptanceResult
import co.twinotify.core.storage.RelayReceiptResult
import co.twinotify.core.storage.LegacyForwardResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class OutboxRepositoryTest {
    private val digest = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
    private val clock = { 1_000L }

    @Test
    fun normalMessageSurvivesRelayAcceptedUntilPeerReceipt() = kotlinx.coroutines.test.runTest {
        val store = FakeOutboxStore()
        store.rows["m1"] = message("m1", requiresPeerReceipt = true)
        val repo = OutboxRepository(store, clock)
        assertEquals(OutboxTransition.Retained, repo.onRelayAccepted("m1", 1_000))
        assertNotNull(store.rows["m1"])
        assertEquals(OutboxTransition.Deleted, repo.onPeerReceipt("m1", digest, "applied", occurredAt = 2_000))
        assertNull(store.rows["m1"])
    }

    @Test
    fun receiptMessageIsDeletedAfterRelayAccepted() = kotlinx.coroutines.test.runTest {
        val store = FakeOutboxStore()
        store.rows["r1"] = message("r1", requiresPeerReceipt = false)
        assertEquals(OutboxTransition.Deleted, OutboxRepository(store, clock).onRelayAccepted("r1", 1_000))
        assertNull(store.rows["r1"])
    }

    @Test
    fun legacyRowIsDeletedOnlyAfterLegacyForwarded() = kotlinx.coroutines.test.runTest {
        val store = FakeOutboxStore()
        store.rows["legacy"] = message("legacy", requiresPeerReceipt = true).copy(protocolVersion = 1)
        val repo = OutboxRepository(store, clock)
        assertNotNull(store.rows["legacy"])
        assertEquals(OutboxTransition.Deleted, repo.onLegacyForwarded("legacy", 2_000))
        assertNull(store.rows["legacy"])
    }

    private fun message(id: String, requiresPeerReceipt: Boolean) = OutboundMessage(
        msgId = id, canonId = null, sequence = null, eventType = if (requiresPeerReceipt) "notif.post" else "peer.receipt",
        protocolVersion = 2, envelopeJson = "{}", envelopeSha256 = digest, byteSize = 2, createdAt = 1,
        expiresAt = 10_000, relayAcceptedAt = null, attempts = 0, nextAttemptAt = 1,
        state = "NEW", lastError = null, requiresPeerReceipt = requiresPeerReceipt,
    )

    private class FakeOutboxStore : OutboxStore {
        val rows = linkedMapOf<String, OutboundMessage>()
        override suspend fun sendable(now: Long, limit: Int) = rows.values.filter { it.nextAttemptAt <= now }.take(limit)
        override suspend fun markRelaySent(msgId: String, retryAt: Long): Int {
            val row = rows[msgId] ?: return 0
            rows[msgId] = row.copy(attempts = row.attempts + 1, nextAttemptAt = retryAt)
            return 1
        }
        override suspend fun legacyForwarded(msgId: String, forwardedAt: Long) =
            if (rows.remove(msgId) != null) LegacyForwardResult.Deleted else LegacyForwardResult.Missing
        override suspend fun acceptRelay(msgId: String, acceptedAt: Long, retryAt: Long): RelayAcceptanceResult {
            val row = rows[msgId] ?: return RelayAcceptanceResult.Missing
            if (!row.requiresPeerReceipt) { rows.remove(msgId); return RelayAcceptanceResult.DeletedReceipt }
            if (row.state == "ACCEPTED") return RelayAcceptanceResult.AlreadyAccepted
            rows[msgId] = row.copy(state = "ACCEPTED", relayAcceptedAt = acceptedAt, nextAttemptAt = retryAt)
            return RelayAcceptanceResult.Accepted
        }
        override suspend fun applyPeerReceipt(ackedMsgId: String, envelopeSha256: String, status: String, reason: String?, occurredAt: Long): RelayReceiptResult {
            val row = rows[ackedMsgId] ?: return RelayReceiptResult.Missing
            if (row.envelopeSha256 != envelopeSha256) return RelayReceiptResult.Conflict(row.envelopeSha256)
            rows.remove(ackedMsgId)
            return RelayReceiptResult.Deleted
        }
        override suspend fun rejectRelay(msgId: String, reason: String, occurredAt: Long, retryAt: Long) = RelayRejectionResult.Retained
        override suspend fun expireRelay(msgId: String, expiredAt: Long) = RelayReceiptResult.Deleted
        override suspend fun readyRelayAcks(limit: Int) = emptyList<RelayAckRecord>()
        override suspend fun markRelayAckSent(msgId: String, envelopeSha256: String) = 0
    }
}
