package expo.modules.phonesynccore

import android.os.Handler
import android.os.Looper
import expo.modules.kotlin.Promise
import expo.modules.kotlin.modules.Module
import expo.modules.kotlin.modules.ModuleDefinition
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class PhoneSyncCoreModule : Module() {
    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    override fun definition() = ModuleDefinition {
        Name("PhoneSyncCore")

        AsyncFunction("ping") { relayUrl: String, promise: Promise ->
            val settled = AtomicBoolean(false)
            val handler = Handler(Looper.getMainLooper())
            var timeoutRunnable: Runnable? = null

            fun resolve(value: String) {
                if (settled.compareAndSet(false, true)) {
                    timeoutRunnable?.let { handler.removeCallbacks(it) }
                    promise.resolve(value)
                }
            }
            fun reject(code: String, msg: String, t: Throwable?) {
                if (settled.compareAndSet(false, true)) {
                    timeoutRunnable?.let { handler.removeCallbacks(it) }
                    promise.reject(code, msg, t)
                }
            }

            val request = Request.Builder().url(relayUrl).build()
            val msgId = UUID.randomUUID().toString()
            // TODO(phase-2): replace "mobile" with the paired device_id from persistent storage.
            val envelope = """{"v":1,"type":"ping","msg_id":"$msgId","origin_device":"mobile","ts":${System.currentTimeMillis()}}"""

            val ws = client.newWebSocket(request, object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    webSocket.send(envelope)
                }
                override fun onMessage(webSocket: WebSocket, text: String) {
                    resolve(text)
                    webSocket.close(1000, "done")
                }
                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    reject("PING_FAILED", t.message ?: "unknown", t)
                }
                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    reject("PING_CLOSED", "closed before message (code=$code reason=$reason)", null)
                }
            })

            timeoutRunnable = Runnable {
                if (!settled.get()) {
                    ws.cancel()
                    reject("PING_TIMEOUT", "no response within 10s", null)
                }
            }
            handler.postDelayed(timeoutRunnable!!, 10_000)
        }
    }
}
