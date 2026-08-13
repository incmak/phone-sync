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
            ).materializePending()
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
                .onFailure { android.util.Log.w(TAG, "retention sweep unavailable", it) }
        }
        coordinator.resumeDeferred()
        val active = runCatching { activeNotifications.orEmpty() }.getOrDefault(emptyArray())
        active.forEach(::capturePosted)
        scope.launch {
            try {
                val liveKeys = active.mapTo(hashSetOf()) { it.key }
                CaptureReconciliation.missingActiveStates(
                    reliableDao.activeOriginStates(originDevice),
                    liveKeys,
                )
                    .forEach { state ->
                        coordinator.submit(
                            RemoveCommand(
                                canonId = state.canonId,
                                sourceKey = state.sourceNotificationKey.orEmpty(),
                                reason = "listener_reconcile",
                                removedAt = System.currentTimeMillis(),
                            ),
                        )
                    }
            } catch (error: Throwable) {
                android.util.Log.w(TAG, "active notification reconciliation unavailable", error)
            }
        }
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
        check(coordinator.submit(PostCommand(canonId, snapshot.sourceKey, snapshot))) {
            android.util.Log.e(TAG, "durable capture lane rejected post canon=$canonId")
        }
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
                if (reliableDao.consumePeerCancel(canonId) > 0) {
                    dao.deleteByCanonId(canonId)
                    return@launch
                }
                processRemoved(sbn, canonId, ownPkg = true, reason, ts)
            }
        } else {
            scope.launch(removalDispatcher) {
                val canonId = reliableDao.canonicalForSourceKey(sbn.key)
                    ?: CanonIdBuilder.build(originDevice, sbn.packageName, sbn.id, sbn.tag)
                processRemoved(sbn, canonId, ownPkg = false, reason, ts)
            }
        }
    }

    private fun processRemoved(
        sbn: StatusBarNotification,
        canonId: String,
        ownPkg: Boolean,
        reason: Int,
        timestamp: Long,
    ) {
        val canonInPending = PendingPeerCancel.consume(canonId)
        when (val result = ReasonCodeFilter.filter(ownPkg, canonInPending, reason)) {
            is FilterResult.Suppress -> if (ownPkg) scope.launch {
                reliableDao.clearPeerCancelPending(canonId)
                dao.deleteByCanonId(canonId)
            }
            is FilterResult.NoEmit -> Unit
            is FilterResult.Emit -> {
                check(coordinator.submit(RemoveCommand(canonId, sbn.key, result.reason, timestamp))) {
                    android.util.Log.e(TAG, "durable capture lane rejected cancel canon=$canonId")
                }
                if (ownPkg) scope.launch {
                    reliableDao.clearPeerCancelPending(canonId)
                    dao.deleteByCanonId(canonId)
                }
            }
        }
    }

    companion object {
        private const val TAG = "TwinotifyListener"
    }
}
