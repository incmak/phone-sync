package co.twinotify.core.service

import android.content.Context
import android.util.Base64
import co.twinotify.core.crypto.CryptoStore
import co.twinotify.core.crypto.Encrypter
import co.twinotify.core.listener.NotifPostJson
import co.twinotify.core.storage.PeerStore
import co.twinotify.core.storage.ReplayGuard
import org.json.JSONObject

class InboundDispatcher(private val ctx: Context) {
    suspend fun dispatch(raw: String) {
        val env = try { EncryptedEnvelope.fromJson(raw) } catch (e: Throwable) {
            android.util.Log.w("Twinotify", "bad inbound envelope: ${e.message}")
            return
        }
        if (env.type != "enc") return
        // Replay check BEFORE decrypt — cheap rejection path
        if (ReplayGuard.seenOrMark(ctx, env.msgId)) return
        val peer = PeerStore.load(ctx) ?: run {
            android.util.Log.w("Twinotify", "no peer paired; dropping inbound")
            return
        }
        val (box, _) = CryptoStore.loadOrGenerate(ctx)
        val plaintext: ByteArray = try {
            Encrypter.decrypt(
                Base64.decode(env.ciphertextB64, Base64.DEFAULT),
                Base64.decode(env.nonceB64, Base64.DEFAULT),
                peer.encPubkey,
                box.secretKey,
            )
        } catch (e: Throwable) {
            android.util.Log.w("Twinotify", "decrypt failed: ${e.message}")
            return
        }
        val inner = try { JSONObject(plaintext.toString(Charsets.UTF_8)) }
            catch (e: Throwable) { return }
        val innerType = inner.optString("type")
        when (innerType) {
            "notif.post", "notif.update" -> handlePost(inner)
            "notif.cancel" -> handleCancel(inner)
            "unpair" -> handleUnpair()
            "ack" -> { /* Phase 3: drop */ }
            else -> android.util.Log.i("Twinotify", "unknown inner type: $innerType")
        }
    }

    private suspend fun handlePost(o: JSONObject) {
        val post = NotifPostJson(
            type = o.getString("type"),
            canon_id = o.getString("canon_id"),
            app_name = o.optString("app_name").takeIf { it.isNotEmpty() },
            package_name = o.optString("package_name"),
            id = o.optInt("id"),
            tag = o.optString("tag").takeIf { it.isNotEmpty() },
            title = o.optString("title").takeIf { it.isNotEmpty() },
            text = o.optString("text").takeIf { it.isNotEmpty() },
            sub_text = o.optString("sub_text").takeIf { it.isNotEmpty() },
            big_text = o.optString("big_text").takeIf { it.isNotEmpty() },
            visibility = o.optString("visibility", "private"),
            is_group_summary = o.optBoolean("is_group_summary"),
            is_ongoing = o.optBoolean("is_ongoing"),
            is_clearable = o.optBoolean("is_clearable", true),
            small_icon_png_b64 = o.optString("small_icon_png_b64").takeIf { it.isNotEmpty() },
            large_icon_png_b64 = o.optString("large_icon_png_b64").takeIf { it.isNotEmpty() },
            ts = o.optLong("ts"),
        )
        // Record latency from envelope timestamp (stamped by sender) to receive time.
        val latencyMs = System.currentTimeMillis() - post.ts
        co.twinotify.core.metrics.MetricsStore.recordLatency(ctx, latencyMs)

        if (post.is_group_summary) return   // spec §4.7.2 — drop summary, mirror children only
        MirrorPoster.post(ctx, post)
    }

    private suspend fun handleCancel(o: JSONObject) {
        val canonId = o.getString("canon_id")
        MirrorDismisser.dismiss(ctx, canonId)
    }

    private suspend fun handleUnpair() {
        android.util.Log.i("Twinotify", "peer initiated unpair — wiping local state")
        // Wipe inside NonCancellable + signal JS BEFORE stopService. If this runs on the
        // SyncService's scope and we stopService first, onDestroy can cancel the scope mid-wipe
        // at the next DataStore suspension point, leaving a partial wipe (peer cleared but
        // crypto not rotated). NonCancellable protects the wipe sequence from scope cancellation;
        // stopService after so the service exits on the now-cleared state.
        kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
            co.twinotify.core.pairing.UnpairOps.wipeAll(ctx)
            SyncServiceStatus.notifyPeerUnpaired()
        }
        val stopIntent = android.content.Intent(ctx, co.twinotify.core.service.SyncService::class.java)
        ctx.stopService(stopIntent)
    }
}
