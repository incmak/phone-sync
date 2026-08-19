package co.twinotify.core.listener

import android.content.Context
import android.util.Base64
import co.twinotify.core.crypto.CryptoStore
import co.twinotify.core.crypto.Encrypter
import co.twinotify.core.crypto.NonceSource
import co.twinotify.core.call.CallStateEvent
import co.twinotify.core.call.CallStatePersistResult
import co.twinotify.core.protocol.EncryptedEnvelope
import co.twinotify.core.protocol.InnerEventV2
import co.twinotify.core.protocol.ProtocolJson
import co.twinotify.core.storage.CanonicalNotificationState
import co.twinotify.core.storage.CallRecoveryCommitResult
import co.twinotify.core.storage.DeviceIdentity
import co.twinotify.core.storage.NotificationDb
import co.twinotify.core.storage.OutboundMessage
import co.twinotify.core.storage.OutboundStateCommitResult
import co.twinotify.core.storage.PeerStore
import java.security.MessageDigest
import java.util.UUID
import org.json.JSONObject

/**
 * The application capture boundary. Preparation is independent of the listener lifecycle; the
 * final canonical-state and outbox mutation is one Room transaction in [ReliableDeliveryDao].
 */
class DurableCapturePersister(context: Context) : CapturePersister {
    private val appContext = context.applicationContext
    private val dao = NotificationDb.get(appContext).reliableDeliveryDao()

    override suspend fun persist(command: CaptureCommand): CapturePersistResult {
        val peer = PeerStore.load(appContext)
            ?: throw CaptureNotPairedException("capture deferred until a peer is paired")
        val originDevice = DeviceIdentity.getOrCreate(appContext)
        val current = dao.canonical(command.canonId)
        val eventType = when (command) {
            is PostCommand -> if (current?.state == "ACTIVE") "notif.update" else "notif.post"
            is RemoveCommand -> "notif.cancel"
        }
        // Sequence allocation is finalized by commitCapturedState in the same Room transaction
        // as the canonical/outbox mutation. This read only prepares the encrypted payload; a
        // concurrent writer loses the compare-and-increment and is rebuilt with the next value.
        val sequence = dao.nextCaptureSequenceForEvent(command.canonId)
        val now = when (command) {
            is RemoveCommand -> command.removedAt.coerceAtLeast(0L)
            is PostCommand -> command.snapshot.postTime.coerceAtLeast(0L)
        }.coerceAtLeast(System.currentTimeMillis())
        val expiresAt = now + RETENTION_MS
        val payloadJson = when (command) {
            is PostCommand -> NotifPostBuilder.toPayloadJson(
                NotifPostBuilder.build(command.snapshot, appContext, originDevice, eventType),
            )
            is RemoveCommand -> JSONObject().apply {
                put("reason", command.reason)
                put("removed_at", command.removedAt)
            }.toString()
        }
        val msgId = UUID.randomUUID().toString()
        val inner = InnerEventV2(
            msgId = msgId,
            // The authenticated emitter remains local. Canonical ownership is preserved below.
            originDevice = originDevice,
            type = eventType,
            canonId = command.canonId,
            sequence = sequence,
            createdAt = now,
            expiresAt = expiresAt,
            payloadJson = payloadJson,
        )
        val innerJson = ProtocolJson.encodeInner(inner)
        val (box, _) = CryptoStore.loadOrGenerate(appContext)
        val nonce = NonceSource.next(appContext)
        val ciphertext = Encrypter.encrypt(
            plain = innerJson.toByteArray(Charsets.UTF_8),
            nonce = nonce,
            peerPubkey = peer.encPubkey,
            ownSecret = box.secretKey,
        )
        val envelope = EncryptedEnvelope(
            version = ProtocolJson.VERSION,
            msgId = msgId,
            originDevice = originDevice,
            createdAt = now,
            nonceB64 = Base64.encodeToString(nonce, Base64.NO_WRAP),
            ciphertextB64 = Base64.encodeToString(ciphertext, Base64.NO_WRAP),
        )
        val envelopeJson = ProtocolJson.encodeEnvelope(envelope)
        val desired = CanonicalNotificationState(
            canonId = command.canonId,
            // A peer mirror may be cancelled by the local user without transferring canonical
            // ownership, so the source owner remains stable on both devices.
            originDevice = current?.originDevice ?: originDevice,
            latestSequence = sequence,
            state = if (eventType == "notif.cancel") "CANCELLED" else "ACTIVE",
            desiredPayloadJson = if (eventType == "notif.cancel") null else payloadJson,
            materializedSequence = current?.materializedSequence ?: 0L,
            sourceNotificationKey = command.sourceKey.takeIf { it.isNotEmpty() }
                ?: current?.sourceNotificationKey,
            mirrorLocalId = current?.mirrorLocalId,
            mirrorLocalTag = current?.mirrorLocalTag,
            peerCancelPending = current?.peerCancelPending ?: false,
            updatedAt = now,
        )
        val row = OutboundMessage(
            msgId = msgId,
            canonId = command.canonId,
            sequence = sequence,
            eventType = eventType,
            protocolVersion = ProtocolJson.VERSION,
            envelopeJson = envelopeJson,
            envelopeSha256 = sha256(envelopeJson),
            byteSize = envelopeJson.toByteArray(Charsets.UTF_8).size.toLong(),
            createdAt = now,
            expiresAt = expiresAt,
            relayAcceptedAt = null,
            attempts = 0,
            nextAttemptAt = now,
            state = "NEW",
            lastError = null,
            requiresPeerReceipt = true,
        )
        when (val result = dao.commitCapturedState(desired, row)) {
            is OutboundStateCommitResult.Committed -> return CapturePersistResult(sequence, msgId)
            is OutboundStateCommitResult.Stale -> return persist(command)
            OutboundStateCommitResult.NotStateEvent -> error("capture produced unsupported event type")
        }
    }

    /** Authenticated v2 durable boundary for privacy-bounded cellular call state. */
    suspend fun persistCallState(event: CallStateEvent): CallStatePersistResult =
        persistCallState(event, expectedLocalOrigin = null)

    /** Recovery-only boundary that atomically refuses to terminate a call no longer owned locally. */
    internal suspend fun persistRecoveredCallState(
        event: CallStateEvent,
        expectedLocalOrigin: String,
    ): CallStatePersistResult = persistCallState(event, expectedLocalOrigin)

    private suspend fun persistCallState(
        event: CallStateEvent,
        expectedLocalOrigin: String?,
    ): CallStatePersistResult {
        val peer = PeerStore.load(appContext)
            ?: throw CaptureNotPairedException("call capture deferred until a peer is paired")
        val originDevice = DeviceIdentity.getOrCreate(appContext)
        val canonId = "call:${event.callSessionId}"
        val current = dao.canonical(canonId)
        val now = System.currentTimeMillis().coerceAtLeast(0L)
        val payloadJson = JSONObject()
            .put("call_session_id", event.callSessionId)
            .put("state", event.state)
            .put(
                "direction",
                when (event.direction) {
                    co.twinotify.core.call.CallDirection.INCOMING -> "incoming"
                    co.twinotify.core.call.CallDirection.OUTGOING -> "outgoing"
                    co.twinotify.core.call.CallDirection.UNKNOWN -> "unknown"
                },
            )
            .toString()
        val msgId = UUID.randomUUID().toString()
        val inner = InnerEventV2(
            msgId = msgId,
            originDevice = originDevice,
            type = "call.state",
            canonId = canonId,
            sequence = event.sequence,
            createdAt = now,
            expiresAt = now + RETENTION_MS,
            payloadJson = payloadJson,
        )
        val (box, _) = CryptoStore.loadOrGenerate(appContext)
        val nonce = NonceSource.next(appContext)
        val ciphertext = Encrypter.encrypt(
            plain = ProtocolJson.encodeInner(inner).toByteArray(Charsets.UTF_8),
            nonce = nonce,
            peerPubkey = peer.encPubkey,
            ownSecret = box.secretKey,
        )
        val envelopeJson = ProtocolJson.encodeEnvelope(
            EncryptedEnvelope(
                version = ProtocolJson.VERSION,
                msgId = msgId,
                originDevice = originDevice,
                createdAt = now,
                nonceB64 = Base64.encodeToString(nonce, Base64.NO_WRAP),
                ciphertextB64 = Base64.encodeToString(ciphertext, Base64.NO_WRAP),
            ),
        )
        val row = OutboundMessage(
            msgId = msgId,
            canonId = canonId,
            sequence = event.sequence,
            eventType = "call.state",
            protocolVersion = ProtocolJson.VERSION,
            envelopeJson = envelopeJson,
            envelopeSha256 = sha256(envelopeJson),
            byteSize = envelopeJson.toByteArray(Charsets.UTF_8).size.toLong(),
            createdAt = now,
            expiresAt = now + RETENTION_MS,
            relayAcceptedAt = null,
            attempts = 0,
            nextAttemptAt = now,
            state = "NEW",
            lastError = null,
            requiresPeerReceipt = true,
        )
        val desired = CanonicalNotificationState(
            canonId = canonId,
            originDevice = expectedLocalOrigin ?: current?.originDevice ?: originDevice,
            latestSequence = event.sequence,
            state = if (event.state == "idle") "CANCELLED" else "ACTIVE",
            desiredPayloadJson = if (event.state == "idle") null else payloadJson,
            materializedSequence = current?.materializedSequence ?: 0L,
            sourceNotificationKey = null,
            mirrorLocalId = current?.mirrorLocalId,
            mirrorLocalTag = current?.mirrorLocalTag,
            peerCancelPending = current?.peerCancelPending ?: false,
            updatedAt = now,
        )
        if (expectedLocalOrigin == null) {
            return when (val result = dao.commitCapturedState(desired, row)) {
                is OutboundStateCommitResult.Committed -> CallStatePersistResult.Persisted(event.sequence, msgId)
                is OutboundStateCommitResult.Stale -> CallStatePersistResult.Stale(result.latestSequence)
                OutboundStateCommitResult.NotStateEvent -> error("call capture produced unsupported event type")
            }
        }
        return when (val result = dao.commitRecoveredCallState(desired, row, expectedLocalOrigin)) {
            is CallRecoveryCommitResult.Committed -> CallStatePersistResult.Persisted(event.sequence, msgId)
            is CallRecoveryCommitResult.Stale -> CallStatePersistResult.Stale(result.latestSequence)
            CallRecoveryCommitResult.OwnershipLost -> CallStatePersistResult.OwnershipLost
            CallRecoveryCommitResult.NotStateEvent -> error("call recovery produced unsupported event type")
        }
    }

    /** Control events have no canonical sequence but use the same durable v2 outbox boundary. */
    suspend fun persistUnpair(reason: String, originDevice: String, timestamp: Long) {
        val peer = PeerStore.load(appContext)
            ?: throw CaptureNotPairedException("unpair capture requires a paired peer")
        val createdAt = timestamp.coerceAtLeast(0L).coerceAtLeast(System.currentTimeMillis())
        val expiresAt = createdAt + RETENTION_MS
        val msgId = UUID.randomUUID().toString()
        val inner = InnerEventV2(
            msgId = msgId,
            originDevice = originDevice,
            type = "unpair",
            canonId = null,
            sequence = null,
            createdAt = createdAt,
            expiresAt = expiresAt,
            payloadJson = JSONObject().put("reason", reason).toString(),
        )
        val (box, _) = CryptoStore.loadOrGenerate(appContext)
        val nonce = NonceSource.next(appContext)
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
        dao.insertOutbound(
            OutboundMessage(
                msgId = msgId,
                canonId = null,
                sequence = null,
                eventType = "unpair",
                protocolVersion = ProtocolJson.VERSION,
                envelopeJson = envelope,
                envelopeSha256 = sha256(envelope),
                byteSize = envelope.toByteArray(Charsets.UTF_8).size.toLong(),
                createdAt = createdAt,
                expiresAt = expiresAt,
                relayAcceptedAt = null,
                attempts = 0,
                nextAttemptAt = createdAt,
                state = "NEW",
                lastError = null,
                requiresPeerReceipt = false,
            ),
        )
    }

    /** Encrypts and durably queues one authenticated snapshot control/item event. */
    suspend fun persistSnapshotEvent(event: Any) {
        val originDevice = DeviceIdentity.getOrCreate(appContext)
        val (type, values) = when (event) {
            is co.twinotify.core.service.StateDigest -> "state.digest" to Triple(
                null,
                null,
                JSONObject().apply {
                    put("origin_device", event.originDevice)
                    put("origin_epoch", event.originEpoch)
                    put("count", event.count)
                    put("digest", event.digest)
                }.toString(),
            )
            is co.twinotify.core.service.SnapshotBeginEvent -> "state.snapshot.begin" to Triple(
                null,
                null,
                JSONObject().apply {
                    put("snapshot_id", event.snapshotId)
                    put("origin_device", event.originDevice)
                    put("origin_epoch", event.originEpoch)
                    put("item_count", event.itemCount)
                }.toString(),
            )
            is co.twinotify.core.service.SnapshotItemEvent -> "state.snapshot.item" to Triple(
                event.canonId,
                event.sequence,
                JSONObject().apply {
                    put("snapshot_id", event.snapshotId)
                    put("notification_payload", JSONObject(event.payloadJson))
                }.toString(),
            )
            is co.twinotify.core.service.SnapshotEndEvent -> "state.snapshot.end" to Triple(
                null,
                null,
                JSONObject().apply {
                    put("snapshot_id", event.snapshotId)
                    put("origin_device", event.originDevice)
                    put("digest", event.digest)
                }.toString(),
            )
            else -> error("unsupported snapshot event ${event::class.java.name}")
        }
        val (canonId, sequence, payloadJson) = values
        persistEncryptedControl(
            originDevice = originDevice,
            type = type,
            canonId = canonId,
            sequence = sequence,
            payloadJson = payloadJson,
        )
    }

    private suspend fun persistEncryptedControl(
        originDevice: String,
        type: String,
        canonId: String?,
        sequence: Long?,
        payloadJson: String,
    ) {
        val peer = PeerStore.load(appContext)
            ?: throw CaptureNotPairedException("control event deferred until a peer is paired")
        val createdAt = System.currentTimeMillis().coerceAtLeast(0L)
        val expiresAt = createdAt + RETENTION_MS
        val msgId = UUID.randomUUID().toString()
        val inner = InnerEventV2(msgId, originDevice, type, canonId, sequence, createdAt, expiresAt, payloadJson)
        val (box, _) = CryptoStore.loadOrGenerate(appContext)
        val nonce = NonceSource.next(appContext)
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
        dao.insertOutbound(
            OutboundMessage(
                msgId = msgId,
                canonId = canonId,
                sequence = sequence,
                eventType = type,
                protocolVersion = ProtocolJson.VERSION,
                envelopeJson = envelope,
                envelopeSha256 = sha256(envelope),
                byteSize = envelope.toByteArray(Charsets.UTF_8).size.toLong(),
                createdAt = createdAt,
                expiresAt = expiresAt,
                relayAcceptedAt = null,
                attempts = 0,
                nextAttemptAt = createdAt,
                state = "NEW",
                lastError = null,
                // Snapshot controls are idempotent convergence hints. The receiver stages and
                // validates them atomically; retaining each control until a peer receipt would
                // create a receipt recursion with no user-visible notification state.
                requiresPeerReceipt = false,
            ),
        )
    }

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xff) }

    companion object {
        private const val RETENTION_MS = 24 * 60 * 60 * 1_000L
    }
}

class CaptureNotPairedException(message: String) : IllegalStateException(message)
