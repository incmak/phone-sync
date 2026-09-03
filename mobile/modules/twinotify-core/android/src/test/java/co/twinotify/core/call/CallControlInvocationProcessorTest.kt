package co.twinotify.core.call

import co.twinotify.core.actions.ActionClaimDecision
import co.twinotify.core.storage.ActionExecution
import co.twinotify.core.storage.InboundMessage
import co.twinotify.core.storage.OutboundMessage
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest

class CallControlInvocationProcessorTest {
    @Test
    fun durableClaimPrecedesMutableStateLookupAndDispatch() = runTest {
        val events = mutableListOf<String>()
        val fixture = Fixture(events = events)
        assertEquals(CallControlProcessResult.Completed("dispatched"), fixture.processor.process(request()))
        assertEquals(listOf("claim", "schedule", "state", "registry", "dispatch", "complete:dispatched"), events)
    }

    @Test
    fun duplicateCapabilityExecutesPendingIntentOnceAndReplaysStoredResult() = runTest {
        val fixture = Fixture()
        assertEquals(CallControlProcessResult.Completed("dispatched"), fixture.processor.process(request(MESSAGE_ONE)))
        fixture.claimDecision = ActionClaimDecision.Replay("dispatched")
        assertEquals(CallControlProcessResult.Replayed("dispatched"), fixture.processor.process(request(MESSAGE_TWO)))
        assertEquals(1, fixture.dispatches.get())
        assertEquals("dispatched", fixture.replayedStatus)
    }

    @Test
    fun claimedCrashBecomesOutcomeUnknownAndNeverRedispatches() = runTest {
        val fixture = Fixture(claim = ActionClaimDecision.Replay("outcome_unknown"))
        assertEquals(CallControlProcessResult.Replayed("outcome_unknown"), fixture.processor.process(request(MESSAGE_TWO)))
        assertEquals(0, fixture.dispatches.get())
    }

    @Test
    fun freshnessAndFutureSkewFailClosed() = runTest {
        assertEquals("expired", Fixture(now = 16_001).completed(request(invokedAt = 1_000)))
        assertEquals("dispatched", Fixture(now = 16_000).completed(request(invokedAt = 1_000)))
        assertEquals("expired", Fixture(now = 1_000).completed(request(invokedAt = 31_001)))
        assertEquals("dispatched", Fixture(now = 1_000).completed(request(invokedAt = 31_000)))
    }

    @Test
    fun wrongSessionSequenceKindAndPurgedCapabilityNeverDispatch() = runTest {
        assertEquals("call_gone", Fixture(state = null).completed(request()))
        assertEquals("call_gone", Fixture().completed(request(canonId = "call:$OTHER_SESSION")))
        assertEquals("stale_state", Fixture(state = LocalCallControlState(3, CallDirection.INCOMING)).completed(request()))
        assertEquals("call_gone", Fixture(state = LocalCallControlState(2, CallDirection.OUTGOING)).completed(request()))
        assertEquals("capability_gone", Fixture(lookup = CallCapabilityLookup.MissingControl).completed(request()))
        assertEquals("capability_gone", Fixture().completed(request(kind = CallControlKind.DECLINE)))
    }

    @Test
    fun dispatchFailuresAreBoundedAndCancellationPropagates() = runTest {
        assertEquals("failed", Fixture(dispatchFailure = IllegalStateException("private payload")).completed(request()))
        try {
            Fixture(dispatchFailure = CancellationException("stop")).processor.process(request())
            error("expected cancellation")
        } catch (_: CancellationException) {
            assertTrue(true)
        }
    }

    @Test
    fun failedResultSealingLeavesClaimForCrashRecovery() = runTest {
        val fixture = Fixture(resultFailure = IllegalStateException("seal failed"))
        assertEquals(CallControlProcessResult.CompletionLost, fixture.processor.process(request()))
        assertEquals(null, fixture.completedStatus)
    }

    @Test
    fun mismatchedInvocationAndControlIdsFailBeforeMutableLookup() = runTest {
        val events = mutableListOf<String>()
        val fixture = Fixture(events = events)
        val result = fixture.processor.process(request().copy(invocationId = OTHER_CONTROL))
        assertEquals(CallControlProcessResult.Completed("failed"), result)
        assertEquals(listOf("claim", "schedule", "complete:failed"), events)
    }

    private class Fixture(
        val events: MutableList<String> = mutableListOf(),
        claim: ActionClaimDecision = ActionClaimDecision.Execute,
        now: Long = 10_000,
        state: LocalCallControlState? = LocalCallControlState(2, CallDirection.INCOMING),
        lookup: CallCapabilityLookup<String> = CallCapabilityLookup.Found("pending-intent"),
        private val dispatchFailure: Throwable? = null,
        private val resultFailure: Throwable? = null,
    ) : CallControlClaimJournal {
        var claimDecision = claim
        val dispatches = AtomicInteger()
        var completedStatus: String? = null
        var replayedStatus: String? = null
        val processor = CallControlInvocationProcessor(
            journal = this,
            currentLocalCallState = { events += "state"; state },
            registryLookup = { _, _, _, kind ->
                events += "registry"
                if (kind != CallControlKind.ANSWER) CallCapabilityLookup.MissingControl else lookup
            },
            executor = CallControlExecutor {
                events += "dispatch"; dispatches.incrementAndGet(); dispatchFailure?.let { throw it }; true
            },
            resultEncoder = CallControlResultRowEncoder { input ->
                resultFailure?.let { throw it }
                resultRow(input.status)
            },
            wakeScheduler = CallControlClaimWakeScheduler { events += "schedule" },
            clock = { now },
        )

        suspend fun completed(value: CallControlInvokeRequest): String {
            val result = processor.process(value)
            return (result as CallControlProcessResult.Completed).status
        }

        override suspend fun claim(inbound: InboundMessage, execution: ActionExecution, now: Long): ActionClaimDecision {
            events += "claim"; return claimDecision
        }
        override suspend fun completeAndEnqueue(invocationId: String, status: String, now: Long, result: OutboundMessage): Boolean {
            events += "complete:$status"; completedStatus = status; return true
        }
        override suspend fun replayResult(invocationId: String, status: String, result: OutboundMessage): Boolean {
            replayedStatus = status; return true
        }

        private fun resultRow(status: String) = OutboundMessage(
            "33333333-3333-4333-8333-${status.hashCode().toUInt().toString().padStart(12, '0').takeLast(12)}",
            null, null, "call.control.result", 2, "{}", "b".repeat(64), 2, 1_000, 301_000,
            null, null, 0, 1_000, "NEW", null, false,
        )
    }

    private fun request(
        msgId: String = MESSAGE_ONE,
        canonId: String = CANON,
        invokedAt: Long = 1_000,
        kind: CallControlKind = CallControlKind.ANSWER,
    ) = CallControlInvokeRequest(inbound(msgId), CONTROL, canonId, SESSION, 2, CONTROL, kind, invokedAt)

    private fun inbound(msgId: String) = InboundMessage(
        msgId, "mirror-device", "a".repeat(64), "call.control.invoke", null, null,
        "APPLIED", 1_000, 1_000, null, "READY",
    )

    private companion object {
        const val SESSION = "11111111-1111-4111-8111-111111111111"
        const val OTHER_SESSION = "99999999-9999-4999-8999-999999999999"
        const val CONTROL = "22222222-2222-4222-8222-222222222222"
        const val OTHER_CONTROL = "77777777-7777-4777-8777-777777777777"
        const val CANON = "call:$SESSION"
        const val MESSAGE_ONE = "44444444-4444-4444-8444-444444444444"
        const val MESSAGE_TWO = "55555555-5555-4555-8555-555555555555"
    }
}
