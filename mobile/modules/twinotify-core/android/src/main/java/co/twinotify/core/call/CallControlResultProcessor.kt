package co.twinotify.core.call

import co.twinotify.core.actions.ActionResultCommitResult
import co.twinotify.core.actions.ActionResultProcessResult
import co.twinotify.core.actions.ActionResultRepost
import co.twinotify.core.storage.InboundMessage

data class CallControlResultRequest(
    val inbound: InboundMessage,
    val invocationId: String,
    val canonId: String,
    val kind: CallControlKind,
    val status: String,
)

fun interface CallControlResultJournal {
    suspend fun commit(request: CallControlResultRequest): ActionResultCommitResult
}

fun interface CallControlResultReposter {
    suspend fun repost(target: ActionResultRepost)
}

class CallControlResultProcessor(
    private val journal: CallControlResultJournal,
    private val repost: CallControlResultReposter,
) {
    suspend fun process(request: CallControlResultRequest): ActionResultProcessResult = when (val result = journal.commit(request)) {
        is ActionResultCommitResult.Committed -> {
            result.repost?.let { repost.repost(it) }
            ActionResultProcessResult.Applied
        }
        ActionResultCommitResult.Duplicate -> ActionResultProcessResult.Duplicate
        ActionResultCommitResult.IdConflict -> ActionResultProcessResult.IdConflict
    }
}
