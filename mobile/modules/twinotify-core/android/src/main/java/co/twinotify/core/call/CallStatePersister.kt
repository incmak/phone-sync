package co.twinotify.core.call

import android.content.Context
import co.twinotify.core.listener.DurableCapturePersister
import java.util.UUID

sealed interface CallStatePersistResult {
    data class Persisted(val sequence: Long, val msgId: String) : CallStatePersistResult
    data class Duplicate(val sequence: Long, val msgId: String?) : CallStatePersistResult
    data class Stale(val latestSequence: Long) : CallStatePersistResult
    data object OwnershipLost : CallStatePersistResult
}
fun interface CallStateSink {
    suspend fun persist(event: CallStateEvent): CallStatePersistResult
}
internal fun interface CallRecoveryStateSink {
    suspend fun persistForRecovery(
        event: CallStateEvent,
        expectedLocalOrigin: String,
    ): CallStatePersistResult
}

/** Validates the privacy-bounded event before handing it to the authenticated Room/outbox sink. */
class CallStatePersister internal constructor(
    private val sink: CallStateSink,
    private val recoverySink: CallRecoveryStateSink,
) {
    constructor(sink: CallStateSink) : this(
        sink,
        CallRecoveryStateSink { event, _ -> sink.persist(event) },
    )

    constructor(context: Context) : this(
        sink = CallStateSink { event ->
            DurableCapturePersister(context.applicationContext).persistCallState(event)
        },
        recoverySink = CallRecoveryStateSink { event, expectedLocalOrigin ->
            DurableCapturePersister(context.applicationContext)
                .persistRecoveredCallState(event, expectedLocalOrigin)
        },
    )

    suspend fun persist(event: CallStateEvent): CallStatePersistResult {
        validate(event)
        return sink.persist(event)
    }

    internal suspend fun persistForRecovery(
        event: CallStateEvent,
        expectedLocalOrigin: String,
    ): CallStatePersistResult {
        validate(event)
        require(expectedLocalOrigin.isNotEmpty()) { "expected local origin must not be empty" }
        return recoverySink.persistForRecovery(event, expectedLocalOrigin)
    }

    private fun validate(event: CallStateEvent) {
        require(event.state in setOf("ringing", "active", "idle")) { "unsupported call state" }
        require(event.sequence > 0) { "call sequence must be positive" }
        require(event.callSessionId == UUID.fromString(event.callSessionId).toString()) {
            "call session id must be a lower-case canonical UUID"
        }
    }
}
