package co.twinotify.core.e2e

import android.content.Context
import android.os.SystemClock
import androidx.core.content.edit
import co.twinotify.core.listener.CanonIdBuilder
import co.twinotify.core.listener.CaptureAdmission
import co.twinotify.core.listener.CaptureCoordinator
import co.twinotify.core.listener.PostCommand
import co.twinotify.core.listener.SourceNotificationSnapshot
import co.twinotify.core.service.RouteKind
import co.twinotify.core.service.RoutePhase
import co.twinotify.core.service.SyncService
import co.twinotify.core.service.SyncServiceStatus
import co.twinotify.core.storage.DeviceIdentity
import co.twinotify.core.storage.NotificationDb
import kotlinx.coroutines.delay
import org.json.JSONObject

/**
 * Debug-only route controls for the Bluetooth scenario.
 *
 * Every response is a closed key set of enums, counters, and durations. No
 * Bluetooth address, device name, SSID, association identifier, peer key,
 * envelope, or notification content is reachable from any of these commands,
 * and none of this file exists in a release build.
 */
internal object BluetoothRouteControl {
    /** Closed route vocabulary. The host may name nothing else. */
    private val ROUTES = setOf("LAN", "BLUETOOTH", "RELAY")

    private val PHASES = setOf("IDLE", "CONNECTING", "AUTHENTICATED", "RECONNECTING")

    /** Matches the LAN fault precedent: a bounded window, never a permanent flag. */
    private const val FAULT_WINDOW_MS = 120_000L

    private const val PREFS = "e2e-control"

    /**
     * A blocking control must give the broadcast queue back before it waits, so
     * the bound stays at the receiver's existing 10s ceiling rather than the
     * 15s the plan sketched: one wedged route must never hold this process's
     * broadcast queue longer than an already-proven command can.
     */
    const val MAX_AWAIT_MS = 10_000L

    /** The protocol envelope maximum. A fixture may target it and nothing above it. */
    const val MAX_FIXTURE_BYTES = 1_048_576

    /**
     * Conservative allowance for the inner event, payload framing, nonce, and
     * base64 expansion around the padding. The fixture reports the envelope size
     * it actually authored, so an undershoot is visible rather than assumed.
     */
    private const val FIXTURE_FRAMING_ALLOWANCE_BYTES = 4_096

    private const val FIXTURE_PACKAGE = "co.twinotify.e2e.fixture"

    internal fun routeIsValid(route: String): Boolean = route in ROUTES

    internal fun phaseIsValid(phase: String): Boolean = phase in PHASES

    internal fun faultKey(route: String): String = route.lowercase() + "_fault_until_ms"

    /**
     * Makes one route unavailable to this device's own route factory, or restores
     * it, then asks the coordinator to re-evaluate. This is the same mechanism
     * `SET_LAN_AVAILABLE` uses and writes the same preferences file, so the two
     * commands can never disagree about LAN.
     */
    fun fault(context: Context, route: String, enabled: Boolean): E2eCommandResultBody {
        if (!routeIsValid(route)) return E2eCommandResultBody("invalid", "route must be LAN, BLUETOOTH, or RELAY")
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit {
            if (enabled) putLong(faultKey(route), System.currentTimeMillis() + FAULT_WINDOW_MS)
            else remove(faultKey(route))
        }
        SyncService.notifyRoutePreferenceChanged()
        SyncServiceStatus.requestRouteRetry()
        return E2eCommandResultBody(
            "ok",
            payload = JSONObject().put("route", route).put("enabled", enabled),
        )
    }

    /** Blocks until this device's own route status reaches the named route and phase. */
    suspend fun awaitRoute(route: String, phase: String, timeoutMs: Long): E2eCommandResultBody {
        if (!routeIsValid(route)) return E2eCommandResultBody("invalid", "route must be LAN, BLUETOOTH, or RELAY")
        if (!phaseIsValid(phase)) return E2eCommandResultBody("invalid", "phase is outside the closed contract")
        if (timeoutMs !in 1..MAX_AWAIT_MS) return E2eCommandResultBody("invalid", "timeout_ms must be 1..$MAX_AWAIT_MS")
        val wantRoute = RouteKind.valueOf(route)
        val wantPhase = RoutePhase.valueOf(phase)
        val startedAt = SystemClock.elapsedRealtime()
        var status = SyncServiceStatus.routeStatus.value
        while ((status.route != wantRoute || status.phase != wantPhase) &&
            SystemClock.elapsedRealtime() - startedAt < timeoutMs
        ) {
            delay(25)
            status = SyncServiceStatus.routeStatus.value
        }
        val matched = status.route == wantRoute && status.phase == wantPhase
        return E2eCommandResultBody(
            "ok",
            payload = JSONObject()
                .put("route", status.route.name.lowercase())
                .put("phase", status.phase.name.lowercase())
                .put("status", if (matched) "matched" else "timeout")
                .put("elapsed_ms", elapsedSince(startedAt, timeoutMs)),
        )
    }

    /** Blocks until nothing on this device is still awaiting an authenticated peer receipt. */
    suspend fun awaitPeerReceipt(timeoutMs: Long): E2eCommandResultBody {
        if (timeoutMs !in 1..MAX_AWAIT_MS) return E2eCommandResultBody("invalid", "timeout_ms must be 1..$MAX_AWAIT_MS")
        val startedAt = SystemClock.elapsedRealtime()
        var awaiting = SyncServiceStatus.routeStatus.value.awaitingPeerCount
        while (awaiting > 0 && SystemClock.elapsedRealtime() - startedAt < timeoutMs) {
            delay(25)
            awaiting = SyncServiceStatus.routeStatus.value.awaitingPeerCount
        }
        return E2eCommandResultBody(
            "ok",
            payload = JSONObject()
                .put("status", if (awaiting == 0) "receipted" else "timeout")
                .put("awaiting_peer_count", awaiting.coerceIn(0, 2_000))
                .put("elapsed_ms", elapsedSince(startedAt, timeoutMs)),
        )
    }

    /**
     * Authors one synthetic outbound envelope through the production capture
     * boundary, sized as close to [targetBytes] as the framing allows. Nothing
     * about the fixture is user data: the body is a single repeated character.
     *
     * The reported `bytes` is the envelope this device actually persisted, read
     * back from the durable outbox, so an undershoot cannot be mistaken for a
     * maximum-size proof.
     */
    suspend fun enqueueFixture(context: Context, targetBytes: Int): E2eCommandResultBody {
        if (targetBytes !in 1..MAX_FIXTURE_BYTES) {
            return E2eCommandResultBody("invalid", "bytes must be 1..$MAX_FIXTURE_BYTES")
        }
        val startedAt = SystemClock.elapsedRealtime()
        val padding = paddingFor(targetBytes)
        val originDevice = DeviceIdentity.getOrCreate(context)
        val postedAt = System.currentTimeMillis()
        val id = (postedAt % 100_000).toInt()
        val tag = "bluetooth-fixture"
        val canonId = CanonIdBuilder.build(originDevice, FIXTURE_PACKAGE, id, tag)
        val snapshot = SourceNotificationSnapshot(
            sourceKey = "e2e|$FIXTURE_PACKAGE|$id|$tag",
            packageName = FIXTURE_PACKAGE,
            id = id,
            tag = tag,
            postTime = postedAt,
            flags = 0,
            category = null,
            visibility = 0,
            isGroupSummary = false,
            isOngoing = false,
            isClearable = true,
            appName = "E2E route fixture",
            title = "E2E route fixture",
            text = "E2E route fixture",
            subText = null,
            bigText = "x".repeat(padding),
            smallIcon = null,
            largeIcon = null,
        )
        val admission = CaptureCoordinator.get(context)
            .submitDurably(PostCommand(canonId, snapshot.sourceKey, snapshot))
        if (admission != CaptureAdmission.Accepted) {
            return E2eCommandResultBody("unavailable", "fixture_admission_closed")
        }
        val bytes = awaitPersistedEnvelopeBytes(context, canonId, startedAt)
            ?: return E2eCommandResultBody("unavailable", "fixture_not_persisted")
        if (bytes > targetBytes) return E2eCommandResultBody("unavailable", "fixture_over_budget")
        return E2eCommandResultBody(
            "ok",
            payload = JSONObject()
                .put("bytes", bytes)
                .put("status", "enqueued")
                .put("elapsed_ms", elapsedSince(startedAt, MAX_AWAIT_MS)),
        )
    }

    /**
     * Solves the base64 expansion backwards from the requested envelope size, then
     * subtracts a fixed framing allowance. Undershooting is safe; overshooting
     * would be rejected by the protocol encoder as an oversize envelope.
     */
    internal fun paddingFor(targetBytes: Int): Int =
        ((targetBytes - FIXTURE_FRAMING_ALLOWANCE_BYTES).toLong() * 3L / 4L).toInt().coerceIn(1, MAX_FIXTURE_BYTES)

    private suspend fun awaitPersistedEnvelopeBytes(context: Context, canonId: String, startedAt: Long): Long? {
        while (SystemClock.elapsedRealtime() - startedAt < MAX_AWAIT_MS) {
            val bytes = persistedEnvelopeBytes(context, canonId)
            if (bytes != null) return bytes
            delay(25)
        }
        return persistedEnvelopeBytes(context, canonId)
    }

    private fun persistedEnvelopeBytes(context: Context, canonId: String): Long? =
        NotificationDb.get(context).openHelper.readableDatabase.query(
            "SELECT byteSize FROM outbound_message WHERE canonId = ? ORDER BY createdAt DESC LIMIT 1",
            arrayOf(canonId),
        ).use { cursor -> if (cursor.moveToFirst()) cursor.getLong(0) else null }

    private fun elapsedSince(startedAt: Long, bound: Long): Long =
        (SystemClock.elapsedRealtime() - startedAt).coerceIn(0L, bound)
}
