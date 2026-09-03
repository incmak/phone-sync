package co.twinotify.core.call

import android.app.Notification
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import co.twinotify.core.service.CallMirrorForegroundSlot
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Test-only stand-in for the sync service's foreground slot. Android admits a
 * `Notification.CallStyle` only from a foreground service, so the instrumented proof must
 * render the call mirror the same way production does: through the single foreground slot.
 */
class CallMirrorTestForegroundService : Service() {
    internal lateinit var slot: CallMirrorForegroundSlot<Notification>
        private set

    override fun onCreate() {
        super.onCreate()
        slot = CallMirrorForegroundSlot(STATUS_ID) { id, notification ->
            startForeground(id, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_REMOTE_MESSAGING)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        lastError = runCatching { slot.renderStatus(statusNotification(this)) }.exceptionOrNull()
        instance = this
        started.countDown()
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val STATUS_ID = 9_001

        @Volatile private var instance: CallMirrorTestForegroundService? = null
        @Volatile private var started = CountDownLatch(1)
        @Volatile var lastError: Throwable? = null
            private set

        fun reset() {
            started = CountDownLatch(1)
            lastError = null
        }

        fun statusNotification(context: android.content.Context): Notification =
            Notification.Builder(context, co.twinotify.core.service.NotifChannelSetup.CHANNEL_CALLS)
                .setSmallIcon(android.R.drawable.stat_sys_phone_call)
                .setContentTitle("Twinotify")
                .setContentText("Connected")
                .build()

        fun await(timeoutMs: Long): CallMirrorTestForegroundService? {
            if (!started.await(timeoutMs, TimeUnit.MILLISECONDS)) return null
            return instance
        }
    }
}
