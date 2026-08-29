package co.twinotify.core.e2e

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.net.toUri
import org.json.JSONObject

internal object NotificationActionFixture {
    private const val FIXTURE_PACKAGE = "co.twinotify.fixture"
    private const val COMMAND_RECEIVER = "$FIXTURE_PACKAGE.FixtureCommandReceiver"
    private const val STATE_URI = "content://$FIXTURE_PACKAGE.e2e/state"
    private const val EXTRA_FIXTURE = "fixture"
    private const val EXTRA_OPERATION = "operation"

    private val fixtures = setOf("reply", "mark_read", "auto_cancel", "persistent")
    private val operations = setOf("post", "update", "cancel", "reset_counters")
    private val terminalStatuses = setOf(
        "none",
        "posted",
        "updated",
        "cancelled",
        "counters_reset",
        "reply_dispatched",
        "mark_read_dispatched",
    )
    private val stateKeys = setOf(
        "reply_dispatch_count",
        "mark_read_dispatch_count",
        "last_fixture_generation",
        "last_terminal_status",
    )

    fun execute(context: Context, fixture: String, operation: String): E2eControlOutcome {
        val intent = commandIntent(fixture, operation)
        val available = try {
            context.packageManager.getReceiverInfo(intent.component!!, PackageManager.ComponentInfoFlags.of(0))
            true
        } catch (_: PackageManager.NameNotFoundException) {
            false
        }
        if (!available) return E2eControlOutcome("unavailable", "fixture_not_installed")
        context.sendBroadcast(intent)
        return E2eControlOutcome("ok", "requested")
    }

    fun snapshot(context: Context): JSONObject = runCatching {
        context.contentResolver.query(STATE_URI.toUri(), null, null, null, null)?.use { cursor ->
            require(cursor.moveToFirst()) { "fixture state unavailable" }
            sanitizeState(JSONObject(cursor.getString(cursor.getColumnIndexOrThrow("state_json"))))
        } ?: error("fixture state unavailable")
    }.getOrElse { unavailableState() }

    internal fun commandIntentForTest(fixture: String, operation: String): Intent =
        commandIntent(fixture, operation)

    internal fun sanitizeStateForTest(raw: JSONObject): JSONObject = sanitizeState(raw)

    private fun commandIntent(fixture: String, operation: String): Intent {
        require(fixture in fixtures) { "fixture must be a closed enum" }
        require(operation in operations) { "operation must be a closed enum" }
        return Intent().setComponent(ComponentName(FIXTURE_PACKAGE, COMMAND_RECEIVER))
            .putExtra(EXTRA_FIXTURE, fixture)
            .putExtra(EXTRA_OPERATION, operation)
    }

    private fun sanitizeState(raw: JSONObject): JSONObject {
        require(raw.keys().asSequence().toSet() == stateKeys) { "unexpected fixture state key" }
        val replyCount = raw.getInt("reply_dispatch_count")
        val markReadCount = raw.getInt("mark_read_dispatch_count")
        val generation = raw.getInt("last_fixture_generation")
        val status = raw.getString("last_terminal_status")
        require(replyCount in 0..1_000_000_000) { "reply count out of bounds" }
        require(markReadCount in 0..1_000_000_000) { "mark-read count out of bounds" }
        require(generation in 0..1_000_000_000) { "fixture generation out of bounds" }
        require(status in terminalStatuses) { "fixture terminal status is not allowlisted" }
        return JSONObject()
            .put("available", true)
            .put("reply_dispatch_count", replyCount)
            .put("mark_read_dispatch_count", markReadCount)
            .put("last_fixture_generation", generation)
            .put("last_terminal_status", status)
    }

    private fun unavailableState(): JSONObject = JSONObject()
        .put("available", false)
        .put("reply_dispatch_count", 0)
        .put("mark_read_dispatch_count", 0)
        .put("last_fixture_generation", 0)
        .put("last_terminal_status", "none")
}
