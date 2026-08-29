package co.twinotify.core.e2e

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Process
import android.system.Os
import android.system.OsConstants
import co.twinotify.core.OfflinePairingApiController
import co.twinotify.core.OfflinePairingApiException
import co.twinotify.core.OfflinePairingApiPhase
import co.twinotify.core.OfflinePairingPublicStatus
import co.twinotify.core.defaultOfflinePairingRuntimeFactory
import co.twinotify.core.crypto.CryptoStore
import co.twinotify.core.pairing.PairPayload
import co.twinotify.core.pairing.PairNotifyClient
import co.twinotify.core.pairing.PairProtocol
import co.twinotify.core.service.ServiceConfigStore
import co.twinotify.core.service.SyncService
import co.twinotify.core.service.SyncServiceStatus
import co.twinotify.core.storage.DeviceIdentity
import co.twinotify.core.storage.NotificationDb
import co.twinotify.core.storage.PeerRecord
import co.twinotify.core.storage.PeerStore
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.nio.file.Files
import java.nio.file.LinkOption
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import androidx.core.content.edit
import androidx.core.app.NotificationManagerCompat
import co.twinotify.core.auth.JwtMinter
import co.twinotify.core.pairing.ProductionLocalUnpairEntryPoint
import co.twinotify.core.pairing.awaitLocalUnpairResultAndRecord
import org.json.JSONObject

private const val MAX_DETAIL_LENGTH = 256

data class E2eCommand(
    val requestId: String,
    val name: String,
    val token: String? = null,
    val params: Map<String, String> = emptyMap(),
) {
    fun param(name: String): String? = params[name]

    companion object {
        fun fromIntent(intent: Intent): E2eCommand = E2eCommand(
            requestId = intent.getStringExtra(E2eControlReceiver.EXTRA_REQUEST_ID).orEmpty(),
            name = intent.getStringExtra(E2eControlReceiver.EXTRA_COMMAND).orEmpty(),
            token = intent.getStringExtra(E2eControlReceiver.EXTRA_TOKEN),
            params = intent.extras?.keySet()?.filterNot {
                it == E2eControlReceiver.EXTRA_REQUEST_ID ||
                    it == E2eControlReceiver.EXTRA_COMMAND ||
                    it == E2eControlReceiver.EXTRA_TOKEN
            }?.associateWith { intent.getStringExtra(it).orEmpty() }.orEmpty(),
        )
    }
}

data class E2eCommandResult(
    val requestId: String,
    val code: String,
    val detail: String? = null,
    val payload: JSONObject? = null,
    val secretPayload: ByteArray? = null,
) {
    companion object { const val MAX_JSON_BYTES = 65_536 }

    fun toJson(): JSONObject = JSONObject().apply {
        put("request_id", requestId)
        put("code", code)
        detail?.let { put("detail", it.take(MAX_DETAIL_LENGTH)) }
        payload?.let { put("payload", it) }
    }.also { require(it.toString().encodeToByteArray().size <= MAX_JSON_BYTES) { "bounded E2E result exceeded" } }
}

internal data class E2eControlOutcome(val code: String, val outcome: String)

internal interface E2eProductionControls {
    suspend fun dismissNewestMirror(context: Context): E2eControlOutcome
    suspend fun emitSnapshot(context: Context): E2eControlOutcome
    suspend fun forceRepairSnapshot(context: Context): E2eControlOutcome
    suspend fun localUnpair(context: Context): E2eControlOutcome
}

internal fun cancelMirrorAsUser(
    persistedTag: String,
    persistedId: Int,
    cancel: (String, Int) -> Unit,
) {
    require(persistedTag.isNotBlank() && persistedId > 0) { "invalid persisted mirror identity" }
    // Deliberately no PendingPeerCancel mutation: Android's real listener callback is the oracle.
    cancel(persistedTag, persistedId)
}

private object DefaultE2eProductionControls : E2eProductionControls {
    override suspend fun dismissNewestMirror(context: Context): E2eControlOutcome {
        val database = NotificationDb.get(context).openHelper.readableDatabase
        val identity = database.query(
            "SELECT mirrorLocalTag, mirrorLocalId FROM canonical_notification_state " +
                "WHERE state='ACTIVE' AND mirrorLocalTag IS NOT NULL AND mirrorLocalId IS NOT NULL " +
                "ORDER BY updatedAt DESC LIMIT 1",
        ).use { cursor ->
            if (!cursor.moveToFirst()) null else cursor.getString(0) to cursor.getInt(1)
        } ?: return E2eControlOutcome("not_found", "no_active_mirror")
        // Intentionally bypass MirrorDismisser: a local-user stimulus must not plant a peer-cancel
        // tombstone before Android reports the real notification-listener removal callback.
        cancelMirrorAsUser(identity.first, identity.second, NotificationManagerCompat.from(context)::cancel)
        return E2eControlOutcome("ok", "requested")
    }

    override suspend fun emitSnapshot(context: Context): E2eControlOutcome =
        if (SyncService.emitProductionSnapshotForE2e()) {
            E2eControlOutcome("ok", "emitted")
        } else {
            E2eControlOutcome("unavailable", "service_inactive")
        }

    override suspend fun forceRepairSnapshot(context: Context): E2eControlOutcome =
        if (SyncService.forceProductionRepairSnapshotForE2e()) {
            E2eControlOutcome("ok", "repair_started")
        } else {
            E2eControlOutcome("unavailable", "repair_unavailable")
        }

    override suspend fun localUnpair(context: Context): E2eControlOutcome {
        val result = awaitLocalUnpairResultAndRecord(
            ProductionLocalUnpairEntryPoint.start(
                context = context,
                quiesceOfflinePairing = { E2eOfflinePairingControl.quiesceAndAwait(context) },
            ),
        )
        return E2eControlOutcome("ok", result.custody.statusCode)
    }

}

/** Debug-only command bridge. Every command is authenticated by the install-scoped token. */
class E2eControlReceiver internal constructor(
    private val controls: E2eProductionControls = DefaultE2eProductionControls,
) : BroadcastReceiver() {
    companion object {
        const val ACTION_CONTROL = "co.twinotify.e2e.CONTROL"
        const val EXTRA_REQUEST_ID = "request_id"
        const val EXTRA_COMMAND = "command"
        const val EXTRA_TOKEN = "token"
        private const val RESULT_DIR = "e2e-results"
        private const val MAX_REQUEST_ID_LENGTH = 128
        private const val MAX_SECRET_BYTES = 4_096
        private val SAFE_HANDLE = Regex("[A-Za-z0-9._-]{1,128}")
        private val ALLOWED_COMMANDS = setOf(
            "PAIR_INIT", "PAIR_JOIN", "AWAIT_PEER_HELLO", "SIGN_CONFIRMATION",
            "SEND_CONFIRMATION_SIG", "AWAIT_PAIR_SIG", "PAIR_CONFIRM", "PAIR_COMPLETE", "START_SYNC", "STOP_SYNC",
            "SET_NETWORK_EXPECTED", "RECONCILE", "CLEAR_ACTIVITY", "STATUS", "CALL_CAPTURE_ENABLE", "CALL_STATE",
            "SET_LAN_AVAILABLE",
            "NOTIFICATION_FIXTURE",
            "NOTIFICATION_MIRROR", "NOTIFICATION_ORIGIN",
            "DISMISS_NEWEST_MIRROR", "EMIT_SNAPSHOT", "FORCE_REPAIR_SNAPSHOT", "LOCAL_UNPAIR",
            "OFFLINE_PAIR_START", "OFFLINE_PAIR_JOIN", "OFFLINE_PAIR_CONFIRM", "OFFLINE_PAIR_CANCEL", "OFFLINE_PAIR_QUERY",
        )

        private fun safeRequestId(requestId: String): String = requestId
            .take(MAX_REQUEST_ID_LENGTH)
            .replace(Regex("[^A-Za-z0-9._-]"), "_")
            .ifBlank { "missing-request" }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_CONTROL) return
        val pending = goAsync()
        val appContext = context.applicationContext
        scope.launch {
            var requestId = "missing-request"
            try {
                val parsed = E2eCommand.fromIntent(intent)
                requestId = safeRequestId(parsed.requestId)
                val authId = parsed.param("auth_input_id")
                val auth = runCatching {
                    requireNotNull(authId)
                    consumePrivateInput(appContext, parsed.requestId, authId, "e2e-auth")
                }.getOrNull()
                val authToken = auth?.decodeToString()
                val authenticated = parsed.copy(
                    token = authToken?.takeIf { E2eRequestHandle.matches(it, parsed.name, parsed.requestId, System.currentTimeMillis()) },
                    params = parsed.params - "auth_input_id",
                )
                auth?.fill(0)
                var result = executeForTest(appContext, authenticated)
                result.secretPayload?.let { secret ->
                    result = try {
                        writeSecretResult(appContext, result.requestId, secret)
                        result.copy(secretPayload = null)
                    } catch (_: Throwable) {
                        result.copy(code = "error", detail = "private_result_unavailable", payload = null, secretPayload = null)
                    } finally {
                        secret.fill(0)
                    }
                }
                writeResult(appContext, result)
            } catch (_: Throwable) {
                runCatching { writeResult(appContext, E2eCommandResult(requestId, "error", "operation_failed")) }
            } finally {
                pending.finish()
            }
        }
    }

    /** Synchronous seam used by instrumentation tests; production commands still use real APIs. */
    fun executeForTest(context: Context, command: E2eCommand): E2eCommandResult {
        val requestId = safeRequestId(command.requestId)
        if (!E2eSessionToken.matches(context, command.token)) {
            return E2eCommandResult(requestId, "unauthorized")
        }
        if (command.name !in ALLOWED_COMMANDS) {
            return E2eCommandResult(requestId, "forbidden", "command is not allowlisted")
        }
        validateOfflineParams(command)?.let { return E2eCommandResult(requestId, "invalid", it) }
        return try {
            runBlocking(Dispatchers.IO) { executeAuthorized(context.applicationContext, command, requestId) }
        } catch (error: Throwable) {
            val detail = (error as? OfflinePairingApiException)?.error?.code ?: "operation_failed"
            E2eCommandResult(requestId, "error", detail)
        }
    }

    private suspend fun executeAuthorized(
        context: Context,
        command: E2eCommand,
        requestId: String,
    ): E2eCommandResult {
        return when (command.name) {
        "PAIR_INIT" -> {
            val relayUrl = command.param("relay_url") ?: return E2eCommandResult(requestId, "invalid", "relay_url required")
            val displayName = command.param("display_name").orEmpty()
            val (box, sign) = CryptoStore.loadOrGenerate(context)
            val deviceId = DeviceIdentity.getOrCreate(context)
            val token = PairPayload.newToken()
            PairProtocol.initiate(relayUrl, token, deviceId, box.publicKey, sign.publicKey, displayName)
            E2eCommandResult(
                requestId,
                "ok",
                payload = JSONObject(PairPayload(relayUrl, deviceId, box.publicKey, sign.publicKey, token).toJson()),
            )
        }
        "PAIR_JOIN" -> {
            val payload = PairPayload.fromJson(command.param("pair_payload") ?: return E2eCommandResult(requestId, "invalid", "pair_payload required"))
            val (box, sign) = CryptoStore.loadOrGenerate(context)
            PairProtocol.sendPeerHello(
                payload.relayUrl,
                payload.pairToken,
                DeviceIdentity.getOrCreate(context),
                box.publicKey,
                sign.publicKey,
                command.param("display_name").orEmpty(),
            )
            PeerStore.save(
                context,
                PeerRecord(
                    deviceId = payload.deviceId,
                    encPubkey = payload.encPubkey,
                    signPubkey = payload.signPubkey,
                ),
            )
            E2eCommandResult(requestId, "ok")
        }
        "AWAIT_PEER_HELLO" -> {
            val relayUrl = command.param("relay_url") ?: return E2eCommandResult(requestId, "invalid", "relay_url required")
            val pairToken = command.param("pair_token") ?: return E2eCommandResult(requestId, "invalid", "pair_token required")
            val deviceId = DeviceIdentity.getOrCreate(context)
            val signSecret = CryptoStore.loadOrGenerate(context).second.secretKey
            val frame = PairNotifyClient.awaitAuthenticatedFrame(
                relayUrl, pairToken, role = "A", expectedType = "peer.hello",
                deviceId = deviceId, signSecretKey = signSecret,
                timeoutMs = command.timeoutMs(),
            )
            val hello = JSONObject(frame)
            PeerStore.save(
                context,
                PeerRecord(
                    deviceId = hello.getString("device_id"),
                    encPubkey = java.util.Base64.getDecoder().decode(hello.getString("enc_pubkey")),
                    signPubkey = java.util.Base64.getDecoder().decode(hello.getString("sign_pubkey")),
                    displayName = hello.optString("display_name").takeIf { it.isNotBlank() },
                ),
            )
            E2eCommandResult(requestId, "ok", payload = JSONObject(frame))
        }
        "SIGN_CONFIRMATION" -> {
            val pairToken = command.param("pair_token") ?: return E2eCommandResult(requestId, "invalid", "pair_token required")
            val bEnc = command.param("b_enc_pubkey") ?: return E2eCommandResult(requestId, "invalid", "b_enc_pubkey required")
            val bSign = command.param("b_sign_pubkey") ?: return E2eCommandResult(requestId, "invalid", "b_sign_pubkey required")
            val (box, sign) = CryptoStore.loadOrGenerate(context)
            val signature = PairProtocol.deviceASignConfirmation(
                pairToken,
                box.publicKey,
                sign.publicKey,
                java.util.Base64.getDecoder().decode(bEnc),
                java.util.Base64.getDecoder().decode(bSign),
                sign.secretKey,
            )
            E2eCommandResult(requestId, "ok", payload = JSONObject().put(
                "confirmation_sig", java.util.Base64.getEncoder().encodeToString(signature),
            ))
        }
        "SEND_CONFIRMATION_SIG" -> {
            val relayUrl = command.param("relay_url") ?: return E2eCommandResult(requestId, "invalid", "relay_url required")
            val pairToken = command.param("pair_token") ?: return E2eCommandResult(requestId, "invalid", "pair_token required")
            val signature = command.param("confirmation_sig") ?: return E2eCommandResult(requestId, "invalid", "confirmation_sig required")
            PairProtocol.sendConfirmationSig(
                relayUrl,
                pairToken,
                java.util.Base64.getDecoder().decode(signature),
            )
            E2eCommandResult(requestId, "ok")
        }
        "AWAIT_PAIR_SIG" -> {
            val relayUrl = command.param("relay_url") ?: return E2eCommandResult(requestId, "invalid", "relay_url required")
            val pairToken = command.param("pair_token") ?: return E2eCommandResult(requestId, "invalid", "pair_token required")
            val deviceId = DeviceIdentity.getOrCreate(context)
            val signSecret = CryptoStore.loadOrGenerate(context).second.secretKey
            val frame = PairNotifyClient.awaitAuthenticatedFrame(
                relayUrl, pairToken, role = "B", expectedType = "pair.sig",
                deviceId = deviceId, signSecretKey = signSecret,
                timeoutMs = command.timeoutMs(),
            )
            val signature = java.util.Base64.getDecoder().decode(JSONObject(frame).getString("confirmation_sig"))
            E2eCommandResult(requestId, "ok", payload = JSONObject().put(
                "confirmation_sig", java.util.Base64.getEncoder().encodeToString(signature),
            ))
        }
        "PAIR_CONFIRM" -> {
            PairProtocol.sendConfirmationSig(
                command.param("relay_url") ?: return E2eCommandResult(requestId, "invalid", "relay_url required"),
                command.param("pair_token") ?: return E2eCommandResult(requestId, "invalid", "pair_token required"),
                java.util.Base64.getDecoder().decode(command.param("confirmation_sig") ?: ""),
            )
            E2eCommandResult(requestId, "ok")
        }
        "PAIR_COMPLETE" -> {
            val relayUrl = command.param("relay_url") ?: return E2eCommandResult(requestId, "invalid", "relay_url required")
            val token = command.param("pair_token") ?: return E2eCommandResult(requestId, "invalid", "pair_token required")
            val peer = PeerStore.load(context) ?: return E2eCommandResult(requestId, "invalid", "peer identity required")
            val (box, sign) = CryptoStore.loadOrGenerate(context)
            PairProtocol.deviceBCompletePair(
                relayUrl,
                token,
                DeviceIdentity.getOrCreate(context),
                peer.encPubkey,
                peer.signPubkey,
                box.publicKey,
                sign.publicKey,
                sign.secretKey,
                java.util.Base64.getDecoder().decode(command.param("confirmation_sig") ?: ""),
            )
            E2eCommandResult(requestId, "ok")
        }
        "START_SYNC" -> {
            val relayUrl = command.param("relay_url") ?: return E2eCommandResult(requestId, "invalid", "relay_url required")
            ServiceConfigStore.setRelayUrl(context, relayUrl)
            ServiceConfigStore.setEnabled(context, true)
            context.startForegroundService(Intent(context, SyncService::class.java).apply {
                action = SyncService.ACTION_START
                putExtra(SyncService.EXTRA_RELAY_URL, relayUrl)
            })
            E2eCommandResult(requestId, "ok")
        }
        "STOP_SYNC" -> {
            ServiceConfigStore.setEnabled(context, false)
            context.stopService(Intent(context, SyncService::class.java))
            E2eCommandResult(requestId, "ok")
        }
        "SET_NETWORK_EXPECTED" -> {
            val expected = command.param("expected") ?: return E2eCommandResult(requestId, "invalid", "expected required")
            require(expected == "online" || expected == "offline") { "expected must be online or offline" }
            context.getSharedPreferences("e2e-control", Context.MODE_PRIVATE).edit {
                putString("network_expected", expected)
            }
            E2eCommandResult(requestId, "ok")
        }
        "SET_LAN_AVAILABLE" -> {
            val available = command.param("available") ?: return E2eCommandResult(requestId, "invalid", "available required")
            require(available == "true" || available == "false") { "available must be true or false" }
            context.getSharedPreferences("e2e-control", Context.MODE_PRIVATE).edit {
                if (available.toBoolean()) remove("lan_fault_until_ms")
                else putLong("lan_fault_until_ms", System.currentTimeMillis() + 120_000L)
            }
            SyncService.notifyRoutePreferenceChanged()
            SyncServiceStatus.requestRouteRetry()
            E2eCommandResult(requestId, "ok")
        }
        "NOTIFICATION_FIXTURE" -> NotificationActionFixture.execute(
            context,
            command.param("fixture").orEmpty(),
            command.param("operation").orEmpty(),
        ).toResult(requestId)
        "NOTIFICATION_MIRROR" -> NotificationActionControl.execute(
            context,
            command.param("operation").orEmpty(),
        ).toResult(requestId)
        "NOTIFICATION_ORIGIN" -> NotificationActionControl.origin(
            command.param("operation").orEmpty(),
        ).toResult(requestId)
        "RECONCILE" -> {
            val relayUrl = ServiceConfigStore.read(context).relayUrl
                ?: return E2eCommandResult(requestId, "invalid", "relay URL is not configured")
            context.startForegroundService(Intent(context, SyncService::class.java).apply {
                action = SyncService.ACTION_START
                putExtra(SyncService.EXTRA_RELAY_URL, relayUrl)
            })
            E2eCommandResult(requestId, "ok")
        }
        "CALL_CAPTURE_ENABLE" -> {
            if (!SyncService.startDebugCallCapture()) {
                return E2eCommandResult(requestId, "unsupported", "debug call capture requires an active sync service")
            }
            E2eCommandResult(requestId, "ok", payload = JSONObject().put("enabled", true))
        }
        "CALL_STATE" -> {
            val forbidden = setOf("phone_number", "phone", "contact", "caller", "callee", "audio", "recording", "voicemail")
            if (command.params.keys.any { it.lowercase() in forbidden }) {
                return E2eCommandResult(requestId, "invalid", "phone/contact/audio fields are forbidden")
            }
            val requested = command.param("state") ?: return E2eCommandResult(requestId, "invalid", "state required")
            if (requested !in setOf("ringing", "active", "idle")) {
                return E2eCommandResult(requestId, "invalid", "state must be ringing, active, or idle")
            }
            val event = SyncService.injectDebugCallState(requested)
                ?: return E2eCommandResult(requestId, "unsupported", "debug call capture is not active")
            E2eCommandResult(
                requestId,
                "ok",
                payload = JSONObject()
                    .put("call_session_id", event.callSessionId)
                    .put("state", event.state)
                    .put("sequence", event.sequence),
            )
        }
        "DISMISS_NEWEST_MIRROR" -> controls.dismissNewestMirror(context).toResult(requestId)
        "EMIT_SNAPSHOT" -> controls.emitSnapshot(context).toResult(requestId)
        "FORCE_REPAIR_SNAPSHOT" -> controls.forceRepairSnapshot(context).toResult(requestId)
        "LOCAL_UNPAIR" -> controls.localUnpair(context).toResult(requestId)
        "OFFLINE_PAIR_START" -> {
            val displayName = command.param("display_name")
                ?: return E2eCommandResult(requestId, "invalid", "display_name required")
            val qr = E2eOfflinePairingControl.start(context, displayName).encodeToByteArray()
            if (qr.isEmpty() || qr.size > MAX_SECRET_BYTES) {
                qr.fill(0)
                return E2eCommandResult(requestId, "error", "private_result_unavailable")
            }
            E2eCommandResult(requestId, "ok", payload = E2eStateProvider.offlinePairingEvidenceJson(context), secretPayload = qr)
        }
        "OFFLINE_PAIR_JOIN" -> {
            val displayName = command.param("display_name")
                ?: return E2eCommandResult(requestId, "invalid", "display_name required")
            val inputId = command.param("secret_input_id")
                ?: return E2eCommandResult(requestId, "invalid", "secret_input_id required")
            val qr = consumePrivateInput(context, requestId, inputId, "e2e-inputs")
            try {
                E2eOfflinePairingControl.join(context, qr.decodeToString(), displayName)
            } finally {
                qr.fill(0)
            }
            E2eCommandResult(requestId, "ok", payload = E2eStateProvider.offlinePairingEvidenceJson(context), secretPayload = byteArrayOf('{'.code.toByte(), '}'.code.toByte()))
        }
        "OFFLINE_PAIR_CONFIRM" -> {
            val inputId = command.param("secret_input_id")
                ?: return E2eCommandResult(requestId, "invalid", "secret_input_id required")
            val session = consumePrivateInput(context, requestId, inputId, "e2e-inputs")
            try {
                E2eOfflinePairingControl.confirm(context, session.decodeToString())
            } finally {
                session.fill(0)
            }
            E2eCommandResult(requestId, "ok", payload = E2eStateProvider.offlinePairingEvidenceJson(context), secretPayload = byteArrayOf('{'.code.toByte(), '}'.code.toByte()))
        }
        "OFFLINE_PAIR_CANCEL" -> {
            val inputId = command.param("secret_input_id")
                ?: return E2eCommandResult(requestId, "invalid", "secret_input_id required")
            val session = consumePrivateInput(context, requestId, inputId, "e2e-inputs")
            try {
                E2eOfflinePairingControl.cancel(context, session.decodeToString())
            } finally {
                session.fill(0)
            }
            E2eCommandResult(requestId, "ok", payload = E2eStateProvider.offlinePairingEvidenceJson(context), secretPayload = byteArrayOf('{'.code.toByte(), '}'.code.toByte()))
        }
        "OFFLINE_PAIR_QUERY" -> {
            val status = E2eOfflinePairingControl.status(context)
            val privateStatus = JSONObject().apply {
                status.sessionId?.let { put("session_id", it) }
                status.sas?.let { put("sas", it) }
            }.toString().encodeToByteArray()
            E2eCommandResult(requestId, "ok", payload = E2eStateProvider.offlinePairingEvidenceJson(context), secretPayload = privateStatus)
        }
        "CLEAR_ACTIVITY" -> {
            E2eStateProvider.clearActivity(context)
            SyncService.clearProductObservationsForE2e()
            E2eCommandResult(requestId, "ok")
        }
        "STATUS" -> E2eCommandResult(requestId, "ok", payload = JSONObject(E2eStateProvider.snapshotJson(context)))
        else -> E2eCommandResult(requestId, "forbidden")
        }
    }

    private fun writeResult(context: Context, result: E2eCommandResult) {
        val directory = File(context.filesDir, RESULT_DIR).apply { mkdirs() }
        val target = File(directory, "${safeRequestId(result.requestId)}.json")
        val temporary = File(directory, ".${target.name}.tmp-${System.nanoTime()}")
        temporary.writeText(result.toJson().toString())
        check(temporary.renameTo(target)) { "unable to atomically publish E2E result" }
    }

    private fun validateOfflineParams(command: E2eCommand): String? {
        val allowed = when (command.name) {
            "OFFLINE_PAIR_START" -> setOf("display_name")
            "OFFLINE_PAIR_JOIN" -> setOf("display_name", "secret_input_id")
            "OFFLINE_PAIR_CONFIRM", "OFFLINE_PAIR_CANCEL" -> setOf("secret_input_id")
            "OFFLINE_PAIR_QUERY" -> emptySet()
            "SET_LAN_AVAILABLE" -> setOf("available")
            "NOTIFICATION_FIXTURE" -> setOf("fixture", "operation")
            "NOTIFICATION_MIRROR", "NOTIFICATION_ORIGIN" -> setOf("operation")
            "DISMISS_NEWEST_MIRROR", "EMIT_SNAPSHOT", "FORCE_REPAIR_SNAPSHOT", "LOCAL_UNPAIR" -> emptySet()
            else -> return null
        }
        if (command.params.keys.any { it !in allowed }) return "unexpected parameter"
        if (command.params.values.any { it.encodeToByteArray().size > MAX_SECRET_BYTES }) return "parameter too large"
        if (command.name == "NOTIFICATION_FIXTURE") {
            if (command.param("fixture") !in setOf("reply", "mark_read", "auto_cancel", "persistent")) {
                return "fixture must be reply, mark_read, auto_cancel, or persistent"
            }
            if (command.param("operation") !in setOf("post", "update", "cancel", "reset_counters")) {
                return "operation must be post, update, cancel, or reset_counters"
            }
        }
        if (command.name == "NOTIFICATION_MIRROR" && command.param("operation") !in setOf(
                "invoke_reply", "invoke_mark_read", "replay_last_invoke", "arm_reply", "arm_mark_read", "invoke_armed", "tap",
            )
        ) return "invalid mirror operation"
        if (command.name == "NOTIFICATION_ORIGIN" && command.param("operation") !in setOf(
                "pause_after_claim", "release_claim_pause",
            )
        ) return "invalid origin operation"
        return null
    }

    private fun consumePrivateInput(context: Context, requestId: String, inputId: String, directoryName: String): ByteArray {
        require(inputId == requestId && SAFE_HANDLE.matches(inputId)) { "invalid private input handle" }
        require(directoryName == "e2e-inputs" || directoryName == "e2e-auth") { "private input unavailable" }
        val directory = File(context.filesDir, directoryName)
        val source = File(directory, inputId)
        return try {
            requirePrivateNode(directory, OsConstants.S_IFDIR, 448)
            require(source.parentFile?.canonicalFile == directory.canonicalFile && !Files.isSymbolicLink(source.toPath())) {
                "private input unavailable"
            }
            requirePrivateNode(source, OsConstants.S_IFREG, 384)
            require(source.length() in 1..MAX_SECRET_BYTES.toLong()) { "private input unavailable" }
            source.readBytes().also { require(it.size <= MAX_SECRET_BYTES) { "private input unavailable" } }
        } finally {
            val removed = runCatching { Files.deleteIfExists(source.toPath()) }.isSuccess
            check(removed && !Files.exists(source.toPath(), LinkOption.NOFOLLOW_LINKS)) { "private input cleanup failed" }
        }
    }

    internal fun consumePrivateInputForTest(context: Context, requestId: String, directoryName: String): ByteArray =
        consumePrivateInput(context, requestId, requestId, directoryName)

    private fun writeSecretResult(context: Context, requestId: String, value: ByteArray) {
        require(SAFE_HANDLE.matches(requestId) && value.size in 1..MAX_SECRET_BYTES) { "invalid private result" }
        val directory = File(context.filesDir, "e2e-secrets").apply {
            mkdirs()
        }
        require(!Files.isSymbolicLink(directory.toPath())) { "private result unavailable" }
        Os.chmod(directory.path, 448)
        requirePrivateNode(directory, OsConstants.S_IFDIR, 448)
        val target = File(directory, requestId)
        require(!Files.exists(target.toPath(), LinkOption.NOFOLLOW_LINKS)) { "stale private result handle" }
        require(target.createNewFile()) { "private result unavailable" }
        var complete = false
        try {
            Os.chmod(target.path, 384)
            requirePrivateNode(target, OsConstants.S_IFREG, 384)
            FileOutputStream(target).use { output -> output.write(value); output.fd.sync() }
            complete = true
        } finally {
            if (!complete) runCatching { Files.deleteIfExists(target.toPath()) }
        }
    }

    internal fun writeSecretResultForTest(context: Context, requestId: String, value: ByteArray) =
        writeSecretResult(context, requestId, value)

    private fun requirePrivateNode(file: File, type: Int, mode: Int) {
        val stat = runCatching { Os.lstat(file.path) }
            .getOrElse { throw IllegalArgumentException("private file unavailable", it) }
        require((stat.st_mode and OsConstants.S_IFMT) == type && stat.st_uid == Process.myUid() && (stat.st_mode and 511) == mode) {
            "private file ownership or mode invalid"
        }
    }

    private fun E2eCommand.timeoutMs(): Long = param("timeout_ms")?.toLongOrNull()?.coerceIn(1_000L, 300_000L)
        ?: 60_000L

    private fun E2eControlOutcome.toResult(requestId: String): E2eCommandResult = E2eCommandResult(
        requestId = requestId,
        code = code,
        payload = JSONObject().put("outcome", outcome),
    )
}

internal object E2eOfflinePairingControl {
    private val monitor = Any()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var controller: OfflinePairingApiController? = null

    private fun controller(context: Context): OfflinePairingApiController = controller ?: synchronized(monitor) {
        controller ?: OfflinePairingApiController(
            scope,
            defaultOfflinePairingRuntimeFactory { context.applicationContext },
        ) {}.also { controller = it }
    }

    fun start(context: Context, displayName: String): String = controller(context).start(displayName)
    fun join(context: Context, qr: String, displayName: String) = controller(context).join(qr, displayName)
    fun confirm(context: Context, sessionId: String) = controller(context).confirm(sessionId)
    suspend fun cancel(context: Context, sessionId: String) = controller(context).cancel(sessionId)
    fun status(context: Context): OfflinePairingPublicStatus = controller(context).getStatus()
    suspend fun quiesceAndAwait(context: Context) = controller(context).quiesceAndAwait()

    fun publicStatus(context: Context): JSONObject {
        val status = status(context)
        return JSONObject().apply {
            put("role", status.role?.code)
            put("phase", status.phase.code)
            put("error_code", status.error?.code)
            put("completed", status.completed)
            status.sessionId?.let { put("session_id_hash", sha256Hex(it.encodeToByteArray())) }
            status.sas?.let { put("sas_hash", sha256Hex(it.encodeToByteArray())) }
        }
    }
}

private fun sha256Hex(value: ByteArray): String = MessageDigest.getInstance("SHA-256")
    .digest(value).joinToString("") { "%02x".format(it.toInt() and 0xff) }

internal object E2eRequestHandle {
    private const val MAX_FUTURE_MILLIS = 5 * 60 * 1_000L

    fun matches(token: String, command: String, handle: String, nowMillis: Long): Boolean {
        return try {
            val parts = handle.split('.')
            if (parts.size != 5 || parts[0] != "v1") return false
            val expires = parts[1].toLong()
            if (expires < nowMillis || expires - nowMillis > MAX_FUTURE_MILLIS) return false
            if (parts[2].length != 32 || hexToBytes(parts[2]).size != 16) return false
            val commandHash = MessageDigest.getInstance("SHA-256").digest(command.encodeToByteArray()).copyOfRange(0, 8)
            if (!MessageDigest.isEqual(commandHash, hexToBytes(parts[3]))) return false
            val prefix = parts.take(4).joinToString(".")
            val mac = Mac.getInstance("HmacSHA256").apply {
                init(SecretKeySpec(token.encodeToByteArray(), "HmacSHA256"))
            }.doFinal(prefix.encodeToByteArray()).copyOfRange(0, 16)
            MessageDigest.isEqual(mac, hexToBytes(parts[4]))
        } catch (_: Throwable) {
            false
        }
    }

    fun forTest(token: String, command: String, expiresMillis: Long, nonce: ByteArray): String {
        require(nonce.size == 16)
        val commandHash = MessageDigest.getInstance("SHA-256").digest(command.encodeToByteArray()).copyOfRange(0, 8)
        val prefix = "v1.$expiresMillis.${nonce.toHex()}.${commandHash.toHex()}"
        val mac = Mac.getInstance("HmacSHA256").apply {
            init(SecretKeySpec(token.encodeToByteArray(), "HmacSHA256"))
        }.doFinal(prefix.encodeToByteArray()).copyOfRange(0, 16)
        return "$prefix.${mac.toHex()}"
    }

    private fun hexToBytes(value: String): ByteArray {
        require(value.length % 2 == 0 && value.all { it in '0'..'9' || it in 'a'..'f' })
        return ByteArray(value.length / 2) { index -> value.substring(index * 2, index * 2 + 2).toInt(16).toByte() }
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it.toInt() and 0xff) }
}

internal object E2eSessionToken {
    private const val PREFS = "e2e-control"
    private const val TOKEN = "session_token"
    private const val TOKEN_FILE = "e2e-token"

    @Synchronized
    @Suppress("ApplySharedPref", "UseKtx")
    fun ensure(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val value = prefs.getString(TOKEN, null) ?: java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(
            ByteArray(32).also { java.security.SecureRandom().nextBytes(it) },
        ).also { check(prefs.edit().putString(TOKEN, it).commit()) }
        publishTokenFile(context, value)
        return value
    }

    fun forTest(context: Context, @Suppress("UNUSED_PARAMETER") marker: String): String = ensure(context)

    fun matches(context: Context, provided: String?): Boolean {
        if (provided.isNullOrBlank()) return false
        val expected = ensure(context)
        return java.security.MessageDigest.isEqual(expected.toByteArray(), provided.toByteArray())
    }

    private fun publishTokenFile(context: Context, value: String) {
        val target = context.getFileStreamPath(TOKEN_FILE)
        val temporary = File(context.filesDir, ".$TOKEN_FILE.tmp")
        temporary.writeText(value)
        check(temporary.renameTo(target)) { "unable to publish E2E token" }
    }
}
