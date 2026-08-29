package co.twinotify.core.actions

import co.twinotify.core.storage.OutboundStateCommitResult
import kotlin.test.Test
import kotlin.test.assertIs

class ActionGenerationCommitterTest {
    @Test
    fun onlyTheWinningPostCommitInstallsItsGeneration() {
        val registry = ActionRegistry<String>()
        val committer = ActionGenerationCommitter(registry)
        val losing = ActionGeneration(4, "source", "pkg", mapOf("losing" to "handle"))
        val winning = ActionGeneration(5, "source", "pkg", mapOf("winning" to "handle"))

        committer.afterPostCommit("canon", losing, OutboundStateCommitResult.Stale(5))
        assertIs<ActionLookup.MissingGeneration>(registry.lookup("canon", 4, "losing"))

        committer.afterPostCommit("canon", winning, OutboundStateCommitResult.Committed(0))
        assertIs<ActionLookup.Found<String>>(registry.lookup("canon", 5, "winning"))
    }

    @Test
    fun committedCancelPurgesButStaleCancelDoesNot() {
        val registry = ActionRegistry<String>()
        val committer = ActionGenerationCommitter(registry)
        registry.install("canon", ActionGeneration(5, "source", "pkg", mapOf("action" to "handle")))

        committer.afterCancelCommit("canon", OutboundStateCommitResult.Stale(6))
        assertIs<ActionLookup.Found<String>>(registry.lookup("canon", 5, "action"))

        committer.afterCancelCommit("canon", OutboundStateCommitResult.Committed(0))
        assertIs<ActionLookup.MissingGeneration>(registry.lookup("canon", 5, "action"))
    }
}
