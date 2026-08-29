package co.twinotify.core.storage

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import kotlin.test.assertEquals
import kotlin.test.assertNull
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NotificationActionDaoTest {
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
    fun tearDown() = db.close()

    @Test
    fun invocationTerminalTransitionClearsReplyTextOnlyOnce() = runBlocking {
        dao.insertActionInvocation(
            ActionInvocation(
                invocationId = INVOCATION_ID,
                canonId = CANON_ID,
                actionId = ACTION_ID,
                notificationSequence = 7,
                replyText = "private reply",
                state = "PENDING",
                createdAt = 1_000,
                expiresAt = 121_000,
                updatedAt = 1_000,
            ),
        )

        assertEquals(1, dao.terminalizeActionInvocation(INVOCATION_ID, "DISPATCHED", 2_000))
        assertEquals(0, dao.terminalizeActionInvocation(INVOCATION_ID, "FAILED", 3_000))
        val row = dao.actionInvocation(INVOCATION_ID)!!
        assertEquals("DISPATCHED", row.state)
        assertNull(row.replyText)
        assertEquals(2_000, row.updatedAt)
    }

    @Test
    fun executionCompletionIsCompareAndSet() = runBlocking {
        dao.insertActionExecution(
            ActionExecution(
                invocationId = INVOCATION_ID,
                canonId = CANON_ID,
                actionId = ACTION_ID,
                state = "CLAIMED",
                resultStatus = null,
                claimedAt = 1_000,
                completedAt = null,
            ),
        )

        assertEquals(1, dao.completeActionExecutionClaim(INVOCATION_ID, "dispatched", 2_000))
        assertEquals(0, dao.completeActionExecutionClaim(INVOCATION_ID, "failed", 3_000))
        assertEquals("dispatched", dao.actionExecution(INVOCATION_ID)?.resultStatus)
    }

    @Test
    fun retentionRemovesOnlyCompletedActionExecutionsAfterTwentyFourHours() = runBlocking {
        val oldCompleted = execution(INVOCATION_ID, claimedAt = 1).copy(
            state = "COMPLETED",
            resultStatus = "dispatched",
            completedAt = 2,
        )
        val liveClaim = execution("11111111-1111-4111-8111-111111111111", claimedAt = 1)
        dao.insertActionExecution(oldCompleted)
        dao.insertActionExecution(liveClaim)

        dao.sweepRetention(
            now = 24 * 60 * 60 * 1_000L + 3,
            activityRetentionMs = 24 * 60 * 60 * 1_000L,
            tombstoneRetentionMs = 24 * 60 * 60 * 1_000L,
        )

        assertNull(dao.actionExecution(INVOCATION_ID))
        assertEquals("CLAIMED", dao.actionExecution(liveClaim.invocationId)?.state)
    }

    @Test
    fun detailCacheRoundTripsByOpaqueAndCanonicalIdentity() = runBlocking {
        val row = NotificationDetailCache(
            detailId = DETAIL_ID,
            canonId = CANON_ID,
            payloadJson = "{\"type\":\"notif.post\"}",
            originDevice = "origin-device",
            receivedAt = 1_000,
            updatedAt = 1_000,
            cancelledAt = null,
        )

        dao.putNotificationDetail(row)

        assertEquals(row, dao.notificationDetail(DETAIL_ID))
        assertEquals(row, dao.notificationDetailForCanon(CANON_ID))
    }

    private fun execution(id: String, claimedAt: Long) = ActionExecution(
        invocationId = id,
        canonId = CANON_ID,
        actionId = ACTION_ID,
        state = "CLAIMED",
        resultStatus = null,
        claimedAt = claimedAt,
        completedAt = null,
    )

    private companion object {
        const val INVOCATION_ID = "fd2fb70b-829a-4701-8956-61611bc9c701"
        const val ACTION_ID = "b6d3142a-e936-4d7d-b15a-bdf318bb0539"
        const val DETAIL_ID = "bfda72bd-a06e-4698-9577-2e5771fd2295"
        const val CANON_ID = "origin-device:com.example.chat:42:thread-7"
    }
}
