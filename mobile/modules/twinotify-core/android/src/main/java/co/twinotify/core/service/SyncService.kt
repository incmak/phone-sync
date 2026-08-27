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
import co.twinotify.core.call.CallShutdownConfigIntent
import co.twinotify.core.call.GracefulCallShutdownGate
import co.twinotify.core.call.GracefulCallShutdownResult
import co.twinotify.core.call.DaoActiveCallRecoveryStore
import co.twinotify.core.call.TelephonyCallStateSource
import co.twinotify.core.call.gracefullyShutdownCallCapture
import co.twinotify.core.call.persistDisabledForCallShutdown
import co.twinotify.core.call.code
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.coroutineScope
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

internal suspend fun forceRepairSnapshotForE2e(
    localDigest: suspend () -> StateDigest,
    onDigest: suspend (StateDigest, Boolean) -> SnapshotConvergence,
): Boolean {
    val current = localDigest()
    require(current.digest.matches(Regex("[0-9a-f]{64}"))) { "invalid production digest" }
    val replacement = if (current.digest[0] == '0') '1' else '0'
    val mismatch = current.copy(digest = replacement + current.digest.substring(1))
    return onDigest(mismatch, true) is SnapshotConvergence.RepairStarted
}

internal data class ProductObservationSnapshot(
    val custodyCounts: Map<String, Map<String, Long>>,
    val peerReceiptCount: Long,
    val snapshotDigestCount: Long,
    val snapshotBeginCount: Long,
    val snapshotEndCount: Long,
    val snapshotCommitCount: Long,
    val userDismissCount: Long,
    val unpairInboundCount: Long,
    val peakQueueCount: Int,
    val peakQueueBytes: Long,
)

/** Process-local, content-free counters fed only by authenticated production transitions. */
internal object ProductObservationTracker {
    const val MAX_COUNTER = 1_000_000_000L
    val EVENT_KEYS = linkedSetOf(
        "notif_post", "notif_update", "notif_cancel", "call_state", "state_digest",
        "state_snapshot_begin", "state_snapshot_item", "state_snapshot_end", "unpair",
        "peer_receipt",
    )
    private val custody = linkedMapOf(
        "lan" to EVENT_KEYS.associateWith { AtomicLong() },
        "relay" to EVENT_KEYS.associateWith { AtomicLong() },
    )
    private val authenticatedInbound = EVENT_KEYS.associateWith { AtomicLong() }
    private val peakCount = AtomicLong()
    private val peakBytes = AtomicLong()
    private val userDismiss = AtomicLong()
    private val snapshotCommit = AtomicLong()

    fun recordCustody(route: String, eventType: String?) {
        val key = eventType?.replace('.', '_') ?: return
        custody[route]?.get(key)?.boundedIncrement()
    }

    fun recordAuthenticatedInbound(eventType: String) {
        authenticatedInbound[eventType.replace('.', '_')]?.boundedIncrement()
    }

    fun recordUserDismiss() {
        userDismiss.boundedIncrement()
    }

    fun recordSnapshotCommit() {
        snapshotCommit.boundedIncrement()
    }

    fun recordQueue(count: Int, bytes: Long) {
        if (count !in 0..2_000 || bytes !in 0..134_217_728L) return
        peakCount.accumulateAndGet(count.toLong(), ::maxOf)
        peakBytes.accumulateAndGet(bytes, ::maxOf)
    }

    fun snapshot(): ProductObservationSnapshot = ProductObservationSnapshot(
        custodyCounts = custody.mapValues { (_, counts) -> counts.mapValues { it.value.get() } },
        peerReceiptCount = authenticatedInbound.getValue("peer_receipt").get(),
        snapshotDigestCount = authenticatedInbound.getValue("state_digest").get(),
        snapshotBeginCount = authenticatedInbound.getValue("state_snapshot_begin").get(),
        snapshotEndCount = authenticatedInbound.getValue("state_snapshot_end").get(),
        snapshotCommitCount = snapshotCommit.get(),
        userDismissCount = userDismiss.get(),
        unpairInboundCount = authenticatedInbound.getValue("unpair").get(),
        peakQueueCount = peakCount.get().toInt(),
        peakQueueBytes = peakBytes.get(),
    )

    fun clear() {
        custody.values.flatMap { it.values }.forEach { it.set(0) }
        authenticatedInbound.values.forEach { it.set(0) }
        peakCount.set(0)
        peakBytes.set(0)
        userDismiss.set(0)
        snapshotCommit.set(0)
    }

    private fun AtomicLong.boundedIncrement() {
        updateAndGet { current -> if (current >= MAX_COUNTER) MAX_COUNTER else current + 1 }
    }
}

internal suspend fun runTransportSideEffect(
    block: suspend () -> Unit,
    onFailure: (Throwable) -> Unit,
) {
    try {
        block()
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (error: Throwable) {
        onFailure(error)
    }
}

internal suspend fun dispatchRelayDeliveryWithFinalization(
    dispatch: suspend () -> InboundDispatchResult?,
) {
    val result = dispatch()
    if (result is InboundDispatchResult.AcceptedAfterCustody) {
        // The relay has already durably accepted the sender's row before delivery to this client.
        result.finalizeAfterCustody()
    }
}

internal fun acceptRelayUnpairCustody(
    event: TransportEvent,
    tracker: UnpairCustodyTracker,
): Boolean = event is TransportEvent.RelayAccepted &&
    tracker.accept(event.msgId, CustodyRoute.RELAY)

internal fun acceptLanUnpairCustody(
    event: co.twinotify.core.lan.LanTransportEvent,
    tracker: UnpairCustodyTracker,
): Boolean = event is co.twinotify.core.lan.LanTransportEvent.PeerAccepted &&
    tracker.accept(event.msgId, CustodyRoute.LAN)

internal fun recordRelayCustodyObservation(event: TransportEvent.RelayAccepted) {
    ProductObservationTracker.recordCustody("relay", event.eventType)
}

internal fun recordLanCustodyObservation(
    event: co.twinotify.core.lan.LanTransportEvent.PeerAccepted,
) {
    ProductObservationTracker.recordCustody("lan", event.eventType)
}

/**
 * Lifecycle-independent service transport loop. One coordinator owns both route selection and
 * outbox draining; the Android Service owns only this loop's Job.
 */
internal class LiveServiceTransportLoop(
    private val outbox: OutboxRepository,
    private val loadRoutes: suspend () -> LiveTransportRoutes,
    private val queuedCount: suspend () -> Int,
    private val retryRequests: Flow<Unit> = emptyFlow(),
    private val onAuthenticatedRoute: suspend (RouteKind) -> Unit = {},
    private val publishHealth: suspend (RouteHealth) -> Unit,
) {
    private val healthState = MutableStateFlow(RouteHealth())
    val health: StateFlow<RouteHealth> = healthState.asStateFlow()

    suspend fun run(preferLan: Boolean) {
        val routes = loadRoutes()
        val coordinator = TransportCoordinator(
            outbox = outbox,
            lan = routes.lan,
            relay = routes.relay,
            preferLan = preferLan,
            queuedCount = queuedCount,
            retryRequests = retryRequests,
        )
        coroutineScope {
            var authenticatedRoute = RouteKind.NONE
            val healthPublisher = launch {
                coordinator.health.collect { health ->
                    if (health.phase == RoutePhase.AUTHENTICATED) {
                        if (health.active != authenticatedRoute) {
                            authenticatedRoute = health.active
                            onAuthenticatedRoute(health.active)
                        }
                    } else {
                        authenticatedRoute = RouteKind.NONE
                    }
                    healthState.value = health
                    publishHealth(health)
                }
            }
            try {
                coordinator.run()
            } finally {
                healthPublisher.cancelAndJoin()
            }
        }
    }
}

/** Conflates preference bursts and never starts a replacement before the old loop is joined. */
internal class SerializedTransportRestarter(
    private val isCurrentActive: () -> Boolean,
    private val stopCurrent: suspend () -> Unit,
    private val readPreference: suspend () -> Boolean,
    private val startCurrent: suspend (Boolean) -> Unit,
) {
    private val requests = Channel<Unit>(capacity = Channel.CONFLATED)
    private val forceRequested = AtomicBoolean(false)

    fun ensureStarted() {
        requests.trySend(Unit)
    }

    fun forceRestart() {
        forceRequested.set(true)
        requests.trySend(Unit)
    }

    suspend fun run() {
        for (ignored in requests) {
            val active = isCurrentActive()
            val force = forceRequested.getAndSet(false)
            if (active && !force) continue
            if (active) stopCurrent()
            startCurrent(readPreference())
        }
    }
}

internal inline fun admitTransportGeneration(
    currentActive: Boolean,
    beginGeneration: () -> Unit,
): Boolean {
    if (currentActive) return false
    beginGeneration()
    return true
}

internal suspend fun executeCallCaptureStopRequest(
    sharedShutdown: suspend () -> GracefulCallShutdownResult,
    finalizeStop: suspend () -> Unit,
) {
    when (val result = sharedShutdown()) {
        GracefulCallShutdownResult.Completed -> finalizeStop()
        is GracefulCallShutdownResult.Failed -> throw co.twinotify.core.call.ActiveCallRecoveryException(result.code)
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
internal suspend fun <T> awaitCallShutdownResult(
    shutdown: Deferred<T>,
): T = try {
    shutdown.await()
} catch (cancellation: CancellationException) {
    // Read the exception recorded by this Deferred instead of guessing from an arbitrary cause
    // chain. Deferred.await may wrap that exact object for stacktrace recovery.
    throw (shutdown.getCompletionExceptionOrNull() as? CancellationException ?: cancellation)
}

internal class CallShutdownPhaseState {
    private var terminalCustody = false

    @Synchronized
    fun hasTerminalCustody(): Boolean = terminalCustody

    @Synchronized
    fun markTerminalCustodyComplete() {
        terminalCustody = true
    }

    @Synchronized
    fun clearAfterConfigSuccess() {
        terminalCustody = false
    }
}

internal suspend fun executeCallShutdownPhases(
    gate: GracefulCallShutdownGate,
    phaseState: CallShutdownPhaseState = CallShutdownPhaseState(),
    terminalize: suspend () -> Unit,
    persistIntent: suspend (CallShutdownConfigIntent) -> Unit,
    reportFailure: (String) -> Unit,
): GracefulCallShutdownResult {
    if (!phaseState.hasTerminalCustody()) {
        val terminal = gracefullyShutdownCallCapture(
            quiesceAndTerminalize = terminalize,
            reportFailure = reportFailure,
        )
        if (terminal != GracefulCallShutdownResult.Completed) return terminal
        phaseState.markTerminalCustodyComplete()
    }
    val config = gate.persistMergedIntent { intent ->
        persistDisabledForCallShutdown(
            persistDisabled = { persistIntent(intent) },
            reportFailure = reportFailure,
        )
    }
    if (config == GracefulCallShutdownResult.Completed) {
        phaseState.clearAfterConfigSuccess()
    }
    return config
}

internal fun <T : Any> startCallShutdownBeforeActiveObservation(
    gate: GracefulCallShutdownGate,
    scope: CoroutineScope,
    intent: CallShutdownConfigIntent,
    activeInstance: () -> T?,
    shutdownActive: suspend (T) -> GracefulCallShutdownResult,
    shutdownWithoutActive: suspend () -> GracefulCallShutdownResult,
): Deferred<GracefulCallShutdownResult> = gate.start(scope, intent) {
    activeInstance()?.let { shutdownActive(it) } ?: shutdownWithoutActive()
}

internal class CallCaptureStopRequestGate {
    private var active: Job? = null

    @Synchronized
    fun start(scope: CoroutineScope, request: suspend () -> Unit): Job {
        active?.takeIf { it.isActive }?.let { return it }
        val next = scope.launch(start = CoroutineStart.LAZY) { request() }
        active = next
        next.invokeOnCompletion {
            synchronized(this) {
                if (active === next) active = null
            }
        }
        next.start()
        return next
    }
}

internal suspend fun resumeNormalCallCaptureAfterShutdown(
    awaitRelease: suspend () -> Unit,
    readConfig: suspend () -> ServiceConfig,
    configure: (Boolean) -> Unit,
) {
    awaitRelease()
    val config = readConfig()
    if (config.enabled) configure(config.callCaptureEnabled)
}

internal suspend fun quiesceServiceJobsAfterCallShutdown(
    fromRelayJob: Boolean,
    activeRelay: Job?,
    stopOtherChildren: suspend () -> Unit,
    cancelAndJoinServiceScope: suspend () -> Unit,
) {
    if (!fromRelayJob) activeRelay?.cancelAndJoin()
    stopOtherChildren()
    if (!fromRelayJob) cancelAndJoinServiceScope()
}

internal suspend fun stopRoutePreferenceOwner(
    fromTransportJob: Boolean,
    cancelOwner: () -> Unit,
    cancelAndJoinOwner: suspend () -> Unit,
) {
    if (fromTransportJob) cancelOwner() else cancelAndJoinOwner()
}

internal fun executeUnexpectedServiceDestroy(
    closeCallCapture: () -> Unit,
    cancelServiceJobs: () -> Unit,
) {
    closeCallCapture()
    cancelServiceJobs()
}

/** Avoids crash recovery while a live source can still be producing legitimate ACTIVE rows. */
internal fun startCallCaptureRecoveryForServiceStart(
    captureStatus: CallCaptureStatus?,
    startRecovery: () -> Job,
): Job? = if (captureStatus?.enabled == true) null else startRecovery()

/** Serializes coordinator registration and teardown across service lifecycle threads. */
internal data class CallCaptureQuiesceLease(
    val coordinator: CallStateCoordinator?,
    val terminal: Boolean,
)

internal class CallCaptureLifecycleFence {
    private val monitor = Any()
    private var coordinator: CallStateCoordinator? = null
    private var terminal = false
    private var quiescing = false
    private var activeLease: CallCaptureQuiesceLease? = null

    fun current(): CallStateCoordinator? = synchronized(monitor) { coordinator }

    fun status(): CallCaptureStatus? = synchronized(monitor) { coordinator?.status }

    fun start(
        admissionReserved: () -> Boolean = { false },
        startup: (install: (CallStateCoordinator?) -> Unit) -> Unit,
    ): Boolean =
        synchronized(monitor) {
            if (terminal || quiescing || admissionReserved()) return false
            if (coordinator != null) return true
            startup { coordinator = it }
            true
        }

    fun beginQuiesce(terminal: Boolean): CallCaptureQuiesceLease = synchronized(monitor) {
        check(activeLease == null) { "call capture quiesce is already active" }
        quiescing = true
        CallCaptureQuiesceLease(coordinator, terminal).also { activeLease = it }
    }

    fun finishQuiesce(
        lease: CallCaptureQuiesceLease,
        completed: Boolean,
        terminal: Boolean = lease.terminal,
    ) {
        val close = synchronized(monitor) {
            check(activeLease === lease) { "call capture quiesce lease is not active" }
            activeLease = null
            if (!completed) return
            if (terminal) this.terminal = true
            quiescing = false
            coordinator.also { coordinator = null }
        }
        close?.close()
    }

    fun stop(terminal: Boolean) {
        synchronized(monitor) {
            if (terminal) this.terminal = true
            activeLease = null
            quiescing = terminal
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
        private val callShutdownScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        private val callShutdownGate = GracefulCallShutdownGate()
        private val callShutdownPhaseState = CallShutdownPhaseState()

        private fun requestCallShutdown(
            context: android.content.Context,
            intent: CallShutdownConfigIntent,
        ): Deferred<GracefulCallShutdownResult> {
            val appContext = context.applicationContext
            return startCallShutdownBeforeActiveObservation(
                gate = callShutdownGate,
                scope = callShutdownScope,
                intent = intent,
                activeInstance = { activeInstance },
                shutdownActive = { it.runGracefulCallShutdown(intent.disableService) },
                shutdownWithoutActive = { runGracefulCallShutdownWithoutService(appContext) },
            )
        }

        suspend fun disableCallCaptureAndAwait(context: android.content.Context) {
            executeCallCaptureStopRequest(
                sharedShutdown = {
                    requestCallShutdown(
                        context,
                        CallShutdownConfigIntent(disableCallCapture = true, disableService = false),
                    ).let { awaitCallShutdownResult(it) }
                },
                finalizeStop = {
                    SyncServiceStatus.setCallCapture(false, "call_capture_disabled")
                },
            )
        }

        suspend fun awaitCallShutdownRelease() {
            callShutdownGate.awaitRelease()
        }

        /** Stop all service-owned jobs and await cancellation before key/database cleanup. */
        suspend fun shutdownActive(
            ctx: android.content.Context,
            fromRelayJob: Boolean = false,
        ) {
            executeCallCaptureStopRequest(
                sharedShutdown = {
                    requestCallShutdown(
                        ctx,
                        CallShutdownConfigIntent(disableCallCapture = false, disableService = true),
                    ).let { awaitCallShutdownResult(it) }
                },
                finalizeStop = {
                    val service = activeInstance
                    if (service != null) {
                        service.shutdownForUnpair(fromRelayJob)
                        service.shutdownCompleted.await()
                    }
                    if (!fromRelayJob) {
                        ctx.stopService(Intent(ctx, SyncService::class.java))
                    }
                },
            )
        }

        /**
         * Terminalize calls and durably disable the service while leaving the active route alive.
         * The returned handle owns the later, idempotent resource join after the unpair custody
         * attempt has completed.
         */
        internal suspend fun prepareLocalUnpair(
            ctx: android.content.Context,
        ): PreparedLocalUnpairService {
            executeCallCaptureStopRequest(
                sharedShutdown = {
                    requestCallShutdown(
                        ctx,
                        CallShutdownConfigIntent(disableCallCapture = false, disableService = true),
                    ).let { awaitCallShutdownResult(it) }
                },
                finalizeStop = {},
            )
            val service = activeInstance
            val tracker = service?.unpairCustodyTracker ?: UnpairCustodyTracker()
            return preparedLocalUnpairService(service?.transportJob, tracker) {
                if (service != null) {
                    service.shutdownForUnpair(fromRelayJob = false)
                    service.shutdownCompleted.await()
                }
                ctx.stopService(Intent(ctx, SyncService::class.java))
            }
        }

        private suspend fun runGracefulCallShutdownWithoutService(
            context: android.content.Context,
        ): GracefulCallShutdownResult = executeCallShutdownPhases(
            gate = callShutdownGate,
            phaseState = callShutdownPhaseState,
            terminalize = {
                val dao = NotificationDb.get(context).reliableDeliveryDao()
                val localDevice = DeviceIdentity.getOrCreate(context)
                ActiveCallTerminalizer(
                    store = DaoActiveCallRecoveryStore(dao),
                    persister = CallStatePersister(context),
                ).recover(localDevice)
            },
            persistIntent = { ServiceConfigStore.applyCallShutdownIntent(context, it) },
            reportFailure = { SyncServiceStatus.setCallCapture(false, it) },
        )

        /** Stop the live call-state source immediately when the JS opt-in is disabled. */
        fun stopActiveCallCapture() {
            activeInstance?.stopCallCapture()
                ?: SyncServiceStatus.setCallCapture(false, "call_capture_disabled")
        }

        /** Debug-only synthetic source setup; no telephony data is read by this path. */
        fun startDebugCallCapture(): Boolean {
            if (callShutdownGate.isReserved()) return false
            val service = activeInstance ?: return false
            return service.configureCallCapture(enabled = true, debugSynthetic = true)
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

        /** Called only after the new preference is durable. */
        fun notifyRoutePreferenceChanged() {
            activeInstance?.routePreferenceRestarter?.forceRestart()
        }

        /** Debug source calls this seam; the emitted digest is the normal production event. */
        internal suspend fun emitProductionSnapshotForE2e(): Boolean {
            val service = activeInstance ?: return false
            service.snapshotCoordinator.emitLocalDigest(DeviceIdentity.getOrCreate(service.applicationContext))
            return true
        }

        /** Debug bridge into the production mismatch-repair path; it authors no protocol rows. */
        internal suspend fun forceProductionRepairSnapshotForE2e(): Boolean {
            val service = activeInstance ?: return false
            val localDevice = DeviceIdentity.getOrCreate(service.applicationContext)
            return forceRepairSnapshotForE2e(
                localDigest = { service.snapshotCoordinator.localDigest(localDevice) },
                onDigest = { digest, force -> service.snapshotCoordinator.onDigest(digest, force = force) },
            )
        }

        internal fun clearProductObservationsForE2e() {
            ProductObservationTracker.clear()
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var transportJob: Job? = null
    private var routePreferenceJob: Job? = null
    private var retentionJob: Job? = null
    private var healthJob: Job? = null
    private var materializerJob: Job? = null
    private var callCaptureReservationWaiter: Job? = null
    private val actionStopGate = CallCaptureStopRequestGate()
    private val unpairCustodyTracker = UnpairCustodyTracker()
    private var retainedCallShutdownLease: CallCaptureQuiesceLease? = null
    private var retainedCallShutdownTerminal = false
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
    private val routePreferenceRestarter = SerializedTransportRestarter(
        isCurrentActive = { transportJob?.isActive == true },
        stopCurrent = {
            transportJob?.cancelAndJoin()
            transportJob = null
        },
        readPreference = { ServiceConfigStore.read(applicationContext).preferLan },
        startCurrent = { preferLan -> restartTransportFromPersistedConfig(preferLan) },
    )

    override fun onCreate() {
        super.onCreate()
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
        dispatcher = InboundDispatcher(
            this,
            snapshotCoordinator,
            onAuthenticatedEvent = ProductObservationTracker::recordAuthenticatedInbound,
        )
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
        routePreferenceJob = scope.launch { routePreferenceRestarter.run() }
        // Publish only after every field needed by a concurrently reserved shutdown is ready.
        activeInstance = this
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            requestActionStop()
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
        val peer = runBlocking(Dispatchers.IO) { PeerStore.load(applicationContext) }
        val paired = peer != null
        // A peer paired over the LAN has a binding and may have no relay at all.
        val lanBound = peer?.lanBindingId != null
        when (
            val decision = ServiceStartPolicy.decide(intent?.action, config, paired, lanBound)
        ) {
            is ServiceStartDecision.Stop -> {
                SyncServiceStatus.setLastError(decision.reason)
                SyncServiceStatus.setState(SyncState.DISCONNECTED)
                SyncServiceStatus.clearRouteStatus()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
            is ServiceStartDecision.Start -> {
                startForegroundCompat()
                foregroundStarted = true
                recoverCallsBeforeNormalCapture()
                scope.launch { runRetentionSweep() }
                // Initial start and live preference changes share one serialized owner. It
                // rereads the durable config, so a start racing a toggle cannot use stale order.
                routePreferenceRestarter.ensureStarted()
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        shuttingDown = true
        foregroundStarted = false
        SyncServiceStatus.setState(SyncState.DISCONNECTED)
        // Android destruction cannot await durable shutdown. Keep any ACTIVE call rows as the
        // crash journal for Plan 009 startup recovery and perform process-local teardown only.
        executeUnexpectedServiceDestroy(
            closeCallCapture = { stopCallCapture(terminal = true) },
            cancelServiceJobs = {
                transportJob?.cancel()
                routePreferenceJob?.cancel()
                retentionJob?.cancel()
                healthJob?.cancel()
                materializerJob?.cancel()
                scope.cancel()
            },
        )
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
        val activeRelay = transportJob
        quiesceServiceJobsAfterCallShutdown(
            fromRelayJob = fromRelayJob,
            activeRelay = activeRelay,
            stopOtherChildren = {
                retentionJob?.cancelAndJoin()
                healthJob?.cancelAndJoin()
                materializerJob?.cancelAndJoin()
                // A forced preference restart may currently be joining this transport. A
                // peer-unpair callback must cancel that owner instead of joining the cycle.
                stopRoutePreferenceOwner(
                    fromTransportJob = fromRelayJob,
                    cancelOwner = { routePreferenceJob?.cancel() },
                    cancelAndJoinOwner = { routePreferenceJob?.cancelAndJoin() },
                )
            },
            cancelAndJoinServiceScope = {
                scope.coroutineContext[Job]?.cancelAndJoin()
            },
        )
        if (!fromRelayJob) stopForeground(STOP_FOREGROUND_REMOVE)
        shutdownCompleted.complete(Unit)
    }

    private fun foregroundText(health: SyncHealth): String = when (health.service) {
        "connected" -> if (health.queuedCount == 0) "Connected - mirrors notifications to your paired phone."
        else "Connected - ${health.queuedCount} item(s) queued."
        "connecting" -> "Connecting to the paired phone…"
        "degraded" -> "Offline - ${health.queuedCount} item(s) queued for retry."
        else -> "Stopped - syncing is disabled."
    }

    private fun startTransport(relayInput: String?, preferLan: Boolean) {
        if (!admitTransportGeneration(transportJob?.isActive == true, SyncServiceStatus::beginRouteGeneration)) return
        transportJob = scope.launch {
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
            val deviceId = DeviceIdentity.getOrCreate(applicationContext)
            val relayConfig = relayInput?.let { input ->
                val endpoints = try {
                    RelayUrlPolicy.parse(
                        input,
                        debug = (applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0,
                    )
                } catch (_: Throwable) {
                    SyncServiceStatus.setLastError("invalid_relay_url")
                    null
                } ?: return@let null
                val (_, signingKeys) = CryptoStore.loadOrGenerate(applicationContext)
                liveRelayRouteConfig(
                    outbox = outbox,
                    url = endpoints.webSocket,
                    authHeadersProvider = {
                        mapOf("Authorization" to "Bearer " + JwtMinter.mint(deviceId, signingKeys.secretKey))
                    },
                    hooks = LiveRelayRouteHooks(
                        dispatch = { envelope ->
                            dispatchRelayDeliveryWithFinalization {
                                try {
                                    dispatcher.dispatch(envelope)
                                } catch (cancellation: CancellationException) {
                                    throw cancellation
                                } catch (_: Throwable) {
                                    SyncServiceStatus.setLastError("inbound_dispatch")
                                    null
                                }
                            }
                        },
                        onEvent = { event ->
                            when (event) {
                                TransportEvent.LegacyOnlineOnly -> SyncServiceStatus.setState(
                                    SyncState.LEGACY_ONLINE_ONLY,
                                )
                                is TransportEvent.LegacyForwarded,
                                is TransportEvent.RelayRejected,
                                -> updateQueueHealthNow()
                                is TransportEvent.RelayAccepted -> {
                                    recordRelayCustodyObservation(event)
                                    acceptRelayUnpairCustody(event, unpairCustodyTracker)
                                    updateQueueHealthNow()
                                }
                                is TransportEvent.Failed -> SyncServiceStatus.setLastError(
                                    event.error.javaClass.simpleName,
                                )
                                is TransportEvent.Closed -> SyncServiceStatus.setLastError("transport_closed")
                                else -> Unit
                            }
                        },
                        onAuthenticated = { floor ->
                            SyncServiceStatus.setProtocolFloor(floor)
                            updateQueueHealthNow()
                        },
                        onExpired = {
                            updateQueueHealthNow()
                            runTransportSideEffect(
                                block = { snapshotCoordinator.emitLocalDigest(deviceId) },
                                onFailure = { SyncServiceStatus.setLastError("snapshot_emit") },
                            )
                        },
                    ),
                )
            }
            val routeFactory = LiveTransportRoutesFactory.production(
                context = applicationContext,
                outbox = outbox,
                dispatch = dispatcher::dispatch,
                onLanEvent = { event ->
                    if (event is co.twinotify.core.lan.LanTransportEvent.PeerAccepted) {
                        recordLanCustodyObservation(event)
                    }
                    acceptLanUnpairCustody(event, unpairCustodyTracker)
                    updateQueueHealthNow()
                },
            )
            LiveServiceTransportLoop(
                outbox = outbox,
                loadRoutes = { routeFactory.create(relayConfig) },
                queuedCount = { reliableDao.activeOutboundCount() },
                retryRequests = SyncServiceStatus.routeRetryRequested,
                onAuthenticatedRoute = {
                    try {
                        snapshotCoordinator.emitLocalDigest(deviceId)
                    } catch (cancellation: CancellationException) {
                        throw cancellation
                    } catch (_: Throwable) {
                        SyncServiceStatus.setLastError("snapshot_emit")
                    }
                },
                publishHealth = { routeHealth ->
                    val status = routeHealth.toSyncRouteStatus()
                    SyncServiceStatus.setRouteStatus(status)
                    SyncServiceStatus.setState(
                        status.toSyncState(SyncServiceStatus.health.value.protocolFloor),
                    )
                    SyncServiceStatus.setQueueStats(
                        routeHealth.queuedCount,
                        reliableDao.activeOutboundBytes(),
                    )
                    ProductObservationTracker.recordQueue(
                        routeHealth.queuedCount,
                        reliableDao.activeOutboundBytes(),
                    )
                },
            ).run(preferLan)
        }
    }

    private suspend fun restartTransportFromPersistedConfig(preferLan: Boolean) {
        if (shuttingDown) return
        val config = ServiceConfigStore.read(applicationContext)
        val peer = PeerStore.load(applicationContext)
        val decision = ServiceStartPolicy.decide(
            intentAction = null,
            persisted = config,
            paired = peer != null,
            lanBound = peer?.lanBindingId != null,
        )
        if (decision is ServiceStartDecision.Start) {
            startTransport(decision.relayUrl, preferLan)
        }
    }

    private suspend fun updateQueueHealthNow() {
        runTransportSideEffect(
            block = {
                val activeCount = reliableDao.activeOutboundCount()
                val activeBytes = reliableDao.activeOutboundBytes()
                SyncServiceStatus.setQueueStats(
                    activeCount,
                    activeBytes,
                )
                ProductObservationTracker.recordQueue(
                    activeCount,
                    activeBytes,
                )
            },
            onFailure = { SyncServiceStatus.setLastError("queue_health") },
        )
    }

    private fun recoverCallsBeforeNormalCapture() {
        startCallCaptureRecoveryForServiceStart(callCaptureLifecycle.status()) {
            callCaptureStartupGate.start(
                scope = scope,
                recover = {
                    callShutdownGate.awaitRelease()
                    val localDevice = DeviceIdentity.getOrCreate(applicationContext)
                    ActiveCallTerminalizer(
                        store = DaoActiveCallRecoveryStore(reliableDao),
                        persister = CallStatePersister(applicationContext),
                    ).recover(localDevice)
                },
                startCapture = {
                    // Read only after recovery. If this read fails, the helper
                    // reports a bounded failure and retries the full recovery.
                    val config = ServiceConfigStore.read(applicationContext)
                    if (config.enabled) configureCallCapture(config.callCaptureEnabled)
                },
                reportFailure = { code ->
                    SyncServiceStatus.setCallCapture(false, code)
                },
            )
        }
    }

    private fun configureCallCapture(enabled: Boolean, debugSynthetic: Boolean = false): Boolean {
        if (!enabled) {
            stopCallCapture()
            SyncServiceStatus.setCallCapture(false, "call_capture_disabled")
            return false
        }
        val accepted = callCaptureLifecycle.start(admissionReserved = callShutdownGate::isReserved) { install ->
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
        if (!accepted && callShutdownGate.isReserved()) {
            SyncServiceStatus.setCallCapture(false, co.twinotify.core.call.CALL_SHUTDOWN_FAILED)
            if (!debugSynthetic) resumeCallCaptureAfterShutdown()
        }
        return accepted && callCaptureLifecycle.status()?.enabled == true
    }

    private fun resumeCallCaptureAfterShutdown() {
        if (callCaptureReservationWaiter?.isActive == true) return
        lateinit var waiter: Job
        waiter = scope.launch {
            try {
                resumeNormalCallCaptureAfterShutdown(
                    awaitRelease = callShutdownGate::awaitRelease,
                    readConfig = { ServiceConfigStore.read(applicationContext) },
                    configure = { configureCallCapture(it) },
                )
            } finally {
                if (callCaptureReservationWaiter === waiter) callCaptureReservationWaiter = null
            }
        }
        callCaptureReservationWaiter = waiter
    }

    private suspend fun runGracefulCallShutdown(terminal: Boolean): GracefulCallShutdownResult {
        retainedCallShutdownTerminal = retainedCallShutdownTerminal || terminal
        val result = try {
            executeCallShutdownPhases(
                gate = callShutdownGate,
                phaseState = callShutdownPhaseState,
                terminalize = {
                    val lease = callCaptureLifecycle.beginQuiesce(terminal)
                    try {
                        val terminalizeCommittedCalls: suspend () -> Unit = {
                            val localDevice = DeviceIdentity.getOrCreate(applicationContext)
                            ActiveCallTerminalizer(
                                store = DaoActiveCallRecoveryStore(reliableDao),
                                persister = CallStatePersister(applicationContext),
                            ).recover(localDevice)
                        }
                        lease.coordinator?.quiesceAndTerminalize(terminalizeCommittedCalls)
                            ?: terminalizeCommittedCalls()
                        retainedCallShutdownLease = lease
                    } catch (cancellation: CancellationException) {
                        callCaptureLifecycle.finishQuiesce(lease, completed = false)
                        throw cancellation
                    } catch (failure: Exception) {
                        callCaptureLifecycle.finishQuiesce(lease, completed = false)
                        throw failure
                    }
                },
                persistIntent = {
                    retainedCallShutdownTerminal =
                        retainedCallShutdownTerminal || it.disableService
                    ServiceConfigStore.applyCallShutdownIntent(applicationContext, it)
                },
                reportFailure = { SyncServiceStatus.setCallCapture(false, it) },
            )
        } catch (cancellation: CancellationException) {
            releaseRetainedCallShutdownLeaseAfterTerminalFailure()
            throw cancellation
        } catch (failure: Exception) {
            releaseRetainedCallShutdownLeaseAfterTerminalFailure()
            throw failure
        }
        if (result == GracefulCallShutdownResult.Completed) {
            retainedCallShutdownLease?.let {
                callCaptureLifecycle.finishQuiesce(
                    it,
                    completed = true,
                    terminal = retainedCallShutdownTerminal,
                )
            }
            retainedCallShutdownLease = null
            retainedCallShutdownTerminal = false
        } else if (!callShutdownPhaseState.hasTerminalCustody()) {
            releaseRetainedCallShutdownLeaseAfterTerminalFailure()
        }
        return result
    }

    private fun releaseRetainedCallShutdownLeaseAfterTerminalFailure() {
        if (callShutdownPhaseState.hasTerminalCustody()) return
        retainedCallShutdownLease?.let {
            callCaptureLifecycle.finishQuiesce(it, completed = false)
        }
        retainedCallShutdownLease = null
        retainedCallShutdownTerminal = false
    }

    private fun requestActionStop() {
        actionStopGate.start(scope) {
            try {
                executeCallCaptureStopRequest(
                    sharedShutdown = {
                        requestCallShutdown(
                            applicationContext,
                            CallShutdownConfigIntent(disableCallCapture = false, disableService = true),
                        ).let { awaitCallShutdownResult(it) }
                    },
                    finalizeStop = { finalizeActionStop() },
                )
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: co.twinotify.core.call.ActiveCallRecoveryException) {
                SyncServiceStatus.setCallCapture(false, failure.code)
            }
        }
    }

    private suspend fun finalizeActionStop() {
        if (shuttingDown) return
        shuttingDown = true
        foregroundStarted = false
        transportJob?.cancelAndJoin()
        retentionJob?.cancelAndJoin()
        healthJob?.cancelAndJoin()
        materializerJob?.cancelAndJoin()
        routePreferenceJob?.cancelAndJoin()
        SyncServiceStatus.setState(SyncState.DISCONNECTED)
        stopForeground(STOP_FOREGROUND_REMOVE)
        shutdownCompleted.complete(Unit)
        stopSelf()
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
