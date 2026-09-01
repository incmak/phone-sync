package co.twinotify.core.service

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import co.twinotify.core.R

internal object ForegroundNotificationFactory {
    const val REQUEST_CODE = 0x544E
    const val pendingIntentFlags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE

    fun prepareLauncherIntent(source: Intent, appPackage: String): Intent {
        val component = requireNotNull(source.component) { "Twinotify launcher intent must be explicit" }
        require(component.packageName == appPackage) { "Twinotify launcher intent must target this package" }
        return Intent(Intent.ACTION_MAIN)
            .setComponent(component)
            .addCategory(Intent.CATEGORY_LAUNCHER)
            .setFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP,
            )
    }

    fun build(
        context: Context,
        presentation: DeliveryPresentation,
        launchIntentProvider: () -> Intent? = {
            context.packageManager.getLaunchIntentForPackage(context.packageName)
        },
    ): Notification {
        val launcher = requireNotNull(launchIntentProvider()) { "Twinotify launcher activity unavailable" }
        val contentIntent = PendingIntent.getActivity(
            context,
            REQUEST_CODE,
            prepareLauncherIntent(launcher, context.packageName),
            pendingIntentFlags,
        )
        return NotificationCompat.Builder(context, NotifChannelSetup.CHANNEL_FGS)
            .setContentTitle(presentation.label)
            .setContentText(presentation.explanation)
            .setSmallIcon(R.drawable.ic_stat_twinotify)
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }
}
