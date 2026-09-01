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
import androidx.core.app.Person
import androidx.core.app.RemoteInput
import androidx.core.net.toUri
import co.twinotify.core.R
import co.twinotify.core.actions.ActionInvokeReceiver
import co.twinotify.core.actions.MirrorActionIntent
import co.twinotify.core.detail.NotificationRouterActivity
import co.twinotify.core.listener.NotifPostJson
import co.twinotify.core.storage.NotificationDb
import co.twinotify.core.storage.ActionInvocation

object MirrorPoster {
    @SuppressLint("LaunchActivityFromNotification")
    fun buildNotification(
        ctx: Context,
        post: NotifPostJson,
        localId: Int,
        localTag: String = NotificationStateReducer.stableMirrorTag(post.canon_id),
        invocations: List<ActionInvocation> = emptyList(),
        detailId: String? = null,
    ): Notification {
        require(localId > 0) { "local notification ID must be positive" }
        NotifChannelSetup.ensureChannels(ctx)
        val smallIcon = post.small_icon_png_b64?.let(::decodeBitmap)
        val largeIcon = post.large_icon_png_b64?.let(::decodeBitmap)
        val sourceArtwork = largeIcon ?: smallIcon

        val tapPi = detailId?.let {
            val tapIntent = Intent(ctx, NotificationRouterActivity::class.java).apply {
                data = "${NotificationRouterActivity.SCHEME}://${NotificationRouterActivity.NOTIFICATION_HOST}/$it".toUri()
            }
            PendingIntent.getActivity(
                ctx, 0, tapIntent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        }
        val expandedText = post.big_text?.takeIf { it.isNotBlank() }
            ?: post.text?.takeIf { it.isNotBlank() }
            ?: post.title
        val presentation = MirrorActionPresentation.from(ctx, invocations)
        return NotificationCompat.Builder(ctx, NotifChannelSetup.CHANNEL_MIRRORS)
            .setContentTitle(post.title ?: "")
            .setContentText(post.text ?: "")
            .setSubText(presentation.statusText ?: post.sub_text)
            .setVisibility(NotifVisibility.toAndroid(post.visibility))
            .setAutoCancel(post.is_auto_cancel)
            .setSmallIcon(R.drawable.ic_stat_twinotify)
            .apply {
                tapPi?.let(::setContentIntent)
                if (sourceArtwork != null) setLargeIcon(sourceArtwork)
                val conversationStyle = post.conversation?.takeIf { it.messages.isNotEmpty() }?.let { conversation ->
                    val style = NotificationCompat.MessagingStyle(
                        Person.Builder().setName(post.app_name ?: post.title ?: "Source app").build(),
                    )
                    conversation.title?.let(style::setConversationTitle)
                    style.setGroupConversation(conversation.is_group)
                    conversation.messages.forEach { message ->
                        val sender = message.sender_name?.let { senderName ->
                            Person.Builder().setName(senderName).apply {
                                message.sender_key?.let(::setKey)
                            }.build()
                        }
                        style.addMessage(
                            NotificationCompat.MessagingStyle.Message(
                                message.text,
                                message.timestamp,
                                sender,
                            ),
                        )
                    }
                    style
                }
                if (conversationStyle != null) {
                    setStyle(conversationStyle)
                } else if (!expandedText.isNullOrBlank()) {
                    setStyle(NotificationCompat.BigTextStyle().bigText(expandedText))
                }
                presentation.replyText?.let { setRemoteInputHistory(arrayOf(it)) }
                post.actions.take(3).filterNot { it.action_id in presentation.pendingActionIds }.forEach { action ->
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

private data class MirrorActionPresentation(
    val statusText: String?,
    val replyText: String?,
    val pendingActionIds: Set<String>,
) {
    companion object {
        fun from(context: Context, invocations: List<ActionInvocation>): MirrorActionPresentation {
            val latest = invocations.firstOrNull()
                ?: return MirrorActionPresentation(null, null, emptySet())
            val label = when (latest.state) {
                "PENDING" -> R.string.mirror_action_pending
                "DISPATCHED" -> R.string.mirror_action_dispatched
                "OUTCOME_UNKNOWN" -> R.string.mirror_action_outcome_unknown
                "FAILED" -> R.string.mirror_action_failed
                "ACTION_GONE" -> R.string.mirror_action_gone
                "NOTIFICATION_GONE" -> R.string.mirror_action_notification_gone
                "EXPIRED" -> R.string.mirror_action_expired
                else -> return MirrorActionPresentation(null, null, emptySet())
            }
            return MirrorActionPresentation(
                statusText = context.getString(label),
                replyText = latest.replyText.takeIf { latest.state == "PENDING" },
                pendingActionIds = invocations.asSequence()
                    .filter { it.state == "PENDING" }
                    .map { it.actionId }
                    .toSet(),
            )
        }
    }
}

private object NotifVisibility {
    fun toAndroid(value: String): Int = when (value) {
        "public" -> NotificationCompat.VISIBILITY_PUBLIC
        "secret" -> NotificationCompat.VISIBILITY_SECRET
        else -> NotificationCompat.VISIBILITY_PRIVATE
    }
}
