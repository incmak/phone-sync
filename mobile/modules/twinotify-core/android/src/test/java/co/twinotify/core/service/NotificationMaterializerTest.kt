package co.twinotify.core.service

import co.twinotify.core.storage.CanonicalNotificationState
import co.twinotify.core.storage.InboundMessage
import co.twinotify.core.storage.MaterializationResult
import co.twinotify.core.storage.MaterializationRetryDisposition
import co.twinotify.core.storage.MaterializationRetryWriteResult
import co.twinotify.core.storage.OutboundMessage
import co.twinotify.core.protocol.InnerEventV2
import kotlinx.coroutines.runBlocking
import java.util.concurrent.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class NotificationMaterializerTest {
    @Test
    fun permissionBlockedPostStaysPendingWithoutSchedulingRetryWake() = runBlocking {
        val store = FakeStore(canonical(sequence = 2, materialized = 1))
        val scheduled = mutableListOf<Long>()
        val port = object : AndroidNotificationPort {
            override fun postMirror(state: CanonicalNotificationState): Boolean = false
            override fun postMirrorOutcome(state: CanonicalNotificationState): NotificationPostOutcome =
                NotificationPostOutcome.PermissionBlocked
            override fun cancelMirror(localTag: String, localId: Int): Boolean = true
            override fun cancelSource(notificationKey: String): Boolean = true
        }

        val result = NotificationMaterializer(
            store = store,
            port = port,
            retryScheduler = MaterializationRetryScheduler { delayMs, _ -> scheduled += delayMs },
        ).materializePending(nowMs = 1_000L)

        assertEquals(MaterializationSummary(applied = 0, pending = 1, skipped = 0), result)
        assertEquals(1, store.state.materializedSequence)
        assertEquals("post_permission_blocked", store.lastRetryCode)
        assertEquals(MaterializationRetryDisposition.PERMISSION_BLOCKED, store.retryDisposition)
        assertEquals(null, store.retryAt)
        assertTrue(scheduled.isEmpty())
    }

    @Test
    fun permissionBlockedSameSequenceReattemptsOnNextStartupMaterialization() = runBlocking {
        val store = FakeStore(canonical(sequence = 2, materialized = 1))
        var posts = 0
        val port = object : AndroidNotificationPort {
            override fun postMirror(state: CanonicalNotificationState): Boolean = false
            override fun postMirrorOutcome(state: CanonicalNotificationState): NotificationPostOutcome {
                posts += 1
                return if (posts == 1) NotificationPostOutcome.PermissionBlocked else NotificationPostOutcome.Applied
            }
            override fun cancelMirror(localTag: String, localId: Int): Boolean = true
            override fun cancelSource(notificationKey: String): Boolean = true
        }

        val first = NotificationMaterializer(store, port).materializePending(nowMs = 1_000L)
        val second = NotificationMaterializer(store, port).materializePending(nowMs = 2_000L)

        assertEquals(MaterializationSummary(applied = 0, pending = 1, skipped = 0), first)
        assertEquals(MaterializationSummary(applied = 1, pending = 0, skipped = 0), second)
        assertEquals(2, store.state.materializedSequence)
        assertEquals(2, posts)
    }

    @Test
    fun startupSchedulesTheEarliestDurableRetryableDueTime() = runBlocking {
        val store = FakeStore(canonical(sequence = 2, materialized = 2)).apply {
            retryDisposition = MaterializationRetryDisposition.RETRYABLE
            retryAt = 9_000L
        }
        val scheduled = mutableListOf<Long>()

        NotificationMaterializer(
            store = store,
            port = noOpPort(),
            retryScheduler = MaterializationRetryScheduler { delayMs, _ -> scheduled += delayMs },
        ).materializePending(nowMs = 1_000L)

        assertEquals(listOf(8_000L), scheduled)
    }

    @Test
    fun receiptCancellationKeepsIdentityAndDoesNotScheduleRetry() = runBlocking {
        val expected = CancellationException("receipt cancelled")
        val store = FakeStore(canonical(sequence = 2, materialized = 1))
        val scheduled = mutableListOf<Long>()
        val materializer = NotificationMaterializer(
            store = store,
            port = object : AndroidNotificationPort {
                override fun postMirror(state: CanonicalNotificationState): Boolean = true
                override fun cancelMirror(localTag: String, localId: Int): Boolean = true
                override fun cancelSource(notificationKey: String): Boolean = true
            },
            receiptFactory = { _, _ -> throw expected },
            retryScheduler = MaterializationRetryScheduler { delayMs, _ -> scheduled += delayMs },
        )

        val actual = kotlin.test.assertFailsWith<CancellationException> {
            materializer.materializePending(nowMs = 1_000L)
        }

        kotlin.test.assertSame(expected, actual)
        assertTrue(scheduled.isEmpty())
        assertEquals(null, store.retryAt)
    }
    @Test
    fun failedPlatformOperationRemainsPendingAndRestartRetriesSameIdentity() = runBlocking {
        val state = canonical(sequence = 3, materialized = 2)
        val store = FakeStore(state)
        val scheduled = mutableListOf<Long>()
        val retryScheduler = MaterializationRetryScheduler { delayMs, _ -> scheduled += delayMs }
        var receiptFactoryCalls = 0
        val port = object : AndroidNotificationPort {
            var attempts = 0
            var lastIdentity: Pair<Int, String>? = null
            override fun postMirror(state: CanonicalNotificationState): Boolean {
                attempts += 1
                lastIdentity = state.mirrorLocalId!! to state.mirrorLocalTag!!
                return attempts > 1
            }
            override fun cancelMirror(localTag: String, localId: Int): Boolean = true
            override fun cancelSource(notificationKey: String): Boolean = true
        }

        val first = NotificationMaterializer(
            store,
            port,
            receiptFactory = { _, _ -> receiptFactoryCalls += 1; receipt("r1") },
            retryScheduler = retryScheduler,
        )
            .materializePending(nowMs = 1_000L)
        assertEquals(MaterializationSummary(applied = 0, pending = 1, skipped = 0), first)
        assertEquals(2, store.state.materializedSequence)
        assertEquals(0, store.receipts)
        assertEquals(42 to "mirror-stable", port.lastIdentity)
        assertEquals(listOf(5_000L), scheduled)

        val beforeDue = NotificationMaterializer(
            store,
            port,
            receiptFactory = { _, _ -> receiptFactoryCalls += 1; receipt("r1") },
            retryScheduler = retryScheduler,
        ).materializePending(nowMs = 2_000L)
        assertEquals(MaterializationSummary(applied = 0, pending = 0, skipped = 0), beforeDue)

        val second = NotificationMaterializer(
            store,
            port,
            receiptFactory = { _, _ -> receiptFactoryCalls += 1; receipt("r1") },
            retryScheduler = retryScheduler,
        )
            .materializePending(nowMs = 6_000L)
        assertEquals(MaterializationSummary(applied = 1, pending = 0, skipped = 0), second)
        assertEquals(3, store.state.materializedSequence)
        assertEquals(1, store.receipts)
        assertEquals(1, receiptFactoryCalls)
        assertEquals(2, store.receiptPreparations)
        assertEquals(42 to "mirror-stable", port.lastIdentity)
    }

    @Test
    fun disabledSourceCancellationStaysPending() = runBlocking {
        val store = FakeStore(
            canonical(
                sequence = 2,
                materialized = 1,
                origin = "dev-local",
                state = "CANCELLED",
                sourceKey = "source-key",
                mirrorId = null,
                mirrorTag = null,
            ),
        )
        val port = object : AndroidNotificationPort {
            override fun postMirror(state: CanonicalNotificationState): Boolean = true
            override fun cancelMirror(localTag: String, localId: Int): Boolean = true
            override fun cancelSource(notificationKey: String): Boolean = false
        }

        val result = NotificationMaterializer(store, port, localDeviceId = "dev-local").materializePending()

        assertEquals(0, result.applied)
        assertEquals(1, result.pending)
        assertFalse(store.completed)
    }

    @Test
    fun mirrorCancellationUsesPersistedIdentityEvenWhenPostingPermissionWouldBeOff() = runBlocking {
        val store = FakeStore(
            canonical(
                sequence = 2,
                materialized = 1,
                state = "CANCELLED",
                mirrorId = 42,
                mirrorTag = "mirror-stable",
            ),
        )
        var cancelCalls = 0
        val port = object : AndroidNotificationPort {
            override fun postMirror(state: CanonicalNotificationState): Boolean = false
            override fun cancelMirror(localTag: String, localId: Int): Boolean {
                cancelCalls += 1
                return localTag == "mirror-stable" && localId == 42
            }
            override fun cancelSource(notificationKey: String): Boolean = false
        }

        val result = NotificationMaterializer(
            store,
            port,
            localDeviceId = "dev-local",
            retryScheduler = MaterializationRetryScheduler { _, _ -> },
        ).materializePending()

        assertEquals(1, result.applied)
        assertEquals(1, cancelCalls)
    }

    @Test
    fun remoteCallUsesDedicatedActionFreeMirrorPort() = runBlocking {
        val store = FakeStore(
            canonical(
                sequence = 1,
                materialized = 0,
                origin = "dev-peer",
                mirrorId = 73,
                mirrorTag = "call-mirror",
            ).copy(
                canonId = "call:11111111-1111-4111-8111-111111111111",
                desiredPayloadJson = "{\"call_session_id\":\"11111111-1111-4111-8111-111111111111\",\"state\":\"ringing\",\"direction\":\"incoming\"}",
            ),
        )
        var callPosts = 0
        var genericPosts = 0
        val result = NotificationMaterializer(
            store,
            object : AndroidNotificationPort {
                override fun postMirror(state: CanonicalNotificationState): Boolean {
                    genericPosts += 1
                    return true
                }
                override fun postCallMirror(state: CanonicalNotificationState): Boolean {
                    callPosts += 1
                    return true
                }
                override fun cancelMirror(localTag: String, localId: Int): Boolean = true
                override fun cancelSource(notificationKey: String): Boolean = true
            },
            localDeviceId = "dev-local",
        ).materializePending()

        assertEquals(1, result.applied)
        assertEquals(1, callPosts)
        assertEquals(0, genericPosts)
    }

    @Test
    fun pairedPeerMirrorSwipeCancelsSourceOnOwnerDevice() = runBlocking {
        val active = canonical(
            sequence = 4,
            materialized = 4,
            origin = "dev-owner",
            sourceKey = "pkg|42|tag",
            mirrorId = null,
            mirrorTag = null,
        )
        val incoming = InnerEventV2(
            msgId = "cancel-from-peer",
            originDevice = "dev-peer",
            type = "notif.cancel",
            canonId = active.canonId,
            sequence = 5,
            createdAt = 5_000,
            expiresAt = 6_000,
            payloadJson = "{}",
        )
        val authorized = requireNotNull(
            NotificationStateReducer.authorizePeerCancel(active, incoming, "dev-peer"),
        )
        val cancelled = assertIs<Reduction.Apply>(
            NotificationStateReducer.reduce(
                active,
                authorized,
                localDeviceId = "dev-owner",
                allocator = LocalIdAllocator { 99 },
            ),
        ).state
        val store = FakeStore(cancelled)
        var sourceCancel: String? = null
        val result = NotificationMaterializer(
            store,
            object : AndroidNotificationPort {
                override fun postMirror(state: CanonicalNotificationState): Boolean = false
                override fun cancelMirror(localTag: String, localId: Int): Boolean = false
                override fun cancelSource(notificationKey: String): Boolean {
                    sourceCancel = notificationKey
                    return true
                }
            },
            localDeviceId = "dev-owner",
            retryScheduler = MaterializationRetryScheduler { _, _ -> },
        ).materializePending()

        assertEquals(1, result.applied)
        assertEquals("pkg|42|tag", sourceCancel)
    }

    private class FakeStore(initial: CanonicalNotificationState) : MaterializationStore {
        var state = initial
        var completed = false
        var receipts = 0
        var preparedReceipt: OutboundMessage? = null
        var retryAt: Long? = null
        var lastRetryCode: String? = null
        var retryDisposition: MaterializationRetryDisposition? = null
        override suspend fun pendingMaterialization(nowMs: Long): List<CanonicalNotificationState> =
            if (state.latestSequence > state.materializedSequence && (
                retryDisposition == MaterializationRetryDisposition.PERMISSION_BLOCKED ||
                    retryAt == null || retryAt!! <= nowMs
                )
            ) {
                listOf(state)
            } else emptyList()

        override suspend fun recordRetry(canonId: String, nextAttemptAt: Long, lastError: String?) {
            retryAt = nextAttemptAt
            lastRetryCode = lastError
        }

        override suspend fun recordMaterializationRetry(
            canonId: String,
            sequence: Long,
            nowMs: Long,
            disposition: MaterializationRetryDisposition,
            lastError: String,
        ): MaterializationRetryWriteResult {
            retryDisposition = disposition
            lastRetryCode = lastError
            return if (disposition == MaterializationRetryDisposition.PERMISSION_BLOCKED) {
                retryAt = null
                MaterializationRetryWriteResult.PermissionBlocked
            } else {
                val dueAt = nowMs + 5_000L
                retryAt = dueAt
                MaterializationRetryWriteResult.RetryableScheduled(dueAt)
            }
        }

        override suspend fun earliestRetryableMaterializationAt(): Long? =
            if (retryDisposition == MaterializationRetryDisposition.RETRYABLE) retryAt else null

        override suspend fun clearRetry(canonId: String) {
            retryAt = null
            retryDisposition = null
        }

        override suspend fun pendingInbound(canonId: String, sequence: Long): List<InboundMessage> =
            listOf(
                InboundMessage(
                    msgId = "inbound-1",
                    originDevice = "dev-peer",
                    envelopeSha256 = "a".repeat(64),
                    eventType = "notif.post",
                    canonId = canonId,
                    sequence = sequence,
                    outcome = "PENDING_PLATFORM",
                    committedAt = 1,
                    appliedAt = null,
                    receiptMsgId = preparedReceiptId,
                    relayAckState = "NONE",
                ),
            )

        var preparedReceiptId: String? = null
        var receiptPreparations = 0

        override suspend fun prepareReceipt(
            canonId: String,
            sequence: Long,
            candidate: OutboundMessage?,
        ): co.twinotify.core.storage.MaterializationReceiptResult {
            receiptPreparations += 1
            val receipt = candidate ?: preparedReceipt
                ?: return co.twinotify.core.storage.MaterializationReceiptResult.NotNeeded
            preparedReceiptId = receipt.msgId
            preparedReceipt = receipt
            return co.twinotify.core.storage.MaterializationReceiptResult.Prepared(receipt)
        }

        override suspend fun completeMaterialization(
            canonId: String,
            sequence: Long,
            appliedAt: Long,
            receipt: OutboundMessage?,
        ): MaterializationResult {
            if (sequence <= state.materializedSequence) return MaterializationResult.AlreadyCompleted
            state = state.copy(materializedSequence = sequence)
            completed = true
            if (receipt != null) receipts += 1
            return MaterializationResult.Completed
        }
    }

    private fun canonical(
        sequence: Long,
        materialized: Long,
        origin: String = "dev-peer",
        state: String = "ACTIVE",
        sourceKey: String? = null,
        mirrorId: Int? = 42,
        mirrorTag: String? = "mirror-stable",
    ) = CanonicalNotificationState(
        canonId = "dev-peer:pkg:1:",
        originDevice = origin,
        latestSequence = sequence,
        state = state,
        desiredPayloadJson = if (state == "ACTIVE") "{\"title\":\"hello\"}" else null,
        materializedSequence = materialized,
        sourceNotificationKey = sourceKey,
        mirrorLocalId = mirrorId,
        mirrorLocalTag = mirrorTag,
        peerCancelPending = false,
        updatedAt = 1_000,
    )

    private fun receipt(msgId: String) = OutboundMessage(
        msgId = msgId,
        canonId = null,
        sequence = null,
        eventType = "peer.receipt",
        protocolVersion = 2,
        envelopeJson = "{}",
        envelopeSha256 = "b".repeat(64),
        byteSize = 2,
        createdAt = 1,
        expiresAt = 2_000,
        custodyAcceptedAt = null,
        custodyRoute = null,
        attempts = 0,
        nextAttemptAt = 1,
        state = "NEW",
        lastError = null,
        requiresPeerReceipt = false,
    )

    private fun noOpPort() = object : AndroidNotificationPort {
        override fun postMirror(state: CanonicalNotificationState): Boolean = true
        override fun cancelMirror(localTag: String, localId: Int): Boolean = true
        override fun cancelSource(notificationKey: String): Boolean = true
    }

}
