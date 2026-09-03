package co.twinotify.core.call

import co.twinotify.core.actions.ActionResultCommitResult
import co.twinotify.core.actions.ActionResultProcessResult
import co.twinotify.core.actions.ActionResultRepost
import co.twinotify.core.storage.InboundMessage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.test.runTest

class CallControlResultProcessorTest {
    @Test
    fun committedResultRepostsCallMirrorAfterDurableTransition() = runTest {
        val events = mutableListOf<String>()
        val processor = CallControlResultProcessor(
            journal = CallControlResultJournal { events += "commit"; ActionResultCommitResult.Committed(TARGET) },
            repost = CallControlResultReposter { events += "repost" },
        )
        assertEquals(ActionResultProcessResult.Applied, processor.process(request()))
        assertEquals(listOf("commit", "repost"), events)
    }

    @Test
    fun resultReplayConflictAndLateResultAreIdempotent() = runTest {
        var reposts = 0
        suspend fun run(result: ActionResultCommitResult) = CallControlResultProcessor(
            journal = CallControlResultJournal { result },
            repost = CallControlResultReposter { reposts += 1 },
        ).process(request())
        assertEquals(ActionResultProcessResult.Duplicate, run(ActionResultCommitResult.Duplicate))
        assertEquals(ActionResultProcessResult.IdConflict, run(ActionResultCommitResult.IdConflict))
        assertEquals(ActionResultProcessResult.Applied, run(ActionResultCommitResult.Committed(null)))
        assertEquals(0, reposts)
    }

    private fun request() = CallControlResultRequest(
        InboundMessage(MESSAGE, "origin-device", "a".repeat(64), "call.control.result", null, null,
            "APPLIED", 2_000, 2_000, null, "READY"),
        CONTROL, CANON, CallControlKind.ANSWER, "dispatched",
    )

    private companion object {
        const val SESSION = "11111111-1111-4111-8111-111111111111"
        const val CONTROL = "22222222-2222-4222-8222-222222222222"
        const val MESSAGE = "33333333-3333-4333-8333-333333333333"
        const val CANON = "call:$SESSION"
        val TARGET = ActionResultRepost(CANON, 2, "call-mirror", 41)
    }
}
