package co.twinotify.core.call

import android.app.Notification

class CallCapabilitySelector {
    fun <T> select(
        defaultDialerPackage: String?,
        state: CallFrameworkState,
        direction: CallDirection,
        candidates: List<CallCapabilityCandidate<T>>,
    ): CallCapabilitySelection<T> {
        val eligible = candidates.filter { candidate ->
            candidate.packageName == defaultDialerPackage &&
                candidate.category == Notification.CATEGORY_CALL
        }
        if (eligible.size != 1) {
            return CallCapabilitySelection.None("ambiguous_call_notification")
        }

        val candidate = eligible.single()
        val handles = when {
            state == CallFrameworkState.RINGING &&
                direction == CallDirection.INCOMING &&
                candidate.answer != null &&
                candidate.decline != null &&
                candidate.hangUp == null -> mapOf(
                    CallControlKind.ANSWER to candidate.answer,
                    CallControlKind.DECLINE to candidate.decline,
                )

            state == CallFrameworkState.OFFHOOK &&
                direction == CallDirection.INCOMING &&
                candidate.answer == null &&
                candidate.decline == null &&
                candidate.hangUp != null -> mapOf(
                    CallControlKind.HANG_UP to candidate.hangUp,
                )

            else -> return CallCapabilitySelection.None("call_controls_unavailable")
        }
        return CallCapabilitySelection.Ready(candidate.sourceKey, handles)
    }
}
