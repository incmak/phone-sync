package co.twinotify.core.call

import co.twinotify.core.service.LocalIdAllocator
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class CallStateMaterializerTest {
    @Test
    fun ringingActiveIdleUseOneStableMirrorAndIdleCancels() {
        val ringing = assertIs<CallReduction.Apply>(CallStateReducer.reduce(null, event("ringing", 1), "dev-local", LocalIdAllocator { 41 }))
        val active = assertIs<CallReduction.Apply>(CallStateReducer.reduce(ringing.state, event("active", 2), "dev-local", LocalIdAllocator { 99 }))
        val idle = assertIs<CallReduction.Apply>(CallStateReducer.reduce(active.state, event("idle", 3), "dev-local", LocalIdAllocator { 99 }))

        assertEquals("ACTIVE", ringing.state.state)
        assertEquals(ringing.state.mirrorLocalId, active.state.mirrorLocalId)
        assertEquals(ringing.state.mirrorLocalTag, active.state.mirrorLocalTag)
        assertEquals("CANCELLED", idle.state.state)
        assertEquals(null, idle.state.desiredPayloadJson)
        assertEquals(active.state.mirrorLocalId, idle.state.mirrorLocalId)
    }

    @Test
    fun lowerSequenceIsExplicitlyRejectedWithoutChangingCallMirror() {
        val first = assertIs<CallReduction.Apply>(CallStateReducer.reduce(null, event("ringing", 2), "dev-local", LocalIdAllocator { 41 }))
        val lower = assertIs<CallReduction.LowerSequence>(CallStateReducer.reduce(first.state, event("active", 1), "dev-local", LocalIdAllocator { 99 }))
        assertEquals(first.state, lower.state)
        assertEquals("call_sequence_lower", lower.code)
    }

    @Test
    fun sameSequenceWithDifferentAuthenticatedContentIsConflict() {
        val first = assertIs<CallReduction.Apply>(CallStateReducer.reduce(null, event("ringing", 1), "dev-local", LocalIdAllocator { 41 }))
        val conflict = assertIs<CallReduction.Conflict>(CallStateReducer.reduce(first.state, event("active", 1), "dev-local", LocalIdAllocator { 99 }))
        assertEquals(first.state, conflict.state)
        assertEquals("call_sequence_conflict", conflict.code)
    }

    @Test
    fun exactDuplicateRequiresAuthenticatedJournalMatch() {
        val first = assertIs<CallReduction.Apply>(CallStateReducer.reduce(null, event("ringing", 1), "dev-local", LocalIdAllocator { 41 }))
        val duplicate = assertIs<CallReduction.Duplicate>(
            CallStateReducer.reduce(
                current = first.state,
                event = event("ringing", 1),
                localDeviceId = "dev-local",
                allocator = LocalIdAllocator { 99 },
                authenticatedDuplicate = true,
            ),
        )
        assertEquals(first.state, duplicate.state)
    }

    @Test
    fun remoteCallGetsStableMirrorIdentityAndExplicitActionFreeCapability() {
        val applied = assertIs<CallReduction.Apply>(
            CallStateReducer.reduceInbound(
                current = null,
                originDevice = "dev-peer",
                event = event("ringing", 1),
                localDeviceId = "dev-local",
                allocator = LocalIdAllocator { 73 },
            ),
        )

        assertEquals(73, applied.state.mirrorLocalId)
        assertEquals(CallStateMaterializer.stableTag("call:$SESSION"), applied.state.mirrorLocalTag)
        assertEquals(CallNotificationMode.GENERIC_CATEGORY_CALL, CallStateMaterializer.mode)
        assertEquals("Incoming call", CallStateMaterializer.content(requireNotNull(applied.state.desiredPayloadJson)).title)
    }

    @Test
    fun stableCallTagBytesRemainFrozen() {
        assertEquals("call-a95b079d1d364881a4029f91", CallStateMaterializer.stableTag("call:$SESSION"))
        assertEquals(CallStateMaterializer.stableTag("call:$SESSION"), CallStateReducer.stableMirrorTag("call:$SESSION"))
    }

    private fun event(state: String, sequence: Long) = CallStateEvent(
        callSessionId = SESSION,
        state = state,
        direction = CallDirection.INCOMING,
        sequence = sequence,
    )

    private companion object {
        const val SESSION = "11111111-1111-4111-8111-111111111111"
    }
}
