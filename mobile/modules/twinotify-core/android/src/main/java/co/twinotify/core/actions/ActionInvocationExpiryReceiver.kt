package co.twinotify.core.actions

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import co.twinotify.core.service.DefaultAndroidNotificationPort
import co.twinotify.core.storage.ActionInvocation
import co.twinotify.core.storage.DeviceIdentity
import co.twinotify.core.storage.NotificationDb
import co.twinotify.core.storage.ReliableDeliveryDao
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class PersistentActionInvocationExpiryScheduler(
    private val context: Context,
) : ActionInvocationExpiryScheduler {
    override fun schedule(dueAt: Long) {
        ActionInvocationExpiryRuntime.schedule(context.applicationContext, dueAt)
    }
}

class DaoActionInvocationExpiryStore(
    private val dao: ReliableDeliveryDao,
) : ActionInvocationExpiryStore {
    override suspend fun due(now: Long): List<ActionInvocation> = dao.dueActionInvocations(now)

    override suspend fun expire(row: ActionInvocation, now: Long): ActionExpiryCommitResult =
        dao.expireActionInvocation(row, now)

    override suspend fun earliestDueAt(): Long? = dao.earliestPendingActionInvocationAt()
}

internal object ActionInvocationExpiryRuntime {
    private const val REQUEST_CODE = 7_305
    private const val EXTRA_DUE_AT = "due_at"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val earliest = EarliestActionClaimWake()
    private val lock = Any()
    private var job: Job? = null

    fun schedule(context: Context, dueAt: Long) {
        synchronized(lock) {
            if (!earliest.claim(dueAt)) return
            job?.cancel()
            persistAlarm(context, dueAt)
            job = scope.launch {
                delay((dueAt - System.currentTimeMillis()).coerceAtLeast(0L))
                if (earliest.consume(dueAt)) expire(context)
            }
        }
    }

    suspend fun receive(context: Context, dueAt: Long) {
        synchronized(lock) {
            if (earliest.consume(dueAt)) job?.cancel()
        }
        expire(context.applicationContext)
    }

    suspend fun expire(context: Context): ActionInvocationExpirySummary {
        val app = context.applicationContext
        val dao = NotificationDb.get(app).reliableDeliveryDao()
        return ActionInvocationExpiry(
            store = DaoActionInvocationExpiryStore(dao),
            repost = ActionExpiredReposter { row ->
                val state = dao.canonical(row.canonId)
                    ?.takeIf { it.state == "ACTIVE" && it.latestSequence == row.notificationSequence }
                    ?: return@ActionExpiredReposter
                DefaultAndroidNotificationPort(
                    app,
                    DeviceIdentity.getOrCreate(app),
                    dao,
                ).postMirror(state)
            },
            scheduler = PersistentActionInvocationExpiryScheduler(app),
        ).expireDue()
    }

    private fun persistAlarm(context: Context, dueAt: Long) {
        val alarm = context.getSystemService(AlarmManager::class.java) ?: return
        val pending = PendingIntent.getBroadcast(
            context,
            REQUEST_CODE,
            Intent(context, ActionInvocationExpiryReceiver::class.java).putExtra(EXTRA_DUE_AT, dueAt),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        try {
            alarm.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, dueAt, pending)
        } catch (_: SecurityException) {
            // The process timer remains armed; service startup rehydrates Room state.
        }
    }

    fun dueAt(intent: Intent?): Long = intent?.getLongExtra(EXTRA_DUE_AT, -1L) ?: -1L
}

class ActionInvocationExpiryReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val dueAt = ActionInvocationExpiryRuntime.dueAt(intent)
        if (dueAt < 0L) return
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                ActionInvocationExpiryRuntime.receive(context.applicationContext, dueAt)
            } finally {
                pending.finish()
            }
        }
    }
}
