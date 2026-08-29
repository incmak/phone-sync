package co.twinotify.core.service

import co.twinotify.core.protocol.InnerEventV2
import co.twinotify.core.storage.CanonicalNotificationState
import java.security.MessageDigest

/** A narrow allocator boundary makes local notification IDs deterministic in tests and durable in production. */
fun interface LocalIdAllocator {
    fun nextId(): Int
}

/** A notification event stripped of transport concerns for callers that do not use v2 envelopes. */
data class NotificationStateEvent(
    val type: String,
    val canonId: String,
    val originDevice: String,
    val sequence: Long,
    val payloadJson: String?,
    val createdAt: Long,
)

sealed interface Reduction {
    val state: CanonicalNotificationState

    data class Apply(override val state: CanonicalNotificationState) : Reduction
    data class Stale(override val state: CanonicalNotificationState) : Reduction
}

/** Pure desired-state transition logic. It never invokes Android APIs or Room. */
object NotificationStateReducer {
    /**
     * A paired peer may cancel a canonical notification it did not originate. Authentication
     * proves the emitter is the paired peer; this normalization preserves the canonical owner's
     * source-vs-mirror materialization semantics and rejects any other origin change.
     */
    fun authorizePeerCancel(
        current: CanonicalNotificationState?,
        event: InnerEventV2,
        authenticatedPeerId: String,
    ): InnerEventV2? {
        if (event.type != "notif.cancel") return event
        // Offline compaction may legitimately leave only a cancel when a notification was posted
        // and removed before delivery. Preserve that cancellation tombstone, but only when the
        // claimed origin is the authenticated peer; a peer-dismissal still requires known state.
        if (current == null) {
            return event.takeIf { event.originDevice == authenticatedPeerId }
        }
        if (event.originDevice == current.originDevice) return event
        if (event.originDevice != authenticatedPeerId) return null
        return event.copy(originDevice = current.originDevice)
    }

    fun reduce(
        current: CanonicalNotificationState?,
        event: InnerEventV2,
        localDeviceId: String,
        allocator: LocalIdAllocator,
        updatedAt: Long = event.createdAt,
    ): Reduction {
        val canonId = requireNotNull(event.canonId) { "notification event requires canon_id" }
        val sequence = requireNotNull(event.sequence) { "notification event requires sequence" }
        return reduce(
            current = current,
            event = NotificationStateEvent(
                type = event.type,
                canonId = canonId,
                originDevice = event.originDevice,
                sequence = sequence,
                payloadJson = event.payloadJson,
                createdAt = event.createdAt,
            ),
            localDeviceId = localDeviceId,
            allocator = allocator,
            updatedAt = updatedAt,
        )
    }

    fun reduce(
        current: CanonicalNotificationState?,
        event: NotificationStateEvent,
        localDeviceId: String,
        allocator: LocalIdAllocator,
        updatedAt: Long = event.createdAt,
    ): Reduction {
        require(event.type == "notif.post" || event.type == "notif.update" || event.type == "notif.cancel") {
            "unsupported notification state event ${event.type}"
        }
        require(event.canonId.isNotEmpty()) { "notification event canon_id must not be empty" }
        require(event.originDevice.isNotEmpty()) { "notification event origin_device must not be empty" }
        require(event.sequence > 0) { "notification event sequence must be positive" }
        if (current != null) {
            require(current.canonId == event.canonId) { "canonical ID mismatch" }
            require(current.originDevice == event.originDevice) {
                "canonical origin cannot change for ${event.canonId}"
            }
            if (event.sequence <= current.latestSequence) return Reduction.Stale(current)
        }

        val isCancel = event.type == "notif.cancel"
        val isLocalSource = event.originDevice == localDeviceId
        val existingMirrorId = current?.mirrorLocalId
        val existingMirrorTag = current?.mirrorLocalTag
        val mirrorIdentity = if (isLocalSource || isCancel && current == null) {
            null
        } else {
            val id = existingMirrorId ?: allocator.nextId().also {
                require(it > 0) { "local mirror ID must be positive" }
            }
            val tag = existingMirrorTag ?: stableMirrorTag(event.canonId)
            id to tag
        }

        return Reduction.Apply(
            CanonicalNotificationState(
                canonId = event.canonId,
                originDevice = event.originDevice,
                latestSequence = event.sequence,
                state = if (isCancel) "CANCELLED" else "ACTIVE",
                desiredPayloadJson = if (isCancel) null else requireNotNull(event.payloadJson) {
                    "${event.type} requires a payload"
                },
                materializedSequence = current?.materializedSequence ?: 0L,
                sourceNotificationKey = current?.sourceNotificationKey,
                mirrorLocalId = mirrorIdentity?.first,
                mirrorLocalTag = mirrorIdentity?.second,
                peerCancelPending = current?.peerCancelPending ?: false,
                updatedAt = updatedAt.coerceAtLeast(current?.updatedAt ?: Long.MIN_VALUE),
            ),
        )
    }

    fun stableMirrorTag(canonId: String): String = MIRROR_TAG_PREFIX + sha256(canonId).take(24)

    fun isMirrorTag(tag: String?): Boolean = tag?.startsWith(MIRROR_TAG_PREFIX) == true

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xff) }

    private const val MIRROR_TAG_PREFIX = "mirror-"
}
