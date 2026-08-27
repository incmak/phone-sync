package co.twinotify.core.listener

import android.content.Context
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import co.twinotify.core.filter.AppFilterStore
import co.twinotify.core.filter.DenylistLoader
import co.twinotify.core.metrics.MetricsStore
import co.twinotify.core.storage.DeviceIdentity
import co.twinotify.core.storage.NotificationDb
import co.twinotify.core.storage.NotificationMapDao
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.ExperimentalCoroutinesApi

internal fun submitRemovalWithObservation(
    ownPackage: Boolean,
    durablePeerCancelConsumed: Boolean,
    inMemoryPeerCancelConsumed: Boolean,
    removalReason: Int,
    command: RemoveCommand,
    submit: (RemoveCommand) -> CaptureAdmission,
    requestReconciliation: () -> Unit = {},
    recordUserDismiss: () -> Unit,
): FilterResult {
    val result = if (durablePeerCancelConsumed) {
        FilterResult.Suppress
    } else {
        ReasonCodeFilter.filter(ownPackage, inMemoryPeerCancelConsumed, removalReason)
    }
    if (result is FilterResult.Emit) {
        when (submit(command.copy(reason = result.reason))) {
            CaptureAdmission.Accepted -> if (ownPackage) recordUserDismiss()
            CaptureAdmission.ReconcileRequired -> requestReconciliation()
            CaptureAdmission.Closed -> Unit
        }
    }
    return result
}

/** Android callback adapter. It never depends on SyncService or a React Native lifecycle. */
@OptIn(ExperimentalCoroutinesApi::class)
class TwinotifyNotificationListener : NotificationListenerService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val removalDispatcher = Dispatchers.IO.limitedParallelism(1)
    private lateinit var dao: NotificationMapDao
    private lateinit var reliableDao: co.twinotify.core.storage.ReliableDeliveryDao
    private lateinit var denylist: Set<String>
    private lateinit var originDevice: String
    private lateinit var coordinator: CaptureCoordinator
    private val reconciliationLock = Any()
    private var reconciliationRunning = false

    override fun onCreate() {
        super.onCreate()
        val ctx: Context = applicationContext
        dao = NotificationDb.get(ctx).notificationMapDao()
        reliableDao = NotificationDb.get(ctx).reliableDeliveryDao()
        denylist = DenylistLoader.load(ctx)
        // Load the user filter once before callbacks begin. The callback path then performs no
        // DataStore read and can submit an immutable command immediately in callback order.
        runBlocking { AppFilterStore.load(ctx) }
        originDevice = runBlocking { DeviceIdentity.getOrCreate(ctx) }
        coordinator = CaptureCoordinator.get(ctx)
        val postAvailable = co.twinotify.core.service.effectivePostAvailability(ctx)
        co.twinotify.core.service.SyncServiceStatus.setListenerHealth(
            connected = false,
            permission = true,
        )
        NotificationListenerBridge.attach(this)
        scope.launch {
            co.twinotify.core.service.NotificationMaterializer(
                dao = reliableDao,
                port = co.twinotify.core.service.DefaultAndroidNotificationPort(ctx, originDevice, reliableDao),
                receiptFactory = co.twinotify.core.service.DurableReceiptFactory(ctx),
                localDeviceId = originDevice,
                retryScheduler = co.twinotify.core.service.materializationStartupScheduler(ctx),
            ).materializePending(
                trigger = co.twinotify.core.service.materializationTriggerForPostAvailability(postAvailable),
            )
        }
    }

    override fun onDestroy() {
        co.twinotify.core.service.SyncServiceStatus.setListenerHealth(
            connected = false,
            permission = false,
        )
        NotificationListenerBridge.detach(this)
        scope.cancel()
        super.onDestroy()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        capturePosted(sbn)
    }

    /** Re-submit the platform's current set after a listener rebind/process restart. */
    override fun onListenerConnected() {
        super.onListenerConnected()
        co.twinotify.core.service.SyncServiceStatus.setListenerHealth(
            connected = true,
            permission = true,
        )
        scope.launch {
            runCatching { co.twinotify.core.service.RetentionCoordinator.sweep(applicationContext) }
                .onFailure { android.util.Log.w(TAG, "retention_sweep_unavailable") }
        }
        coordinator.resumeDeferred()
        val active = runCatching { activeNotifications.orEmpty() }.getOrDefault(emptyArray())
        active.forEach(::capturePosted)
        requestCaptureReconciliation()
    }

    private fun capturePosted(sbn: StatusBarNotification) {
        if (sbn.packageName == packageName) return
        val canonId = CanonIdBuilder.build(originDevice, sbn.packageName, sbn.id, sbn.tag)
        val snapshot = NotifPostBuilder.captureSnapshot(
            sbn = sbn,
            ctx = applicationContext,
            denylist = denylist + AppFilterStore.cachedOrEmpty(),
        ) ?: run {
            scope.launch { MetricsStore.incrementBlocked(applicationContext) }
            return
        }
        check(snapshot.sourceKey == sbn.key) { "capture snapshot lost source notification key" }
        submitFromListener(PostCommand(canonId, snapshot.sourceKey, snapshot))
    }

    override fun onNotificationRemoved(
        sbn: StatusBarNotification,
        rankingMap: RankingMap?,
        reason: Int,
    ) {
        val ownPkg = sbn.packageName == packageName
        val ts = System.currentTimeMillis()
        if (ownPkg) {
            // A missing mirror mapping after process death is unrecoverable until a snapshot;
            // never guess from a local id because that could cancel an unrelated notification.
            scope.launch(removalDispatcher) {
                // v2 mirrors persist their canonical identity; resolve it before falling back to
                // the legacy mapping so a cancel echo cannot be emitted for a guessed canonId.
                val canonId = reliableDao.canonicalForMirrorIdentity(sbn.tag.orEmpty(), sbn.id)
                    ?: dao.lookupByLocal(sbn.id, sbn.tag)
                    ?: return@launch
                processRemoved(
                    sbn,
                    canonId,
                    ownPkg = true,
                    durablePeerCancelConsumed = reliableDao.consumePeerCancel(canonId) > 0,
                    reason,
                    ts,
                )
            }
        } else {
            scope.launch(removalDispatcher) {
                val canonId = reliableDao.canonicalForSourceKey(sbn.key)
                    ?: CanonIdBuilder.build(originDevice, sbn.packageName, sbn.id, sbn.tag)
                processRemoved(
                    sbn,
                    canonId,
                    ownPkg = false,
                    durablePeerCancelConsumed = false,
                    reason,
                    ts,
                )
            }
        }
    }

    private fun processRemoved(
        sbn: StatusBarNotification,
        canonId: String,
        ownPkg: Boolean,
        durablePeerCancelConsumed: Boolean,
        reason: Int,
        timestamp: Long,
    ) {
        val canonInPending = PendingPeerCancel.consume(canonId)
        val command = RemoveCommand(canonId, sbn.key, "user_swipe", timestamp)
        when (val result = submitRemovalWithObservation(
            ownPackage = ownPkg,
            durablePeerCancelConsumed = durablePeerCancelConsumed,
            inMemoryPeerCancelConsumed = canonInPending,
            removalReason = reason,
            command = command,
            submit = coordinator::submit,
            requestReconciliation = ::requestCaptureReconciliation,
            recordUserDismiss = co.twinotify.core.service.ProductObservationTracker::recordUserDismiss,
        )) {
            is FilterResult.Suppress -> if (ownPkg) scope.launch {
                reliableDao.clearPeerCancelPending(canonId)
                dao.deleteByCanonId(canonId)
            }
            is FilterResult.NoEmit -> Unit
            is FilterResult.Emit -> {
                if (ownPkg) scope.launch {
                    reliableDao.clearPeerCancelPending(canonId)
                    dao.deleteByCanonId(canonId)
                }
            }
        }
    }

    private fun submitFromListener(command: CaptureCommand) {
        when (coordinator.submit(command)) {
            CaptureAdmission.Accepted -> Unit
            CaptureAdmission.ReconcileRequired -> requestCaptureReconciliation()
            CaptureAdmission.Closed -> android.util.Log.w(TAG, "capture_admission_closed")
        }
    }

    private fun requestCaptureReconciliation() {
        if (!NotificationListenerBridge.isAttached() || !coordinator.reconciliationNeeded()) return
        val shouldLaunch = synchronized(reconciliationLock) {
            if (reconciliationRunning) false else {
                reconciliationRunning = true
                true
            }
        }
        if (!shouldLaunch) return
        scope.launch {
            try {
                reconcileCaptureOverflow()
            } catch (cancellation: kotlinx.coroutines.CancellationException) {
                throw cancellation
            } catch (_: Throwable) {
                android.util.Log.w(TAG, "capture_reconciliation_unavailable")
            } finally {
                synchronized(reconciliationLock) { reconciliationRunning = false }
            }
        }
    }

    private suspend fun reconcileCaptureOverflow() {
        while (coordinator.reconciliationNeeded() && NotificationListenerBridge.isAttached()) {
            if (!coordinator.awaitReconciliationCapacity()) return
            val recoveryGeneration = coordinator.reconciliationGeneration()
            val recovered = try {
                val listenerSnapshot = NotificationListenerBridge.activeCaptureSnapshot(
                    context = applicationContext,
                    denylist = denylist + AppFilterStore.cachedOrEmpty(),
                    selfPackage = packageName,
                )
                CaptureReconciliation.recoveryCommands(
                    originDevice = originDevice,
                    snapshots = listenerSnapshot.sourceSnapshots,
                    states = reliableDao.activeOriginStates(originDevice),
                    peerMirrorStates = reliableDao.activePeerMirrorStates(originDevice),
                    liveMirrorIdentities = listenerSnapshot.liveMirrorIdentities,
                    removedAt = System.currentTimeMillis(),
                )
            } catch (cancellation: kotlinx.coroutines.CancellationException) {
                throw cancellation
            } catch (_: Throwable) {
                android.util.Log.w(TAG, "capture_reconciliation_unavailable")
                if (!coordinator.awaitReconciliationStateChange()) return
                continue
            }
            var saturated = false
            for (command in recovered) {
                when (coordinator.submit(command)) {
                    CaptureAdmission.Accepted -> Unit
                    CaptureAdmission.ReconcileRequired -> {
                        saturated = true
                        break
                    }
                    CaptureAdmission.Closed -> return
                }
            }
            if (!saturated) {
                if (coordinator.clearReconciliationLatchIfCurrent(recoveryGeneration)) return
            }
        }
    }

    companion object {
        private const val TAG = "TwinotifyListener"
    }
}
