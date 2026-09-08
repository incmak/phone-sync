package co.twinotify.core.lan

import android.os.Bundle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import co.twinotify.core.crypto.WrappedKeys
import co.twinotify.core.crypto.Encrypter
import co.twinotify.core.protocol.EncryptedEnvelope
import co.twinotify.core.protocol.EnvelopeAuthenticator
import co.twinotify.core.protocol.InnerEventV2
import co.twinotify.core.protocol.PayloadDecryptor
import co.twinotify.core.protocol.ProtocolJson
import co.twinotify.core.pairing.lan.LanIdentityStore
import co.twinotify.core.pairing.lan.LanTlsContextFactory
import java.net.InetAddress
import java.util.Base64
import java.util.UUID
import java.security.MessageDigest
import java.security.SecureRandom
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLSocket
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assume.assumeTrue
import org.junit.runner.RunWith
import org.json.JSONObject

/** Explicit host-driven test; ordinary instrumentation does not wait for a Mac. */
@RunWith(AndroidJUnit4::class)
class MacLanInteropTest {
    @Test
    fun authenticateMacAndPreserveFrames() = runBlocking {
        val arguments = InstrumentationRegistry.getArguments()
        val raw = arguments.getString("mac_lan_peer")
        assumeTrue(raw != null)
        val mac = JSONObject(raw!!)
        val peerPin = mac.getString("tls_pin").chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        val peerSigningKey = Base64.getDecoder().decode(mac.getString("signing_key"))
        val keys = WrappedKeys.generateSign()
        val box = WrappedKeys.generateBox()
        val macBox = Base64.getDecoder().decode(mac.getString("encryption_key"))
        val identity = LanIdentityStore.loadOrCreate()
        val deviceId = "dev-00000000-0000-0000-0000-000000000042"
        val material = LanBootstrapCrypto.derive(LanBootstrapIdentity(deviceId, box.publicKey, keys.publicKey),
            LanBootstrapIdentity(mac.getString("device_id"), macBox, peerSigningKey), box.secretKey)
        val server = (LanTlsContextFactory.serverContext(peerPin).serverSocketFactory
            .createServerSocket(0, 1, InetAddress.getLoopbackAddress()) as SSLServerSocket).apply {
            needClientAuth = true
            soTimeout = 60_000
        }
        try {
            InstrumentationRegistry.getInstrumentation().sendStatus(0, Bundle().apply {
                putString("lan_interop", JSONObject().put("port", server.localPort)
                    .put("device_id", deviceId).put("signing_key", Base64.getEncoder().encodeToString(keys.publicKey))
                    .put("encryption_key", Base64.getEncoder().encodeToString(box.publicKey))
                    .put("binding_context", Base64.getEncoder().encodeToString(material.bindingContextSha256))
                    .put("tls_pin", identity.spkiSha256.joinToString("") { "%02x".format(it) }).toString())
            })
            val socket = server.accept() as SSLSocket
            val tls = JsseLanTlsSocket(socket)
            withTimeout(30_000) {
                tls.startHandshake()
                assertEquals(LanTlsExporter.ALPN, socket.applicationProtocol)
                SignedLanSocketHandshake(LanHandshake(deviceId, mac.getString("device_id"), keys.secretKey,
                    peerSigningKey, LanConnectionRole.ACCEPTOR, LanFrame.VERSION)).authenticate(tls)
                assertEquals(LanFrame.Ping(42), tls.readFrame())
                tls.writeFrame(LanFrame.Pong(42))
                val expected = " {\"unicode\":\"😀\", \"escaped\":\"a\\nb\"} \n".encodeToByteArray()
                assertEquals(LanFrame.Put(expected), tls.readFrame())
                tls.writeFrame(LanFrame.Put(expected))
                val now = System.currentTimeMillis()
                val id = UUID.randomUUID().toString()
                val payload = JSONObject().put("v", 1).put("type", "notif.post").put("canon_id", "lan-interop")
                    .put("app_name", "LAN fixture").put("package_name", "co.twinotify.fixture").put("id", 42)
                    .put("tag", JSONObject.NULL).put("title", "Direct delivery").put("text", "Synthetic Unicode 😀")
                    .put("sub_text", JSONObject.NULL).put("big_text", JSONObject.NULL).put("visibility", "private")
                    .put("is_group_summary", false).put("is_ongoing", false).put("is_clearable", true)
                    .put("small_icon_png_b64", JSONObject.NULL).put("large_icon_png_b64", JSONObject.NULL).put("ts", now)
                val inner = ProtocolJson.encodeInner(InnerEventV2(id, deviceId, "notif.post", "lan-interop", 1L, now, now + 300_000, payload.toString()))
                val nonce = ByteArray(24).also(SecureRandom()::nextBytes)
                val ciphertext = Encrypter.encrypt(inner.encodeToByteArray(), nonce, macBox, box.secretKey)
                val envelope = ProtocolJson.encodeEnvelope(EncryptedEnvelope(2, id, deviceId, now,
                    Base64.getEncoder().encodeToString(nonce), Base64.getEncoder().encodeToString(ciphertext))).encodeToByteArray()
                val digest = MessageDigest.getInstance("SHA-256").digest(envelope).joinToString("") { "%02x".format(it) }
                val opener = EnvelopeAuthenticator(PayloadDecryptor { outer ->
                    Encrypter.decrypt(Base64.getDecoder().decode(outer.ciphertextB64), Base64.getDecoder().decode(outer.nonceB64), macBox, box.secretKey)
                }, mac.getString("device_id"))
                var receiptId: String? = null
                repeat(2) {
                    tls.writeFrame(LanFrame.Put(envelope))
                    var custody = false
                    var receipt = false
                    while (!custody || !receipt) {
                        when (val frame = tls.readFrame()) {
                            is LanFrame.Accepted -> {
                                assertEquals(id, frame.msgId); assertEquals(digest, frame.envelopeSha256); custody = true
                            }
                            is LanFrame.Put -> {
                                val opened = opener.open(frame.envelope.decodeToString())
                                assertEquals("peer.receipt", opened.inner.type)
                                assertEquals(id, opened.inner.payloadObject().getString("acked_msg_id"))
                                assertEquals(digest, opened.inner.payloadObject().getString("envelope_sha256"))
                                assertEquals("applied", opened.inner.payloadObject().getString("status"))
                                if (receiptId == null) receiptId = opened.inner.msgId else assertEquals(receiptId, opened.inner.msgId)
                                tls.writeFrame(LanFrame.Accepted(opened.inner.msgId, opened.envelopeSha256)); receipt = true
                            }
                            is LanFrame.Ping -> tls.writeFrame(LanFrame.Pong(frame.token))
                            else -> error("unexpected LAN delivery frame")
                        }
                    }
                }
                tls.writeFrame(LanFrame.Close("test_complete"))
            }
            tls.close()
        } finally {
            server.close()
            keys.secretKey.fill(0)
            box.secretKey.fill(0)
        }
    }
}
