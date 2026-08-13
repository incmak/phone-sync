package co.twinotify.core.service

import android.content.Context
import android.util.Base64
import co.twinotify.core.crypto.CryptoStore
import co.twinotify.core.crypto.Encrypter
import co.twinotify.core.listener.NotifPostJson
import co.twinotify.core.protocol.EnvelopeAuthenticator
import co.twinotify.core.protocol.PayloadDecryptor
import co.twinotify.core.protocol.ProtocolJson
import co.twinotify.core.storage.DeviceIdentity
import co.twinotify.core.storage.InboundMessage
import co.twinotify.core.storage.NotificationDb
import co.twinotify.core.storage.PeerStore
import co.twinotify.core.storage.ReplayGuard
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject

class InboundDispatcher(
    private val ctx: Context,
    private val snapshotCoordinator: SnapshotCoordinator = SnapshotCoordinator(
        NotificationDb.get(ctx.applicationContext).reliableDeliveryDao(),
    ),
) {
    private val reliableDao by lazy { NotificationDb.get(ctx.applicationContext).reliableDeliveryDao() }
    private val outbox by lazy { OutboxRepository(DaoOutboxStore(reliableDao)) }
    private val stateMutex = Mutex()
    private val snapshots get() = snapshotCoordinator

    suspend fun dispatch(raw: String) {
        val parsed = runCatching { JSONObject(raw) }.getOrNull()
        if (parsed?.optInt("v", 1) == ProtocolJson.VERSION) {
            dispatchV2(raw)
            return
        }
        dispatchV1(raw)
    }

    /** Legacy v1 compatibility path. New relay deliveries use [dispatchV2]. */
    private suspend fun dispatchV1(raw: String) {
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

    private suspend fun dispatchV2(raw: String) {
        val peer = PeerStore.load(ctx) ?: run {
            android.util.Log.w("Twinotify", "no peer paired; dropping v2 inbound")
            return
        }
        val (cryptoBox, _) = CryptoStore.loadOrGenerate(ctx)
        val opened = try {
            EnvelopeAuthenticator(
                decryptor = PayloadDecryptor { envelope ->
                    Encrypter.decrypt(
                        ct = Base64.decode(envelope.ciphertextB64, Base64.DEFAULT),
                        nonce = Base64.decode(envelope.nonceB64, Base64.DEFAULT),
                        peerPubkey = peer.encPubkey,
                        ownSecret = cryptoBox.secretKey,
                    )
                },
                peerDeviceId = peer.deviceId,
            ).open(raw)
        } catch (error: Throwable) {
            android.util.Log.w("Twinotify", "v2 authentication failed: ${error.message}")
            return
        }
        val inner = opened.inner
        if (inner.type == "peer.receipt") {
            val payload = inner.payloadObject()
            val status = payload.getString("status")
            val reason = payload.optString("reason").takeIf { it.isNotEmpty() }
            outbox.onPeerReceipt(
                ackedMsgId = payload.getString("acked_msg_id"),
                envelopeSha256 = payload.getString("envelope_sha256"),
                status = status,
                reason = reason,
            )
            SyncServiceStatus.setLastReceiptAt(System.currentTimeMillis())
            SyncServiceStatus.setQueueStats(reliableDao.activeOutboundCount(), reliableDao.activeOutboundBytes())
            return
        }
        if (inner.type == "state.digest") {
            runCatching { snapshots.onDigest(inner) }
                .onFailure { android.util.Log.w("Twinotify", "snapshot digest rejected", it) }
            return
        }
        if (inner.type == "state.snapshot.begin") {
            runCatching { snapshots.onBegin(inner) }
                .onFailure { android.util.Log.w("Twinotify", "snapshot begin rejected", it) }
            return
        }
        if (inner.type == "state.snapshot.item") {
            runCatching { snapshots.onItem(inner) }
                .onFailure { android.util.Log.w("Twinotify", "snapshot item rejected", it) }
            return
        }
        if (inner.type == "state.snapshot.end") {
            val result = runCatching { snapshots.onEnd(inner) }
                .onFailure { android.util.Log.w("Twinotify", "snapshot end rejected", it) }
                .getOrNull()
            if (result is SnapshotConvergence.Committed) {
                val localDeviceId = DeviceIdentity.getOrCreate(ctx)
                NotificationMaterializer(
                    dao = reliableDao,
                    port = DefaultAndroidNotificationPort(ctx, localDeviceId, reliableDao),
                    receiptFactory = DurableReceiptFactory(ctx),
                    localDeviceId = localDeviceId,
                    retryScheduler = materializationStartupScheduler(ctx),
                ).materializePending()
            }
            return
        }
        if (inner.type !in setOf("notif.post", "notif.update", "notif.cancel")) {
            // Receipt/control processing belongs to the transport task. Preserve the authenticated
            // event in the journal only when it has a canonical desired state.
            return
        }
        stateMutex.withLock {
            val canonId = requireNotNull(inner.canonId)
            val localDeviceId = DeviceIdentity.getOrCreate(ctx)
            val current = reliableDao.canonical(canonId)
            val nextMirrorLocalId = reliableDao.nextMirrorLocalId()
            val allocator = LocalIdAllocator { nextMirrorLocalId }
            val authorizedEvent = NotificationStateReducer.authorizePeerCancel(
                current = current,
                event = inner,
                authenticatedPeerId = peer.deviceId,
            ) ?: run {
                android.util.Log.w("Twinotify", "v2 cancel origin is not the paired peer")
                return@withLock
            }
            val desired = try {
                when (
                    val reduction = NotificationStateReducer.reduce(
                        current = current,
                        event = authorizedEvent,
                        localDeviceId = localDeviceId,
                        allocator = allocator,
                    )
                ) {
                    is Reduction.Apply -> reduction.state
                    is Reduction.Stale -> reduction.state
                }
            } catch (error: Throwable) {
                android.util.Log.w("Twinotify", "v2 desired-state reduction rejected event", error)
                return@withLock
            }
            val inbound = InboundMessage(
                msgId = inner.msgId,
                originDevice = inner.originDevice,
                envelopeSha256 = opened.envelopeSha256,
                eventType = inner.type,
                canonId = canonId,
                sequence = inner.sequence,
                outcome = "PENDING_PLATFORM",
                committedAt = System.currentTimeMillis(),
                appliedAt = null,
                receiptMsgId = null,
                relayAckState = "NONE",
            )
            var commitDesired = desired
            var commitResult: co.twinotify.core.storage.InboundDesiredCommitResult? = null
            for (attempt in 0 until 3) {
                commitResult = reliableDao.commitInboundDesired(inbound, commitDesired)
                if (commitResult !is co.twinotify.core.storage.InboundDesiredCommitResult.MirrorIdentityCollision) {
                    break
                }
                val retryId = reliableDao.nextMirrorLocalId()
                commitDesired = commitDesired.copy(mirrorLocalId = retryId)
            }
            when (val result = commitResult) {
                is co.twinotify.core.storage.InboundDesiredCommitResult.Committed,
                is co.twinotify.core.storage.InboundDesiredCommitResult.Duplicate,
                -> NotificationMaterializer(
                    dao = reliableDao,
                    port = DefaultAndroidNotificationPort(ctx, localDeviceId, reliableDao),
                    receiptFactory = DurableReceiptFactory(ctx),
                    localDeviceId = localDeviceId,
                    retryScheduler = materializationStartupScheduler(ctx),
                ).materializePending()
                else -> Unit
            }
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
        co.twinotify.core.pairing.UnpairWorkflow.execute(
            // This callback is invoked from the relay collection itself. The service must
            // cancel that job, but cannot join its own coroutine without deadlocking.
            stopAndAwait = { SyncService.shutdownActive(ctx, fromRelayJob = true) },
            revokePeer = {},
            wipeLocal = {
                co.twinotify.core.pairing.UnpairOps.wipeAll(ctx)
                SyncServiceStatus.notifyPeerUnpaired()
            },
        )
    }
}
