package co.twinotify.core.listener

import co.twinotify.core.storage.CanonicalNotificationState

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
}
