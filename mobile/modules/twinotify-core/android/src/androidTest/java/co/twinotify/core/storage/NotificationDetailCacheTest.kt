package co.twinotify.core.storage

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test

class NotificationDetailCacheTest {
    private lateinit var db: NotificationDbImpl
    private lateinit var dao: ReliableDeliveryDao

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        db = Room.inMemoryDatabaseBuilder(context, NotificationDbImpl::class.java)
            .allowMainThreadQueries()
            .build()
        dao = db.reliableDeliveryDao()
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun postCreatesOpaqueDetailAndUpdateReusesIt() = runBlocking {
        val firstPayload = payload("first")
        assertEquals(
            InboundDesiredCommitResult.Committed,
            dao.commitInboundDesired(inbound("notif.post", 1, 1_000), active(1, firstPayload, 1_000)),
        )

        val first = assertNotNull(dao.notificationDetailForCanon(CANON_ID))
        assertEquals(UUID.fromString(first.detailId).toString(), first.detailId)
        assertNotEquals(CANON_ID, first.detailId)
        assertEquals(firstPayload, first.payloadJson)
        assertEquals(1_000, first.receivedAt)
        assertNull(first.cancelledAt)
        assertEquals(MaterializationResult.Completed, dao.completeMaterialization(CANON_ID, 1, 1_100, null))

        val secondPayload = payload("second")
        assertEquals(
            InboundDesiredCommitResult.Committed,
            dao.commitInboundDesired(inbound("notif.update", 2, 2_000), active(2, secondPayload, 2_000)),
        )

        val updated = assertNotNull(dao.notificationDetailForCanon(CANON_ID))
        assertEquals(first.detailId, updated.detailId)
        assertEquals(secondPayload, updated.payloadJson)
        assertEquals(1_000, updated.receivedAt)
        assertEquals(2_000, updated.updatedAt)
        assertNull(updated.cancelledAt)
    }

    @Test
    fun cancelRetainsPayloadAndStampsCancelledAt() = runBlocking {
        val content = payload("keep me")
        dao.commitInboundDesired(inbound("notif.post", 1, 1_000), active(1, content, 1_000))
        assertEquals(MaterializationResult.Completed, dao.completeMaterialization(CANON_ID, 1, 1_100, null))

        assertEquals(
            InboundDesiredCommitResult.Committed,
            dao.commitInboundDesired(inbound("notif.cancel", 2, 2_000), cancelled(2, 2_000)),
        )

        assertNull(dao.canonical(CANON_ID)?.desiredPayloadJson)
        val cached = assertNotNull(dao.notificationDetailForCanon(CANON_ID))
        assertEquals(content, cached.payloadJson)
        assertEquals(2_000, cached.updatedAt)
        assertEquals(2_000, cached.cancelledAt)
    }

    @Test
    fun activeRowsSurviveAgeSweepAndCancelledRowsExpireAfterTenMinutes() = runBlocking {
        dao.putNotificationDetail(detail("active", "active-canon", cancelledAt = null, updatedAt = 1))
        dao.putNotificationDetail(detail("young", "young-canon", cancelledAt = 400_001, updatedAt = 400_001))
        dao.putNotificationDetail(detail("boundary", "boundary-canon", cancelledAt = 400_000, updatedAt = 400_000))
        dao.putNotificationDetail(detail("old", "old-canon", cancelledAt = 399_999, updatedAt = 399_999))

        dao.sweepNotificationDetailCache(now = 1_000_000)

        assertNotNull(dao.notificationDetail("active"))
        assertNotNull(dao.notificationDetail("young"))
        assertNotNull(dao.notificationDetail("boundary"))
        assertNull(dao.notificationDetail("old"))
    }

    @Test
    fun cancelledCacheIsBoundedToFiveHundredNewestRows() = runBlocking {
        repeat(501) { index ->
            dao.putNotificationDetail(
                detail(
                    detailId = "detail-$index",
                    canonId = "canon-$index",
                    cancelledAt = index.toLong() + 1,
                    updatedAt = index.toLong() + 1,
                ),
            )
        }

        dao.sweepNotificationDetailCache(now = 600_000)

        assertNull(dao.notificationDetail("detail-0"))
        assertNotNull(dao.notificationDetail("detail-500"))
        assertEquals(500, dao.cancelledNotificationDetailCount())
    }

    @Test
    fun rejectedTransactionCreatesNeitherInboundCanonicalNorCache() = runBlocking {
        assertFailsWith<IllegalArgumentException> {
            dao.commitInboundDesired(
                inbound("notif.post", 1, 1_000),
                active(1, payload("bad"), 1_000).copy(canonId = "different-canon"),
            )
        }

        assertNull(dao.inbound("msg-1"))
        assertNull(dao.canonical(CANON_ID))
        assertNull(dao.notificationDetailForCanon(CANON_ID))
        assertTrue(dao.cancelledNotificationDetailCount() == 0)
    }

    @Test
    fun snapshotUpsertAndSnapshotCancellationMaintainTheSameDetailRow() = runBlocking {
        val snapshotPayload = payload("from snapshot")
        dao.beginSnapshot("snapshot-post", ORIGIN, expectedItemCount = 1, receivedAt = 100)
        dao.stageSnapshotItem(SnapshotStage("snapshot-post", CANON_ID, 3, snapshotPayload, 110))
        assertEquals(
            SnapshotCommitResult.Committed(upserted = 1, cancelled = 0),
            dao.commitSnapshot("snapshot-post", committedAt = 200),
        )
        val active = assertNotNull(dao.notificationDetailForCanon(CANON_ID))
        assertEquals(snapshotPayload, active.payloadJson)
        assertNull(active.cancelledAt)

        dao.beginSnapshot("snapshot-cancel", ORIGIN, expectedItemCount = 0, receivedAt = 300)
        assertEquals(
            SnapshotCommitResult.Committed(upserted = 0, cancelled = 1),
            dao.commitSnapshot("snapshot-cancel", committedAt = 400),
        )
        val cancelled = assertNotNull(dao.notificationDetailForCanon(CANON_ID))
        assertEquals(active.detailId, cancelled.detailId)
        assertEquals(snapshotPayload, cancelled.payloadJson)
        assertEquals(400, cancelled.cancelledAt)
    }

    private fun inbound(type: String, sequence: Long, committedAt: Long) = InboundMessage(
        msgId = "msg-$sequence",
        originDevice = ORIGIN,
        envelopeSha256 = sequence.toString().padStart(64, '0'),
        eventType = type,
        canonId = CANON_ID,
        sequence = sequence,
        outcome = "PENDING_PLATFORM",
        committedAt = committedAt,
        appliedAt = null,
        receiptMsgId = null,
        relayAckState = "NONE",
    )

    private fun active(sequence: Long, payload: String, updatedAt: Long) = CanonicalNotificationState(
        canonId = CANON_ID,
        originDevice = ORIGIN,
        latestSequence = sequence,
        state = "ACTIVE",
        desiredPayloadJson = payload,
        materializedSequence = 0,
        sourceNotificationKey = null,
        mirrorLocalId = 7,
        mirrorLocalTag = "twinotify:mirror",
        peerCancelPending = false,
        updatedAt = updatedAt,
    )

    private fun cancelled(sequence: Long, updatedAt: Long) = active(sequence, payload("unused"), updatedAt).copy(
        state = "CANCELLED",
        desiredPayloadJson = null,
    )

    private fun payload(text: String) = """{"package_name":"com.example","text":"$text"}"""

    private fun detail(
        detailId: String,
        canonId: String,
        cancelledAt: Long?,
        updatedAt: Long,
    ) = NotificationDetailCache(
        detailId = detailId,
        canonId = canonId,
        payloadJson = payload(detailId),
        originDevice = ORIGIN,
        receivedAt = 1,
        updatedAt = updatedAt,
        cancelledAt = cancelledAt,
    )

    private companion object {
        const val CANON_ID = "peer:com.example:7:tag"
        const val ORIGIN = "peer"
    }
}
