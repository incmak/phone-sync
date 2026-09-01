package co.twinotify.core.service

import co.twinotify.core.protocol.InnerEventV2
import co.twinotify.core.storage.CanonicalNotificationState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class NotificationStateReducerTest {
    private val allocator = object : LocalIdAllocator {
        private var next = 40
        override fun nextId(): Int = ++next
    }

    @Test
    fun cancelAtSequenceThreeRejectsLateUpdateAtSequenceTwo() {
        val cancelled = state(sequence = 3, state = "CANCELLED", materialized = 3)
        val result = NotificationStateReducer.reduce(
            cancelled,
            event(type = "notif.update", sequence = 2),
            localDeviceId = "dev-local",
            allocator = allocator,
        )

        val stale = assertIs<Reduction.Stale>(result)
        assertEquals(cancelled, stale.state)
    }

    @Test
    fun updateReusesPersistedMirrorIdentity() {
        val active = state(
            sequence = 1,
            state = "ACTIVE",
            materialized = 1,
            localId = 42,
            localTag = "mirror-x",
        )
        val result = assertIs<Reduction.Apply>(
            NotificationStateReducer.reduce(
                active,
                event(type = "notif.update", sequence = 2),
                localDeviceId = "dev-local",
                allocator = allocator,
            ),
        )

        assertEquals(42, result.state.mirrorLocalId)
        assertEquals("mirror-x", result.state.mirrorLocalTag)
        assertEquals(2, result.state.latestSequence)
    }

    @Test
    fun firstPeerPostAllocatesStableTagAndId() {
        val event = event(type = "notif.post", sequence = 1, origin = "dev-peer")
        val first = assertIs<Reduction.Apply>(
            NotificationStateReducer.reduce(
                current = null,
                event = event,
                localDeviceId = "dev-local",
                allocator = allocator,
            ),
        ).state
        val second = assertIs<Reduction.Apply>(
            NotificationStateReducer.reduce(
                current = first,
                event = event.copy(sequence = 2),
                localDeviceId = "dev-local",
                allocator = allocator,
            ),
        ).state

        assertEquals(first.mirrorLocalId, second.mirrorLocalId)
        assertEquals(first.mirrorLocalTag, second.mirrorLocalTag)
        assertEquals("mirror-" + first.mirrorLocalTag!!.removePrefix("mirror-"), first.mirrorLocalTag)
    }

    @Test
    fun distinctSourceNotificationsReceiveDistinctMirrorIdentities() {
        val first = assertIs<Reduction.Apply>(
            NotificationStateReducer.reduce(
                current = null,
                event = event(type = "notif.post", sequence = 1).copy(canonId = "dev-peer:pkg:7:chat-a"),
                localDeviceId = "dev-local",
                allocator = allocator,
            ),
        ).state
        val second = assertIs<Reduction.Apply>(
            NotificationStateReducer.reduce(
                current = null,
                event = event(type = "notif.post", sequence = 1).copy(canonId = "dev-peer:pkg:8:chat-b"),
                localDeviceId = "dev-local",
                allocator = allocator,
            ),
        ).state

        assertFalse(first.mirrorLocalId == second.mirrorLocalId)
        assertFalse(first.mirrorLocalTag == second.mirrorLocalTag)
    }

    @Test
    fun threeUpdatesConvergeWithoutChangingMirrorIdentity() {
        var state = assertIs<Reduction.Apply>(
            NotificationStateReducer.reduce(
                current = null,
                event = event(type = "notif.post", sequence = 1),
                localDeviceId = "dev-local",
                allocator = allocator,
            ),
        ).state
        val initialIdentity = state.mirrorLocalTag to state.mirrorLocalId

        for (sequence in 2L..4L) {
            state = assertIs<Reduction.Apply>(
                NotificationStateReducer.reduce(
                    current = state,
                    event = event(type = "notif.update", sequence = sequence),
                    localDeviceId = "dev-local",
                    allocator = allocator,
                ),
            ).state
        }

        assertEquals(initialIdentity, state.mirrorLocalTag to state.mirrorLocalId)
        assertEquals(4, state.latestSequence)
    }

    @Test
    fun mirrorTagPredicateMatchesOnlyProductionMirrorTags() {
        assertTrue(NotificationStateReducer.isMirrorTag(NotificationStateReducer.stableMirrorTag("canon")))
        assertFalse(NotificationStateReducer.isMirrorTag("twinotify-mirror-stale"))
        assertFalse(NotificationStateReducer.isMirrorTag(null))
    }

    @Test
    fun cancelClearsDesiredPayloadButKeepsStableMirrorIdentity() {
        val active = state(
            sequence = 1,
            state = "ACTIVE",
            materialized = 1,
            localId = 42,
            localTag = "mirror-x",
        )
        val cancelled = assertIs<Reduction.Apply>(
            NotificationStateReducer.reduce(
                active,
                event(type = "notif.cancel", sequence = 2),
                localDeviceId = "dev-local",
                allocator = allocator,
            ),
        ).state

        assertEquals("CANCELLED", cancelled.state)
        assertEquals(null, cancelled.desiredPayloadJson)
        assertEquals(42, cancelled.mirrorLocalId)
        assertEquals("mirror-x", cancelled.mirrorLocalTag)
    }

    @Test
    fun localSourceStateNeverAllocatesPeerMirrorIdentity() {
        val source = assertIs<Reduction.Apply>(
            NotificationStateReducer.reduce(
                current = null,
                event = event(type = "notif.post", sequence = 1, origin = "dev-local"),
                localDeviceId = "dev-local",
                allocator = allocator,
            ),
        ).state

        assertEquals(null, source.mirrorLocalId)
        assertEquals(null, source.mirrorLocalTag)
    }

    @Test
    fun authenticatedPeerCancelPreservesSourceOwnerForSourceMaterialization() {
        val current = state(
            sequence = 4,
            state = "ACTIVE",
            materialized = 4,
            localId = null,
            localTag = null,
        ).copy(sourceNotificationKey = "pkg|42|tag")
        val incoming = event(type = "notif.cancel", sequence = 5, origin = "dev-peer-canceller")
            .copy(canonId = current.canonId)

        val authorized = NotificationStateReducer.authorizePeerCancel(
            current,
            incoming,
            authenticatedPeerId = "dev-peer-canceller",
        )
        val cancelled = assertIs<Reduction.Apply>(
            NotificationStateReducer.reduce(
                current,
                requireNotNull(authorized),
                localDeviceId = "dev-peer",
                allocator = allocator,
            ),
        ).state

        assertEquals("dev-peer", cancelled.originDevice)
        assertEquals("pkg|42|tag", cancelled.sourceNotificationKey)
        assertEquals("CANCELLED", cancelled.state)
        assertEquals(null, cancelled.mirrorLocalId)
    }

    @Test
    fun cancelFromUnpairedOriginIsRejectedWithoutChangingOwnership() {
        val current = state(sequence = 4, state = "ACTIVE", materialized = 4)
        val incoming = event(type = "notif.cancel", sequence = 5, origin = "spoof")
        assertEquals(
            null,
            NotificationStateReducer.authorizePeerCancel(current, incoming, "dev-peer-canceller"),
        )
    }

    @Test
    fun authenticatedOriginCancelWithoutPriorStateCreatesATombstone() {
        val incoming = event(type = "notif.cancel", sequence = 3, origin = "dev-peer")

        val authorized = NotificationStateReducer.authorizePeerCancel(
            current = null,
            event = incoming,
            authenticatedPeerId = "dev-peer",
        )
        val cancelled = assertIs<Reduction.Apply>(
            NotificationStateReducer.reduce(
                current = null,
                event = requireNotNull(authorized),
                localDeviceId = "dev-local",
                allocator = allocator,
            ),
        ).state

        assertEquals("CANCELLED", cancelled.state)
        assertEquals("dev-peer", cancelled.originDevice)
        assertEquals(null, cancelled.mirrorLocalId)
        assertEquals(null, cancelled.mirrorLocalTag)
    }

    @Test
    fun unknownCancelFromMismatchedOriginIsRejected() {
        val incoming = event(type = "notif.cancel", sequence = 3, origin = "spoof")

        assertEquals(
            null,
            NotificationStateReducer.authorizePeerCancel(
                current = null,
                event = incoming,
                authenticatedPeerId = "dev-peer",
            ),
        )
    }

    private fun event(
        type: String,
        sequence: Long,
        origin: String = "dev-peer",
    ) = InnerEventV2(
        msgId = "11111111-1111-4111-8111-111111111111",
        originDevice = origin,
        type = type,
        canonId = "dev-peer:pkg:1:",
        sequence = sequence,
        createdAt = 1_000,
        expiresAt = 2_000,
        payloadJson = "{\"title\":\"hello\"}",
    )

    private fun state(
        sequence: Long,
        state: String,
        materialized: Long,
        localId: Int? = null,
        localTag: String? = null,
    ) = CanonicalNotificationState(
        canonId = "dev-peer:pkg:1:",
        originDevice = "dev-peer",
        latestSequence = sequence,
        state = state,
        desiredPayloadJson = if (state == "ACTIVE") "{\"title\":\"hello\"}" else null,
        materializedSequence = materialized,
        sourceNotificationKey = null,
        mirrorLocalId = localId,
        mirrorLocalTag = localTag,
        peerCancelPending = false,
        updatedAt = 1_000,
    )
}
