package co.twinotify.core

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.core.net.toUri
import expo.modules.kotlin.Promise
import expo.modules.kotlin.modules.Module
import expo.modules.kotlin.modules.ModuleDefinition
import co.twinotify.core.auth.JwtMinter
import co.twinotify.core.crypto.CryptoStore
import co.twinotify.core.crypto.Encrypter
import co.twinotify.core.crypto.NonceSource
import co.twinotify.core.pairing.Fingerprint
import co.twinotify.core.pairing.PairPayload
import co.twinotify.core.pairing.PairProtocol
import co.twinotify.core.pairing.lan.LanPairingCodec
import co.twinotify.core.pairing.lan.LanPairingQr
import co.twinotify.core.pairing.lan.AndroidOfflinePairingRuntimeFactory
import co.twinotify.core.service.toEventMap
import co.twinotify.core.storage.DeviceIdentity
import co.twinotify.core.storage.PeerRecord
import co.twinotify.core.storage.PeerStore
import co.twinotify.core.storage.ReplayGuard
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.CoroutineScope
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.Base64
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.text.Normalizer

class TwinotifyCoreModule internal constructor(
    offlinePairingRuntimeFactory: OfflinePairingRuntimeFactory?,
) : Module() {

    constructor() : this(null)

    private val moduleScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val offlinePairing = OfflinePairingApiController(
        moduleScope,
        offlinePairingRuntimeFactory ?: defaultOfflinePairingRuntimeFactory(::requireContext),
    ) { status ->
        mainHandler.post { sendEvent("onOfflinePairingStatus", status.toEventMap()) }
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    private fun requireContext(): Context =
        appContext.reactContext ?: error("no react context — module not initialised")

    override fun definition() = ModuleDefinition {
        Name("TwinotifyCore")

        Events("onSyncStatus", "onPeerUnpair", "onOfflinePairingStatus")

        OnCreate {
            try {
                co.twinotify.core.filter.DenylistLoader.load(requireContext())
            } catch (e: SecurityException) {
                throw e   // tamper — abort module init so app visibly fails
            } catch (e: Throwable) {
                android.util.Log.e("Twinotify", "denylist load failed (non-tamper): ${e.message}", e)
            }
            moduleScope.launch {
                co.twinotify.core.service.SyncServiceStatus.health.collect { health ->
                    sendEvent("onSyncStatus", health.toEventMap())
                }
            }
            moduleScope.launch {
                co.twinotify.core.service.SyncServiceStatus.peerUnpaired.collect {
                    sendEvent("onPeerUnpair", emptyMap<String, Any>())
                }
            }
        }

        OnDestroy {
            offlinePairing.destroy()
            moduleScope.cancel()
        }

        AsyncFunction("startSyncService") { relayUrl: String, promise: Promise ->
            moduleScope.launch {
                try {
                    val ctx = requireContext()
                    co.twinotify.core.service.ServiceConfigStore.setRelayUrl(ctx, relayUrl)
                    co.twinotify.core.service.ServiceConfigStore.setEnabled(ctx, true)
                    val intent = android.content.Intent(ctx, co.twinotify.core.service.SyncService::class.java).apply {
                        action = co.twinotify.core.service.SyncService.ACTION_START
                        putExtra(co.twinotify.core.service.SyncService.EXTRA_RELAY_URL, relayUrl)
                    }
                    ctx.startForegroundService(intent)
                    promise.resolve(null)
                } catch (e: Throwable) { promise.reject("START_SVC", e.message ?: "err", e) }
            }
        }

        AsyncFunction("stopSyncService") { promise: Promise ->
            moduleScope.launch {
                try {
                    val ctx = requireContext()
                    co.twinotify.core.service.ServiceConfigStore.setEnabled(ctx, false)
                    val intent = android.content.Intent(ctx, co.twinotify.core.service.SyncService::class.java)
                    ctx.stopService(intent)
                    promise.resolve(null)
                } catch (e: Throwable) { promise.reject("STOP_SVC", e.message ?: "err", e) }
            }
        }

        AsyncFunction("setCallCaptureEnabled") { enabled: Boolean, promise: Promise ->
            moduleScope.launch {
                try {
                    val ctx = requireContext()
                    val config = co.twinotify.core.service.ServiceConfigStore.setCallCaptureEnabled(ctx, enabled)
                    if (!enabled) {
                        // DataStore persistence alone would leave an already-running callback
                        // registered until the next service restart. Tear down the live source now.
                        co.twinotify.core.service.SyncService.stopActiveCallCapture()
                    } else if (config.enabled) {
                        val intent = android.content.Intent(ctx, co.twinotify.core.service.SyncService::class.java).apply {
                            action = co.twinotify.core.service.SyncService.ACTION_START
                            config.relayUrl?.let { putExtra(co.twinotify.core.service.SyncService.EXTRA_RELAY_URL, it) }
                        }
                        ctx.startForegroundService(intent)
                    }
                    promise.resolve(config.callCaptureEnabled)
                } catch (e: Throwable) { promise.reject("CALL_CAPTURE", e.message ?: "err", e) }
            }
        }

        AsyncFunction("isNotificationListenerGranted") { promise: Promise ->
            try {
                val ctx = requireContext()
                val enabled = androidx.core.app.NotificationManagerCompat
                    .getEnabledListenerPackages(ctx).contains(ctx.packageName)
                promise.resolve(enabled)
            } catch (e: Throwable) { promise.reject("NLS_GRANT", e.message ?: "err", e) }
        }

        AsyncFunction("openListenerSettings") { promise: Promise ->
            try {
                val ctx = requireContext()
                val i = android.content.Intent(android.provider.Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                ctx.startActivity(i)
                promise.resolve(null)
            } catch (e: Throwable) { promise.reject("NLS_SETTINGS", e.message ?: "err", e) }
        }

        AsyncFunction("isPostNotificationsGranted") { promise: Promise ->
            try {
                val ctx = requireContext()
                val granted = androidx.core.content.ContextCompat.checkSelfPermission(
                    ctx, android.Manifest.permission.POST_NOTIFICATIONS
                ) == android.content.pm.PackageManager.PERMISSION_GRANTED
                promise.resolve(granted)
            } catch (e: Throwable) { promise.reject("POST_NOTIF", e.message ?: "err", e) }
        }

        AsyncFunction("openAppSettings") { promise: Promise ->
            try {
                val ctx = requireContext()
                val i = android.content.Intent(
                    android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    "package:${ctx.packageName}".toUri()
                ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                ctx.startActivity(i)
                promise.resolve(null)
            } catch (e: Throwable) { promise.reject("APP_SETTINGS", e.message ?: "err", e) }
        }

        AsyncFunction("getSyncStatus") { promise: Promise ->
            try {
                promise.resolve(co.twinotify.core.service.SyncServiceStatus.health.value.toEventMap())
            } catch (e: Throwable) { promise.reject("SYNC_STATUS", e.message ?: "err", e) }
        }

        AsyncFunction("getPairStatus") { promise: Promise ->
            moduleScope.launch {
                try {
                    val peer = co.twinotify.core.storage.PeerStore.load(requireContext())
                    if (peer == null) {
                        promise.resolve(mapOf("paired" to false))
                    } else {
                        promise.resolve(mapOf(
                            "paired" to true,
                            "peerDeviceId" to peer.deviceId,
                            "peerEncPubkey" to Base64.getEncoder().encodeToString(peer.encPubkey),
                            "peerSignPubkey" to Base64.getEncoder().encodeToString(peer.signPubkey),
                            "peerDisplayName" to (peer.displayName ?: ""),
                        ))
                    }
                } catch (e: Throwable) { promise.reject("PAIR_STATUS", e.message ?: "err", e) }
            }
        }

        AsyncFunction("startOfflinePairing") { displayName: String, promise: Promise ->
            moduleScope.launch {
                try {
                    val qrJson = offlinePairing.start(displayName)
                    resolveOnMain(promise, qrJson)
                } catch (error: Throwable) {
                    rejectOfflinePairingOnMain(promise, error)
                }
            }
        }

        AsyncFunction("joinOfflinePairing") { qrJson: String, displayName: String, promise: Promise ->
            moduleScope.launch {
                try {
                    offlinePairing.join(qrJson, displayName)
                    resolveOnMain(promise, null)
                } catch (error: Throwable) {
                    rejectOfflinePairingOnMain(promise, error)
                }
            }
        }

        AsyncFunction("confirmOfflinePairing") { sessionId: String, promise: Promise ->
            moduleScope.launch {
                try {
                    offlinePairing.confirm(sessionId)
                    resolveOnMain(promise, null)
                } catch (error: Throwable) {
                    rejectOfflinePairingOnMain(promise, error)
                }
            }
        }

        AsyncFunction("cancelOfflinePairing") { sessionId: String, promise: Promise ->
            moduleScope.launch {
                try {
                    offlinePairing.cancel(sessionId)
                    resolveOnMain(promise, null)
                } catch (error: Throwable) {
                    rejectOfflinePairingOnMain(promise, error)
                }
            }
        }

        AsyncFunction("getOfflinePairingStatus") { promise: Promise ->
            moduleScope.launch {
                try {
                    resolveOnMain(promise, offlinePairing.getStatus().toEventMap())
                } catch (error: Throwable) {
                    rejectOfflinePairingOnMain(promise, error)
                }
            }
        }

        AsyncFunction("getDeviceId") { promise: Promise ->
            moduleScope.launch {
                try { promise.resolve(DeviceIdentity.getOrCreate(requireContext())) }
                catch (e: Throwable) { promise.reject("DEVICE_ID", e.message ?: "err", e) }
            }
        }

        AsyncFunction("getPublicKeys") { promise: Promise ->
            moduleScope.launch {
                try {
                    val (box, sign) = CryptoStore.loadOrGenerate(requireContext())
                    promise.resolve(mapOf(
                        "encPubkey"  to Base64.getEncoder().encodeToString(box.publicKey),
                        "signPubkey" to Base64.getEncoder().encodeToString(sign.publicKey),
                    ))
                } catch (e: Throwable) { promise.reject("PUBKEYS", e.message ?: "err", e) }
            }
        }

        AsyncFunction("getDeviceDisplayName") { promise: Promise ->
            try {
                val ctx = requireContext()
                val name = android.provider.Settings.Global.getString(ctx.contentResolver, "device_name")
                    ?.takeIf { it.isNotBlank() }
                    ?: android.os.Build.MODEL
                    ?: "Android device"
                promise.resolve(name)
            } catch (e: Throwable) { promise.reject("DEVICE_NAME", e.message ?: "err", e) }
        }

        AsyncFunction("startPairInitiator") { relayUrl: String, displayName: String, promise: Promise ->
            moduleScope.launch {
                try {
                    val ctx = requireContext()
                    val (box, sign) = CryptoStore.loadOrGenerate(ctx)
                    val deviceId = DeviceIdentity.getOrCreate(ctx)
                    val token = PairPayload.newToken()
                    PairProtocol.initiate(
                        relayUrl, token, deviceId, box.publicKey, sign.publicKey,
                        displayName.takeIf { it.isNotBlank() }
                    )
                    val payload = PairPayload(relayUrl, deviceId, box.publicKey, sign.publicKey, token).toJson()
                    promise.resolve(payload)
                } catch (e: Throwable) { promise.reject("PAIR_INIT", e.message ?: "err", e) }
            }
        }

        AsyncFunction("sendPeerHello") { relayUrl: String, pairToken: String, displayName: String, promise: Promise ->
            moduleScope.launch {
                try {
                    val ctx = requireContext()
                    val deviceId = co.twinotify.core.storage.DeviceIdentity.getOrCreate(ctx)
                    val (box, sign) = co.twinotify.core.crypto.CryptoStore.loadOrGenerate(ctx)
                    co.twinotify.core.pairing.PairProtocol.sendPeerHello(
                        relayUrl, pairToken, deviceId, box.publicKey, sign.publicKey,
                        displayName.takeIf { it.isNotBlank() }
                    )
                    promise.resolve(null)
                } catch (e: Throwable) { promise.reject("PAIR_HELLO", e.message ?: "err", e) }
            }
        }

        AsyncFunction("awaitPeerHello") { relayUrl: String, pairToken: String, promise: Promise ->
            moduleScope.launch {
                try {
                    val ctx = requireContext()
                    val deviceId = co.twinotify.core.storage.DeviceIdentity.getOrCreate(ctx)
                    val signSecret = co.twinotify.core.crypto.CryptoStore.loadOrGenerate(ctx).second.secretKey
                    val frame = co.twinotify.core.pairing.PairNotifyClient.awaitAuthenticatedFrame(
                        relayUrl, pairToken, role = "A", expectedType = "peer.hello",
                        deviceId = deviceId, signSecretKey = signSecret,
                    )
                    promise.resolve(frame)
                } catch (e: Throwable) { promise.reject("PAIR_HELLO_WAIT", e.message ?: "err", e) }
            }
        }

        AsyncFunction("sendConfirmationSig") { relayUrl: String, pairToken: String, sigB64: String, promise: Promise ->
            moduleScope.launch {
                try {
                    val sig = java.util.Base64.getDecoder().decode(sigB64)
                    co.twinotify.core.pairing.PairProtocol.sendConfirmationSig(relayUrl, pairToken, sig)
                    promise.resolve(null)
                } catch (e: Throwable) { promise.reject("SEND_SIG", e.message ?: "err", e) }
            }
        }

        AsyncFunction("computeFingerprint") { encPubkeyB64: String, signPubkeyB64: String, promise: Promise ->
            try {
                val enc  = Base64.getDecoder().decode(encPubkeyB64)
                val sign = Base64.getDecoder().decode(signPubkeyB64)
                promise.resolve(Fingerprint.of(enc, sign))
            } catch (e: Throwable) { promise.reject("FINGERPRINT", e.message ?: "err", e) }
        }

        AsyncFunction("deviceASignConfirmation") { pairToken: String, bEncB64: String, bSignB64: String, promise: Promise ->
            moduleScope.launch {
                try {
                    val ctx = requireContext()
                    val (box, sign) = CryptoStore.loadOrGenerate(ctx)
                    val bEncPk  = Base64.getDecoder().decode(bEncB64)
                    val bSignPk = Base64.getDecoder().decode(bSignB64)
                    val sig = PairProtocol.deviceASignConfirmation(
                        pairToken, box.publicKey, sign.publicKey, bEncPk, bSignPk, sign.secretKey
                    )
                    promise.resolve(Base64.getEncoder().encodeToString(sig))
                } catch (e: Throwable) { promise.reject("SIGN_CONFIRM", e.message ?: "err", e) }
            }
        }

        AsyncFunction("awaitPairSig") { relayUrl: String, pairToken: String, promise: Promise ->
            moduleScope.launch {
                try {
                    val ctx = requireContext()
                    val deviceId = co.twinotify.core.storage.DeviceIdentity.getOrCreate(ctx)
                    val signSecret = co.twinotify.core.crypto.CryptoStore.loadOrGenerate(ctx).second.secretKey
                    val frame = co.twinotify.core.pairing.PairNotifyClient.awaitAuthenticatedFrame(
                        relayUrl, pairToken, role = "B", expectedType = "pair.sig",
                        deviceId = deviceId, signSecretKey = signSecret,
                    )
                    val sig = java.util.Base64.getDecoder().decode(org.json.JSONObject(frame).getString("confirmation_sig"))
                    promise.resolve(Base64.getEncoder().encodeToString(sig))
                } catch (e: Throwable) { promise.reject("PAIR_NOTIFY", e.message ?: "err", e) }
            }
        }

        AsyncFunction("deviceBCompletePairing") { relayUrl: String, pairToken: String, sigB64: String, promise: Promise ->
            moduleScope.launch {
                try {
                    val ctx = requireContext()
                    val (box, sign) = CryptoStore.loadOrGenerate(ctx)
                    val deviceId = DeviceIdentity.getOrCreate(ctx)
                    val sig = Base64.getDecoder().decode(sigB64)
                    PairProtocol.deviceBCompletePair(relayUrl, pairToken, deviceId, box.publicKey, sign.publicKey, sig)
                    promise.resolve(null)
                } catch (e: Throwable) { promise.reject("COMPLETE_PAIR", e.message ?: "err", e) }
            }
        }

        AsyncFunction("storePeerPubkeys") { encB64: String, signB64: String, peerDeviceId: String, peerDisplayName: String, promise: Promise ->
            moduleScope.launch {
                try {
                    val ctx  = requireContext()
                    val enc  = Base64.getDecoder().decode(encB64)
                    val sign = Base64.getDecoder().decode(signB64)
                    val name = peerDisplayName.takeIf { it.isNotBlank() }
                    PeerStore.save(ctx, PeerRecord(peerDeviceId, enc, sign, name))
                    co.twinotify.core.listener.CaptureCoordinator.get(ctx).resumeDeferred()
                    promise.resolve(null)
                } catch (e: Throwable) { promise.reject("PEER_STORE", e.message ?: "err", e) }
            }
        }

        AsyncFunction("mintAuthJwt") { promise: Promise ->
            moduleScope.launch {
                try {
                    val ctx = requireContext()
                    val (_, sign) = CryptoStore.loadOrGenerate(ctx)
                    val deviceId = DeviceIdentity.getOrCreate(ctx)
                    promise.resolve(JwtMinter.mint(deviceId, sign.secretKey))
                } catch (e: Throwable) { promise.reject("JWT", e.message ?: "err", e) }
            }
        }

        AsyncFunction("encryptToPeer") { plaintextB64: String, promise: Promise ->
            moduleScope.launch {
                try {
                    val ctx  = requireContext()
                    val peer = PeerStore.load(ctx) ?: throw IllegalStateException("no peer paired")
                    val (box, _) = CryptoStore.loadOrGenerate(ctx)
                    val nonce = NonceSource.next(ctx)
                    val plain = Base64.getDecoder().decode(plaintextB64)
                    val ct = Encrypter.encrypt(plain, nonce, peer.encPubkey, box.secretKey)
                    promise.resolve(mapOf(
                        "ciphertext" to Base64.getEncoder().encodeToString(ct),
                        "nonce"      to Base64.getEncoder().encodeToString(nonce),
                    ))
                } catch (e: Throwable) { promise.reject("ENCRYPT", e.message ?: "err", e) }
            }
        }

        AsyncFunction("decryptFromPeer") { ctB64: String, nonceB64: String, promise: Promise ->
            moduleScope.launch {
                try {
                    val ctx  = requireContext()
                    val peer = PeerStore.load(ctx) ?: throw IllegalStateException("no peer paired")
                    val (box, _) = CryptoStore.loadOrGenerate(ctx)
                    val ct    = Base64.getDecoder().decode(ctB64)
                    val nonce = Base64.getDecoder().decode(nonceB64)
                    val plain = Encrypter.decrypt(ct, nonce, peer.encPubkey, box.secretKey)
                    promise.resolve(Base64.getEncoder().encodeToString(plain))
                } catch (e: Throwable) { promise.reject("DECRYPT", e.message ?: "err", e) }
            }
        }

        AsyncFunction("unpair") { promise: Promise ->
            moduleScope.launch {
                try {
                    val ctx = requireContext()
                    val peer = PeerStore.load(ctx)
                    val config = co.twinotify.core.service.ServiceConfigStore.read(ctx)
                    // Persist intent before stopping anything. This marker makes a lost 204
                    // retryable as terminal 401 while the old signing key is still available.
                    val markedConfig = co.twinotify.core.service.ServiceConfigStore.setRevocationRequestedAt(ctx)
                    co.twinotify.core.pairing.UnpairWorkflow.execute(
                        stopAndAwait = {
                            offlinePairing.quiesceAndAwait()
                            co.twinotify.core.service.SyncService.shutdownActive(ctx)
                        },
                        revokePeer = {
                            if (peer != null) {
                                val relayUrl = config.relayUrl ?: error("paired device has no relay URL")
                                val (_, sign) = CryptoStore.loadOrGenerate(ctx)
                                val deviceId = DeviceIdentity.getOrCreate(ctx)
                                val jwt = JwtMinter.mint(deviceId, sign.secretKey)
                                PairProtocol.revoke(
                                    relayUrl,
                                    jwt,
                                    debug = (ctx.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0,
                                    revocationMarkerPresent = markedConfig.revocationRequestedAt != null,
                                )
                            }
                        },
                        wipeLocal = {
                            co.twinotify.core.pairing.UnpairOps.wipeAll(ctx)
                            co.twinotify.core.service.SyncServiceStatus.notifyPeerUnpaired()
                        },
                    )
                    promise.resolve(null)
                } catch (e: Throwable) { promise.reject("UNPAIR", e.message ?: "err", e) }
            }
        }

        AsyncFunction("getUserDenylist") { promise: Promise ->
            moduleScope.launch {
                try {
                    val set = co.twinotify.core.filter.AppFilterStore.load(requireContext())
                    promise.resolve(set.toList())
                } catch (e: Throwable) { promise.reject("FILTER_GET", e.message ?: "err", e) }
            }
        }

        AsyncFunction("addToDenylist") { pkg: String, promise: Promise ->
            moduleScope.launch {
                try {
                    co.twinotify.core.filter.AppFilterStore.add(requireContext(), pkg)
                    promise.resolve(null)
                } catch (e: Throwable) { promise.reject("FILTER_ADD", e.message ?: "err", e) }
            }
        }

        AsyncFunction("removeFromDenylist") { pkg: String, promise: Promise ->
            moduleScope.launch {
                try {
                    co.twinotify.core.filter.AppFilterStore.remove(requireContext(), pkg)
                    promise.resolve(null)
                } catch (e: Throwable) { promise.reject("FILTER_REMOVE", e.message ?: "err", e) }
            }
        }

        AsyncFunction("getMetrics") { promise: Promise ->
            moduleScope.launch {
                try {
                    val s = co.twinotify.core.metrics.MetricsStore.snapshot(requireContext())
                    promise.resolve(mapOf(
                        "mirroredToday" to s.mirroredToday,
                        "blockedToday"  to s.blockedToday,
                        "latencyMs"     to s.latencyMs,
                    ))
                } catch (e: Throwable) { promise.reject("METRICS", e.message ?: "err", e) }
            }
        }

        AsyncFunction("ping") { relayUrl: String, authed: Boolean, promise: Promise ->
            val settled = AtomicBoolean(false)
            val handler = Handler(Looper.getMainLooper())
            var timeoutRunnable: Runnable? = null

            fun resolve(value: String) {
                if (settled.compareAndSet(false, true)) {
                    timeoutRunnable?.let { handler.removeCallbacks(it) }
                    promise.resolve(value)
                }
            }
            fun reject(code: String, msg: String, t: Throwable?) {
                if (settled.compareAndSet(false, true)) {
                    timeoutRunnable?.let { handler.removeCallbacks(it) }
                    promise.reject(code, msg, t)
                }
            }

            moduleScope.launch {
                try {
                    val ctx      = requireContext()
                    val deviceId = DeviceIdentity.getOrCreate(ctx)
                    val msgId    = UUID.randomUUID().toString()
                    val envelope = """{"v":1,"type":"ping","msg_id":"$msgId","origin_device":"$deviceId","ts":${System.currentTimeMillis()}}"""

                    val reqBuilder = Request.Builder().url(relayUrl)
                    if (authed) {
                        val (_, sign) = CryptoStore.loadOrGenerate(ctx)
                        reqBuilder.header("Authorization", "Bearer " + JwtMinter.mint(deviceId, sign.secretKey))
                    }
                    val request = reqBuilder.build()

                    val ws = client.newWebSocket(request, object : WebSocketListener() {
                        override fun onOpen(webSocket: WebSocket, response: Response) {
                            webSocket.send(envelope)
                        }
                        override fun onMessage(webSocket: WebSocket, text: String) {
                            resolve(text)
                            webSocket.close(1000, "done")
                        }
                        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                            reject("PING_FAILED", t.message ?: "unknown", t)
                        }
                        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                            reject("PING_CLOSED", "closed before message (code=$code reason=$reason)", null)
                        }
                    })

                    timeoutRunnable = Runnable {
                        if (!settled.get()) {
                            ws.cancel()
                            reject("PING_TIMEOUT", "no response within 10s", null)
                        }
                    }
                    handler.postDelayed(timeoutRunnable!!, 10_000)
                } catch (e: Throwable) {
                    reject("PING_ERR", e.message ?: "err", e)
                }
            }
        }
    }

    private fun resolveOnMain(promise: Promise, value: Any?) {
        mainHandler.post { promise.resolve(value) }
    }

    private fun rejectOfflinePairingOnMain(promise: Promise, failure: Throwable) {
        val error = (failure as? OfflinePairingApiException)?.error
            ?: OfflinePairingApiError.PAIR_RUNTIME_UNAVAILABLE
        mainHandler.post { promise.reject(error.code, error.code, null) }
    }
}

internal enum class OfflinePairingApiRole(val code: String) {
    INITIATOR("initiator"),
    JOINER("joiner"),
}

internal enum class OfflinePairingApiPhase(val code: String) {
    IDLE("idle"),
    ADVERTISING("advertising"),
    RESOLVING("resolving"),
    TLS_AUTHENTICATED("tls_authenticated"),
    VERIFY_CODE("verify_code"),
    LOCAL_CONFIRMED("local_confirmed"),
    MUTUALLY_SIGNED("mutually_signed"),
    COMMITTED("committed"),
    COMPLETE("complete"),
}

internal enum class OfflinePairingApiError(val code: String) {
    PAIR_SESSION_ACTIVE("pair_session_active"),
    PAIR_SESSION_NOT_FOUND("pair_session_not_found"),
    PAIR_SESSION_MISMATCH("pair_session_mismatch"),
    PAIR_INVALID_DISPLAY_NAME("pair_invalid_display_name"),
    PAIR_INVALID_QR("pair_invalid_qr"),
    PAIR_RUNTIME_UNAVAILABLE("pair_runtime_unavailable"),
    EXPIRED("expired"),
    TLS_PIN_MISMATCH("tls_pin_mismatch"),
    IDENTITY_MISMATCH("identity_mismatch"),
    INVALID_FRAME("invalid_frame"),
    COMMIT_FAILED("commit_failed"),
    CANCELLED("cancelled"),
    PEER_REJECTED("peer_rejected"),
    WIFI_PERMISSION_DENIED("wifi_permission_denied"),
    WIFI_UNAVAILABLE("wifi_unavailable"),
}

internal class OfflinePairingApiException(
    val error: OfflinePairingApiError,
) : RuntimeException(error.code)

/** The complete, secret-free native-to-JS pairing status allowlist. */
internal data class OfflinePairingPublicStatus(
    val role: OfflinePairingApiRole?,
    val phase: OfflinePairingApiPhase,
    val sessionId: String?,
    val error: OfflinePairingApiError?,
    val peerDisplayName: String?,
    val sas: String?,
    val completed: Boolean,
) {
    init {
        if (sessionId != null) requireCanonicalSessionId(sessionId)
        require(peerDisplayName == null || peerDisplayName == normalizedDisplayName(peerDisplayName)) {
            "pair_invalid_status"
        }
        require(sas == null || SAS_PATTERN.matches(sas)) { "pair_invalid_status" }
        require(completed == (phase == OfflinePairingApiPhase.COMPLETE)) { "pair_invalid_status" }
        require((role == null) == (sessionId == null)) { "pair_invalid_status" }
        require(phase != OfflinePairingApiPhase.IDLE || !completed) { "pair_invalid_status" }
    }

    fun toEventMap(): Map<String, Any?> = linkedMapOf(
        "role" to role?.code,
        "phase" to phase.code,
        "sessionId" to sessionId,
        "errorCode" to error?.code,
        "peerDisplayName" to peerDisplayName,
        "sas" to sas,
        "completed" to completed,
    )
}

/** Factory seam retained for deterministic API-controller tests. */
internal interface OfflinePairingRuntimeFactory {
    fun start(
        scope: CoroutineScope,
        displayName: String,
        statusSink: (OfflinePairingPublicStatus) -> Unit,
    ): OfflinePairingRuntime

    fun join(
        scope: CoroutineScope,
        qr: LanPairingQr,
        displayName: String,
        statusSink: (OfflinePairingPublicStatus) -> Unit,
    ): OfflinePairingRuntime
}

internal interface OfflinePairingRuntime {
    val role: OfflinePairingApiRole
    val sessionId: String
    val qrJson: String?
    val job: Job
    fun confirm()
    suspend fun cancel()
    fun close()
}

internal fun defaultOfflinePairingRuntimeFactory(
    contextProvider: () -> Context,
): OfflinePairingRuntimeFactory = AndroidOfflinePairingRuntimeFactory(contextProvider)

/** Serial owner of exactly one provisional pairing runtime and child job. */
internal class OfflinePairingApiController(
    private val scope: CoroutineScope,
    private val factory: OfflinePairingRuntimeFactory,
    private val statusSink: (OfflinePairingPublicStatus) -> Unit,
) {
    private val monitor = Any()
    private var generation = 0L
    private var runtime: OfflinePairingRuntime? = null
    private var cancellingRuntime: OfflinePairingRuntime? = null
    private var status = idleStatus()

    fun start(displayName: String): String = synchronized(monitor) {
        ensureNoActiveSession()
        val normalizedName = requireDisplayName(displayName)
        val created = create(OfflinePairingApiRole.INITIATOR, OfflinePairingApiPhase.ADVERTISING) { sink ->
            factory.start(scope, normalizedName, sink)
        }
        val rawQr = created.qrJson ?: failCreated(created, OfflinePairingApiError.PAIR_RUNTIME_UNAVAILABLE)
        val decoded = decodeQr(rawQr)
        if (decoded.sessionId != created.sessionId || rawQr.encodeToByteArray().size > MAX_QR_BYTES) {
            failCreated(created, OfflinePairingApiError.PAIR_RUNTIME_UNAVAILABLE)
        }
        rawQr
    }

    fun join(qrJson: String, displayName: String) = synchronized(monitor) {
        ensureNoActiveSession()
        val qr = decodeQr(qrJson)
        val normalizedName = requireDisplayName(displayName)
        create(OfflinePairingApiRole.JOINER, OfflinePairingApiPhase.RESOLVING, qr.displayName) { sink ->
            factory.join(scope, qr, normalizedName, sink)
        }
        Unit
    }

    fun confirm(sessionId: String) = synchronized(monitor) {
        val active = requireExactSession(sessionId)
        try {
            active.confirm()
        } catch (error: OfflinePairingApiException) {
            terminate(active, error.error, cancelCoordinator = true)
            throw error
        } catch (_: Throwable) {
            val bounded = OfflinePairingApiError.PAIR_RUNTIME_UNAVAILABLE
            terminate(active, bounded, cancelCoordinator = true)
            throw OfflinePairingApiException(bounded)
        }
    }

    suspend fun cancel(sessionId: String) {
        val active = synchronized(monitor) {
            requireExactSession(sessionId).also { cancellingRuntime = it }
        }
        try {
            active.cancel()
        } catch (_: Throwable) {
            // Cancellation still closes the child job and transport. Provider
            // details are never allowed across the native boundary.
        } finally {
            synchronized(monitor) {
                if (cancellingRuntime === active) cancellingRuntime = null
                if (runtime === active) {
                    val terminal = OfflinePairingPublicStatus(
                        role = active.role,
                        phase = OfflinePairingApiPhase.IDLE,
                        sessionId = active.sessionId,
                        error = status.error ?: OfflinePairingApiError.CANCELLED,
                        peerDisplayName = null,
                        sas = null,
                        completed = false,
                    )
                    if (status != terminal) {
                        status = terminal
                        statusSink(terminal)
                    }
                }
                release(active, cancelCoordinator = false)
            }
        }
    }

    fun getStatus(): OfflinePairingPublicStatus = synchronized(monitor) { status }

    fun destroy() = synchronized(monitor) {
        runtime?.let { release(it, cancelCoordinator = false) }
        status = idleStatus()
    }

    suspend fun quiesceAndAwait() {
        val active = synchronized(monitor) { runtime?.also { cancellingRuntime = it } } ?: return
        runCatching { active.cancel() }
        synchronized(monitor) {
            if (cancellingRuntime === active) cancellingRuntime = null
            if (runtime === active) release(active, cancelCoordinator = false)
            status = idleStatus()
        }
    }

    private fun create(
        expectedRole: OfflinePairingApiRole,
        initialPhase: OfflinePairingApiPhase,
        peerDisplayName: String? = null,
        creator: ((OfflinePairingPublicStatus) -> Unit) -> OfflinePairingRuntime,
    ): OfflinePairingRuntime {
        val token = ++generation
        val buffered = mutableListOf<OfflinePairingPublicStatus>()
        var constructing = true
        val callback: (OfflinePairingPublicStatus) -> Unit = { update ->
            synchronized(monitor) {
                if (generation == token) {
                    if (constructing) buffered += update else acceptStatus(token, update)
                }
            }
        }
        val created = try {
            creator(callback)
        } catch (error: OfflinePairingApiException) {
            generation++
            throw error
        } catch (_: Throwable) {
            generation++
            throw OfflinePairingApiException(OfflinePairingApiError.PAIR_RUNTIME_UNAVAILABLE)
        }
        try {
            requireCanonicalSessionId(created.sessionId)
            if (created.role != expectedRole || !created.job.isActive) {
                failCreated(created, OfflinePairingApiError.PAIR_RUNTIME_UNAVAILABLE)
            }
            runtime = created
            status = OfflinePairingPublicStatus(
                expectedRole,
                initialPhase,
                created.sessionId,
                null,
                peerDisplayName,
                null,
                false,
            )
            statusSink(status)
            constructing = false
            observeCompletion(created, token)
            buffered.forEach { acceptStatus(token, it) }
            if (runtime !== created || generation != token || !created.job.isActive) {
                val retained = status.error ?: OfflinePairingApiError.PAIR_RUNTIME_UNAVAILABLE
                if (runtime === created) failCreated(created, retained)
                throw OfflinePairingApiException(retained)
            }
            return created
        } catch (error: OfflinePairingApiException) {
            throw error
        } catch (_: Throwable) {
            failCreated(created, OfflinePairingApiError.PAIR_RUNTIME_UNAVAILABLE)
        }
    }

    private fun acceptStatus(token: Long, update: OfflinePairingPublicStatus) {
        val active = runtime ?: return
        if (generation != token || update.role != active.role || update.sessionId != active.sessionId) return
        if (cancellingRuntime === active) return
        status = update
        statusSink(update)
        if (update.completed || (update.phase == OfflinePairingApiPhase.IDLE && update.error != null)) {
            if (cancellingRuntime !== active) release(active, cancelCoordinator = false)
        }
    }

    private fun observeCompletion(active: OfflinePairingRuntime, token: Long) {
        active.job.invokeOnCompletion {
            synchronized(monitor) {
                if (generation != token || runtime !== active || cancellingRuntime === active) return@synchronized
                terminate(active, OfflinePairingApiError.PAIR_RUNTIME_UNAVAILABLE, cancelCoordinator = false)
            }
        }
    }

    /** Publish a secret-free terminal state before releasing this exact runtime. */
    private fun terminate(
        active: OfflinePairingRuntime,
        error: OfflinePairingApiError,
        cancelCoordinator: Boolean,
    ) {
        if (runtime !== active) return
        val terminal = OfflinePairingPublicStatus(
            role = active.role,
            phase = OfflinePairingApiPhase.IDLE,
            sessionId = active.sessionId,
            error = error,
            peerDisplayName = null,
            sas = null,
            completed = false,
        )
        if (status != terminal) {
            status = terminal
            statusSink(terminal)
        }
        release(active, cancelCoordinator)
    }

    private fun requireExactSession(sessionId: String): OfflinePairingRuntime {
        val active = runtime ?: throw OfflinePairingApiException(OfflinePairingApiError.PAIR_SESSION_NOT_FOUND)
        val canonical = try {
            requireCanonicalSessionId(sessionId)
            sessionId
        } catch (_: IllegalArgumentException) {
            throw OfflinePairingApiException(OfflinePairingApiError.PAIR_SESSION_MISMATCH)
        }
        if (active.sessionId != canonical) {
            throw OfflinePairingApiException(OfflinePairingApiError.PAIR_SESSION_MISMATCH)
        }
        return active
    }

    private fun release(active: OfflinePairingRuntime, cancelCoordinator: Boolean) {
        if (runtime !== active) return
        generation++
        runtime = null
        if (cancellingRuntime === active) cancellingRuntime = null
        if (cancelCoordinator) active.job.cancel()
        active.job.cancel()
        runCatching { active.close() }
    }

    private fun failCreated(created: OfflinePairingRuntime, error: OfflinePairingApiError): Nothing {
        if (runtime === created) runtime = null
        generation++
        created.job.cancel()
        runCatching { created.close() }
        throw OfflinePairingApiException(error)
    }

    private fun ensureNoActiveSession() {
        if (runtime != null) throw OfflinePairingApiException(OfflinePairingApiError.PAIR_SESSION_ACTIVE)
    }

    private fun decodeQr(raw: String): LanPairingQr {
        if (raw.encodeToByteArray().size > MAX_QR_BYTES) {
            throw OfflinePairingApiException(OfflinePairingApiError.PAIR_INVALID_QR)
        }
        return try {
            LanPairingCodec.decodeQr(raw)
        } catch (_: Throwable) {
            throw OfflinePairingApiException(OfflinePairingApiError.PAIR_INVALID_QR)
        }
    }

    private companion object {
        const val MAX_QR_BYTES = 4_096
    }
}

private val SAS_PATTERN = Regex("[0-9]{6}")
private const val MAX_DISPLAY_NAME_CODE_POINTS = 128
private const val MAX_DISPLAY_NAME_BYTES = 256

private fun requireDisplayName(value: String): String = try {
    normalizedDisplayName(value)
} catch (_: IllegalArgumentException) {
    throw OfflinePairingApiException(OfflinePairingApiError.PAIR_INVALID_DISPLAY_NAME)
}

private fun normalizedDisplayName(value: String): String {
    val normalized = Normalizer.normalize(value.trim(), Normalizer.Form.NFC)
    require(normalized.isNotBlank()) { "pair_invalid_display_name" }
    require(normalized.codePointCount(0, normalized.length) <= MAX_DISPLAY_NAME_CODE_POINTS) {
        "pair_invalid_display_name"
    }
    require(normalized.encodeToByteArray().size <= MAX_DISPLAY_NAME_BYTES) { "pair_invalid_display_name" }
    require(normalized.none { it == '\u0000' || it == '\r' || it == '\n' || Character.isISOControl(it) }) {
        "pair_invalid_display_name"
    }
    return normalized
}

private fun requireCanonicalSessionId(value: String) {
    require(value.length == 36 && UUID.fromString(value).toString() == value) { "pair_invalid_session" }
}

private fun idleStatus() = OfflinePairingPublicStatus(
    role = null,
    phase = OfflinePairingApiPhase.IDLE,
    sessionId = null,
    error = null,
    peerDisplayName = null,
    sas = null,
    completed = false,
)
