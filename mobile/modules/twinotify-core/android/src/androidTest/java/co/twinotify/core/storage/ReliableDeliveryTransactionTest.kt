package co.twinotify.core.storage

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import co.twinotify.core.call.ActiveCallRecoveryException
import co.twinotify.core.call.ActiveCallRecoverySummary
import co.twinotify.core.call.ActiveCallTerminalizer
import co.twinotify.core.call.CALL_SHUTDOWN_FAILED
import co.twinotify.core.call.CallFrameworkState
import co.twinotify.core.call.CallStateCoordinator
import co.twinotify.core.call.CallDirection
import co.twinotify.core.call.CallStateEvent
import co.twinotify.core.call.CallStatePersistResult
import co.twinotify.core.call.CallStatePersister
import co.twinotify.core.call.DaoActiveCallRecoveryStore
import co.twinotify.core.call.GracefulCallShutdownResult
import co.twinotify.core.call.CallSourceCapabilities
import co.twinotify.core.call.CallStateSource
import co.twinotify.core.call.gracefullyShutdownCallCapture
import co.twinotify.core.service.CustodyRoute
import co.twinotify.core.service.DirectControlCommitResult
import co.twinotify.core.service.DirectControlProcessingResult
import co.twinotify.core.service.ReceiptBackedControlResult
import kotlinx.coroutines.runBlocking
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ReliableDeliveryTransactionTest {
    private lateinit var db: NotificationDbImpl
    private lateinit var dao: ReliableDeliveryDao

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            NotificationDbImpl::class.java,
        ).allowMainThreadQueries().build()
        dao = db.reliableDeliveryDao()
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun deliveryQueueSnapshotClassifiesUserCustodyAndInternalRowsWithoutLosingTotals() = runBlocking {
        dao.insertOutbound(queueRow("pending", "notif.post", byteSize = 10))
        dao.insertOutbound(queueRow("lan-awaiting", "notif.update", byteSize = 11).copy(
            state = "ACCEPTED",
            custodyAcceptedAt = 1_000,
            custodyRoute = "LAN",
        ))
        dao.insertOutbound(queueRow("relay-held", "notif.cancel", byteSize = 12).copy(
            state = "ACCEPTED",
            custodyAcceptedAt = 1_000,
            custodyRoute = "RELAY",
            relayCustodyState = "ACCEPTED",
        ))
        listOf(
            "peer.receipt",
            "state.digest",
            "state.snapshot.begin",
            "state.snapshot.item",
            "state.snapshot.end",
            "lan.bootstrap",
            "peer.probe",
            "unpair",
        ).forEachIndexed { index, type ->
            dao.insertOutbound(queueRow("internal-$index", type, byteSize = 13))
        }
        dao.insertOutbound(queueRow("terminal", "call.state", byteSize = 99).copy(state = "TERMINAL"))

        assertEquals(
            DeliveryQueueSnapshot(
                pendingLocal = 1,
                awaitingPeer = 2,
                heldByRelay = 1,
                internalActive = 8,
                totalActive = 11,
                totalActiveBytes = 137,
                userContentKind = UserContentKind.NOTIFICATIONS,
            ),
            dao.deliveryQueueSnapshot(),
        )

        dao.insertOutbound(queueRow("call", "call.state", byteSize = 14))
        dao.insertOutbound(queueRow("action", "notif.action.invoke", byteSize = 15))
        val mixed = dao.deliveryQueueSnapshot()
        assertEquals(UserContentKind.SYNC_UPDATES, mixed.userContentKind)
        assertEquals(3, mixed.pendingLocal)
        assertEquals(13, mixed.totalActive)
        assertEquals(166, mixed.totalActiveBytes)
        assertEquals(mixed.totalActive, dao.activeOutboundCount())
        assertEquals(mixed.totalActiveBytes, dao.activeOutboundBytes())
    }

    @Test
    fun authenticatedAppliedNotificationReceiptRecordsOneMetricAndDuplicateDoesNotInflateIt() = runBlocking {
        val row = outbound("metric-notification", 1_000, "notif.post")
        dao.insertOutbound(row)

        assertEquals(
            RelayReceiptResult.Deleted,
            dao.applyPeerReceipt(
                row.msgId,
                row.envelopeSha256,
                status = "applied",
                reason = null,
                occurredAt = 2_000,
                peerReceiptCreatedAt = 1_125,
            ),
        )
        assertEquals(
            VerifiedDeliverySnapshot(mirroredToday = 1, latencyMs = 125),
            dao.verifiedDeliverySnapshot(1_000, 3_000),
        )

        assertEquals(
            RelayReceiptResult.AlreadyTerminal,
            dao.applyPeerReceipt(
                row.msgId,
                row.envelopeSha256,
                status = "applied",
                reason = null,
                occurredAt = 2_100,
                peerReceiptCreatedAt = 1_200,
            ),
        )
        assertEquals(1, dao.verifiedDeliverySnapshot(1_000, 3_000).mirroredToday)
    }

    @Test
    fun metricLedgerExcludesFailuresAndNonNotificationsButKeepsClockSkewSeparate() = runBlocking {
        val rejected = outbound("metric-rejected", 1_000, "notif.update")
        val call = outbound("metric-call", 1_000, "call.state")
        val skewed = outbound("metric-skewed", 1_000, "notif.update")
        listOf(rejected, call, skewed).forEach { dao.insertOutbound(it) }

        dao.applyPeerReceipt(rejected.msgId, rejected.envelopeSha256, "rejected", "stale", 2_000, 1_100)
        dao.applyPeerReceipt(call.msgId, call.envelopeSha256, "applied", null, 2_100, 1_200)
        dao.applyPeerReceipt(skewed.msgId, skewed.envelopeSha256, "applied", null, 2_200, 900)

        assertEquals(
            VerifiedDeliverySnapshot(mirroredToday = 1, latencyMs = null),
            dao.verifiedDeliverySnapshot(1_000, 3_000),
        )
        assertEquals("CLOCK_SKEW", dao.verifiedDeliveryMetric(skewed.msgId)?.latencyStatus)
    }

    @Test
    fun verifiedDeliverySnapshotUsesHalfOpenDayBoundsAndLastTenMeasuredSamples() = runBlocking {
        repeat(11) { index ->
            val row = outbound("metric-$index", 1_000, if (index % 2 == 0) "notif.post" else "notif.update")
            dao.insertOutbound(row)
            dao.applyPeerReceipt(
                row.msgId,
                row.envelopeSha256,
                "applied",
                null,
                occurredAt = 1_999L + index,
                peerReceiptCreatedAt = 1_001L + index,
            )
        }

        assertEquals(1, dao.verifiedDeliverySnapshot(1_000, 2_000).mirroredToday)
        assertEquals(10, dao.verifiedDeliverySnapshot(2_000, 3_000).mirroredToday)
        assertEquals(6, dao.verifiedDeliverySnapshot(1_000, 3_000).latencyMs)
    }

    @Test
    fun receiptBackedControlCommitsJournalAndReceiptAtomicallyAndDuplicatesSafely() = runBlocking {
        val receipt = controlOutbound("bootstrap-receipt", "peer.receipt").copy(
            envelopeSha256 = "receipt-digest",
        )
        val inbound = receiptBackedInbound("bootstrap", "digest-a", receipt.msgId)
        var processed = 0

        assertEquals(
            DirectControlCommitResult.Committed,
            dao.commitReceiptBackedControl(inbound, receipt) {
                processed += 1
                ReceiptBackedControlResult.Applied
            },
        )
        assertEquals(receipt.msgId, dao.inbound(inbound.msgId)?.receiptMsgId)
        assertEquals(receipt.envelopeSha256, dao.outboundMessage(receipt.msgId)?.envelopeSha256)
        assertEquals(emptyList(), dao.recentUiActivity(20))
        assertEquals(
            DirectControlCommitResult.Duplicate,
            dao.commitReceiptBackedControl(inbound, receipt) {
                error("duplicate must not process twice")
            },
        )
        assertEquals(1, processed)

        assertEquals(
            DirectControlCommitResult.IdConflict,
            dao.commitReceiptBackedControl(inbound.copy(envelopeSha256 = "digest-b"), receipt) {
                error("ID conflict must not process")
            },
        )
    }

    @Test
    fun receiptBackedControlWaitsForReceiptCustodyBeforeRelayAckBecomesReady() = runBlocking {
        val receipt = controlOutbound("probe-receipt", "peer.receipt")
        val inbound = receiptBackedInbound("probe", "probe-digest", receipt.msgId)
            .copy(eventType = "peer.probe", relayAckState = "NONE")

        assertEquals(
            DirectControlCommitResult.Committed,
            dao.commitReceiptBackedControl(inbound, receipt) {
                ReceiptBackedControlResult.Applied
            },
        )
        assertEquals("NONE", dao.inbound(inbound.msgId)?.relayAckState)

        assertEquals(
            CustodyAcceptanceResult.DeletedReceipt,
            dao.acceptCustody(receipt.msgId, "RELAY", acceptedAt = 2_000, retryAt = 7_000),
        )
        assertNull(dao.outboundMessage(receipt.msgId))
        assertEquals("READY", dao.inbound(inbound.msgId)?.relayAckState)
    }

    @Test
    fun rejectedOrReceiptConflictingControlLeavesNoInboundOrNewReceipt() = runBlocking {
        val rejectedReceipt = controlOutbound("rejected-receipt", "peer.receipt")
        val rejected = receiptBackedInbound("rejected-bootstrap", "digest-a", rejectedReceipt.msgId)
        assertEquals(
            DirectControlCommitResult.Rejected("lan_binding_conflict"),
            dao.commitReceiptBackedControl(rejected, rejectedReceipt) {
                ReceiptBackedControlResult.Rejected("lan_binding_conflict")
            },
        )
        assertNull(dao.inbound(rejected.msgId))
        assertNull(dao.outboundMessage(rejectedReceipt.msgId))

        val existingReceipt = controlOutbound("conflict-receipt", "peer.receipt").copy(
            envelopeSha256 = "existing-digest",
        )
        dao.insertOutbound(existingReceipt)
        val conflicted = receiptBackedInbound(
            "conflicted-probe",
            "digest-b",
            existingReceipt.msgId,
        ).copy(eventType = "peer.probe")
        assertEquals(
            DirectControlCommitResult.Rejected("receipt_conflict"),
            dao.commitReceiptBackedControl(
                conflicted,
                existingReceipt.copy(envelopeSha256 = "different-digest"),
            ) { ReceiptBackedControlResult.Applied },
        )
        assertNull(dao.inbound(conflicted.msgId))
        assertEquals("existing-digest", dao.outboundMessage(existingReceipt.msgId)?.envelopeSha256)
    }

    @Test
    fun emptyAuthoritativeSnapshotCancelsExistingMirror() = runBlocking {
        dao.putCanonical(canonical(sequence = 4, state = "ACTIVE", payload = "payload"))
        dao.beginSnapshot(
            snapshotId = "empty-snapshot",
            originDevice = ORIGIN,
            expectedItemCount = 0,
            receivedAt = 1_500,
        )

        val result = dao.commitSnapshot(
            snapshotId = "empty-snapshot",
            committedAt = 2_000,
        )

        assertEquals(SnapshotCommitResult.Committed(upserted = 0, cancelled = 1), result)
        val reconciled = dao.canonical(CANON_ID)!!
        assertEquals("CANCELLED", reconciled.state)
        assertNull(reconciled.desiredPayloadJson)
    }

    @Test
    fun snapshotBeginBaselineExcludesActiveCallState() = runBlocking {
        dao.putCanonical(canonical(sequence = 4, state = "ACTIVE", payload = "notification"))
        dao.putCanonical(callCanonical(sequence = 9))

        val begin = dao.beginSnapshot(
            snapshotId = "mixed-baseline",
            originDevice = ORIGIN,
            expectedItemCount = 0,
            receivedAt = 1_500,
        )

        assertEquals(SnapshotBeginResult.Started(baselineCount = 1), begin)
    }

    @Test
    fun emptyNotificationSnapshotPreservesActiveCallState() = runBlocking {
        val call = callCanonical(sequence = 9)
        dao.putCanonical(call)
        dao.beginSnapshot("call-only-snapshot", ORIGIN, expectedItemCount = 0, receivedAt = 1_500)

        val result = dao.commitSnapshot("call-only-snapshot", committedAt = 2_000)

        assertEquals(SnapshotCommitResult.Committed(upserted = 0, cancelled = 0), result)
        assertEquals(call, dao.canonical(CALL_CANON_ID))
    }

    @Test
    fun notificationSnapshotCancelsMissingNotificationButPreservesActiveCall() = runBlocking {
        dao.putCanonical(canonical(sequence = 4, state = "ACTIVE", payload = "notification"))
        val call = callCanonical(sequence = 9)
        dao.putCanonical(call)
        dao.beginSnapshot("mixed-empty-snapshot", ORIGIN, expectedItemCount = 0, receivedAt = 1_500)

        val result = dao.commitSnapshot("mixed-empty-snapshot", committedAt = 2_000)

        assertEquals(SnapshotCommitResult.Committed(upserted = 0, cancelled = 1), result)
        assertEquals("CANCELLED", dao.canonical(CANON_ID)?.state)
        assertEquals(call, dao.canonical(CALL_CANON_ID))
    }

    @Test
    fun snapshotStageRejectsCallNamespaceItem() = runBlocking {
        dao.beginSnapshot("call-stage", ORIGIN, expectedItemCount = 1, receivedAt = 1_500)

        assertFailsWith<IllegalArgumentException> {
            dao.stageSnapshotItem(
                SnapshotStage(
                    snapshotId = "call-stage",
                    canonId = CALL_CANON_ID,
                    sequence = 10,
                    payloadJson = snapshotPayload(CALL_CANON_ID),
                    receivedAt = 1_600,
                ),
            )
        }
        Unit
    }

    @Test
    fun commitRejectsPreexistingCallStageWithoutMutatingCallState() = runBlocking {
        val call = callCanonical(sequence = 9)
        dao.putCanonical(call)
        dao.beginSnapshot("legacy-call-stage", ORIGIN, expectedItemCount = 1, receivedAt = 1_500)
        db.openHelper.writableDatabase.execSQL(
            "INSERT INTO snapshot_stage(snapshotId,canonId,sequence,payloadJson,receivedAt) VALUES(?,?,?,?,?)",
            arrayOf<Any?>("legacy-call-stage", CALL_CANON_ID, 10, snapshotPayload(CALL_CANON_ID), 1_600),
        )

        val result = dao.commitSnapshot("legacy-call-stage", committedAt = 2_000)

        assertEquals(SnapshotCommitResult.InvalidItem(CALL_CANON_ID), result)
        assertEquals(call, dao.canonical(CALL_CANON_ID))
    }

    @Test
    fun v2MirrorCancelEchoResolvesAndConsumesPersistedCanonicalTombstone() = runBlocking {
        dao.putCanonical(
            canonical(sequence = 3, state = "CANCELLED", payload = null).copy(
                mirrorLocalTag = "mirror-stable",
                mirrorLocalId = 42,
                peerCancelPending = true,
            ),
        )

        assertEquals(CANON_ID, dao.canonicalForMirrorIdentity("mirror-stable", 42))
        assertEquals(1, dao.consumePeerCancel(CANON_ID))
        assertEquals(0, dao.consumePeerCancel(CANON_ID))
        assertEquals(false, dao.canonical(CANON_ID)!!.peerCancelPending)
    }

    @Test
    fun incompleteSnapshotCannotCancelExistingMirror() = runBlocking {
        dao.putCanonical(canonical(sequence = 4, state = "ACTIVE", payload = "payload"))
        dao.beginSnapshot(
            snapshotId = "incomplete-snapshot",
            originDevice = ORIGIN,
            expectedItemCount = 1,
            receivedAt = 1_500,
        )

        val result = dao.commitSnapshot(
            snapshotId = "incomplete-snapshot",
            committedAt = 2_000,
        )

        assertEquals(SnapshotCommitResult.Incomplete(expected = 1, staged = 0), result)
        val retained = dao.canonical(CANON_ID)!!
        assertEquals("ACTIVE", retained.state)
        assertEquals("payload", retained.desiredPayloadJson)
    }

    @Test
    fun snapshotCannotCancelLiveStateCommittedAfterBegin() = runBlocking {
        val begin = dao.beginSnapshot(
            snapshotId = "concurrent-snapshot",
            originDevice = ORIGIN,
            expectedItemCount = 0,
            receivedAt = 1_500,
        )
        assertEquals(SnapshotBeginResult.Started(baselineCount = 0), begin)

        val liveResult = dao.commitOutboundState(
            canonical(sequence = 1, state = "ACTIVE", payload = "live", updatedAt = 2_000),
            outbound("live", sequence = 1, eventType = "notif.post"),
        )
        assertEquals(OutboundStateCommitResult.Committed(compacted = 0), liveResult)

        val snapshotResult = dao.commitSnapshot(
            snapshotId = "concurrent-snapshot",
            committedAt = 2_500,
        )

        assertEquals(SnapshotCommitResult.Committed(upserted = 0, cancelled = 0), snapshotResult)
        val retained = dao.canonical(CANON_ID)!!
        assertEquals("ACTIVE", retained.state)
        assertEquals("live", retained.desiredPayloadJson)
        assertEquals(1, retained.latestSequence)
    }

    @Test
    fun snapshotCannotCancelLiveUpdateCommittedAfterBegin() = runBlocking {
        dao.putCanonical(canonical(sequence = 4, state = "ACTIVE", payload = "before"))
        val begin = dao.beginSnapshot(
            snapshotId = "concurrent-update-snapshot",
            originDevice = ORIGIN,
            expectedItemCount = 0,
            receivedAt = 1_500,
        )
        assertEquals(SnapshotBeginResult.Started(baselineCount = 1), begin)

        val liveResult = dao.commitOutboundState(
            canonical(sequence = 5, state = "ACTIVE", payload = "after", updatedAt = 2_000),
            outbound("live-update", sequence = 5, eventType = "notif.update"),
        )
        assertEquals(OutboundStateCommitResult.Committed(compacted = 0), liveResult)

        val snapshotResult = dao.commitSnapshot(
            snapshotId = "concurrent-update-snapshot",
            committedAt = 2_500,
        )

        assertEquals(SnapshotCommitResult.Committed(upserted = 0, cancelled = 0), snapshotResult)
        val retained = dao.canonical(CANON_ID)!!
        assertEquals("ACTIVE", retained.state)
        assertEquals("after", retained.desiredPayloadJson)
        assertEquals(5, retained.latestSequence)
    }

    @Test
    fun snapshotDigestMismatchLeavesCanonicalAndStagingUntouched() = runBlocking {
        dao.beginSnapshot("digest-mismatch", ORIGIN, expectedItemCount = 1, receivedAt = 100)
        dao.stageSnapshotItem(
            SnapshotStage("digest-mismatch", "new-canon", 4, snapshotPayload("new-canon"), 100),
        )

        val result = dao.commitSnapshot("digest-mismatch", "0".repeat(64), committedAt = 200)

        assertIs<SnapshotCommitResult.DigestMismatch>(result)
        assertNull(dao.canonical("new-canon"))
        assertIs<SnapshotCommitResult.Committed>(
            dao.commitSnapshot("digest-mismatch", snapshotDigest("new-canon", 4), committedAt = 300),
        )
        Unit
    }

    @Test
    fun validSnapshotAssignsStableMirrorIdentityAndExpiresWholeStage() = runBlocking {
        dao.beginSnapshot("stable-snapshot", ORIGIN, expectedItemCount = 1, receivedAt = 100)
        dao.stageSnapshotItem(
            SnapshotStage("stable-snapshot", "stable-canon", 1, snapshotPayload("stable-canon"), 100),
        )

        val result = dao.commitSnapshot("stable-snapshot", snapshotDigest("stable-canon", 1), committedAt = 200)

        assertEquals(SnapshotCommitResult.Committed(upserted = 1, cancelled = 0), result)
        val state = dao.canonical("stable-canon")!!
        assertTrue(state.mirrorLocalTag?.startsWith("mirror-") == true)
        assertTrue(state.mirrorLocalId!! > 0)

        dao.beginSnapshot("expired-snapshot", ORIGIN, expectedItemCount = 0, receivedAt = 100)
        assertEquals(1, dao.expireSnapshotStages(cutoff = 101))
        assertEquals(SnapshotCommitResult.MissingBegin, dao.commitSnapshot("expired-snapshot", committedAt = 200))
    }

    @Test
    fun olderPostCannotDeleteNewerQueuedUpdate() = runBlocking {
        dao.putCanonical(canonical(sequence = 5, state = "ACTIVE", payload = "newer"))
        dao.insertOutbound(outbound("newer", sequence = 5, eventType = "notif.update"))

        val result = dao.commitOutboundState(
            canonical(sequence = 4, state = "ACTIVE", payload = "older"),
            outbound("older", sequence = 4, eventType = "notif.post"),
        )

        assertIs<OutboundStateCommitResult.Stale>(result)
        assertEquals(listOf("newer"), activeMessageIds())
        assertEquals(5, dao.canonical(CANON_ID)!!.latestSequence)
    }

    @Test
    fun acceptedCustodyRetryAdvancesAttemptBackoffWithoutChangingFirstRoute() = runBlocking {
        dao.insertOutbound(outbound("accepted-retry", sequence = 1, eventType = "notif.post"))
        assertEquals(
            CustodyAcceptanceResult.Accepted,
            dao.acceptCustody("accepted-retry", CustodyRoute.RELAY.name, acceptedAt = 100, retryAt = 200),
        )
        assertEquals(
            CustodyAcceptanceResult.AlreadyAccepted,
            dao.acceptCustody("accepted-retry", CustodyRoute.LAN.name, acceptedAt = 300, retryAt = 400),
        )

        assertEquals(1, dao.markSent("accepted-retry", retryAt = 400))
        val retried = dao.sendable(now = 400, limit = 1).single()
        assertEquals("ACCEPTED", retried.state)
        assertEquals(100, retried.custodyAcceptedAt)
        assertEquals(CustodyRoute.RELAY.name, retried.custodyRoute)
        assertEquals(1, retried.attempts)
        assertEquals(400, retried.nextAttemptAt)
    }

    @Test
    fun lanFirstThenRelayAcceptanceRecordsBothCustodyFacts() = runBlocking {
        dao.insertOutbound(outbound("lan-relay", sequence = 1, eventType = "notif.post"))

        assertEquals(
            CustodyAcceptanceResult.Accepted,
            dao.acceptCustody("lan-relay", CustodyRoute.LAN.name, acceptedAt = 100, retryAt = 200),
        )
        assertEquals(
            CustodyAcceptanceResult.AlreadyAccepted,
            dao.acceptCustody("lan-relay", CustodyRoute.RELAY.name, acceptedAt = 300, retryAt = 400),
        )

        val row = dao.outboundMessage("lan-relay")!!
        assertEquals(100, row.custodyAcceptedAt)
        assertEquals(CustodyRoute.LAN.name, row.custodyRoute)
        assertEquals("ACCEPTED", row.relayCustodyState)
    }

    @Test
    fun localExpiryTerminalizesOnlyKnownNoRelayRowsOnceAndPreservesRemainingOrder() = runBlocking {
        dao.insertOutbound(outbound("new-expired", 1, "notif.post").copy(createdAt = 7, expiresAt = 1_000))
        dao.insertOutbound(outbound("relay-expired", 2, "notif.post").copy(createdAt = 7, expiresAt = 999))
        dao.acceptCustody("relay-expired", CustodyRoute.RELAY.name, acceptedAt = 100, retryAt = 200)
        dao.insertOutbound(outbound("lan-expired", 3, "notif.post").copy(createdAt = 7, expiresAt = 999))
        dao.acceptCustody("lan-expired", CustodyRoute.LAN.name, acceptedAt = 100, retryAt = 200)
        dao.insertOutbound(outbound("unknown-expired", 4, "notif.post").copy(
            createdAt = 7,
            expiresAt = 999,
            state = "ACCEPTED",
            custodyAcceptedAt = 100,
            custodyRoute = CustodyRoute.LAN.name,
            relayCustodyState = "UNKNOWN",
        ))
        dao.insertOutbound(outbound("fresh", 5, "notif.post").copy(createdAt = 7, expiresAt = 1_001))

        assertEquals(2, dao.expireLocal(now = 1_000))
        assertEquals(0, dao.expireLocal(now = 1_000))
        assertEquals(
            listOf("relay-expired", "unknown-expired", "fresh"),
            dao.sendable(now = 1_000, limit = 10).map { it.msgId },
        )
        db.openHelper.readableDatabase.query(
            "SELECT msgId,eventType,status,detailCode FROM activity_event " +
                "WHERE msgId IN ('new-expired','lan-expired') ORDER BY occurredAt,msgId",
        ).use { activities ->
            val observed = buildList {
                while (activities.moveToNext()) {
                    add(List(4) { activities.getString(it) })
                }
            }
            assertEquals(
                listOf(
                    listOf("lan-expired", "delivery.expired", "expired", "local_expired"),
                    listOf("new-expired", "delivery.expired", "expired", "local_expired"),
                ),
                observed,
            )
        }
    }

    @Test
    fun localAndRelayExpiryRaceProducesOneTerminalActivity() = runBlocking {
        dao.insertOutbound(outbound("local-wins", 1, "notif.post").copy(expiresAt = 1_000))
        assertEquals(1, dao.expireLocal(1_000))
        assertEquals(RelayReceiptResult.AlreadyTerminal, dao.expireRelay("local-wins", 1_001))

        dao.insertOutbound(outbound("relay-wins", 2, "notif.post").copy(expiresAt = 1_000))
        assertEquals(RelayReceiptResult.Deleted, dao.expireRelay("relay-wins", 999))
        assertEquals(0, dao.expireLocal(1_000))

        db.openHelper.readableDatabase.query(
            "SELECT msgId,COUNT(*) FROM activity_event " +
                "WHERE msgId IN ('local-wins','relay-wins') GROUP BY msgId ORDER BY msgId",
        ).use { activities ->
            assertTrue(activities.moveToFirst())
            assertEquals("local-wins", activities.getString(0))
            assertEquals(1, activities.getInt(1))
            assertTrue(activities.moveToNext())
            assertEquals("relay-wins", activities.getString(0))
            assertEquals(1, activities.getInt(1))
            assertFalse(activities.moveToNext())
        }
    }

    @Test
    fun custodyReceiptDeleteRollsBackWhenAckTransitionFails() = runBlocking {
        val receipt = outbound("receipt-rollback", sequence = null, eventType = "peer.receipt").copy(
            requiresPeerReceipt = false,
        )
        dao.insertOutbound(receipt)
        dao.insertInbound(inbound("source", "source-digest").copy(receiptMsgId = receipt.msgId))
        db.openHelper.writableDatabase.execSQL(
            "CREATE TRIGGER fail_receipt_ack BEFORE UPDATE OF relayAckState ON inbound_message " +
                "BEGIN SELECT RAISE(ABORT, 'forced receipt ack failure'); END",
        )

        assertFailsWith<android.database.sqlite.SQLiteException> {
            dao.acceptCustody(receipt.msgId, CustodyRoute.LAN.name, acceptedAt = 100, retryAt = 200)
        }

        assertNotNull(dao.outboundMessage(receipt.msgId))
        assertEquals("NONE", dao.inbound("source")!!.relayAckState)
    }

    @Test
    fun directControlJournalIsReadyThenSentOnceAndDuplicateDoesNotReprocess() = runBlocking {
        var processCount = 0
        val row = inbound("direct-control", "a".repeat(64)).copy(
            eventType = "state.digest",
            canonId = null,
            sequence = null,
            outcome = "APPLIED",
            appliedAt = 100,
            relayAckState = "READY",
        )

        assertEquals(
            DirectControlCommitResult.Committed,
            dao.commitDirectControl(row) {
                processCount += 1
                DirectControlProcessingResult.Applied
            },
        )
        assertEquals(listOf(co.twinotify.core.service.RelayAckRecord(row.msgId, row.envelopeSha256)), dao.readyRelayAcks(10))
        assertEquals(1, dao.markRelayAckSent(row.msgId, row.envelopeSha256))
        assertTrue(dao.readyRelayAcks(10).isEmpty())

        assertEquals(
            DirectControlCommitResult.Duplicate,
            dao.commitDirectControl(row.copy(committedAt = 200, appliedAt = 200)) {
                error("ACKed duplicate must not execute control again")
            },
        )
        assertEquals(1, processCount)
        assertEquals("SENT", dao.inbound(row.msgId)?.relayAckState)
    }

    @Test
    fun everyNoReceiptControlTypeCreatesOneReadyAckRow() = runBlocking {
        val types = listOf(
            "peer.receipt",
            "state.digest",
            "state.snapshot.begin",
            "state.snapshot.item",
            "state.snapshot.end",
        )
        types.forEachIndexed { index, eventType ->
            val row = inbound("control-$index", index.toString().repeat(64)).copy(
                eventType = eventType,
                canonId = null,
                sequence = null,
                outcome = "APPLIED",
                appliedAt = 100L + index,
                relayAckState = "READY",
            )
            assertEquals(
                DirectControlCommitResult.Committed,
                dao.commitDirectControl(row) { DirectControlProcessingResult.Applied },
            )
        }

        assertEquals(types.indices.map { "control-$it" }, dao.readyRelayAcks(10).map { it.msgId })
    }

    @Test
    fun directControlDigestConflictRejectsWithoutChangingReadySource() = runBlocking {
        val original = inbound("control-conflict", "a".repeat(64)).copy(
            eventType = "state.snapshot.item",
            canonId = null,
            sequence = null,
            outcome = "APPLIED",
            appliedAt = 100,
            relayAckState = "READY",
        )
        assertEquals(
            DirectControlCommitResult.Committed,
            dao.commitDirectControl(original) { DirectControlProcessingResult.Applied },
        )

        assertEquals(
            DirectControlCommitResult.IdConflict,
            dao.commitDirectControl(original.copy(envelopeSha256 = "b".repeat(64))) {
                error("conflict must not execute control")
            },
        )
        assertEquals(original, dao.inbound(original.msgId))
        assertEquals(listOf(original.msgId), dao.readyRelayAcks(10).map { it.msgId })
    }

    @Test
    fun snapshotEndAndAckJournalRollbackTogetherWhenJournalInsertFails() = runBlocking {
        dao.beginSnapshot("atomic-end", ORIGIN, expectedItemCount = 1, receivedAt = 100)
        dao.stageSnapshotItem(
            SnapshotStage("atomic-end", "snapshot-canon", 1, snapshotPayload("snapshot-canon"), 100),
        )
        db.openHelper.writableDatabase.execSQL(
            "CREATE TRIGGER fail_control_journal BEFORE INSERT ON inbound_message " +
                "BEGIN SELECT RAISE(ABORT, 'forced control journal failure'); END",
        )
        val row = inbound("snapshot-end-control", "c".repeat(64)).copy(
            eventType = "state.snapshot.end",
            canonId = null,
            sequence = null,
            outcome = "APPLIED",
            appliedAt = 200,
            relayAckState = "READY",
        )

        assertFailsWith<android.database.sqlite.SQLiteException> {
            dao.commitDirectControl(row) {
                assertIs<SnapshotCommitResult.Committed>(
                    dao.commitSnapshot("atomic-end", snapshotDigest("snapshot-canon", 1), committedAt = 200),
                )
                DirectControlProcessingResult.Applied
            }
        }

        assertNull(dao.inbound(row.msgId))
        assertNull(dao.canonical("snapshot-canon"))
        db.openHelper.writableDatabase.execSQL("DROP TRIGGER fail_control_journal")
        assertIs<SnapshotCommitResult.Committed>(
            dao.commitSnapshot("atomic-end", snapshotDigest("snapshot-canon", 1), committedAt = 300),
        )
        Unit
    }

    @Test
    fun unpairCannotEnterGenericDirectAckJournal() = runBlocking {
        val row = inbound("unpair-control", "d".repeat(64)).copy(
            eventType = "unpair",
            canonId = null,
            sequence = null,
            outcome = "APPLIED",
            appliedAt = 100,
            relayAckState = "READY",
        )

        assertEquals(
            DirectControlCommitResult.NotEligible,
            dao.commitDirectControl(row) { error("unpair owns a dedicated finalizer path") },
        )
        assertNull(dao.inbound(row.msgId))
        assertTrue(dao.readyRelayAcks(10).isEmpty())
    }

    @Test
    fun olderCancelCannotDeleteNewerQueuedState() = runBlocking {
        dao.putCanonical(canonical(sequence = 8, state = "ACTIVE", payload = "newer"))
        dao.insertOutbound(outbound("newer", sequence = 8, eventType = "notif.update"))

        val result = dao.commitOutboundState(
            canonical(sequence = 7, state = "CANCELLED", payload = null),
            outbound("older-cancel", sequence = 7, eventType = "notif.cancel"),
        )

        assertIs<OutboundStateCommitResult.Stale>(result)
        assertEquals(listOf("newer"), activeMessageIds())
        assertEquals(8, dao.canonical(CANON_ID)!!.latestSequence)
    }

    @Test
    fun newerUpdateAtomicallyCompactsOlderPostAndCommitsCanonicalState() = runBlocking {
        dao.putCanonical(canonical(sequence = 4, state = "ACTIVE", payload = "older"))
        dao.insertOutbound(outbound("older", sequence = 4, eventType = "notif.post"))

        val result = dao.commitOutboundState(
            canonical(sequence = 5, state = "ACTIVE", payload = "newer"),
            outbound("newer", sequence = 5, eventType = "notif.update"),
        )

        assertEquals(OutboundStateCommitResult.Committed(compacted = 1), result)
        assertEquals(listOf("newer"), activeMessageIds())
        assertEquals(5, dao.canonical(CANON_ID)!!.latestSequence)
        assertEquals("newer", dao.canonical(CANON_ID)!!.desiredPayloadJson)
    }

    @Test
    fun captureSequenceAndStateRollbackTogetherWhenOutboxInsertConflicts() = runBlocking {
        // Force the final outbox insert to fail after commitOutboundState has prepared the
        // canonical row. The capture transaction must roll back both that row and its sequence.
        dao.insertOutbound(outbound("duplicate", sequence = null, eventType = "unpair"))

        assertFailsWith<android.database.sqlite.SQLiteConstraintException> {
            dao.commitCapturedState(
                canonical(sequence = 1, state = "ACTIVE", payload = "capture"),
                outbound("duplicate", sequence = 1, eventType = "notif.post"),
            )
        }

        assertNull(dao.canonical(CANON_ID))
        assertNull(dao.nextCaptureSequence(CANON_ID))
    }

    @Test
    fun callStateUsesTheSameAtomicSequenceAndReceiptCustodyBoundary() = runBlocking {
        val callCanon = "call:11111111-1111-4111-8111-111111111111"
        val first = CanonicalNotificationState(
            canonId = callCanon,
            originDevice = ORIGIN,
            latestSequence = 1,
            state = "ACTIVE",
            desiredPayloadJson = "{\"state\":\"ringing\"}",
            materializedSequence = 0,
            sourceNotificationKey = null,
            mirrorLocalId = null,
            mirrorLocalTag = null,
            peerCancelPending = false,
            updatedAt = 1_000,
        )
        val firstRow = outbound("call-1", sequence = 1, eventType = "call.state").copy(
            canonId = callCanon,
            requiresPeerReceipt = true,
        )
        assertEquals(OutboundStateCommitResult.Committed(compacted = 0), dao.commitCapturedState(first, firstRow))
        assertEquals(2, dao.nextCaptureSequence(callCanon))

        val stale = first.copy(latestSequence = 1, state = "CANCELLED", desiredPayloadJson = null)
        val staleRow = firstRow.copy(msgId = "call-1-retry")
        assertIs<OutboundStateCommitResult.Stale>(dao.commitCapturedState(stale, staleRow))
        assertEquals(1, activeMessageIds().count { it.startsWith("call-") })
    }

    @Test
    fun activeLocalCallSelectionIsLocalActiveCallsInStableOrderOnly() = runBlocking {
        val first = callCanonical(
            canonId = "call:11111111-1111-4111-8111-111111111111",
            sequence = 2,
            updatedAt = 100,
        )
        val second = callCanonical(
            canonId = "call:22222222-2222-4222-8222-222222222222",
            sequence = 3,
            updatedAt = 100,
        )
        dao.putCanonical(second)
        dao.putCanonical(first)
        dao.putCanonical(canonical(sequence = 9, state = "ACTIVE", payload = "notification", updatedAt = 1))
        dao.putCanonical(
            callCanonical(
                canonId = "call:33333333-3333-4333-8333-333333333333",
                origin = "remote-device",
                sequence = 4,
                updatedAt = 1,
            ),
        )
        dao.putCanonical(
            callCanonical(
                canonId = "call:44444444-4444-4444-8444-444444444444",
                sequence = 5,
                state = "CANCELLED",
                updatedAt = 1,
            ),
        )

        assertEquals(
            listOf(first.canonId, second.canonId),
            dao.activeLocalCallStates(ORIGIN).map { it.canonId },
        )
    }

    @Test
    fun terminalizerAtomicallyCancelsLocalCallAndQueuesOneIdleWithoutTouchingOtherRows() = runBlocking {
        val local = callCanonical(sequence = 8)
        val remote = callCanonical(
            canonId = "call:22222222-2222-4222-8222-222222222222",
            origin = "remote-device",
            sequence = 4,
        )
        val notification = canonical(sequence = 7, state = "ACTIVE", payload = "notification")
        dao.putCanonical(local)
        dao.putCanonical(remote)
        dao.putCanonical(notification)
        val sink = DaoCallRecoverySink(dao)
        val terminalizer = ActiveCallTerminalizer(DaoActiveCallRecoveryStore(dao), CallStatePersister(sink))

        assertEquals(ActiveCallRecoverySummary(terminated = 1), terminalizer.recover(ORIGIN))
        assertEquals("CANCELLED", dao.canonical(local.canonId)?.state)
        assertEquals(9, dao.canonical(local.canonId)?.latestSequence)
        assertEquals(listOf(CallStateEvent(sessionId(local), "idle", CallDirection.INCOMING, 9)), sink.events)
        assertEquals(
            listOf("recovery-9"),
            dao.sendable(now = 1_000, limit = 10).map { it.msgId },
        )
        assertEquals("NEW", dao.outboundMessage("recovery-9")?.state)
        assertEquals("call.state", dao.outboundMessage("recovery-9")?.eventType)
        assertEquals(remote, dao.canonical(remote.canonId))
        assertEquals(notification, dao.canonical(notification.canonId))

        assertEquals(ActiveCallRecoverySummary(terminated = 0), terminalizer.recover(ORIGIN))
        assertEquals(listOf("recovery-9"), dao.sendable(now = 1_000, limit = 10).map { it.msgId })
    }

    @Test
    fun terminalizerTreatsStaleResultWithCancelledDaoRereadAsSuccess() = runBlocking {
        val local = callCanonical(sequence = 8)
        dao.putCanonical(local)
        val sink = DaoCallRecoverySink(dao, staleMode = StaleMode.CANCELLED)
        val terminalizer = ActiveCallTerminalizer(DaoActiveCallRecoveryStore(dao), CallStatePersister(sink))

        assertEquals(ActiveCallRecoverySummary(terminated = 1), terminalizer.recover(ORIGIN))
        assertEquals("CANCELLED", dao.canonical(local.canonId)?.state)
        assertEquals(9, dao.canonical(local.canonId)?.latestSequence)
        assertEquals(listOf(CallStateEvent(sessionId(local), "idle", CallDirection.INCOMING, 9)), sink.events)
        assertEquals(listOf("concurrent-cancel-9"), dao.sendable(now = 1_000, limit = 10).map { it.msgId })
    }

    @Test
    fun terminalizerRecomputesOnceAgainstActiveDaoRereadBeforeCancelling() = runBlocking {
        val local = callCanonical(sequence = 8)
        dao.putCanonical(local)
        val sink = DaoCallRecoverySink(dao, staleMode = StaleMode.ACTIVE)
        val terminalizer = ActiveCallTerminalizer(DaoActiveCallRecoveryStore(dao), CallStatePersister(sink))

        assertEquals(ActiveCallRecoverySummary(terminated = 1), terminalizer.recover(ORIGIN))
        assertEquals("CANCELLED", dao.canonical(local.canonId)?.state)
        assertEquals(10, dao.canonical(local.canonId)?.latestSequence)
        assertEquals(
            listOf(
                CallStateEvent(sessionId(local), "idle", CallDirection.INCOMING, 9),
                CallStateEvent(sessionId(local), "idle", CallDirection.INCOMING, 10),
            ),
            sink.events,
        )
        assertEquals(
            listOf("concurrent-active-9", "recovery-10"),
            dao.sendable(now = 1_000, limit = 10).map { it.msgId },
        )
    }

    @Test
    fun terminalizerRollbackOnOutboxConflictLeavesCallAndSequenceReservationUntouched() = runBlocking {
        val local = callCanonical(sequence = 8)
        val conflict = outbound("recovery-9", sequence = null, eventType = "unpair")
        dao.putCanonical(local)
        dao.insertOutbound(conflict)
        val sink = DaoCallRecoverySink(dao)
        val terminalizer = ActiveCallTerminalizer(DaoActiveCallRecoveryStore(dao), CallStatePersister(sink))

        val error = assertFailsWith<ActiveCallRecoveryException> {
            terminalizer.recover(ORIGIN)
        }

        assertEquals("call_recovery_failed", error.code)
        assertEquals(local, dao.canonical(local.canonId))
        assertNull(dao.nextCaptureSequence(local.canonId))
        assertEquals(conflict, dao.outboundMessage(conflict.msgId))
        assertEquals(listOf(conflict.msgId), activeMessageIds())
        assertEquals(listOf(CallStateEvent(sessionId(local), "idle", CallDirection.INCOMING, 9)), sink.events)
    }

    @Test
    fun recoveryOwnershipCheckAtomicallyPreservesConcurrentRemoteCallAndOutboxState() = runBlocking {
        val staleLocal = callCanonical(sequence = 8)
        val remote = staleLocal.copy(originDevice = "remote-device", updatedAt = 1_001)
        dao.putCanonical(staleLocal)
        val store = object : co.twinotify.core.call.ActiveCallRecoveryStore {
            override suspend fun activeLocalCalls(originDevice: String) = listOf(staleLocal)

            override suspend fun canonical(canonId: String) = dao.canonical(canonId)

            override suspend fun nextSequence(canonId: String): Long {
                dao.putCanonical(remote)
                return 9L
            }
        }
        val sink = DaoCallRecoverySink(dao)
        val terminalizer = ActiveCallTerminalizer(store, CallStatePersister(sink, sink))

        assertEquals(ActiveCallRecoverySummary(terminated = 1), terminalizer.recover(ORIGIN))
        assertEquals(remote, dao.canonical(staleLocal.canonId))
        assertNull(dao.nextCaptureSequence(staleLocal.canonId))
        assertTrue(activeMessageIds().isEmpty())
        assertEquals(
            listOf(CallStateEvent(sessionId(staleLocal), "idle", CallDirection.INCOMING, 9)),
            sink.events,
        )
        assertEquals(listOf(ORIGIN), sink.expectedLocalOrigins)
    }

    private suspend fun gracefulRoomShutdown(
        coordinator: CallStateCoordinator,
        terminalizer: ActiveCallTerminalizer,
        beforeCompletion: suspend () -> Unit = {},
        finalize: suspend () -> Unit = {},
    ): GracefulCallShutdownResult {
        val result = gracefullyShutdownCallCapture(
            quiesceAndTerminalize = {
                coordinator.quiesceAndTerminalize {
                    terminalizer.recover(ORIGIN)
                    beforeCompletion()
                }
            },
            reportFailure = {},
            delayBeforeRetry = {},
        )
        if (result == GracefulCallShutdownResult.Completed) finalize()
        return result
    }

    @Test
    fun recoveryOwnershipCheckRejectsDesiredOwnerMutationBeforeAnyWrite() = runBlocking {
        val local = callCanonical(sequence = 8)
        val incoming = callRecoveryOutbound("wrong-owner-recovery", local.canonId, 9)
        dao.putCanonical(local)

        assertEquals(
            CallRecoveryCommitResult.OwnershipLost,
            dao.commitRecoveredCallState(
                local.copy(
                    originDevice = "remote-device",
                    latestSequence = 9,
                    state = "CANCELLED",
                    desiredPayloadJson = null,
                ),
                incoming,
                ORIGIN,
            ),
        )
        assertEquals(local, dao.canonical(local.canonId))
        assertNull(dao.nextCaptureSequence(local.canonId))
        assertNull(dao.outboundMessage(incoming.msgId))
    }

    @Test
    fun gracefulShutdownCustodiesEveryLocalCallInRoomOrderBeforeClearingCoordinatorMemory() = runBlocking {
        val first = callCanonical(
            canonId = "call:22222222-2222-4222-8222-222222222222",
            sequence = 2,
            updatedAt = 100,
        )
        val second = callCanonical(
            canonId = "call:11111111-1111-4111-8111-111111111111",
            sequence = 8,
            updatedAt = 200,
        ).copy(
            desiredPayloadJson =
                """{"call_session_id":"11111111-1111-4111-8111-111111111111","state":"active","direction":"outgoing"}""",
        )
        val remote = callCanonical(
            canonId = "call:33333333-3333-4333-8333-333333333333",
            origin = "remote-device",
            sequence = 4,
            updatedAt = 1,
        )
        val notification = canonical(sequence = 7, state = "ACTIVE", payload = "notification")
        dao.putCanonical(second)
        dao.putCanonical(remote)
        dao.putCanonical(notification)
        dao.putCanonical(first)

        val coordinator = CallStateCoordinator(IdleCallSource(), emit = {})
        coordinator.start()
        coordinator.injectDebugState(CallFrameworkState.RINGING)
        val memorySession = assertNotNull(coordinator.debugState().sessionId)
        val persistedOrder = mutableListOf<String>()
        val memoryDuringPersistence = mutableListOf<String?>()
        val sink = DaoCallRecoverySink(dao) { event ->
            persistedOrder += "call:${event.callSessionId}"
            memoryDuringPersistence += coordinator.debugState().sessionId
        }
        val terminalizer = ActiveCallTerminalizer(
            DaoActiveCallRecoveryStore(dao),
            CallStatePersister(sink, sink),
        )
        var durableBeforeCompletion = false
        var finalized = false

        val result = gracefulRoomShutdown(
            coordinator = coordinator,
            terminalizer = terminalizer,
            beforeCompletion = {
                durableBeforeCompletion =
                    dao.canonical(first.canonId)?.state == "CANCELLED" &&
                        dao.canonical(second.canonId)?.state == "CANCELLED" &&
                        dao.outboundMessage("recovery-3")?.state == "NEW" &&
                        dao.outboundMessage("recovery-9")?.state == "NEW"
            },
            finalize = { finalized = true },
        )

        assertSame(GracefulCallShutdownResult.Completed, result)
        assertTrue(durableBeforeCompletion)
        assertTrue(finalized)
        assertEquals(listOf(first.canonId, second.canonId), persistedOrder)
        assertEquals(listOf<String?>(memorySession, memorySession), memoryDuringPersistence)
        assertEquals(
            listOf(
                CallStateEvent(sessionId(first), "idle", CallDirection.INCOMING, 3),
                CallStateEvent(sessionId(second), "idle", CallDirection.OUTGOING, 9),
            ),
            sink.events,
        )
        assertEquals(3, dao.canonical(first.canonId)?.latestSequence)
        assertEquals(9, dao.canonical(second.canonId)?.latestSequence)
        assertNull(dao.canonical(first.canonId)?.desiredPayloadJson)
        assertNull(dao.canonical(second.canonId)?.desiredPayloadJson)
        assertEquals(listOf("recovery-3", "recovery-9"), activeMessageIds())
        assertEquals("call.state", dao.outboundMessage("recovery-3")?.eventType)
        assertEquals("call.state", dao.outboundMessage("recovery-9")?.eventType)
        assertEquals(remote, dao.canonical(remote.canonId))
        assertEquals(notification, dao.canonical(notification.canonId))
        assertFalse(coordinator.debugState().registered)
        assertNull(coordinator.debugState().sessionId)
        assertEquals(0, coordinator.debugState().pendingCount)
    }

    @Test
    fun gracefulShutdownOutboxConflictRollsBackAndReturnsFailedWithoutFinalizing() = runBlocking {
        val local = callCanonical(sequence = 8)
        val conflict = outbound("recovery-9", sequence = null, eventType = "unpair")
        dao.putCanonical(local)
        dao.insertOutbound(conflict)
        val coordinator = CallStateCoordinator(IdleCallSource(), emit = {})
        coordinator.start()
        coordinator.injectDebugState(CallFrameworkState.RINGING)
        val retainedMemory = assertNotNull(coordinator.debugState().sessionId)
        val sink = DaoCallRecoverySink(dao)
        val terminalizer = ActiveCallTerminalizer(
            DaoActiveCallRecoveryStore(dao),
            CallStatePersister(sink, sink),
        )
        var finalized = false

        val result = gracefulRoomShutdown(
            coordinator = coordinator,
            terminalizer = terminalizer,
            finalize = { finalized = true },
        )

        assertEquals(GracefulCallShutdownResult.Failed(CALL_SHUTDOWN_FAILED), result)
        assertFalse(finalized)
        assertEquals(local, dao.canonical(local.canonId))
        assertNull(dao.nextCaptureSequence(local.canonId))
        assertEquals(conflict, dao.outboundMessage(conflict.msgId))
        assertEquals(listOf(conflict.msgId), activeMessageIds())
        assertEquals(3, sink.events.size)
        assertEquals(retainedMemory, coordinator.debugState().sessionId)
    }

    @Test
    fun repeatedGracefulShutdownAfterSuccessDoesNotCreateAnotherIdleOutboxRow() = runBlocking {
        val local = callCanonical(sequence = 8)
        dao.putCanonical(local)
        val coordinator = CallStateCoordinator(IdleCallSource(), emit = {})
        coordinator.start()
        val sink = DaoCallRecoverySink(dao)
        val terminalizer = ActiveCallTerminalizer(
            DaoActiveCallRecoveryStore(dao),
            CallStatePersister(sink, sink),
        )

        assertSame(
            GracefulCallShutdownResult.Completed,
            gracefulRoomShutdown(coordinator, terminalizer),
        )
        assertSame(
            GracefulCallShutdownResult.Completed,
            gracefulRoomShutdown(coordinator, terminalizer),
        )

        assertEquals(9, dao.canonical(local.canonId)?.latestSequence)
        assertEquals(listOf("recovery-9"), activeMessageIds())
        assertEquals(1, sink.events.size)
    }

    @Test
    fun gracefulShutdownTreatsRemoteOwnershipFlipAsIdempotentWithoutMutation() = runBlocking {
        val staleLocal = callCanonical(sequence = 8)
        val remote = staleLocal.copy(originDevice = "remote-device", updatedAt = 1_001)
        dao.putCanonical(staleLocal)
        val store = object : co.twinotify.core.call.ActiveCallRecoveryStore {
            override suspend fun activeLocalCalls(originDevice: String) = listOf(staleLocal)
            override suspend fun canonical(canonId: String) = dao.canonical(canonId)
            override suspend fun nextSequence(canonId: String): Long {
                dao.putCanonical(remote)
                return 9L
            }
        }
        val sink = DaoCallRecoverySink(dao)
        val coordinator = CallStateCoordinator(IdleCallSource(), emit = {})
        var finalized = false

        val result = gracefulRoomShutdown(
            coordinator = coordinator,
            terminalizer = ActiveCallTerminalizer(store, CallStatePersister(sink, sink)),
            finalize = { finalized = true },
        )

        assertSame(GracefulCallShutdownResult.Completed, result)
        assertTrue(finalized)
        assertEquals(remote, dao.canonical(staleLocal.canonId))
        assertNull(dao.nextCaptureSequence(staleLocal.canonId))
        assertTrue(activeMessageIds().isEmpty())
        assertEquals(listOf(ORIGIN), sink.expectedLocalOrigins)
    }

    @Test
    fun sendableKeepsRecoveryIdleAheadOfFreshRingingDespiteCompetingTimestampIndex() = runBlocking {
        val createdAt = 500L
        db.openHelper.writableDatabase.execSQL(
            "CREATE INDEX competing_sendable_tiebreak ON outbound_message(createdAt, msgId DESC)",
        )
        dao.insertOutbound(
            outbound("a-recovery-idle", sequence = 9, eventType = "call.state").copy(
                canonId = CALL_CANON_ID,
                createdAt = createdAt,
                nextAttemptAt = createdAt,
            ),
        )
        dao.insertOutbound(
            outbound("z-fresh-ringing", sequence = 10, eventType = "call.state").copy(
                canonId = CALL_CANON_ID,
                createdAt = createdAt,
                nextAttemptAt = createdAt,
            ),
        )

        assertEquals(
            listOf("a-recovery-idle", "z-fresh-ringing"),
            dao.sendable(now = createdAt, limit = 10).map { it.msgId },
        )
    }

    @Test
    fun inboundJournalAtomicallyDistinguishesSameDigestDuplicateFromIdConflict() = runBlocking {
        val first = inbound(msgId = "inbound-1", digest = "digest-a")

        assertEquals(InboundDesiredCommitResult.Committed, dao.commitInboundDesired(first, desired = null))
        assertEquals(
            InboundDesiredCommitResult.Duplicate(outcome = "PENDING_PLATFORM", receiptMsgId = null),
            dao.commitInboundDesired(first, desired = null),
        )
        assertEquals(
            InboundDesiredCommitResult.IdConflict(existingSha256 = "digest-a"),
            dao.commitInboundDesired(first.copy(envelopeSha256 = "digest-b"), desired = null),
        )
        assertEquals("digest-a", dao.inbound("inbound-1")?.envelopeSha256)
    }

    @Test
    fun terminalizingSupersededCanonicalGroupRejectsEveryOlderPendingInboundWithReceipts() = runBlocking {
        dao.putCanonical(canonical(sequence = 3, state = "ACTIVE", payload = "payload"))
        val oldOne = inbound("old-one", "digest-one").copy(
            eventType = "notif.post", canonId = CANON_ID, sequence = 1, committedAt = 10,
        )
        val oldTwo = inbound("old-two", "digest-two").copy(
            eventType = "notif.update", canonId = CANON_ID, sequence = 2, committedAt = 11,
        )
        dao.commitInboundDesired(oldOne, desired = null)
        dao.commitInboundDesired(oldTwo, desired = null)
        val receiptOne = outbound("receipt-one", null, "peer.receipt").copy(
            canonId = null, envelopeSha256 = "receipt-digest-one", requiresPeerReceipt = false,
        )
        val receiptTwo = outbound("receipt-two", null, "peer.receipt").copy(
            canonId = null, envelopeSha256 = "receipt-digest-two", requiresPeerReceipt = false,
        )

        assertTrue(dao.terminalizeSupersededInbound(
            canonId = CANON_ID,
            sequence = 3,
            supersession = SupersessionBundle(listOf(
                SupersessionEntry("old-one", "digest-one", receiptOne),
                SupersessionEntry("old-two", "digest-two", receiptTwo),
            )),
            terminalAt = 20,
        ))

        assertEquals("REJECTED", dao.inbound("old-one")?.outcome)
        assertEquals("REJECTED", dao.inbound("old-two")?.outcome)
        assertEquals("receipt-one", dao.inbound("old-one")?.receiptMsgId)
        assertEquals("receipt-two", dao.inbound("old-two")?.receiptMsgId)
        assertEquals("NEW", dao.outboundMessage("receipt-one")?.state)
        assertEquals("NEW", dao.outboundMessage("receipt-two")?.state)
    }

    @Test
    fun newerDesiredAtomicallyTerminalizesOlderInboundAndRejectsReceiptIdConflictWithoutMutation() = runBlocking {
        dao.putCanonical(canonical(sequence = 2, state = "ACTIVE", payload = "old"))
        val old = inbound("old", "digest-old").copy(
            eventType = "notif.update", canonId = CANON_ID, sequence = 1, committedAt = 10,
        )
        dao.commitInboundDesired(old, desired = null)
        val incoming = inbound("new", "digest-new").copy(
            eventType = "notif.update", canonId = CANON_ID, sequence = 3, committedAt = 20,
        )
        val receipt = outbound("receipt", null, "peer.receipt").copy(
            canonId = null, envelopeSha256 = "receipt-digest", requiresPeerReceipt = false,
        )
        val desired = canonical(sequence = 3, state = "ACTIVE", payload = "new", updatedAt = 20)

        assertEquals(
            InboundDesiredCommitResult.Committed,
            dao.commitInboundDesired(incoming, desired, SupersessionBundle(listOf(
                SupersessionEntry(old.msgId, old.envelopeSha256, receipt),
            ))),
        )
        assertEquals("REJECTED", dao.inbound(old.msgId)?.outcome)
        assertEquals("PENDING_PLATFORM", dao.inbound(incoming.msgId)?.outcome)
        assertEquals(3, dao.canonical(CANON_ID)?.latestSequence)

        val next = inbound("next", "digest-next").copy(
            eventType = "notif.update", canonId = CANON_ID, sequence = 4, committedAt = 30,
        )
        val conflictingReceipt = receipt.copy(envelopeSha256 = "different-receipt-digest")
        assertEquals(
            InboundDesiredCommitResult.ReceiptConflict("receipt-digest"),
            dao.commitInboundDesired(next, canonical(sequence = 4, state = "ACTIVE", payload = "next", updatedAt = 30),
                SupersessionBundle(listOf(SupersessionEntry(incoming.msgId, incoming.envelopeSha256, conflictingReceipt)))),
        )
        assertEquals("PENDING_PLATFORM", dao.inbound(incoming.msgId)?.outcome)
        assertNull(dao.inbound(next.msgId))
        assertEquals(3, dao.canonical(CANON_ID)?.latestSequence)
    }

    @Test
    fun newerDesiredWithMissingSupersessionBundleRetainsSenderCustodyAndLeavesStateUntouched() = runBlocking {
        dao.putCanonical(canonical(sequence = 2, state = "ACTIVE", payload = "old"))
        val old = inbound("old-missing", "digest-old-missing").copy(
            eventType = "notif.update", canonId = CANON_ID, sequence = 1, committedAt = 10,
        )
        dao.commitInboundDesired(old, desired = null)
        val incoming = inbound("new-missing", "digest-new-missing").copy(
            eventType = "notif.update", canonId = CANON_ID, sequence = 3, committedAt = 20,
        )

        assertEquals(
            InboundDesiredCommitResult.SupersessionUnavailable,
            dao.commitInboundDesired(incoming, canonical(3, "ACTIVE", "new", 20)),
        )
        assertEquals("PENDING_PLATFORM", dao.inbound(old.msgId)?.outcome)
        assertNull(dao.inbound(incoming.msgId))
        assertEquals(2, dao.canonical(CANON_ID)?.latestSequence)
    }

    @Test
    fun notificationRejectionJournalRetainsTerminalReceiptForStaleNotification() = runBlocking {
        val rejected = inbound("stale", "digest-stale").copy(
            eventType = "notif.cancel", canonId = CANON_ID, sequence = 1, outcome = "REJECTED",
            receiptMsgId = "stale-receipt",
        )
        val receipt = outbound("stale-receipt", null, "peer.receipt").copy(
            canonId = null, envelopeSha256 = "stale-receipt-digest", requiresPeerReceipt = false,
        )

        assertEquals(co.twinotify.core.service.CallRejectionCommitResult.Committed, dao.commitInboundRejection(rejected, receipt))
        assertEquals("REJECTED", dao.inbound("stale")?.outcome)
        assertEquals("stale-receipt", dao.inbound("stale")?.receiptMsgId)
        assertEquals("NEW", dao.outboundMessage("stale-receipt")?.state)
    }

    @Test
    fun newerDesiredReplacesOnlyItsPrivateUnactivatedAppliedReceipt() = runBlocking {
        dao.putCanonical(canonical(sequence = 2, state = "ACTIVE", payload = "old"))
        val old = inbound("old-staged", "digest-old-staged").copy(
            eventType = "notif.post", canonId = CANON_ID, sequence = 1, committedAt = 10,
        )
        dao.commitInboundDesired(old, desired = null)
        val staged = outbound("staged-applied", null, "peer.receipt").copy(
            canonId = null, state = "PENDING_PLATFORM", requiresPeerReceipt = false,
        )
        dao.insertOutbound(staged)
        db.openHelper.writableDatabase.execSQL(
            "UPDATE inbound_message SET receiptMsgId='staged-applied' WHERE msgId='old-staged'",
        )
        val incoming = inbound("new-staged", "digest-new-staged").copy(
            eventType = "notif.update", canonId = CANON_ID, sequence = 3, committedAt = 20,
        )
        val replacement = outbound("replacement-applied", null, "peer.receipt").copy(
            canonId = null, requiresPeerReceipt = false,
        )

        assertEquals(
            InboundDesiredCommitResult.Committed,
            dao.commitInboundDesired(incoming, canonical(3, "ACTIVE", "new", 20), SupersessionBundle(listOf(
                SupersessionEntry(old.msgId, old.envelopeSha256, replacement),
            ))),
        )
        assertNull(dao.outboundMessage("staged-applied"))
        assertEquals("NEW", dao.outboundMessage("replacement-applied")?.state)
        assertEquals("replacement-applied", dao.inbound("old-staged")?.receiptMsgId)
    }

    @Test
    fun retentionRemovesTerminalHistoryButKeepsPendingMaterialization() = runBlocking {
        dao.putCanonical(canonical(sequence = 2, state = "CANCELLED", payload = null, updatedAt = 1))
        dao.commitInboundDesired(
            inbound("pending-old", "digest-pending").copy(
                canonId = CANON_ID,
                sequence = 2,
                outcome = "PENDING_PLATFORM",
                committedAt = 1,
            ),
            desired = null,
        )
        dao.commitInboundDesired(
            inbound("applied-old", "digest-applied").copy(
                outcome = "APPLIED",
                committedAt = 1,
            ),
            desired = null,
        )
        dao.commitInboundDesired(
            inbound("rejected-old", "digest-rejected").copy(
                outcome = "REJECTED",
                committedAt = 1,
            ),
            desired = null,
        )
        db.openHelper.writableDatabase.execSQL(
            "INSERT INTO activity_event(eventId,msgId,packageName,eventType,status,byteSize,occurredAt,detailCode) " +
                "VALUES('activity-old','applied-old',NULL,'peer.receipt','applied',1,1,NULL)",
        )

        dao.sweepRetention(
            now = 10 * 60 * 1_000L + 1_000,
            activityRetentionMs = 100,
            tombstoneRetentionMs = 100,
        )

        assertNull(dao.inbound("applied-old"))
        assertNull(dao.inbound("rejected-old"))
        assertTrue(dao.inbound("pending-old") != null)
        assertTrue(dao.canonical(CANON_ID) != null)
        db.openHelper.readableDatabase.query("SELECT * FROM activity_event WHERE eventId='activity-old'").use {
            assertTrue(!it.moveToFirst())
        }
    }

    @Test
    fun mirrorActionInvocationAndInvokeOutboxCommitAtomically() = runBlocking {
        val invocation = actionInvocation()
        val invoke = controlOutbound("action-invoke", "notif.action.invoke")

        assertEquals(
            ActionInvocationOutboxCommitResult.Committed,
            dao.commitActionInvocationAndOutbound(invocation, invoke),
        )

        assertEquals(invocation, dao.actionInvocation(INVOCATION_ID))
        assertEquals(invoke, dao.outboundMessage("action-invoke"))
    }

    @Test
    fun mirrorActionInvocationRollsBackWhenInvokeOutboxConflicts() = runBlocking {
        dao.insertOutbound(controlOutbound("action-invoke", "notif.action.result"))

        assertEquals(
            ActionInvocationOutboxCommitResult.OutboundConflict,
            dao.commitActionInvocationAndOutbound(
                actionInvocation(),
                controlOutbound("action-invoke", "notif.action.invoke"),
            ),
        )

        assertNull(dao.actionInvocation(INVOCATION_ID))
    }

    @Test
    fun originActionCompletionAndResultOutboxCommitAtomically() = runBlocking {
        dao.insertActionExecution(actionExecution())
        val result = controlOutbound("action-result", "notif.action.result")

        assertEquals(
            ActionCompletionOutboxCommitResult.Committed,
            dao.completeActionExecutionAndEnqueue(
                invocationId = INVOCATION_ID,
                status = "dispatched",
                now = 2_000,
                result = result,
            ),
        )

        assertEquals("dispatched", dao.actionExecution(INVOCATION_ID)?.resultStatus)
        assertEquals(result, dao.outboundMessage("action-result"))
        assertEquals(
            ActionCompletionOutboxCommitResult.AlreadyCompleted("dispatched"),
            dao.completeActionExecutionAndEnqueue(
                invocationId = INVOCATION_ID,
                status = "failed",
                now = 3_000,
                result = controlOutbound("contradictory-result", "notif.action.result"),
            ),
        )
        assertNull(dao.outboundMessage("contradictory-result"))
    }

    private fun activeMessageIds(): List<String> = db.openHelper.readableDatabase.query(
        "SELECT msgId FROM outbound_message ORDER BY msgId",
    ).use { cursor ->
        buildList {
            while (cursor.moveToNext()) add(cursor.getString(0))
        }
    }

    private fun canonical(
        sequence: Long,
        state: String,
        payload: String?,
        updatedAt: Long = 1_000,
    ) =
        CanonicalNotificationState(
            canonId = CANON_ID,
            originDevice = ORIGIN,
            latestSequence = sequence,
            state = state,
            desiredPayloadJson = payload,
            materializedSequence = sequence,
            sourceNotificationKey = null,
            mirrorLocalId = 12,
            mirrorLocalTag = "mirror",
            peerCancelPending = false,
            updatedAt = updatedAt,
        )

    private fun callCanonical(
        canonId: String = CALL_CANON_ID,
        origin: String = ORIGIN,
        sequence: Long,
        state: String = "ACTIVE",
        updatedAt: Long = 1_000,
    ) = CanonicalNotificationState(
        canonId = canonId,
        originDevice = origin,
        latestSequence = sequence,
        state = state,
        desiredPayloadJson = if (state == "ACTIVE") {
            """{"call_session_id":"${canonId.removePrefix("call:")}","state":"ringing","direction":"incoming"}"""
        } else {
            null
        },
        materializedSequence = sequence,
        sourceNotificationKey = null,
        mirrorLocalId = null,
        mirrorLocalTag = null,
        peerCancelPending = false,
        updatedAt = updatedAt,
    )

    private fun sessionId(state: CanonicalNotificationState): String = state.canonId.removePrefix("call:")

    private fun callRecoveryOutbound(msgId: String, canonId: String, sequence: Long) = OutboundMessage(
        msgId = msgId,
        canonId = canonId,
        sequence = sequence,
        eventType = "call.state",
        protocolVersion = 2,
        envelopeJson = "{}",
        envelopeSha256 = "sha-$msgId",
        byteSize = 2,
        createdAt = sequence,
        expiresAt = 100_000,
        custodyAcceptedAt = null,
        custodyRoute = null,
        attempts = 0,
        nextAttemptAt = sequence,
        state = "NEW",
        lastError = null,
        requiresPeerReceipt = true,
    )

    private enum class StaleMode { ACTIVE, CANCELLED }

    private class IdleCallSource : CallStateSource {
        override fun capabilities() = CallSourceCapabilities(
            supported = true,
            permissionGranted = true,
        )

        override fun register(listener: (CallFrameworkState) -> Unit): AutoCloseable =
            AutoCloseable { }
    }

    private class DaoCallRecoverySink(
        private val dao: ReliableDeliveryDao,
        private val staleMode: StaleMode? = null,
        private val onPersist: (CallStateEvent) -> Unit = {},
    ) : co.twinotify.core.call.CallStateSink, co.twinotify.core.call.CallRecoveryStateSink {
        val events = mutableListOf<CallStateEvent>()
        val expectedLocalOrigins = mutableListOf<String>()
        private var staleInjected = false

        override suspend fun persist(event: CallStateEvent): CallStatePersistResult {
            return persistForRecovery(event, ORIGIN)
        }

        override suspend fun persistForRecovery(
            event: CallStateEvent,
            expectedLocalOrigin: String,
        ): CallStatePersistResult {
            events += event
            expectedLocalOrigins += expectedLocalOrigin
            onPersist(event)
            val canonId = "call:${event.callSessionId}"
            val current = requireNotNull(dao.canonical(canonId))
            if (!staleInjected && staleMode != null) {
                staleInjected = true
                val concurrentState = if (staleMode == StaleMode.ACTIVE) "ACTIVE" else "CANCELLED"
                val concurrentId = if (staleMode == StaleMode.ACTIVE) {
                    "concurrent-active-${event.sequence}"
                } else {
                    "concurrent-cancel-${event.sequence}"
                }
                assertEquals(
                    OutboundStateCommitResult.Committed(compacted = 0),
                    dao.commitCapturedState(
                        current.copy(
                            latestSequence = event.sequence,
                            state = concurrentState,
                            desiredPayloadJson = if (concurrentState == "ACTIVE") current.desiredPayloadJson else null,
                        ),
                        recoveryOutbound(concurrentId, canonId, event.sequence),
                    ),
                )
            }
            val desired = current.copy(
                latestSequence = event.sequence,
                state = "CANCELLED",
                desiredPayloadJson = null,
            )
            return when (val result = dao.commitRecoveredCallState(
                desired,
                recoveryOutbound("recovery-${event.sequence}", canonId, event.sequence),
                expectedLocalOrigin,
            )) {
                is CallRecoveryCommitResult.Committed -> CallStatePersistResult.Persisted(event.sequence, "recovery-${event.sequence}")
                is CallRecoveryCommitResult.Stale -> CallStatePersistResult.Stale(result.latestSequence)
                CallRecoveryCommitResult.OwnershipLost -> CallStatePersistResult.OwnershipLost
                CallRecoveryCommitResult.NotStateEvent -> error("call recovery only writes call.state")
            }
        }

        private fun recoveryOutbound(msgId: String, canonId: String, sequence: Long) = OutboundMessage(
            msgId = msgId,
            canonId = canonId,
            sequence = sequence,
            eventType = "call.state",
            protocolVersion = 2,
            envelopeJson = "{}",
            envelopeSha256 = "sha-$msgId",
            byteSize = 2,
            createdAt = sequence,
            expiresAt = 100_000,
            custodyAcceptedAt = null,
            custodyRoute = null,
            attempts = 0,
            nextAttemptAt = sequence,
            state = "NEW",
            lastError = null,
            requiresPeerReceipt = true,
        )

    }

    private fun outbound(msgId: String, sequence: Long?, eventType: String) = OutboundMessage(
        msgId = msgId,
        canonId = CANON_ID,
        sequence = sequence,
        eventType = eventType,
        protocolVersion = 2,
        envelopeJson = "{}",
        envelopeSha256 = "sha-$msgId",
        byteSize = 2,
        createdAt = sequence ?: 0L,
        expiresAt = 100_000,
        custodyAcceptedAt = null,
        custodyRoute = null,
        attempts = 0,
        nextAttemptAt = sequence ?: 0L,
        state = "NEW",
        lastError = null,
        requiresPeerReceipt = true,
    )

    private fun queueRow(msgId: String, eventType: String, byteSize: Long) = OutboundMessage(
        msgId = msgId,
        canonId = if (eventType.startsWith("notif.") || eventType == "call.state") CANON_ID else null,
        sequence = if (eventType in setOf("notif.post", "notif.update", "notif.cancel", "call.state")) 1 else null,
        eventType = eventType,
        protocolVersion = 2,
        envelopeJson = "{}",
        envelopeSha256 = "sha-$msgId",
        byteSize = byteSize,
        createdAt = 1_000,
        expiresAt = 100_000,
        custodyAcceptedAt = null,
        custodyRoute = null,
        attempts = 0,
        nextAttemptAt = 1_000,
        state = "NEW",
        lastError = null,
        requiresPeerReceipt = eventType !in setOf("peer.receipt", "state.digest", "state.snapshot.begin", "state.snapshot.item", "state.snapshot.end", "unpair"),
    )

    private fun receiptBackedInbound(msgId: String, digest: String, receiptMsgId: String) = InboundMessage(
        msgId = msgId,
        originDevice = ORIGIN,
        envelopeSha256 = digest,
        eventType = "lan.bootstrap",
        canonId = null,
        sequence = null,
        outcome = "APPLIED",
        committedAt = 1_000,
        appliedAt = 1_000,
        receiptMsgId = receiptMsgId,
        relayAckState = "NONE",
    )

    private fun controlOutbound(msgId: String, eventType: String) = outbound(
        msgId = msgId,
        sequence = null,
        eventType = eventType,
    ).copy(
        canonId = null,
        requiresPeerReceipt = false,
    )

    private fun actionInvocation() = ActionInvocation(
        invocationId = INVOCATION_ID,
        canonId = CANON_ID,
        actionId = ACTION_ID,
        notificationSequence = 7,
        replyText = "private reply",
        state = "PENDING",
        createdAt = 1_000,
        expiresAt = 121_000,
        updatedAt = 1_000,
    )

    private fun actionExecution() = ActionExecution(
        invocationId = INVOCATION_ID,
        canonId = CANON_ID,
        actionId = ACTION_ID,
        state = "CLAIMED",
        resultStatus = null,
        claimedAt = 1_000,
        completedAt = null,
    )

    private fun inbound(msgId: String, digest: String) = InboundMessage(
        msgId = msgId,
        originDevice = ORIGIN,
        envelopeSha256 = digest,
        eventType = "peer.receipt",
        canonId = null,
        sequence = null,
        outcome = "PENDING_PLATFORM",
        committedAt = 1_000,
        appliedAt = null,
        receiptMsgId = null,
        relayAckState = "NONE",
    )

    private fun snapshotPayload(canonId: String) =
        "{\"type\":\"notif.post\",\"canon_id\":\"$canonId\",\"package_name\":\"pkg\",\"id\":1,\"visibility\":\"private\"}"

    private fun snapshotDigest(canonId: String, sequence: Long): String =
        java.security.MessageDigest.getInstance("SHA-256")
            .digest("$canonId\u0000$sequence\u0000ACTIVE".toByteArray())
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }

    private companion object {
        const val CANON_ID = "canon-a"
        const val CALL_CANON_ID = "call:11111111-1111-4111-8111-111111111111"
        const val ORIGIN = "dev-a"
        const val INVOCATION_ID = "22222222-2222-4222-8222-222222222222"
        const val ACTION_ID = "33333333-3333-4333-8333-333333333333"
    }
}
