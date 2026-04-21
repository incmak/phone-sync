package co.twinotify.core.listener

sealed class FilterResult {
    object Suppress : FilterResult()
    object NoEmit : FilterResult()
    data class Emit(val reason: String) : FilterResult()
}

object ReasonCodeFilter {
    /**
     * Spec §4.1 truth table applied to onNotificationRemoved.
     * @param isOwnPackage sbn.packageName == context.packageName (our mirror?)
     * @param canonInPending canon_id is in pendingPeerCancel (we just triggered this cancel in response to peer)
     * @param removalReason the Android reason code (1..14)
     */
    fun filter(isOwnPackage: Boolean, canonInPending: Boolean, removalReason: Int): FilterResult {
        // Self-mirror path
        if (isOwnPackage) {
            return if (canonInPending) FilterResult.Suppress
            else FilterResult.Emit("user_swipe") // user dismissed our mirror locally
        }
        // Real notification path
        if (canonInPending) return FilterResult.Suppress
        return when (removalReason) {
            1 -> FilterResult.Emit("user_click")            // REASON_CLICK
            2, 3 -> FilterResult.Emit("user_swipe")         // REASON_CANCEL, REASON_CANCEL_ALL
            8, 9 -> FilterResult.Emit("app_cancel")         // REASON_APP_CANCEL, REASON_APP_CANCEL_ALL
            else -> FilterResult.NoEmit                     // 4, 6, 10, 12, 13, 14 + unknown
        }
    }
}
