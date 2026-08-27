package co.twinotify.core.service

import android.content.Context
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Intent
import android.util.Base64
import co.twinotify.core.crypto.CryptoStore
import co.twinotify.core.crypto.Encrypter
import co.twinotify.core.crypto.NonceSource
import co.twinotify.core.protocol.EncryptedEnvelope
import co.twinotify.core.protocol.InnerEventV2
import co.twinotify.core.protocol.ProtocolJson
import co.twinotify.core.storage.CanonicalNotificationState
import co.twinotify.core.storage.DeviceIdentity
import co.twinotify.core.storage.InboundMessage
import co.twinotify.core.storage.MaterializationResult
import co.twinotify.core.storage.MaterializationReceiptResult
import co.twinotify.core.storage.MaterializationRetry
import co.twinotify.core.storage.MaterializationRetryDisposition
import co.twinotify.core.storage.MaterializationRetryWriteResult
import co.twinotify.core.storage.NotificationDb
import co.twinotify.core.storage.OutboundMessage
import co.twinotify.core.storage.ReliableDeliveryDao
import co.twinotify.core.storage.PeerStore
import co.twinotify.core.call.CallStateMaterializer
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject

data class MaterializationSummary(
    val applied: Int,
    val pending: Int,
    val skipped: Int,
)

/** Declares why a materialization pass is running so permission-held work is never polled. */
enum class MaterializationTrigger {
    ROUTINE,
    POST_PERMISSION_AVAILABLE,
}

internal fun materializationTriggerForPostAvailability(
    postAvailable: Boolean,
): MaterializationTrigger = if (postAvailable) {
    MaterializationTrigger.POST_PERMISSION_AVAILABLE
} else {
    MaterializationTrigger.ROUTINE
}

/** Every entry point shares this boundary so platform effects cannot overlap before Room fences. */
private object ProcessMaterializationPassCoordinator {
    private val mutex = Mutex()

    suspend fun <T> serialize(block: suspend () -> T): T = mutex.withLock { block() }
}

fun interface MaterializationRetryScheduler {
    fun schedule(delayMs: Long, action: suspend () -> Unit)
}

/** Keeps one process wake and replaces it only when durable work becomes due sooner. */
internal class EarliestMaterializationWake {
    private var dueAt: Long? = null

    @Synchronized
    fun claim(nowMs: Long, delayMs: Long): Boolean {
        val candidate = if (nowMs > Long.MAX_VALUE - delayMs) Long.MAX_VALUE else nowMs + delayMs
        if (dueAt?.let { it <= candidate } == true) return false
        dueAt = candidate
        return true
    }

    @Synchronized
    fun consume(dueMs: Long): Boolean {
        if (dueAt != dueMs) return false
        dueAt = null
        return true
    }
}

/** Process-scoped wakeup coordinator; duplicate materializers share one retry timer. */
object NotificationMaterializationRetry {
    private val scope = kotlinx.coroutines.CoroutineScope(
        kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO,
    )
    private val wake = EarliestMaterializationWake()
    private val schedulingLock = Any()
    private var scheduledJob: kotlinx.coroutines.Job? = null
    val scheduler = MaterializationRetryScheduler { delayMs, action ->
        val safeDelay = delayMs.coerceAtLeast(0L)
        val now = System.nanoTime() / 1_000_000L
        val dueAt = if (now > Long.MAX_VALUE - safeDelay) Long.MAX_VALUE else now + safeDelay
        synchronized(schedulingLock) {
            if (wake.claim(now, safeDelay)) {
                scheduledJob?.cancel()
                scheduledJob = scope.launch {
                    kotlinx.coroutines.delay(safeDelay)
                    if (wake.consume(dueAt)) action()
                }
            }
        }
    }
}

/** Persists a wakeup in AlarmManager so a failed platform effect is retried after process death. */
class AlarmManagerMaterializationScheduler(private val context: Context) : MaterializationRetryScheduler {
    override fun schedule(delayMs: Long, action: suspend () -> Unit) {
        val alarm = context.getSystemService(AlarmManager::class.java)
        if (alarm == null) {
            NotificationMaterializationRetry.scheduler.schedule(delayMs, action)
            return
        }
        try {
            val intent = Intent(context, MaterializationRetryReceiver::class.java)
            val pending = PendingIntent.getBroadcast(
                context,
                0,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            alarm.setAndAllowWhileIdle(
                AlarmManager.RTC_WAKEUP,
                saturatingAlarmTriggerAt(System.currentTimeMillis(), delayMs),
                pending,
            )
        } catch (_: SecurityException) {
            // Alarm policy restrictions must not strand work while this process is alive.
            NotificationMaterializationRetry.scheduler.schedule(delayMs, action)
        }
    }
}

internal fun saturatingAlarmTriggerAt(nowMs: Long, delayMs: Long): Long {
    val safeDelay = delayMs.coerceAtLeast(0L)
    return if (nowMs > Long.MAX_VALUE - safeDelay) Long.MAX_VALUE else nowMs + safeDelay
}

/** Single production startup seam used by services/listeners and covered by instrumentation. */
internal fun materializationStartupScheduler(context: Context): MaterializationRetryScheduler =
    AlarmManagerMaterializationScheduler(context.applicationContext)

class MaterializationRetryReceiver : android.content.BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val pending = goAsync()
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO).launch {
            try {
                NotificationMaterializer(context.applicationContext).materializePending()
            } finally {
                pending.finish()
            }
        }
    }
}

/** The small persistence surface needed by the materializer; production uses Room below. */
interface MaterializationStore {
    suspend fun pendingMaterialization(
        trigger: MaterializationTrigger = MaterializationTrigger.ROUTINE,
        nowMs: Long = System.currentTimeMillis(),
    ): List<CanonicalNotificationState>

    suspend fun pendingInbound(canonId: String, sequence: Long): List<InboundMessage> = emptyList()

    suspend fun markPeerCancelPending(canonId: String) = Unit

    suspend fun recordRetry(canonId: String, nextAttemptAt: Long, lastError: String?) = Unit

    suspend fun recordMaterializationRetry(
        canonId: String,
        sequence: Long,
        nowMs: Long,
        disposition: MaterializationRetryDisposition,
        lastError: String,
    ): MaterializationRetryWriteResult {
        return if (disposition == MaterializationRetryDisposition.PERMISSION_BLOCKED) {
            recordRetry(canonId, Long.MAX_VALUE, lastError)
            MaterializationRetryWriteResult.PermissionBlocked
        } else {
            val dueAt = nowMs + 5_000L
            recordRetry(canonId, dueAt, lastError)
            MaterializationRetryWriteResult.RetryableScheduled(dueAt)
        }
    }

    suspend fun earliestRetryableMaterializationAt(): Long? = null

    suspend fun clearRetry(canonId: String) = Unit

    suspend fun clearRetry(canonId: String, sequence: Long) {
        clearRetry(canonId)
    }

    suspend fun prepareReceipt(
        canonId: String,
        sequence: Long,
        candidate: OutboundMessage?,
    ): MaterializationReceiptResult = if (candidate == null) {
        MaterializationReceiptResult.NotNeeded
    } else {
        MaterializationReceiptResult.Prepared(candidate)
    }

    suspend fun completeMaterialization(
        canonId: String,
        sequence: Long,
        appliedAt: Long,
        receipt: OutboundMessage?,
    ): MaterializationResult
}

class DaoMaterializationStore(internal val dao: ReliableDeliveryDao) : MaterializationStore {
    override suspend fun pendingMaterialization(
        trigger: MaterializationTrigger,
        nowMs: Long,
    ): List<CanonicalNotificationState> = dao.pendingMaterialization(
        now = nowMs,
        includePermissionBlocked = trigger == MaterializationTrigger.POST_PERMISSION_AVAILABLE,
    )

    override suspend fun pendingInbound(canonId: String, sequence: Long): List<InboundMessage> =
        dao.pendingInboundForMaterialization(canonId, sequence)

    override suspend fun markPeerCancelPending(canonId: String) {
        dao.markPeerCancelPending(canonId)
    }

    override suspend fun recordMaterializationRetry(
        canonId: String,
        sequence: Long,
        nowMs: Long,
        disposition: MaterializationRetryDisposition,
        lastError: String,
    ): MaterializationRetryWriteResult = dao.recordMaterializationRetry(
        canonId,
        sequence,
        nowMs,
        disposition,
        lastError,
    )

    override suspend fun earliestRetryableMaterializationAt(): Long? =
        dao.earliestRetryableMaterializationAt()

    override suspend fun clearRetry(canonId: String, sequence: Long) {
        dao.clearMaterializationRetriesThrough(canonId, sequence)
    }

    override suspend fun completeMaterialization(
        canonId: String,
        sequence: Long,
        appliedAt: Long,
        receipt: OutboundMessage?,
    ): MaterializationResult = dao.completeMaterialization(canonId, sequence, appliedAt, receipt)

    override suspend fun prepareReceipt(
        canonId: String,
        sequence: Long,
        candidate: OutboundMessage?,
    ): MaterializationReceiptResult = dao.prepareMaterializationReceipt(canonId, sequence, candidate)
}

fun interface ReceiptFactory {
    suspend fun create(
        state: CanonicalNotificationState,
        pendingInbound: List<InboundMessage>,
    ): OutboundMessage?

    suspend fun createRejected(ackedMsgId: String, envelopeSha256: String, reason: String): OutboundMessage? = null
}

/** Applies persisted desired state after a platform crash window. */
class NotificationMaterializer(
    private val store: MaterializationStore,
    private val port: AndroidNotificationPort,
    private val receiptFactory: ReceiptFactory? = null,
    private val localDeviceId: String? = null,
    private val retryScheduler: MaterializationRetryScheduler = NotificationMaterializationRetry.scheduler,
) {
    constructor(
        dao: ReliableDeliveryDao,
        port: AndroidNotificationPort,
        receiptFactory: ReceiptFactory? = null,
        localDeviceId: String? = null,
        retryScheduler: MaterializationRetryScheduler = NotificationMaterializationRetry.scheduler,
    ) : this(DaoMaterializationStore(dao), port, receiptFactory, localDeviceId, retryScheduler)

    suspend fun materializePending(
        trigger: MaterializationTrigger = MaterializationTrigger.ROUTINE,
        nowMs: Long = System.currentTimeMillis(),
    ): MaterializationSummary = ProcessMaterializationPassCoordinator.serialize {
        materializePendingSerialized(trigger, nowMs)
    }

    private suspend fun materializePendingSerialized(
        trigger: MaterializationTrigger,
        nowMs: Long,
    ): MaterializationSummary {
        var applied = 0
        var pending = 0
        var skipped = 0
        reconcileSupersededInbound(nowMs)
        var observedRetryDue: Long? = null
        suspend fun recordRetry(
            state: CanonicalNotificationState,
            disposition: MaterializationRetryDisposition,
            code: String,
        ) {
            when (val result = store.recordMaterializationRetry(
                canonId = state.canonId,
                sequence = state.latestSequence,
                nowMs = nowMs,
                disposition = disposition,
                lastError = code,
            )) {
                is MaterializationRetryWriteResult.RetryableScheduled -> {
                    observedRetryDue = minOf(observedRetryDue ?: result.dueAt, result.dueAt)
                }
                MaterializationRetryWriteResult.PermissionBlocked,
                MaterializationRetryWriteResult.Superseded,
                -> Unit
            }
        }
        for (state in store.pendingMaterialization(trigger, nowMs)) {
            val inbound = store.pendingInbound(state.canonId, state.latestSequence)
            val candidate = try {
                if (inbound.any { it.receiptMsgId == null }) {
                    receiptFactory?.create(state, inbound)
                } else {
                    null
                }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Throwable) {
                recordRetry(state, MaterializationRetryDisposition.RETRYABLE, "receipt_creation_failed")
                pending += 1
                continue
            }
            val preparedReceipt = when (
                val result = store.prepareReceipt(state.canonId, state.latestSequence, candidate)
            ) {
                MaterializationReceiptResult.NotNeeded -> null
                MaterializationReceiptResult.Unavailable -> {
                    recordRetry(state, MaterializationRetryDisposition.RETRYABLE, "receipt_unavailable")
                    pending += 1
                    continue
                }
                is MaterializationReceiptResult.Prepared -> result.receipt
                is MaterializationReceiptResult.Conflict -> {
                    skipped += 1
                    continue
                }
            }
            when (applyPlatform(state)) {
                NotificationPostOutcome.Applied -> Unit
                NotificationPostOutcome.PermissionBlocked -> {
                    recordRetry(state, MaterializationRetryDisposition.PERMISSION_BLOCKED, "post_permission_blocked")
                    pending += 1
                    continue
                }
                NotificationPostOutcome.RetryableFailure -> {
                    recordRetry(state, MaterializationRetryDisposition.RETRYABLE, "platform_retryable")
                    pending += 1
                    continue
                }
            }
            when (store.completeMaterialization(state.canonId, state.latestSequence, nowMs, preparedReceipt)) {
                MaterializationResult.Completed,
                MaterializationResult.AlreadyCompleted,
                -> {
                    store.clearRetry(state.canonId, state.latestSequence)
                    applied += 1
                }
                MaterializationResult.Superseded,
                MaterializationResult.Missing,
                is MaterializationResult.ReceiptConflict,
                -> skipped += 1
            }
        }
        val nextRetryAt = store.earliestRetryableMaterializationAt() ?: observedRetryDue
        if (nextRetryAt != null) {
            retryScheduler.schedule((nextRetryAt - nowMs).coerceAtLeast(0L)) { materializePending() }
        }
        return MaterializationSummary(applied = applied, pending = pending, skipped = skipped)
    }

    private suspend fun reconcileSupersededInbound(nowMs: Long) {
        val dao = (store as? DaoMaterializationStore)?.dao ?: return
        val factory = receiptFactory ?: return
        var retryRequired = false
        for (state in dao.strandedSupersededCanonicalGroups(limit = 32)) {
            val older = dao.pendingSupersededInboundPreflight(state.canonId, state.latestSequence)
            val prepared = try {
                when (val result = prepareSupersessionRejections(older) { row, reason ->
                    factory.createRejected(row.msgId, row.envelopeSha256, reason)
                }) {
                    is SupersessionPreparation.Prepared -> result
                    SupersessionPreparation.Unavailable -> {
                        retryRequired = true
                        continue
                    }
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Throwable) {
                retryRequired = true
                continue
            }
            if (!dao.terminalizeSupersededInbound(
                canonId = state.canonId,
                sequence = state.latestSequence,
                supersession = co.twinotify.core.storage.SupersessionBundle(
                    prepared.entries.map { co.twinotify.core.storage.SupersessionEntry(it.inboundMsgId, it.envelopeSha256, it.receipt) },
                ),
                terminalAt = nowMs,
            )) retryRequired = true
        }
        if (retryRequired) {
            retryScheduler.schedule(5_000L) { materializePending() }
        }
    }

    private suspend fun applyPlatform(state: CanonicalNotificationState): NotificationPostOutcome {
        val isSource = localDeviceId != null && state.originDevice == localDeviceId
        return if (isSource) {
            when (state.state) {
                "ACTIVE" -> NotificationPostOutcome.Applied // The listener already owns the source notification.
                "CANCELLED" -> if (state.sourceNotificationKey?.let(port::cancelSource) == true) {
                    NotificationPostOutcome.Applied
                } else {
                    NotificationPostOutcome.RetryableFailure
                }
                else -> NotificationPostOutcome.RetryableFailure
            }
        } else {
            when (state.state) {
                "ACTIVE" -> if (CallStateMaterializer.isCall(state.canonId)) {
                    port.postCallMirrorOutcome(state)
                } else {
                    port.postMirrorOutcome(state)
                }
                "CANCELLED" -> {
                    val localId = state.mirrorLocalId
                    val localTag = state.mirrorLocalTag
                    if (localId == null || localTag == null) {
                        NotificationPostOutcome.Applied // Nothing was materialized on this device.
                    } else {
                        store.markPeerCancelPending(state.canonId)
                        val cancelled = if (CallStateMaterializer.isCall(state.canonId)) {
                            port.cancelCallMirror(localTag, localId)
                        } else {
                            port.cancelMirror(localTag, localId)
                        }
                        if (cancelled) NotificationPostOutcome.Applied else NotificationPostOutcome.RetryableFailure
                    }
                }
                else -> NotificationPostOutcome.RetryableFailure
            }
        }
    }
}

/** Creates an authenticated v2 peer receipt after a platform operation succeeds. */
class DurableReceiptFactory(private val context: Context) : ReceiptFactory {
    override suspend fun create(
        state: CanonicalNotificationState,
        pendingInbound: List<InboundMessage>,
    ): OutboundMessage? {
        val inbound = pendingInbound.firstOrNull() ?: return null
        return createReceipt(inbound.msgId, inbound.envelopeSha256, "applied", null)
    }

    override suspend fun createRejected(
        ackedMsgId: String,
        envelopeSha256: String,
        reason: String,
    ): OutboundMessage? = createReceipt(ackedMsgId, envelopeSha256, "rejected", reason)

    private suspend fun createReceipt(
        ackedMsgId: String,
        envelopeSha256: String,
        status: String,
        reason: String?,
    ): OutboundMessage? {
        val peer = PeerStore.load(context) ?: return null
        val originDevice = DeviceIdentity.getOrCreate(context)
        val createdAt = System.currentTimeMillis().coerceAtLeast(0L)
        val expiresAt = createdAt + RETENTION_MS
        val msgId = UUID.randomUUID().toString()
        val payload = JSONObject()
            .put("acked_msg_id", ackedMsgId)
            .put("envelope_sha256", envelopeSha256)
            .put("status", status)
            .apply { if (reason != null) put("reason", reason) }
            .toString()
        val inner = InnerEventV2(
            msgId = msgId,
            originDevice = originDevice,
            type = "peer.receipt",
            canonId = null,
            sequence = null,
            createdAt = createdAt,
            expiresAt = expiresAt,
            payloadJson = payload,
        )
        val (box, _) = CryptoStore.loadOrGenerate(context)
        val nonce = NonceSource.next(context)
        val ciphertext = Encrypter.encrypt(
            plain = ProtocolJson.encodeInner(inner).toByteArray(Charsets.UTF_8),
            nonce = nonce,
            peerPubkey = peer.encPubkey,
            ownSecret = box.secretKey,
        )
        val envelope = ProtocolJson.encodeEnvelope(
            EncryptedEnvelope(
                version = ProtocolJson.VERSION,
                msgId = msgId,
                originDevice = originDevice,
                createdAt = createdAt,
                nonceB64 = Base64.encodeToString(nonce, Base64.NO_WRAP),
                ciphertextB64 = Base64.encodeToString(ciphertext, Base64.NO_WRAP),
            ),
        )
        return OutboundMessage(
            msgId = msgId,
            canonId = null,
            sequence = null,
            eventType = "peer.receipt",
            protocolVersion = ProtocolJson.VERSION,
            envelopeJson = envelope,
            envelopeSha256 = sha256(envelope),
            byteSize = envelope.toByteArray(Charsets.UTF_8).size.toLong(),
            createdAt = createdAt,
            expiresAt = expiresAt,
            custodyAcceptedAt = null,
            custodyRoute = null,
            attempts = 0,
            nextAttemptAt = createdAt,
            state = "NEW",
            lastError = null,
            requiresPeerReceipt = false,
        )
    }

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xff) }

    private companion object {
        const val RETENTION_MS = 24 * 60 * 60 * 1_000L
    }
}

fun NotificationMaterializer(context: Context): NotificationMaterializer {
    val app = context.applicationContext
    val dao = NotificationDb.get(app).reliableDeliveryDao()
    val local = runCatching { kotlinx.coroutines.runBlocking { DeviceIdentity.getOrCreate(app) } }
        .getOrNull()
        ?: return NotificationMaterializer(dao, DefaultAndroidNotificationPort(app, ""))
    return NotificationMaterializer(
        dao = dao,
        port = DefaultAndroidNotificationPort(app, local, dao),
        receiptFactory = DurableReceiptFactory(app),
        localDeviceId = local,
        retryScheduler = AlarmManagerMaterializationScheduler(app),
    )
}
