package co.twinotify.core.call

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
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

    private companion object {
        const val SESSION = "11111111-1111-4111-8111-111111111111"
    }
}
