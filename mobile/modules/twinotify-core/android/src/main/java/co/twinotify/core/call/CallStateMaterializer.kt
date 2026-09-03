package co.twinotify.core.call

import android.app.Notification
import android.app.PendingIntent
import android.app.Person
import android.graphics.drawable.Icon
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.net.toUri
import co.twinotify.core.R
import co.twinotify.core.service.NotifChannelSetup
import co.twinotify.core.storage.ActionInvocation
import co.twinotify.core.storage.CanonicalNotificationState
import java.security.MessageDigest
import java.util.UUID
import org.json.JSONObject

data class CallNotificationContent(val title: String, val text: String)

enum class CallNotificationMode(val capabilityCode: String) {
    GENERIC_CATEGORY_CALL("call_style_deferred_no_controls"),
    CALL_STYLE_CONDITIONAL_CONTROLS("call_style_conditional_controls"),
}

sealed interface CallNotificationModel {
    val personName: String

    data class IncomingControllable(
        override val personName: String,
        val answer: CallControlDescriptor,
        val decline: CallControlDescriptor,
    ) : CallNotificationModel

    data class OngoingControllable(
        override val personName: String,
        val hangUp: CallControlDescriptor,
    ) : CallNotificationModel

    data class StateOnly(
        override val personName: String,
        val title: String,
        val text: String,
    ) : CallNotificationModel

    data class Attempted(
        override val personName: String,
        val title: String,
        val text: String,
    ) : CallNotificationModel
}

/** Builds privacy-bounded call notifications and fails closed when controls are incomplete. */
object CallStateMaterializer {
    val mode: CallNotificationMode = CallNotificationMode.CALL_STYLE_CONDITIONAL_CONTROLS
    fun isCall(canonId: String): Boolean = canonId.startsWith("call:")

    fun stableTag(canonId: String): String = "call-" + callStateSha256(canonId).take(24)

    fun content(payloadJson: String): CallNotificationContent {
        val payload = JSONObject(payloadJson)
        return when (payload.getString("state")) {
            "ringing" -> CallNotificationContent("Incoming call", "Incoming call")
            "active" -> CallNotificationContent("Call in progress", "Call in progress")
            "idle" -> CallNotificationContent("Call ended", "Call ended")
            else -> error("unsupported call state")
        }
    }

    fun model(
        state: CanonicalNotificationState,
        invocations: List<ActionInvocation>,
        peerName: String?,
    ): CallNotificationModel {
        require(isCall(state.canonId)) { "not a call canonical ID" }
        val payloadJson = requireNotNull(state.desiredPayloadJson) { "call state payload required" }
        val payload = JSONObject(payloadJson)
        val content = content(payloadJson)
        val personName = "Call on ${peerName?.trim()?.takeIf(String::isNotEmpty) ?: "Paired device"}"
        if (invocations.isNotEmpty()) {
            return CallNotificationModel.Attempted(
                personName,
                content.title,
                "Control attempted. Check the other phone.",
            )
        }
        val controls = parseControls(payload) ?: return CallNotificationModel.StateOnly(
            personName,
            content.title,
            content.text,
        )
        return when {
            payload.optString("state") == "ringing" && payload.optString("direction") == "incoming" &&
                controls.keys == setOf(CallControlKind.ANSWER, CallControlKind.DECLINE) ->
                CallNotificationModel.IncomingControllable(
                    personName,
                    requireNotNull(controls[CallControlKind.ANSWER]),
                    requireNotNull(controls[CallControlKind.DECLINE]),
                )
            payload.optString("state") == "active" && payload.optString("direction") == "incoming" &&
                controls.keys == setOf(CallControlKind.HANG_UP) ->
                CallNotificationModel.OngoingControllable(
                    personName,
                    requireNotNull(controls[CallControlKind.HANG_UP]),
                )
            else -> CallNotificationModel.StateOnly(personName, content.title, content.text)
        }
    }

    fun build(
        context: Context,
        state: CanonicalNotificationState,
        localId: Int,
        invocations: List<ActionInvocation> = emptyList(),
        peerName: String? = null,
    ): Notification {
        require(isCall(state.canonId)) { "not a call canonical ID" }
        require(localId > 0) { "call notification ID must be positive" }
        val tag = requireNotNull(state.mirrorLocalTag) { "call mirror tag required" }
        require(tag == stableTag(state.canonId)) { "call mirror tag mismatch" }
        require(state.mirrorLocalId == localId) { "call mirror ID mismatch" }
        val model = model(state, invocations, peerName)
        val builder = Notification.Builder(context, NotifChannelSetup.CHANNEL_CALLS)
            .setSmallIcon(R.drawable.ic_stat_twinotify)
            .setCategory(Notification.CATEGORY_CALL)
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .setOngoing(true)
        when (model) {
            is CallNotificationModel.IncomingControllable -> builder.setStyle(
                Notification.CallStyle.forIncomingCall(
                    callPerson(context, model),
                    callPendingIntent(context, model.decline, tag, localId),
                    callPendingIntent(context, model.answer, tag, localId),
                ),
            )
            is CallNotificationModel.OngoingControllable -> builder.setStyle(
                Notification.CallStyle.forOngoingCall(
                    callPerson(context, model),
                    callPendingIntent(context, model.hangUp, tag, localId),
                ),
            )
            is CallNotificationModel.StateOnly -> builder
                .setContentTitle(model.title)
                .setContentText(model.text)
            is CallNotificationModel.Attempted -> builder
                .setContentTitle(model.title)
                .setContentText(model.text)
        }
        return builder.build()
    }

    private fun parseControls(payload: JSONObject): Map<CallControlKind, CallControlDescriptor>? {
        val raw = payload.optJSONArray("controls") ?: return null
        val controls = LinkedHashMap<CallControlKind, CallControlDescriptor>()
        val ids = HashSet<String>()
        repeat(raw.length()) { index ->
            val item = raw.optJSONObject(index) ?: return null
            if (item.length() != 2) return null
            val id = item.optString("control_id")
            if (runCatching { UUID.fromString(id).toString() }.getOrNull() != id || !ids.add(id)) return null
            val kind = runCatching { CallControlKind.fromWire(item.optString("kind")) }.getOrNull()
                ?: return null
            if (controls.put(kind, CallControlDescriptor(id, kind)) != null) return null
        }
        return controls
    }

    /** The "person" is the paired phone, so its avatar is the app mark rather than a letter. */
    private fun callPerson(context: Context, model: CallNotificationModel): Person = Person.Builder()
        .setName(model.personName)
        .setIcon(Icon.createWithResource(context, context.applicationInfo.icon))
        .setImportant(true)
        .build()

    private fun callPendingIntent(
        context: Context,
        control: CallControlDescriptor,
        tag: String,
        id: Int,
    ): PendingIntent {
        val rawUri = MirrorCallControlIntent.dataUri(tag, id, control.controlId, control.kind)
        val intent = Intent(context, CallControlInvokeReceiver::class.java).apply {
            data = rawUri.toUri()
            // A background app's own manifest receiver is otherwise deferred for seconds on
            // modern Android; a call control tap must reach the invoke path immediately.
            addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
        }
        return PendingIntent.getBroadcast(
            context,
            requestCode(rawUri),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun requestCode(rawUri: String): Int {
        val digest = MessageDigest.getInstance("SHA-256").digest(rawUri.toByteArray(Charsets.UTF_8))
        val value = ((digest[0].toInt() and 0xff) shl 24) or
            ((digest[1].toInt() and 0xff) shl 16) or
            ((digest[2].toInt() and 0xff) shl 8) or
            (digest[3].toInt() and 0xff)
        return (value and Int.MAX_VALUE).coerceAtLeast(1)
    }
}
