package co.twinotify.core.e2e

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import co.twinotify.core.crypto.CryptoStore
import co.twinotify.core.pairing.PairPayload
import co.twinotify.core.pairing.PairProtocol
import co.twinotify.core.service.ServiceConfigStore
import co.twinotify.core.service.SyncService
import co.twinotify.core.storage.DeviceIdentity
import co.twinotify.core.storage.PeerStore
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
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
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("request_id", requestId)
        put("code", code)
        detail?.let { put("detail", it.take(MAX_DETAIL_LENGTH)) }
        payload?.let { put("payload", it) }
    }
}

/** Debug-only command bridge. Every command is authenticated by the install-scoped token. */
class E2eControlReceiver : BroadcastReceiver() {
    companion object {
        const val ACTION_CONTROL = "co.twinotify.e2e.CONTROL"
        const val EXTRA_REQUEST_ID = "request_id"
        const val EXTRA_COMMAND = "command"
        const val EXTRA_TOKEN = "token"
        private const val RESULT_DIR = "e2e-results"
        private const val MAX_REQUEST_ID_LENGTH = 128
        private val ALLOWED_COMMANDS = setOf(
            "PAIR_INIT", "PAIR_JOIN", "PAIR_CONFIRM", "PAIR_COMPLETE", "START_SYNC", "STOP_SYNC",
            "SET_NETWORK_EXPECTED", "RECONCILE", "CLEAR_ACTIVITY", "STATUS",
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
            val result = executeForTest(appContext, E2eCommand.fromIntent(intent))
            writeResult(appContext, result)
            pending.finish()
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
        return try {
            runBlocking(Dispatchers.IO) { executeAuthorized(context.applicationContext, command, requestId) }
        } catch (error: Throwable) {
            E2eCommandResult(requestId, "error", error.message ?: error::class.simpleName)
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
            E2eCommandResult(requestId, "ok")
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
            PairProtocol.deviceBCompletePair(
                relayUrl,
                token,
                DeviceIdentity.getOrCreate(context),
                CryptoStore.loadOrGenerate(context).first.publicKey,
                CryptoStore.loadOrGenerate(context).second.publicKey,
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
            context.getSharedPreferences("e2e-control", Context.MODE_PRIVATE).edit()
                .putString("network_expected", expected).commit()
            E2eCommandResult(requestId, "ok")
        }
        "RECONCILE" -> {
            val relayUrl = ServiceConfigStore.read(context).relayUrl
                ?: return E2eCommandResult(requestId, "invalid", "relay URL is not configured")
            context.startForegroundService(Intent(context, SyncService::class.java).apply {
                action = SyncService.ACTION_START
                putExtra(SyncService.EXTRA_RELAY_URL, relayUrl)
            })
            E2eCommandResult(requestId, "ok")
        }
        "CLEAR_ACTIVITY" -> {
            E2eStateProvider.clearActivity(context)
            E2eCommandResult(requestId, "ok")
        }
        "STATUS" -> E2eCommandResult(requestId, "ok", payload = JSONObject(E2eStateProvider.snapshotJson(context)))
        else -> E2eCommandResult(requestId, "forbidden")
        }
    }

    private fun writeResult(context: Context, result: E2eCommandResult) {
        val directory = File(context.filesDir, RESULT_DIR).apply { mkdirs() }
        val target = File(directory, "${safeRequestId(result.requestId)}.json")
        val temporary = File(directory, ".${target.name}.tmp-${Thread.currentThread().id}")
        temporary.writeText(result.toJson().toString())
        check(temporary.renameTo(target)) { "unable to atomically publish E2E result" }
    }
}

internal object E2eSessionToken {
    private const val PREFS = "e2e-control"
    private const val TOKEN = "session_token"
    private const val TOKEN_FILE = "e2e-token"

    @Synchronized
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
