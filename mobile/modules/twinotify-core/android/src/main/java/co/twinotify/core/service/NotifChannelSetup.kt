package co.twinotify.core.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context

object NotifChannelSetup {
    const val CHANNEL_MIRRORS = "mirrored_notifications"
    const val CHANNEL_CALLS   = "mirrored_call_state_v1"
    const val CHANNEL_FGS     = "twinotify_fgs_status"

    fun ensureChannels(ctx: Context) {
        val mgr = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (mgr.getNotificationChannel(CHANNEL_MIRRORS) == null) {
            mgr.createNotificationChannel(NotificationChannel(
                CHANNEL_MIRRORS, "Mirrored notifications", NotificationManager.IMPORTANCE_DEFAULT
            ).apply { description = "Notifications mirrored from your paired device." })
        }
        if (mgr.getNotificationChannel(CHANNEL_FGS) == null) {
            mgr.createNotificationChannel(NotificationChannel(
                CHANNEL_FGS, "Twinotify status", NotificationManager.IMPORTANCE_LOW
            ).apply { description = "Ongoing connection status (can be hidden in system settings)." })
        }
        val calls = mgr.getNotificationChannel(CHANNEL_CALLS) ?: NotificationChannel(
            CHANNEL_CALLS,
            "Mirrored call state",
            NotificationManager.IMPORTANCE_HIGH,
        )
        calls.description = "Incoming call state and controls from your paired phone. Caller identity and audio are not shared."
        calls.lockscreenVisibility = android.app.Notification.VISIBILITY_PRIVATE
        mgr.createNotificationChannel(calls)
    }
}
