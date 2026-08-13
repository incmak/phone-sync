package co.twinotify.core.service

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

data class RelayEndpoints(
    val http: HttpUrl,
    val webSocket: RelayWebSocketUrl,
) {
    /** Explicit names used by pairing/revocation callers. */
    val pairing: HttpUrl get() = http
    val revocation: HttpUrl get() = http
}

data class RelayWebSocketUrl(
    val scheme: String,
    val host: String,
    val port: Int,
    val encodedPath: String,
    val encodedQuery: String?,
) {
    fun asHttpUrl(): HttpUrl {
        // OkHttp's WebSocket request builder accepts the HTTP(S) spelling, but the
        // caller retains this typed URL (including wss) so policy cannot silently
        // downgrade a secure endpoint.
        val httpScheme = if (scheme == "wss") "https" else "http"
        val builder = HttpUrl.Builder().scheme(httpScheme).host(host).port(port).encodedPath(encodedPath)
        encodedQuery?.let { builder.encodedQuery(it) }
        return builder.build()
    }
    override fun toString(): String = buildString {
        append(scheme).append("://").append(host)
        if (!((scheme == "ws" && port == 80) || (scheme == "wss" && port == 443))) append(":").append(port)
        append(encodedPath)
        encodedQuery?.let { append("?").append(it) }
    }
}

/**
 * One URL policy shared by pairing, unpair and the live transport.  Cleartext is only accepted
 * for loopback/debug development; release builds never silently downgrade TLS.
 */
object RelayUrlPolicy {
    fun parse(input: String, debug: Boolean): RelayEndpoints {
        val trimmed = input.trim()
        require(trimmed.isNotEmpty()) { "relay URL must not be empty" }
        val parseValue = when {
            trimmed.startsWith("ws://") -> "http://" + trimmed.removePrefix("ws://")
            trimmed.startsWith("wss://") -> "https://" + trimmed.removePrefix("wss://")
            else -> trimmed
        }
        val parsed = parseValue.toHttpUrlOrNull() ?: throw IllegalArgumentException("invalid relay URL")
        val requestedScheme = when {
            trimmed.startsWith("ws://") -> "ws"
            trimmed.startsWith("wss://") -> "wss"
            else -> parsed.scheme
        }
        val scheme = requestedScheme
        require(scheme == "http" || scheme == "https" || scheme == "ws" || scheme == "wss") {
            "relay URL must use http(s) or ws(s)"
        }
        if (scheme == "http" || scheme == "ws") {
            require(debug && isLoopback(parsed.host)) {
                "cleartext relay URLs are allowed only for loopback debug builds"
            }
        }

        val httpScheme = if (scheme == "ws" || scheme == "wss") {
            if (scheme == "ws") "http" else "https"
        } else scheme
        val wsScheme = when (scheme) {
            "http", "ws" -> "ws"
            "https", "wss" -> "wss"
            else -> error("unsupported relay scheme")
        }

        val path = parsed.encodedPath.trimEnd('/').let {
            if (it.isEmpty()) "" else if (it == "/ws") "" else it.removeSuffix("/ws")
        }
        val http = parsed.newBuilder().scheme(httpScheme).encodedPath(path.ifEmpty { "/" }).build()
        val ws = RelayWebSocketUrl(
            scheme = wsScheme,
            host = parsed.host,
            port = parsed.port,
            encodedPath = (path.ifEmpty { "" } + "/ws").replace("//", "/"),
            encodedQuery = parsed.encodedQuery,
        )
        return RelayEndpoints(http = http, webSocket = ws)
    }

    private fun isLoopback(host: String): Boolean = host == "localhost" || host == "127.0.0.1" || host == "[::1]" || host == "::1"
}
