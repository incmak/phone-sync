package co.twinotify.core.service

/**
 * Android only admits a `Notification.CallStyle` posted by a foreground service (or one
 * carrying a full-screen intent, which Twinotify never requests). The sync service owns a
 * single foreground-notification slot, so every call mirror is rendered through that slot
 * and the delivery-status notification is deferred until the call session ends.
 *
 * Pure bookkeeping: `render` is the only Android effect and is injected.
 */
internal class CallMirrorForegroundSlot<N : Any>(
    private val statusId: Int,
    private val render: (id: Int, notification: N) -> Unit,
) {
    private var heldBy: Int? = null
    private var deferredStatus: N? = null

    /** Renders the status notification now, or remembers it while a call holds the slot. */
    @Synchronized
    fun renderStatus(notification: N) {
        if (heldBy != null) {
            deferredStatus = notification
            return
        }
        render(statusId, notification)
    }

    /** Posts or updates a call mirror as the service's foreground notification. */
    @Synchronized
    fun hold(id: Int, notification: N): Boolean {
        if (id == statusId) return false
        render(id, notification)
        heldBy = id
        return true
    }

    /**
     * Ends a call mirror by giving the slot back to the status notification, which makes the
     * platform drop the call notification. A mirror that never held the slot is already gone.
     */
    @Synchronized
    fun release(id: Int, currentStatus: () -> N?): Boolean {
        if (heldBy != id) return true
        val status = deferredStatus ?: currentStatus() ?: return false
        render(statusId, status)
        heldBy = null
        deferredStatus = null
        return true
    }

    @Synchronized
    fun heldBy(): Int? = heldBy

    @Synchronized
    fun clear() {
        heldBy = null
        deferredStatus = null
    }
}

/** The running sync service, as seen by the call-mirror notification port. */
internal interface CallMirrorForegroundHost {
    fun postCallMirror(id: Int, notification: android.app.Notification): Boolean
    fun cancelCallMirror(id: Int): Boolean
}
