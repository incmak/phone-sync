package co.twinotify.core.call

import android.app.Notification
import android.content.Context
import co.twinotify.core.service.NotifChannelSetup
import co.twinotify.core.storage.CanonicalNotificationState
import java.security.MessageDigest
import org.json.JSONObject

data class CallNotificationContent(val title: String, val text: String)

enum class CallNotificationMode(val capabilityCode: String) {
    GENERIC_CATEGORY_CALL("call_style_deferred_no_controls"),
}

/** Builds generic, action-free call notifications from the privacy-bounded payload. */
object CallStateMaterializer {
    val mode: CallNotificationMode = CallNotificationMode.GENERIC_CATEGORY_CALL
    fun isCall(canonId: String): Boolean = canonId.startsWith("call:")

    fun stableTag(canonId: String): String = "call-" + sha256(canonId).take(24)

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
        return Notification.Builder(context, NotifChannelSetup.CHANNEL_MIRRORS)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(content.title)
            .setContentText(content.text)
            .setCategory(Notification.CATEGORY_CALL)
            .setOngoing(true)
            .build()
    }

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }
}
