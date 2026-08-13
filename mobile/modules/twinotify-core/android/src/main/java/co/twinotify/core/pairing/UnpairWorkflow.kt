package co.twinotify.core.pairing

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/** Shared production workflow: quiesce jobs, complete authenticated revoke, then wipe locally. */
object UnpairWorkflow {
    suspend fun execute(
        stopAndAwait: suspend () -> Unit,
        revokePeer: suspend () -> Unit,
        wipeLocal: suspend () -> Unit,
    ) {
        stopAndAwait()
        revokePeer()
        withContext(NonCancellable) { wipeLocal() }
    }
}
