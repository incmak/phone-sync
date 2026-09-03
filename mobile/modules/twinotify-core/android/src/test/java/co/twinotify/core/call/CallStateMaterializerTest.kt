package co.twinotify.core.call

import co.twinotify.core.persistCallControlsThenReconfigure
import co.twinotify.core.service.LocalIdAllocator
import co.twinotify.core.service.ServiceConfig
import co.twinotify.core.service.mergeCallShutdownIntent
import co.twinotify.core.service.peerDisplayNameForCall
import co.twinotify.core.service.reconfigureCommittedCallControls
import co.twinotify.core.service.durableCallControlsValue
import co.twinotify.core.storage.ActionInvocation
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.json.JSONObject

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
    fun reducerTreatsSameSequenceAsConflictBecauseDuplicateIdentityIsJournalOwned() {
        val first = assertIs<CallReduction.Apply>(CallStateReducer.reduce(null, event("ringing", 1), "dev-local", LocalIdAllocator { 41 }))
        val conflict = assertIs<CallReduction.Conflict>(
            CallStateReducer.reduce(
                current = first.state,
                event = event("ringing", 1),
                localDeviceId = "dev-local",
                allocator = LocalIdAllocator { 99 },
            ),
        )
        assertEquals(first.state, conflict.state)
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
        assertEquals(CallNotificationMode.CALL_STYLE_CONDITIONAL_CONTROLS, CallStateMaterializer.mode)
        assertEquals("Incoming call", CallStateMaterializer.content(requireNotNull(applied.state.desiredPayloadJson)).title)
    }

    @Test
    fun inboundCallPersistsOnlyAdvertisedControlIdsAndKinds() {
        val event = event("ringing", 2).copy(
            controls = listOf(
                CallControlDescriptor(ANSWER, CallControlKind.ANSWER),
                CallControlDescriptor(DECLINE, CallControlKind.DECLINE),
            ),
        )

        val applied = assertIs<CallReduction.Apply>(
            CallStateReducer.reduceInbound(null, "dev-peer", event, "dev-local", LocalIdAllocator { 73 }),
        )

        val payload = JSONObject(requireNotNull(applied.state.desiredPayloadJson))
        val controls = payload.getJSONArray("controls")
        assertEquals(2, controls.length())
        assertEquals(setOf("control_id", "kind"), controls.getJSONObject(0).keys().asSequence().toSet())
        assertEquals(ANSWER, controls.getJSONObject(0).getString("control_id"))
        assertEquals("answer", controls.getJSONObject(0).getString("kind"))
        assertEquals(DECLINE, controls.getJSONObject(1).getString("control_id"))
        assertEquals("decline", controls.getJSONObject(1).getString("kind"))
        assertEquals(setOf("call_session_id", "state", "direction", "controls"), payload.keys().asSequence().toSet())
    }

    @Test
    fun stableCallTagBytesRemainFrozen() {
        assertEquals("call-a95b079d1d364881a4029f91", CallStateMaterializer.stableTag("call:$SESSION"))
        assertEquals(CallStateMaterializer.stableTag("call:$SESSION"), CallStateReducer.stableMirrorTag("call:$SESSION"))
    }

    @Test
    fun ringingWithCompleteUnusedControlsUsesNativeIncomingCallStyle() {
        val model = CallStateMaterializer.model(
            remoteState(
                state = "ringing",
                direction = CallDirection.INCOMING,
                controls = listOf(
                    CallControlDescriptor(ANSWER, CallControlKind.ANSWER),
                    CallControlDescriptor(DECLINE, CallControlKind.DECLINE),
                ),
            ),
            invocations = emptyList(),
            peerName = "Pixel 9",
        )

        val incoming = assertIs<CallNotificationModel.IncomingControllable>(model)
        assertEquals("Call on Pixel 9", incoming.personName)
        assertEquals(ANSWER, incoming.answer.controlId)
        assertEquals(DECLINE, incoming.decline.controlId)
    }

    @Test
    fun missingPartialUsedOrFailedControlsNeverDrawFakeButtons() {
        assertIs<CallNotificationModel.StateOnly>(
            CallStateMaterializer.model(remoteState("ringing"), emptyList(), "Pixel 9"),
        )
        assertIs<CallNotificationModel.StateOnly>(
            CallStateMaterializer.model(
                remoteState(
                    state = "ringing",
                    controls = listOf(CallControlDescriptor(ANSWER, CallControlKind.ANSWER)),
                ),
                emptyList(),
                "Pixel 9",
            ),
        )
        assertIs<CallNotificationModel.Attempted>(
            CallStateMaterializer.model(
                remoteState(
                    state = "ringing",
                    controls = listOf(
                        CallControlDescriptor(ANSWER, CallControlKind.ANSWER),
                        CallControlDescriptor(DECLINE, CallControlKind.DECLINE),
                    ),
                ),
                listOf(invocation(ANSWER, "PENDING")),
                "Pixel 9",
            ),
        )
        assertIs<CallNotificationModel.Attempted>(
            CallStateMaterializer.model(
                remoteState(
                    state = "ringing",
                    controls = listOf(
                        CallControlDescriptor(ANSWER, CallControlKind.ANSWER),
                        CallControlDescriptor(DECLINE, CallControlKind.DECLINE),
                    ),
                ),
                listOf(invocation(ANSWER, "FAILED")),
                "Pixel 9",
            ),
        )
    }

    @Test
    fun activeHangupRequiresIncomingDirectionAndCanonicalControlId() {
        assertIs<CallNotificationModel.OngoingControllable>(
            CallStateMaterializer.model(
                remoteState(
                    state = "active",
                    controls = listOf(CallControlDescriptor(HANG_UP, CallControlKind.HANG_UP)),
                ),
                emptyList(),
                "Pixel 9",
            ),
        )
        assertIs<CallNotificationModel.StateOnly>(
            CallStateMaterializer.model(
                remoteState(
                    state = "active",
                    direction = CallDirection.UNKNOWN,
                    controls = listOf(CallControlDescriptor(HANG_UP, CallControlKind.HANG_UP)),
                ),
                emptyList(),
                "Pixel 9",
            ),
        )
        assertIs<CallNotificationModel.StateOnly>(
            CallStateMaterializer.model(
                remoteState(
                    state = "active",
                    controls = listOf(CallControlDescriptor("not-a-uuid", CallControlKind.HANG_UP)),
                ),
                emptyList(),
                "Pixel 9",
            ),
        )
    }

    @Test
    fun pairedDeviceFallbackNeverInfersCallerIdentity() {
        val model = CallStateMaterializer.model(remoteState("ringing"), emptyList(), "  ")
        assertEquals("Call on Paired device", model.personName)
    }

    @Test
    fun reboundPeerNameIsNeverAppliedToAnOlderCallOrigin() {
        assertNull(peerDisplayNameForCall("old-peer", "new-peer", "New phone"))
        assertEquals("Current phone", peerDisplayNameForCall("peer", "peer", "Current phone"))
    }

    @Test
    fun disablingCallStateAtomicallyDisablesControlsButControlsOnlyChangePreservesCallState() {
        val enabled = ServiceConfig(
            enabled = true,
            callCaptureEnabled = true,
            callControlsEnabled = true,
        )

        assertEquals(
            enabled.copy(callCaptureEnabled = false, callControlsEnabled = false),
            mergeCallShutdownIntent(
                enabled,
                CallShutdownConfigIntent(disableCallCapture = true, disableService = false),
                now = 5L,
            ),
        )
        assertTrue(enabled.copy(callControlsEnabled = false).callCaptureEnabled)
        assertFalse(durableCallControlsValue(callCaptureEnabled = false, requestedEnabled = true))
        assertTrue(durableCallControlsValue(callCaptureEnabled = true, requestedEnabled = true))
    }

    @Test
    fun controlsLifecycleRereadsCommittedConfigBeforeReconfiguringWithoutTerminalizingCapture() = runTest {
        val order = mutableListOf<String>()
        val applied = mutableListOf<Triple<Boolean, Boolean, Boolean>>()

        val durable = reconfigureCommittedCallControls(
            readConfig = {
                order += "read-committed"
                ServiceConfig(enabled = true, callCaptureEnabled = true, callControlsEnabled = false)
            },
            reconfigure = { service, capture, controls ->
                order += "reconfigure"
                applied += Triple(service, capture, controls)
            },
        )

        assertFalse(durable)
        assertEquals(listOf("read-committed", "reconfigure"), order)
        assertEquals(listOf(Triple(true, true, false)), applied)
    }

    @Test
    fun postCommitReconfigureFailureReturnsDurableTruthInsteadOfTriggeringUiRollback() = runTest {
        val order = mutableListOf<String>()

        val durable = persistCallControlsThenReconfigure(
            persist = {
                order += "persist"
                false
            },
            reconfigure = {
                order += "reconfigure"
                throw IllegalStateException("service stopped")
            },
            onPostCommitFailure = { order += "fallback-$it" },
        )

        assertFalse(durable)
        assertEquals(listOf("persist", "reconfigure", "fallback-false"), order)
    }

    private fun remoteState(
        state: String,
        direction: CallDirection = CallDirection.INCOMING,
        controls: List<CallControlDescriptor> = emptyList(),
    ) = assertIs<CallReduction.Apply>(
        CallStateReducer.reduceInbound(
            current = null,
            originDevice = "dev-peer",
            event = CallStateEvent(SESSION, state, direction, 1L, controls),
            localDeviceId = "dev-local",
            allocator = LocalIdAllocator { 73 },
        ),
    ).state

    private fun invocation(controlId: String, state: String) = ActionInvocation(
        invocationId = controlId,
        canonId = "call:$SESSION",
        actionId = CallControlKind.ANSWER.wire,
        notificationSequence = 1L,
        replyText = null,
        state = state,
        createdAt = 1L,
        expiresAt = 16L,
        updatedAt = 2L,
    )

    private fun event(state: String, sequence: Long) = CallStateEvent(
        callSessionId = SESSION,
        state = state,
        direction = CallDirection.INCOMING,
        sequence = sequence,
    )

    private companion object {
        const val SESSION = "11111111-1111-4111-8111-111111111111"
        const val ANSWER = "22222222-2222-4222-8222-222222222222"
        const val DECLINE = "33333333-3333-4333-8333-333333333333"
        const val HANG_UP = "44444444-4444-4444-8444-444444444444"
    }
}
