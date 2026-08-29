package co.twinotify.core.call

import android.app.Notification
import android.content.Context
import co.twinotify.core.R
import co.twinotify.core.service.NotifChannelSetup
import co.twinotify.core.storage.CanonicalNotificationState
import org.json.JSONObject

data class CallNotificationContent(val title: String, val text: String)

enum class CallNotificationMode(val capabilityCode: String) {
    GENERIC_CATEGORY_CALL("call_style_deferred_no_controls"),
}

/** Builds generic, action-free call notifications from the privacy-bounded payload. */
object CallStateMaterializer {
    val mode: CallNotificationMode = CallNotificationMode.GENERIC_CATEGORY_CALL
    fun isCall(canonId: String): Boolean = canonId.startsWith("call:")

    fun stableTag(canonId: String): String = "call-" + callStateSha256(canonId).take(24)

    fun content(payloadJson: String): CallNotificationContent {
        val payload = JSONObject(payloadJson)
        return when (payload.getString("state")) {
            "ringing" -> CallNotificationContent("Incoming call", "Incoming call")
            "active" -> CallNotificationContent("Call in progress", "Call in progress")
            "idle" -> CallNotificationContent("Call ended", "Call ended")
            else -> error("unsupported call state")
        }
    }

    fun build(context: Context, state: CanonicalNotificationState, localId: Int): Notification {
        require(isCall(state.canonId)) { "not a call canonical ID" }
        require(localId > 0) { "call notification ID must be positive" }
        val payload = requireNotNull(state.desiredPayloadJson) { "call state payload required" }
        val content = content(payload)
        // Notification.CallStyle requires answer/reject/hang-up PendingIntents. Those are
        // explicitly deferred, so every supported API uses this action-free CATEGORY_CALL
        // fallback.
        return Notification.Builder(context, NotifChannelSetup.CHANNEL_CALLS)
            .setSmallIcon(R.drawable.ic_stat_twinotify)
            .setContentTitle(content.title)
            .setContentText(content.text)
            .setCategory(Notification.CATEGORY_CALL)
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .setOngoing(true)
            .build()
    }
}
