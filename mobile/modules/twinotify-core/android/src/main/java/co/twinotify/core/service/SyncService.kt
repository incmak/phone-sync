package co.twinotify.core.service

import android.app.Notification
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import co.twinotify.core.auth.JwtMinter
import co.twinotify.core.crypto.CryptoStore
import co.twinotify.core.storage.DeviceIdentity
import co.twinotify.core.storage.NotificationDb
import co.twinotify.core.storage.PeerStore
import co.twinotify.core.call.CallStateCoordinator
import co.twinotify.core.call.CallStateEvent
import co.twinotify.core.call.CallFrameworkState
import co.twinotify.core.call.ActiveCallTerminalizer
import co.twinotify.core.call.CallCaptureStartupGate
import co.twinotify.core.call.startNormalCallCapture
import co.twinotify.core.call.CallCaptureDecision
import co.twinotify.core.call.CallCapturePolicy
import co.twinotify.core.call.CallCaptureStatus
import co.twinotify.core.call.CallStatePersister
import co.twinotify.core.call.CallStateMaterializer
import co.twinotify.core.call.DaoActiveCallRecoveryStore
import co.twinotify.core.call.TelephonyCallStateSource
import co.twinotify.core.call.code
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.cancel
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.isActive
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.collectLatest

/** Avoids crash recovery while a live source can still be producing legitimate ACTIVE rows. */
internal fun startCallCaptureRecoveryForServiceStart(
    captureStatus: CallCaptureStatus?,
    startRecovery: () -> Job,
): Job? = if (captureStatus?.enabled == true) null else startRecovery()

/** Serializes coordinator registration and teardown across service lifecycle threads. */
internal class CallCaptureLifecycleFence {
    private val monitor = Any()
    private var coordinator: CallStateCoordinator? = null
    private var terminal = false

    fun current(): CallStateCoordinator? = synchronized(monitor) { coordinator }

    fun status(): CallCaptureStatus? = synchronized(monitor) { coordinator?.status }

    fun start(startup: (install: (CallStateCoordinator?) -> Unit) -> Unit): Boolean =
        synchronized(monitor) {
            if (terminal) return false
            if (coordinator != null) return true
            startup { coordinator = it }
            true
        }

    fun stop(terminal: Boolean) {
        synchronized(monitor) {
            if (terminal) this.terminal = true
            coordinator?.close()
            coordinator = null
        }
    }
}

/** Lifecycle shell around the lifecycle-independent durable relay transport. */
class SyncService : Service() {
    companion object {
        const val FGS_ID = 9_001
        const val EXTRA_RELAY_URL = "relay_url"
        const val ACTION_START = "co.twinotify.service.START"
        const val ACTION_STOP = "co.twinotify.service.STOP"

        @Volatile private var activeInstance: SyncService? = null

        /** Stop all service-owned jobs and await cancellation before key/database cleanup. */
        suspend fun shutdownActive(
            ctx: android.content.Context,
            fromRelayJob: Boolean = false,
        ) {
            val service = activeInstance
            if (service != null) {
                service.shutdownForUnpair(fromRelayJob)
                service.shutdownCompleted.await()
            }
            ctx.stopService(Intent(ctx, SyncService::class.java))
        }

        /** Stop the live call-state source immediately when the JS opt-in is disabled. */
        fun stopActiveCallCapture() {
            activeInstance?.stopCallCapture()
                ?: SyncServiceStatus.setCallCapture(false, "call_capture_disabled")
        }

        /** Debug-only synthetic source setup; no telephony data is read by this path. */
        fun startDebugCallCapture(): Boolean {
            val service = activeInstance ?: return false
            service.configureCallCapture(enabled = true, debugSynthetic = true)
            return service.callCaptureLifecycle.status()?.enabled == true
        }

        suspend fun injectDebugCallState(state: String): CallStateEvent? {
            val frameworkState = when (state) {
                "ringing" -> CallFrameworkState.RINGING
                "active" -> CallFrameworkState.OFFHOOK
                "idle" -> CallFrameworkState.IDLE
                else -> return null
            }
            return activeInstance?.callCaptureLifecycle?.current()?.injectDebugState(frameworkState)
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var relayJob: Job? = null
    private var retentionJob: Job? = null
    private var healthJob: Job? = null
    private var materializerJob: Job? = null
    private val callCaptureLifecycle = CallCaptureLifecycleFence()
    private val callCaptureStartupGate = CallCaptureStartupGate()
    private var foregroundStarted = false
    private var shuttingDown = false
    private val shutdownCompleted = CompletableDeferred<Unit>()
    private lateinit var legacyMigration: Deferred<LegacyMigrationSummary>
    private lateinit var legacyStore: co.twinotify.core.storage.LegacyOutboxStore
    private lateinit var reliableDao: co.twinotify.core.storage.ReliableDeliveryDao
    private lateinit var outbox: OutboxRepository
    private lateinit var dispatcher: InboundDispatcher
    private lateinit var snapshotCoordinator: SnapshotCoordinator

    override fun onCreate() {
        super.onCreate()
        activeInstance = this
        NotifChannelSetup.ensureChannels(this)
        reliableDao = NotificationDb.get(this).reliableDeliveryDao()
        val dao = reliableDao
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
        // Every health transition refreshes the foreground text from the same native snapshot.
        healthJob = scope.launch {
            SyncServiceStatus.health.collectLatest {
                if (foregroundStarted && !shuttingDown) updateForegroundCompat()
            }
        }
        retentionJob = scope.launch {
            while (isActive) {
                runRetentionSweep()
                delay(RetentionCoordinator.INTERVAL_MS)
            }
        }
        // Legacy outbound_queue rows must enter the durable outbox before any
        // negotiated floor-1/floor-2 transport flush. The transaction is
        // idempotent, so a crash or service restart cannot duplicate events.
        legacyMigration = scope.async {
            migrateLegacyOutboxBeforeRelay(legacyStore, DeviceIdentity.getOrCreate(applicationContext))
        }
        // Resume Room commits that were left before an Android platform call completed.
        materializerJob = scope.launch {
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
            // Persist the user's choice before any service cancellation can occur. A null sticky
            // restart must observe this value and remain stopped until the user starts again.
            runBlocking(Dispatchers.IO) {
                ServiceConfigStore.setEnabled(applicationContext, enabled = false)
            }
            stopCallCapture(terminal = true)
            SyncServiceStatus.setState(SyncState.DISCONNECTED)
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }

        // Explicit user starts carry the freshly entered URL. Persist both fields before the
        // socket is attempted so pairing remains complete even when the relay is unavailable.
        val requestedUrl = intent?.getStringExtra(EXTRA_RELAY_URL)
        if (intent?.action == ACTION_START && !requestedUrl.isNullOrBlank()) {
            runBlocking(Dispatchers.IO) {
                ServiceConfigStore.setRelayUrl(applicationContext, requestedUrl)
                ServiceConfigStore.setEnabled(applicationContext, enabled = true)
            }
        }
        val config = runBlocking(Dispatchers.IO) { ServiceConfigStore.read(applicationContext) }
        val paired = runBlocking(Dispatchers.IO) { PeerStore.load(applicationContext) != null }
        when (val decision = ServiceStartPolicy.decide(intent?.action, config, paired)) {
            is ServiceStartDecision.Stop -> {
                SyncServiceStatus.setLastError(decision.reason)
                SyncServiceStatus.setState(SyncState.DISCONNECTED)
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
            is ServiceStartDecision.Start -> {
                startForegroundCompat()
                foregroundStarted = true
                recoverCallsBeforeNormalCapture()
                scope.launch { runRetentionSweep() }
                startRelay(decision.relayUrl)
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        shuttingDown = true
        foregroundStarted = false
        SyncServiceStatus.setState(SyncState.DISCONNECTED)
        relayJob?.cancel()
        retentionJob?.cancel()
        healthJob?.cancel()
        stopCallCapture(terminal = true)
        scope.cancel()
        activeInstance = null
        shutdownCompleted.complete(Unit)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startForegroundCompat() {
        SyncServiceStatus.setPostPermission(
            ContextCompat.checkSelfPermission(
                this,
                android.Manifest.permission.POST_NOTIFICATIONS,
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED,
        )
        val health = SyncServiceStatus.health.value
        val notif: Notification = NotificationCompat.Builder(this, NotifChannelSetup.CHANNEL_FGS)
            .setContentTitle("Twinotify active")
            .setContentText(foregroundText(health))
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setOngoing(true)
            .build()
        startForeground(FGS_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_REMOTE_MESSAGING)
    }

    private fun updateForegroundCompat() {
        if (!foregroundStarted || shuttingDown) return
        startForegroundCompat()
    }

    private suspend fun shutdownForUnpair(fromRelayJob: Boolean) {
        if (shuttingDown) return
        shuttingDown = true
        foregroundStarted = false
        stopCallCapture(terminal = true)
        val activeRelay = relayJob
        activeRelay?.cancel()
        // Peer-initiated unpair runs inside the relay job itself. Joining that job from its own
        // coroutine would deadlock; cancellation is enough there, while local unpair awaits it.
        if (!fromRelayJob) activeRelay?.join()
        retentionJob?.cancelAndJoin()
        healthJob?.cancelAndJoin()
        materializerJob?.cancelAndJoin()
        val scopeJob = scope.coroutineContext[Job]
        scopeJob?.cancel()
        if (!fromRelayJob) scopeJob?.join()
        stopForeground(STOP_FOREGROUND_REMOVE)
        shutdownCompleted.complete(Unit)
    }

    private fun foregroundText(health: SyncHealth): String = when (health.service) {
        "connected" -> if (health.queuedCount == 0) "Connected - mirrors notifications to your paired phone."
        else "Connected - ${health.queuedCount} item(s) queued."
        "connecting" -> "Connecting to the paired phone…"
        "degraded" -> "Offline - ${health.queuedCount} item(s) queued for retry."
        else -> "Stopped - syncing is disabled."
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
                    SyncServiceStatus.setLastError("legacy_migration")
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
                SyncServiceStatus.setLastError("invalid_relay_url")
                return@launch
            }
            val deviceId = DeviceIdentity.getOrCreate(applicationContext)
            val (_, signingKeys) = CryptoStore.loadOrGenerate(applicationContext)
            val transport = RelayTransport(
                outbox = outbox,
                // RelayTransport reconnects within the same run. Mint per connector attempt so
                // a retry after the 60-second JWT lifetime is authenticated with a fresh token.
                authHeadersProvider = {
                    mapOf("Authorization" to "Bearer " + JwtMinter.mint(deviceId, signingKeys.secretKey))
                },
            )
            // Keep the downstream delivery budget small because each envelope may approach
            // the protocol's 1 MiB limit. RelayTransport suspends at its flow boundary until
            // this ordered worker accepts the next item; no Delivery is silently discarded.
            val deliveryQueue = Channel<String>(capacity = 4)
            val deliveryWorker = scope.launch {
                for (envelope in deliveryQueue) {
                    runCatching { dispatcher.dispatch(envelope) }
                        .onFailure { SyncServiceStatus.setLastError("inbound_dispatch") }
                }
            }
            try {
                transport.run(endpoints.webSocket).collect { event ->
                    when (event) {
                    TransportEvent.Connected -> {
                        SyncServiceStatus.setState(SyncState.CONNECTING)
                    }
                    is TransportEvent.Authenticated -> {
                        SyncServiceStatus.setProtocolFloor(event.floor)
                        SyncServiceStatus.setState(SyncState.CONNECTED)
                        updateQueueHealth(reliableDao)
                        scope.launch {
                            runCatching { snapshotCoordinator.emitLocalDigest(deviceId) }
                        }
                    }
                    TransportEvent.LegacyOnlineOnly -> {
                        SyncServiceStatus.setState(SyncState.LEGACY_ONLINE_ONLY)
                    }
                    is TransportEvent.LegacyForwarded -> scope.launch { updateQueueHealth(reliableDao) }
                    is TransportEvent.Delivery -> deliveryQueue.send(event.envelope)
                    is TransportEvent.RelayAccepted,
                    is TransportEvent.RelayRejected,
                    -> scope.launch { updateQueueHealth(reliableDao) }
                    is TransportEvent.RelayExpired -> {
                        scope.launch { updateQueueHealth(reliableDao) }
                        scope.launch { runCatching { snapshotCoordinator.emitLocalDigest(deviceId) } }
                    }
                    is TransportEvent.Failed,
                    is TransportEvent.Closed,
                    -> if (isActive) {
                        SyncServiceStatus.setLastError(
                            (event as? TransportEvent.Failed)?.error?.javaClass?.simpleName ?: "transport_closed",
                        )
                        SyncServiceStatus.setState(SyncState.OFFLINE_QUEUED)
                        updateQueueHealth(reliableDao)
                    }
                }
                }
            } finally {
                deliveryQueue.close()
                deliveryWorker.cancelAndJoin()
            }
        }
    }

    private fun updateQueueHealth(dao: co.twinotify.core.storage.ReliableDeliveryDao) {
        scope.launch {
            runCatching {
                SyncServiceStatus.setQueueStats(dao.activeOutboundCount(), dao.activeOutboundBytes())
                updateForegroundCompat()
            }
        }
    }

    private fun recoverCallsBeforeNormalCapture() {
        startCallCaptureRecoveryForServiceStart(callCaptureLifecycle.status()) {
            callCaptureStartupGate.start(
                scope = scope,
                recover = {
                    val localDevice = DeviceIdentity.getOrCreate(applicationContext)
                    ActiveCallTerminalizer(
                        store = DaoActiveCallRecoveryStore(reliableDao),
                        persister = CallStatePersister(applicationContext),
                    ).recover(localDevice)
                },
                startCapture = {
                    // Read only after recovery. If this read fails, the helper
                    // reports a bounded failure and retries the full recovery.
                    configureCallCapture(ServiceConfigStore.read(applicationContext).callCaptureEnabled)
                },
                reportFailure = { code ->
                    SyncServiceStatus.setCallCapture(false, code)
                },
            )
        }
    }

    private fun configureCallCapture(enabled: Boolean, debugSynthetic: Boolean = false) {
        if (!enabled) {
            stopCallCapture()
            SyncServiceStatus.setCallCapture(false, "call_capture_disabled")
            return
        }
        callCaptureLifecycle.start { install ->
            val source = TelephonyCallStateSource(applicationContext)
            when (val decision = CallCapturePolicy.decide(enabled, source.capabilities())) {
                is CallCaptureDecision.Disabled -> {
                    if (debugSynthetic) {
                        // The synthetic host scenario must not depend on a real cellular modem or
                        // READ_PHONE_STATE. It still uses the production persister and coordinator.
                    } else {
                        SyncServiceStatus.setCallCapture(false, decision.code)
                        return@start
                    }
                }
                CallCaptureDecision.Start -> Unit
            }
            val coordinator = CallStateCoordinator(
                source = source,
                emit = { event ->
                    try {
                        CallStatePersister(applicationContext).persist(event)
                        SyncServiceStatus.setLastCallEventAt(System.currentTimeMillis())
                        SyncServiceStatus.setCallCapture(true, CallStateMaterializer.mode.capabilityCode)
                    } catch (error: Throwable) {
                        // Expose only a bounded health code; the coordinator still serializes and
                        // suppresses callbacks while the service remains opted in.
                        SyncServiceStatus.setCallCapture(true, "call_event_persist_failed")
                        throw error
                    }
                },
                dispatcher = Dispatchers.IO,
            )
            val status = if (debugSynthetic) {
                install(coordinator)
                coordinator.startForDebug()
            } else {
                startNormalCallCapture(
                    coordinator = coordinator,
                    install = install,
                    reportRegistrationFailure = { code -> SyncServiceStatus.setCallCapture(false, code) },
                )
            }
            val capability = if (status.enabled) CallStateMaterializer.mode.capabilityCode else null
            SyncServiceStatus.setCallCapture(status.enabled, status.lastErrorCode ?: status.reason?.code ?: capability)
        }
    }

    private fun stopCallCapture(terminal: Boolean = false) {
        if (terminal) shuttingDown = true
        callCaptureLifecycle.stop(terminal)
        SyncServiceStatus.setCallCapture(false, "call_capture_disabled")
    }

    private suspend fun runRetentionSweep() {
        runCatching {
            RetentionCoordinator.sweep(applicationContext)
        }.onFailure { SyncServiceStatus.setLastError("retention_sweep") }
    }

}
