package co.twinotify.core.protocol

import java.util.Base64
import java.util.Locale
import java.util.UUID
import org.json.JSONException
import org.json.JSONObject

/**
 * The single v2 JSON boundary. It intentionally validates the same field constraints as the
 * committed protocol schemas before a frame can reach crypto or durable state.
 */
object ProtocolJson {
    const val VERSION = 2
    const val MAX_ENVELOPE_BYTES = 1024 * 1024
    private const val MAX_ORIGIN_DEVICE_LENGTH = 128
    private const val MAX_CANON_ID_LENGTH = 1024
    private const val MAX_RECEIPT_REASON_LENGTH = 128
    private const val MAX_REPLY_BYTES = 4096
    private const val ACTION_INVOKE_TTL_MS = 120_000L
    private const val ACTION_RESULT_TTL_MS = 600_000L
    private const val LAN_BOOTSTRAP_TTL_MS = 600_000L
    private const val PEER_PROBE_TTL_MS = 120_000L
    private const val ENVELOPE_TYPE = "enc"
    private val receiptStatuses = setOf("applied", "expired", "rejected", "decrypt_failed")
    private val actionResultStatuses = setOf(
        "dispatched",
        "outcome_unknown",
        "action_gone",
        "notification_gone",
        "expired",
        "failed",
    )

    private val innerTypes = setOf(
        "notif.post",
        "notif.update",
        "notif.cancel",
        "notif.action.invoke",
        "notif.action.result",
        "call.state",
        "peer.receipt",
        "peer.probe",
        "lan.bootstrap",
        "state.digest",
        "state.snapshot.begin",
        "state.snapshot.item",
        "state.snapshot.end",
        "unpair",
    )
    private val canonicalTypes = setOf(
        "notif.post",
        "notif.update",
        "notif.cancel",
        "call.state",
        "state.snapshot.item",
    )

    fun encodeInner(event: InnerEventV2): String {
        validateInner(event)
        return JSONObject().apply {
            put("v", VERSION)
            put("msg_id", event.msgId)
            put("origin_device", event.originDevice)
            put("type", event.type)
            event.canonId?.let { put("canon_id", it) }
            event.sequence?.let { put("sequence", it) }
            put("created_at", event.createdAt)
            put("expires_at", event.expiresAt)
            put("payload", JSONObject(event.payloadJson))
        }.toString()
    }

    fun decodeInner(raw: String): InnerEventV2 {
        val objectValue = parseObject(raw, "inner event")
        requireOnlyKeys(
            objectValue,
            setOf(
                "v",
                "msg_id",
                "origin_device",
                "type",
                "canon_id",
                "sequence",
                "created_at",
                "expires_at",
                "payload",
            ),
            "inner event",
        )
        val event = InnerEventV2(
            msgId = requiredUuid(objectValue, "msg_id", "inner event"),
            originDevice = requiredDevice(objectValue, "origin_device", "inner event"),
            type = requiredString(objectValue, "type", "inner event"),
            canonId = optionalString(objectValue, "canon_id", "inner event"),
            sequence = optionalPositiveLong(objectValue, "sequence", "inner event"),
            createdAt = requiredNonNegativeLong(objectValue, "created_at", "inner event"),
            expiresAt = requiredNonNegativeLong(objectValue, "expires_at", "inner event"),
            payloadJson = requiredObject(objectValue, "payload", "inner event").toString(),
        )
        require(requiredInt(objectValue, "v", "inner event") == VERSION) {
            "inner event must use protocol version $VERSION"
        }
        validateInner(event)
        return event
    }

    fun encodeEnvelope(envelope: EncryptedEnvelope): String {
        validateEnvelope(envelope)
        val encoded = JSONObject().apply {
            put("v", VERSION)
            put("type", ENVELOPE_TYPE)
            put("msg_id", envelope.msgId)
            put("origin_device", envelope.originDevice)
            put("created_at", envelope.createdAt)
            put("nonce", envelope.nonceB64)
            put("ciphertext", envelope.ciphertextB64)
        }.toString()
        require(encoded.encodeToByteArray().size <= MAX_ENVELOPE_BYTES) {
            "encrypted envelope exceeds $MAX_ENVELOPE_BYTES bytes"
        }
        return encoded
    }

    fun decodeEnvelope(raw: String): EncryptedEnvelope {
        require(raw.encodeToByteArray().size <= MAX_ENVELOPE_BYTES) {
            "encrypted envelope exceeds $MAX_ENVELOPE_BYTES bytes"
        }
        val objectValue = parseObject(raw, "encrypted envelope")
        requireOnlyKeys(
            objectValue,
            setOf("v", "type", "msg_id", "origin_device", "created_at", "nonce", "ciphertext"),
            "encrypted envelope",
        )
        require(requiredInt(objectValue, "v", "encrypted envelope") == VERSION) {
            "encrypted envelope must use protocol version $VERSION"
        }
        require(requiredString(objectValue, "type", "encrypted envelope") == ENVELOPE_TYPE) {
            "encrypted envelope must use type $ENVELOPE_TYPE"
        }
        return EncryptedEnvelope(
            version = VERSION,
            msgId = requiredUuid(objectValue, "msg_id", "encrypted envelope"),
            originDevice = requiredDevice(objectValue, "origin_device", "encrypted envelope"),
            createdAt = requiredNonNegativeLong(objectValue, "created_at", "encrypted envelope"),
            nonceB64 = requiredString(objectValue, "nonce", "encrypted envelope"),
            ciphertextB64 = requiredString(objectValue, "ciphertext", "encrypted envelope"),
        ).also(::validateEnvelope)
    }

    fun JSONObject.optNullableString(key: String): String? =
        if (!has(key) || isNull(key)) null else getString(key)

    fun JSONObject.putNullable(key: String, value: String?) {
        put(key, value ?: JSONObject.NULL)
    }

    private fun validateInner(event: InnerEventV2) {
        validateUuid(event.msgId, "inner event msg_id")
        validateDevice(event.originDevice, "inner event origin_device")
        require(event.type in innerTypes) { "unsupported inner event type ${event.type}" }
        if (event.canonId != null) {
            require(event.canonId.isNotEmpty() && event.canonId.length <= MAX_CANON_ID_LENGTH) {
                "inner event canon_id must be 1..$MAX_CANON_ID_LENGTH characters"
            }
        }
        if (event.sequence != null) require(event.sequence >= 1) { "inner event sequence must be positive" }
        if (event.type in canonicalTypes) {
            require(event.canonId != null) { "${event.type} requires canon_id" }
            require(event.sequence != null) { "${event.type} requires sequence" }
        }
        require(event.createdAt >= 0) { "inner event created_at must be non-negative" }
        require(event.expiresAt > event.createdAt) { "inner event expires_at must be after created_at" }
        val payload = try {
            JSONObject(event.payloadJson)
        } catch (error: JSONException) {
            throw ProtocolException("inner event payload must be a JSON object", error)
        }
        if (event.type == "peer.receipt") validateReceiptPayload(payload)
        if (event.type == "lan.bootstrap") {
            require(event.canonId == null && event.sequence == null) {
                "lan.bootstrap must not carry canon_id or sequence"
            }
            validateLanBootstrapPayload(payload)
            require(event.expiresAt - event.createdAt == LAN_BOOTSTRAP_TTL_MS) {
                "lan.bootstrap expires_at must be created_at + $LAN_BOOTSTRAP_TTL_MS"
            }
        }
        if (event.type == "peer.probe") {
            require(event.canonId == null && event.sequence == null) {
                "peer.probe must not carry canon_id or sequence"
            }
            validatePeerProbePayload(payload, event.msgId)
            require(event.expiresAt - event.createdAt == PEER_PROBE_TTL_MS) {
                "peer.probe expires_at must be created_at + $PEER_PROBE_TTL_MS"
            }
        }
        if (event.type == "call.state") validateCallStatePayload(payload, event.canonId, event.sequence)
        if (event.type == "notif.action.invoke") {
            validateActionInvokePayload(payload)
            require(event.expiresAt - event.createdAt == ACTION_INVOKE_TTL_MS) {
                "notif.action.invoke expires_at must be created_at + $ACTION_INVOKE_TTL_MS"
            }
            require(payload.getLong("invoked_at") == event.createdAt) {
                "notif.action.invoke invoked_at must equal created_at"
            }
        }
        if (event.type == "notif.action.result") {
            validateActionResultPayload(payload)
            require(event.expiresAt - event.createdAt == ACTION_RESULT_TTL_MS) {
                "notif.action.result expires_at must be created_at + $ACTION_RESULT_TTL_MS"
            }
        }
    }

    private fun validateEnvelope(envelope: EncryptedEnvelope) {
        require(envelope.version == VERSION) { "encrypted envelope must use protocol version $VERSION" }
        validateUuid(envelope.msgId, "encrypted envelope msg_id")
        validateDevice(envelope.originDevice, "encrypted envelope origin_device")
        require(envelope.createdAt >= 0) { "encrypted envelope created_at must be non-negative" }
        val nonce = decodeBase64(envelope.nonceB64, "nonce")
        require(nonce.size == 24) { "encrypted envelope nonce must decode to 24 bytes" }
        val ciphertext = decodeBase64(envelope.ciphertextB64, "ciphertext")
        require(ciphertext.isNotEmpty()) { "encrypted envelope ciphertext must not be empty" }
    }

    private fun validateReceiptPayload(payload: JSONObject) {
        requireOnlyKeys(
            payload,
            setOf("acked_msg_id", "envelope_sha256", "status", "reason"),
            "peer.receipt payload",
        )
        requiredUuid(payload, "acked_msg_id", "peer.receipt payload")
        val digest = requiredString(payload, "envelope_sha256", "peer.receipt payload")
        require(digest.matches(Regex("^[0-9a-f]{64}$"))) {
            "peer.receipt payload envelope_sha256 must be lower-case SHA-256"
        }
        val status = requiredString(payload, "status", "peer.receipt payload")
        require(status in receiptStatuses) { "unsupported peer receipt status $status" }
        val reason = optionalString(payload, "reason", "peer.receipt payload")
        if (status == "rejected" || status == "decrypt_failed") {
            require(!reason.isNullOrEmpty()) { "peer.receipt payload $status requires a reason" }
        }
        reason?.let { value ->
            require(value.codePointCount(0, value.length) <= MAX_RECEIPT_REASON_LENGTH) {
                "peer.receipt payload reason must be at most $MAX_RECEIPT_REASON_LENGTH characters"
            }
        }
    }

    private fun validateLanBootstrapPayload(payload: JSONObject) {
        requireOnlyKeys(
            payload,
            setOf("protocol_version", "tls_spki_sha256", "binding_context_sha256"),
            "lan.bootstrap payload",
        )
        require(requiredInt(payload, "protocol_version", "lan.bootstrap payload") == 1) {
            "lan.bootstrap payload protocol_version must be 1"
        }
        for (key in listOf("tls_spki_sha256", "binding_context_sha256")) {
            val digest = requiredString(payload, key, "lan.bootstrap payload")
            require(digest.matches(Regex("^[0-9a-f]{64}$"))) {
                "lan.bootstrap payload $key must be lower-case SHA-256"
            }
        }
    }

    private fun validatePeerProbePayload(payload: JSONObject, msgId: String) {
        requireOnlyKeys(
            payload,
            setOf("probe_id", "sent_at", "request_direct"),
            "peer.probe payload",
        )
        val probeId = requiredUuid(payload, "probe_id", "peer.probe payload")
        require(UUID.fromString(probeId).toString() == probeId) {
            "peer.probe payload probe_id must be a lower-case canonical UUID"
        }
        require(probeId == msgId) { "peer.probe payload probe_id must equal msg_id" }
        requiredNonNegativeLong(payload, "sent_at", "peer.probe payload")
        requiredBoolean(payload, "request_direct", "peer.probe payload")
    }

    private fun validateCallStatePayload(payload: JSONObject, canonId: String?, sequence: Long?) {
        requireOnlyKeys(
            payload,
            setOf("call_session_id", "state", "direction"),
            "call.state payload",
        )
        val sessionId = requiredUuid(payload, "call_session_id", "call.state payload")
        require(sessionId == UUID.fromString(sessionId).toString()) {
            "call.state payload call_session_id must be a lower-case canonical UUID"
        }
        require(canonId == "call:${sessionId.lowercase(Locale.ROOT)}") {
            "call.state canon_id must equal call:<call_session_id>"
        }
        require(sequence != null && sequence >= 1) { "call.state requires a positive sequence" }
        val state = requiredString(payload, "state", "call.state payload")
        require(state in setOf("ringing", "active", "idle")) {
            "unsupported call.state state $state"
        }
        val direction = requiredString(payload, "direction", "call.state payload")
        require(direction in setOf("incoming", "outgoing", "unknown")) {
            "unsupported call.state direction $direction"
        }
    }

    private fun validateActionInvokePayload(payload: JSONObject) {
        requireOnlyKeys(
            payload,
            setOf(
                "invocation_id",
                "canon_id",
                "action_id",
                "notification_sequence",
                "reply_text",
                "invoked_at",
            ),
            "notif.action.invoke payload",
        )
        requiredUuid(payload, "invocation_id", "notif.action.invoke payload")
        val canonId = requiredString(payload, "canon_id", "notif.action.invoke payload")
        require(canonId.isNotEmpty() && canonId.length <= MAX_CANON_ID_LENGTH) {
            "notif.action.invoke payload canon_id must be 1..$MAX_CANON_ID_LENGTH characters"
        }
        requiredUuid(payload, "action_id", "notif.action.invoke payload")
        require(requiredLong(payload, "notification_sequence", "notif.action.invoke payload") >= 1) {
            "notif.action.invoke payload notification_sequence must be positive"
        }
        optionalString(payload, "reply_text", "notif.action.invoke payload")?.let { replyText ->
            require(replyText.toByteArray(Charsets.UTF_8).size <= MAX_REPLY_BYTES) {
                "notif.action.invoke payload reply_text must be at most $MAX_REPLY_BYTES UTF-8 bytes"
            }
        }
        requiredNonNegativeLong(payload, "invoked_at", "notif.action.invoke payload")
    }

    private fun validateActionResultPayload(payload: JSONObject) {
        requireOnlyKeys(
            payload,
            setOf("invocation_id", "canon_id", "status"),
            "notif.action.result payload",
        )
        requiredUuid(payload, "invocation_id", "notif.action.result payload")
        val canonId = requiredString(payload, "canon_id", "notif.action.result payload")
        require(canonId.isNotEmpty() && canonId.length <= MAX_CANON_ID_LENGTH) {
            "notif.action.result payload canon_id must be 1..$MAX_CANON_ID_LENGTH characters"
        }
        val status = requiredString(payload, "status", "notif.action.result payload")
        require(status in actionResultStatuses) {
            "unsupported notif.action.result status $status"
        }
    }

    private fun parseObject(raw: String, label: String): JSONObject = try {
        JSONObject(raw)
    } catch (error: JSONException) {
        throw ProtocolException("invalid $label JSON", error)
    }

    private fun requiredObject(value: JSONObject, key: String, label: String): JSONObject = try {
        value.get(key).let {
            if (it !is JSONObject) throw ProtocolException("$label $key must be an object")
            it
        }
    } catch (error: JSONException) {
        throw ProtocolException("$label requires $key", error)
    }

    private fun requiredString(value: JSONObject, key: String, label: String): String = try {
        value.get(key).let {
            if (it !is String) throw ProtocolException("$label $key must be a string")
            it
        }
    } catch (error: JSONException) {
        throw ProtocolException("$label requires $key", error)
    }

    private fun optionalString(value: JSONObject, key: String, label: String): String? {
        if (!value.has(key)) return null
        if (value.isNull(key)) throw ProtocolException("$label $key must be a string when present")
        return requiredString(value, key, label)
    }

    private fun requiredUuid(value: JSONObject, key: String, label: String): String =
        requiredString(value, key, label).also { validateUuid(it, "$label $key") }

    private fun requiredDevice(value: JSONObject, key: String, label: String): String =
        requiredString(value, key, label).also { validateDevice(it, "$label $key") }

    private fun requiredInt(value: JSONObject, key: String, label: String): Int =
        requiredLong(value, key, label).also {
            if (it !in Int.MIN_VALUE..Int.MAX_VALUE) throw ProtocolException("$label $key is out of range")
        }.toInt()

    private fun requiredNonNegativeLong(value: JSONObject, key: String, label: String): Long =
        requiredLong(value, key, label).also {
            if (it < 0) throw ProtocolException("$label $key must be non-negative")
        }

    private fun optionalPositiveLong(value: JSONObject, key: String, label: String): Long? {
        if (!value.has(key)) return null
        if (value.isNull(key)) throw ProtocolException("$label $key must be an integer when present")
        return requiredLong(value, key, label).also {
            if (it < 1) throw ProtocolException("$label $key must be positive")
        }
    }

    private fun requiredLong(value: JSONObject, key: String, label: String): Long = try {
        val number = value.get(key) as? Number ?: throw ProtocolException("$label $key must be an integer")
        val long = number.toLong()
        if (number.toDouble() != long.toDouble()) throw ProtocolException("$label $key must be an integer")
        long
    } catch (error: JSONException) {
        throw ProtocolException("$label requires $key", error)
    }

    private fun requiredBoolean(value: JSONObject, key: String, label: String): Boolean = try {
        value.get(key).let {
            if (it !is Boolean) throw ProtocolException("$label $key must be a boolean")
            it
        }
    } catch (error: JSONException) {
        throw ProtocolException("$label requires $key", error)
    }

    private fun requireOnlyKeys(value: JSONObject, allowed: Set<String>, label: String) {
        val unknown = value.keys().asSequence().filterNot(allowed::contains).toList()
        require(unknown.isEmpty()) { "$label contains unknown fields: ${unknown.joinToString()}" }
    }

    private fun validateUuid(value: String, label: String) {
        val uuid = try {
            UUID.fromString(value)
        } catch (error: IllegalArgumentException) {
            throw ProtocolException("$label must be a UUID", error)
        }
        require(uuid.toString().equals(value, ignoreCase = true)) { "$label must be a canonical UUID" }
    }

    private fun validateDevice(value: String, label: String) {
        require(value.isNotEmpty() && value.length <= MAX_ORIGIN_DEVICE_LENGTH) {
            "$label must be 1..$MAX_ORIGIN_DEVICE_LENGTH characters"
        }
    }

    private fun decodeBase64(value: String, label: String): ByteArray = try {
        Base64.getDecoder().decode(value)
    } catch (error: IllegalArgumentException) {
        throw ProtocolException("encrypted envelope $label is not valid base64", error)
    }
}

fun JSONObject.optNullableString(key: String): String? = ProtocolJson.run { optNullableString(key) }

fun JSONObject.putNullable(key: String, value: String?) = ProtocolJson.run { putNullable(key, value) }
