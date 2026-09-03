package co.twinotify.core.storage

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import co.twinotify.core.actions.ActionClaimDecision
import co.twinotify.core.actions.ActionResultCommitResult
import co.twinotify.core.call.CallControlKind
import co.twinotify.core.call.CallControlResultRequest
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

    @Test
    fun notificationAndCallClaimsShareInvocationIdentityConflictBoundary() = runBlocking {
        assertEquals(
            ActionClaimDecision.Execute,
            dao.claimActionInvocation(inbound("invoke-1", "a"), execution(), now = 1_000),
        )
        val callExecution = execution().copy(canonId = CALL_CANON, actionId = "answer:2")

        assertEquals(
            ActionClaimDecision.IdConflict,
            dao.claimCallControlInvocation(callInbound("call-2", "b"), callExecution, now = 2_000),
        )
        assertNull(dao.inbound("call-2"))
    }

    @Test
    fun differentCallControlsForSameSequenceCannotBothQueue() = runBlocking {
        val answer = callInvocation(INVOCATION_ID, "answer")
        val decline = callInvocation("77777777-7777-4777-8777-777777777777", "decline")

        assertEquals(
            ActionInvocationOutboxCommitResult.Committed,
            dao.commitCallControlInvocationAndOutbound(answer, callOutbound("88888888-8888-4888-8888-888888888888")),
        )
        assertEquals(
            ActionInvocationOutboxCommitResult.InvocationConflict,
            dao.commitCallControlInvocationAndOutbound(decline, callOutbound("99999999-9999-4999-8999-999999999999")),
        )
        assertNull(dao.actionInvocation(decline.invocationId))
    }

    @Test
    fun simultaneousAnswerAndDeclineYieldOneDurableAttempt() {
        val ready = CountDownLatch(2)
        val release = CountDownLatch(1)
        val results = mutableListOf<ActionInvocationOutboxCommitResult>()
        val attempts = listOf(
            callInvocation(INVOCATION_ID, "answer") to callOutbound("88888888-8888-4888-8888-888888888888"),
            callInvocation("77777777-7777-4777-8777-777777777777", "decline") to
                callOutbound("99999999-9999-4999-8999-999999999999"),
        )
        val workers = attempts.map { (invocation, outbound) ->
            thread {
                ready.countDown()
                release.await()
                val result = runBlocking { dao.commitCallControlInvocationAndOutbound(invocation, outbound) }
                synchronized(results) { results += result }
            }
        }
        ready.await()
        release.countDown()
        workers.forEach { it.join() }

        assertEquals(1, results.count { it == ActionInvocationOutboxCommitResult.Committed })
        assertEquals(1, results.count { it == ActionInvocationOutboxCommitResult.InvocationConflict })
        assertEquals(1, dao.actionInvocationsForNotification(CALL_CANON, 2).size)
    }

    @Test
    fun callResultKindMustMatchStoredInvocationAtomically() = runBlocking {
        dao.insertActionInvocation(callInvocation(INVOCATION_ID, "answer"))
        val resultInbound = callInbound("result-1", "d").copy(eventType = "call.control.result")

        assertEquals(
            ActionResultCommitResult.Committed(repost = null),
            dao.commitCallControlResult(
                CallControlResultRequest(
                    resultInbound, INVOCATION_ID, CALL_CANON, CallControlKind.DECLINE, "dispatched",
                ),
            ),
        )
        assertEquals("PENDING", dao.actionInvocation(INVOCATION_ID)?.state)
    }

    @Test
    fun callCompletionOutboundConflictLeavesClaimRecoverable() = runBlocking {
        dao.insertActionExecution(execution().copy(canonId = CALL_CANON, actionId = "answer:2"))
        val result = callResultOutbound("88888888-8888-4888-8888-888888888888")
        dao.insertOutbound(result.copy(eventType = "peer.receipt"))

        assertEquals(
            ActionCompletionOutboxCommitResult.OutboundConflict,
            dao.completeCallControlExecutionAndEnqueue(INVOCATION_ID, "dispatched", 2_000, result),
        )
        assertEquals("CLAIMED", dao.actionExecution(INVOCATION_ID)?.state)
    }

    @Test
    fun completedCallClaimReplaysStoredResultAcrossMessageIds() = runBlocking {
        val callExecution = execution().copy(canonId = CALL_CANON, actionId = "answer:2")
        assertEquals(
            ActionClaimDecision.Execute,
            dao.claimCallControlInvocation(callInbound("call-1", "a"), callExecution, 1_000),
        )
        assertEquals(1, dao.completeActionExecutionClaim(INVOCATION_ID, "dispatched", 2_000))
        assertEquals(
            ActionClaimDecision.Replay("dispatched"),
            dao.claimCallControlInvocation(callInbound("call-2", "b"), callExecution, 3_000),
        )
    }

    @Test
    fun callControlRowsAreUserSyncUpdatesNotInternalTraffic() = runBlocking {
        dao.insertOutbound(callOutbound("88888888-8888-4888-8888-888888888888"))
        dao.insertOutbound(callResultOutbound("99999999-9999-4999-8999-999999999999"))

        val snapshot = dao.deliveryQueueSnapshot()
        assertEquals(2, snapshot.pendingLocal)
        assertEquals(0, snapshot.internalActive)
        assertEquals(UserContentKind.SYNC_UPDATES, snapshot.userContentKind)
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

    private fun callInbound(msgId: String, digest: String) = inbound(msgId, digest).copy(
        eventType = "call.control.invoke",
    )

    private fun callInvocation(id: String, kind: String) = ActionInvocation(
        invocationId = id,
        canonId = CALL_CANON,
        actionId = kind,
        notificationSequence = 2,
        replyText = null,
        state = "PENDING",
        createdAt = 1_000,
        expiresAt = 16_000,
        updatedAt = 1_000,
    )

    private fun callOutbound(msgId: String) = OutboundMessage(
        msgId, null, null, "call.control.invoke", 2, "{}", "c".repeat(64), 2,
        1_000, 16_000, null, null, 0, 1_000, "NEW", null, false,
    )

    private fun callResultOutbound(msgId: String) = callOutbound(msgId).copy(
        eventType = "call.control.result",
        expiresAt = 301_000,
    )

    private companion object {
        const val INVOCATION_ID = "22222222-2222-4222-8222-222222222222"
        const val CALL_CANON = "call:11111111-1111-4111-8111-111111111111"
    }
}
