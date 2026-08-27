package co.twinotify.core.service

import android.content.Context
import android.content.ContextWrapper
import java.util.concurrent.CancellationException
import java.util.Base64
import co.twinotify.core.protocol.EncryptedEnvelope
import co.twinotify.core.protocol.EnvelopeAuthenticator
import co.twinotify.core.protocol.InnerEventV2
import co.twinotify.core.protocol.PayloadDecryptor
import co.twinotify.core.protocol.ProtocolJson
import co.twinotify.core.storage.CanonicalNotificationState
import co.twinotify.core.storage.InboundMessage
import co.twinotify.core.storage.SnapshotBeginResult
import co.twinotify.core.storage.SnapshotCommitResult
import co.twinotify.core.storage.SnapshotStage
import co.twinotify.core.storage.SnapshotStageResult
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertIs

class InboundDispatcherControlTest {
    @Test
    fun permanentControlValidationFailureBecomesBoundedRejection() = runTest {
        val result = processAuthenticatedControl("snapshot_invalid") {
            throw IllegalArgumentException("private snapshot ID")
        }

        assertEquals(DirectControlProcessingResult.Rejected("snapshot_invalid"), result)
    }

    @Test
    fun malformedAuthenticatedControlJsonBecomesBoundedRejection() = runTest {
        val result = processAuthenticatedControl("snapshot_invalid") {
            throw org.json.JSONException("private payload field")
        }

        assertEquals(DirectControlProcessingResult.Rejected("snapshot_invalid"), result)
    }

    @Test
    fun malformedAuthenticatedSnapshotEnvelopesRejectWithoutAckJournalCustody() = runTest {
        val peerDeviceId = "peer-device"
        val events = listOf(
            malformedSnapshotEvent(
                msgId = "11111111-1111-4111-8111-111111111111",
                type = "state.snapshot.begin",
            ),
            malformedSnapshotEvent(
                msgId = "22222222-2222-4222-8222-222222222222",
                type = "state.snapshot.item",
                canonId = "peer-device:chat:42:",
                sequence = 1,
            ),
            malformedSnapshotEvent(
                msgId = "33333333-3333-4333-8333-333333333333",
                type = "state.snapshot.end",
            ),
        )
        val plaintextByMsgId = events.associate { it.msgId to ProtocolJson.encodeInner(it).encodeToByteArray() }
        val authenticator = EnvelopeAuthenticator(PayloadDecryptor { envelope ->
            requireNotNull(plaintextByMsgId[envelope.msgId])
        }, peerDeviceId, clock = { 2_000 })
        val context = object : ContextWrapper(null) {
            override fun getApplicationContext(): Context = this
        }
        var journalInvocations = 0
        val journaledRows = mutableListOf<InboundMessage>()
        val dispatcher = InboundDispatcher(
            ctx = context,
            snapshotCoordinator = SnapshotCoordinator(NoAccessSnapshotStore),
            onAuthenticatedEvent = {},
            authenticatedV2Opener = authenticator::open,
            directControlJournal = DirectControlJournal { row, process ->
                journalInvocations += 1
                when (val result = process()) {
                    DirectControlProcessingResult.Applied -> {
                        journaledRows += row
                        DirectControlCommitResult.Committed
                    }
                    is DirectControlProcessingResult.Rejected -> DirectControlCommitResult.Rejected(result.code)
                }
            },
        )

        assertEquals(
            listOf(
                InboundDispatchResult.Rejected("snapshot_begin_rejected"),
                InboundDispatchResult.Rejected("snapshot_item_rejected"),
                InboundDispatchResult.Rejected("snapshot_end_rejected"),
            ),
            events.map { dispatcher.dispatch(envelopeFor(it)) },
        )
        assertEquals(3, journalInvocations)
        assertEquals(emptyList(), journaledRows, "rejection must not create READY relay ACK custody")
    }

    @Test
    fun relayDispatchReportsThenRethrowsTransientFailure() = runTest {
        val expected = IllegalStateException("database unavailable")
        var reported: Throwable? = null

        val actual = assertFailsWith<IllegalStateException> {
            dispatchRelayDeliveryFailClosed(
                dispatch = { throw expected },
                onFailure = { reported = it },
            )
        }

        assertSame(expected, actual)
        assertSame(expected, reported)
    }

    @Test
    fun transientControlStorageFailureEscapesWithoutAcceptance() = runTest {
        val expected = IllegalStateException("storage unavailable")
        val journal = DirectControlJournal { _, process ->
            process()
            throw expected
        }

        val actual = assertFailsWith<IllegalStateException> {
            dispatchAuthenticatedDirectControl(
                msgId = "11111111-1111-4111-8111-111111111111",
                originDevice = "peer-device",
                envelopeSha256 = "a".repeat(64),
                eventType = "state.snapshot.begin",
                committedAt = 100,
                journal = journal,
                process = { DirectControlProcessingResult.Applied },
            )
        }

        assertSame(expected, actual)
    }

    @Test
    fun transientBeginItemAndEndFailuresAllEscapeWithoutAcceptance() = runTest {
        for (eventType in listOf("state.snapshot.begin", "state.snapshot.item", "state.snapshot.end")) {
            val expected = IllegalStateException("storage unavailable")
            val actual = assertFailsWith<IllegalStateException> {
                dispatchAuthenticatedDirectControl(
                    msgId = "11111111-1111-4111-8111-111111111111",
                    originDevice = "peer-device",
                    envelopeSha256 = "a".repeat(64),
                    eventType = eventType,
                    committedAt = 100,
                    journal = DirectControlJournal { _, process ->
                        process()
                        DirectControlCommitResult.Committed
                    },
                    process = { throw expected },
                )
            }
            assertSame(expected, actual)
        }
    }

    @Test
    fun committedAndDuplicateControlsPreserveTypedAcceptance() = runTest {
        val row = arrayOfNulls<InboundMessage>(1)
        val committed = dispatchAuthenticatedDirectControl(
            msgId = "11111111-1111-4111-8111-111111111111",
            originDevice = "peer-device",
            envelopeSha256 = "a".repeat(64),
            eventType = "state.digest",
            committedAt = 100,
            journal = DirectControlJournal { inbound, process ->
                row[0] = inbound
                assertSame(DirectControlProcessingResult.Applied, process())
                DirectControlCommitResult.Committed
            },
            process = { DirectControlProcessingResult.Applied },
        )
        val duplicate = dispatchAuthenticatedDirectControl(
            msgId = "11111111-1111-4111-8111-111111111111",
            originDevice = "peer-device",
            envelopeSha256 = "a".repeat(64),
            eventType = "state.digest",
            committedAt = 200,
            journal = DirectControlJournal { _, _ -> DirectControlCommitResult.Duplicate },
            process = { error("duplicate must not reprocess") },
        )

        assertIs<InboundDispatchResult.Accepted>(committed)
        assertIs<InboundDispatchResult.Duplicate>(duplicate)
        assertEquals(null, row[0]?.canonId)
        assertEquals(null, row[0]?.sequence)
        assertEquals("READY", row[0]?.relayAckState)
    }

    @Test
    fun conflictingOrRejectedControlIsNeverAccepted() = runTest {
        val conflict = dispatchAuthenticatedDirectControl(
            msgId = "11111111-1111-4111-8111-111111111111",
            originDevice = "peer-device",
            envelopeSha256 = "a".repeat(64),
            eventType = "peer.receipt",
            committedAt = 100,
            journal = DirectControlJournal { _, _ -> DirectControlCommitResult.IdConflict },
            process = { error("conflict must not reprocess") },
        )
        val rejected = dispatchAuthenticatedDirectControl(
            msgId = "22222222-2222-4222-8222-222222222222",
            originDevice = "peer-device",
            envelopeSha256 = "b".repeat(64),
            eventType = "state.snapshot.end",
            committedAt = 100,
            journal = DirectControlJournal { _, process ->
                when (val result = process()) {
                    DirectControlProcessingResult.Applied -> DirectControlCommitResult.Committed
                    is DirectControlProcessingResult.Rejected -> DirectControlCommitResult.Rejected(result.code)
                }
            },
            process = { DirectControlProcessingResult.Rejected("snapshot_incomplete") },
        )

        assertEquals(InboundDispatchResult.Rejected("id_conflict"), conflict)
        assertEquals(InboundDispatchResult.Rejected("snapshot_incomplete"), rejected)
    }

    @Test
    fun peerReceiptDigestConflictMapsToBoundedControlRejection() {
        assertEquals(
            DirectControlProcessingResult.Rejected("receipt_conflict"),
            peerReceiptControlResult(OutboxTransition.Conflict("private-existing-digest")),
        )
        assertSame(DirectControlProcessingResult.Applied, peerReceiptControlResult(OutboxTransition.Deleted))
    }

    @Test
    fun committedSnapshotIncrementsCommitObservationOnce() {
        ProductObservationTracker.clear()

        recordSnapshotCommitIfCommitted(Result.success(SnapshotConvergence.Committed(1, 0)))

        assertEquals(1L, ProductObservationTracker.snapshot().snapshotCommitCount)
    }

    @Test
    fun digestMismatchDoesNotIncrementSnapshotCommitObservation() {
        ProductObservationTracker.clear()

        recordSnapshotCommitIfCommitted(Result.success(SnapshotConvergence.DigestMismatch("a", "b")))

        assertEquals(0L, ProductObservationTracker.snapshot().snapshotCommitCount)
    }

    @Test
    fun rejectedSnapshotDoesNotIncrementSnapshotCommitObservation() {
        ProductObservationTracker.clear()

        recordSnapshotCommitIfCommitted(Result.success(SnapshotConvergence.Rejected("fixture")))

        assertEquals(0L, ProductObservationTracker.snapshot().snapshotCommitCount)
    }

    @Test
    fun exceptionalSnapshotEndDoesNotIncrementSnapshotCommitObservation() {
        ProductObservationTracker.clear()

        recordSnapshotCommitIfCommitted(Result.failure(IllegalStateException("fixture")))

        assertEquals(0L, ProductObservationTracker.snapshot().snapshotCommitCount)
    }

    @Test
    fun authenticatedV2UnpairCompletesProductionHandlerBeforeAcceptance() = runTest {
        val order = mutableListOf<String>()
        val result = dispatchAuthenticatedV2Unpair(
            eventType = "unpair",
            msgId = "11111111-1111-4111-8111-111111111111",
            envelopeSha256 = "a".repeat(64),
            preparePeerUnpair = { order += "handled" },
            finalizeServiceStop = { order += "stopped" },
        )

        order += "returned"
        assertEquals(listOf("handled", "returned"), order)
        val accepted = result as InboundDispatchResult.AcceptedAfterCustody
        assertEquals("11111111-1111-4111-8111-111111111111", accepted.msgId)
        assertEquals("a".repeat(64), accepted.envelopeSha256)
        accepted.finalizeAfterCustody()
        assertEquals(listOf("handled", "returned", "stopped"), order)
    }

    @Test
    fun authenticatedV2UnpairPropagatesCancellationWithoutAcceptance() = runTest {
        val expected = CancellationException("caller cancelled")
        val actual = assertFailsWith<CancellationException> {
            dispatchAuthenticatedV2Unpair(
                eventType = "unpair",
                msgId = "11111111-1111-4111-8111-111111111111",
                envelopeSha256 = "a".repeat(64),
                preparePeerUnpair = { throw expected },
                finalizeServiceStop = { error("must not run") },
            )
        }
        assertSame(expected, actual)
    }

    @Test
    fun authenticatedV2ControlHelperDoesNotClaimOtherEventTypes() = runTest {
        assertNull(
            dispatchAuthenticatedV2Unpair(
                eventType = "notif.post",
                msgId = "11111111-1111-4111-8111-111111111111",
                envelopeSha256 = "a".repeat(64),
                preparePeerUnpair = { error("must not run") },
                finalizeServiceStop = { error("must not run") },
            ),
        )
    }

    private object NoAccessSnapshotStore : SnapshotStore {
        override suspend fun activeOriginStates(originDevice: String): List<CanonicalNotificationState> =
            error("malformed begin must reject before storage")

        override suspend fun beginSnapshot(
            snapshotId: String,
            originDevice: String,
            expectedItemCount: Int,
            receivedAt: Long,
        ): SnapshotBeginResult = error("malformed begin must reject before storage")

        override suspend fun stageSnapshotItem(row: SnapshotStage): SnapshotStageResult =
            error("malformed begin must reject before storage")

        override suspend fun commitSnapshot(
            snapshotId: String,
            expectedDigest: String,
            committedAt: Long,
        ): SnapshotCommitResult = error("malformed begin must reject before storage")
    }

    private fun malformedSnapshotEvent(
        msgId: String,
        type: String,
        canonId: String? = null,
        sequence: Long? = null,
    ) = InnerEventV2(
        msgId = msgId,
        originDevice = "peer-device",
        type = type,
        canonId = canonId,
        sequence = sequence,
        createdAt = 1_000,
        expiresAt = 10_000,
        payloadJson = "{}",
    )

    private fun envelopeFor(event: InnerEventV2): String {
        val plaintext = ProtocolJson.encodeInner(event).encodeToByteArray()
        return ProtocolJson.encodeEnvelope(
            EncryptedEnvelope(
                version = ProtocolJson.VERSION,
                msgId = event.msgId,
                originDevice = event.originDevice,
                createdAt = event.createdAt,
                nonceB64 = Base64.getEncoder().encodeToString(ByteArray(24)),
                ciphertextB64 = Base64.getEncoder().encodeToString(plaintext),
            ),
        )
    }
}
