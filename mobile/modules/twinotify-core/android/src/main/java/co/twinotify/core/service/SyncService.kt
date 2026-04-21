package co.twinotify.core.service

import android.app.Notification
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import co.twinotify.core.auth.JwtMinter
import co.twinotify.core.crypto.CryptoStore
import co.twinotify.core.listener.TwinotifyNotificationListener
import co.twinotify.core.storage.DeviceIdentity
import co.twinotify.core.storage.NotificationDb
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.TimeUnit

class SyncService : Service() {

    companion object {
        const val FGS_ID = 9_001
        const val EXTRA_RELAY_URL = "relay_url"
        const val ACTION_START = "co.twinotify.service.START"
        const val ACTION_STOP  = "co.twinotify.service.STOP"
        private const val KEEPALIVE_S = 30L
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var wsJob: Job? = null
    @Volatile private var currentWs: WebSocket? = null
    private val flushMutex = Mutex()
    private lateinit var queue: OutboundQueue
    private lateinit var dispatcher: InboundDispatcher
    private lateinit var sink: QueuingOutboundSink

    private val client = OkHttpClient.Builder()
        .pingInterval(KEEPALIVE_S, TimeUnit.SECONDS)
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    override fun onCreate() {
        super.onCreate()
        NotifChannelSetup.ensureChannels(this)
        val db = NotificationDb.get(this)
        queue = OutboundQueue(db.outboundEventDao())
        dispatcher = InboundDispatcher(this)
        sink = QueuingOutboundSink(
            ctx = applicationContext,
            queue = queue,
            onEnqueued = { scope.launch { flushIfConnected() } },
        )
        // Wire the listener to our real sink (it defaults to LoggingOutboundSink).
        TwinotifyNotificationListener.installSink(sink)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
            else -> {
                startForegroundCompat()
                val url = intent?.getStringExtra(EXTRA_RELAY_URL) ?: return START_NOT_STICKY
                startWebSocketLoop(url)
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        SyncServiceStatus.setState(SyncState.DISCONNECTED)
        currentWs?.close(1000, "service stopping")
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startForegroundCompat() {
        val notif: Notification = NotificationCompat.Builder(this, NotifChannelSetup.CHANNEL_FGS)
            .setContentTitle("Twinotify active")
            .setContentText("Connected — mirrors notifications to your paired phone.")
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(FGS_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_REMOTE_MESSAGING)
        } else {
            startForeground(FGS_ID, notif)
        }
    }

    private fun startWebSocketLoop(relayUrl: String) {
        if (wsJob?.isActive == true) return
        wsJob = scope.launch {
            var backoff = 1_000L
            while (isActive) {
                SyncServiceStatus.setState(SyncState.CONNECTING)
                val connected = try { connect(relayUrl) } catch (e: Throwable) { false }
                if (!isActive) break
                SyncServiceStatus.setState(SyncState.OFFLINE_QUEUED)
                SyncServiceStatus.setQueuedCount(queue.count())
                delay(backoff)
                backoff = (backoff * 2).coerceAtMost(60_000L)
            }
        }
    }

    /**
     * Opens a WebSocket, drains the queue, listens for inbound messages.
     * Returns true on clean close (code 1000), false on failure (triggers reconnect).
     */
    private suspend fun connect(relayUrl: String): Boolean {
        val deviceId = DeviceIdentity.getOrCreate(applicationContext)
        val (_, sign) = CryptoStore.loadOrGenerate(applicationContext)
        val jwt = JwtMinter.mint(deviceId, sign.secretKey)
        val req = Request.Builder()
            .url(relayUrl)
            .header("Authorization", "Bearer $jwt")
            .build()

        val done = kotlinx.coroutines.CompletableDeferred<Boolean>()
        val ws = client.newWebSocket(req, object : WebSocketListener() {
            override fun onOpen(ws: WebSocket, response: Response) {
                currentWs = ws
                SyncServiceStatus.setState(SyncState.CONNECTED)
                scope.launch { flushQueue(ws) }
            }
            override fun onMessage(ws: WebSocket, text: String) {
                scope.launch { dispatcher.dispatch(text) }
            }
            override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
                currentWs = null
                done.complete(false)
            }
            override fun onClosed(ws: WebSocket, code: Int, reason: String) {
                currentWs = null
                done.complete(code == 1000)
            }
        })
        currentWs = ws
        return done.await()
    }

    private suspend fun flushIfConnected() {
        val ws = currentWs ?: return
        flushQueue(ws)
    }

    private suspend fun flushQueue(ws: WebSocket) = flushMutex.withLock {
        val originDevice = DeviceIdentity.getOrCreate(applicationContext)
        while (true) {
            val batch = queue.drain()
            if (batch.isEmpty()) break
            var anySent = false
            for (ev in batch) {
                val env = EncryptedEnvelope(
                    msgId = ev.msgId,
                    originDevice = originDevice,
                    ts = ev.createdTs,
                    nonceB64 = ev.nonceB64,
                    ciphertextB64 = ev.ciphertextB64,
                )
                if (ws.send(env.toJson())) {
                    queue.ack(ev.id)
                    anySent = true
                } else {
                    break  // ws buffer full or closing; reconnect loop will retry
                }
            }
            if (!anySent) break
        }
        SyncServiceStatus.setQueuedCount(queue.count())
    }
}
