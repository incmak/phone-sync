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
import co.twinotify.core.storage.NotificationDb
import co.twinotify.core.storage.OutboundMessage
import co.twinotify.core.storage.ReliableDeliveryDao
import co.twinotify.core.storage.PeerStore
import co.twinotify.core.call.CallStateMaterializer
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.launch
import org.json.JSONObject

data class MaterializationSummary(
    val applied: Int,
    val pending: Int,
    val skipped: Int,
)

fun interface MaterializationRetryScheduler {
    fun schedule(delayMs: Long, action: suspend () -> Unit)
}

/** Process-scoped wakeup coordinator; duplicate materializers share one retry timer. */
object NotificationMaterializationRetry {
    private val scope = kotlinx.coroutines.CoroutineScope(
        kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO,
    )
    private val scheduled = java.util.concurrent.atomic.AtomicBoolean(false)
    val scheduler = MaterializationRetryScheduler { delayMs, action ->
        if (scheduled.compareAndSet(false, true)) {
            scope.launch {
                try {
                    kotlinx.coroutines.delay(delayMs)
                    action()
                } finally {
                    scheduled.set(false)
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
            alarm.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, System.currentTimeMillis() + delayMs, pending)
        } catch (_: SecurityException) {
            // Alarm policy restrictions must not strand work while this process is alive.
            NotificationMaterializationRetry.scheduler.schedule(delayMs, action)
        }
    }
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
    suspend fun pendingMaterialization(nowMs: Long = System.currentTimeMillis()): List<CanonicalNotificationState>

    suspend fun pendingInbound(canonId: String, sequence: Long): List<InboundMessage> = emptyList()

    suspend fun markPeerCancelPending(canonId: String) = Unit

    suspend fun recordRetry(canonId: String, nextAttemptAt: Long, lastError: String?) = Unit

    suspend fun clearRetry(canonId: String) = Unit

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

class DaoMaterializationStore(private val dao: ReliableDeliveryDao) : MaterializationStore {
    override suspend fun pendingMaterialization(nowMs: Long): List<CanonicalNotificationState> = dao.pendingMaterialization(nowMs)

    override suspend fun pendingInbound(canonId: String, sequence: Long): List<InboundMessage> =
        dao.pendingInboundForMaterialization(canonId, sequence)

    override suspend fun markPeerCancelPending(canonId: String) {
        dao.markPeerCancelPending(canonId)
    }

    override suspend fun recordRetry(canonId: String, nextAttemptAt: Long, lastError: String?) {
        val previous = dao.materializationRetry(canonId)
        dao.putMaterializationRetry(
            MaterializationRetry(
                canonId = canonId,
                nextAttemptAt = nextAttemptAt,
                attempts = (previous?.attempts ?: 0) + 1,
                lastError = lastError,
            ),
        )
    }

    override suspend fun clearRetry(canonId: String) {
        dao.clearMaterializationRetry(canonId)
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
}

/** Applies persisted desired state after a platform crash window. */
class NotificationMaterializer(
    private val store: MaterializationStore,
    private val port: AndroidNotificationPort,
    private val receiptFactory: ReceiptFactory? = null,
    private val localDeviceId: String? = null,
    private val retryScheduler: MaterializationRetryScheduler = NotificationMaterializationRetry.scheduler,
    private val retryDelayMs: Long = RETRY_DELAY_MS,
) {
    constructor(
        dao: ReliableDeliveryDao,
        port: AndroidNotificationPort,
        receiptFactory: ReceiptFactory? = null,
        localDeviceId: String? = null,
        retryScheduler: MaterializationRetryScheduler = NotificationMaterializationRetry.scheduler,
        retryDelayMs: Long = RETRY_DELAY_MS,
    ) : this(DaoMaterializationStore(dao), port, receiptFactory, localDeviceId, retryScheduler, retryDelayMs)

    suspend fun materializePending(nowMs: Long = System.currentTimeMillis()): MaterializationSummary {
        var applied = 0
        var pending = 0
        var skipped = 0
        for (state in store.pendingMaterialization(nowMs)) {
            val inbound = store.pendingInbound(state.canonId, state.latestSequence)
            val candidate = try {
                if (inbound.any { it.receiptMsgId == null }) {
                    receiptFactory?.create(state, inbound)
                } else {
                    null
                }
            } catch (error: Throwable) {
                store.recordRetry(state.canonId, nowMs + retryDelayMs, "receipt_creation_failed")
                pending += 1
                continue
            }
            val preparedReceipt = when (
                val result = store.prepareReceipt(state.canonId, state.latestSequence, candidate)
            ) {
                MaterializationReceiptResult.NotNeeded -> null
                MaterializationReceiptResult.Unavailable -> {
                    store.recordRetry(state.canonId, nowMs + retryDelayMs, "receipt_unavailable")
                    pending += 1
                    continue
                }
                is MaterializationReceiptResult.Prepared -> result.receipt
                is MaterializationReceiptResult.Conflict -> {
                    skipped += 1
                    continue
                }
            }
            val successful = applyPlatform(state)
            if (!successful) {
                store.recordRetry(state.canonId, nowMs + retryDelayMs, "platform_effect_failed")
                pending += 1
                continue
            }
            when (store.completeMaterialization(state.canonId, state.latestSequence, nowMs, preparedReceipt)) {
                MaterializationResult.Completed,
                MaterializationResult.AlreadyCompleted,
                -> {
                    store.clearRetry(state.canonId)
                    applied += 1
                }
                MaterializationResult.Superseded,
                MaterializationResult.Missing,
                is MaterializationResult.ReceiptConflict,
                -> skipped += 1
            }
        }
        if (pending > 0) {
            retryScheduler.schedule(retryDelayMs) { materializePending() }
        }
        return MaterializationSummary(applied = applied, pending = pending, skipped = skipped)
    }

    private suspend fun applyPlatform(state: CanonicalNotificationState): Boolean {
        val isSource = localDeviceId != null && state.originDevice == localDeviceId
        return if (isSource) {
            when (state.state) {
                "ACTIVE" -> true // The listener already owns the source notification.
                "CANCELLED" -> state.sourceNotificationKey?.let(port::cancelSource) ?: false
                else -> false
            }
        } else {
            when (state.state) {
                "ACTIVE" -> if (CallStateMaterializer.isCall(state.canonId)) {
                    port.postCallMirror(state)
                } else {
                    port.postMirror(state)
                }
                "CANCELLED" -> {
                    val localId = state.mirrorLocalId
                    val localTag = state.mirrorLocalTag
                    if (localId == null || localTag == null) {
                        true // Nothing was materialized on this device.
                    } else {
                        store.markPeerCancelPending(state.canonId)
                        if (CallStateMaterializer.isCall(state.canonId)) {
                            port.cancelCallMirror(localTag, localId)
                        } else {
                            port.cancelMirror(localTag, localId)
                        }
                    }
                }
                else -> false
            }
        }
    }

    private companion object {
        const val RETRY_DELAY_MS = 5_000L
    }
}

/** Creates an authenticated v2 peer receipt after a platform operation succeeds. */
class DurableReceiptFactory(private val context: Context) : ReceiptFactory {
    override suspend fun create(
        state: CanonicalNotificationState,
        pendingInbound: List<InboundMessage>,
    ): OutboundMessage? {
        val inbound = pendingInbound.firstOrNull() ?: return null
        val peer = PeerStore.load(context) ?: return null
        val originDevice = DeviceIdentity.getOrCreate(context)
        val createdAt = System.currentTimeMillis().coerceAtLeast(0L)
        val expiresAt = createdAt + RETENTION_MS
        val msgId = UUID.randomUUID().toString()
        val payload = JSONObject()
            .put("acked_msg_id", inbound.msgId)
            .put("envelope_sha256", inbound.envelopeSha256)
            .put("status", "applied")
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
