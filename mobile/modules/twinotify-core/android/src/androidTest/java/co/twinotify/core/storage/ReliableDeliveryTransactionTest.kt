package co.twinotify.core.storage

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
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
    fun v2MirrorCancelEchoResolvesAndConsumesPersistedCanonicalTombstone() = runBlocking {
        dao.putCanonical(
            canonical(sequence = 3, state = "CANCELLED", payload = null).copy(
                mirrorLocalTag = "mirror-stable",
                mirrorLocalId = 42,
                peerCancelPending = true,
            ),
        )

        assertEquals("canon-1", dao.canonicalForMirrorIdentity("mirror-stable", 42))
        assertEquals(1, dao.consumePeerCancel("canon-1"))
        assertEquals(0, dao.consumePeerCancel("canon-1"))
        assertEquals(false, dao.canonical("canon-1")!!.peerCancelPending)
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
        relayAcceptedAt = null,
        attempts = 0,
        nextAttemptAt = sequence ?: 0L,
        state = "NEW",
        lastError = null,
        requiresPeerReceipt = true,
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

    private companion object {
        const val CANON_ID = "canon-a"
        const val ORIGIN = "dev-a"
    }
}
