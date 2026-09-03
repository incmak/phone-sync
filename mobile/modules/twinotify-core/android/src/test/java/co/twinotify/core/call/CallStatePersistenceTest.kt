package co.twinotify.core.call

import co.twinotify.core.storage.OutboundStateCommitResult
import org.json.JSONObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlinx.coroutines.runBlocking

class CallStatePersistenceTest {
    @Test
    fun persisterForwardsOnlyFrozenPrivacyBoundedEvent() = runBlocking {
        var observed: CallStateEvent? = null
        val persister = CallStatePersister { event ->
            observed = event
            CallStatePersistResult.Persisted(sequence = event.sequence, msgId = "msg-${event.sequence}")
        }

        val event = CallStateEvent(
            callSessionId = SESSION,
            state = "ringing",
            direction = CallDirection.INCOMING,
            sequence = 1,
        )
        val result = persister.persist(event)

        assertEquals(event, observed)
        assertEquals(CallStatePersistResult.Persisted(1, "msg-1"), result)
    }

    @Test
    fun persisterRejectsInvalidSessionOrSequenceBeforeDurableSink() = runBlocking {
        var calls = 0
        val persister = CallStatePersister { calls += 1; error("sink must not run") }

        assertFailsWith<IllegalArgumentException> {
            persister.persist(CallStateEvent("not-a-uuid", "ringing", CallDirection.UNKNOWN, 1))
        }
        assertFailsWith<IllegalArgumentException> {
            persister.persist(CallStateEvent(SESSION, "idle", CallDirection.UNKNOWN, 0))
        }
        assertEquals(0, calls)
    }

    @Test
    fun payloadSerializesDescriptorsButNeverPendingHandles() {
        val event = controllableRingingEvent()

        val payload = JSONObject(callStatePayloadJson(event))

        assertEquals(setOf("call_session_id", "state", "direction", "controls"), payload.keys().asSequence().toSet())
        assertEquals(2, payload.getJSONArray("controls").length())
        assertEquals(ANSWER_ID, payload.getJSONArray("controls").getJSONObject(0).getString("control_id"))
        assertEquals("answer", payload.getJSONArray("controls").getJSONObject(0).getString("kind"))
        assertEquals(false, payload.toString().contains("answer-token"))
    }

    @Test
    fun onlyCommittedGenerationInstallsAndCommittedControlFreeStatePurges() {
        val registry = CallCapabilityRegistry<String>()
        val committer = CallCapabilityGenerationCommitter(registry)
        val controllable = controllableRingingEvent()
        val canon = "call:$SESSION"

        committer.afterCommit(
            canon,
            controllable,
            controllable.pendingGenerationOf(String::class.java),
            OutboundStateCommitResult.Stale(3),
        )
        assertIs<CallCapabilityLookup.MissingGeneration>(
            registry.lookup(canon, 2, ANSWER_ID, CallControlKind.ANSWER),
        )

        committer.afterCommit(
            canon,
            controllable,
            controllable.pendingGenerationOf(String::class.java),
            OutboundStateCommitResult.Committed(0),
        )
        assertIs<CallCapabilityLookup.Found<String>>(
            registry.lookup(canon, 2, ANSWER_ID, CallControlKind.ANSWER),
        )

        committer.afterCommit(
            canon,
            controllable.copy(sequence = 3, controls = emptyList(), pendingGeneration = null),
            null,
            OutboundStateCommitResult.Stale(4),
        )
        assertIs<CallCapabilityLookup.Found<String>>(
            registry.lookup(canon, 2, ANSWER_ID, CallControlKind.ANSWER),
        )

        committer.afterCommit(
            canon,
            controllable.copy(sequence = 3, controls = emptyList(), pendingGeneration = null),
            null,
            OutboundStateCommitResult.Committed(0),
        )
        assertIs<CallCapabilityLookup.MissingGeneration>(
            registry.lookup(canon, 2, ANSWER_ID, CallControlKind.ANSWER),
        )
    }

    @Test
    fun staleOrdinaryCommitFailsSoCoordinatorCanRetryExactEvent() = runBlocking {
        val event = controllableRingingEvent()
        val persister = CallStatePersister { CallStatePersistResult.Stale(latestSequence = 3) }

        val failure = assertFailsWith<CallStatePersistenceException> { persister.persist(event) }

        assertEquals("call_state_stale", failure.code)
        assertEquals(3L, failure.latestSequence)
    }

    @Test
    fun typedGenerationBoundaryRejectsWrongHandleClass() {
        val event = controllableRingingEvent()

        assertFailsWith<IllegalArgumentException> {
            event.pendingGenerationOf(android.app.PendingIntent::class.java)
        }
    }

    private fun controllableRingingEvent() = CallStateEvent(
        callSessionId = SESSION,
        state = "ringing",
        direction = CallDirection.INCOMING,
        sequence = 2,
        controls = listOf(
            CallControlDescriptor(ANSWER_ID, CallControlKind.ANSWER),
            CallControlDescriptor(DECLINE_ID, CallControlKind.DECLINE),
        ),
        pendingGeneration = CallCapabilityGeneration(
            sequence = 2,
            sourceKey = "dialer-key",
            controls = mapOf(
                ANSWER_ID to RegisteredCallControl(CallControlKind.ANSWER, "answer-token"),
                DECLINE_ID to RegisteredCallControl(CallControlKind.DECLINE, "decline-token"),
            ),
        ),
    )

    private companion object {
        const val SESSION = "11111111-1111-4111-8111-111111111111"
        const val ANSWER_ID = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
        const val DECLINE_ID = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"
    }
}
