package co.twinotify.core.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build

object NotifChannelSetup {
    const val CHANNEL_MIRRORS = "mirrored_notifications"
    const val CHANNEL_FGS     = "twinotify_fgs_status"

    fun ensureChannels(ctx: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
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
    }
}
