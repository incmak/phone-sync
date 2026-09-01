package co.twinotify.core.service

import android.content.Context
import android.content.ContextWrapper
import java.util.concurrent.CancellationException
import java.util.Base64
import java.io.File
import co.twinotify.core.protocol.EncryptedEnvelope
import co.twinotify.core.protocol.AuthenticatedEnvelope
import co.twinotify.core.protocol.EnvelopeAuthenticator
import co.twinotify.core.protocol.InnerEventV2
import co.twinotify.core.protocol.PayloadDecryptor
import co.twinotify.core.protocol.ProtocolJson
import co.twinotify.core.actions.ActionInvokeRequest
import co.twinotify.core.actions.ActionProcessResult
import co.twinotify.core.actions.ActionResultProcessResult
import co.twinotify.core.actions.ActionResultRequest
import co.twinotify.core.storage.CanonicalNotificationState
import co.twinotify.core.storage.InboundMessage
import co.twinotify.core.storage.SnapshotBeginResult
import co.twinotify.core.storage.SnapshotCommitResult
import co.twinotify.core.storage.SnapshotStage
import co.twinotify.core.storage.SnapshotStageResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertIs
import kotlin.test.assertTrue

class InboundDispatcherControlTest {
    @Test
    fun authenticatedExpiredEventCommitsTerminalReceiptWithoutMaterialization() = runTest {
        val inner = malformedSnapshotEvent(
            msgId = 49.canonicalUuid(),
            type = "notif.post",
            canonId = "peer-device:com.example:49:",
            sequence = 49,
        )
        val opened = AuthenticatedEnvelope(
            outer = EncryptedEnvelope(
                version = 2,
                msgId = inner.msgId,
                originDevice = inner.originDevice,
                createdAt = inner.createdAt,
                nonceB64 = "nonce",
                ciphertextB64 = "ciphertext",
            ),
            inner = inner,
            envelopeSha256 = "a".repeat(64),
        )
        var committed: Pair<InboundMessage, co.twinotify.core.storage.OutboundMessage>? = null
        var receiptArgs: Pair<String, String>? = null

        val result = dispatchAuthenticatedExpiry(
            opened = opened,
            committedAt = 20_000,
            createReceipt = { msgId, digest ->
                receiptArgs = msgId to digest
                outboundPeerReceipt(79.canonicalUuid())
            },
            journal = CallRejectionJournal { inbound, receipt ->
                committed = inbound to receipt
                CallRejectionCommitResult.Committed
            },
        )

        assertEquals(InboundDispatchResult.Accepted(inner.msgId, opened.envelopeSha256), result)
        assertEquals(inner.msgId to opened.envelopeSha256, receiptArgs)
        val (inbound, receipt) = requireNotNull(committed)
        assertEquals("REJECTED", inbound.outcome)
        assertEquals("NONE", inbound.relayAckState)
        assertEquals(inner.canonId, inbound.canonId)
        assertEquals(inner.sequence, inbound.sequence)
        assertEquals(receipt.msgId, inbound.receiptMsgId)
    }

    @Test
    fun authenticatedBootstrapAndProbeCommitAppliedReceiptsBeforePostCommitSignals() = runTest {
        val bootstrap = lanBootstrapEvent()
        val probe = peerProbeEvent()
        val plaintextByMsgId = listOf(bootstrap, probe).associate { event ->
            event.msgId to ProtocolJson.encodeInner(event).encodeToByteArray()
        }
        val authenticator = EnvelopeAuthenticator(PayloadDecryptor { envelope ->
            requireNotNull(plaintextByMsgId[envelope.msgId])
        }, "peer-device", clock = { 2_000 })
        val context = object : ContextWrapper(null) {
            override fun getApplicationContext(): Context = this
        }
        val committed = mutableListOf<Pair<InboundMessage, co.twinotify.core.storage.OutboundMessage>>()
        val signals = mutableListOf<String>()
        val events = mutableListOf<String>()
        var materializationRequests = 0
        var nextReceipt = 70
        val dispatcher = InboundDispatcher(
            ctx = context,
            snapshotCoordinator = SnapshotCoordinator(NoAccessSnapshotStore),
            onAuthenticatedEvent = events::add,
            authenticatedV2Opener = authenticator::open,
            directControlJournal = null,
            materializationRequester = MaterializationRequester { materializationRequests += 1 },
            receiptBackedControlJournal = ReceiptBackedControlJournal { inbound, receipt, process ->
                assertSame(ReceiptBackedControlResult.Applied, process())
                committed += inbound to receipt
                DirectControlCommitResult.Committed
            },
            appliedReceiptFactory = AppliedControlReceiptFactory { ackedMsgId, digest ->
                outboundPeerReceipt((nextReceipt++).canonicalUuid()).copy(
                    envelopeSha256 = digest,
                    envelopeJson = ackedMsgId,
                )
            },
            lanBootstrapProcessor = LanBootstrapProcessor {
                LanBootstrapProcessResult.Applied(bindingChanged = true)
            },
            transportGeneration = { 9 },
            requestDirectAttempt = { signals += "direct" },
            requestRouteReload = { signals += "reload" },
        )

        assertIs<InboundDispatchResult.Accepted>(dispatcher.dispatch(envelopeFor(bootstrap)))
        assertEquals(listOf("reload"), signals)
        assertIs<InboundDispatchResult.Accepted>(dispatcher.dispatch(envelopeFor(probe)))

        assertEquals(listOf("reload", "direct"), signals)
        assertEquals(0, materializationRequests)
        assertEquals(listOf("lan.bootstrap", "peer.probe"), events)
        assertEquals(listOf("lan.bootstrap", "peer.probe"), committed.map { it.first.eventType })
        committed.forEach { (inbound, receipt) ->
            assertEquals(receipt.msgId, inbound.receiptMsgId)
            assertEquals("NONE", inbound.relayAckState)
            assertEquals("peer.receipt", receipt.eventType)
        }
    }

    @Test
    fun duplicateReceiptBackedControlDoesNotReprocessOrSignal() = runTest {
        val event = peerProbeEvent()
        val result = dispatchAuthenticatedReceiptBackedControl(
            inner = event,
            envelopeSha256 = "a".repeat(64),
            committedAt = 2_000,
            receiptFactory = AppliedControlReceiptFactory { _, _ ->
                outboundPeerReceipt(80.canonicalUuid())
            },
            journal = ReceiptBackedControlJournal { _, _, _ -> DirectControlCommitResult.Duplicate },
            process = { error("duplicate must not process") },
        )

        assertIs<InboundDispatchResult.Duplicate>(result)
    }

    @Test
    fun authenticatedActionResultUsesDedicatedProcessor() = runTest {
        val event = actionResultEvent()
        var request: ActionResultRequest? = null

        val result = dispatchAuthenticatedActionResult(
            inner = event,
            envelopeSha256 = "d".repeat(64),
            committedAt = 2_000,
            processor = AuthenticatedActionResultProcessor {
                request = it
                ActionResultProcessResult.Applied
            },
        )

        assertEquals(InboundDispatchResult.Accepted(event.msgId, "d".repeat(64)), result)
        assertEquals("dispatched", request?.status)
        assertEquals("APPLIED", request?.inbound?.outcome)
        assertNull(request?.inbound?.canonId)
    }

    @Test
    fun authenticatedActionInvokeUsesDedicatedProcessorAndWaitsForItsClaimCommit() = runTest {
        val processorEntered = CompletableDeferred<Unit>()
        val releaseClaimCommit = CompletableDeferred<Unit>()
        var captured: ActionInvokeRequest? = null
        val event = actionInvokeEvent()

        val dispatch = async {
            dispatchAuthenticatedActionInvoke(
                inner = event,
                envelopeSha256 = "a".repeat(64),
                committedAt = 2_000,
                processor = AuthenticatedActionInvokeProcessor { request ->
                    captured = request
                    processorEntered.complete(Unit)
                    releaseClaimCommit.await()
                    ActionProcessResult.Completed("dispatched")
                },
                rejectionJournal = ActionInvokeRejectionJournal { error("valid invoke must not reject") },
            )
        }
        processorEntered.await()
        assertFalse(dispatch.isCompleted, "route acknowledgement must wait for Transaction A")
        releaseClaimCommit.complete(Unit)

        assertEquals(
            InboundDispatchResult.Accepted(event.msgId, "a".repeat(64)),
            dispatch.await(),
        )
        assertEquals("origin:pkg:1:tag", captured?.canonId)
        assertEquals(7, captured?.notificationSequence)
        assertEquals("APPLIED", captured?.inbound?.outcome)
        assertNull(captured?.inbound?.canonId)
    }

    @Test
    fun malformedAuthenticatedActionInvokeIsJournaledAsRejected() = runTest {
        var rejected: InboundMessage? = null
        val malformed = actionInvokeEvent().copy(payloadJson = "{}")

        val result = dispatchAuthenticatedActionInvoke(
            inner = malformed,
            envelopeSha256 = "b".repeat(64),
            committedAt = 2_000,
            processor = AuthenticatedActionInvokeProcessor { error("malformed invoke must not process") },
            rejectionJournal = ActionInvokeRejectionJournal { row ->
                rejected = row
                ActionInvokeRejectionCommitResult.Committed
            },
        )

        assertEquals(InboundDispatchResult.Accepted(malformed.msgId, "b".repeat(64)), result)
        assertEquals("REJECTED", rejected?.outcome)
        assertEquals("READY", rejected?.relayAckState)
        assertEquals("notif.action.invoke", rejected?.eventType)
    }

    @Test
    fun actionInvokeIdConflictIsAStableRouteRejection() = runTest {
        val event = actionInvokeEvent()

        val result = dispatchAuthenticatedActionInvoke(
            inner = event,
            envelopeSha256 = "c".repeat(64),
            committedAt = 2_000,
            processor = AuthenticatedActionInvokeProcessor { ActionProcessResult.IdConflict },
            rejectionJournal = ActionInvokeRejectionJournal { error("valid invoke must not reject") },
        )

        assertEquals(InboundDispatchResult.Rejected("id_conflict"), result)
    }

    @Test
    fun notificationRequesterRunsAfterItsDurableCommitReleasesStateMutex() = runTest {
        val stateMutex = Mutex()
        val firstRequesterEntered = CompletableDeferred<Unit>()
        val releaseFirstRequester = CompletableDeferred<Unit>()
        val durableCanonIds = mutableListOf<String>()
        var requests = 0
        val requester = MaterializationRequester {
            assertTrue(durableCanonIds.isNotEmpty(), "request must follow the durable notification callback")
            requests += 1
            if (requests == 1) {
                firstRequesterEntered.complete(Unit)
                releaseFirstRequester.await()
            }
        }

        val first = async {
            dispatchDesiredStateAfterCommit(stateMutex, requester) {
                durableCanonIds += "notification-first"
                DesiredStateDispatch(
                    InboundDispatchResult.Accepted("notification-first", "a".repeat(64)),
                    requestMaterialization = true,
                )
            }
        }
        firstRequesterEntered.await()

        val second = async {
            dispatchDesiredStateAfterCommit(stateMutex, requester) {
                durableCanonIds += "notification-second"
                DesiredStateDispatch(
                    InboundDispatchResult.Accepted("notification-second", "b".repeat(64)),
                    requestMaterialization = true,
                )
            }
        }

        assertEquals(
            InboundDispatchResult.Accepted("notification-second", "b".repeat(64)),
            withTimeout(100) { second.await() },
        )
        assertEquals(listOf("notification-first", "notification-second"), durableCanonIds)
        assertEquals(2, requests)
        assertFalse(first.isCompleted, "the first requester remains blocked without holding stateMutex")

        releaseFirstRequester.complete(Unit)
        assertEquals(
            InboundDispatchResult.Accepted("notification-first", "a".repeat(64)),
            first.await(),
        )
    }

    @Test
    fun callRequesterRunsAfterItsDurableCommitReleasesStateMutex() = runTest {
        val stateMutex = Mutex()
        val firstRequesterEntered = CompletableDeferred<Unit>()
        val releaseFirstRequester = CompletableDeferred<Unit>()
        val durableCanonIds = mutableListOf<String>()
        var requests = 0
        val requester = MaterializationRequester {
            assertTrue(durableCanonIds.isNotEmpty(), "request must follow the durable call callback")
            requests += 1
            if (requests == 1) {
                firstRequesterEntered.complete(Unit)
                releaseFirstRequester.await()
            }
        }

        val first = async {
            dispatchDesiredStateAfterCommit(stateMutex, requester) {
                durableCanonIds += "call-first"
                DesiredStateDispatch(
                    InboundDispatchResult.Accepted("call-first", "a".repeat(64)),
                    requestMaterialization = true,
                )
            }
        }
        firstRequesterEntered.await()

        val second = async {
            dispatchDesiredStateAfterCommit(stateMutex, requester) {
                durableCanonIds += "call-second"
                DesiredStateDispatch(
                    InboundDispatchResult.Accepted("call-second", "b".repeat(64)),
                    requestMaterialization = true,
                )
            }
        }

        assertEquals(
            InboundDispatchResult.Accepted("call-second", "b".repeat(64)),
            withTimeout(100) { second.await() },
        )
        assertEquals(listOf("call-first", "call-second"), durableCanonIds)
        assertEquals(2, requests)
        assertFalse(first.isCompleted, "the first requester remains blocked without holding stateMutex")

        releaseFirstRequester.complete(Unit)
        assertEquals(
            InboundDispatchResult.Accepted("call-first", "a".repeat(64)),
            first.await(),
        )
    }

    @Test
    fun desiredStateRequesterPropagatesCancellationExactlyAfterCommit() = runTest {
        val expected = CancellationException("request cancelled")
        val actual = assertFailsWith<CancellationException> {
            dispatchDesiredStateAfterCommit(
                stateMutex = Mutex(),
                requester = MaterializationRequester { throw expected },
            ) {
                DesiredStateDispatch(
                    InboundDispatchResult.Accepted("committed", "a".repeat(64)),
                    requestMaterialization = true,
                )
            }
        }

        assertSame(expected, actual)
    }

    @Test
    fun committedDuplicateRequestsButRejectionsAndReceiptConflictsDoNot() = runTest {
        var requests = 0
        val requester = MaterializationRequester { requests += 1 }
        val mutex = Mutex()
        val duplicate = InboundDispatchResult.Duplicate("duplicate", "a".repeat(64))

        assertEquals(
            duplicate,
            dispatchDesiredStateAfterCommit(mutex, requester) {
                DesiredStateDispatch(duplicate, requestMaterialization = true)
            },
        )
        for (rejection in listOf(
            InboundDispatchResult.Rejected("id_conflict"),
            InboundDispatchResult.Rejected("supersession_receipt_conflict"),
        )) {
            assertEquals(
                rejection,
                dispatchDesiredStateAfterCommit(mutex, requester) {
                    DesiredStateDispatch(rejection, requestMaterialization = false)
                },
            )
        }

        assertEquals(1, requests)
    }

    @Test
    fun productionNotificationAndCallBranchesUseTheTestedPostCommitRequester() {
        val sourceRoot = File(System.getProperty("user.dir"), "src/main/java/co/twinotify/core/service")
        val dispatcherSource = File(sourceRoot, "InboundDispatcher.kt").readText()
        val notificationBranch = dispatcherSource
            .substringAfter("if (inner.type !in setOf(\"notif.post\", \"notif.update\", \"notif.cancel\"))")
            .substringBefore("private suspend fun dispatchCallState")
        val callBranch = dispatcherSource.substringAfter("private suspend fun dispatchCallState")

        assertTrue(notificationBranch.contains("dispatchDesiredStateAfterCommit(stateMutex, materializationRequester)"))
        assertTrue(callBranch.contains("dispatchDesiredStateAfterCommit(stateMutex, materializationRequester)"))

        val serviceSource = File(sourceRoot, "SyncService.kt").readText()
        val onCreate = serviceSource.substringAfter("override fun onCreate()").substringBefore("override fun onStartCommand")
        assertTrue(onCreate.contains("materializationRequester = MaterializationRequester"))
        assertTrue(onCreate.contains("requestPendingMaterialization(MaterializationTrigger.ROUTINE)"))
        assertTrue(serviceSource.contains("ProcessNotificationActionRegistry.registry.clear()"))
        assertTrue(dispatcherSource.contains("if (inner.type == \"notif.action.invoke\")"))
        assertTrue(dispatcherSource.indexOf("if (inner.type == \"notif.action.invoke\")") <
            dispatcherSource.indexOf("if (inner.type !in setOf(\"notif.post\", \"notif.update\", \"notif.cancel\"))"))
    }

    private fun actionInvokeEvent() = InnerEventV2(
        msgId = "11111111-1111-4111-8111-111111111111",
        originDevice = "mirror-device",
        type = "notif.action.invoke",
        canonId = null,
        sequence = null,
        createdAt = 1_000,
        expiresAt = 121_000,
        payloadJson = """{
          "invocation_id":"22222222-2222-4222-8222-222222222222",
          "canon_id":"origin:pkg:1:tag",
          "action_id":"33333333-3333-4333-8333-333333333333",
          "notification_sequence":7,
          "reply_text":"private reply",
          "invoked_at":1000
        }""".trimIndent(),
    )

    private fun actionResultEvent() = InnerEventV2(
        msgId = "55555555-5555-4555-8555-555555555555",
        originDevice = "origin-device",
        type = "notif.action.result",
        canonId = null,
        sequence = null,
        createdAt = 1_000,
        expiresAt = 601_000,
        payloadJson = """{
          "invocation_id":"22222222-2222-4222-8222-222222222222",
          "canon_id":"origin:pkg:1:tag",
          "status":"dispatched"
        }""".trimIndent(),
    )

    @Test
    fun supersessionPreparationCreatesOneRejectedReceiptPerStoredInboundInOrder() = runTest {
        val older = listOf(
            InboundMessage("old-1", "peer", "a".repeat(64), "notif.post", "canon", 1, "PENDING_PLATFORM", 1, null, null, "NONE"),
            InboundMessage("old-2", "peer", "b".repeat(64), "notif.update", "canon", 2, "PENDING_PLATFORM", 2, null, null, "NONE"),
        )

        val result = prepareSupersessionRejections(older) { row, reason ->
            assertEquals("superseded", reason)
            outboundPeerReceipt("receipt-${row.msgId}")
        }

        val prepared = assertIs<SupersessionPreparation.Prepared>(result)
        assertEquals(listOf("old-1", "old-2"), prepared.entries.map { it.inboundMsgId })
        assertEquals(listOf("a".repeat(64), "b".repeat(64)), prepared.entries.map { it.envelopeSha256 })
    }

    @Test
    fun supersessionPreparationFailsClosedAndPreservesCancellationIdentity() = runTest {
        val older = listOf(InboundMessage("old", "peer", "a".repeat(64), "notif.post", "canon", 1, "PENDING_PLATFORM", 1, null, null, "NONE"))
        assertEquals(
            SupersessionPreparation.Unavailable,
            prepareSupersessionRejections(older) { _, _ -> null },
        )
        val expected = CancellationException("stop")
        assertSame(expected, assertFailsWith<CancellationException> {
            prepareSupersessionRejections(older) { _, _ -> throw expected }
        })
    }

    @Test
    fun callSequenceRejectionCreatesDurableTerminalReceiptBeforeAcceptance() = runTest {
        var inbound: InboundMessage? = null
        var receipt: co.twinotify.core.storage.OutboundMessage? = null
        val expectedReceipt = outboundPeerReceipt("receipt-1")

        val result = dispatchAuthenticatedCallRejection(
            msgId = "11111111-1111-4111-8111-111111111111",
            originDevice = "peer-device",
            envelopeSha256 = "a".repeat(64),
            canonId = "call:22222222-2222-4222-8222-222222222222",
            sequence = 1,
            reason = "call_sequence_lower",
            committedAt = 100,
            createReceipt = { reason ->
                assertEquals("call_sequence_lower", reason)
                expectedReceipt
            },
            journal = CallRejectionJournal { row, terminalReceipt ->
                inbound = row
                receipt = terminalReceipt
                CallRejectionCommitResult.Committed
            },
        )

        assertEquals(
            InboundDispatchResult.Accepted("11111111-1111-4111-8111-111111111111", "a".repeat(64)),
            result,
        )
        assertEquals("REJECTED", inbound?.outcome)
        assertEquals(expectedReceipt.msgId, inbound?.receiptMsgId)
        assertEquals(expectedReceipt, receipt)
        assertEquals("peer.receipt", receipt?.eventType)
        assertEquals(false, receipt?.requiresPeerReceipt)
    }

    @Test
    fun notificationSequenceRejectionJournalsRejectedNotificationRatherThanReceiptlessStale() = runTest {
        var journaled: InboundMessage? = null

        val result = dispatchAuthenticatedCallRejection(
            msgId = "11111111-1111-4111-8111-111111111111",
            originDevice = "peer-device",
            envelopeSha256 = "a".repeat(64),
            canonId = "peer-device:chat:42:",
            sequence = 1,
            reason = "notification_sequence_stale",
            committedAt = 100,
            eventType = "notif.update",
            createReceipt = { outboundPeerReceipt("receipt-stale") },
            journal = CallRejectionJournal { row, _ ->
                journaled = row
                CallRejectionCommitResult.Committed
            },
        )

        assertEquals(InboundDispatchResult.Accepted("11111111-1111-4111-8111-111111111111", "a".repeat(64)), result)
        assertEquals("notif.update", journaled?.eventType)
        assertEquals("REJECTED", journaled?.outcome)
    }

    @Test
    fun callSequenceRejectionCannotAcceptWithoutDurableReceipt() = runTest {
        val result = dispatchAuthenticatedCallRejection(
            msgId = "11111111-1111-4111-8111-111111111111",
            originDevice = "peer-device",
            envelopeSha256 = "a".repeat(64),
            canonId = "call:22222222-2222-4222-8222-222222222222",
            sequence = 1,
            reason = "call_sequence_conflict",
            committedAt = 100,
            createReceipt = { null },
            journal = CallRejectionJournal { _, _ -> error("journal must not run") },
        )

        assertEquals(InboundDispatchResult.Rejected("call_rejection_receipt_unavailable"), result)
    }
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
            materializationRequester = MaterializationRequester {},
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
    fun digestSourceUnavailableIsAcceptedSoTheDirectQueueCanContinueDraining() {
        assertSame(
            DirectControlProcessingResult.Applied,
            SnapshotConvergence.SourceUnavailable.toDirectControlResult("digest_rejected"),
        )
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

    private fun lanBootstrapEvent() = InnerEventV2(
        msgId = 50.canonicalUuid(),
        originDevice = "peer-device",
        type = "lan.bootstrap",
        canonId = null,
        sequence = null,
        createdAt = 1_000,
        expiresAt = 601_000,
        payloadJson = """{"protocol_version":1,"tls_spki_sha256":"${"1".repeat(64)}","binding_context_sha256":"${"2".repeat(64)}"}""",
    )

    private fun peerProbeEvent() = InnerEventV2(
        msgId = 60.canonicalUuid(),
        originDevice = "peer-device",
        type = "peer.probe",
        canonId = null,
        sequence = null,
        createdAt = 1_000,
        expiresAt = 121_000,
        payloadJson = """{"probe_id":"${60.canonicalUuid()}","sent_at":1000,"request_direct":true}""",
    )

    private fun Int.canonicalUuid() = "%08d-0000-4000-8000-000000000000".format(this)
}

private fun outboundPeerReceipt(msgId: String) = co.twinotify.core.storage.OutboundMessage(
    msgId = msgId,
    canonId = null,
    sequence = null,
    eventType = "peer.receipt",
    protocolVersion = 2,
    envelopeJson = "{}",
    envelopeSha256 = "b".repeat(64),
    byteSize = 2,
    createdAt = 100,
    expiresAt = 200,
    custodyAcceptedAt = null,
    custodyRoute = null,
    attempts = 0,
    nextAttemptAt = 100,
    state = "NEW",
    lastError = null,
    requiresPeerReceipt = false,
)
