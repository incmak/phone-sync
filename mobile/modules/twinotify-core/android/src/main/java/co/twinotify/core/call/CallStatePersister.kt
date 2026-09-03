package co.twinotify.core.call

import android.content.Context
import co.twinotify.core.listener.DurableCapturePersister
import co.twinotify.core.storage.OutboundStateCommitResult
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

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

class CallStatePersistenceException(
    val code: String,
    val latestSequence: Long? = null,
) : Exception(code)

/** Runtime-check the sole Android handle boundary before installing into a typed registry. */
internal fun <T : Any> CallStateEvent.pendingGenerationOf(
    handleType: Class<T>,
): CallCapabilityGeneration<T>? {
    val generation = pendingGeneration ?: return null
    return CallCapabilityGeneration(
        sequence = generation.sequence,
        sourceKey = generation.sourceKey,
        controls = generation.controls.mapValues { (_, control) ->
            require(handleType.isInstance(control.handle)) { "unexpected call capability handle type" }
            RegisteredCallControl(control.kind, handleType.cast(control.handle))
        },
    )
}

/** Installs only a capability generation whose exact state/outbox transaction committed. */
internal class CallCapabilityGenerationCommitter<T>(
    private val registry: CallCapabilityRegistry<T>,
) {
    fun afterCommit(
        canonId: String,
        event: CallStateEvent,
        generation: CallCapabilityGeneration<T>?,
        result: OutboundStateCommitResult,
    ) {
        if (result !is OutboundStateCommitResult.Committed) return
        if (generation == null) {
            require(event.controls.isEmpty()) { "serialized controls require a typed generation" }
            registry.purge(canonId)
            return
        }
        registry.install(canonId, generation.copy(sequence = event.sequence))
    }
}

internal fun callStatePayloadJson(event: CallStateEvent): String = JSONObject()
    .put("call_session_id", event.callSessionId)
    .put("state", event.state)
    .put(
        "direction",
        when (event.direction) {
            CallDirection.INCOMING -> "incoming"
            CallDirection.OUTGOING -> "outgoing"
            CallDirection.UNKNOWN -> "unknown"
        },
    )
    .put(
        "controls",
        JSONArray().apply {
            event.controls.forEach { descriptor ->
                put(
                    JSONObject()
                        .put("control_id", descriptor.controlId)
                        .put("kind", descriptor.kind.wire),
                )
            }
        },
    )
    .toString()

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
        return when (val result = sink.persist(event)) {
            is CallStatePersistResult.Stale -> throw CallStatePersistenceException(
                "call_state_stale",
                result.latestSequence,
            )
            CallStatePersistResult.OwnershipLost -> throw CallStatePersistenceException("call_state_ownership_lost")
            else -> result
        }
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
        val legalKinds = when {
            event.state == "ringing" && event.direction == CallDirection.INCOMING ->
                setOf(CallControlKind.ANSWER, CallControlKind.DECLINE)
            event.state == "active" && event.direction == CallDirection.INCOMING ->
                setOf(CallControlKind.HANG_UP)
            else -> emptySet()
        }
        require(
            event.controls.isEmpty() ||
                event.controls.size == legalKinds.size &&
                event.controls.mapTo(linkedSetOf()) { it.kind } == legalKinds
        ) {
            "illegal controls for call state"
        }
        require(event.controls.map { it.controlId }.distinct().size == event.controls.size) {
            "call control ids must be unique"
        }
        event.controls.forEach {
            require(it.controlId == UUID.fromString(it.controlId).toString()) {
                "call control id must be a lower-case canonical UUID"
            }
        }
        val pending = event.pendingGeneration
        require((pending == null) == event.controls.isEmpty()) {
            "pending generation must exactly match serialized controls"
        }
        if (pending != null) {
            require(pending.sequence == event.sequence && pending.sourceKey.isNotEmpty()) {
                "pending generation identity mismatch"
            }
            require(
                pending.controls.mapValues { it.value.kind } ==
                    event.controls.associate { it.controlId to it.kind },
            ) { "pending generation controls mismatch" }
        }
    }
}
