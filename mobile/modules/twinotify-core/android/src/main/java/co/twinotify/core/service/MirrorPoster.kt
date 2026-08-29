package co.twinotify.core.service

import android.app.Notification
import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.util.Base64
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.RemoteInput
import androidx.core.net.toUri
import co.twinotify.core.R
import co.twinotify.core.actions.ActionInvokeReceiver
import co.twinotify.core.actions.MirrorActionIntent
import co.twinotify.core.listener.NotifPostJson
import co.twinotify.core.storage.NotificationDb

object MirrorPoster {
    @SuppressLint("LaunchActivityFromNotification")
    fun buildNotification(
        ctx: Context,
        post: NotifPostJson,
        localId: Int,
        localTag: String = NotificationStateReducer.stableMirrorTag(post.canon_id),
    ): Notification {
        require(localId > 0) { "local notification ID must be positive" }
        NotifChannelSetup.ensureChannels(ctx)
        val smallIcon = post.small_icon_png_b64?.let(::decodeBitmap)
        val largeIcon = post.large_icon_png_b64?.let(::decodeBitmap)
        val sourceArtwork = largeIcon ?: smallIcon

        val tapIntent = Intent("co.twinotify.MIRROR_TAP").apply {
            putExtra("canon_id", post.canon_id)
            setPackage(ctx.packageName)
        }
        val tapPi = PendingIntent.getBroadcast(
            ctx, localId, tapIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val expandedText = post.big_text?.takeIf { it.isNotBlank() }
            ?: post.text?.takeIf { it.isNotBlank() }
            ?: post.title
        return NotificationCompat.Builder(ctx, NotifChannelSetup.CHANNEL_MIRRORS)
            .setContentTitle(post.title ?: "")
            .setContentText(post.text ?: "")
            .setSubText(post.sub_text)
            .setVisibility(NotifVisibility.toAndroid(post.visibility))
            .setAutoCancel(post.is_auto_cancel)
            .setContentIntent(tapPi)
            .setSmallIcon(R.drawable.ic_stat_twinotify)
            .apply {
                if (sourceArtwork != null) setLargeIcon(sourceArtwork)
                if (!expandedText.isNullOrBlank()) setStyle(NotificationCompat.BigTextStyle().bigText(expandedText))
                post.actions.take(3).forEach { action ->
                    val invokeIntent = Intent(ctx, ActionInvokeReceiver::class.java).apply {
                        data = MirrorActionIntent.dataUri(localTag, localId, action.action_id).toUri()
                    }
                    val pending = PendingIntent.getBroadcast(
                        ctx,
                        0,
                        invokeIntent,
                        MirrorActionIntent.pendingIntentFlags(action.reply),
                    )
                    val builder = NotificationCompat.Action.Builder(0, action.title, pending)
                        .setSemanticAction(action.semantic)
                        .setAllowGeneratedReplies(false)
                        .setAuthenticationRequired(true)
                    if (action.reply) {
                        builder.addRemoteInput(
                            RemoteInput.Builder(MirrorActionIntent.REMOTE_INPUT_KEY)
                                .setLabel(action.reply_label ?: action.title)
                                .setAllowFreeFormInput(true)
                                .build(),
                        )
                    }
                    addAction(builder.build())
                }
            }
            .build()
            .also { if (!post.is_clearable) it.flags = it.flags or Notification.FLAG_NO_CLEAR }
    }

    // The tap PendingIntent intentionally targets our receiver, which validates and forwards the URI.
    @Suppress("LaunchActivityFromNotification")
    suspend fun post(ctx: Context, post: NotifPostJson) {
        val localId = stableLocalId(post.canon_id)
        val localTag = co.twinotify.core.service.NotificationStateReducer.stableMirrorTag(post.canon_id)
        val notificationManager = NotificationManagerCompat.from(ctx)
        if (notificationManager.areNotificationsEnabled()) {
            notificationManager.notify(localTag, localId, buildNotification(ctx, post, localId, localTag))
        }

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

    private fun stableLocalId(canonId: String): Int {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
            .digest(canonId.toByteArray(Charsets.UTF_8))
        val raw = ((digest[0].toInt() and 0xff) shl 24) or
            ((digest[1].toInt() and 0xff) shl 16) or
            ((digest[2].toInt() and 0xff) shl 8) or
            (digest[3].toInt() and 0xff)
        return (raw and Int.MAX_VALUE).coerceAtLeast(1)
    }
}

private object NotifVisibility {
    fun toAndroid(value: String): Int = when (value) {
        "public" -> NotificationCompat.VISIBILITY_PUBLIC
        "secret" -> NotificationCompat.VISIBILITY_SECRET
        else -> NotificationCompat.VISIBILITY_PRIVATE
    }
}
