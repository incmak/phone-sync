package co.twinotify.core.pairing

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals

/** Exercises the exact workflow shared by local and peer-initiated production paths. */
class UnpairWorkflowTest {
    @Test
    fun productionWorkflowStopsBeforeRevokeAndWipe() = runBlocking {
        val steps = mutableListOf<String>()

        UnpairWorkflow.execute(
            stopAndAwait = { steps += "stop-and-await" },
            revokePeer = { steps += "revoke" },
            wipeLocal = { steps += "wipe" },
        )

        assertEquals(listOf("stop-and-await", "revoke", "wipe"), steps)
    }
}
