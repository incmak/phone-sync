package co.twinotify.core.actions

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import co.twinotify.core.service.SyncService
import co.twinotify.core.storage.NotificationDb
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class PersistentActionClaimWakeScheduler(
    private val context: Context,
) : ActionClaimWakeScheduler {
    override fun schedule(dueAt: Long) {
        ActionClaimRecoveryRuntime.schedule(context.applicationContext, dueAt)
    }
}

internal object ActionClaimRecoveryRuntime {
    private const val REQUEST_CODE = 7_304
    private const val EXTRA_DUE_AT = "due_at"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val earliest = EarliestActionClaimWake()
    private val lock = Any()
    private var job: Job? = null

    fun schedule(context: Context, dueAt: Long) {
        synchronized(lock) {
            if (!earliest.claim(dueAt)) return
            job?.cancel()
            armProcessDeadlineThenPersistAlarm(
                armProcessDeadline = {
                    job = scope.launch {
                        delay((dueAt - System.currentTimeMillis()).coerceAtLeast(0L))
                        if (earliest.consume(dueAt)) recover(context)
                    }
                },
                persistAlarm = { persistAlarm(context, dueAt) },
            )
        }
    }

    suspend fun receive(context: Context, dueAt: Long) {
        synchronized(lock) {
            if (earliest.consume(dueAt)) job?.cancel()
        }
        recover(context.applicationContext)
    }

    suspend fun recover(context: Context): ActionClaimRecoverySummary {
        val app = context.applicationContext
        val dao = NotificationDb.get(app).reliableDeliveryDao()
        val encoder = ActionControlEncoder(app)
        return ActionClaimRecovery(
            store = DaoActionClaimRecoveryStore(dao),
            resultEncoder = ActionResultRowEncoder(encoder::encodeResult),
            scheduler = PersistentActionClaimWakeScheduler(app),
            signalTransport = { SyncService.notifyActionOutboxChanged(app) },
        ).recover()
    }

    private fun persistAlarm(context: Context, dueAt: Long) {
        val alarm = context.getSystemService(AlarmManager::class.java) ?: return
        val intent = Intent(context, ActionClaimRecoveryReceiver::class.java).apply {
            putExtra(EXTRA_DUE_AT, dueAt)
        }
        val pending = PendingIntent.getBroadcast(
            context,
            REQUEST_CODE,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        try {
            alarm.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, dueAt, pending)
        } catch (_: SecurityException) {
            // The process timer remains armed; startup recovery covers process death.
        }
    }

    fun dueAt(intent: Intent?): Long = intent?.getLongExtra(EXTRA_DUE_AT, -1L) ?: -1L
}

class ActionClaimRecoveryReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val dueAt = ActionClaimRecoveryRuntime.dueAt(intent)
        if (dueAt < 0L) return
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                ActionClaimRecoveryRuntime.receive(context.applicationContext, dueAt)
            } finally {
                pending.finish()
            }
        }
    }
}
