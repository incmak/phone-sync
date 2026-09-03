package co.twinotify.core.call

import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.UUID

data class CallControlInvokeIdentity(
    val mirrorTag: String,
    val mirrorId: Int,
    val controlId: String,
    val kind: CallControlKind,
)

object MirrorCallControlIntent {
    fun dataUri(mirrorTag: String, mirrorId: Int, controlId: String, kind: CallControlKind): String {
        require(mirrorTag.isNotEmpty())
        require(mirrorId > 0)
        require(UUID.fromString(controlId).toString() == controlId)
        return "twinotify://call-control/${encodeSegment(mirrorTag)}/$mirrorId/$controlId/${kind.wire}"
    }

    fun parse(raw: String?): CallControlInvokeIdentity? {
        val uri = runCatching { URI(raw ?: return null) }.getOrNull() ?: return null
        if (
            uri.scheme != "twinotify" || uri.host != "call-control" || uri.rawUserInfo != null ||
            uri.port != -1 || uri.query != null || uri.fragment != null
        ) return null
        val path = uri.rawPath.orEmpty()
        if (!path.matches(Regex("^/[^/]+/[^/]+/[^/]+/[^/]+$"))) return null
        val segments = path.removePrefix("/").split('/')
        if (segments.size != 4) return null
        val tag = runCatching { URLDecoder.decode(segments[0], StandardCharsets.UTF_8) }.getOrNull()
            ?.takeIf(String::isNotEmpty) ?: return null
        val id = segments[1].toIntOrNull()?.takeIf { it > 0 } ?: return null
        val controlId = segments[2]
        if (runCatching { UUID.fromString(controlId).toString() }.getOrNull() != controlId) return null
        val kind = runCatching { CallControlKind.fromWire(segments[3]) }.getOrNull() ?: return null
        return CallControlInvokeIdentity(tag, id, controlId, kind)
    }

    private fun encodeSegment(value: String): String =
        URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20")
}
