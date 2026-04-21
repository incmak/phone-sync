package co.twinotify.core.service

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.util.Base64
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import co.twinotify.core.listener.NotifPostJson
import co.twinotify.core.storage.NotificationDb
import kotlin.random.Random

object MirrorPoster {
    suspend fun post(ctx: Context, post: NotifPostJson) {
        NotifChannelSetup.ensureChannels(ctx)
        val localId = Random.nextInt(1, Int.MAX_VALUE)
        val localTag = "mirror-${post.canon_id.hashCode()}"

        val smallIcon = post.small_icon_png_b64?.let(::decodeBitmap)
        val largeIcon = post.large_icon_png_b64?.let(::decodeBitmap)

        val tapIntent = Intent("co.twinotify.MIRROR_TAP").apply {
            putExtra("canon_id", post.canon_id)
            setPackage(ctx.packageName)
        }
        val tapPi = PendingIntent.getBroadcast(
            ctx, localId, tapIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        val nb = NotificationCompat.Builder(ctx, NotifChannelSetup.CHANNEL_MIRRORS)
            .setContentTitle(post.title ?: "")
            .setContentText(post.text ?: "")
            .setSubText(post.sub_text)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)  // spec §4.7.3
            .setAutoCancel(true)                                   // spec §4.7.1
            .setContentIntent(tapPi)
            .setSmallIcon(android.R.drawable.ic_dialog_info)       // fallback — real smallIcon is a Bitmap, not an Icon resource; Phase 3 uses the system one until Phase 7 icon cache lands
            .apply {
                if (largeIcon != null) setLargeIcon(largeIcon)
                if (post.big_text != null) setStyle(NotificationCompat.BigTextStyle().bigText(post.big_text))
            }
            .build()
            .also { if (!post.is_clearable) it.flags = it.flags or Notification.FLAG_NO_CLEAR }

        // POST_NOTIFICATIONS runtime perm (API 33+) — if missing, notify() is a no-op but no exception thrown
        NotificationManagerCompat.from(ctx).notify(localTag, localId, nb)

        val dao = NotificationDb.get(ctx).notificationMapDao()
        dao.putMirror(post.canon_id, extractOrigin(post.canon_id), localId, localTag)
    }

    private fun decodeBitmap(b64: String): android.graphics.Bitmap? {
        return try {
            val bytes = Base64.decode(b64, Base64.DEFAULT)
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        } catch (e: Throwable) { null }
    }

    private fun extractOrigin(canonId: String): String = canonId.substringBefore(':')
}
