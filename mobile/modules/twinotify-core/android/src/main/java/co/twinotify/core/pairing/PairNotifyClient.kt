package co.twinotify.core.pairing

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import com.goterl.lazysodium.LazySodiumAndroid
import com.goterl.lazysodium.SodiumAndroid
import com.goterl.lazysodium.interfaces.Sign
import org.json.JSONObject
import java.util.Base64

object PairNotifyClient {
    private val sodium = LazySodiumAndroid(SodiumAndroid()).sodium
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
        debug: Boolean = false,
    ): String {
        require(role == "A" || role == "B") { "role must be A or B, got $role" }
        val wsUrl = PairingRelayEndpoint.notify(relayBaseUrl, pairToken, role, debug = debug)
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

    /**
     * Authenticated variant required by the relay. The signature covers the
     * exact domain-separated proof expected by /pair/notify; no bearer token
     * or unsigned fallback is accepted on this path.
     */
    suspend fun awaitAuthenticatedFrame(
        relayBaseUrl: String,
        pairToken: String,
        role: String,
        expectedType: String,
        deviceId: String,
        signSecretKey: ByteArray,
        timeoutMs: Long = 5 * 60 * 1000L,
        debug: Boolean = false,
    ): String {
        val headers = authenticatedHeaders(pairToken, role, deviceId, signSecretKey)
        return awaitFrameInternal(relayBaseUrl, pairToken, role, expectedType, timeoutMs, headers, debug)
    }

    /** Backward-compat shim: waits for pair.sig on role=B and returns the decoded sig. */
    suspend fun awaitSig(
        relayBaseUrl: String,
        pairToken: String,
        timeoutMs: Long = 5 * 60 * 1000L,
        debug: Boolean = false,
    ): ByteArray {
        val frame = awaitFrame(relayBaseUrl, pairToken, "B", "pair.sig", timeoutMs, debug)
        val sigB64 = JSONObject(frame).getString("confirmation_sig")
        return Base64.getDecoder().decode(sigB64)
    }

    private suspend fun awaitFrameInternal(
        relayBaseUrl: String,
        pairToken: String,
        role: String,
        expectedType: String,
        timeoutMs: Long,
        headers: Map<String, String> = emptyMap(),
        debug: Boolean = false,
    ): String {
        require(role == "A" || role == "B") { "role must be A or B, got $role" }
        val wsUrl = PairingRelayEndpoint.notify(relayBaseUrl, pairToken, role, debug = debug)
        val requestBuilder = Request.Builder().url(wsUrl)
        headers.forEach { (name, value) -> requestBuilder.header(name, value) }
        val deferred = CompletableDeferred<String>()
        val ws = client.newWebSocket(requestBuilder.build(), object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                try {
                    val o = JSONObject(text)
                    if (o.optString("type") == expectedType && o.optString("pair_token") == pairToken) {
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
                if (!deferred.isCompleted) deferred.completeExceptionally(RuntimeException("pair-notify closed: $reason"))
            }
        })
        return try {
            withTimeout(timeoutMs) { deferred.await() }
        } catch (e: TimeoutCancellationException) {
            ws.cancel()
            throw RuntimeException("pair_notify($expectedType) timeout after ${timeoutMs}ms", e)
        }
    }

    internal fun authenticatedHeaders(
        pairToken: String,
        role: String,
        deviceId: String,
        signSecretKey: ByteArray,
    ): Map<String, String> {
        require(role == "A" || role == "B") { "role must be A or B, got $role" }
        require(deviceId.isNotBlank()) { "device ID is required" }
        require(signSecretKey.size == Sign.SECRETKEYBYTES) { "invalid Ed25519 secret key" }
        val message = "twinotify-pair-notify-v1\n$pairToken\n$role\n$deviceId".toByteArray()
        val signature = ByteArray(Sign.BYTES)
        check(sodium.crypto_sign_detached(signature, null, message, message.size.toLong(), signSecretKey) == 0) {
            "unable to sign pair notify proof"
        }
        return mapOf(
            "X-Twinotify-Device-ID" to deviceId,
            "X-Twinotify-Pair-Signature" to Base64.getEncoder().encodeToString(signature),
        )
    }
}
