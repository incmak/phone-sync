package co.twinotify.core

import android.content.Context
import android.os.Handler
import android.os.Looper
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
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

    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    private fun requireContext(): Context =
        appContext.reactContext ?: error("no react context — module not initialised")

    override fun definition() = ModuleDefinition {
        Name("TwinotifyCore")

        AsyncFunction("getDeviceId") { promise: Promise ->
            CoroutineScope(Dispatchers.IO).launch {
                try { promise.resolve(DeviceIdentity.getOrCreate(requireContext())) }
                catch (e: Throwable) { promise.reject("DEVICE_ID", e.message ?: "err", e) }
            }
        }

        AsyncFunction("getPublicKeys") { promise: Promise ->
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    val (box, sign) = CryptoStore.loadOrGenerate(requireContext())
                    promise.resolve(mapOf(
                        "encPubkey"  to Base64.getEncoder().encodeToString(box.publicKey),
                        "signPubkey" to Base64.getEncoder().encodeToString(sign.publicKey),
                    ))
                } catch (e: Throwable) { promise.reject("PUBKEYS", e.message ?: "err", e) }
            }
        }

        AsyncFunction("startPairInitiator") { relayUrl: String, promise: Promise ->
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    val ctx = requireContext()
                    val (box, sign) = CryptoStore.loadOrGenerate(ctx)
                    val deviceId = DeviceIdentity.getOrCreate(ctx)
                    val token = PairPayload.newToken()
                    PairProtocol.initiate(relayUrl, token, deviceId, box.publicKey, sign.publicKey)
                    val payload = PairPayload(relayUrl, deviceId, box.publicKey, sign.publicKey, token).toJson()
                    promise.resolve(payload)
                } catch (e: Throwable) { promise.reject("PAIR_INIT", e.message ?: "err", e) }
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
            CoroutineScope(Dispatchers.IO).launch {
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

        AsyncFunction("deviceBCompletePairing") { relayUrl: String, pairToken: String, sigB64: String, promise: Promise ->
            CoroutineScope(Dispatchers.IO).launch {
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

        AsyncFunction("storePeerPubkeys") { encB64: String, signB64: String, peerDeviceId: String, promise: Promise ->
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    val ctx  = requireContext()
                    val enc  = Base64.getDecoder().decode(encB64)
                    val sign = Base64.getDecoder().decode(signB64)
                    PeerStore.save(ctx, PeerRecord(peerDeviceId, enc, sign))
                    promise.resolve(null)
                } catch (e: Throwable) { promise.reject("PEER_STORE", e.message ?: "err", e) }
            }
        }

        AsyncFunction("mintAuthJwt") { promise: Promise ->
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    val ctx = requireContext()
                    val (_, sign) = CryptoStore.loadOrGenerate(ctx)
                    val deviceId = DeviceIdentity.getOrCreate(ctx)
                    promise.resolve(JwtMinter.mint(deviceId, sign.secretKey))
                } catch (e: Throwable) { promise.reject("JWT", e.message ?: "err", e) }
            }
        }

        AsyncFunction("encryptToPeer") { plaintextB64: String, promise: Promise ->
            CoroutineScope(Dispatchers.IO).launch {
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
            CoroutineScope(Dispatchers.IO).launch {
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
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    val ctx = requireContext()
                    PeerStore.clear(ctx)
                    CryptoStore.rotate(ctx)
                    NonceSource.regenerate(ctx)
                    ReplayGuard.clear(ctx)
                    promise.resolve(null)
                } catch (e: Throwable) { promise.reject("UNPAIR", e.message ?: "err", e) }
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

            CoroutineScope(Dispatchers.IO).launch {
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
