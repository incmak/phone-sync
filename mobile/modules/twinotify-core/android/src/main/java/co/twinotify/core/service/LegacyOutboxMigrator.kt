package co.twinotify.core.service

import co.twinotify.core.storage.LegacyConversionResult
import co.twinotify.core.storage.LegacyOutboxStore
import co.twinotify.core.storage.OutboundMessage
import java.security.MessageDigest
import org.json.JSONObject

data class LegacyMigrationSummary(
    val converted: Int,
    val alreadyConverted: Int,
    val conflicts: Int,
)

class LegacyOutboxMigrator(
    private val store: LegacyOutboxStore,
    private val batchSize: Int = 64,
) {
    init {
        require(batchSize > 0)
    }

    suspend fun migrate(originDevice: String): LegacyMigrationSummary {
        require(originDevice.isNotBlank())
        var converted = 0
        var alreadyConverted = 0
        var conflicts = 0

        while (true) {
            val batch = store.legacyBatch(batchSize)
            if (batch.isEmpty()) break
            var removed = 0
            for (legacy in batch) {
                val envelope = legacyEnvelope(
                    msgId = legacy.msgId,
                    originDevice = originDevice,
                    createdTs = legacy.createdTs,
                    nonce = legacy.nonceB64,
                    ciphertext = legacy.ciphertextB64,
                )
                val bytes = envelope.toByteArray(Charsets.UTF_8)
                val row = OutboundMessage(
                    msgId = legacy.msgId,
                    canonId = null,
                    sequence = null,
                    eventType = "enc",
                    protocolVersion = 1,
                    envelopeJson = envelope,
                    envelopeSha256 = sha256(bytes),
                    byteSize = bytes.size.toLong(),
                    createdAt = legacy.createdTs,
                    expiresAt = saturatingAdd(legacy.createdTs, LEGACY_RETENTION_MILLIS),
                    custodyAcceptedAt = null,
                    custodyRoute = null,
                    attempts = 0,
                    nextAttemptAt = legacy.createdTs,
                    state = "NEW",
                    lastError = null,
                    requiresPeerReceipt = true,
                )
                when (store.convertLegacy(legacy.id, row)) {
                    LegacyConversionResult.Converted -> {
                        converted += 1
                        removed += 1
                    }
                    LegacyConversionResult.AlreadyConverted -> {
                        alreadyConverted += 1
                        removed += 1
                    }
                    is LegacyConversionResult.Conflict -> conflicts += 1
                }
            }
            if (removed == 0 || batch.size < batchSize) break
        }
        return LegacyMigrationSummary(converted, alreadyConverted, conflicts)
    }

    private fun legacyEnvelope(
        msgId: String,
        originDevice: String,
        createdTs: Long,
        nonce: String,
        ciphertext: String,
    ): String = buildString {
        append("{\"v\":1,\"type\":\"enc\",\"msg_id\":")
        append(JSONObject.quote(msgId))
        append(",\"origin_device\":")
        append(JSONObject.quote(originDevice))
        append(",\"ts\":")
        append(createdTs)
        append(",\"nonce\":")
        append(JSONObject.quote(nonce))
        append(",\"ciphertext\":")
        append(JSONObject.quote(ciphertext))
        append('}')
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString("") { "%02x".format(it) }

    private fun saturatingAdd(value: Long, increment: Long): Long =
        if (value > Long.MAX_VALUE - increment) Long.MAX_VALUE else value + increment

    private companion object {
        const val LEGACY_RETENTION_MILLIS = 24L * 60L * 60L * 1_000L
    }
}

/** Startup seam used by SyncService; migration completes before relay flushing begins. */
suspend fun migrateLegacyOutboxBeforeRelay(store: LegacyOutboxStore, originDevice: String): LegacyMigrationSummary =
    LegacyOutboxMigrator(store).migrate(originDevice)
