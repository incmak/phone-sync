package co.twinotify.core.call

import android.content.Context
import co.twinotify.core.listener.DurableCapturePersister
import java.util.UUID

sealed interface CallStatePersistResult {
    data class Persisted(val sequence: Long, val msgId: String) : CallStatePersistResult
    data class Duplicate(val sequence: Long, val msgId: String?) : CallStatePersistResult
    data class Stale(val latestSequence: Long) : CallStatePersistResult
}
fun interface CallStateSink {
    suspend fun persist(event: CallStateEvent): CallStatePersistResult
}

/** Validates the privacy-bounded event before handing it to the authenticated Room/outbox sink. */
class CallStatePersister(private val sink: CallStateSink) {
    constructor(context: Context) : this(
        CallStateSink { event -> DurableCapturePersister(context.applicationContext).persistCallState(event) },
    )

    suspend fun persist(event: CallStateEvent): CallStatePersistResult {
        require(event.state in setOf("ringing", "active", "idle")) { "unsupported call state" }
        require(event.sequence > 0) { "call sequence must be positive" }
        require(event.callSessionId == UUID.fromString(event.callSessionId).toString()) {
            "call session id must be a lower-case canonical UUID"
        }
        return sink.persist(event)
    }
}
