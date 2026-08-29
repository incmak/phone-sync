package co.twinotify.core.actions

import co.twinotify.core.storage.InboundMessage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.test.runTest

class ActionResultProcessorTest {
    @Test
    fun committedResultRepostsOnlyAfterJournalTransition() = runTest {
        val events = mutableListOf<String>()
        val target = ActionResultRepost("canon", 7, "tag", 41)
        val processor = ActionResultProcessor(
            journal = ActionResultJournal {
                events += "commit"
                ActionResultCommitResult.Committed(target)
            },
            repost = ActionResultReposter {
                events += "repost"
                assertEquals(target, it)
            },
        )

        assertEquals(ActionResultProcessResult.Applied, processor.process(request()))
        assertEquals(listOf("commit", "repost"), events)
    }

    @Test
    fun duplicateAndLateTerminalResultsAreIdempotent() = runTest {
        var reposts = 0
        val duplicate = ActionResultProcessor(
            journal = ActionResultJournal { ActionResultCommitResult.Duplicate },
            repost = ActionResultReposter { reposts += 1 },
        )
        assertEquals(ActionResultProcessResult.Duplicate, duplicate.process(request()))

        val late = ActionResultProcessor(
            journal = ActionResultJournal { ActionResultCommitResult.Committed(repost = null) },
            repost = ActionResultReposter { reposts += 1 },
        )
        assertEquals(ActionResultProcessResult.Applied, late.process(request()))
        assertEquals(0, reposts)
    }

    @Test
    fun conflictingMessageIdentityIsRejected() = runTest {
        val processor = ActionResultProcessor(
            journal = ActionResultJournal { ActionResultCommitResult.IdConflict },
            repost = ActionResultReposter { error("conflict must not repost") },
        )

        assertEquals(ActionResultProcessResult.IdConflict, processor.process(request()))
    }

    private fun request() = ActionResultRequest(
        inbound = InboundMessage(
            msgId = "11111111-1111-4111-8111-111111111111",
            originDevice = "origin",
            envelopeSha256 = "a".repeat(64),
            eventType = "notif.action.result",
            canonId = null,
            sequence = null,
            outcome = "APPLIED",
            committedAt = 2_000,
            appliedAt = 2_000,
            receiptMsgId = null,
            relayAckState = "READY",
        ),
        invocationId = "22222222-2222-4222-8222-222222222222",
        canonId = "canon",
        status = "dispatched",
    )
}
