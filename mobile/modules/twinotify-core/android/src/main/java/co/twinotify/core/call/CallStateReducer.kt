package co.twinotify.core.call

import co.twinotify.core.service.LocalIdAllocator
import co.twinotify.core.service.NotificationStateReducer
import co.twinotify.core.storage.CanonicalNotificationState
import java.security.MessageDigest
import java.util.UUID
import org.json.JSONObject

sealed interface CallReduction {
    val state: CanonicalNotificationState
    data class Apply(override val state: CanonicalNotificationState) : CallReduction
    data class Duplicate(override val state: CanonicalNotificationState) : CallReduction
    data class LowerSequence(
        override val state: CanonicalNotificationState,
        val code: String = "call_sequence_lower",
    ) : CallReduction
    data class Conflict(
        override val state: CanonicalNotificationState,
        val code: String = "call_sequence_conflict",
    ) : CallReduction
}

/** Pure inbound call-state reduction; it carries no answer/reject/hang-up capability. */
object CallStateReducer {
    fun reduce(
        current: CanonicalNotificationState?,
        event: CallStateEvent,
        localDeviceId: String,
        allocator: LocalIdAllocator,
        updatedAt: Long = System.currentTimeMillis(),
        authenticatedDuplicate: Boolean = false,
    ): CallReduction = reduceInternal(
        current, localDeviceId, event, localDeviceId, allocator, updatedAt, authenticatedDuplicate,
    )

    private fun reduceInternal(
        current: CanonicalNotificationState?,
        originDevice: String,
        event: CallStateEvent,
        localDeviceId: String,
        allocator: LocalIdAllocator,
        updatedAt: Long,
        authenticatedDuplicate: Boolean,
    ): CallReduction {
        require(event.callSessionId == UUID.fromString(event.callSessionId).toString()) {
            "call session id must be a lower-case canonical UUID"
        }
        val canonId = "call:${event.callSessionId}"
        require(current == null || current.canonId == canonId) { "call canonical ID mismatch" }
        require(event.sequence > 0) { "call sequence must be positive" }
        require(event.state in setOf("ringing", "active", "idle")) { "unsupported call state" }
        if (current != null) {
            require(current.originDevice.isNotEmpty()) { "call origin must not be empty" }
            if (authenticatedDuplicate) return CallReduction.Duplicate(current)
            if (event.sequence < current.latestSequence) return CallReduction.LowerSequence(current)
            if (event.sequence == current.latestSequence) return CallReduction.Conflict(current)
        }
        if (event.state == "idle") require(current != null) { "idle requires an existing call session" }

        val remote = originDevice != localDeviceId
        val mirror = if (remote) {
            val id = current?.mirrorLocalId ?: allocator.nextId().also { require(it > 0) }
            val tag = current?.mirrorLocalTag ?: stableMirrorTag(canonId)
            id to tag
        } else null
        val payload = JSONObject()
            .put("call_session_id", event.callSessionId)
            .put("state", event.state)
            .put("direction", event.direction.wireValue)
            .toString()
        return CallReduction.Apply(
            CanonicalNotificationState(
                canonId = canonId,
                originDevice = current?.originDevice ?: originDevice,
                latestSequence = event.sequence,
                state = if (event.state == "idle") "CANCELLED" else "ACTIVE",
                desiredPayloadJson = if (event.state == "idle") null else payload,
                materializedSequence = current?.materializedSequence ?: 0L,
                sourceNotificationKey = null,
                mirrorLocalId = mirror?.first ?: current?.mirrorLocalId,
                mirrorLocalTag = mirror?.second ?: current?.mirrorLocalTag,
                peerCancelPending = current?.peerCancelPending ?: false,
                updatedAt = updatedAt.coerceAtLeast(current?.updatedAt ?: Long.MIN_VALUE),
            ),
        )
    }

    /** Inbound callers set origin separately; this helper keeps the event model privacy-only. */
    fun reduceInbound(
        current: CanonicalNotificationState?,
        originDevice: String,
        event: CallStateEvent,
        localDeviceId: String,
        allocator: LocalIdAllocator,
        updatedAt: Long = System.currentTimeMillis(),
        authenticatedDuplicate: Boolean = false,
    ): CallReduction {
        require(originDevice.isNotEmpty()) { "call origin must not be empty" }
        val result = reduceInternal(
            current, originDevice, event, localDeviceId, allocator, updatedAt, authenticatedDuplicate,
        )
        if (current != null && current.originDevice != originDevice) {
            require(originDevice == current.originDevice) { "call canonical origin cannot change" }
        }
        return when (result) {
            is CallReduction.Apply -> CallReduction.Apply(
                result.state.copy(originDevice = current?.originDevice ?: originDevice),
            )
            else -> result
        }
    }

    fun stableMirrorTag(canonId: String): String = "call-" + callStateSha256(canonId).take(24)
}

internal fun callStateSha256(value: String): String = MessageDigest.getInstance("SHA-256")
    .digest(value.toByteArray(Charsets.UTF_8))
    .joinToString("") { "%02x".format(it.toInt() and 0xff) }

private val CallDirection.wireValue: String
    get() = when (this) {
        CallDirection.INCOMING -> "incoming"
        CallDirection.OUTGOING -> "outgoing"
        CallDirection.UNKNOWN -> "unknown"
    }
