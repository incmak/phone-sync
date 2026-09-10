package co.twinotify.core.storage

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import kotlin.test.*
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MultiPeerStorageTest {
    private lateinit var db: NotificationDbImpl
    private lateinit var dao: ReliableDeliveryDao
    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), NotificationDbImpl::class.java).build()
        dao = db.reliableDeliveryDao()
    }
    @After fun close() = db.close()

    @Test fun importIsAtomicIdempotentAndPreservesCustodyBytes(): Unit = runBlocking {
        val original = row("message", LEGACY_PEER_LINK_ID, 7).copy(state = "ACCEPTED", custodyAcceptedAt = 1234, custodyRoute = "RELAY", relayCustodyState = "ACCEPTED")
        dao.insertOutbound(original)
        dao.insertInbound(inbound("inbound", LEGACY_PEER_LINK_ID, "phone"))
        val peer = link("phone-link", "phone")
        db.peerLinkDao().importLegacy("local", peer)
        db.peerLinkDao().importLegacy("local", peer)
        assertEquals(original.copy(peerLinkId = peer.peerLinkId), dao.outboundMessage(original.msgId))
        assertNotNull(dao.inbound("inbound", peer.peerLinkId))
        assertNull(dao.inbound("inbound", LEGACY_PEER_LINK_ID))
        assertEquals(1, db.peerLinkDao().all().size)
        assertFailsWith<IllegalStateException> { db.peerLinkDao().importLegacy("rotated-local", peer) }
    }

    @Test fun interruptedImportRollsBackEveryOwnershipChange(): Unit = runBlocking {
        dao.insertOutbound(row("message", LEGACY_PEER_LINK_ID, 1))
        // Missing public membership cannot be guessed from queued ciphertext.
        assertFailsWith<IllegalStateException> { db.peerLinkDao().importLegacy("local", null) }
        assertNull(db.peerLinkDao().importState())
        assertTrue(db.peerLinkDao().all().isEmpty())
        assertEquals(LEGACY_PEER_LINK_ID, dao.outboundMessage("message")?.peerLinkId)
        db.peerLinkDao().importLegacy("local", link("phone-link", "phone"))
        assertEquals("phone-link", dao.outboundMessage("message")?.peerLinkId)
    }

    @Test fun fanoutCommitsOneSequenceAndBothRecipientsAndScopesReceipts(): Unit = runBlocking {
        preparePeers()
        val rows = listOf(row("phone-message", "phone-link", 1), row("mac-message", "mac-link", 1))
        assertIs<OutboundStateCommitResult.Committed>(dao.commitCapturedFanout(state(1), rows))
        assertEquals(2L, dao.nextCaptureSequenceForEvent("canon"))
        assertEquals(listOf("phone-message"), dao.sendable(1000, 32, "phone-link").map { it.msgId })
        assertEquals(listOf("mac-message"), dao.sendable(1000, 32, "mac-link").map { it.msgId })
        assertSame(RelayReceiptResult.Missing, dao.applyPeerReceipt("phone-message", "phone-message-digest", "applied", null, 1001, peerLinkId = "mac-link"))
        assertNotNull(dao.outboundMessage("phone-message"))
        assertSame(RelayReceiptResult.Deleted, dao.applyPeerReceipt("phone-message", "phone-message-digest", "applied", null, 1001, peerLinkId = "phone-link"))
        assertNotNull(dao.outboundMessage("mac-message"))
        // Updates compact only each recipient's own undelivered state.
        assertIs<OutboundStateCommitResult.Committed>(dao.commitCapturedFanout(state(2), listOf(row("phone-2", "phone-link", 2), row("mac-2", "mac-link", 2))))
        assertNull(dao.outboundMessage("mac-message"))
        assertEquals(2, dao.activeOutboundCount())
    }

    @Test fun capacityFailureForSecondRecipientRollsBackFirstAndCanonicalSequence(): Unit = runBlocking {
        preparePeers()
        // Saturate the second recipient's own share: a blocker on the first link no longer
        // starves the second now that each peer is admitted against its own budget.
        dao.insertOutbound(row("blocker", "mac-link", 1).copy(canonId = "other", byteSize = MAX_OUTBOUND_BYTES_PER_PEER - 1))
        assertFailsWith<OutboundCapacityException> {
            dao.commitCapturedFanout(state(1), listOf(row("phone-message", "phone-link", 1), row("mac-message", "mac-link", 1)))
        }
        assertNull(dao.canonical("canon"))
        assertNull(dao.nextCaptureSequence("canon"))
        assertNull(dao.outboundMessage("phone-message"))
        assertNull(dao.outboundMessage("mac-message"))
        assertNotNull(dao.outboundMessage("blocker"))
    }

    @Test fun inboundIdsAndRelayAcksAreIndependentPerLink(): Unit = runBlocking {
        preparePeers()
        dao.insertInbound(inbound("same-id", "phone-link", "phone"))
        dao.insertInbound(inbound("same-id", "mac-link", "mac"))
        assertEquals(1, dao.markRelayAckSent("same-id", "digest", "phone-link"))
        assertEquals("SENT", dao.inbound("same-id", "phone-link")?.relayAckState)
        assertEquals("READY", dao.inbound("same-id", "mac-link")?.relayAckState)
        assertEquals(1, dao.readyRelayAcks(32, "mac-link").size)
        db.peerLinkDao().beginRemoval("phone-link")
        assertFailsWith<IllegalStateException> { dao.insertOutbound(row("late", "phone-link", 2)) }
        assertNotNull(dao.inbound("same-id", "mac-link"))
    }

    @Test fun sourceDismissalStagesEveryRecipientUntilPlatformCompletionAndDuplicateIsInert(): Unit = runBlocking {
        preparePeers()
        dao.commitCapturedFanout(state(1), listOf(row("phone-post", "phone-link", 1), row("mac-post", "mac-link", 1)))
        val request = cancelRequest()
        val cancels = stagedCancels()
        val desired = state(3).copy(state = "CANCELLED", desiredPayloadJson = null, materializedSequence = 1, peerCancelPending = true)
        assertIs<InboundDesiredCommitResult.Committed>(dao.commitOriginCancelRequest(request, 2, 1, "local", desired, cancels))
        assertEquals(4L, dao.nextCaptureSequenceForEvent("canon"))
        assertTrue(dao.sendable(1000, 32, "phone-link").isEmpty())
        assertTrue(dao.sendable(1000, 32, "mac-link").isEmpty())
        assertEquals("PENDING_PLATFORM", dao.outboundMessage("mac-cancel")?.state)
        assertIs<InboundDesiredCommitResult.Duplicate>(dao.commitOriginCancelRequest(request, 2, 1, "local", desired, cancels))
        assertEquals(2, dao.activeOutboundCount())
        val receipt = row("receipt", "phone-link", 3).copy(eventType = "peer.receipt", canonId = null, sequence = null, requiresPeerReceipt = false)
        assertIs<MaterializationReceiptResult.Prepared>(dao.prepareMaterializationReceipt("canon", 3, receipt))
        assertSame(MaterializationResult.Completed, dao.completeMaterialization("canon", 3, 1000, receipt))
        assertEquals(setOf("phone-cancel", "receipt"), dao.sendable(1000, 32, "phone-link").map { it.msgId }.toSet())
        assertEquals(listOf("mac-cancel"), dao.sendable(1000, 32, "mac-link").map { it.msgId })
        assertEquals("APPLIED", dao.inbound("request", "phone-link")?.outcome)
    }

    @Test fun sourceCancelCapacityFailureRollsBackJournalDesiredAndAllRecipients(): Unit = runBlocking {
        preparePeers()
        dao.commitCapturedFanout(state(1), listOf(row("phone-post", "phone-link", 1), row("mac-post", "mac-link", 1)))
        // Sized against the post-compaction state: the commit deletes each peer's obsolete post
        // before staging its cancel, so only this blocker still occupies mac-link's share and it
        // leaves room for less than one staged cancel.
        dao.insertOutbound(row("blocker", "mac-link", 1).copy(canonId = "other", byteSize = MAX_OUTBOUND_BYTES_PER_PEER - 2))
        assertFailsWith<OutboundCapacityException> {
            dao.commitOriginCancelRequest(cancelRequest(), 2, 1, "local",
                state(3).copy(state = "CANCELLED", desiredPayloadJson = null, materializedSequence = 1),
                stagedCancels().map { it.copy(byteSize = 3) })
        }
        assertNull(dao.inbound("request", "phone-link"))
        assertEquals("ACTIVE", dao.canonical("canon")?.state)
        assertEquals(2L, dao.nextCaptureSequenceForEvent("canon"))
        assertNotNull(dao.outboundMessage("phone-post"))
        assertNotNull(dao.outboundMessage("mac-post"))
        assertNull(dao.outboundMessage("phone-cancel"))
    }

    @Test fun newerSourceUpdateDiscardsUnreleasedCancelsAndCannotReleaseThemLater(): Unit = runBlocking {
        preparePeers()
        dao.commitCapturedFanout(state(1), listOf(row("phone-post", "phone-link", 1), row("mac-post", "mac-link", 1)))
        // Seed the already-staged platform operation to isolate supersession from receipt policy.
        dao.commitOriginCancelRequest(cancelRequest(), 2, 1, "local",
            state(3).copy(state = "CANCELLED", desiredPayloadJson = null, materializedSequence = 1), stagedCancels())
        dao.commitCapturedFanout(state(4), listOf(row("phone-new", "phone-link", 4), row("mac-new", "mac-link", 4)))
        assertNull(dao.outboundMessage("phone-cancel"))
        assertNull(dao.outboundMessage("mac-cancel"))
        assertSame(MaterializationResult.Superseded, dao.completeMaterialization("canon", 3, 1000, null))
        assertEquals("ACTIVE", dao.canonical("canon")?.state)
    }

    @Test fun staleDismissalCannotCancelNewerSourceState(): Unit = runBlocking {
        preparePeers()
        dao.commitCapturedFanout(state(1), listOf(row("phone-post", "phone-link", 1), row("mac-post", "mac-link", 1)))
        assertIs<InboundDesiredCommitResult.Stale>(dao.commitOriginCancelRequest(cancelRequest(), 1, 1, "local",
            state(3).copy(state = "CANCELLED", desiredPayloadJson = null, materializedSequence = 1), stagedCancels()))
        assertNull(dao.inbound("request", "phone-link"))
        assertEquals("ACTIVE", dao.canonical("canon")?.state)
    }

    @Test fun removalDisablesOnlySelectedDrainerAndPurgesOnlyItsMirrorsAndJournal(): Unit = runBlocking {
        preparePeers()
        dao.commitCapturedFanout(state(1), listOf(row("phone-post", "phone-link", 1), row("mac-post", "mac-link", 1)))
        dao.putCanonical(state(1).copy(canonId = "phone-mirror", originDevice = "phone", peerLinkId = "phone-link", mirrorLocalId = 1, mirrorLocalTag = "phone"))
        dao.putCanonical(state(1).copy(canonId = "mac-mirror", originDevice = "mac", peerLinkId = "mac-link", mirrorLocalId = 2, mirrorLocalTag = "mac"))
        dao.insertInbound(inbound("same", "phone-link", "phone"))
        dao.insertInbound(inbound("same", "mac-link", "mac"))
        dao.beginPeerRemoval("phone-link", row("unpair", "phone-link", 1).copy(eventType = "unpair", canonId = null, sequence = null, requiresPeerReceipt = false))
        assertEquals(listOf("unpair"), dao.sendable(1000, 32, "phone-link").map { it.msgId })
        assertEquals(listOf("mac-post"), dao.sendable(1000, 32, "mac-link").map { it.msgId })
        dao.purgePeerDelivery("phone-link")
        assertNull(dao.canonical("phone-mirror"))
        assertNotNull(dao.canonical("mac-mirror"))
        assertEquals(state(1), dao.canonical("canon"))
        assertNull(dao.inbound("same", "phone-link"))
        assertNotNull(dao.inbound("same", "mac-link"))
        assertEquals(1, dao.deliveryQueueSnapshot("mac-link").pendingLocal)
        assertEquals(0, dao.deliveryQueueSnapshot("phone-link").totalActive)
        assertEquals("REMOVING", db.peerLinkDao().get("phone-link")?.lifecycle)
    }

    @Test fun snapshotAdmissionIsBoundedAndConflictingRetriesDoNotEraseStagedWork(): Unit = runBlocking {
        preparePeers()
        dao.beginSnapshot("repair", "phone", 1, 1, peerLinkId = "phone-link")
        val item = SnapshotStage("repair", "phone:notif", 1, "{}", 1, peerLinkId = "phone-link")
        dao.stageSnapshotItem(item, "phone")
        assertFailsWith<IllegalStateException> {
            dao.beginSnapshot("repair", "phone", 2, 2, peerLinkId = "phone-link")
        }
        assertFailsWith<IllegalStateException> { dao.stageSnapshotItem(item.copy(payloadJson = "{ }"), "phone") }
        assertEquals(item, dao.snapshotRows("repair", "phone-link").single { it.canonId == item.canonId })
        assertFailsWith<IllegalStateException> { dao.stageSnapshotItem(item.copy(canonId = "phone:other"), "phone") }
        repeat(3) { dao.beginSnapshot("other-$it", "mac", 0, 1, peerLinkId = "mac-link") }
        assertFailsWith<IllegalStateException> { dao.beginSnapshot("fifth", "mac", 0, 1, peerLinkId = "mac-link") }
        assertTrue(dao.snapshotRows("fifth", "mac-link").isEmpty())
    }

    @Test fun aSaturatedUserQueueStillAdmitsTheReceiptThatWouldDrainIt(): Unit = runBlocking {
        // The deadlock this pins: a peer receipt is what retires an outbound row, so refusing one
        // at the cap means the queue can never drain and every redelivery fails identically.
        preparePeers()
        dao.insertOutbound(row("blocker", "phone-link", 1).copy(byteSize = MAX_OUTBOUND_BYTES_PER_PEER))
        assertFailsWith<OutboundCapacityException> {
            dao.insertOutbound(row("more-user-content", "phone-link", 2).copy(byteSize = 1))
        }
        val receipt = row("receipt", "phone-link", 3)
            .copy(eventType = "peer.receipt", requiresPeerReceipt = false, byteSize = 1)
        dao.insertOutbound(receipt)
        assertEquals(receipt, dao.outboundMessage("receipt"))
    }

    @Test fun aPeerThatSaturatesItsShareCannotStarveAnother(): Unit = runBlocking {
        // A laptop asleep for a weekend used to consume the whole device budget, because nothing
        // releases a row until that peer confirms it, and every other pairing then queued behind it.
        preparePeers()
        dao.insertOutbound(row("mac-blocker", "mac-link", 1).copy(byteSize = MAX_OUTBOUND_BYTES_PER_PEER))
        assertFailsWith<OutboundCapacityException> {
            dao.insertOutbound(row("mac-more", "mac-link", 2).copy(byteSize = 1))
        }
        val unaffected = row("phone-message", "phone-link", 1).copy(byteSize = 1)
        dao.insertOutbound(unaffected)
        assertEquals(unaffected, dao.outboundMessage("phone-message"))
    }

    @Test fun theControlReserveIsACeilingAndNotAnExemption(): Unit = runBlocking {
        preparePeers()
        dao.insertOutbound(row("blocker", "phone-link", 1).copy(byteSize = MAX_OUTBOUND_BYTES_PER_PEER))
        // Control rows draw on bounded headroom above the user cap, so a runaway control writer
        // is still refused rather than growing the queue without limit.
        dao.insertOutbound(
            row("control-fills-reserve", "phone-link", 2)
                .copy(eventType = "peer.receipt", requiresPeerReceipt = false, byteSize = MAX_OUTBOUND_CONTROL_RESERVE_BYTES),
        )
        assertFailsWith<OutboundCapacityException> {
            dao.insertOutbound(
                row("control-overflow", "phone-link", 3)
                    .copy(eventType = "peer.receipt", requiresPeerReceipt = false, byteSize = 1),
            )
        }
    }

    private fun cancelRequest() = InboundMessage("request", "phone", "request-digest", "notif.cancel", "canon", 3,
        "PENDING_PLATFORM", 1, null, null, "NONE", peerLinkId = "phone-link")
    private fun stagedCancels() = listOf(row("phone-cancel", "phone-link", 3), row("mac-cancel", "mac-link", 3))
        .map { it.copy(eventType = "notif.cancel", state = "PENDING_PLATFORM") }

    private suspend fun preparePeers() {
        db.peerLinkDao().importLegacy("local", null)
        db.peerLinkDao().add(link("phone-link", "phone"))
        db.peerLinkDao().add(link("mac-link", "mac"))
    }
    private fun link(id: String, device: String) = PeerLink(id, device, ByteArray(32) { 1 }, ByteArray(32) { 2 }, device,
        "https://relay.example.com", "$id-pair", true, null, true, "ACTIVE", 1)
    private fun row(id: String, link: String, seq: Long) = OutboundMessage(id, "canon", seq, "notif.post", 2, "{}", "$id-digest",
        2, 1, 10000, null, null, 0, 1, "NEW", null, true, peerLinkId = link)
    private fun state(seq: Long) = CanonicalNotificationState("canon", "local", seq, "ACTIVE", "{}", seq, "source", null, null, false, 1)
    private fun inbound(id: String, link: String, origin: String) = InboundMessage(id, origin, "digest", "peer.receipt", null, null,
        "APPLIED", 1, 1, null, "READY", peerLinkId = link)
}
