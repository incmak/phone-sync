package co.twinotify.core.e2e

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.CancellationSignal
import androidx.core.net.toUri
import co.twinotify.core.service.SyncServiceStatus
import co.twinotify.core.service.toEventMap
import co.twinotify.core.service.ProductObservationTracker
import co.twinotify.core.crypto.CryptoStore
import co.twinotify.core.pairing.LocalUnpairStatus
import co.twinotify.core.pairing.lan.LanIdentityStore
import co.twinotify.core.storage.DeviceIdentity
import co.twinotify.core.storage.LanPairStore
import co.twinotify.core.storage.NotificationDb
import co.twinotify.core.storage.PeerStore
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject

/** Content-free, debug-only durable state view for host-side E2E assertions. */
class E2eStateProvider : ContentProvider() {
    companion object {
        const val AUTHORITY = "co.twinotify.app.e2e"
        val STATE_URI: Uri = "content://$AUTHORITY/state".toUri()
        val ALLOWED_EVENT_COUNT_KEYS: Set<String> = ProductObservationTracker.EVENT_KEYS

        fun stateUri(context: Context): Uri = "content://${context.packageName}.e2e/state".toUri()

        fun offlinePairingEvidenceJson(context: Context): JSONObject = runBlocking(Dispatchers.IO) {
            val peer = PeerStore.load(context)
            val deviceId = DeviceIdentity.getOrCreate(context)
            val keys = CryptoStore.loadOrGenerate(context)
            val binding = peer?.takeIf { it.lanBindingId != null }?.let { LanPairStore.loadValidated(context, it) }
            val offline = E2eOfflinePairingControl.publicStatus(context)
            if (offline.optString("phase") == "idle" && binding != null) {
                offline.put("phase", "complete").put("completed", true)
            }
            JSONObject()
                .put("offline_pairing", offline)
                .put("device_application_identity_hash", applicationIdentityHash(deviceId, keys.first.publicKey, keys.second.publicKey))
                .put("peer_application_identity_hash", peer?.let { applicationIdentityHash(it.deviceId, it.encPubkey, it.signPubkey) } ?: JSONObject.NULL)
                .put("lan_binding_present", binding != null)
                .put("local_tls_pin_hash", binding?.let { sha256BytesHex(LanIdentityStore.loadOrCreate().spkiSha256) } ?: JSONObject.NULL)
                .put("peer_tls_pin_hash", binding?.let { sha256BytesHex(it.peerTlsSpkiSha256) } ?: JSONObject.NULL)
        }

        fun snapshotJson(context: Context): String = runBlocking(Dispatchers.IO) {
            E2eSessionToken.ensure(context)
            val db = NotificationDb.get(context)
            val database = db.openHelper.readableDatabase
            val offline = E2eOfflinePairingControl.publicStatus(context)
            val health = SyncServiceStatus.health.value
            val route = SyncServiceStatus.routeStatus.value
            val outboxBytes = scalarLong(database, "SELECT COALESCE(SUM(byteSize),0) FROM outbound_message WHERE state IN ('NEW','ACCEPTED')")
            val activeOutbox = scalar(database, "SELECT COUNT(*) FROM outbound_message WHERE state IN ('NEW','ACCEPTED')")
            require(activeOutbox in 0..2_000) { "active queue count exceeds production bound" }
            require(outboxBytes in 0..134_217_728L) { "active queue bytes exceed production bound" }
            ProductObservationTracker.recordQueue(activeOutbox, outboxBytes)
            val root = JSONObject()
                .put("offline_pairing", offline)
                .put("health", JSONObject(health.toEventMap()))
                .put("route", JSONObject(route.toPublicMap()))
                .put("route_evidence", JSONObject()
                    .put("route", route.route.name.lowercase())
                    .put("phase", route.phase.name.lowercase())
                    .put("route_generation", route.routeGeneration)
                    .put("queued_count", activeOutbox)
                    .put("queued_bytes", outboxBytes)
                    .put("receipt_at_ms", health.lastReceiptAt ?: JSONObject.NULL)
                    .put("error_code", health.lastErrorCode ?: JSONObject.NULL))
                .put("outbox_bytes", outboxBytes)
                .put("active_outbox", activeOutbox)
                .put("active_inbound", scalar(database, "SELECT COUNT(*) FROM inbound_message WHERE outcome='PENDING_PLATFORM'"))
                .put("pending_materialization", scalar(database, "SELECT COUNT(*) FROM canonical_notification_state WHERE latestSequence > materializedSequence"))
                .put("product_observations", productObservations(context, database, activeOutbox, outboxBytes))
            root.put("canonical", canonical(database))
            root.put("activity", activity(database))
            root.put("notification_action_fixture", NotificationActionFixture.snapshot(context))
            root.toString()
        }

        fun clearActivity(context: Context) {
            NotificationDb.get(context).openHelper.writableDatabase.execSQL("DELETE FROM activity_event")
        }

        private fun scalar(database: androidx.sqlite.db.SupportSQLiteDatabase, sql: String): Int =
            database.query(sql).use { cursor -> if (cursor.moveToFirst()) cursor.getInt(0) else 0 }

        private fun scalarLong(database: androidx.sqlite.db.SupportSQLiteDatabase, sql: String): Long =
            database.query(sql).use { cursor -> if (cursor.moveToFirst()) cursor.getLong(0) else 0L }

        private fun canonical(database: androidx.sqlite.db.SupportSQLiteDatabase): JSONArray {
            val result = JSONArray()
            database.query(
                "SELECT canonId, latestSequence, state, materializedSequence, desiredPayloadJson " +
                    "FROM canonical_notification_state ORDER BY updatedAt LIMIT 200",
            ).use { cursor ->
                while (cursor.moveToNext()) {
                    val canonId = cursor.getString(0)
                    val row = JSONObject()
                        .put("canon_id_hash", sha256Hex(cursor.getString(0)))
                        .put("sequence", cursor.getLong(1))
                        .put("state", cursor.getString(2))
                        .put("materialized_sequence", cursor.getLong(3))
                    callSemanticState(canonId, cursor.getString(2), cursor.getString(4))?.let {
                        row.put("semantic_state", it)
                    }
                    result.put(row)
                }
            }
            return result
        }

        internal fun callSemanticState(canonId: String, durableState: String, payloadJson: String?): String? {
            if (!canonId.startsWith("call:")) return null
            val sessionId = canonId.removePrefix("call:")
            require(runCatching { UUID.fromString(sessionId).toString() }.getOrNull() == sessionId) {
                "invalid call canonical identifier"
            }
            if (durableState == "CANCELLED") {
                require(payloadJson == null) { "cancelled call retained a payload" }
                return "IDLE"
            }
            require(durableState == "ACTIVE") { "invalid durable call state" }
            require(payloadJson != null && payloadJson.toByteArray().size <= MAX_CALL_PAYLOAD_BYTES) {
                "invalid call payload size"
            }
            val payload = runCatching { JSONObject(payloadJson) }
                .getOrElse { throw IllegalArgumentException("malformed call payload") }
            require(payload.keys().asSequence().toSet() == CALL_PAYLOAD_KEYS) { "unknown call payload field" }
            require(payload.optString("call_session_id") == sessionId) { "call session mismatch" }
            require(payload.optString("direction") in CALL_DIRECTIONS) { "invalid call direction" }
            return when (payload.optString("state")) {
                "ringing" -> "RINGING"
                "active" -> "ACTIVE"
                else -> throw IllegalArgumentException("invalid call semantic state")
            }
        }

        private const val MAX_CALL_PAYLOAD_BYTES = 4_096
        private val CALL_PAYLOAD_KEYS = setOf("call_session_id", "state", "direction")
        private val CALL_DIRECTIONS = setOf("incoming", "outgoing", "unknown")

        private fun activity(database: androidx.sqlite.db.SupportSQLiteDatabase): JSONArray {
            val result = JSONArray()
            database.query(
                "SELECT eventType, status, detailCode FROM activity_event " +
                    "ORDER BY occurredAt DESC LIMIT 100",
            ).use { cursor ->
                while (cursor.moveToNext()) {
                    result.put(JSONObject()
                        .put("event_type", cursor.getString(0))
                        .put("status", cursor.getString(1))
                        .put("detail_code", cursor.getString(2)))
                }
            }
            return result
        }

        private suspend fun productObservations(
            context: Context,
            database: androidx.sqlite.db.SupportSQLiteDatabase,
            activeQueueCount: Int,
            activeQueueBytes: Long,
        ): JSONObject {
            val tracked = ProductObservationTracker.snapshot()
            val custody = tracked.custodyCounts.mapValues { (_, counts) -> counts.toMutableMap() }.toMutableMap()
            database.query(
                "SELECT custodyRoute, eventType, COUNT(*) FROM outbound_message " +
                    "WHERE custodyRoute IN ('LAN','RELAY') GROUP BY custodyRoute, eventType",
            ).use { cursor ->
                while (cursor.moveToNext()) {
                    val route = cursor.getString(0).lowercase()
                    val event = cursor.getString(1).replace('.', '_')
                    if (route in custody && event in ALLOWED_EVENT_COUNT_KEYS) {
                        val count = cursor.getLong(2).coerceIn(0L, ProductObservationTracker.MAX_COUNTER)
                        custody.getValue(route)[event] = maxOf(custody.getValue(route).getValue(event), count)
                    }
                }
            }
            val custodyJson = JSONObject()
            for (route in listOf("lan", "relay")) {
                val counts = JSONObject()
                for (event in ALLOWED_EVENT_COUNT_KEYS) {
                    counts.put(event, custody.getValue(route).getValue(event))
                }
                custodyJson.put(route, counts)
            }
            val persistedReceipts = scalarLong(
                database,
                "SELECT COUNT(*) FROM activity_event WHERE eventType='peer.receipt'",
            ).coerceIn(0L, ProductObservationTracker.MAX_COUNTER)
            return JSONObject()
                .put("paired", PeerStore.load(context) != null)
                .put("custody_counts", custodyJson)
                .put("peer_receipt_count", maxOf(tracked.peerReceiptCount, persistedReceipts))
                .put("snapshot_digest_count", tracked.snapshotDigestCount)
                .put("snapshot_begin_count", tracked.snapshotBeginCount)
                .put("snapshot_end_count", tracked.snapshotEndCount)
                .put("snapshot_commit_count", tracked.snapshotCommitCount)
                .put("user_dismiss_count", tracked.userDismissCount)
                .put("unpair_inbound_count", tracked.unpairInboundCount)
                .put("unpair_outcome", LocalUnpairStatus.lastOutcome.value ?: "none")
                .put("active_queue_count", activeQueueCount)
                .put("active_queue_bytes", activeQueueBytes)
                .put("peak_queue_count", maxOf(activeQueueCount, tracked.peakQueueCount))
                .put("peak_queue_bytes", maxOf(activeQueueBytes, tracked.peakQueueBytes))
        }

        private fun sha256Hex(value: String): String = MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray())
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }

        private fun applicationIdentityHash(deviceId: String, encryption: ByteArray, signing: ByteArray): String {
            val digest = MessageDigest.getInstance("SHA-256")
            digest.update("twinotify.application-identity.v1\u0000".encodeToByteArray())
            digest.update(deviceId.encodeToByteArray())
            digest.update(0.toByte())
            digest.update(encryption)
            digest.update(signing)
            return digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
        }

        private fun sha256BytesHex(value: ByteArray): String = MessageDigest.getInstance("SHA-256")
            .digest(value).joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    override fun onCreate(): Boolean {
        E2eSessionToken.ensure(requireNotNull(context))
        return true
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor {
        require(uri.authority == "${requireNotNull(context).packageName}.e2e" && uri.path == "/state") {
            "unsupported E2E state URI"
        }
        val token = uri.getQueryParameter("token")
        if (!E2eSessionToken.matches(requireNotNull(context), token)) {
            throw SecurityException("unauthorized E2E state query")
        }
        return MatrixCursor(arrayOf("state_json"), 1).apply {
            addRow(arrayOf(snapshotJson(requireNotNull(context))))
        }
    }

    override fun getType(uri: Uri): String = "application/json"
    override fun insert(uri: Uri, values: ContentValues?): Uri? = error("read-only E2E state")
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = error("read-only E2E state")
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = error("read-only E2E state")
}
