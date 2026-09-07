package co.twinotify.core.service

import android.annotation.SuppressLint
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.net.toUri
import co.twinotify.core.R
import co.twinotify.core.listener.NotifPostBuilder
import co.twinotify.core.listener.NotifPostJson
import co.twinotify.core.storage.DeviceIdentity
import co.twinotify.core.storage.NotificationDb
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject

/** Local presentation preferences only: never changes peer state, protocol, or app filters. */
object RepeatProtection {
    private const val PREFS = "repeat_protection_v1"
    private const val CHANNEL = "repeat_protection"
    private const val NOTICE_ID = 1
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var wakeJob: kotlinx.coroutines.Job? = null
    private var wakeAt: Long? = null

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    // KTX edit(commit = true) discards commit's result; a failed durable write must stop hiding.
    @SuppressLint("UseKtx")
    private fun commitPreferences(context: Context, edit: android.content.SharedPreferences.Editor.() -> Unit) {
        check(prefs(context).edit().apply(edit).commit()) { "repeat protection preferences were not saved" }
    }

    private fun key(canonId: String): String = "notification:" + java.security.MessageDigest.getInstance("SHA-256")
        .digest(canonId.toByteArray()).joinToString("") { "%02x".format(it) }

    private fun read(context: Context, canonId: String): JSONObject =
        prefs(context).getString(key(canonId), null)?.let(::JSONObject) ?: JSONObject().put("canonId", canonId)

    private fun state(row: JSONObject) = RepeatProtectionState(
        revision = row.optString("revision"),
        recent = row.optJSONArray("recent")?.let { a -> List(a.length()) { a.getLong(it) } }.orEmpty(),
        lastAt = row.optLong("lastAt"),
        snoozeUntil = row.optLong("snoozeUntil"),
        continuous = row.optBoolean("continuous", true),
        blocked = row.optBoolean("blocked"),
        exempt = row.optBoolean("exempt"),
        hidden = row.optBoolean("hidden"),
        noticeSent = row.optBoolean("noticeSent"),
    )

    private fun save(context: Context, row: JSONObject, state: RepeatProtectionState) {
        row.put("revision", state.revision).put("recent", JSONArray(state.recent))
            .put("lastAt", state.lastAt).put("snoozeUntil", state.snoozeUntil)
            .put("continuous", state.continuous).put("blocked", state.blocked)
            .put("exempt", state.exempt).put("hidden", state.hidden).put("noticeSent", state.noticeSent)
        commitPreferences(context) { putString(key(row.getString("canonId")), row.toString()) }
    }

    private fun rows(context: Context): List<JSONObject> = prefs(context).all.entries
        .filter { it.key.startsWith("notification:") }
        .map { JSONObject(it.value as String) }

    @Synchronized
    fun enabled(context: Context): Boolean = prefs(context).getBoolean("enabled", true)

    @Synchronized
    fun blockedCount(context: Context): Int = rows(context).count { state(it).blocked }

    /** Hidden rows stay excluded from dismissal reconciliation until the platform post succeeds. */
    @Synchronized
    fun isHidden(context: Context, canonId: String): Boolean = state(read(context, canonId)).let { it.hidden || it.suppress }

    @Synchronized
    fun setEnabled(context: Context, enabled: Boolean) {
        commitPreferences(context) { putBoolean("enabled", enabled) }
        if (!enabled) {
            rows(context).forEach { row ->
                val old = state(row)
                save(context, row, RepeatProtectionState(revision = old.revision, hidden = old.hidden))
                dismissNotice(context, row.getString("canonId"))
            }
            recover(context)
        }
    }

    @Synchronized
    fun undo(context: Context, canonId: String) {
        val row = read(context, canonId)
        val old = state(row)
        // Explicit re-enable exempts this identity so the next three VPN ticks cannot undo it.
        save(context, row, RepeatProtectionState(revision = old.revision, hidden = old.hidden, exempt = true))
        dismissNotice(context, canonId)
        recover(context)
    }

    @Synchronized
    fun restoreBlocked(context: Context) {
        rows(context).filter { state(it).blocked }.forEach { undo(context, it.getString("canonId")) }
    }

    /** All ordinary mirror posts, including v1 and timer restoration, pass this boundary. */
    @Synchronized
    fun present(
        context: Context,
        post: NotifPostJson,
        revision: String,
        localTag: String,
        localId: Int,
        legacy: Boolean = false,
        nowMs: Long = System.currentTimeMillis(),
        notify: () -> Unit,
    ) {
        if (!prefs(context).contains(key(post.canon_id))) pruneCounters(context, nowMs)
        val row = read(context, post.canon_id)
        val old = state(row)
        // A timer restoration may race a newer materialization; never repost an older revision.
        if (RepeatProtectionPolicy.isOlderRevision(old.revision, revision)) return
        var next = if (enabled(context)) RepeatProtectionPolicy.observe(old, revision, nowMs)
            else RepeatProtectionState(revision = revision, hidden = old.hidden)
        row.put("appName", post.app_name ?: post.package_name).put("tag", localTag).put("id", localId)
        row.put("legacy", legacy)
        if (legacy && next.suppress) row.put("payload", NotifPostBuilder.toPayloadJson(post))
        // Durable hidden intent precedes cancel. Reconciliation and listener callbacks see it.
        if (next.suppress) {
            next = next.copy(hidden = true)
            save(context, row, next)
            hide(context, post.canon_id, localTag, localId)
            if (next.blocked && !next.noticeSent) showNotice(context, row, next)
        } else {
            notify()
            row.remove("payload")
            save(context, row, next.copy(hidden = false))
        }
        schedule(context)
    }

    private fun hide(context: Context, canonId: String, tag: String, id: Int) {
        val row = read(context, canonId)
        row.put("pendingCancel", true)
        save(context, row, state(row))
        NotificationManagerCompat.from(context).cancel(tag, id)
    }

    /** Only our programmatic cancellation is suppressed; a later real swipe still syncs. */
    @Synchronized
    fun consumeRemoval(context: Context, canonId: String, reason: Int): Boolean {
        if (reason != android.service.notification.NotificationListenerService.REASON_APP_CANCEL &&
            reason != android.service.notification.NotificationListenerService.REASON_APP_CANCEL_ALL
        ) return false
        val row = read(context, canonId)
        if (!row.optBoolean("pendingCancel")) return false
        row.put("pendingCancel", false)
        save(context, row, state(row))
        return true
    }

    @SuppressLint("MissingPermission")
    private fun showNotice(context: Context, row: JSONObject, next: RepeatProtectionState) {
        if (!effectivePostAvailability(context)) return
        val canonId = row.getString("canonId")
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "Repeat protection", NotificationManager.IMPORTANCE_DEFAULT))
        val undo = PendingIntent.getBroadcast(
            context, 0,
            Intent(context, RepeatProtectionReceiver::class.java).apply {
                action = "co.twinotify.UNDO_REPEAT_BLOCK"
                data = "twinotify://repeat-protection/${key(canonId)}".toUri()
                putExtra("canonId", canonId)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val text = context.getString(R.string.repeat_protection_notice_body, row.optString("appName", "Source app"))
        manager.notify(key(canonId), NOTICE_ID, NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_twinotify)
            .setContentTitle(context.getString(R.string.repeat_protection_notice_title))
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setOnlyAlertOnce(true)
            .setAutoCancel(true)
            .addAction(0, context.getString(R.string.repeat_protection_undo), undo)
            .build())
        save(context, read(context, canonId), next.copy(noticeSent = true))
    }

    private fun dismissNotice(context: Context, canonId: String) {
        NotificationManagerCompat.from(context).cancel(key(canonId), NOTICE_ID)
    }

    /** A real peer cancellation ends a snooze, but a manual-only block survives ID reuse. */
    @Synchronized
    fun cancelled(context: Context, canonId: String) {
        val row = read(context, canonId)
        val old = state(row)
        row.remove("payload")
        if (old.blocked || old.exempt) save(context, row, old.copy(hidden = false, snoozeUntil = 0))
        else commitPreferences(context) { remove(key(canonId)) }
        schedule(context)
    }

    @Synchronized
    fun clear(context: Context) {
        rows(context).forEach { dismissNotice(context, it.getString("canonId")) }
        commitPreferences(context) { clear() }
        schedule(context)
    }

    fun resume(context: Context) { scope.launch { recoverSafely(context.applicationContext) } }

    internal fun recoverSafely(context: Context) {
        try {
            recover(context)
        } catch (cancellation: kotlinx.coroutines.CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            android.util.Log.w("RepeatProtection", "repeat_protection_recovery_failed")
            // A transient platform/storage failure must not crash the listener or strand a snooze.
            synchronized(this) {
                wakeJob?.cancel()
                wakeAt = null
                wakeJob = scope.launch {
                    delay(5_000)
                    recoverSafely(context.applicationContext)
                }
            }
        }
    }

    /** Alarm + process timer: snoozes expire even when no more source updates arrive. */
    @Synchronized
    internal fun recover(context: Context, now: Long = System.currentTimeMillis()) {
        val dao = NotificationDb.get(context).reliableDeliveryDao()
        for (row in rows(context)) {
            val canonId = row.getString("canonId")
            var next = RepeatProtectionPolicy.expire(state(row), now)
            if (next.lastAt > now && !next.blocked) next = next.copy(snoozeUntil = 0, recent = emptyList())
            save(context, row, next)
            if (next.suppress && row.has("tag") && row.has("id")) {
                hide(context, canonId, row.getString("tag"), row.getInt("id"))
            }
            if (next.blocked) {
                if (!next.noticeSent) showNotice(context, row, next)
            } else if (!next.suppress && next.hidden && effectivePostAvailability(context)) {
                // Read current desired state; never resurrect a peer-cancelled or superseded payload.
                val canonical = runBlocking(Dispatchers.IO) { dao.canonical(canonId) }
                if (row.optBoolean("legacy")) {
                    row.optString("payload").takeIf { it.isNotEmpty() }?.let { payload ->
                        runBlocking { MirrorPoster.post(context, NotifPostJson.fromPayloadJson(payload)) }
                    }
                } else if (canonical?.state == "ACTIVE") {
                    val device = runBlocking(Dispatchers.IO) { DeviceIdentity.getOrCreate(context) }
                    DefaultAndroidNotificationPort(context, device, dao).postMirrorOutcome(canonical)
                } else {
                    cancelled(context, canonId)
                }
            }
        }
        // Retain blocks/exemptions; discard old transient counters without storing content history.
        commitPreferences(context) {
            rows(context).filter { state(it).let { s -> !s.hidden && !s.suppress && !s.exempt && now - s.lastAt > RepeatProtectionPolicy.WINDOW_MS } }
                .forEach { remove(key(it.getString("canonId"))) }
        }
        schedule(context)
    }

    private fun pruneCounters(context: Context, now: Long) {
        val transient = rows(context).filter { state(it).let { s -> !s.hidden && !s.suppress && !s.exempt } }
            .sortedBy { state(it).lastAt }
        val remove = transient.filter { now - state(it).lastAt > RepeatProtectionPolicy.WINDOW_MS }
            .toSet() + transient.take((transient.size - 511).coerceAtLeast(0))
        if (remove.isNotEmpty()) {
            commitPreferences(context) {
                remove.forEach { remove(key(it.getString("canonId"))) }
            }
        }
    }

    private fun schedule(context: Context) {
        val now = System.currentTimeMillis()
        val due = rows(context).mapNotNull { row ->
            val s = state(row)
            when {
                s.snoozeUntil > 0 -> s.snoozeUntil
                s.hidden && !s.blocked && effectivePostAvailability(context) -> now + 5_000L
                else -> null
            }
        }.minOrNull()
        if (due == wakeAt) return
        wakeJob?.cancel()
        wakeAt = due
        val pending = PendingIntent.getBroadcast(context, 0, Intent(context, RepeatProtectionReceiver::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val alarm = context.getSystemService(AlarmManager::class.java)
        if (due == null) { alarm.cancel(pending); return }
        alarm.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, due.coerceAtLeast(now + 1), pending)
        wakeJob = scope.launch {
            delay((due - now).coerceAtLeast(1))
            synchronized(this@RepeatProtection) { wakeAt = null }
            recoverSafely(context.applicationContext)
        }
    }
}

class RepeatProtectionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                if (intent?.action == "co.twinotify.UNDO_REPEAT_BLOCK") {
                    intent.getStringExtra("canonId")?.let { RepeatProtection.undo(context, it) }
                } else {
                    RepeatProtection.recoverSafely(context)
                }
            } catch (cancellation: kotlinx.coroutines.CancellationException) {
                throw cancellation
            } catch (_: Exception) {
                android.util.Log.w("RepeatProtection", "repeat_protection_action_failed")
            } finally { pending.finish() }
        }
    }
}
