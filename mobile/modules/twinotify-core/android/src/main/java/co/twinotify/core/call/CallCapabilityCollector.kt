package co.twinotify.core.call

import android.app.Notification
import android.app.PendingIntent
import android.service.notification.StatusBarNotification

object CallCapabilityCollector {
    fun capture(sbn: StatusBarNotification): CallCapabilityCandidate<PendingIntent> {
        val extras = sbn.notification.extras
        return CallCapabilityCandidate(
            sourceKey = sbn.key,
            packageName = sbn.packageName,
            category = sbn.notification.category,
            answer = extras.getParcelable(
                Notification.EXTRA_ANSWER_INTENT,
                PendingIntent::class.java,
            ),
            decline = extras.getParcelable(
                Notification.EXTRA_DECLINE_INTENT,
                PendingIntent::class.java,
            ),
            hangUp = extras.getParcelable(
                Notification.EXTRA_HANG_UP_INTENT,
                PendingIntent::class.java,
            ),
        )
    }
}
