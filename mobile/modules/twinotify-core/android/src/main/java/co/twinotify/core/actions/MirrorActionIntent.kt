package co.twinotify.core.actions

import android.app.PendingIntent
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.UUID

data class ActionInvokeIdentity(
    val mirrorTag: String,
    val mirrorId: Int,
    val actionId: String,
)

object MirrorActionIntent {
    const val REMOTE_INPUT_KEY = "twinotify_reply"

    fun dataUri(mirrorTag: String, mirrorId: Int, actionId: String): String {
        require(mirrorTag.isNotEmpty())
        require(mirrorId > 0)
        require(UUID.fromString(actionId).toString() == actionId)
        return "twinotify://invoke/${encodeSegment(mirrorTag)}/$mirrorId/$actionId"
    }

    fun parse(raw: String?): ActionInvokeIdentity? {
        val uri = runCatching { URI(raw ?: return null) }.getOrNull() ?: return null
        if (uri.scheme != "twinotify" || uri.host != "invoke" || uri.query != null || uri.fragment != null) return null
        val segments = uri.rawPath.orEmpty().split('/').filter(String::isNotEmpty)
        if (segments.size != 3) return null
        val tag = runCatching { URLDecoder.decode(segments[0], StandardCharsets.UTF_8) }.getOrNull()
            ?.takeIf(String::isNotEmpty) ?: return null
        val id = segments[1].toIntOrNull()?.takeIf { it > 0 } ?: return null
        val actionId = segments[2]
        if (runCatching { UUID.fromString(actionId).toString() }.getOrNull() != actionId) return null
        return ActionInvokeIdentity(tag, id, actionId)
    }

    fun pendingIntentFlags(reply: Boolean): Int = PendingIntent.FLAG_UPDATE_CURRENT or
        if (reply) PendingIntent.FLAG_MUTABLE else PendingIntent.FLAG_IMMUTABLE

    private fun encodeSegment(value: String): String =
        URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20")
}
