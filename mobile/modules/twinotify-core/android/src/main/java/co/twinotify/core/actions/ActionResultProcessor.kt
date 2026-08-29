package co.twinotify.core.actions

import co.twinotify.core.storage.InboundMessage

data class ActionResultRequest(
    val inbound: InboundMessage,
    val invocationId: String,
    val canonId: String,
    val status: String,
)

data class ActionResultRepost(
    val canonId: String,
    val notificationSequence: Long,
    val localTag: String,
    val localId: Int,
)

sealed interface ActionResultCommitResult {
    data class Committed(val repost: ActionResultRepost?) : ActionResultCommitResult
    data object Duplicate : ActionResultCommitResult
    data object IdConflict : ActionResultCommitResult
}

sealed interface ActionResultProcessResult {
    data object Applied : ActionResultProcessResult
    data object Duplicate : ActionResultProcessResult
    data object IdConflict : ActionResultProcessResult
}

fun interface ActionResultJournal {
    suspend fun commit(request: ActionResultRequest): ActionResultCommitResult
}

fun interface ActionResultReposter {
    suspend fun repost(target: ActionResultRepost)
}

class ActionResultProcessor(
    private val journal: ActionResultJournal,
    private val repost: ActionResultReposter,
) {
    suspend fun process(request: ActionResultRequest): ActionResultProcessResult = when (
        val committed = journal.commit(request)
    ) {
        is ActionResultCommitResult.Committed -> {
            committed.repost?.let { repost.repost(it) }
            ActionResultProcessResult.Applied
        }
        ActionResultCommitResult.Duplicate -> ActionResultProcessResult.Duplicate
        ActionResultCommitResult.IdConflict -> ActionResultProcessResult.IdConflict
    }
}
