package co.twinotify.core.service

import android.content.Context
import co.twinotify.core.storage.NotificationDb

/** Process-independent maintenance entry point used by service and listener recovery. */
object RetentionCoordinator {
    const val INTERVAL_MS = 6 * 60 * 60 * 1_000L
    private const val ACTIVITY_RETENTION_MS = 30 * 24 * 60 * 60 * 1_000L
    private const val LEGACY_MAPPING_RETENTION_MS = 7 * 24 * 60 * 60 * 1_000L
    private const val TOMBSTONE_RETENTION_MS = 7 * 24 * 60 * 60 * 1_000L

    suspend fun sweep(ctx: Context) {
        val app = ctx.applicationContext
        val now = System.currentTimeMillis()
        val db = NotificationDb.get(app)
        db.reliableDeliveryDao().sweepRetention(
            now = now,
            activityRetentionMs = ACTIVITY_RETENTION_MS,
            tombstoneRetentionMs = TOMBSTONE_RETENTION_MS,
        )
        db.notificationMapDao().sweepExpired(now - LEGACY_MAPPING_RETENTION_MS)
    }
}
