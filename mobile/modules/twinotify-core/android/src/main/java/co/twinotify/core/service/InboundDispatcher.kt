package co.twinotify.core.service

import android.content.Context
import android.content.Intent
import android.util.Base64
import co.twinotify.core.crypto.CryptoStore
import co.twinotify.core.crypto.Encrypter
import co.twinotify.core.actions.ActionControlEncoder
import co.twinotify.core.actions.ActionInvokeRequest
import co.twinotify.core.actions.ActionInvocationProcessor
import co.twinotify.core.actions.ActionDispatchGate
import co.twinotify.core.actions.ActionProcessResult
import co.twinotify.core.actions.ActionResultRowEncoder
import co.twinotify.core.actions.ActionResultJournal
import co.twinotify.core.actions.ActionResultProcessResult
import co.twinotify.core.actions.ActionResultProcessor
import co.twinotify.core.actions.ActionResultReposter
import co.twinotify.core.actions.ActionResultRequest
import co.twinotify.core.actions.DaoActionClaimJournal
import co.twinotify.core.actions.PendingIntentActionExecutor
import co.twinotify.core.actions.PersistentActionClaimWakeScheduler
import co.twinotify.core.actions.ProcessNotificationActionRegistry
import co.twinotify.core.call.CallDirection
import co.twinotify.core.call.CallStateEvent
import co.twinotify.core.call.CallStateReducer
import co.twinotify.core.listener.NotifPostJson
import co.twinotify.core.listener.NotificationListenerBridge
import co.twinotify.core.protocol.AuthenticatedEnvelope
import co.twinotify.core.protocol.AuthenticatedEnvelopeExpiredException
import co.twinotify.core.protocol.EnvelopeAuthenticator
import co.twinotify.core.protocol.PayloadDecryptor
import co.twinotify.core.protocol.optNullableString
import co.twinotify.core.protocol.ProtocolJson
import co.twinotify.core.storage.DeviceIdentity
import co.twinotify.core.storage.InboundMessage
import co.twinotify.core.storage.NotificationDb
import co.twinotify.core.storage.OutboundMessage
import co.twinotify.core.storage.PeerStore
import co.twinotify.core.storage.ReplayGuard
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.CancellationException
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

sealed interface DirectControlProcessingResult {
    data object Applied : DirectControlProcessingResult
    data class Rejected(val code: String) : DirectControlProcessingResult
}

sealed interface DirectControlCommitResult {
    data object Committed : DirectControlCommitResult
    data object Duplicate : DirectControlCommitResult
    data object IdConflict : DirectControlCommitResult
    data object NotEligible : DirectControlCommitResult
    data class Rejected(val code: String) : DirectControlCommitResult
}

fun interface DirectControlJournal {
    suspend fun commit(
        row: InboundMessage,
        process: suspend () -> DirectControlProcessingResult,
    ): DirectControlCommitResult
}

sealed interface ReceiptBackedControlResult {
    data object Applied : ReceiptBackedControlResult
    data class Rejected(val code: String) : ReceiptBackedControlResult
}

fun interface ReceiptBackedControlJournal {
    suspend fun commit(
        inbound: InboundMessage,
        receipt: OutboundMessage,
        process: suspend () -> ReceiptBackedControlResult,
    ): DirectControlCommitResult
}

fun interface AppliedControlReceiptFactory {
    suspend fun createApplied(ackedMsgId: String, envelopeSha256: String): OutboundMessage?
}

sealed interface ActionInvokeRejectionCommitResult {
    data object Committed : ActionInvokeRejectionCommitResult
    data object Duplicate : ActionInvokeRejectionCommitResult
    data object IdConflict : ActionInvokeRejectionCommitResult
}

fun interface ActionInvokeRejectionJournal {
    suspend fun commit(row: InboundMessage): ActionInvokeRejectionCommitResult
}

fun interface AuthenticatedActionInvokeProcessor {
    suspend fun process(request: ActionInvokeRequest): ActionProcessResult
}

fun interface AuthenticatedActionResultProcessor {
    suspend fun process(request: ActionResultRequest): ActionResultProcessResult
}

internal suspend fun dispatchAuthenticatedActionResult(
    inner: co.twinotify.core.protocol.InnerEventV2,
    envelopeSha256: String,
    committedAt: Long,
    processor: AuthenticatedActionResultProcessor,
): InboundDispatchResult {
    require(inner.type == "notif.action.result")
    val payload = inner.payloadObject()
    val request = ActionResultRequest(
        inbound = InboundMessage(
            msgId = inner.msgId,
            originDevice = inner.originDevice,
            envelopeSha256 = envelopeSha256,
            eventType = inner.type,
            canonId = null,
            sequence = null,
            outcome = "APPLIED",
            committedAt = committedAt,
            appliedAt = committedAt,
            receiptMsgId = null,
            relayAckState = "READY",
        ),
        invocationId = payload.getString("invocation_id"),
        canonId = payload.getString("canon_id"),
        status = payload.getString("status"),
    )
    return when (processor.process(request)) {
        ActionResultProcessResult.Applied -> InboundDispatchResult.Accepted(inner.msgId, envelopeSha256)
        ActionResultProcessResult.Duplicate -> InboundDispatchResult.Duplicate(inner.msgId, envelopeSha256)
        ActionResultProcessResult.IdConflict -> InboundDispatchResult.Rejected("id_conflict")
    }
}

internal suspend fun dispatchAuthenticatedActionInvoke(
    inner: co.twinotify.core.protocol.InnerEventV2,
    envelopeSha256: String,
    committedAt: Long,
    processor: AuthenticatedActionInvokeProcessor,
    rejectionJournal: ActionInvokeRejectionJournal,
): InboundDispatchResult {
    require(inner.type == "notif.action.invoke")
    val inbound = InboundMessage(
        msgId = inner.msgId,
        originDevice = inner.originDevice,
        envelopeSha256 = envelopeSha256,
        eventType = inner.type,
        canonId = null,
        sequence = null,
        outcome = "APPLIED",
        committedAt = committedAt,
        appliedAt = committedAt,
        receiptMsgId = null,
        relayAckState = "READY",
    )
    val request = try {
        val payload = inner.payloadObject()
        ActionInvokeRequest(
            inbound = inbound,
            invocationId = payload.getString("invocation_id"),
            canonId = payload.getString("canon_id"),
            actionId = payload.getString("action_id"),
            notificationSequence = payload.getLong("notification_sequence"),
            replyText = payload.optNullableString("reply_text"),
            invokedAt = payload.getLong("invoked_at"),
        )
    } catch (_: IllegalArgumentException) {
        null
    } catch (_: org.json.JSONException) {
        null
    }
    if (request == null) {
        return when (rejectionJournal.commit(inbound.copy(outcome = "REJECTED"))) {
            ActionInvokeRejectionCommitResult.Committed ->
                InboundDispatchResult.Accepted(inner.msgId, envelopeSha256)
            ActionInvokeRejectionCommitResult.Duplicate ->
                InboundDispatchResult.Duplicate(inner.msgId, envelopeSha256)
            ActionInvokeRejectionCommitResult.IdConflict -> InboundDispatchResult.Rejected("id_conflict")
        }
    }
    return when (processor.process(request)) {
        ActionProcessResult.IdConflict -> InboundDispatchResult.Rejected("id_conflict")
        ActionProcessResult.InFlight,
        ActionProcessResult.CompletionLost,
        is ActionProcessResult.Replayed,
        is ActionProcessResult.Completed,
        -> InboundDispatchResult.Accepted(inner.msgId, envelopeSha256)
    }
}

/** Requests a coalesced materialization pass after durable desired state exists. */
internal fun interface MaterializationRequester {
    suspend fun request()
}

/** Closed-world handoff from the desired-state transaction to materialization. */
internal data class DesiredStateDispatch(
    val result: InboundDispatchResult,
    val requestMaterialization: Boolean,
)

internal suspend fun dispatchDesiredStateAfterCommit(
    stateMutex: Mutex,
    requester: MaterializationRequester,
    lockedPhase: suspend () -> DesiredStateDispatch,
): InboundDispatchResult {
    val dispatch = stateMutex.withLock { lockedPhase() }
    if (dispatch.requestMaterialization) requester.request()
    return dispatch.result
}

sealed interface CallRejectionCommitResult {
    data object Committed : CallRejectionCommitResult
    data object Duplicate : CallRejectionCommitResult
    data object IdConflict : CallRejectionCommitResult
    data object ReceiptConflict : CallRejectionCommitResult
}

fun interface CallRejectionJournal {
    suspend fun commit(
        row: InboundMessage,
        receipt: co.twinotify.core.storage.OutboundMessage,
    ): CallRejectionCommitResult
}

data class SupersessionReceiptEntry(
    val inboundMsgId: String,
    val envelopeSha256: String,
    val receipt: co.twinotify.core.storage.OutboundMessage,
)

sealed interface SupersessionPreparation {
    data class Prepared(val entries: List<SupersessionReceiptEntry>) : SupersessionPreparation
    data object Unavailable : SupersessionPreparation
}

/** Crypto stays outside Room; callers commit this exact set atomically or reject the newer delivery. */
suspend fun prepareSupersessionRejections(
    older: List<InboundMessage>,
    createReceipt: suspend (InboundMessage, String) -> co.twinotify.core.storage.OutboundMessage?,
): SupersessionPreparation {
    val entries = ArrayList<SupersessionReceiptEntry>(older.size)
    for (row in older) {
        val receipt = try {
            createReceipt(row, "superseded")
        } catch (cancellation: CancellationException) {
            throw cancellation
        }
        if (receipt == null) return SupersessionPreparation.Unavailable
        entries += SupersessionReceiptEntry(row.msgId, row.envelopeSha256, receipt)
    }
    return SupersessionPreparation.Prepared(entries)
}

internal suspend fun dispatchAuthenticatedCallRejection(
    msgId: String,
    originDevice: String,
    envelopeSha256: String,
    canonId: String,
    sequence: Long,
    reason: String,
    committedAt: Long,
    eventType: String = "call.state",
    createReceipt: suspend (String) -> co.twinotify.core.storage.OutboundMessage?,
    journal: CallRejectionJournal,
): InboundDispatchResult {
    val receipt = createReceipt(reason)
        ?: return InboundDispatchResult.Rejected("call_rejection_receipt_unavailable")
    val row = InboundMessage(
        msgId = msgId,
        originDevice = originDevice,
        envelopeSha256 = envelopeSha256,
        eventType = eventType,
        canonId = canonId,
        sequence = sequence,
        outcome = "REJECTED",
        committedAt = committedAt,
        appliedAt = committedAt,
        receiptMsgId = receipt.msgId,
        relayAckState = "NONE",
    )
    return when (journal.commit(row, receipt)) {
        CallRejectionCommitResult.Committed -> InboundDispatchResult.Accepted(msgId, envelopeSha256)
        CallRejectionCommitResult.Duplicate -> InboundDispatchResult.Duplicate(msgId, envelopeSha256)
        CallRejectionCommitResult.IdConflict -> InboundDispatchResult.Rejected("id_conflict")
        CallRejectionCommitResult.ReceiptConflict ->
            InboundDispatchResult.Rejected("call_rejection_receipt_conflict")
    }
}

/**
 * Terminalizes an event only after it decrypted and its authenticated inner metadata matched the
 * relay-visible envelope. The encrypted expiry receipt must reach durable custody before the
 * original relay record becomes ACK-ready.
 */
internal suspend fun dispatchAuthenticatedExpiry(
    opened: AuthenticatedEnvelope,
    committedAt: Long,
    createReceipt: suspend (String, String) -> co.twinotify.core.storage.OutboundMessage?,
    journal: CallRejectionJournal,
): InboundDispatchResult {
    val inner = opened.inner
    val receipt = createReceipt(inner.msgId, opened.envelopeSha256)
        ?: return InboundDispatchResult.Rejected("expiry_receipt_unavailable")
    val inbound = InboundMessage(
        msgId = inner.msgId,
        originDevice = inner.originDevice,
        envelopeSha256 = opened.envelopeSha256,
        eventType = inner.type,
        canonId = inner.canonId,
        sequence = inner.sequence,
        outcome = "REJECTED",
        committedAt = committedAt,
        appliedAt = committedAt,
        receiptMsgId = receipt.msgId,
        relayAckState = "NONE",
    )
    return when (journal.commit(inbound, receipt)) {
        CallRejectionCommitResult.Committed ->
            InboundDispatchResult.Accepted(inner.msgId, opened.envelopeSha256)
        CallRejectionCommitResult.Duplicate ->
            InboundDispatchResult.Duplicate(inner.msgId, opened.envelopeSha256)
        CallRejectionCommitResult.IdConflict -> InboundDispatchResult.Rejected("id_conflict")
        CallRejectionCommitResult.ReceiptConflict ->
            InboundDispatchResult.Rejected("expiry_receipt_conflict")
    }
}

internal suspend fun dispatchAuthenticatedDirectControl(
    msgId: String,
    originDevice: String,
    envelopeSha256: String,
    eventType: String,
    committedAt: Long,
    journal: DirectControlJournal,
    process: suspend () -> DirectControlProcessingResult,
): InboundDispatchResult {
    val row = InboundMessage(
        msgId = msgId,
        originDevice = originDevice,
        envelopeSha256 = envelopeSha256,
        eventType = eventType,
        canonId = null,
        sequence = null,
        outcome = "APPLIED",
        committedAt = committedAt,
        appliedAt = committedAt,
        receiptMsgId = null,
        relayAckState = "READY",
    )
    return when (val result = journal.commit(row, process)) {
        DirectControlCommitResult.Committed -> InboundDispatchResult.Accepted(msgId, envelopeSha256)
        DirectControlCommitResult.Duplicate -> InboundDispatchResult.Duplicate(msgId, envelopeSha256)
        DirectControlCommitResult.IdConflict -> InboundDispatchResult.Rejected("id_conflict")
        DirectControlCommitResult.NotEligible -> InboundDispatchResult.Rejected("unsupported_control")
        is DirectControlCommitResult.Rejected -> InboundDispatchResult.Rejected(result.code)
    }
}

internal suspend fun dispatchAuthenticatedReceiptBackedControl(
    inner: co.twinotify.core.protocol.InnerEventV2,
    envelopeSha256: String,
    committedAt: Long,
    receiptFactory: AppliedControlReceiptFactory,
    journal: ReceiptBackedControlJournal,
    process: suspend () -> ReceiptBackedControlResult,
): InboundDispatchResult {
    require(inner.type in RECEIPT_BACKED_CONTROL_TYPES)
    val receipt = receiptFactory.createApplied(inner.msgId, envelopeSha256)
        ?: return InboundDispatchResult.Rejected("control_receipt_unavailable")
    val inbound = InboundMessage(
        msgId = inner.msgId,
        originDevice = inner.originDevice,
        envelopeSha256 = envelopeSha256,
        eventType = inner.type,
        canonId = null,
        sequence = null,
        outcome = "APPLIED",
        committedAt = committedAt,
        appliedAt = committedAt,
        receiptMsgId = receipt.msgId,
        relayAckState = "NONE",
    )
    return when (val result = journal.commit(inbound, receipt, process)) {
        DirectControlCommitResult.Committed -> InboundDispatchResult.Accepted(inner.msgId, envelopeSha256)
        DirectControlCommitResult.Duplicate -> InboundDispatchResult.Duplicate(inner.msgId, envelopeSha256)
        DirectControlCommitResult.IdConflict -> InboundDispatchResult.Rejected("id_conflict")
        DirectControlCommitResult.NotEligible -> InboundDispatchResult.Rejected("unsupported_control")
        is DirectControlCommitResult.Rejected -> InboundDispatchResult.Rejected(result.code)
    }
}

internal suspend fun processAuthenticatedControl(
    rejectedCode: String,
    process: suspend () -> DirectControlProcessingResult,
): DirectControlProcessingResult = try {
    process()
} catch (cancellation: CancellationException) {
    throw cancellation
} catch (_: IllegalArgumentException) {
    DirectControlProcessingResult.Rejected(rejectedCode)
} catch (_: org.json.JSONException) {
    DirectControlProcessingResult.Rejected(rejectedCode)
}

internal fun peerReceiptControlResult(transition: OutboxTransition): DirectControlProcessingResult =
    if (transition is OutboxTransition.Conflict) {
        DirectControlProcessingResult.Rejected("receipt_conflict")
    } else {
        DirectControlProcessingResult.Applied
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

class InboundDispatcher internal constructor(
    private val ctx: Context,
    private val snapshotCoordinator: SnapshotCoordinator,
    private val onAuthenticatedEvent: (String) -> Unit,
    private val authenticatedV2Opener: ((String) -> AuthenticatedEnvelope)?,
    private val directControlJournal: DirectControlJournal?,
    private val materializationRequester: MaterializationRequester,
    private val receiptBackedControlJournal: ReceiptBackedControlJournal? = null,
    private val appliedReceiptFactory: AppliedControlReceiptFactory? = null,
    private val lanBootstrapProcessor: LanBootstrapProcessor? = null,
    private val peerControlOutbox: PeerControlOutbox? = null,
    private val transportGeneration: () -> Int = { SyncServiceStatus.routeStatus.value.routeGeneration },
    private val requestDirectAttempt: () -> Unit = {},
    private val requestRouteReload: () -> Unit = {},
) {
    internal constructor(
        ctx: Context,
        snapshotCoordinator: SnapshotCoordinator = SnapshotCoordinator(
            NotificationDb.get(ctx.applicationContext).reliableDeliveryDao(),
        ),
        onAuthenticatedEvent: (String) -> Unit = {},
        materializationRequester: MaterializationRequester,
        peerControlOutbox: PeerControlOutbox? = null,
        transportGeneration: () -> Int = { SyncServiceStatus.routeStatus.value.routeGeneration },
        requestDirectAttempt: () -> Unit = {},
        requestRouteReload: () -> Unit = {},
    ) : this(
        ctx = ctx,
        snapshotCoordinator = snapshotCoordinator,
        onAuthenticatedEvent = onAuthenticatedEvent,
        authenticatedV2Opener = null,
        directControlJournal = null,
        materializationRequester = materializationRequester,
        peerControlOutbox = peerControlOutbox,
        transportGeneration = transportGeneration,
        requestDirectAttempt = requestDirectAttempt,
        requestRouteReload = requestRouteReload,
    )

    private val reliableDao by lazy { NotificationDb.get(ctx.applicationContext).reliableDeliveryDao() }
    private val outbox by lazy { OutboxRepository(DaoOutboxStore(reliableDao)) }
    private val controls by lazy { peerControlOutbox ?: PeerControlOutbox(ctx.applicationContext, reliableDao) }
    private val bootstrapProcessor by lazy {
        lanBootstrapProcessor ?: DefaultLanBootstrapProcessor(
            ctx.applicationContext,
            controls,
            transportGeneration,
        )
    }
    private val controlReceiptFactory by lazy {
        appliedReceiptFactory ?: DurableReceiptFactory(ctx.applicationContext).let { factory ->
            AppliedControlReceiptFactory(factory::createApplied)
        }
    }
    private val stateMutex = Mutex()
    private val snapshots get() = snapshotCoordinator
    private val actionProcessor by lazy {
        val encoder = ActionControlEncoder(ctx.applicationContext)
        ActionInvocationProcessor(
            journal = DaoActionClaimJournal(reliableDao),
            registryLookup = ProcessNotificationActionRegistry.registry::lookup,
            sourceActive = { sourceKey -> sourceKey in NotificationListenerBridge.activeSources() },
            supportsReply = PendingIntentActionExecutor::supportsReply,
            executor = PendingIntentActionExecutor(ctx.applicationContext),
            resultEncoder = ActionResultRowEncoder(encoder::encodeResult),
            wakeScheduler = PersistentActionClaimWakeScheduler(ctx.applicationContext),
            beforeDispatch = ActionDispatchGate::awaitIfArmed,
        )
    }
    private val actionResultProcessor by lazy {
        ActionResultProcessor(
            journal = ActionResultJournal(reliableDao::commitActionResult),
            repost = ActionResultReposter { target ->
                val current = reliableDao.canonical(target.canonId) ?: return@ActionResultReposter
                if (
                    current.state != "ACTIVE" ||
                    current.latestSequence != target.notificationSequence ||
                    current.mirrorLocalTag != target.localTag ||
                    current.mirrorLocalId != target.localId
                ) {
                    return@ActionResultReposter
                }
                val localDeviceId = DeviceIdentity.getOrCreate(ctx)
                DefaultAndroidNotificationPort(ctx, localDeviceId, reliableDao).postMirrorOutcome(current)
            },
        )
    }

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
        val peer = if (authenticatedV2Opener == null) {
            PeerStore.load(ctx) ?: run {
                android.util.Log.w("Twinotify", "no peer paired; dropping v2 inbound")
                return InboundDispatchResult.Rejected("no_peer")
            }
        } else {
            null
        }
        val opened = try {
            authenticatedV2Opener?.invoke(raw) ?: run {
                val pairedPeer = requireNotNull(peer)
                val (cryptoBox, _) = CryptoStore.loadOrGenerate(ctx)
                EnvelopeAuthenticator(
                    decryptor = PayloadDecryptor { envelope ->
                        Encrypter.decrypt(
                            ct = Base64.decode(envelope.ciphertextB64, Base64.DEFAULT),
                            nonce = Base64.decode(envelope.nonceB64, Base64.DEFAULT),
                            peerPubkey = pairedPeer.encPubkey,
                            ownSecret = cryptoBox.secretKey,
                        )
                    },
                    peerDeviceId = pairedPeer.deviceId,
                ).open(raw)
            }
        } catch (expired: AuthenticatedEnvelopeExpiredException) {
            val authenticated = expired.authenticated
            val receiptFactory = DurableReceiptFactory(ctx.applicationContext)
            val result = dispatchAuthenticatedExpiry(
                opened = authenticated,
                committedAt = System.currentTimeMillis().coerceAtLeast(0L),
                createReceipt = receiptFactory::createExpired,
                journal = CallRejectionJournal(reliableDao::commitInboundRejection),
            )
            if (result !is InboundDispatchResult.Rejected) {
                onAuthenticatedEvent(authenticated.inner.type)
            }
            return result
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
        if (inner.type in RECEIPT_BACKED_CONTROL_TYPES) {
            var bindingChanged = false
            var requestDirect = false
            val result = dispatchAuthenticatedReceiptBackedControl(
                inner = inner,
                envelopeSha256 = opened.envelopeSha256,
                committedAt = System.currentTimeMillis().coerceAtLeast(0L),
                receiptFactory = controlReceiptFactory,
                journal = receiptBackedControlJournal
                    ?: ReceiptBackedControlJournal(reliableDao::commitReceiptBackedControl),
            ) {
                when (inner.type) {
                    "lan.bootstrap" -> {
                        val payload = inner.payloadObject()
                        when (val processed = bootstrapProcessor.process(
                            LanBootstrapPayload(
                                protocolVersion = payload.getInt("protocol_version"),
                                tlsSpkiSha256 = payload.getString("tls_spki_sha256"),
                                bindingContextSha256 = payload.getString("binding_context_sha256"),
                            ),
                        )) {
                            is LanBootstrapProcessResult.Applied -> {
                                bindingChanged = processed.bindingChanged
                                ReceiptBackedControlResult.Applied
                            }
                            is LanBootstrapProcessResult.Rejected ->
                                ReceiptBackedControlResult.Rejected(processed.code)
                        }
                    }
                    "peer.probe" -> {
                        requestDirect = inner.payloadObject().getBoolean("request_direct")
                        ReceiptBackedControlResult.Applied
                    }
                    else -> error("receipt-backed control allowlist drift")
                }
            }
            if (result is InboundDispatchResult.Accepted) {
                if (bindingChanged) requestRouteReload()
                if (requestDirect) requestDirectAttempt()
            } else if (result is InboundDispatchResult.Rejected && result.code == "lan_binding_conflict") {
                SyncServiceStatus.setDeliveryConditions(
                    DeliveryConditions(bindingConflict = true),
                    transportGeneration(),
                )
            }
            onAuthenticatedEvent(inner.type)
            return result
        }
        if (inner.type in DIRECT_ACK_CONTROL_TYPES) {
            var snapshotCommitted = false
            var acceptedProbeReceipt: Pair<String, String>? = null
            val controlNow = System.currentTimeMillis().coerceAtLeast(0L)
            val result = dispatchAuthenticatedDirectControl(
                msgId = inner.msgId,
                originDevice = inner.originDevice,
                envelopeSha256 = opened.envelopeSha256,
                eventType = inner.type,
                committedAt = System.currentTimeMillis(),
                journal = directControlJournal ?: DirectControlJournal(reliableDao::commitDirectControl),
            ) {
                val rejectedCode = when (inner.type) {
                    "peer.receipt" -> "receipt_invalid"
                    "state.digest" -> "digest_rejected"
                    "state.snapshot.begin" -> "snapshot_begin_rejected"
                    "state.snapshot.item" -> "snapshot_item_rejected"
                    "state.snapshot.end" -> "snapshot_end_rejected"
                    else -> error("direct control allowlist drift")
                }
                processAuthenticatedControl(rejectedCode) { when (inner.type) {
                    "peer.receipt" -> {
                        val payload = inner.payloadObject()
                        val transition = outbox.onPeerReceipt(
                            ackedMsgId = payload.getString("acked_msg_id"),
                            envelopeSha256 = payload.getString("envelope_sha256"),
                            status = payload.getString("status"),
                            reason = payload.optString("reason").takeIf { it.isNotEmpty() },
                            occurredAt = controlNow,
                            peerReceiptCreatedAt = inner.createdAt,
                        )
                        if (transition is OutboxTransition.Deleted) {
                            acceptedProbeReceipt = payload.getString("acked_msg_id") to
                                payload.getString("envelope_sha256")
                        }
                        peerReceiptControlResult(transition)
                    }
                    "state.digest" -> snapshots.onDigest(inner).toDirectControlResult("digest_rejected")
                    "state.snapshot.begin" -> snapshots.onBegin(inner).toDirectControlResult("snapshot_begin_rejected")
                    "state.snapshot.item" -> snapshots.onItem(inner).toDirectControlResult("snapshot_item_rejected")
                    "state.snapshot.end" -> {
                        val convergence = snapshots.onEnd(inner)
                        snapshotCommitted = convergence is SnapshotConvergence.Committed
                        convergence.toDirectControlResult("snapshot_end_rejected", requireCommitted = true)
                    }
                    else -> error("direct control allowlist drift")
                } }
            }
            if (inner.type == "peer.receipt" && result !is InboundDispatchResult.Rejected) {
                acceptedProbeReceipt?.let { (msgId, digest) ->
                    val generation = transportGeneration()
                    if (controls.acceptProbeReceipt(msgId, digest, generation, controlNow)) {
                        SyncServiceStatus.setLastReceiptAt(controlNow)
                        SyncServiceStatus.setPeerEvidence(
                            controls.peerEvidence(generation, controlNow),
                            generation,
                        )
                    }
                }
                val snapshot = reliableDao.deliveryQueueSnapshot()
                SyncServiceStatus.setQueueSnapshot(snapshot)
            }
            if (snapshotCommitted) {
                ProductObservationTracker.recordSnapshotCommit()
                val localDeviceId = DeviceIdentity.getOrCreate(ctx)
                NotificationMaterializer(
                    dao = reliableDao,
                    port = DefaultAndroidNotificationPort(ctx, localDeviceId, reliableDao),
                    receiptFactory = DurableReceiptFactory(ctx),
                    localDeviceId = localDeviceId,
                    retryScheduler = materializationStartupScheduler(ctx),
                    historyRecorder = co.twinotify.core.history.HistoryRepository(ctx),
                ).materializePending()
            }
            onAuthenticatedEvent(inner.type)
            return result
        }
        if (inner.type == "notif.action.invoke") {
            val result = dispatchAuthenticatedActionInvoke(
                inner = inner,
                envelopeSha256 = opened.envelopeSha256,
                committedAt = System.currentTimeMillis(),
                processor = AuthenticatedActionInvokeProcessor(actionProcessor::process),
                rejectionJournal = ActionInvokeRejectionJournal(reliableDao::commitActionInvokeRejection),
            )
            if (result !is InboundDispatchResult.Rejected) {
                SyncServiceStatus.setQueueSnapshot(reliableDao.deliveryQueueSnapshot())
            }
            onAuthenticatedEvent(inner.type)
            return result
        }
        if (inner.type == "notif.action.result") {
            val result = dispatchAuthenticatedActionResult(
                inner = inner,
                envelopeSha256 = opened.envelopeSha256,
                committedAt = System.currentTimeMillis(),
                processor = AuthenticatedActionResultProcessor(actionResultProcessor::process),
            )
            onAuthenticatedEvent(inner.type)
            return result
        }
        if (inner.type == "call.state") {
            return dispatchCallState(inner, opened.envelopeSha256, peer?.deviceId ?: inner.originDevice)
        }
        if (inner.type !in setOf("notif.post", "notif.update", "notif.cancel")) {
            // Receipt/control processing belongs to the transport task. Preserve the authenticated
            // event in the journal only when it has a canonical desired state.
            return InboundDispatchResult.Rejected("unsupported_event")
        }
        return dispatchDesiredStateAfterCommit(stateMutex, materializationRequester) {
            val canonId = requireNotNull(inner.canonId)
            val localDeviceId = DeviceIdentity.getOrCreate(ctx)
            val current = reliableDao.canonical(canonId)
            reliableDao.inbound(inner.msgId)?.let { existing ->
                return@dispatchDesiredStateAfterCommit if (existing.envelopeSha256 == opened.envelopeSha256) {
                    DesiredStateDispatch(InboundDispatchResult.Duplicate(inner.msgId, opened.envelopeSha256), false)
                } else {
                    DesiredStateDispatch(InboundDispatchResult.Rejected("id_conflict"), false)
                }
            }
            val nextMirrorLocalId = reliableDao.nextMirrorLocalId()
            val allocator = LocalIdAllocator { nextMirrorLocalId }
            val authorizedEvent = NotificationStateReducer.authorizePeerCancel(
                current = current,
                event = inner,
                authenticatedPeerId = peer?.deviceId ?: inner.originDevice,
            ) ?: run {
                android.util.Log.w("Twinotify", "v2 cancel origin is not the paired peer")
                return@dispatchDesiredStateAfterCommit DesiredStateDispatch(
                    InboundDispatchResult.Rejected("unauthorized_cancel"),
                    false,
                )
            }
            if (current != null && requireNotNull(inner.sequence) <= current.latestSequence) {
                val receiptFactory = DurableReceiptFactory(ctx)
                return@dispatchDesiredStateAfterCommit DesiredStateDispatch(
                    dispatchAuthenticatedCallRejection(
                        msgId = inner.msgId,
                        originDevice = inner.originDevice,
                        envelopeSha256 = opened.envelopeSha256,
                        canonId = canonId,
                        sequence = requireNotNull(inner.sequence),
                        reason = "notification_sequence_stale",
                        committedAt = System.currentTimeMillis(),
                        eventType = inner.type,
                        createReceipt = { reason -> receiptFactory.createRejected(inner.msgId, opened.envelopeSha256, reason) },
                        journal = CallRejectionJournal(reliableDao::commitInboundRejection),
                    ),
                    false,
                )
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
                return@dispatchDesiredStateAfterCommit DesiredStateDispatch(
                    InboundDispatchResult.Rejected("reduction_rejected"),
                    false,
                )
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
            val preparedSupersession = when (val prepared = prepareSupersessionRejections(
                reliableDao.pendingSupersededInboundPreflight(canonId, requireNotNull(inner.sequence)),
            ) { older, reason -> DurableReceiptFactory(ctx).createRejected(older.msgId, older.envelopeSha256, reason) }) {
                is SupersessionPreparation.Prepared -> co.twinotify.core.storage.SupersessionBundle(
                    prepared.entries.map { entry -> co.twinotify.core.storage.SupersessionEntry(entry.inboundMsgId, entry.envelopeSha256, entry.receipt) },
                )
                SupersessionPreparation.Unavailable -> return@dispatchDesiredStateAfterCommit DesiredStateDispatch(
                    InboundDispatchResult.Rejected("supersession_receipt_unavailable"),
                    false,
                )
            }
            var commitDesired = desired
            var commitResult: co.twinotify.core.storage.InboundDesiredCommitResult? = null
            for (attempt in 0 until 3) {
                commitResult = reliableDao.commitInboundDesired(inbound, commitDesired, preparedSupersession)
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
                    // Platform materialization may still fail and retry. Custody is
                    // already durable, so the peer is told to stop resending.
                    DesiredStateDispatch(
                        if (result is co.twinotify.core.storage.InboundDesiredCommitResult.Duplicate) {
                            InboundDispatchResult.Duplicate(inner.msgId, opened.envelopeSha256)
                        } else {
                            InboundDispatchResult.Accepted(inner.msgId, opened.envelopeSha256)
                        },
                        requestMaterialization = true,
                    )
                }
                is co.twinotify.core.storage.InboundDesiredCommitResult.Stale -> {
                    // The canonical check can advance between the preflight and this transaction.
                    // Journal a terminal receipt instead of leaving a receipt-less STALE row.
                    val receiptFactory = DurableReceiptFactory(ctx)
                    DesiredStateDispatch(
                        dispatchAuthenticatedCallRejection(
                            msgId = inner.msgId,
                            originDevice = inner.originDevice,
                            envelopeSha256 = opened.envelopeSha256,
                            canonId = canonId,
                            sequence = requireNotNull(inner.sequence),
                            reason = "notification_sequence_stale",
                            committedAt = System.currentTimeMillis(),
                            eventType = inner.type,
                            createReceipt = { reason -> receiptFactory.createRejected(inner.msgId, opened.envelopeSha256, reason) },
                            journal = CallRejectionJournal(reliableDao::commitInboundRejection),
                        ),
                        false,
                    )
                }
                is co.twinotify.core.storage.InboundDesiredCommitResult.IdConflict ->
                    DesiredStateDispatch(InboundDispatchResult.Rejected("id_conflict"), false)
                co.twinotify.core.storage.InboundDesiredCommitResult.SupersessionUnavailable ->
                    DesiredStateDispatch(InboundDispatchResult.Rejected("supersession_unavailable"), false)
                is co.twinotify.core.storage.InboundDesiredCommitResult.ReceiptConflict ->
                    DesiredStateDispatch(InboundDispatchResult.Rejected("supersession_receipt_conflict"), false)
                is co.twinotify.core.storage.InboundDesiredCommitResult.MirrorIdentityCollision ->
                    DesiredStateDispatch(InboundDispatchResult.Rejected("mirror_identity_collision"), false)
                null -> DesiredStateDispatch(InboundDispatchResult.Rejected("commit_failed"), false)
            }
        }
    }

    private suspend fun dispatchCallState(
        inner: co.twinotify.core.protocol.InnerEventV2,
        envelopeSha256: String,
        authenticatedPeerId: String,
    ): InboundDispatchResult {
        return dispatchDesiredStateAfterCommit(stateMutex, materializationRequester) {
            if (inner.originDevice != authenticatedPeerId) {
                android.util.Log.w("Twinotify", "call state origin is not the paired peer")
                return@dispatchDesiredStateAfterCommit DesiredStateDispatch(
                    InboundDispatchResult.Rejected("unauthorized_call_origin"),
                    false,
                )
            }
            reliableDao.inbound(inner.msgId)?.let { existing ->
                return@dispatchDesiredStateAfterCommit if (existing.envelopeSha256 == envelopeSha256) {
                    DesiredStateDispatch(InboundDispatchResult.Duplicate(inner.msgId, envelopeSha256), false)
                } else {
                    DesiredStateDispatch(InboundDispatchResult.Rejected("id_conflict"), false)
                }
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
                return@dispatchDesiredStateAfterCommit DesiredStateDispatch(
                    InboundDispatchResult.Rejected("call_state_rejected"),
                    false,
                )
            }
            val rejectionCode = when (reduction) {
                is co.twinotify.core.call.CallReduction.LowerSequence -> reduction.code
                is co.twinotify.core.call.CallReduction.Conflict -> reduction.code
                is co.twinotify.core.call.CallReduction.Apply -> null
            }
            if (rejectionCode != null) {
                val receiptFactory = DurableReceiptFactory(ctx)
                return@dispatchDesiredStateAfterCommit DesiredStateDispatch(
                    dispatchAuthenticatedCallRejection(
                        msgId = inner.msgId,
                        originDevice = inner.originDevice,
                        envelopeSha256 = envelopeSha256,
                        canonId = requireNotNull(inner.canonId),
                        sequence = requireNotNull(inner.sequence),
                        reason = rejectionCode,
                        committedAt = System.currentTimeMillis(),
                        createReceipt = { reason ->
                            receiptFactory.createRejected(inner.msgId, envelopeSha256, reason)
                        },
                        journal = CallRejectionJournal(reliableDao::commitCallRejection),
                    ),
                    false,
                )
            }
            val inbound = InboundMessage(
                msgId = inner.msgId,
                originDevice = inner.originDevice,
                envelopeSha256 = envelopeSha256,
                eventType = inner.type,
                canonId = inner.canonId,
                sequence = inner.sequence,
                outcome = "PENDING_PLATFORM",
                committedAt = System.currentTimeMillis(),
                appliedAt = null,
                receiptMsgId = null,
                relayAckState = "NONE",
            )
            val supersession = when (val prepared = prepareSupersessionRejections(
                reliableDao.pendingSupersededInboundPreflight(requireNotNull(inner.canonId), requireNotNull(inner.sequence)),
            ) { older, reason -> DurableReceiptFactory(ctx).createRejected(older.msgId, older.envelopeSha256, reason) }) {
                is SupersessionPreparation.Prepared -> co.twinotify.core.storage.SupersessionBundle(
                    prepared.entries.map { entry -> co.twinotify.core.storage.SupersessionEntry(entry.inboundMsgId, entry.envelopeSha256, entry.receipt) },
                )
                SupersessionPreparation.Unavailable -> return@dispatchDesiredStateAfterCommit DesiredStateDispatch(
                    InboundDispatchResult.Rejected("supersession_receipt_unavailable"),
                    false,
                )
            }
            val commit = reliableDao.commitInboundDesired(
                inbound,
                (reduction as co.twinotify.core.call.CallReduction.Apply).state,
                supersession,
            )
            when (commit) {
                is co.twinotify.core.storage.InboundDesiredCommitResult.Duplicate ->
                    DesiredStateDispatch(InboundDispatchResult.Duplicate(inner.msgId, envelopeSha256), true)
                is co.twinotify.core.storage.InboundDesiredCommitResult.Committed ->
                    DesiredStateDispatch(InboundDispatchResult.Accepted(inner.msgId, envelopeSha256), true)
                is co.twinotify.core.storage.InboundDesiredCommitResult.Stale -> {
                    val receiptFactory = DurableReceiptFactory(ctx)
                    DesiredStateDispatch(
                        dispatchAuthenticatedCallRejection(
                            msgId = inner.msgId,
                            originDevice = inner.originDevice,
                            envelopeSha256 = envelopeSha256,
                            canonId = requireNotNull(inner.canonId),
                            sequence = requireNotNull(inner.sequence),
                            reason = "call_sequence_lower",
                            committedAt = System.currentTimeMillis(),
                            createReceipt = { reason -> receiptFactory.createRejected(inner.msgId, envelopeSha256, reason) },
                            journal = CallRejectionJournal(reliableDao::commitInboundRejection),
                        ),
                        false,
                    )
                }
                is co.twinotify.core.storage.InboundDesiredCommitResult.IdConflict ->
                    DesiredStateDispatch(InboundDispatchResult.Rejected("id_conflict"), false)
                co.twinotify.core.storage.InboundDesiredCommitResult.SupersessionUnavailable ->
                    DesiredStateDispatch(InboundDispatchResult.Rejected("supersession_unavailable"), false)
                is co.twinotify.core.storage.InboundDesiredCommitResult.ReceiptConflict ->
                    DesiredStateDispatch(InboundDispatchResult.Rejected("supersession_receipt_conflict"), false)
                is co.twinotify.core.storage.InboundDesiredCommitResult.MirrorIdentityCollision ->
                    DesiredStateDispatch(InboundDispatchResult.Rejected("mirror_identity_collision"), false)
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

internal fun recordSnapshotCommitIfCommitted(result: Result<SnapshotConvergence>): SnapshotConvergence? {
    val convergence = result.getOrNull()
    if (convergence is SnapshotConvergence.Committed) {
        ProductObservationTracker.recordSnapshotCommit()
    }
    return convergence
}

internal fun SnapshotConvergence.toDirectControlResult(
    rejectedCode: String,
    requireCommitted: Boolean = false,
): DirectControlProcessingResult = when {
    this is SnapshotConvergence.Rejected ||
        this is SnapshotConvergence.Incomplete ||
        this is SnapshotConvergence.DigestMismatch ||
        (requireCommitted && this !is SnapshotConvergence.Committed) ->
        DirectControlProcessingResult.Rejected(rejectedCode)
    // A peer receiving an origin owner's digest cannot enumerate that owner's source
    // notifications. That is a valid no-repair outcome, not malformed control data.
    else -> DirectControlProcessingResult.Applied
}

private val DIRECT_ACK_CONTROL_TYPES = setOf(
    "peer.receipt",
    "state.digest",
    "state.snapshot.begin",
    "state.snapshot.item",
    "state.snapshot.end",
)

private val RECEIPT_BACKED_CONTROL_TYPES = setOf("lan.bootstrap", "peer.probe")
