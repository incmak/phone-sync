package co.twinotify.core.actions

import android.app.ActivityOptions
import android.app.Notification
import android.app.PendingIntent
import android.app.RemoteInput
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle

class PendingIntentActionExecutor(
    private val context: Context,
) : RegisteredActionExecutor<Notification.Action> {
    override suspend fun dispatch(handle: Notification.Action, replyText: String?): Boolean {
        val fillIn = Intent()
        if (replyText != null) {
            val remoteInputs = handle.remoteInputs.orEmpty().filter { it.allowFreeFormInput }.toTypedArray()
            if (remoteInputs.isEmpty()) return false
            val results = Bundle().apply {
                remoteInputs.forEach { input -> putCharSequence(input.resultKey, replyText) }
            }
            runCatching { RemoteInput.addResultsToIntent(remoteInputs, fillIn, results) }
                .getOrElse { return false }
        }

        val options = if (handle.actionIntent.isActivity) {
            @Suppress("DEPRECATION")
            val backgroundStartMode = if (Build.VERSION.SDK_INT >= 36) {
                ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOW_ALWAYS
            } else {
                ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED
            }
            ActivityOptions.makeBasic()
                .setPendingIntentBackgroundActivityStartMode(backgroundStartMode)
                .toBundle()
        } else {
            null
        }
        return try {
            handle.actionIntent.send(context, 0, fillIn, null, null, null, options)
            true
        } catch (_: PendingIntent.CanceledException) {
            false
        } catch (_: SecurityException) {
            false
        } catch (_: IllegalArgumentException) {
            false
        }
    }

    companion object {
        fun supportsReply(handle: Notification.Action): Boolean =
            handle.remoteInputs.orEmpty().any { it.allowFreeFormInput }
    }
}
