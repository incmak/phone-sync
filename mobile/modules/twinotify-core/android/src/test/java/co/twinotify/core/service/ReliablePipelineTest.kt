package co.twinotify.core.service

import co.twinotify.core.protocol.EncryptedEnvelope
import co.twinotify.core.protocol.InnerEventV2
import co.twinotify.core.protocol.ProtocolJson
import co.twinotify.core.storage.CanonicalNotificationState
import co.twinotify.core.storage.InboundMessage
import co.twinotify.core.storage.LegacyForwardResult
import co.twinotify.core.storage.MaterializationReceiptResult
import co.twinotify.core.storage.MaterializationResult
import co.twinotify.core.storage.MaterializationRetry
import co.twinotify.core.storage.OutboundMessage
import co.twinotify.core.storage.CustodyAcceptanceResult
import co.twinotify.core.storage.RelayReceiptResult
import java.security.MessageDigest
import java.util.Base64
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.json.JSONObject

/**
 * Deterministic end-to-end proof for the durable notification state path.  The fake store and
 * platform are deliberately narrow seams: codecs, reducer, OutboxRepository, and
 * NotificationMaterializer are the production implementations under test.
 */
class ReliablePipelineTest {
    @Test
    fun reliableState_survivesCustodyDuplicatesCrashAndLateEvents() = runBlocking {
        val store = PipelineStore()
        val outbox = OutboxRepository(store, clock = { store.now })
        val port = RecordingPort().also { it.failPosts = 1 }
        val retryScheduler = MaterializationRetryScheduler { _, _ -> }

        val post = event("post-1", "notif.post", 1, payload = "{\"title\":\"hello\"}")
        val postRow = row(post, requiresPeerReceipt = true)
        store.outbound[post.msgId] = postRow

        // Capture and relay custody are durable before the peer has acknowledged the event.
        assertEquals(listOf(post.msgId), outbox.sendable(now = 1_100).map { it.msgId })
        assertEquals(
            CustodyResult.Accepted,
            outbox.onCustodyAccepted(post.msgId, CustodyRoute.RELAY, acceptedAt = 1_100),
        )
        assertNotNull(store.outbound[post.msgId], "relay acceptance must not delete a normal row")

        // The real codec boundary is exercised before the receiver reducer sees the event.
        val decodedPost = ProtocolJson.decodeInner(ProtocolJson.encodeInner(post))
        assertEquals(post, decodedPost)
        assertEquals(
            RelayFrame.Put(row(post, true).envelopeJson),
            RelayFrameCodec.decode(ReliableRelayFrames.put(row(post, true).envelopeJson)),
        )

        store.deliver(decodedPost, postRow.envelopeSha256)
        store.deliver(decodedPost, postRow.envelopeSha256) // duplicate delivery is idempotent
        assertEquals(1, store.inbound.size)

        // The first Android call fails.  Receipt identity is staged, desired state remains pending,
        // and a fresh materializer can retry the same mirror identity after process restart.
        val first = NotificationMaterializer(
            store = store,
            port = port,
            receiptFactory = { _, _ -> receiptFor(post, postRow.envelopeSha256) },
            retryScheduler = retryScheduler,
        ).materializePending(nowMs = 1_200)
        assertEquals(MaterializationSummary(applied = 0, pending = 1, skipped = 0), first)
        assertEquals(0, store.state(post.canonId!!)?.materializedSequence)
        val receipt = assertNotNull(store.outbound.values.singleOrNull { it.eventType == "peer.receipt" })

        val afterRestart = NotificationMaterializer(
            store = store,
            port = port,
            receiptFactory = { _, _ -> error("restart must reuse the staged receipt") },
            retryScheduler = retryScheduler,
        ).materializePending(nowMs = 6_200)
        assertEquals(MaterializationSummary(applied = 1, pending = 0, skipped = 0), afterRestart)
        assertEquals(1, store.state(post.canonId!!)?.materializedSequence)
        assertEquals(1, store.inbound.values.count { it.outcome == "APPLIED" })

        // The sender receipt may remove the original before the receipt itself receives relay ACK.
        assertEquals(
            OutboxTransition.Deleted,
            outbox.onPeerReceipt(post.msgId, postRow.envelopeSha256, "applied", occurredAt = 6_300),
        )
        assertNull(store.outbound[post.msgId])
        assertNotNull(store.outbound[receipt.msgId])
        assertEquals(
            CustodyResult.DeletedReceipt,
            outbox.onCustodyAccepted(receipt.msgId, CustodyRoute.RELAY, acceptedAt = 6_400),
        )
        assertTrue(store.readyAcks.contains(receipt.msgId))

        val update = event("update-2", "notif.update", 2, payload = "{\"title\":\"updated\"}")
        val updateRow = row(update, requiresPeerReceipt = true)
        store.outbound[update.msgId] = updateRow
        store.deliver(ProtocolJson.decodeInner(ProtocolJson.encodeInner(update)), updateRow.envelopeSha256)
        val updateResult = NotificationMaterializer(
            store = store,
            port = port,
            receiptFactory = { _, _ -> receiptFor(update, updateRow.envelopeSha256) },
            retryScheduler = retryScheduler,
        ).materializePending(nowMs = 7_000)
        assertEquals(1, updateResult.applied)
        assertEquals(2, store.state(update.canonId!!)?.materializedSequence)
        val updateReceipt = assertNotNull(store.outbound.values.singleOrNull { it.eventType == "peer.receipt" })
        assertEquals(OutboxTransition.Deleted, outbox.onPeerReceipt(update.msgId, updateRow.envelopeSha256, "applied", occurredAt = 7_100))
        assertEquals(
            CustodyResult.DeletedReceipt,
            outbox.onCustodyAccepted(updateReceipt.msgId, CustodyRoute.RELAY, acceptedAt = 7_101),
        )

        val cancel = event("cancel-3", "notif.cancel", 3, payload = "{\"reason\":\"dismissed\"}")
        val cancelRow = row(cancel, requiresPeerReceipt = true)
        store.outbound[cancel.msgId] = cancelRow
        store.deliver(ProtocolJson.decodeInner(ProtocolJson.encodeInner(cancel)), cancelRow.envelopeSha256)
        val cancelResult = NotificationMaterializer(
            store = store,
            port = port,
            receiptFactory = { _, _ -> receiptFor(cancel, cancelRow.envelopeSha256) },
            retryScheduler = retryScheduler,
        ).materializePending(nowMs = 8_000)
        assertEquals(1, cancelResult.applied)
        assertEquals("CANCELLED", store.state(cancel.canonId!!)?.state)
        val cancelReceipt = assertNotNull(store.outbound.values.singleOrNull { it.eventType == "peer.receipt" })
        assertEquals(OutboxTransition.Deleted, outbox.onPeerReceipt(cancel.msgId, cancelRow.envelopeSha256, "applied", occurredAt = 8_100))
        assertEquals(
            CustodyResult.DeletedReceipt,
            outbox.onCustodyAccepted(cancelReceipt.msgId, CustodyRoute.RELAY, acceptedAt = 8_101),
        )

        // A delayed sequence-2 update is journaled as stale and cannot resurrect the cancelled item.
        val lateUpdate = update.copy(msgId = UUID.nameUUIDFromBytes("late-update-2".encodeToByteArray()).toString())
        store.deliver(lateUpdate, sha256(ProtocolJson.encodeInner(lateUpdate)))
        assertEquals("CANCELLED", store.state(cancel.canonId!!)?.state)
        assertEquals(3, store.state(cancel.canonId!!)?.latestSequence)
        assertEquals("STALE", store.inbound[lateUpdate.msgId]?.outcome)

        assertEquals(1, port.posts.distinct().size, "one stable visible mirror identity")
        assertEquals(1, port.cancellations.size, "one final cancellation")
        assertEquals(port.posts.first(), port.cancellations.single())
        assertTrue(store.outbound.isEmpty(), "all normal rows require receipts; no silent deletion")
    }

    private class RecordingPort : AndroidNotificationPort {
        var failPosts = 0
        val posts = mutableListOf<Pair<String, Int>>()
        val cancellations = mutableListOf<Pair<String, Int>>()

        override fun postMirror(state: CanonicalNotificationState): Boolean {
            posts += state.mirrorLocalTag!! to state.mirrorLocalId!!
            if (failPosts > 0) {
                failPosts -= 1
                return false
            }
            return true
        }

        override fun cancelMirror(localTag: String, localId: Int): Boolean {
            cancellations += localTag to localId
            return true
        }

        override fun cancelSource(notificationKey: String): Boolean = true
    }

    private class PipelineStore : OutboxStore, MaterializationStore {
        val outbound = linkedMapOf<String, OutboundMessage>()
        val inbound = linkedMapOf<String, InboundMessage>()
        private val states = linkedMapOf<String, CanonicalNotificationState>()
        private val retries = linkedMapOf<String, MaterializationRetry>()
        val readyAcks = mutableSetOf<String>()
        private val terminal = mutableSetOf<String>()
        var now: Long = 1_000
        private var nextMirrorId = 41

        fun state(canonId: String): CanonicalNotificationState? = states[canonId]

        fun deliver(event: InnerEventV2, digest: String) {
            if (inbound.containsKey(event.msgId)) return
            val canonId = requireNotNull(event.canonId)
            val current = states[canonId]
            val reduction = NotificationStateReducer.reduce(
                current = current,
                event = event,
                localDeviceId = "dev-local",
                allocator = LocalIdAllocator { ++nextMirrorId },
                updatedAt = event.createdAt,
            )
            val inboundRow = InboundMessage(
                msgId = event.msgId,
                originDevice = event.originDevice,
                envelopeSha256 = digest,
                eventType = event.type,
                canonId = event.canonId,
                sequence = event.sequence,
                outcome = if (reduction is Reduction.Stale) "STALE" else "PENDING_PLATFORM",
                committedAt = event.createdAt,
                appliedAt = null,
                receiptMsgId = null,
                relayAckState = "NONE",
            )
            inbound[event.msgId] = inboundRow
            if (reduction is Reduction.Apply) states[canonId] = reduction.state
        }

        override suspend fun sendable(now: Long, limit: Int): List<OutboundMessage> = outbound.values
            .filter { it.state in setOf("NEW", "ACCEPTED") && it.nextAttemptAt <= now }
            .take(limit)

        override suspend fun markSent(msgId: String, retryAt: Long): Int {
            val row = outbound[msgId] ?: return 0
            outbound[msgId] = row.copy(attempts = row.attempts + 1, nextAttemptAt = retryAt)
            return 1
        }

        override suspend fun legacyForwarded(msgId: String, forwardedAt: Long): LegacyForwardResult =
            if (outbound.remove(msgId) != null) LegacyForwardResult.Deleted else LegacyForwardResult.Missing

        override suspend fun acceptCustody(
            msgId: String,
            route: CustodyRoute,
            acceptedAt: Long,
            retryAt: Long,
        ): CustodyAcceptanceResult {
            val row = outbound[msgId] ?: return CustodyAcceptanceResult.Missing
            if (!row.requiresPeerReceipt) {
                outbound.remove(msgId)
                readyAcks += msgId
                return CustodyAcceptanceResult.DeletedReceipt
            }
            if (row.state == "ACCEPTED") return CustodyAcceptanceResult.AlreadyAccepted
            outbound[msgId] = row.copy(
                state = "ACCEPTED",
                custodyAcceptedAt = acceptedAt,
                custodyRoute = route.name,
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
        ): RelayReceiptResult {
            val row = outbound[ackedMsgId] ?: return if (ackedMsgId in terminal) {
                RelayReceiptResult.AlreadyTerminal
            } else RelayReceiptResult.Missing
            if (row.envelopeSha256 != envelopeSha256) return RelayReceiptResult.Conflict(row.envelopeSha256)
            outbound.remove(ackedMsgId)
            terminal += ackedMsgId
            return RelayReceiptResult.Deleted
        }

        override suspend fun rejectRelay(msgId: String, reason: String, occurredAt: Long, retryAt: Long): RelayRejectionResult =
            RelayRejectionResult.Retained

        override suspend fun expireRelay(msgId: String, expiredAt: Long): RelayReceiptResult =
            if (outbound.remove(msgId) != null) RelayReceiptResult.Deleted else RelayReceiptResult.Missing

        override suspend fun readyRelayAcks(limit: Int): List<RelayAckRecord> = readyAcks.take(limit).map {
            RelayAckRecord(it, "b".repeat(64))
        }

        override suspend fun markRelayAckSent(msgId: String, envelopeSha256: String): Int =
            if (readyAcks.remove(msgId)) 1 else 0

        override suspend fun pendingMaterialization(nowMs: Long): List<CanonicalNotificationState> = states.values.filter {
            it.latestSequence > it.materializedSequence && (retries[it.canonId]?.nextAttemptAt ?: 0L) <= nowMs
        }

        override suspend fun pendingInbound(canonId: String, sequence: Long): List<InboundMessage> = inbound.values.filter {
            it.canonId == canonId && it.sequence == sequence && it.outcome == "PENDING_PLATFORM"
        }

        override suspend fun recordRetry(canonId: String, nextAttemptAt: Long, lastError: String?) {
            retries[canonId] = MaterializationRetry(
                canonId = canonId,
                sequence = states[canonId]?.latestSequence ?: 0L,
                nextAttemptAt = nextAttemptAt,
                attempts = (retries[canonId]?.attempts ?: 0) + 1,
                disposition = co.twinotify.core.storage.MaterializationRetryDisposition.RETRYABLE,
                lastError = lastError,
            )
        }

        override suspend fun clearRetry(canonId: String) {
            retries.remove(canonId)
        }

        override suspend fun prepareReceipt(
            canonId: String,
            sequence: Long,
            candidate: OutboundMessage?,
        ): MaterializationReceiptResult {
            val pending = pendingInbound(canonId, sequence)
            if (pending.isEmpty()) return MaterializationReceiptResult.NotNeeded
            val existingId = pending.mapNotNull { it.receiptMsgId }.distinct().singleOrNull()
            if (existingId != null) {
                return outbound[existingId]?.let(MaterializationReceiptResult::Prepared)
                    ?: MaterializationReceiptResult.Conflict("missing staged receipt")
            }
            val receipt = candidate ?: return MaterializationReceiptResult.Unavailable
            outbound[receipt.msgId] = receipt.copy(state = "PENDING_PLATFORM")
            pending.forEach { inbound[it.msgId] = it.copy(receiptMsgId = receipt.msgId) }
            return MaterializationReceiptResult.Prepared(receipt.copy(state = "PENDING_PLATFORM"))
        }

        override suspend fun completeMaterialization(
            canonId: String,
            sequence: Long,
            appliedAt: Long,
            receipt: OutboundMessage?,
        ): MaterializationResult {
            val state = states[canonId] ?: return MaterializationResult.Missing
            if (sequence <= state.materializedSequence) return MaterializationResult.AlreadyCompleted
            if (sequence < state.latestSequence) return MaterializationResult.Superseded
            states[canonId] = state.copy(materializedSequence = sequence, updatedAt = appliedAt)
            inbound.values.filter { it.canonId == canonId && it.sequence == sequence && it.outcome == "PENDING_PLATFORM" }
                .forEach { inbound[it.msgId] = it.copy(outcome = "APPLIED", appliedAt = appliedAt, receiptMsgId = receipt?.msgId) }
            receipt?.let { outbound[it.msgId] = it.copy(state = "NEW") }
            return MaterializationResult.Completed
        }
    }

    private fun event(msgId: String, type: String, sequence: Long, payload: String) = InnerEventV2(
        msgId = UUID.nameUUIDFromBytes(msgId.encodeToByteArray()).toString(),
        originDevice = "dev-peer",
        type = type,
        canonId = "dev-peer:chat:42:",
        sequence = sequence,
        createdAt = 1_000L + sequence,
        expiresAt = 100_000L,
        payloadJson = payload,
    )

    private fun row(event: InnerEventV2, requiresPeerReceipt: Boolean): OutboundMessage {
        val envelope = ProtocolJson.encodeEnvelope(
            EncryptedEnvelope(
                version = 2,
                msgId = event.msgId,
                originDevice = event.originDevice,
                createdAt = event.createdAt,
                nonceB64 = Base64.getEncoder().encodeToString(ByteArray(24)),
                ciphertextB64 = Base64.getEncoder().encodeToString(ProtocolJson.encodeInner(event).encodeToByteArray()),
            ),
        )
        return OutboundMessage(
            msgId = event.msgId,
            canonId = event.canonId,
            sequence = event.sequence,
            eventType = event.type,
            protocolVersion = 2,
            envelopeJson = envelope,
            envelopeSha256 = sha256(envelope),
            byteSize = envelope.encodeToByteArray().size.toLong(),
            createdAt = event.createdAt,
            expiresAt = event.expiresAt,
            custodyAcceptedAt = null,
            custodyRoute = null,
            attempts = 0,
            nextAttemptAt = event.createdAt,
            state = "NEW",
            lastError = null,
            requiresPeerReceipt = requiresPeerReceipt,
        )
    }

    private fun receiptFor(event: InnerEventV2, digest: String) = row(
        event.copy(
            msgId = UUID.nameUUIDFromBytes("receipt-${event.msgId}".encodeToByteArray()).toString(),
            type = "peer.receipt",
            canonId = null,
            sequence = null,
            payloadJson = JSONObject()
                .put("acked_msg_id", event.msgId)
                .put("envelope_sha256", digest)
                .put("status", "applied")
                .toString(),
        ),
        requiresPeerReceipt = false,
    )

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.encodeToByteArray())
        .joinToString("") { "%02x".format(it) }
}
