package co.twinotify.core.service

import android.content.Context
import android.content.Intent
import android.util.Base64
import co.twinotify.core.crypto.CryptoStore
import co.twinotify.core.crypto.Encrypter
import co.twinotify.core.call.CallDirection
import co.twinotify.core.call.CallStateEvent
import co.twinotify.core.call.CallStateReducer
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
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean
import org.json.JSONObject

/**
 * What a route may tell the peer about one inbound envelope. `Accepted` and
 * `Duplicate` are only ever returned after durable custody exists, so a route
 * may acknowledge on either. `Rejected` carries a stable code and never means
 * "retry": the envelope was refused, so a route closes rather than acknowledging.
 */
sealed interface InboundDispatchResult {
    data class Accepted(val msgId: String, val envelopeSha256: String) : InboundDispatchResult
    data class Duplicate(val msgId: String, val envelopeSha256: String) : InboundDispatchResult
    class AcceptedAfterCustody(
        val msgId: String,
        val envelopeSha256: String,
        private val finalizer: suspend () -> Unit,
    ) : InboundDispatchResult {
        private val finalized = AtomicBoolean(false)

        suspend fun finalizeAfterCustody() {
            if (!finalized.compareAndSet(false, true)) return
            withContext(NonCancellable) { finalizer() }
        }
    }
    data class Rejected(val code: String) : InboundDispatchResult
}

internal suspend fun executePeerUnpairAndRequestServiceStop(
    unpair: suspend () -> Unit,
    requestServiceStop: suspend () -> Unit,
) {
    unpair()
    requestServiceStop()
}

/**
 * Runs only after v2 envelope authentication has succeeded. Returning acceptance after the
 * production peer-unpair handler completes prevents an acknowledgement from claiming a wipe that
 * did not happen; cancellation deliberately escapes so the route cannot acknowledge it.
 */
internal suspend fun dispatchAuthenticatedV2Unpair(
    eventType: String,
    msgId: String,
    envelopeSha256: String,
    preparePeerUnpair: suspend () -> Unit,
    finalizeServiceStop: suspend () -> Unit,
): InboundDispatchResult? {
    if (eventType != "unpair") return null
    preparePeerUnpair()
    return InboundDispatchResult.AcceptedAfterCustody(msgId, envelopeSha256, finalizeServiceStop)
}

class InboundDispatcher(
    private val ctx: Context,
    private val snapshotCoordinator: SnapshotCoordinator = SnapshotCoordinator(
        NotificationDb.get(ctx.applicationContext).reliableDeliveryDao(),
    ),
    private val onAuthenticatedEvent: (String) -> Unit = {},
) {
    private val reliableDao by lazy { NotificationDb.get(ctx.applicationContext).reliableDeliveryDao() }
    private val outbox by lazy { OutboxRepository(DaoOutboxStore(reliableDao)) }
    private val stateMutex = Mutex()
    private val snapshots get() = snapshotCoordinator

    /**
     * Relay callers may ignore the result; their acknowledgement semantics are
     * unchanged. A direct route uses it, and must never acknowledge before the
     * Room transaction boundary this returns from.
     */
    suspend fun dispatch(raw: String): InboundDispatchResult {
        val parsed = runCatching { JSONObject(raw) }.getOrNull()
        if (parsed?.optInt("v", 1) == ProtocolJson.VERSION) {
            return dispatchV2(raw)
        }
        dispatchV1(raw)
        // v1 has no authenticated msg_id or digest to acknowledge. It stays a
        // relay-only compatibility path; a direct route must refuse it.
        return InboundDispatchResult.Rejected("legacy_v1")
    }

    /** Legacy v1 compatibility path. New relay deliveries use [dispatchV2]. */
    private suspend fun dispatchV1(raw: String) {
        val env = try { EncryptedEnvelope.fromJson(raw) } catch (e: Throwable) {
            android.util.Log.w("Twinotify", "bad legacy inbound envelope")
            return
        }
        if (env.type != "enc") return
        LegacyInboundProcessor(
            loadPeer = {
                PeerStore.load(ctx) ?: run {
                    android.util.Log.w("Twinotify", "no peer paired; dropping legacy inbound")
                    null
                }
            },
            decrypt = { envelope, peer ->
                val (box, _) = CryptoStore.loadOrGenerate(ctx)
                Encrypter.decrypt(
                    Base64.decode(envelope.ciphertextB64, Base64.DEFAULT),
                    Base64.decode(envelope.nonceB64, Base64.DEFAULT),
                    peer.encPubkey,
                    box.secretKey,
                )
            },
            parseInner = { plaintext ->
                JSONObject(plaintext.toString(Charsets.UTF_8))
            },
            seenOrMark = { msgId -> ReplayGuard.seenOrMark(ctx, msgId) },
            dispatchInner = { inner -> dispatchLegacyInner(inner) },
        ).process(env)
    }

    private suspend fun dispatchLegacyInner(inner: JSONObject) {
        val innerType = inner.optString("type")
        when (innerType) {
            "notif.post", "notif.update" -> handlePost(inner)
            "notif.cancel" -> handleCancel(inner)
            "unpair" -> handleUnpair()
            "ack" -> { /* Phase 3: drop */ }
            else -> android.util.Log.i("Twinotify", "unknown legacy inner type")
        }
    }

    private suspend fun dispatchV2(raw: String): InboundDispatchResult {
        val peer = PeerStore.load(ctx) ?: run {
            android.util.Log.w("Twinotify", "no peer paired; dropping v2 inbound")
            return InboundDispatchResult.Rejected("no_peer")
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
            return InboundDispatchResult.Rejected("auth_failed")
        }
        val inner = opened.inner
        dispatchAuthenticatedV2Unpair(
            eventType = inner.type,
            msgId = inner.msgId,
            envelopeSha256 = opened.envelopeSha256,
            preparePeerUnpair = ::preparePeerUnpair,
            finalizeServiceStop = ::requestServiceStopAfterPeerUnpair,
        )?.let {
            onAuthenticatedEvent(inner.type)
            return it
        }
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
            onAuthenticatedEvent(inner.type)
            return InboundDispatchResult.Accepted(inner.msgId, opened.envelopeSha256)
        }
        if (inner.type == "state.digest") {
            runCatching { snapshots.onDigest(inner) }
                .onFailure { android.util.Log.w("Twinotify", "snapshot digest rejected", it) }
            onAuthenticatedEvent(inner.type)
            return InboundDispatchResult.Accepted(inner.msgId, opened.envelopeSha256)
        }
        if (inner.type == "state.snapshot.begin") {
            runCatching { snapshots.onBegin(inner) }
                .onFailure { android.util.Log.w("Twinotify", "snapshot begin rejected", it) }
            onAuthenticatedEvent(inner.type)
            return InboundDispatchResult.Accepted(inner.msgId, opened.envelopeSha256)
        }
        if (inner.type == "state.snapshot.item") {
            runCatching { snapshots.onItem(inner) }
                .onFailure { android.util.Log.w("Twinotify", "snapshot item rejected", it) }
            onAuthenticatedEvent(inner.type)
            return InboundDispatchResult.Accepted(inner.msgId, opened.envelopeSha256)
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
            onAuthenticatedEvent(inner.type)
            return InboundDispatchResult.Accepted(inner.msgId, opened.envelopeSha256)
        }
        if (inner.type == "call.state") {
            return dispatchCallState(inner, opened.envelopeSha256, peer.deviceId)
        }
        if (inner.type !in setOf("notif.post", "notif.update", "notif.cancel")) {
            // Receipt/control processing belongs to the transport task. Preserve the authenticated
            // event in the journal only when it has a canonical desired state.
            return InboundDispatchResult.Rejected("unsupported_event")
        }
        return stateMutex.withLock {
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
                return@withLock InboundDispatchResult.Rejected("unauthorized_cancel")
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
                return@withLock InboundDispatchResult.Rejected("reduction_rejected")
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
            // Every branch below reports state that is already durable, or refuses.
            // Nothing here may report acceptance for a row that was not committed.
            when (val result = commitResult) {
                is co.twinotify.core.storage.InboundDesiredCommitResult.Committed,
                is co.twinotify.core.storage.InboundDesiredCommitResult.Duplicate,
                -> {
                    NotificationMaterializer(
                        dao = reliableDao,
                        port = DefaultAndroidNotificationPort(ctx, localDeviceId, reliableDao),
                        receiptFactory = DurableReceiptFactory(ctx),
                        localDeviceId = localDeviceId,
                        retryScheduler = materializationStartupScheduler(ctx),
                    ).materializePending()
                    // Platform materialization may still fail and retry. Custody is
                    // already durable, so the peer is told to stop resending.
                    if (result is co.twinotify.core.storage.InboundDesiredCommitResult.Duplicate) {
                        InboundDispatchResult.Duplicate(inner.msgId, opened.envelopeSha256)
                    } else {
                        InboundDispatchResult.Accepted(inner.msgId, opened.envelopeSha256)
                    }
                }
                // Stale still inserts the inbound row, so custody exists.
                is co.twinotify.core.storage.InboundDesiredCommitResult.Stale ->
                    InboundDispatchResult.Accepted(inner.msgId, opened.envelopeSha256)
                is co.twinotify.core.storage.InboundDesiredCommitResult.IdConflict ->
                    InboundDispatchResult.Rejected("id_conflict")
                is co.twinotify.core.storage.InboundDesiredCommitResult.MirrorIdentityCollision ->
                    InboundDispatchResult.Rejected("mirror_identity_collision")
                null -> InboundDispatchResult.Rejected("commit_failed")
            }
        }
    }

    private suspend fun dispatchCallState(
        inner: co.twinotify.core.protocol.InnerEventV2,
        envelopeSha256: String,
        authenticatedPeerId: String,
    ): InboundDispatchResult {
        return stateMutex.withLock {
            if (inner.originDevice != authenticatedPeerId) {
                android.util.Log.w("Twinotify", "call state origin is not the paired peer")
                return@withLock InboundDispatchResult.Rejected("unauthorized_call_origin")
            }
            val payload = inner.payloadObject()
            val event = CallStateEvent(
                callSessionId = payload.getString("call_session_id"),
                state = payload.getString("state"),
                direction = when (payload.getString("direction")) {
                    "incoming" -> CallDirection.INCOMING
                    "outgoing" -> CallDirection.OUTGOING
                    else -> CallDirection.UNKNOWN
                },
                sequence = requireNotNull(inner.sequence),
            )
            val localDeviceId = DeviceIdentity.getOrCreate(ctx)
            val current = reliableDao.canonical(requireNotNull(inner.canonId))
            val nextMirrorId = reliableDao.nextMirrorLocalId()
            val reduction = runCatching {
                CallStateReducer.reduceInbound(
                    current = current,
                    originDevice = inner.originDevice,
                    event = event,
                    localDeviceId = localDeviceId,
                    allocator = LocalIdAllocator { nextMirrorId },
                    updatedAt = inner.createdAt,
                )
            }.getOrElse {
                android.util.Log.w("Twinotify", "call state rejected", it)
                return@withLock InboundDispatchResult.Rejected("call_state_rejected")
            }
            val inbound = InboundMessage(
                msgId = inner.msgId,
                originDevice = inner.originDevice,
                envelopeSha256 = envelopeSha256,
                eventType = inner.type,
                canonId = inner.canonId,
                sequence = inner.sequence,
                outcome = if (reduction is co.twinotify.core.call.CallReduction.Stale) "STALE" else "PENDING_PLATFORM",
                committedAt = System.currentTimeMillis(),
                appliedAt = null,
                receiptMsgId = null,
                relayAckState = "NONE",
            )
            val commit = reliableDao.commitInboundDesired(
                inbound,
                (reduction as? co.twinotify.core.call.CallReduction.Apply)?.state,
            )
            if (commit is co.twinotify.core.storage.InboundDesiredCommitResult.Committed ||
                commit is co.twinotify.core.storage.InboundDesiredCommitResult.Duplicate
            ) {
                NotificationMaterializer(
                    dao = reliableDao,
                    port = DefaultAndroidNotificationPort(ctx, localDeviceId, reliableDao),
                    receiptFactory = DurableReceiptFactory(ctx),
                    localDeviceId = localDeviceId,
                    retryScheduler = materializationStartupScheduler(ctx),
                ).materializePending()
            }
            when (commit) {
                is co.twinotify.core.storage.InboundDesiredCommitResult.Duplicate ->
                    InboundDispatchResult.Duplicate(inner.msgId, envelopeSha256)
                is co.twinotify.core.storage.InboundDesiredCommitResult.Committed,
                is co.twinotify.core.storage.InboundDesiredCommitResult.Stale,
                -> InboundDispatchResult.Accepted(inner.msgId, envelopeSha256)
                is co.twinotify.core.storage.InboundDesiredCommitResult.IdConflict ->
                    InboundDispatchResult.Rejected("id_conflict")
                is co.twinotify.core.storage.InboundDesiredCommitResult.MirrorIdentityCollision ->
                    InboundDispatchResult.Rejected("mirror_identity_collision")
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
        executePeerUnpairAndRequestServiceStop(
            unpair = ::preparePeerUnpair,
            requestServiceStop = ::requestServiceStopAfterPeerUnpair,
        )
    }

    private suspend fun preparePeerUnpair() {
        co.twinotify.core.pairing.UnpairWorkflow.execute(
            // Keep the current authenticated collector alive through the non-cancellable wipe.
            // LAN finalizes service stop only after its acceptance write; relay already has
            // server custody and runs that finalizer immediately after dispatch returns.
            stopAndAwait = { SyncService.shutdownActive(ctx, fromRelayJob = true) },
            revokePeer = {},
            wipeLocal = {
                co.twinotify.core.pairing.UnpairOps.wipeAll(ctx)
                SyncServiceStatus.notifyPeerUnpaired()
            },
        )
    }

    private suspend fun requestServiceStopAfterPeerUnpair() {
        ctx.stopService(Intent(ctx, SyncService::class.java))
    }
}
