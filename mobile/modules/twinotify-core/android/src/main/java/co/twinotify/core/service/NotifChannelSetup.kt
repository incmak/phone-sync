package co.twinotify.core.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.media.AudioAttributes
import android.net.Uri
import co.twinotify.core.R

object NotifChannelSetup {
    /**
     * Versioned deliberately. Android fixes a channel's sound the moment it first creates the
     * channel: a later `createNotificationChannel` with the same id updates only the name,
     * description and (downward) importance, and deleting an id does not clear the settings the
     * platform remembers for it. Giving mirrored notifications their own tone on phones that
     * already have the app therefore requires a new id, not a `setSound` call.
     */
    const val CHANNEL_MIRRORS = "mirrored_notifications_v2"
    private const val CHANNEL_MIRRORS_LEGACY = "mirrored_notifications"
    const val CHANNEL_CALLS   = "mirrored_call_state_v1"
    const val CHANNEL_FGS     = "twinotify_fgs_status"

    fun ensureChannels(ctx: Context) {
        val mgr = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (mgr.getNotificationChannel(CHANNEL_MIRRORS) == null) {
            mgr.createNotificationChannel(NotificationChannel(
                CHANNEL_MIRRORS, "Mirrored notifications", NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Notifications mirrored from your paired device."
                setSound(
                    Uri.parse("android.resource://${ctx.packageName}/${R.raw.twinotify_notification}"),
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build(),
                )
            })
        }
        // Leaves one entry in system settings rather than a live channel beside a dead one. A
        // user who had silenced the old channel gets the tone back and can silence this one.
        mgr.deleteNotificationChannel(CHANNEL_MIRRORS_LEGACY)
        if (mgr.getNotificationChannel(CHANNEL_FGS) == null) {
            mgr.createNotificationChannel(NotificationChannel(
                CHANNEL_FGS, "Twinotify status", NotificationManager.IMPORTANCE_LOW
            ).apply { description = "Ongoing connection status (can be hidden in system settings)." })
        }
        // The call channel keeps platform ring behaviour on purpose: an incoming call should not
        // sound like a mirrored message.
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
