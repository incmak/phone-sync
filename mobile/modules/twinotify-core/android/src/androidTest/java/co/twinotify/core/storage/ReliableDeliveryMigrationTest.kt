package co.twinotify.core.storage

import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ReliableDeliveryMigrationTest {
    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        NotificationDbImpl::class.java,
    )

    @Test
    fun migrate2To3_preservesLegacyCiphertextAndCreatesReliableTables() {
        helper.createDatabase(TEST_DB, 2).apply {
            execSQL(
                "INSERT INTO outbound_queue(ciphertextB64,nonceB64,msgId,createdTs) " +
                    "VALUES('ct','nonce','11111111-1111-4111-8111-111111111111',1000)",
            )
            close()
        }

        val db = helper.runMigrationsAndValidate(TEST_DB, 3, true, MIGRATION_2_3)
        db.query("SELECT ciphertextB64, nonceB64, msgId FROM outbound_queue").use {
            assertTrue(it.moveToFirst())
            assertEquals("ct", it.getString(0))
            assertEquals("nonce", it.getString(1))
            assertEquals("11111111-1111-4111-8111-111111111111", it.getString(2))
        }

        for (
            table in listOf(
                "outbound_message",
                "inbound_message",
                "canonical_notification_state",
                "origin_sequence",
                "activity_event",
                "snapshot_stage",
            )
        ) {
            db.query(
                "SELECT name FROM sqlite_master WHERE type='table' AND name=?",
                arrayOf(table),
            ).use {
                assertTrue(it.moveToFirst(), table)
            }
        }
        db.close()
    }

    @Test
    fun migrate3To4_createsDurableMaterializationRetryTable() {
        helper.createDatabase(TEST_DB_V4, 3).close()
        val db = helper.runMigrationsAndValidate(TEST_DB_V4, 4, true, MIGRATION_3_4)
        db.query(
            "SELECT name FROM sqlite_master WHERE type='table' AND name='materialization_retry'",
        ).use { assertTrue(it.moveToFirst()) }
        db.close()
    }

    private companion object {
        const val TEST_DB = "reliable-delivery-migration-test"
        const val TEST_DB_V4 = "reliable-delivery-migration-v4-test"
    }
}
