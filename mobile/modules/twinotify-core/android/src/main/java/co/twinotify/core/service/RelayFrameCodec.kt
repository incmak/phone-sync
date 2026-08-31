package co.twinotify.core.service

import co.twinotify.core.protocol.ProtocolJson
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

object RelayFeatures {
    const val LAN_BOOTSTRAP_V1 = "lan-bootstrap-v1"
    const val PEER_PROBE_V1 = "peer-probe-v1"
    val CURRENT: Set<String> = linkedSetOf(LAN_BOOTSTRAP_V1, PEER_PROBE_V1)

    internal fun validated(values: Collection<String>): Set<String> {
        require(values.isNotEmpty() && values.size <= CURRENT.size) { "feature list must be 1..${CURRENT.size} items" }
        require(values.toSet().size == values.size) { "feature list must be unique" }
        require(values.all(CURRENT::contains)) { "unsupported relay feature" }
        return values.toCollection(linkedSetOf())
    }
}

/**
 * The typed boundary for relay control frames.  The relay is intentionally opaque to the
 * encrypted envelope, but it is strict about the small control protocol around it.  Keeping
 * this parser separate from the WebSocket callback makes malformed input cheap to reject and
 * makes the transport deterministic in tests.
 */
sealed interface RelayFrame {
    val version: Int
    val type: String

    data class Hello(
        val protocols: List<Int>,
        val appVersion: String,
        val features: Set<String> = emptySet(),
    ) : RelayFrame {
        override val version: Int = 2
        override val type: String = "relay.hello"
    }

    data class Put(val envelope: String) : RelayFrame {
        override val version: Int = 2
        override val type: String = "relay.put"
    }

    data class Ack(val msgId: String, val envelopeSha256: String) : RelayFrame {
        override val version: Int = 2
        override val type: String = "relay.ack"
    }

    data class Accepted(val msgId: String, val acceptedAt: Long) : RelayFrame {
        override val version: Int = 2
        override val type: String = "relay.accepted"
    }

    data class LegacyForwarded(val msgId: String) : RelayFrame {
        override val version: Int = 2
        override val type: String = "relay.legacy_forwarded"
    }

    data class Deliver(val acceptedAt: Long, val envelope: String) : RelayFrame {
        override val version: Int = 2
        override val type: String = "relay.deliver"
    }

    data class Rejected(val msgId: String, val reason: String) : RelayFrame {
        override val version: Int = 2
        override val type: String = "relay.rejected"
    }

    data class Expired(val msgId: String, val expiredAt: Long) : RelayFrame {
        override val version: Int = 2
        override val type: String = "relay.expired"
    }

    data class Capabilities(
        val self: List<Int>,
        val peer: List<Int>,
        val floor: Int,
        val selfFeatures: Set<String> = emptySet(),
        val peerFeatures: Set<String> = emptySet(),
    ) : RelayFrame {
        override val version: Int = 2
        override val type: String = "relay.capabilities"
    }
}

/** Strict JSON codec for relay control frames. Unknown fields are rejected deliberately. */
object RelayFrameCodec {
    const val MAX_FRAME_BYTES = ProtocolJson.MAX_ENVELOPE_BYTES + 4 * 1024
    private val digestPattern = Regex("^[0-9a-f]{64}$")
    private val rejectionReasons = setOf(
        "mailbox_full", "id_conflict", "digest_mismatch", "not_recipient", "peer_legacy", "server_capacity", "invalid_frame",
    )

    fun encode(frame: RelayFrame): String = when (frame) {
        is RelayFrame.Hello -> JSONObject()
            .put("v", 2).put("type", frame.type)
            .put("protocols", JSONArray(frame.protocols))
            .put("app_version", frame.appVersion)
            .apply {
                if (frame.features.isNotEmpty()) {
                    put("features", JSONArray(RelayFeatures.validated(frame.features).toList()))
                }
            }
            .toString()
        is RelayFrame.Put -> envelopeFrame(frame.type, frame.envelope)
        is RelayFrame.Ack -> JSONObject()
            .put("v", 2).put("type", frame.type)
            .put("msg_id", canonicalUuid(frame.msgId))
            .put("envelope_sha256", canonicalDigest(frame.envelopeSha256))
            .toString()
        is RelayFrame.Accepted -> JSONObject()
            .put("v", 2).put("type", frame.type)
            .put("msg_id", canonicalUuid(frame.msgId))
            .put("accepted_at", nonNegative(frame.acceptedAt, "accepted_at"))
            .toString()
        is RelayFrame.LegacyForwarded -> JSONObject()
            .put("v", 2).put("type", frame.type)
            .put("msg_id", canonicalUuid(frame.msgId))
            .toString()
        is RelayFrame.Deliver -> """{"v":2,"type":"${frame.type}","accepted_at":${nonNegative(frame.acceptedAt, "accepted_at")},"envelope":${canonicalEnvelope(frame.envelope)}}"""
        is RelayFrame.Rejected -> JSONObject()
            .put("v", 2).put("type", frame.type)
            .put("msg_id", canonicalUuid(frame.msgId))
            .put("reason", frame.reason.also { require(it in rejectionReasons) { "unsupported relay rejection reason" } })
            .toString()
        is RelayFrame.Expired -> JSONObject()
            .put("v", 2).put("type", frame.type)
            .put("msg_id", canonicalUuid(frame.msgId))
            .put("expired_at", nonNegative(frame.expiredAt, "expired_at"))
            .toString()
        is RelayFrame.Capabilities -> JSONObject()
            .put("v", 2).put("type", frame.type)
            .put("self", JSONArray(protocols(frame.self)))
            .put("peer", JSONArray(protocols(frame.peer)))
            .put("floor", frame.floor.also { require(it == 1 || it == 2) { "relay floor must be 1 or 2" } })
            .apply {
                require(frame.selfFeatures.isNotEmpty() || frame.peerFeatures.isEmpty()) {
                    "peer features require a feature-aware self"
                }
                if (frame.selfFeatures.isNotEmpty()) {
                    put("self_features", JSONArray(RelayFeatures.validated(frame.selfFeatures).toList()))
                    put("peer_features", JSONArray(frame.peerFeatures.let {
                        if (it.isEmpty()) emptyList() else RelayFeatures.validated(it).toList()
                    }))
                }
            }
            .toString()
    }.also { require(it.encodeToByteArray().size <= MAX_FRAME_BYTES) { "relay frame exceeds limit" } }

    fun decode(raw: String): RelayFrame {
        require(raw.encodeToByteArray().size <= MAX_FRAME_BYTES) { "relay frame exceeds limit" }
        val o = try { JSONObject(raw) } catch (e: Throwable) {
            throw IllegalArgumentException("invalid relay frame", e)
        }
        val type = o.requiredString("type")
        require(o.requiredInt("v") == 2) { "relay frame must use protocol version 2" }
        return when (type) {
            "relay.hello" -> {
                requireKeys(o, setOf("v", "type", "protocols", "app_version", "features"))
                RelayFrame.Hello(
                    protocols(o.requiredArray("protocols")),
                    o.requiredString("app_version").also {
                        require(it.isNotEmpty() && it.length <= 32) { "invalid app_version" }
                    },
                    o.optionalFeatures("features"),
                )
            }
            "relay.put" -> {
                requireKeys(o, setOf("v", "type", "envelope"))
                RelayFrame.Put(canonicalEnvelope(o.requiredObject("envelope").toString()))
            }
            "relay.ack" -> {
                requireKeys(o, setOf("v", "type", "msg_id", "envelope_sha256"))
                RelayFrame.Ack(canonicalUuid(o.requiredString("msg_id")), canonicalDigest(o.requiredString("envelope_sha256")))
            }
            "relay.accepted" -> {
                requireKeys(o, setOf("v", "type", "msg_id", "accepted_at"))
                RelayFrame.Accepted(canonicalUuid(o.requiredString("msg_id")), o.requiredNonNegativeLong("accepted_at"))
            }
            "relay.legacy_forwarded" -> {
                requireKeys(o, setOf("v", "type", "msg_id"))
                RelayFrame.LegacyForwarded(canonicalUuid(o.requiredString("msg_id")))
            }
            "relay.deliver" -> {
                requireKeys(o, setOf("v", "type", "accepted_at", "envelope"))
                RelayFrame.Deliver(o.requiredNonNegativeLong("accepted_at"), canonicalEnvelope(o.requiredObject("envelope").toString()))
            }
            "relay.rejected" -> {
                requireKeys(o, setOf("v", "type", "msg_id", "reason"))
                RelayFrame.Rejected(canonicalUuid(o.requiredString("msg_id")), o.requiredString("reason")
                    .also { require(it in rejectionReasons) { "unsupported relay rejection reason" } })
            }
            "relay.expired" -> {
                requireKeys(o, setOf("v", "type", "msg_id", "expired_at"))
                RelayFrame.Expired(canonicalUuid(o.requiredString("msg_id")), o.requiredNonNegativeLong("expired_at"))
            }
            "relay.capabilities" -> {
                requireKeys(o, setOf("v", "type", "self", "peer", "floor", "self_features", "peer_features"))
                require(o.has("self_features") == o.has("peer_features")) {
                    "relay feature capability fields must be paired"
                }
                RelayFrame.Capabilities(
                    protocols(o.requiredArray("self")),
                    protocols(o.requiredArray("peer")),
                    o.requiredInt("floor").also { require(it == 1 || it == 2) { "relay floor must be 1 or 2" } },
                    o.optionalFeatures("self_features"),
                    o.optionalFeatures("peer_features", allowEmpty = true),
                )
            }
            else -> throw IllegalArgumentException("unsupported relay frame type $type")
        }
    }

    private fun protocols(array: JSONArray): List<Int> = (0 until array.length()).map { array.getInt(it) }.let(::protocols)

    /** v1 envelopes are intentionally opaque but still strictly shape-checked. */
    private fun canonicalEnvelope(raw: String): String {
        val objectValue = JSONObject(raw)
        return when (objectValue.optInt("v", -1)) {
            2 -> ProtocolJson.encodeEnvelope(ProtocolJson.decodeEnvelope(raw))
            1 -> {
                requireKeys(objectValue, setOf("v", "type", "msg_id", "origin_device", "ts", "nonce", "ciphertext"))
                require(objectValue.getString("type") == "enc") { "legacy envelope must use type enc" }
                canonicalUuid(objectValue.getString("msg_id"))
                require(objectValue.getString("origin_device").isNotBlank()) { "legacy origin_device is required" }
                require(objectValue.getLong("ts") >= 0) { "legacy ts must be non-negative" }
                require(objectValue.getString("nonce").isNotBlank()) { "legacy nonce is required" }
                require(objectValue.getString("ciphertext").isNotBlank()) { "legacy ciphertext is required" }
                objectValue.toString()
            }
            else -> throw IllegalArgumentException("encrypted envelope must use protocol version 1 or 2")
        }
    }

    private fun envelopeFrame(type: String, envelope: String): String =
        """{"v":2,"type":"$type","envelope":${canonicalEnvelope(envelope)}}"""
    private fun protocols(values: List<Int>): List<Int> {
        require(values.isNotEmpty() && values.distinct().size == values.size) { "protocol list must be non-empty and unique" }
        require(values.all { it == 1 || it == 2 }) { "unsupported protocol" }
        return values
    }
    private fun canonicalUuid(value: String): String = value.also {
        require(UUID.fromString(it).toString().equals(it, ignoreCase = true)) { "invalid UUID" }
    }
    private fun canonicalDigest(value: String): String = value.also { require(it.matches(digestPattern)) { "invalid SHA-256 digest" } }
    private fun nonNegative(value: Long, key: String): Long = value.also { require(it >= 0) { "$key must be non-negative" } }
    private fun requireKeys(o: JSONObject, allowed: Set<String>) {
        val unknown = o.keys().asSequence().filterNot(allowed::contains).toList()
        require(unknown.isEmpty()) { "relay frame contains unknown fields: ${unknown.joinToString()}" }
    }
    private fun JSONObject.requiredString(key: String): String = get(key).let { require(it is String) { "$key must be a string" }; it }
    private fun JSONObject.requiredInt(key: String): Int = get(key).let { require(it is Number) { "$key must be an integer" }; it.toInt().also { n -> require((it as Number).toDouble() == n.toDouble()) { "$key must be an integer" } } }
    private fun JSONObject.requiredNonNegativeLong(key: String): Long = get(key).let { require(it is Number) { "$key must be an integer" }; it.toLong().also { n -> require(n >= 0 && (it as Number).toDouble() == n.toDouble()) { "$key must be a non-negative integer" } } }
    private fun JSONObject.requiredArray(key: String): JSONArray = get(key).let { require(it is JSONArray) { "$key must be an array" }; it }
    private fun JSONObject.requiredObject(key: String): JSONObject = get(key).let { require(it is JSONObject) { "$key must be an object" }; it }

    private fun JSONObject.optionalFeatures(key: String, allowEmpty: Boolean = false): Set<String> {
        if (!has(key)) return emptySet()
        val array = requiredArray(key)
        val values = (0 until array.length()).map { index ->
            array.get(index).let { value ->
                require(value is String) { "$key entries must be strings" }
                value
            }
        }
        if (values.isEmpty() && allowEmpty) return emptySet()
        return RelayFeatures.validated(values)
    }
}
