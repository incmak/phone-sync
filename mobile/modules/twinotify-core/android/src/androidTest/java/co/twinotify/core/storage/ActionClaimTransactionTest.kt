package co.twinotify.core.storage

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import co.twinotify.core.actions.ActionClaimDecision
import java.util.concurrent.CountDownLatch
import kotlin.concurrent.thread
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ActionClaimTransactionTest {
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
    fun firstDeliveryCommitsInboundAndClaimTogether() = runBlocking {
        assertEquals(
            ActionClaimDecision.Execute,
            dao.claimActionInvocation(inbound("invoke-1", "a"), execution(), now = 1_000),
        )

        assertEquals("CLAIMED", dao.actionExecution(INVOCATION_ID)?.state)
        assertEquals("invoke-1", dao.inbound("invoke-1")?.msgId)
    }

    @Test
    fun recentSemanticDuplicateIsInFlightAndStaleOneBecomesOutcomeUnknown() = runBlocking {
        assertEquals(
            ActionClaimDecision.Execute,
            dao.claimActionInvocation(inbound("invoke-1", "a"), execution(), now = 1_000),
        )
        assertEquals(
            ActionClaimDecision.InFlight,
            dao.claimActionInvocation(inbound("invoke-2", "b"), execution(), now = 60_999),
        )
        assertEquals(
            ActionClaimDecision.Replay("outcome_unknown"),
            dao.claimActionInvocation(inbound("invoke-3", "c"), execution(), now = 61_000),
        )

        val row = dao.actionExecution(INVOCATION_ID)!!
        assertEquals("COMPLETED", row.state)
        assertEquals("outcome_unknown", row.resultStatus)
    }

    @Test
    fun completedInvocationReplaysStoredStatusAndNeverCreatesAnotherClaim() = runBlocking {
        dao.insertActionExecution(execution())
        assertEquals(1, dao.completeActionExecutionClaim(INVOCATION_ID, "dispatched", 2_000))

        assertEquals(
            ActionClaimDecision.Replay("dispatched"),
            dao.claimActionInvocation(inbound("invoke-2", "b"), execution(), now = 3_000),
        )
    }

    @Test
    fun duplicateMessageDigestConflictDoesNotMutateExecution() = runBlocking {
        assertEquals(
            ActionClaimDecision.Execute,
            dao.claimActionInvocation(inbound("invoke-1", "a"), execution(), now = 1_000),
        )

        assertEquals(
            ActionClaimDecision.IdConflict,
            dao.claimActionInvocation(inbound("invoke-1", "different"), execution(), now = 2_000),
        )
        assertEquals("CLAIMED", dao.actionExecution(INVOCATION_ID)?.state)
    }

    @Test
    fun invocationIdentityConflictFailsClosed() = runBlocking {
        assertEquals(
            ActionClaimDecision.Execute,
            dao.claimActionInvocation(inbound("invoke-1", "a"), execution(), now = 1_000),
        )
        val conflicting = execution().copy(canonId = "other-canon", actionId = "other-action")

        assertEquals(
            ActionClaimDecision.IdConflict,
            dao.claimActionInvocation(inbound("invoke-2", "b"), conflicting, now = 2_000),
        )
        assertNull(dao.inbound("invoke-2"))
    }

    @Test
    fun simultaneousSemanticDuplicatesYieldOneExecutorLease() {
        val ready = CountDownLatch(2)
        val release = CountDownLatch(1)
        val results = mutableListOf<ActionClaimDecision>()
        val workers = (1..2).map { index ->
            thread {
                ready.countDown()
                release.await()
                val decision = runBlocking {
                    dao.claimActionInvocation(
                        inbound("invoke-$index", index.toString()),
                        execution(),
                        now = 1_000,
                    )
                }
                synchronized(results) { results += decision }
            }
        }
        ready.await()
        release.countDown()
        workers.forEach { it.join() }

        assertEquals(1, results.count { it == ActionClaimDecision.Execute })
        assertEquals(1, results.count { it == ActionClaimDecision.InFlight })
        assertIs<ActionExecution>(runBlocking { dao.actionExecution(INVOCATION_ID) })
    }

    private fun inbound(msgId: String, digest: String) = InboundMessage(
        msgId = msgId,
        originDevice = "mirror-device",
        envelopeSha256 = digest.padEnd(64, digest.first()),
        eventType = "notif.action.invoke",
        canonId = null,
        sequence = null,
        outcome = "APPLIED",
        committedAt = 1_000,
        appliedAt = 1_000,
        receiptMsgId = null,
        relayAckState = "READY",
    )

    private fun execution() = ActionExecution(
        invocationId = INVOCATION_ID,
        canonId = "origin:pkg:1:tag",
        actionId = "33333333-3333-4333-8333-333333333333",
        state = "CLAIMED",
        resultStatus = null,
        claimedAt = 1_000,
        completedAt = null,
    )

    private companion object {
        const val INVOCATION_ID = "22222222-2222-4222-8222-222222222222"
    }
}
