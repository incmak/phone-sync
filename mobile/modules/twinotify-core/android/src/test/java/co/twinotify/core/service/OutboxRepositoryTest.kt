package co.twinotify.core.service

import co.twinotify.core.storage.OutboundMessage
import co.twinotify.core.storage.CustodyAcceptanceResult
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
    fun ordinaryMessageSurvivesEitherCustodyRouteUntilPeerReceipt() = kotlinx.coroutines.test.runTest {
        val store = FakeOutboxStore()
        store.rows["m1"] = message("m1", requiresPeerReceipt = true)
        val repo = OutboxRepository(store, clock)
        assertEquals(CustodyResult.Accepted, repo.onCustodyAccepted("m1", CustodyRoute.LAN, 1_000))
        assertNotNull(store.rows["m1"])
        assertEquals(
            OutboxTransition.Deleted,
            repo.onPeerReceipt(
                "m1",
                digest,
                "applied",
                occurredAt = 2_000,
                peerReceiptCreatedAt = 1_750,
            ),
        )
        assertEquals(1_750, store.lastPeerReceiptCreatedAt)
        assertNull(store.rows["m1"])
    }

    @Test
    fun receiptMessageIsDeletedAfterEitherCustodyRouteAcceptsIt() = kotlinx.coroutines.test.runTest {
        CustodyRoute.entries.forEach { route ->
            val store = FakeOutboxStore()
            store.rows["r-$route"] = message("r-$route", requiresPeerReceipt = false)

            assertEquals(
                CustodyResult.DeletedReceipt,
                OutboxRepository(store, clock).onCustodyAccepted("r-$route", route, 1_000),
            )
            assertNull(store.rows["r-$route"])
        }
    }

    @Test
    fun duplicateCustodyAcceptanceIsIdempotentAndPreservesFirstRoute() = kotlinx.coroutines.test.runTest {
        val store = FakeOutboxStore()
        store.rows["m1"] = message("m1", requiresPeerReceipt = true)
        val repo = OutboxRepository(store, clock)

        assertEquals(CustodyResult.Accepted, repo.onCustodyAccepted("m1", CustodyRoute.RELAY, 1_000))
        assertEquals(CustodyResult.AlreadyAccepted, repo.onCustodyAccepted("m1", CustodyRoute.RELAY, 2_000))
        assertEquals(CustodyResult.AlreadyAccepted, repo.onCustodyAccepted("m1", CustodyRoute.LAN, 3_000))
        assertEquals(1_000, store.rows.getValue("m1").custodyAcceptedAt)
        assertEquals("RELAY", store.rows.getValue("m1").custodyRoute)
    }

    @Test
    fun lanFirstThenRelayAcceptanceRecordsRelayCustodyWithoutChangingFirstRoute() = kotlinx.coroutines.test.runTest {
        val store = FakeOutboxStore()
        store.rows["m1"] = message("m1", requiresPeerReceipt = true)
        val repo = OutboxRepository(store, clock)

        assertEquals(OutboxTransition.Retained, repo.onLanAccepted("m1", 1_000))
        assertEquals(OutboxTransition.Retained, repo.onRelayAccepted("m1", 2_000))

        val row = store.rows.getValue("m1")
        assertEquals(1_000, row.custodyAcceptedAt)
        assertEquals("LAN", row.custodyRoute)
        assertEquals("ACCEPTED", row.relayCustodyState)
    }

    @Test
    fun sendableTerminalizesOnlyKnownNoRelayExpiryOnceBeforeSelection() = kotlinx.coroutines.test.runTest {
        val store = FakeOutboxStore()
        store.rows["new-expired"] = message("new-expired", true).copy(expiresAt = 1_000)
        store.rows["lan-expired"] = message("lan-expired", true).copy(
            expiresAt = 999,
            state = "ACCEPTED",
            custodyAcceptedAt = 500,
            custodyRoute = "LAN",
        )
        store.rows["relay-expired"] = message("relay-expired", true).copy(
            expiresAt = 999,
            state = "ACCEPTED",
            custodyAcceptedAt = 500,
            custodyRoute = "RELAY",
            relayCustodyState = "ACCEPTED",
        )
        store.rows["unknown-expired"] = message("unknown-expired", true).copy(
            expiresAt = 999,
            state = "ACCEPTED",
            custodyAcceptedAt = 500,
            custodyRoute = "LAN",
            relayCustodyState = "UNKNOWN",
        )
        store.rows["fresh"] = message("fresh", true).copy(expiresAt = 1_001)
        val repo = OutboxRepository(store, clock)

        assertEquals(
            listOf("relay-expired", "unknown-expired", "fresh"),
            repo.sendable(now = 1_000).map { it.msgId },
        )
        assertEquals(listOf("new-expired", "lan-expired"), store.localExpirations)

        repo.sendable(now = 1_000)
        assertEquals(listOf("new-expired", "lan-expired"), store.localExpirations)
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
        expiresAt = 10_000, custodyAcceptedAt = null, custodyRoute = null, attempts = 0, nextAttemptAt = 1,
        state = "NEW", lastError = null, requiresPeerReceipt = requiresPeerReceipt,
        relayCustodyState = "NONE",
    )

    private class FakeOutboxStore : OutboxStore {
        val rows = linkedMapOf<String, OutboundMessage>()
        val localExpirations = mutableListOf<String>()
        var lastPeerReceiptCreatedAt: Long? = null
        override suspend fun expireLocal(now: Long): Int {
            val expired = rows.values.filter {
                it.expiresAt <= now && it.relayCustodyState == "NONE" &&
                    (it.state == "NEW" || it.state == "ACCEPTED")
            }
            expired.forEach {
                rows.remove(it.msgId)
                localExpirations += it.msgId
            }
            return expired.size
        }
        override suspend fun sendable(now: Long, limit: Int) = rows.values.filter { it.nextAttemptAt <= now }.take(limit)
        override suspend fun markSent(msgId: String, retryAt: Long): Int {
            val row = rows[msgId] ?: return 0
            rows[msgId] = row.copy(attempts = row.attempts + 1, nextAttemptAt = retryAt)
            return 1
        }
        override suspend fun legacyForwarded(msgId: String, forwardedAt: Long) =
            if (rows.remove(msgId) != null) LegacyForwardResult.Deleted else LegacyForwardResult.Missing
        override suspend fun acceptCustody(
            msgId: String,
            route: CustodyRoute,
            acceptedAt: Long,
            retryAt: Long,
        ): CustodyAcceptanceResult {
            val row = rows[msgId] ?: return CustodyAcceptanceResult.Missing
            if (!row.requiresPeerReceipt) { rows.remove(msgId); return CustodyAcceptanceResult.DeletedReceipt }
            if (row.state == "ACCEPTED") {
                if (route == CustodyRoute.RELAY) {
                    rows[msgId] = row.copy(relayCustodyState = "ACCEPTED")
                }
                return CustodyAcceptanceResult.AlreadyAccepted
            }
            rows[msgId] = row.copy(
                state = "ACCEPTED",
                custodyAcceptedAt = acceptedAt,
                custodyRoute = route.name,
                relayCustodyState = if (route == CustodyRoute.RELAY) "ACCEPTED" else "NONE",
                nextAttemptAt = retryAt,
            )
            return CustodyAcceptanceResult.Accepted
        }
        override suspend fun applyPeerReceipt(
            ackedMsgId: String,
            envelopeSha256: String,
            status: String,
            reason: String?,
            occurredAt: Long,
            peerReceiptCreatedAt: Long?,
        ): RelayReceiptResult {
            lastPeerReceiptCreatedAt = peerReceiptCreatedAt
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
