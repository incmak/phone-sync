package co.twinotify.core.service

import android.content.Context
import co.twinotify.core.crypto.CryptoStore
import co.twinotify.core.crypto.Encrypter
import co.twinotify.core.crypto.NonceSource
import co.twinotify.core.protocol.EncryptedEnvelope
import co.twinotify.core.protocol.InnerEventV2
import co.twinotify.core.protocol.ProtocolJson
import co.twinotify.core.storage.DeviceIdentity
import co.twinotify.core.storage.OutboundMessage
import co.twinotify.core.storage.PeerStore
import co.twinotify.core.storage.ReliableDeliveryDao
import java.security.MessageDigest
import java.util.Base64
import java.util.UUID
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject

data class LanBootstrapPayload(
    val tlsSpkiSha256: String,
    val bindingContextSha256: String,
    val protocolVersion: Int = 1,
)

enum class PeerEvidence { DIRECT, RECENT, STALE, UNKNOWN }

fun interface PeerControlSealer {
    suspend fun seal(event: InnerEventV2, requiresPeerReceipt: Boolean): OutboundMessage
}

interface PeerControlStore {
    suspend fun insert(row: OutboundMessage)
    suspend fun active(eventType: String, now: Long): OutboundMessage?
}

internal data class PeerControlSealInputs(
    val peerPublicKey: ByteArray,
    val ownSecretKey: ByteArray,
    val nonce: ByteArray,
)

internal fun interface PeerControlSealInputsProvider {
    suspend fun load(): PeerControlSealInputs
}

internal fun interface PeerControlEncryptor {
    fun encrypt(
        plain: ByteArray,
        nonce: ByteArray,
        peerPublicKey: ByteArray,
        ownSecretKey: ByteArray,
    ): ByteArray
}

internal class DurablePeerControlSealer(
    private val inputs: PeerControlSealInputsProvider,
    private val encryptor: PeerControlEncryptor,
) : PeerControlSealer {
    constructor(context: Context) : this(
        inputs = PeerControlSealInputsProvider {
            val peer = PeerStore.load(context)
                ?: throw IllegalStateException("peer control requires a paired peer")
            val (box, _) = CryptoStore.loadOrGenerate(context)
            PeerControlSealInputs(
                peerPublicKey = peer.encPubkey,
                ownSecretKey = box.secretKey,
                nonce = NonceSource.next(context),
            )
        },
        encryptor = PeerControlEncryptor { plain, nonce, peerPublicKey, ownSecretKey ->
            Encrypter.encrypt(plain, nonce, peerPublicKey, ownSecretKey)
        },
    )

    override suspend fun seal(event: InnerEventV2, requiresPeerReceipt: Boolean): OutboundMessage {
        val material = inputs.load()
        val peerPublicKey = material.peerPublicKey.copyOf()
        val ownSecretKey = material.ownSecretKey.copyOf()
        val nonce = material.nonce.copyOf()
        val plain = ProtocolJson.encodeInner(event).toByteArray(Charsets.UTF_8)
        var ciphertext: ByteArray? = null
        try {
            ciphertext = encryptor.encrypt(plain, nonce, peerPublicKey, ownSecretKey)
            val envelope = ProtocolJson.encodeEnvelope(
                EncryptedEnvelope(
                    version = ProtocolJson.VERSION,
                    msgId = event.msgId,
                    originDevice = event.originDevice,
                    createdAt = event.createdAt,
                    nonceB64 = Base64.getEncoder().encodeToString(nonce),
                    ciphertextB64 = Base64.getEncoder().encodeToString(ciphertext),
                ),
            )
            val envelopeBytes = envelope.toByteArray(Charsets.UTF_8)
            return OutboundMessage(
                msgId = event.msgId,
                canonId = event.canonId,
                sequence = event.sequence,
                eventType = event.type,
                protocolVersion = ProtocolJson.VERSION,
                envelopeJson = envelope,
                envelopeSha256 = sha256Hex(envelopeBytes),
                byteSize = envelopeBytes.size.toLong(),
                createdAt = event.createdAt,
                expiresAt = event.expiresAt,
                custodyAcceptedAt = null,
                custodyRoute = null,
                attempts = 0,
                nextAttemptAt = event.createdAt,
                state = "NEW",
                lastError = null,
                requiresPeerReceipt = requiresPeerReceipt,
            )
        } finally {
            peerPublicKey.fill(0)
            ownSecretKey.fill(0)
            nonce.fill(0)
            plain.fill(0)
            ciphertext?.fill(0)
        }
    }

    private fun sha256Hex(value: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(value)
        .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
}

class PeerControlOutbox(
    private val store: PeerControlStore,
    private val sealer: PeerControlSealer,
    private val originDevice: suspend () -> String,
    private val clock: () -> Long = { System.currentTimeMillis().coerceAtLeast(0L) },
    private val newId: () -> String = { UUID.randomUUID().toString() },
) {
    constructor(context: Context, dao: ReliableDeliveryDao) : this(
        store = DaoPeerControlStore(dao),
        sealer = DurablePeerControlSealer(context.applicationContext),
        originDevice = { DeviceIdentity.getOrCreate(context.applicationContext) },
    )

    private val ensureLock = Mutex()
    private val stateLock = Any()
    private var bootstrap: BootstrapRegistration? = null
    private var activeProbe: ProbeRegistration? = null
    private var evidence: EvidenceRegistration? = null

    suspend fun ensureBootstrap(generation: Int, payload: LanBootstrapPayload): OutboundMessage =
        ensureLock.withLock {
            require(generation >= 0) { "transport generation must be non-negative" }
            bootstrap?.takeIf { it.generation == generation }?.let {
                require(it.payload == payload) { "LAN bootstrap payload changed within generation" }
                return@withLock it.row
            }
            val createdAt = validNow()
            val event = InnerEventV2(
                msgId = newId(),
                originDevice = originDevice(),
                type = "lan.bootstrap",
                canonId = null,
                sequence = null,
                createdAt = createdAt,
                expiresAt = Math.addExact(createdAt, BOOTSTRAP_TTL_MS),
                payloadJson = JSONObject()
                    .put("protocol_version", payload.protocolVersion)
                    .put("tls_spki_sha256", payload.tlsSpkiSha256)
                    .put("binding_context_sha256", payload.bindingContextSha256)
                    .toString(),
            )
            ProtocolJson.encodeInner(event)
            val row = sealer.seal(event, requiresPeerReceipt = true)
            requireReceiptBackedRow(row, event)
            store.insert(row)
            synchronized(stateLock) {
                bootstrap = BootstrapRegistration(generation, payload, row)
            }
            row
        }

    suspend fun ensureProbe(generation: Int, requestDirect: Boolean): OutboundMessage? =
        ensureLock.withLock {
            require(generation >= 0) { "transport generation must be non-negative" }
            val now = validNow()
            val tracked = synchronized(stateLock) { activeProbe }
            if (tracked != null && tracked.expiresAt > now) return@withLock null
            val recentEvidence = synchronized(stateLock) { evidence }
            if (recentEvidence?.generation == generation && now < recentEvidence.nextProbeAt) {
                return@withLock null
            }
            if (store.active("peer.probe", now) != null) return@withLock null

            val msgId = newId()
            val event = InnerEventV2(
                msgId = msgId,
                originDevice = originDevice(),
                type = "peer.probe",
                canonId = null,
                sequence = null,
                createdAt = now,
                expiresAt = Math.addExact(now, PROBE_TTL_MS),
                payloadJson = JSONObject()
                    .put("probe_id", msgId)
                    .put("sent_at", now)
                    .put("request_direct", requestDirect)
                    .toString(),
            )
            ProtocolJson.encodeInner(event)
            val row = sealer.seal(event, requiresPeerReceipt = true)
            requireReceiptBackedRow(row, event)
            store.insert(row)
            synchronized(stateLock) {
                activeProbe = ProbeRegistration(
                    generation = generation,
                    msgId = row.msgId,
                    envelopeSha256 = row.envelopeSha256,
                    expiresAt = row.expiresAt,
                )
            }
            row
        }

    fun acceptProbeReceipt(
        ackedMsgId: String,
        digest: String,
        generation: Int,
        now: Long,
    ): Boolean = synchronized(stateLock) {
        val probe = activeProbe ?: return@synchronized false
        if (generation != probe.generation || ackedMsgId != probe.msgId || digest != probe.envelopeSha256 ||
            now < 0 || now > probe.expiresAt
        ) return@synchronized false
        activeProbe = null
        evidence = EvidenceRegistration(
            generation = generation,
            receivedAt = now,
            nextProbeAt = saturatingAdd(now, PROBE_INTERVAL_MS),
        )
        true
    }

    fun peerEvidence(generation: Int, now: Long): PeerEvidence = synchronized(stateLock) {
        val current = evidence
        if (current == null || current.generation != generation || now < current.receivedAt) {
            return@synchronized PeerEvidence.UNKNOWN
        }
        if (now - current.receivedAt <= EVIDENCE_FRESH_MS) PeerEvidence.RECENT else PeerEvidence.STALE
    }

    private fun validNow(): Long = clock().also { require(it >= 0) { "clock must be non-negative" } }

    private fun requireReceiptBackedRow(row: OutboundMessage, event: InnerEventV2) {
        require(row.msgId == event.msgId && row.eventType == event.type && row.expiresAt == event.expiresAt) {
            "peer control sealer changed event identity"
        }
        require(row.requiresPeerReceipt) { "peer controls require a peer receipt" }
    }

    private fun saturatingAdd(value: Long, increment: Long): Long =
        if (value > Long.MAX_VALUE - increment) Long.MAX_VALUE else value + increment

    private data class BootstrapRegistration(
        val generation: Int,
        val payload: LanBootstrapPayload,
        val row: OutboundMessage,
    )

    private data class ProbeRegistration(
        val generation: Int,
        val msgId: String,
        val envelopeSha256: String,
        val expiresAt: Long,
    )

    private data class EvidenceRegistration(
        val generation: Int,
        val receivedAt: Long,
        val nextProbeAt: Long,
    )

    private companion object {
        const val BOOTSTRAP_TTL_MS = 600_000L
        const val PROBE_TTL_MS = 120_000L
        const val PROBE_INTERVAL_MS = 60_000L
        const val EVIDENCE_FRESH_MS = 150_000L
    }
}

private class DaoPeerControlStore(private val dao: ReliableDeliveryDao) : PeerControlStore {
    override suspend fun insert(row: OutboundMessage) = dao.insertOutbound(row)

    override suspend fun active(eventType: String, now: Long): OutboundMessage? =
        dao.activeOutboundControl(eventType, now)
}
