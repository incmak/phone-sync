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
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Lifecycle shell around the lifecycle-independent durable relay transport. */
class SyncService : Service() {
    companion object {
        const val FGS_ID = 9_001
        const val EXTRA_RELAY_URL = "relay_url"
        const val ACTION_START = "co.twinotify.service.START"
        const val ACTION_STOP = "co.twinotify.service.STOP"
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var relayJob: Job? = null
    private lateinit var legacyMigration: Deferred<LegacyMigrationSummary>
    private lateinit var legacyStore: co.twinotify.core.storage.LegacyOutboxStore
    private lateinit var outbox: OutboxRepository
    private lateinit var dispatcher: InboundDispatcher
    private lateinit var snapshotCoordinator: SnapshotCoordinator

    override fun onCreate() {
        super.onCreate()
        NotifChannelSetup.ensureChannels(this)
        val dao = NotificationDb.get(this).reliableDeliveryDao()
        legacyStore = dao
        outbox = OutboxRepository(DaoOutboxStore(dao))
        val localDevice = kotlinx.coroutines.runBlocking { DeviceIdentity.getOrCreate(applicationContext) }
        val capturePersister = co.twinotify.core.listener.DurableCapturePersister(applicationContext)
        snapshotCoordinator = SnapshotCoordinator(
            dao = dao,
            emitter = SnapshotEmitter { event -> capturePersister.persistSnapshotEvent(event) },
            source = ListenerSnapshotSource(
                applicationContext,
                co.twinotify.core.filter.DenylistLoader.load(applicationContext),
            ),
            localOriginDevice = localDevice,
        )
        dispatcher = InboundDispatcher(this, snapshotCoordinator)
        // Legacy outbound_queue rows must enter the durable outbox before any
        // negotiated floor-1/floor-2 transport flush. The transaction is
        // idempotent, so a crash or service restart cannot duplicate events.
        legacyMigration = scope.async {
            migrateLegacyOutboxBeforeRelay(legacyStore, DeviceIdentity.getOrCreate(applicationContext))
        }
        // Resume Room commits that were left before an Android platform call completed.
        scope.launch {
            val localDevice = DeviceIdentity.getOrCreate(applicationContext)
            NotificationMaterializer(
                dao = dao,
                port = DefaultAndroidNotificationPort(applicationContext, localDevice, dao),
                receiptFactory = DurableReceiptFactory(applicationContext),
                localDeviceId = localDevice,
                retryScheduler = materializationStartupScheduler(applicationContext),
            ).materializePending()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        startForegroundCompat()
        val relayUrl = intent?.getStringExtra(EXTRA_RELAY_URL)
        if (!relayUrl.isNullOrBlank()) startRelay(relayUrl)
        return START_STICKY
    }

    override fun onDestroy() {
        SyncServiceStatus.setState(SyncState.DISCONNECTED)
        relayJob?.cancel()
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startForegroundCompat() {
        val notif: Notification = NotificationCompat.Builder(this, NotifChannelSetup.CHANNEL_FGS)
            .setContentTitle("Twinotify active")
            .setContentText("Connected - mirrors notifications to your paired phone.")
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setOngoing(true)
            .build()
        startForeground(FGS_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_REMOTE_MESSAGING)
    }

    private fun startRelay(input: String) {
        if (relayJob?.isActive == true) return
        relayJob = scope.launch {
            while (isActive) {
                try {
                    legacyMigration.await()
                    break
                } catch (error: Throwable) {
                    SyncServiceStatus.setState(SyncState.OFFLINE_QUEUED)
                    delay(5_000L)
                    legacyMigration = scope.async {
                        migrateLegacyOutboxBeforeRelay(legacyStore, DeviceIdentity.getOrCreate(applicationContext))
                    }
                }
            }
            if (!isActive) return@launch
            val endpoints = try {
                RelayUrlPolicy.parse(
                    input,
                    debug = (applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0,
                )
            } catch (error: Throwable) {
                SyncServiceStatus.setState(SyncState.OFFLINE_QUEUED)
                return@launch
            }
            val deviceId = DeviceIdentity.getOrCreate(applicationContext)
            val (_, signingKeys) = CryptoStore.loadOrGenerate(applicationContext)
            val jwt = JwtMinter.mint(deviceId, signingKeys.secretKey)
            val transport = RelayTransport(
                outbox = outbox,
                authHeadersProvider = { mapOf("Authorization" to "Bearer $jwt") },
            )
            transport.run(endpoints.webSocket).collect { event ->
                when (event) {
                    TransportEvent.Connected -> SyncServiceStatus.setState(SyncState.CONNECTING)
                    is TransportEvent.Authenticated -> {
                        SyncServiceStatus.setState(SyncState.CONNECTED)
                        scope.launch {
                            runCatching { snapshotCoordinator.emitLocalDigest(deviceId) }
                        }
                    }
                    TransportEvent.LegacyOnlineOnly -> SyncServiceStatus.setState(SyncState.LEGACY_ONLINE_ONLY)
                    is TransportEvent.LegacyForwarded -> SyncServiceStatus.setQueuedCount(outbox.sendable(limit = 2_000).size)
                    is TransportEvent.Delivery -> dispatcher.dispatch(event.envelope)
                    is TransportEvent.RelayAccepted,
                    is TransportEvent.RelayRejected,
                    -> SyncServiceStatus.setQueuedCount(outbox.sendable(limit = 2_000).size)
                    is TransportEvent.RelayExpired -> {
                        SyncServiceStatus.setQueuedCount(outbox.sendable(limit = 2_000).size)
                        scope.launch { runCatching { snapshotCoordinator.emitLocalDigest(deviceId) } }
                    }
                    is TransportEvent.Failed,
                    is TransportEvent.Closed,
                    -> if (isActive) SyncServiceStatus.setState(SyncState.OFFLINE_QUEUED)
                }
            }
        }
    }
}
