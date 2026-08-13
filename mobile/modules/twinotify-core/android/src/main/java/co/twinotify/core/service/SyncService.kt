package co.twinotify.core.service

import android.app.Notification
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.NotificationCompat
import co.twinotify.core.auth.JwtMinter
import co.twinotify.core.crypto.CryptoStore
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
        private const val RETRY_DELAY_MS = 5_000L
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var wsJob: Job? = null
    @Volatile private var currentWs: WebSocket? = null
    private val flushMutex = Mutex()
    private lateinit var reliableDao: co.twinotify.core.storage.ReliableDeliveryDao
    private lateinit var dispatcher: InboundDispatcher

    private val client = OkHttpClient.Builder()
        .pingInterval(KEEPALIVE_S, TimeUnit.SECONDS)
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    override fun onCreate() {
        super.onCreate()
        NotifChannelSetup.ensureChannels(this)
        val db = NotificationDb.get(this)
        reliableDao = db.reliableDeliveryDao()
        dispatcher = InboundDispatcher(this)
        // Resume any desired-state rows left between the Room commit and the Android API call.
        scope.launch {
            val localDevice = DeviceIdentity.getOrCreate(applicationContext)
            NotificationMaterializer(
                dao = reliableDao,
                port = DefaultAndroidNotificationPort(applicationContext, localDevice, reliableDao),
                receiptFactory = DurableReceiptFactory(applicationContext),
                localDeviceId = localDevice,
                retryScheduler = materializationStartupScheduler(applicationContext),
            ).materializePending()
        }
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
        startForeground(FGS_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_REMOTE_MESSAGING)
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
                SyncServiceStatus.setQueuedCount(reliableDao.sendable(Long.MAX_VALUE, 2_000).size)
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
        LegacyOutboxMigrator(reliableDao).migrate(deviceId)
        val (_, sign) = CryptoStore.loadOrGenerate(applicationContext)
        val jwt = JwtMinter.mint(deviceId, sign.secretKey)
        val req = Request.Builder()
            .url(relayUrl)
            .header("Authorization", "Bearer $jwt")
            .build()

        val done = kotlinx.coroutines.CompletableDeferred<Boolean>()
        client.newWebSocket(req, object : WebSocketListener() {
            override fun onOpen(ws: WebSocket, response: Response) {
                currentWs = ws
                SyncServiceStatus.setState(SyncState.CONNECTED)
                ws.send(ReliableRelayFrames.hello("0.8.0"))
                scope.launch { flushQueue(ws) }
                scope.launch {
                    val scheduler = ReliableFlushScheduler()
                    var lastWakeAt = System.currentTimeMillis()
                    while (isActive && currentWs === ws) {
                        delay(scheduler.delayUntil(lastWakeAt))
                        if (!isActive || currentWs !== ws) break
                        flushQueue(ws)
                        lastWakeAt = System.currentTimeMillis()
                    }
                }
            }
            override fun onMessage(ws: WebSocket, text: String) {
                scope.launch {
                    val content = handleReliableControl(text)
                    if (content != null) dispatcher.dispatch(content)
                }
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
        return done.await()
    }

    private suspend fun flushIfConnected() {
        val ws = currentWs ?: return
        flushQueue(ws)
    }

    private suspend fun flushQueue(ws: WebSocket) = flushMutex.withLock {
        while (true) {
            val batch = reliableDao.sendable(System.currentTimeMillis(), 32)
            if (batch.isEmpty()) break
            var anySent = false
            for (row in batch) {
                val frame = ReliableRelayFrames.put(row.envelopeJson)
                if (ws.send(frame)) {
                    anySent = true
                    reliableDao.markRelaySent(row.msgId, System.currentTimeMillis() + RETRY_DELAY_MS)
                } else break
            }
            if (!anySent) break
        }
        SyncServiceStatus.setQueuedCount(reliableDao.sendable(Long.MAX_VALUE, 2_000).size)
    }

    private suspend fun handleReliableControl(text: String): String? {
        val frame = runCatching { org.json.JSONObject(text) }.getOrNull() ?: return null
        when (frame.optString("type")) {
            "relay.deliver" -> return frame.optJSONObject("envelope")?.toString()
            "relay.accepted" -> Unit
            else -> return null
        }
        val msgId = frame.optString("msg_id").takeIf { it.isNotEmpty() } ?: return null
        val acceptedAt = frame.optLong("accepted_at", -1L)
        if (acceptedAt >= 0) reliableDao.markRelayAccepted(msgId, acceptedAt, acceptedAt + RETRY_DELAY_MS)
        return null
    }

}
