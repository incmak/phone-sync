package co.twinotify.core.listener

import co.twinotify.core.storage.CanonicalNotificationState

internal data class MirrorNotificationIdentity(
    val tag: String,
    val id: Int,
)

/** Pure reconciliation rule: persisted ACTIVE rows absent from the platform snapshot cancel. */
internal object CaptureReconciliation {
    fun missingActiveStates(
        states: List<CanonicalNotificationState>,
        liveSourceKeys: Set<String>,
    ): List<CanonicalNotificationState> = states.filter { state ->
        state.state == "ACTIVE" &&
            !state.sourceNotificationKey.isNullOrEmpty() &&
            state.sourceNotificationKey !in liveSourceKeys
    }

    /** A peer-origin row represents this device's posted mirror. */
    fun missingPeerMirrorStates(
        states: List<CanonicalNotificationState>,
        liveMirrorIdentities: Set<MirrorNotificationIdentity>,
    ): List<CanonicalNotificationState> = states.filter { state ->
        !state.canonId.startsWith("call:") &&
            state.state == "ACTIVE" &&
            state.mirrorLocalId != null &&
            state.mirrorLocalTag != null &&
            MirrorNotificationIdentity(state.mirrorLocalTag, state.mirrorLocalId) !in liveMirrorIdentities
    }

    /**
     * Rebuilds only final desired state from a fresh listener snapshot and durable ACTIVE rows.
     * The returned sequence is consumed immediately in bounded capacity windows; it is never an
     * overflow queue and retains no rejected canonical IDs.
     */
    fun recoveryCommands(
        originDevice: String,
        snapshots: List<SourceNotificationSnapshot>,
        states: List<CanonicalNotificationState>,
        removedAt: Long,
        peerMirrorStates: List<CanonicalNotificationState> = emptyList(),
        liveMirrorIdentities: Set<MirrorNotificationIdentity> = emptySet(),
    ): Sequence<CaptureCommand> = sequence {
        val liveSourceKeys = snapshots.mapTo(hashSetOf()) { it.sourceKey }
        snapshots.forEach { snapshot ->
            yield(
                PostCommand(
                    canonId = CanonIdBuilder.build(originDevice, snapshot.packageName, snapshot.id, snapshot.tag),
                    sourceKey = snapshot.sourceKey,
                    snapshot = snapshot,
                ),
            )
        }
        missingActiveStates(states, liveSourceKeys).forEach { state ->
            yield(
                RemoveCommand(
                    canonId = state.canonId,
                    sourceKey = state.sourceNotificationKey.orEmpty(),
                    reason = "listener_reconcile",
                    removedAt = removedAt,
                ),
            )
        }
        missingPeerMirrorStates(peerMirrorStates, liveMirrorIdentities).forEach { state ->
            yield(
                RemoveCommand(
                    canonId = state.canonId,
                    sourceKey = state.sourceNotificationKey.orEmpty(),
                    reason = "listener_reconcile",
                    removedAt = removedAt,
                ),
            )
        }
    }
}
