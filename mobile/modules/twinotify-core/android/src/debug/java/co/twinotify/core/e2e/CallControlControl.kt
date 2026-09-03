package co.twinotify.core.e2e

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import org.json.JSONObject

/**
 * Mirror-side debug control: sends the typed CallStyle intent of this phone's own call mirror,
 * exactly as a lock-screen tap would. `replay` re-sends the last intent to prove one-use.
 */
internal object CallControlControl {
    private val OPERATIONS = setOf("answer", "decline", "hang_up", "replay")

    @Volatile private var last: PendingIntent? = null

    internal fun kindIsValid(kind: String): Boolean = kind in OPERATIONS

    fun tap(context: Context, kind: String): E2eCommandResultBody {
        if (!kindIsValid(kind)) return E2eCommandResultBody("invalid", "kind must be answer, decline, hang_up, or replay")
        val pending = if (kind == "replay") {
            last ?: return E2eCommandResultBody("unsupported", "no previous call control tap")
        } else {
            val mirror = controllableMirror(context)
                ?: return E2eCommandResultBody("unsupported", "no controllable call mirror is posted")
            mirror.extras.getParcelable(extraFor(kind), PendingIntent::class.java)
                ?: return E2eCommandResultBody("unsupported", "call mirror does not offer $kind")
        }
        return runCatching {
            pending.send()
            last = pending
            android.util.Log.i("TwinotifyE2e", "call_control_tap_sent:$kind")
            E2eCommandResultBody("ok", payload = JSONObject().put("kind", kind).put("status", "sent"))
        }.getOrElse { E2eCommandResultBody("unavailable", "call control intent failed") }
    }

    internal fun extraFor(kind: String): String = when (kind) {
        "answer" -> Notification.EXTRA_ANSWER_INTENT
        "decline" -> Notification.EXTRA_DECLINE_INTENT
        else -> Notification.EXTRA_HANG_UP_INTENT
    }

    internal fun resetForTest() {
        last = null
    }

    private fun controllableMirror(context: Context): Notification? =
        context.getSystemService(NotificationManager::class.java)
            ?.activeNotifications.orEmpty()
            .filter {
                it.packageName == context.packageName &&
                    it.id != CallControlFixture.FIXTURE_ID &&
                    it.notification.category == Notification.CATEGORY_CALL
            }
            .maxByOrNull { it.postTime }
            ?.notification
}
