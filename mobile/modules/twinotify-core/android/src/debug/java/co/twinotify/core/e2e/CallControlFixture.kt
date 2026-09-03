package co.twinotify.core.e2e

import android.app.Notification
import android.app.PendingIntent
import android.app.Person
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.net.toUri
import android.os.SystemClock
import co.twinotify.core.call.CallControlKind
import co.twinotify.core.service.NotifChannelSetup
import co.twinotify.core.service.SyncService
import kotlinx.coroutines.delay
import org.json.JSONObject

/**
 * Debug-only stand-in for a compatible dialer on the source phone. It posts a real local
 * `Notification.CallStyle` whose typed answer/decline/hang-up intents target an unexported
 * receiver that records only the control kind and a timestamp. No number, name, or audio
 * exists anywhere in this fixture.
 */
internal object CallControlFixture {
    /** Foreground-slot id for the fixture CallStyle; never collides with mirror ids or the status id. */
    const val FIXTURE_ID = 9_311
    private const val SCHEME = "twinotify-e2e"
    private const val HOST = "call-control"
    private val STATES = setOf("ringing", "active", "idle")

    private val lock = Any()
    private val journal = mutableListOf<Pair<CallControlKind, Long>>()

    internal fun stateIsValid(state: String): Boolean = state in STATES

    /** Drives the synthetic call and its CallStyle capabilities together; returns bounded JSON. */
    suspend fun source(context: Context, state: String): E2eCommandResultBody {
        if (!stateIsValid(state)) return E2eCommandResultBody("invalid", "state must be ringing, active, or idle")
        val host = SyncService.callMirrorForegroundHost()
            ?: return E2eCommandResultBody("unsupported", "sync service is not active")
        when (state) {
            "ringing" -> {
                synchronized(lock) { journal.clear() }
                if (!host.postCallMirror(FIXTURE_ID, incoming(context))) {
                    return E2eCommandResultBody("unavailable", "fixture call notification was not posted")
                }
            }
            "active" -> {
                if (!host.postCallMirror(FIXTURE_ID, ongoing(context))) {
                    return E2eCommandResultBody("unavailable", "fixture call notification was not updated")
                }
            }
        }
        val event = SyncService.injectDebugCallState(state)
            ?: return E2eCommandResultBody("unsupported", "debug call capture is not active")
        if (state == "idle" && !host.cancelCallMirror(FIXTURE_ID)) {
            return E2eCommandResultBody("unavailable", "fixture call notification was not removed")
        }
        return E2eCommandResultBody(
            "ok",
            payload = JSONObject().put("state", event.state).put("sequence", event.sequence),
        )
    }

    suspend fun await(kind: String, timeoutMs: Long): E2eCommandResultBody {
        val wanted = kindOrNull(kind) ?: return E2eCommandResultBody("invalid", "kind must be answer, decline, or hang_up")
        if (timeoutMs !in 1..10_000) return E2eCommandResultBody("invalid", "timeout_ms must be 1..10000")
        val startedAt = SystemClock.elapsedRealtime()
        var count = count(wanted)
        while (count == 0 && SystemClock.elapsedRealtime() - startedAt < timeoutMs) {
            delay(25)
            count = count(wanted)
        }
        val elapsed = (SystemClock.elapsedRealtime() - startedAt).coerceAtMost(timeoutMs)
        return E2eCommandResultBody(
            "ok",
            payload = JSONObject()
                .put("kind", wanted.wire)
                .put("count", count)
                .put("status", if (count > 0) "dispatched" else "timeout")
                .put("elapsed_ms", elapsed),
        )
    }

    /** Closed-world dispatch counts keyed by wire kind. */
    fun dispatches(): JSONObject = synchronized(lock) {
        val result = JSONObject()
        CallControlKind.entries.forEach { kind ->
            val n = journal.count { it.first == kind }
            if (n > 0) result.put(kind.wire, n)
        }
        result
    }

    internal fun record(kind: CallControlKind) = synchronized(lock) {
        journal += kind to SystemClock.elapsedRealtime()
    }

    internal fun resetForTest() = synchronized(lock) { journal.clear() }

    private fun count(kind: CallControlKind): Int = synchronized(lock) { journal.count { it.first == kind } }

    internal fun kindOrNull(raw: String): CallControlKind? = CallControlKind.entries.firstOrNull { it.wire == raw }

    internal fun incoming(context: Context): Notification = base(context)
        .setStyle(
            Notification.CallStyle.forIncomingCall(
                person(),
                pendingIntent(context, CallControlKind.DECLINE),
                pendingIntent(context, CallControlKind.ANSWER),
            ),
        )
        .build()

    internal fun ongoing(context: Context): Notification = base(context)
        .setStyle(Notification.CallStyle.forOngoingCall(person(), pendingIntent(context, CallControlKind.HANG_UP)))
        .build()

    private fun base(context: Context): Notification.Builder {
        NotifChannelSetup.ensureChannels(context)
        return Notification.Builder(context, NotifChannelSetup.CHANNEL_CALLS)
            .setSmallIcon(android.R.drawable.stat_sys_phone_call)
            .setCategory(Notification.CATEGORY_CALL)
            .setOngoing(true)
    }

    private fun person(): Person = Person.Builder().setName("E2E synthetic call").setImportant(true).build()

    internal fun dataUri(kind: CallControlKind): String = "$SCHEME://$HOST/${kind.wire}"

    internal fun kindFromUri(uri: Uri?): CallControlKind? {
        if (uri == null || uri.scheme != SCHEME || uri.host != HOST) return null
        val segments = uri.pathSegments
        if (segments.size != 1) return null
        return kindOrNull(segments[0])
    }

    private fun pendingIntent(context: Context, kind: CallControlKind): PendingIntent {
        val intent = Intent(context, CallControlFixtureReceiver::class.java).apply {
            data = dataUri(kind).toUri()
            addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
        }
        return PendingIntent.getBroadcast(
            context,
            0x5E20 + kind.ordinal,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }
}

/** Bounded result body used by the debug fixture before it is bound to a request id. */
internal data class E2eCommandResultBody(
    val code: String,
    val detail: String? = null,
    val payload: JSONObject? = null,
) {
    fun toResult(requestId: String) = E2eCommandResult(requestId, code, detail, payload)
}

/** Unexported. Records only which typed control the platform dispatched, never any call data. */
class CallControlFixtureReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val kind = CallControlFixture.kindFromUri(intent.data) ?: return
        CallControlFixture.record(kind)
        android.util.Log.i("TwinotifyE2e", "call_control_fixture_dispatched:${kind.wire}")
    }
}
