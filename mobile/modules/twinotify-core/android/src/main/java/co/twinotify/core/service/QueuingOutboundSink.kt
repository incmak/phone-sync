package co.twinotify.core.service

import android.content.Context
import android.util.Base64
import co.twinotify.core.crypto.CryptoStore
import co.twinotify.core.crypto.Encrypter
import co.twinotify.core.crypto.NonceSource
import co.twinotify.core.listener.NotifPostJson
import co.twinotify.core.listener.OutboundSink
import co.twinotify.core.storage.PeerStore
import org.json.JSONObject
import java.util.UUID

class QueuingOutboundSink(
    private val ctx: Context,
    private val queue: OutboundQueue,
    private val onEnqueued: () -> Unit = {},
) : OutboundSink {

    override suspend fun enqueuePost(post: NotifPostJson) {
        val inner = postToJson(post)
        encryptAndEnqueue(inner)
    }

    override suspend fun enqueueCancel(canonId: String, reason: String, originDevice: String, tsMs: Long) {
        val inner = JSONObject().apply {
            put("v", 1)
            put("type", "notif.cancel")
            put("canon_id", canonId)
            put("reason", reason)
            put("ts", tsMs)
        }.toString()
        encryptAndEnqueue(inner)
    }

    override suspend fun enqueueUnpair(reason: String, originDevice: String, tsMs: Long) {
        val inner = JSONObject().apply {
            put("v", 1)
            put("type", "unpair")
            put("reason", reason)
            put("origin_device", originDevice)
            put("ts", tsMs)
        }.toString()
        encryptAndEnqueue(inner)
    }

    private suspend fun encryptAndEnqueue(plaintext: String) {
        val peer = PeerStore.load(ctx) ?: return  // no peer — drop silently
        val (box, _) = CryptoStore.loadOrGenerate(ctx)
        val nonce = NonceSource.next(ctx)
        val ct = Encrypter.encrypt(plaintext.toByteArray(Charsets.UTF_8), nonce, peer.encPubkey, box.secretKey)
        queue.enqueue(
            ciphertextB64 = Base64.encodeToString(ct, Base64.NO_WRAP),
            nonceB64 = Base64.encodeToString(nonce, Base64.NO_WRAP),
            msgId = UUID.randomUUID().toString(),
        )
        onEnqueued()
    }

    private fun postToJson(post: NotifPostJson): String = JSONObject().apply {
        put("v", post.v)
        put("type", post.type)
        put("canon_id", post.canon_id)
        put("app_name", post.app_name ?: JSONObject.NULL)
        put("package_name", post.package_name)
        put("id", post.id)
        put("tag", post.tag ?: JSONObject.NULL)
        put("title", post.title ?: JSONObject.NULL)
        put("text", post.text ?: JSONObject.NULL)
        put("sub_text", post.sub_text ?: JSONObject.NULL)
        put("big_text", post.big_text ?: JSONObject.NULL)
        put("visibility", post.visibility)
        put("is_group_summary", post.is_group_summary)
        put("is_ongoing", post.is_ongoing)
        put("is_clearable", post.is_clearable)
        put("small_icon_png_b64", post.small_icon_png_b64 ?: JSONObject.NULL)
        put("large_icon_png_b64", post.large_icon_png_b64 ?: JSONObject.NULL)
        put("ts", post.ts)
    }.toString()
}
