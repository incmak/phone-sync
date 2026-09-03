package co.twinotify.core.call

import co.twinotify.core.actions.ActionInvocationExpiryScheduler
import co.twinotify.core.storage.ActionInvocation
import co.twinotify.core.storage.ActionInvocationOutboxCommitResult
import co.twinotify.core.storage.OutboundMessage
import co.twinotify.core.storage.CanonicalNotificationState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest

class MirrorCallControlInvokerTest {
    @Test
    fun intentRoundTripsExactIdentityAndRejectsNoncanonicalInputs() {
        val uri = MirrorCallControlIntent.dataUri("mirror tag/+", 41, CONTROL, CallControlKind.ANSWER)
        assertEquals(CallControlInvokeIdentity("mirror tag/+", 41, CONTROL, CallControlKind.ANSWER), MirrorCallControlIntent.parse(uri))
        assertNull(MirrorCallControlIntent.parse("$uri?leak=x"))
        assertNull(MirrorCallControlIntent.parse("twinotify://call-control/tag/0/$CONTROL/answer"))
        assertNull(MirrorCallControlIntent.parse("twinotify://call-control/tag/1/${CONTROL.uppercase()}/answer"))
        assertNull(MirrorCallControlIntent.parse("twinotify://call-control/tag/1/$CONTROL/unknown"))
        assertNull(MirrorCallControlIntent.parse("twinotify://user@call-control/tag/1/$CONTROL/answer"))
        assertNull(MirrorCallControlIntent.parse("twinotify://call-control:7/tag/1/$CONTROL/answer"))
        assertNull(MirrorCallControlIntent.parse("twinotify://call-control/tag/1/$CONTROL/answer/"))
        assertNull(MirrorCallControlIntent.parse("twinotify://call-control//1/$CONTROL/answer"))
    }

    @Test
    fun mirrorQueuesFromLockedDeviceBecauseTapIsExplicitCallUx() = runTest {
        val fixture = Fixture()
        assertEquals(MirrorCallControlInvokeResult.Queued(CONTROL), fixture.invoker.invoke(IDENTITY))
        assertEquals("call.control.invoke", fixture.row?.eventType)
        assertEquals(listOf("load", "encode", "commit", "signal", "schedule", "repost"), fixture.events)
    }

    @Test
    fun targetResolutionRequiresExactCurrentAdvertisedControl() {
        val state = canonical(
            """{"call_session_id":"$SESSION","state":"ringing","direction":"incoming","controls":[{"control_id":"$CONTROL","kind":"answer"}]}""",
        )
        assertEquals(target(), resolveMirrorCallControlTarget(IDENTITY, CANON, state))
        assertNull(resolveMirrorCallControlTarget(IDENTITY.copy(kind = CallControlKind.DECLINE), CANON, state))
        assertNull(resolveMirrorCallControlTarget(IDENTITY, CANON, state.copy(latestSequence = 3, state = "CANCELLED")))
        assertNull(resolveMirrorCallControlTarget(IDENTITY, CANON, state.copy(desiredPayloadJson = "{")))
        assertNull(
            resolveMirrorCallControlTarget(
                IDENTITY,
                CANON,
                state.copy(desiredPayloadJson = state.desiredPayloadJson!!.replace("\"kind\":\"answer\"", "\"kind\":\"answer\",\"caller\":\"x\"")),
            ),
        )
    }

    @Test
    fun missingOrStaleAdvertisedControlDoesNotQueue() = runTest {
        val fixture = Fixture(target = null)
        assertEquals(MirrorCallControlInvokeResult.Gone, fixture.invoker.invoke(IDENTITY))
        assertNull(fixture.invocation)
    }

    @Test
    fun capabilityUuidIsDurableInvocationIdentity() = runTest {
        val fixture = Fixture()
        fixture.invoker.invoke(IDENTITY)
        assertEquals(
            ActionInvocation(CONTROL, CANON, "answer", 2, null, "PENDING", 1_000, 16_000, 1_000),
            fixture.invocation,
        )
    }

    @Test
    fun cancellationPropagatesWithoutCommitting() = runTest {
        val fixture = Fixture(encodeFailure = CancellationException("stop"))
        try {
            fixture.invoker.invoke(IDENTITY)
            error("expected cancellation")
        } catch (_: CancellationException) {
            assertNull(fixture.invocation)
        }
    }

    private class Fixture(
        target: MirrorCallControlTarget? = target(),
        private val encodeFailure: Throwable? = null,
    ) {
        val events = mutableListOf<String>()
        var invocation: ActionInvocation? = null
        var row: OutboundMessage? = null
        val invoker = MirrorCallControlInvoker(
            loadTarget = MirrorCallControlTargetLoader { events += "load"; target },
            encode = CallControlInvokeRowEncoder {
                events += "encode"
                encodeFailure?.let { throw it }
                outbound().also { row = it }
            },
            commit = MirrorCallControlCommitter { value, _ ->
                events += "commit"; invocation = value; ActionInvocationOutboxCommitResult.Committed
            },
            signalTransport = { events += "signal" },
            scheduleExpiry = ActionInvocationExpiryScheduler { events += "schedule" },
            repost = MirrorCallControlReposter { events += "repost" },
        )
    }

    private companion object {
        const val SESSION = "11111111-1111-4111-8111-111111111111"
        const val CONTROL = "22222222-2222-4222-8222-22222222222a"
        const val CANON = "call:$SESSION"
        val IDENTITY = CallControlInvokeIdentity("call-mirror", 41, CONTROL, CallControlKind.ANSWER)
        fun target() = MirrorCallControlTarget(CANON, SESSION, 2, "call-mirror", 41, CONTROL, CallControlKind.ANSWER)
        fun canonical(payload: String) = CanonicalNotificationState(
            CANON, "origin-device", 2, "ACTIVE", payload, 2, null, 41, "call-mirror", false, 1_000,
        )
        fun outbound() = OutboundMessage(
            msgId = "33333333-3333-4333-8333-333333333333", canonId = null, sequence = null,
            eventType = "call.control.invoke", protocolVersion = 2, envelopeJson = "{}",
            envelopeSha256 = "0".repeat(64), byteSize = 2, createdAt = 1_000, expiresAt = 16_000,
            custodyAcceptedAt = null, custodyRoute = null, attempts = 0, nextAttemptAt = 1_000,
            state = "NEW", lastError = null, requiresPeerReceipt = false,
        )
    }
}
