package co.twinotify.core

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.core.content.edit
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
import co.twinotify.core.storage.DeviceIdentity
import co.twinotify.core.storage.PeerRecord
import co.twinotify.core.storage.PeerStore
import co.twinotify.core.storage.ReplayGuard
import kotlinx.coroutines.Dispatchers
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

class TwinotifyCoreModule : Module() {

    private val moduleScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    private fun requireContext(): Context =
        appContext.reactContext ?: error("no react context — module not initialised")

    override fun definition() = ModuleDefinition {
        Name("TwinotifyCore")

        Events("onSyncStatus", "onPeerUnpair")

        OnCreate {
            try {
                co.twinotify.core.filter.DenylistLoader.load(requireContext())
            } catch (e: SecurityException) {
                throw e   // tamper — abort module init so app visibly fails
            } catch (e: Throwable) {
                android.util.Log.e("Twinotify", "denylist load failed (non-tamper): ${e.message}", e)
            }
            moduleScope.launch {
                kotlinx.coroutines.flow.combine(
                    co.twinotify.core.service.SyncServiceStatus.state,
                    co.twinotify.core.service.SyncServiceStatus.queuedCount,
                ) { s, q -> mapOf("state" to s.name, "queuedCount" to q) }
                    .collect { sendEvent("onSyncStatus", it) }
            }
            moduleScope.launch {
                co.twinotify.core.service.SyncServiceStatus.peerUnpaired.collect {
                    sendEvent("onPeerUnpair", emptyMap<String, Any>())
                }
            }
        }

        OnDestroy {
            moduleScope.cancel()
        }

        AsyncFunction("startSyncService") { relayUrl: String, promise: Promise ->
            try {
                val ctx = requireContext()
                ctx.getSharedPreferences("twinotify_service", Context.MODE_PRIVATE)
                    .edit { putString("relay_url", relayUrl) }
                val intent = android.content.Intent(ctx, co.twinotify.core.service.SyncService::class.java).apply {
                    action = co.twinotify.core.service.SyncService.ACTION_START
                    putExtra(co.twinotify.core.service.SyncService.EXTRA_RELAY_URL, relayUrl)
                }
                ctx.startForegroundService(intent)
                promise.resolve(null)
            } catch (e: Throwable) { promise.reject("START_SVC", e.message ?: "err", e) }
        }

        AsyncFunction("stopSyncService") { promise: Promise ->
            try {
                val ctx = requireContext()
                val intent = android.content.Intent(ctx, co.twinotify.core.service.SyncService::class.java)
                ctx.stopService(intent)
                promise.resolve(null)
            } catch (e: Throwable) { promise.reject("STOP_SVC", e.message ?: "err", e) }
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
                promise.resolve(mapOf(
                    "state" to co.twinotify.core.service.SyncServiceStatus.state.value.name,
                    "queuedCount" to co.twinotify.core.service.SyncServiceStatus.queuedCount.value,
                ))
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
                    val frame = co.twinotify.core.pairing.PairNotifyClient.awaitFrame(
                        relayUrl, pairToken, role = "A", expectedType = "peer.hello",
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
                    val sig = co.twinotify.core.pairing.PairNotifyClient.awaitSig(relayUrl, pairToken)
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
                    // Try to notify peer first (best effort, before keys are rotated)
                    val peer = PeerStore.load(ctx)
                    if (peer != null) {
                        try {
                            val deviceId = DeviceIdentity.getOrCreate(ctx)
                            co.twinotify.core.listener.DurableOutboundSink.get(ctx)
                                .enqueueUnpair("user_initiated", deviceId, System.currentTimeMillis())
                            // Wait up to 3s for queue to drain
                            val deadline = System.currentTimeMillis() + 3_000L
                            while (System.currentTimeMillis() < deadline) {
                                if (co.twinotify.core.service.SyncServiceStatus.queuedCount.value == 0) break
                                kotlinx.coroutines.delay(100)
                            }
                        } catch (e: Throwable) {
                            android.util.Log.w("Twinotify", "unpair notify peer failed: ${e.message}")
                            // Continue with local wipe regardless
                        }
                    }
                    // Stop sync service before wiping state
                    val stopIntent = android.content.Intent(ctx, co.twinotify.core.service.SyncService::class.java)
                    ctx.stopService(stopIntent)
                    // Wipe all paired state
                    co.twinotify.core.pairing.UnpairOps.wipeAll(ctx)
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
}
