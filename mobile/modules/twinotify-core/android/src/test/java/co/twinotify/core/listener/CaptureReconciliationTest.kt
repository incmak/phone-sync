package co.twinotify.core.listener

import co.twinotify.core.storage.CanonicalNotificationState
import kotlin.test.Test
import kotlin.test.assertEquals

class CaptureReconciliationTest {
    @Test
    fun reconnectCancelsPersistedSourcesRemovedWhileListenerWasDown() {
        val states = listOf(
            state("canon-live", "source-live", "ACTIVE"),
            state("canon-removed", "source-removed", "ACTIVE"),
            state("canon-cancelled", "source-cancelled", "CANCELLED"),
            state("canon-no-key", null, "ACTIVE"),
        )

        val missing = CaptureReconciliation.missingActiveStates(states, setOf("source-live"))

        assertEquals(listOf("canon-removed"), missing.map { it.canonId })
    }

    private fun state(canonId: String, sourceKey: String?, state: String) =
        CanonicalNotificationState(
            canonId = canonId,
            originDevice = "device-a",
            latestSequence = 1,
            state = state,
            desiredPayloadJson = null,
            materializedSequence = 0,
            sourceNotificationKey = sourceKey,
            mirrorLocalId = null,
            mirrorLocalTag = null,
            peerCancelPending = false,
            updatedAt = 1,
        )
}
