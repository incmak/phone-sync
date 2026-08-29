package co.twinotify.core.actions

import co.twinotify.core.listener.NotifActionJson
import co.twinotify.core.storage.CanonicalNotificationState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class MirrorActionTargetResolutionTest {
    @Test
    fun cancelledNotificationStillRoutesAnAlreadyAdvertisedPendingIntentToTheOrigin() {
        val target = target(sequence = 7)

        val resolved = resolveMirrorActionTarget(
            identity = IDENTITY,
            canonId = CANON_ID,
            state = state(status = "CANCELLED", payload = null),
            advertised = target,
        )

        assertEquals(target, resolved)
    }

    @Test
    fun cancelledNotificationWithoutAnAdvertisedCapabilityFailsClosed() {
        val resolved = resolveMirrorActionTarget(
            identity = IDENTITY,
            canonId = CANON_ID,
            state = state(status = "CANCELLED", payload = null),
            advertised = null,
        )

        assertNull(resolved)
    }

    @Test
    fun advertisedCapabilityCannotCrossCanonicalIdentity() {
        val resolved = resolveMirrorActionTarget(
            identity = IDENTITY,
            canonId = CANON_ID,
            state = state(status = "CANCELLED", payload = null),
            advertised = target(sequence = 7).copy(canonId = "other-canon"),
        )

        assertNull(resolved)
    }

    private fun state(status: String, payload: String?) = CanonicalNotificationState(
        canonId = CANON_ID,
        originDevice = "origin",
        latestSequence = 8,
        state = status,
        desiredPayloadJson = payload,
        materializedSequence = 8,
        sourceNotificationKey = null,
        mirrorLocalId = 42,
        mirrorLocalTag = "mirror-tag",
        peerCancelPending = false,
        updatedAt = 1_000,
    )

    private fun target(sequence: Long) = MirrorActionTarget(
        canonId = CANON_ID,
        notificationSequence = sequence,
        localTag = "mirror-tag",
        localId = 42,
        action = ACTION,
    )

    private companion object {
        const val CANON_ID = "canon"
        val IDENTITY = ActionInvokeIdentity("mirror-tag", 42, "11111111-1111-4111-8111-111111111111")
        val ACTION = NotifActionJson(
            action_id = IDENTITY.actionId,
            title = "Mark read",
            semantic = 2,
            reply = false,
            reply_label = null,
        )
    }
}
