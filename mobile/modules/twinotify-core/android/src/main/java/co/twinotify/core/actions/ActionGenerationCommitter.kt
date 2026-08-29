package co.twinotify.core.actions

import co.twinotify.core.storage.OutboundStateCommitResult

class ActionGenerationCommitter<T>(private val registry: ActionRegistry<T>) {
    fun afterPostCommit(
        canonId: String,
        generation: ActionGeneration<T>,
        result: OutboundStateCommitResult,
    ) {
        if (result is OutboundStateCommitResult.Committed) registry.install(canonId, generation)
    }

    fun afterCancelCommit(canonId: String, result: OutboundStateCommitResult) {
        if (result is OutboundStateCommitResult.Committed) registry.purge(canonId)
    }
}
