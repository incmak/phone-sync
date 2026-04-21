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
     * Subscribes to /pair/notify, waits for the pair.sig frame, returns the base64-decoded sig.
     * Times out after [timeoutMs] (default 5 min).
     */
    suspend fun awaitSig(
        relayBaseUrl: String,
        pairToken: String,
        timeoutMs: Long = 5 * 60 * 1000L,
    ): ByteArray {
        val wsUrl = relayBaseUrl.replaceFirst("^http".toRegex(), "ws") + "/pair/notify?token=$pairToken"
        val req = Request.Builder().url(wsUrl).build()
        val deferred = CompletableDeferred<ByteArray>()

        val ws = client.newWebSocket(req, object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                try {
                    val o = JSONObject(text)
                    if (o.getString("type") == "pair.sig" && o.getString("pair_token") == pairToken) {
                        val sigB64 = o.getString("confirmation_sig")
                        deferred.complete(Base64.getDecoder().decode(sigB64))
                        webSocket.close(1000, "sig received")
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
            throw RuntimeException("pair_notify timeout after ${timeoutMs}ms", e)
        }
    }
}
