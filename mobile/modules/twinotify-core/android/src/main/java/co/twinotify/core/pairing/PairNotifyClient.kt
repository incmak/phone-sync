package co.twinotify.core.pairing

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.util.Base64

object PairNotifyClient {
    private val client = OkHttpClient.Builder()
        .readTimeout(0, java.util.concurrent.TimeUnit.MILLISECONDS)
        .pingInterval(30, java.util.concurrent.TimeUnit.SECONDS)
        .connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
        .build()

    /**
     * Subscribes to /pair/notify?token=X&role=R, waits for a frame where type == expectedType,
     * returns the raw JSON text. Times out after [timeoutMs] (default 5 min).
     */
    suspend fun awaitFrame(
        relayBaseUrl: String,
        pairToken: String,
        role: String,
        expectedType: String,
        timeoutMs: Long = 5 * 60 * 1000L,
    ): String {
        require(role == "A" || role == "B") { "role must be A or B, got $role" }
        // Normalize to ws(s)://host[:port] — strip a trailing /ws path from user-entered URL,
        // swap http(s)→ws(s), then append /pair/notify.
        var origin = relayBaseUrl.trim().trimEnd('/').removeSuffix("/ws")
        origin = origin.replaceFirst(Regex("^https://"), "wss://")
        origin = origin.replaceFirst(Regex("^http://"), "ws://")
        // If user entered ws:// or wss:// already, keep as-is.
        val wsUrl = "$origin/pair/notify?token=$pairToken&role=$role"
        val req = Request.Builder().url(wsUrl).build()
        val deferred = CompletableDeferred<String>()
        val ws = client.newWebSocket(req, object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                try {
                    val o = JSONObject(text)
                    if (o.optString("type") == expectedType &&
                        o.optString("pair_token") == pairToken) {
                        deferred.complete(text)
                        webSocket.close(1000, "$expectedType received")
                    }
                } catch (e: Throwable) {
                    deferred.completeExceptionally(e)
                }
            }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                deferred.completeExceptionally(t)
            }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                if (!deferred.isCompleted) {
                    deferred.completeExceptionally(RuntimeException("pair-notify closed: $reason"))
                }
            }
        })
        return try {
            withTimeout(timeoutMs) { deferred.await() }
        } catch (e: TimeoutCancellationException) {
            ws.cancel()
            throw RuntimeException("pair_notify($expectedType) timeout after ${timeoutMs}ms", e)
        }
    }

    /** Backward-compat shim: waits for pair.sig on role=B and returns the decoded sig. */
    suspend fun awaitSig(
        relayBaseUrl: String,
        pairToken: String,
        timeoutMs: Long = 5 * 60 * 1000L,
    ): ByteArray {
        val frame = awaitFrame(relayBaseUrl, pairToken, "B", "pair.sig", timeoutMs)
        val sigB64 = JSONObject(frame).getString("confirmation_sig")
        return Base64.getDecoder().decode(sigB64)
    }
}
