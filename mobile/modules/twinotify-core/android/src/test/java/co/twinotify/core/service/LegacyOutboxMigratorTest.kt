package co.twinotify.core.service

import co.twinotify.core.storage.LegacyConversionResult
import co.twinotify.core.storage.LegacyOutboundEvent
import co.twinotify.core.storage.LegacyOutboxStore
import co.twinotify.core.storage.OutboundMessage
import java.security.MessageDigest
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LegacyOutboxMigratorTest {
    @Test
    fun migrate_preservesCiphertextAndNonceInExactEnvelopeAndIsIdempotent() = runBlocking {
        val store = FakeLegacyOutboxStore(
            LegacyOutboundEvent(
                id = 7,
                ciphertextB64 = "ct",
                nonceB64 = "nonce",
                msgId = MESSAGE_ID,
                createdTs = 1_000,
            ),
        )

        val first = LegacyOutboxMigrator(store).migrate("dev-a")
        val second = LegacyOutboxMigrator(store).migrate("dev-a")

        val expectedEnvelope =
            "{\"v\":1,\"type\":\"enc\",\"msg_id\":\"$MESSAGE_ID\",\"origin_device\":\"dev-a\"," +
                "\"ts\":1000,\"nonce\":\"nonce\",\"ciphertext\":\"ct\"}"
        val inserted = store.outbound.single()
        assertEquals(expectedEnvelope, inserted.envelopeJson)
        assertEquals(sha256(expectedEnvelope), inserted.envelopeSha256)
        assertContentEquals("ct".toByteArray(), extractJsonString(inserted.envelopeJson, "ciphertext").toByteArray())
        assertContentEquals("nonce".toByteArray(), extractJsonString(inserted.envelopeJson, "nonce").toByteArray())
        assertEquals("NEW", inserted.state)
        assertEquals(1, first.converted)
        assertEquals(0, second.converted)
        assertEquals(1, store.transactionCalls)
        assertTrue(store.legacy.isEmpty())
    }

    private class FakeLegacyOutboxStore(seed: LegacyOutboundEvent) : LegacyOutboxStore {
        val legacy = mutableListOf(seed)
        val outbound = mutableListOf<OutboundMessage>()
        var transactionCalls = 0

        override suspend fun legacyBatch(limit: Int): List<LegacyOutboundEvent> = legacy.take(limit)

        override suspend fun convertLegacy(
            legacyId: Long,
            row: OutboundMessage,
        ): LegacyConversionResult {
            transactionCalls += 1
            val source = legacy.singleOrNull { it.id == legacyId }
                ?: return LegacyConversionResult.AlreadyConverted
            val existing = outbound.singleOrNull { it.msgId == row.msgId }
            if (existing != null && existing.envelopeSha256 != row.envelopeSha256) {
                return LegacyConversionResult.Conflict(existing.envelopeSha256)
            }
            if (existing == null) outbound += row
            legacy.remove(source)
            return if (existing == null) {
                LegacyConversionResult.Converted
            } else {
                LegacyConversionResult.AlreadyConverted
            }
        }
    }

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

    private fun extractJsonString(json: String, name: String): String =
        Regex("\\\"$name\\\":\\\"([^\\\"]*)\\\"").find(json)?.groupValues?.get(1)
            ?: error("missing $name")

    private companion object {
        const val MESSAGE_ID = "11111111-1111-4111-8111-111111111111"
    }
}
