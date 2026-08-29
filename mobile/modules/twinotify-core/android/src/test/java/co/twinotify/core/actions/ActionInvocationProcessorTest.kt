package co.twinotify.core.actions

import co.twinotify.core.storage.ActionExecution
import co.twinotify.core.storage.InboundMessage
import co.twinotify.core.storage.OutboundMessage
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

class ActionInvocationProcessorTest {
    @Test
    fun freshInvocationClaimsBeforeDispatchAndCompletesWithResult() = runBlocking {
        val events = mutableListOf<String>()
        val store = FakeStore(events)
        val processor = processor(store = store, events = events)

        assertEquals(ActionProcessResult.Completed("dispatched"), processor.process(request()))

        assertEquals(listOf("claim", "schedule", "active", "execute", "complete:dispatched"), events)
        assertEquals("dispatched", store.completedStatus)
        assertTrue(store.resultRows.single().eventType == "notif.action.result")
    }

    @Test
    fun completedInvocationReplaysStoredResultWithoutExecuting() = runBlocking {
        val store = FakeStore(claimDecision = ActionClaimDecision.Replay("action_gone"))
        val executor = CountingExecutor()

        assertEquals(
            ActionProcessResult.Replayed("action_gone"),
            processor(store = store, executor = executor).process(request()),
        )

        assertEquals(0, executor.calls.get())
        assertEquals("action_gone", store.replayedStatus)
    }

    @Test
    fun recentClaimIsIgnoredWithoutExecutingOrSendingAnotherResult() = runBlocking {
        val store = FakeStore(claimDecision = ActionClaimDecision.InFlight)
        val executor = CountingExecutor()

        assertEquals(ActionProcessResult.InFlight, processor(store, executor = executor).process(request()))

        assertEquals(0, executor.calls.get())
        assertTrue(store.resultRows.isEmpty())
    }

    @Test
    fun staleClaimReplaysOutcomeUnknownAndNeverExecutes() = runBlocking {
        val store = FakeStore(claimDecision = ActionClaimDecision.Replay("outcome_unknown"))
        val executor = CountingExecutor()

        assertEquals(
            ActionProcessResult.Replayed("outcome_unknown"),
            processor(store, executor = executor).process(request()),
        )

        assertEquals(0, executor.calls.get())
        assertEquals("outcome_unknown", store.replayedStatus)
    }

    @Test
    fun exactFreshnessBoundaryPassesAndNextMillisecondExpires() = runBlocking {
        val atBoundary = FakeStore()
        assertEquals(
            ActionProcessResult.Completed("dispatched"),
            processor(atBoundary, now = 121_000).process(request(invokedAt = 1_000)),
        )

        val tooLate = FakeStore()
        assertEquals(
            ActionProcessResult.Completed("expired"),
            processor(tooLate, now = 121_001).process(request(invokedAt = 1_000)),
        )
    }

    @Test
    fun staleRegistryEntriesFailClosedAsActionGone() = runBlocking {
        for (lookup in listOf(
            ActionLookup.MissingGeneration,
            ActionLookup.StaleGeneration,
            ActionLookup.MissingAction,
        )) {
            val store = FakeStore()
            assertEquals(
                ActionProcessResult.Completed("action_gone"),
                processor(store, lookup = lookup).process(request()),
            )
        }
    }

    @Test
    fun missingSourceKeyOrVanishedNotificationFailClosed() = runBlocking {
        val missingKey = FakeStore()
        assertEquals(
            ActionProcessResult.Completed("notification_gone"),
            processor(missingKey, sourceKey = "").process(request()),
        )

        val vanished = FakeStore()
        assertEquals(
            ActionProcessResult.Completed("notification_gone"),
            processor(vanished, sourceActive = false).process(request()),
        )
    }

    @Test
    fun replyForNonReplyActionAndOversizedReplyFailWithoutDispatch() = runBlocking {
        val wrongShapeExecutor = CountingExecutor()
        val wrongShape = FakeStore()
        assertEquals(
            ActionProcessResult.Completed("failed"),
            processor(wrongShape, executor = wrongShapeExecutor, supportsReply = false)
                .process(request(replyText = "hello")),
        )
        assertEquals(0, wrongShapeExecutor.calls.get())

        val oversizedExecutor = CountingExecutor()
        val oversized = FakeStore()
        assertEquals(
            ActionProcessResult.Completed("failed"),
            processor(oversized, executor = oversizedExecutor)
                .process(request(replyText = "€".repeat(1_366))),
        )
        assertEquals(0, oversizedExecutor.calls.get())
    }

    @Test
    fun executorFailureMapsToSafeFailedStatus() = runBlocking {
        val store = FakeStore()
        val executor = CountingExecutor(dispatches = false)

        assertEquals(
            ActionProcessResult.Completed("failed"),
            processor(store, executor = executor).process(request()),
        )

        assertEquals(1, executor.calls.get())
        assertEquals("failed", store.completedStatus)
    }

    @Test
    fun completionCasLossEmitsNoContradictoryResult() = runBlocking {
        val store = FakeStore(completionWins = false)

        assertEquals(ActionProcessResult.CompletionLost, processor(store).process(request()))

        assertTrue(store.resultRows.isEmpty())
        assertEquals(null, store.completedStatus)
    }

    @Test
    fun concurrentDuplicatesDispatchAtMostOnce() {
        val firstClaimed = CountDownLatch(1)
        val releaseFirst = CountDownLatch(1)
        val claims = AtomicInteger()
        val store = FakeStore(
            claim = {
                if (claims.getAndIncrement() == 0) {
                    firstClaimed.countDown()
                    releaseFirst.await()
                    ActionClaimDecision.Execute
                } else {
                    ActionClaimDecision.InFlight
                }
            },
        )
        val executor = CountingExecutor()
        val results = mutableListOf<ActionProcessResult>()
        val first = thread {
            results += runBlocking { processor(store, executor = executor).process(request()) }
        }
        firstClaimed.await()
        val second = thread {
            results += runBlocking { processor(store, executor = executor).process(request()) }
        }
        second.join()
        releaseFirst.countDown()
        first.join()

        assertEquals(1, executor.calls.get())
        assertTrue(results.contains(ActionProcessResult.InFlight))
        assertTrue(results.contains(ActionProcessResult.Completed("dispatched")))
    }

    private fun processor(
        store: FakeStore,
        executor: CountingExecutor? = null,
        events: MutableList<String> = mutableListOf(),
        now: Long = 100_000,
        lookup: ActionLookup? = null,
        sourceKey: String = "source-key",
        sourceActive: Boolean = true,
        supportsReply: Boolean = true,
    ): ActionInvocationProcessor<String> {
        val found = lookup ?: ActionLookup.Found(
            ActionGeneration(
                sequence = 7,
                sourceKey = sourceKey,
                packageName = "com.example",
                handlesByActionId = mapOf(ACTION_ID to "handle"),
            ),
            "handle",
        )
        return ActionInvocationProcessor(
            journal = store,
            registryLookup = { _, _, _ -> found },
            sourceActive = { events += "active"; sourceActive },
            supportsReply = { supportsReply },
            executor = executor ?: CountingExecutor(events = events),
            resultEncoder = ActionResultRowEncoder { input -> resultRow(input.status) },
            wakeScheduler = ActionClaimWakeScheduler { events += "schedule" },
            clock = { now },
        )
    }

    private fun request(
        invokedAt: Long = 1_000,
        replyText: String? = null,
    ) = ActionInvokeRequest(
        inbound = InboundMessage(
            msgId = "11111111-1111-4111-8111-111111111111",
            originDevice = "mirror-device",
            envelopeSha256 = "a".repeat(64),
            eventType = "notif.action.invoke",
            canonId = null,
            sequence = null,
            outcome = "APPLIED",
            committedAt = 100_000,
            appliedAt = 100_000,
            receiptMsgId = null,
            relayAckState = "READY",
        ),
        invocationId = INVOCATION_ID,
        canonId = CANON_ID,
        actionId = ACTION_ID,
        notificationSequence = 7,
        replyText = replyText,
        invokedAt = invokedAt,
    )

    private class CountingExecutor(
        private val dispatches: Boolean = true,
        private val events: MutableList<String>? = null,
    ) : RegisteredActionExecutor<String> {
        val calls = AtomicInteger()
        override suspend fun dispatch(handle: String, replyText: String?): Boolean {
            events?.add("execute")
            calls.incrementAndGet()
            return dispatches
        }
    }

    private class FakeStore(
        private val events: MutableList<String> = mutableListOf(),
        private val claimDecision: ActionClaimDecision = ActionClaimDecision.Execute,
        private val completionWins: Boolean = true,
        private val claim: (suspend () -> ActionClaimDecision)? = null,
    ) : ActionClaimJournal {
        var completedStatus: String? = null
        var replayedStatus: String? = null
        val resultRows = mutableListOf<OutboundMessage>()

        override suspend fun claim(
            inbound: InboundMessage,
            execution: ActionExecution,
            now: Long,
        ): ActionClaimDecision {
            events += "claim"
            return claim?.invoke() ?: claimDecision
        }

        override suspend fun completeAndEnqueue(
            invocationId: String,
            status: String,
            now: Long,
            result: OutboundMessage,
        ): Boolean {
            events += "complete:$status"
            if (!completionWins) return false
            completedStatus = status
            resultRows += result
            return true
        }

        override suspend fun replayResult(
            invocationId: String,
            status: String,
            result: OutboundMessage,
        ): Boolean {
            replayedStatus = status
            resultRows += result
            return true
        }
    }

    private fun resultRow(status: String) = OutboundMessage(
        msgId = "44444444-4444-4444-8444-${status.hashCode().toUInt().toString().padStart(12, '0').takeLast(12)}",
        canonId = null,
        sequence = null,
        eventType = "notif.action.result",
        protocolVersion = 2,
        envelopeJson = "{}",
        envelopeSha256 = "b".repeat(64),
        byteSize = 2,
        createdAt = 100_000,
        expiresAt = 700_000,
        custodyAcceptedAt = null,
        custodyRoute = null,
        attempts = 0,
        nextAttemptAt = 100_000,
        state = "NEW",
        lastError = null,
        requiresPeerReceipt = false,
    )

    private companion object {
        const val INVOCATION_ID = "22222222-2222-4222-8222-222222222222"
        const val ACTION_ID = "33333333-3333-4333-8333-333333333333"
        const val CANON_ID = "origin:pkg:1:tag"
    }
}
