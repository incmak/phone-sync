package co.twinotify.core.storage

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LegacyConversionTransactionTest {
    private lateinit var db: NotificationDbImpl

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            NotificationDbImpl::class.java,
        ).allowMainThreadQueries().build()
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun missingLegacySourceDoesNotCreateReliableOrphan() = runBlocking {
        val result = db.reliableDeliveryDao().convertLegacy(99, reliableRow("m1", "sha"))

        assertEquals(LegacyConversionResult.AlreadyConverted, result)
        db.openHelper.readableDatabase.query(
            "SELECT msgId FROM outbound_message WHERE msgId='m1'",
        ).use { assertNull(if (it.moveToFirst()) it.getString(0) else null) }
    }

    @Test
    fun successfulConversionInsertsReliableAndDeletesLegacy() = runBlocking {
        val legacyId = db.outboundEventDao().insertRaw(legacy("m1"))

        val result = db.reliableDeliveryDao().convertLegacy(legacyId, reliableRow("m1", "sha"))

        assertEquals(LegacyConversionResult.Converted, result)
        assertEquals("sha", reliableDigest("m1"))
        assertEquals(0, db.outboundEventDao().count())
    }

    @Test
    fun sameDigestConversionKeepsReliableAndDeletesDuplicateLegacy() = runBlocking {
        val legacyId = db.outboundEventDao().insertRaw(legacy("m1"))
        db.reliableDeliveryDao().insertOutbound(reliableRow("m1", "sha"))

        val result = db.reliableDeliveryDao().convertLegacy(legacyId, reliableRow("m1", "sha"))

        assertEquals(LegacyConversionResult.AlreadyConverted, result)
        assertEquals("sha", reliableDigest("m1"))
        assertEquals(0, db.outboundEventDao().count())
    }

    @Test
    fun digestConflictPreservesReliableAndExactLegacySource() = runBlocking {
        val legacyId = db.outboundEventDao().insertRaw(legacy("m1"))
        db.reliableDeliveryDao().insertOutbound(reliableRow("m1", "other-sha"))

        val result = db.reliableDeliveryDao().convertLegacy(legacyId, reliableRow("m1", "sha"))

        assertEquals(LegacyConversionResult.Conflict("other-sha"), result)
        assertEquals("other-sha", reliableDigest("m1"))
        db.openHelper.readableDatabase.query(
            "SELECT ciphertextB64, nonceB64 FROM outbound_queue WHERE id=$legacyId",
        ).use {
            assertTrue(it.moveToFirst())
            assertEquals("ct", it.getString(0))
            assertEquals("nonce", it.getString(1))
        }
    }

    private fun legacy(msgId: String) = LegacyOutboundEvent(
        ciphertextB64 = "ct",
        nonceB64 = "nonce",
        msgId = msgId,
        createdTs = 1,
    )

    private fun reliableDigest(msgId: String): String? = db.openHelper.readableDatabase.query(
        "SELECT envelopeSha256 FROM outbound_message WHERE msgId='$msgId'",
    ).use { if (it.moveToFirst()) it.getString(0) else null }

    private fun reliableRow(msgId: String, digest: String) = OutboundMessage(
        msgId = msgId,
        canonId = null,
        sequence = null,
        eventType = "enc",
        protocolVersion = 1,
        envelopeJson = "{}",
        envelopeSha256 = digest,
        byteSize = 2,
        createdAt = 1,
        expiresAt = 2,
        relayAcceptedAt = null,
        attempts = 0,
        nextAttemptAt = 1,
        state = "NEW",
        lastError = null,
        requiresPeerReceipt = true,
    )
}
