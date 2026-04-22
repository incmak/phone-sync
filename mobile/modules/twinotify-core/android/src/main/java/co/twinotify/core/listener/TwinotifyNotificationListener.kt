package co.twinotify.core.listener

import android.content.Context
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import co.twinotify.core.filter.DenylistLoader
import co.twinotify.core.storage.DeviceIdentity
import co.twinotify.core.storage.NotificationDb
import co.twinotify.core.storage.NotificationMapDao
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

class TwinotifyNotificationListener : NotificationListenerService() {

    companion object {
        @Volatile private var installedSink: OutboundSink? = null
        fun installSink(s: OutboundSink) { installedSink = s }
        fun currentSink(): OutboundSink = installedSink ?: LoggingOutboundSink
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var dao: NotificationMapDao
    private lateinit var denylist: Set<String>
    private lateinit var originDevice: String

    // Resolves the sink on each event so SyncService.installSink() takes effect
    // without requiring the listener to restart.
    internal val sink: OutboundSink
        get() = installedSink ?: LoggingOutboundSink

    override fun onCreate() {
        super.onCreate()
        val ctx: Context = applicationContext
        dao = NotificationDb.get(ctx).notificationMapDao()
        denylist = DenylistLoader.load(ctx)
        // DeviceIdentity is suspend; use runBlocking since onCreate is not a coroutine.
        originDevice = runBlocking { DeviceIdentity.getOrCreate(ctx) }
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (sbn.packageName == packageName) return   // self-mirror loop guard

        // Fast bail for denylisted packages before doing any work.
        // Default list is cached; user list invalidates on write.
        scope.launch {
            val userDeny = co.twinotify.core.filter.AppFilterStore.load(applicationContext)
            val effective = denylist + userDeny
            val post = NotifPostBuilder.build(sbn, applicationContext, originDevice, effective) ?: return@launch
            sink.enqueuePost(post)
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification, rankingMap: NotificationListenerService.RankingMap?, reason: Int) {
        val ownPkg = sbn.packageName == packageName
        val ts = System.currentTimeMillis()

        scope.launch {
            val canonId = if (ownPkg) {
                dao.lookupByLocal(sbn.id, sbn.tag) ?: return@launch  // lost map (process death); skip
            } else {
                CanonIdBuilder.build(originDevice, sbn.packageName, sbn.id, sbn.tag)
            }

            val canonInPending = PendingPeerCancel.consume(canonId)

            when (val result = ReasonCodeFilter.filter(ownPkg, canonInPending, reason)) {
                is FilterResult.Suppress -> {
                    // Consume tombstone; delete map entries for self-mirror path only
                    if (ownPkg) dao.deleteByCanonId(canonId)
                }
                is FilterResult.NoEmit -> { /* drop silently */ }
                is FilterResult.Emit -> {
                    sink.enqueueCancel(canonId, result.reason, originDevice, ts)
                    if (ownPkg) dao.deleteByCanonId(canonId)
                }
            }
        }
    }
}
