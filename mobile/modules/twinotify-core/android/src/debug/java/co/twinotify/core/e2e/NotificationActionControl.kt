package co.twinotify.core.e2e

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.RemoteInput
import android.content.Context
import android.content.Intent
import android.os.Bundle
import co.twinotify.core.actions.ActionControlEncoder
import co.twinotify.core.actions.ActionDispatchGate
import co.twinotify.core.actions.ActionInvokeInput
import co.twinotify.core.actions.MirrorActionIntent
import co.twinotify.core.service.SyncService
import co.twinotify.core.storage.NotificationDb

internal object NotificationActionControl {
    private const val FIXED_REPLY = "Twinotify E2E reply"
    private var armed: PendingIntent? = null
    private var armedRemoteInputs: Array<RemoteInput>? = null

    suspend fun execute(context: Context, operation: String): E2eControlOutcome = when (operation) {
        "invoke_reply" -> invoke(context, reply = true, retain = false)
        "invoke_mark_read" -> invoke(context, reply = false, retain = false)
        "arm_reply" -> arm(context, reply = true)
        "arm_mark_read" -> arm(context, reply = false)
        "invoke_armed" -> send(context, armed, armedRemoteInputs)
        "tap" -> newestMirror(context)?.notification?.contentIntent
            ?.let { send(context, it, null) }
            ?: E2eControlOutcome("not_found", "mirror_tap_unavailable")
        "replay_last_invoke" -> replayLastInvoke(context)
        else -> E2eControlOutcome("unavailable", "unknown_operation")
    }

    fun origin(operation: String): E2eControlOutcome = when (operation) {
        "pause_after_claim" -> if (ActionDispatchGate.arm()) {
            E2eControlOutcome("ok", "armed")
        } else {
            E2eControlOutcome("unavailable", "already_armed")
        }
        "release_claim_pause" -> if (ActionDispatchGate.release()) {
            E2eControlOutcome("ok", "released")
        } else {
            E2eControlOutcome("unavailable", "not_armed")
        }
        else -> E2eControlOutcome("unavailable", "unknown_operation")
    }

    private fun arm(context: Context, reply: Boolean): E2eControlOutcome {
        val action = newestAction(context, reply) ?: return E2eControlOutcome("not_found", "mirror_action_unavailable")
        armed = action.actionIntent
        armedRemoteInputs = action.remoteInputs
        return E2eControlOutcome("ok", "armed")
    }

    private fun invoke(context: Context, reply: Boolean, retain: Boolean): E2eControlOutcome {
        val action = newestAction(context, reply) ?: return E2eControlOutcome("not_found", "mirror_action_unavailable")
        if (retain) {
            armed = action.actionIntent
            armedRemoteInputs = action.remoteInputs
        }
        return send(context, action.actionIntent, action.remoteInputs)
    }

    private fun send(context: Context, pending: PendingIntent?, inputs: Array<RemoteInput>?): E2eControlOutcome {
        pending ?: return E2eControlOutcome("not_found", "pending_intent_unavailable")
        return runCatching {
            val fillIn = Intent()
            if (!inputs.isNullOrEmpty()) {
                RemoteInput.addResultsToIntent(inputs, fillIn, Bundle().apply {
                    putCharSequence(MirrorActionIntent.REMOTE_INPUT_KEY, FIXED_REPLY)
                })
            }
            pending.send(context, 0, fillIn)
            E2eControlOutcome("ok", "sent")
        }.getOrElse { E2eControlOutcome("unavailable", "pending_intent_failed") }
    }

    private fun newestAction(context: Context, reply: Boolean): Notification.Action? = newestMirror(context)
        ?.notification?.actions.orEmpty()
        .firstOrNull { !it.remoteInputs.isNullOrEmpty() == reply }

    private fun newestMirror(context: Context) = context.getSystemService(NotificationManager::class.java)
        ?.activeNotifications.orEmpty()
        .filter { it.packageName == context.packageName && it.tag?.startsWith("twinotify-mirror-") == true }
        .maxByOrNull { it.postTime }

    private suspend fun replayLastInvoke(context: Context): E2eControlOutcome {
        val database = NotificationDb.get(context).openHelper.readableDatabase
        val previous = database.query(
            "SELECT invocationId, canonId, actionId, notificationSequence FROM action_invocation " +
                "ORDER BY updatedAt DESC, invocationId DESC LIMIT 1",
        ).use { cursor ->
            if (!cursor.moveToFirst()) null else ActionInvokeInput(
                invocationId = cursor.getString(0),
                canonId = cursor.getString(1),
                actionId = cursor.getString(2),
                notificationSequence = cursor.getLong(3),
                replyText = null,
            )
        } ?: return E2eControlOutcome("not_found", "invocation_unavailable")
        return runCatching {
            val row = ActionControlEncoder(context.applicationContext).encodeInvoke(previous)
            NotificationDb.get(context).reliableDeliveryDao().insertOutbound(row)
            SyncService.notifyActionOutboxChanged(context.applicationContext)
            E2eControlOutcome("ok", "replayed")
        }.getOrElse { E2eControlOutcome("unavailable", "replay_failed") }
    }
}
