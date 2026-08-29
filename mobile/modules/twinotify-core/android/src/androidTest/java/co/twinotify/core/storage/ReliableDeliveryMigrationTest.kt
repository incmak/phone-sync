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

    @Test
    fun migrate4To5_preservesRowsAndMovesRelayAcceptanceToRouteNeutralCustody() {
        helper.createDatabase(TEST_DB_V5, 4).apply {
            execSQL(
                "INSERT INTO outbound_message(" +
                    "msgId,canonId,sequence,eventType,protocolVersion,envelopeJson,envelopeSha256," +
                    "byteSize,createdAt,expiresAt,relayAcceptedAt,attempts,nextAttemptAt,state,lastError," +
                    "requiresPeerReceipt) VALUES(" +
                    "'accepted','canon',7,'notif.update',2,'{}','digest-a',2,10,20,123456789," +
                    "3,30,'ACCEPTED','retry',1)",
            )
            execSQL(
                "INSERT INTO outbound_message(" +
                    "msgId,canonId,sequence,eventType,protocolVersion,envelopeJson,envelopeSha256," +
                    "byteSize,createdAt,expiresAt,relayAcceptedAt,attempts,nextAttemptAt,state,lastError," +
                    "requiresPeerReceipt) VALUES(" +
                    "'new',NULL,NULL,'peer.receipt',2,'{}','digest-b',2,11,21,NULL,0,31,'NEW',NULL,0)",
            )
            execSQL(
                "INSERT INTO inbound_message(" +
                    "msgId,originDevice,envelopeSha256,eventType,canonId,sequence,outcome,committedAt," +
                    "appliedAt,receiptMsgId,relayAckState) VALUES(" +
                    "'inbound','peer','digest-c','notif.post','canon',7,'APPLIED',12,13,'new','READY')",
            )
            execSQL(
                "INSERT INTO activity_event(" +
                    "eventId,msgId,packageName,eventType,status,byteSize,occurredAt,detailCode) VALUES(" +
                    "'activity','accepted','pkg','peer.receipt','applied',2,14,'ok')",
            )
            close()
        }

        val db = helper.runMigrationsAndValidate(TEST_DB_V5, 5, true, MIGRATION_4_5)
        db.query(
            "SELECT msgId,canonId,sequence,eventType,protocolVersion,envelopeJson,envelopeSha256," +
                "byteSize,createdAt,expiresAt,custodyAcceptedAt,custodyRoute,attempts,nextAttemptAt," +
                "state,lastError,requiresPeerReceipt FROM outbound_message ORDER BY msgId",
        ).use { rows ->
            assertTrue(rows.moveToFirst())
            assertEquals("accepted", rows.getString(0))
            assertEquals("canon", rows.getString(1))
            assertEquals(7L, rows.getLong(2))
            assertEquals("notif.update", rows.getString(3))
            assertEquals(2, rows.getInt(4))
            assertEquals("{}", rows.getString(5))
            assertEquals("digest-a", rows.getString(6))
            assertEquals(2L, rows.getLong(7))
            assertEquals(10L, rows.getLong(8))
            assertEquals(20L, rows.getLong(9))
            assertEquals(123456789L, rows.getLong(10))
            assertEquals("RELAY", rows.getString(11))
            assertEquals(3, rows.getInt(12))
            assertEquals(30L, rows.getLong(13))
            assertEquals("ACCEPTED", rows.getString(14))
            assertEquals("retry", rows.getString(15))
            assertEquals(1, rows.getInt(16))
            assertTrue(rows.moveToNext())
            assertEquals("new", rows.getString(0))
            assertTrue(rows.isNull(10))
            assertTrue(rows.isNull(11))
            assertEquals(0, rows.getInt(16))
        }
        db.query("PRAGMA index_list('outbound_message')").use { indices ->
            val names = buildSet {
                while (indices.moveToNext()) add(indices.getString(indices.getColumnIndexOrThrow("name")))
            }
            assertTrue("index_outbound_message_state" in names)
            assertTrue("index_outbound_message_nextAttemptAt" in names)
            assertTrue("index_outbound_message_canonId" in names)
        }
        db.query(
            "SELECT originDevice,envelopeSha256,receiptMsgId,relayAckState FROM inbound_message " +
                "WHERE msgId='inbound'",
        ).use { inbound ->
            assertTrue(inbound.moveToFirst())
            assertEquals("peer", inbound.getString(0))
            assertEquals("digest-c", inbound.getString(1))
            assertEquals("new", inbound.getString(2))
            assertEquals("READY", inbound.getString(3))
        }
        db.query(
            "SELECT msgId,eventType,status,detailCode FROM activity_event WHERE eventId='activity'",
        ).use { activity ->
            assertTrue(activity.moveToFirst())
            assertEquals("accepted", activity.getString(0))
            assertEquals("peer.receipt", activity.getString(1))
            assertEquals("applied", activity.getString(2))
            assertEquals("ok", activity.getString(3))
        }
        db.close()
    }

    @Test
    fun migrate5To6_assignsRelayCustodyConservativelyWithoutLosingFirstCustody() {
        helper.createDatabase(TEST_DB_V6, 5).apply {
            fun insert(msgId: String, state: String, acceptedAt: String, route: String) {
                execSQL(
                    "INSERT INTO outbound_message(" +
                        "msgId,canonId,sequence,eventType,protocolVersion,envelopeJson,envelopeSha256," +
                        "byteSize,createdAt,expiresAt,custodyAcceptedAt,custodyRoute,attempts,nextAttemptAt," +
                        "state,lastError,requiresPeerReceipt) VALUES(" +
                        "'$msgId',NULL,NULL,'notif.post',2,'{}','digest-$msgId',2,10,20," +
                        "$acceptedAt,$route,0,10,'$state',NULL,1)",
                )
            }
            insert("new", "NEW", "NULL", "NULL")
            insert("relay", "ACCEPTED", "11", "'RELAY'")
            insert("lan-ambiguous", "ACCEPTED", "12", "'LAN'")
            close()
        }

        val db = helper.runMigrationsAndValidate(TEST_DB_V6, 6, true, MIGRATION_5_6)
        db.query(
            "SELECT msgId,custodyAcceptedAt,custodyRoute,relayCustodyState " +
                "FROM outbound_message ORDER BY msgId",
        ).use { rows ->
            assertTrue(rows.moveToFirst())
            assertEquals("lan-ambiguous", rows.getString(0))
            assertEquals(12, rows.getLong(1))
            assertEquals("LAN", rows.getString(2))
            assertEquals("UNKNOWN", rows.getString(3))
            assertTrue(rows.moveToNext())
            assertEquals("new", rows.getString(0))
            assertTrue(rows.isNull(1))
            assertTrue(rows.isNull(2))
            assertEquals("NONE", rows.getString(3))
            assertTrue(rows.moveToNext())
            assertEquals("relay", rows.getString(0))
            assertEquals(11, rows.getLong(1))
            assertEquals("RELAY", rows.getString(2))
            assertEquals("ACCEPTED", rows.getString(3))
            assertTrue(!rows.moveToNext())
        }
        db.close()
    }

    @Test
    fun migrate6To7_preservesRetryAndBackfillsItsCanonicalSequence() {
        helper.createDatabase(TEST_DB_V7, 6).apply {
            execSQL(
                "INSERT INTO canonical_notification_state(" +
                    "canonId,originDevice,latestSequence,state,desiredPayloadJson,materializedSequence," +
                    "sourceNotificationKey,mirrorLocalId,mirrorLocalTag,peerCancelPending,updatedAt) VALUES(" +
                    "'canon','peer',9,'ACTIVE','{}',8,NULL,41,'tag',0,1000)",
            )
            execSQL(
                "INSERT INTO materialization_retry(canonId,nextAttemptAt,attempts,lastError) " +
                    "VALUES('canon',5000,3,'platform_retryable')",
            )
            close()
        }

        val db = helper.runMigrationsAndValidate(TEST_DB_V7, 7, true, MIGRATION_6_7)
        db.query("SELECT canonId,sequence,nextAttemptAt,attempts,lastError FROM materialization_retry").use { row ->
            assertTrue(row.moveToFirst())
            assertEquals("canon", row.getString(0))
            assertEquals(9L, row.getLong(1))
            assertEquals(5_000L, row.getLong(2))
            assertEquals(3, row.getInt(3))
            assertEquals("platform_retryable", row.getString(4))
        }
        db.close()
    }

    @Test
    fun migrate7To8_preservesProtocolHistoryAndAddsPrivacyBoundedUiActivity() {
        helper.createDatabase(TEST_DB_V8, 7).apply {
            execSQL(
                "INSERT INTO activity_event(" +
                    "eventId,msgId,packageName,eventType,status,byteSize,occurredAt,detailCode) " +
                    "VALUES('protocol-event','message-1','example.messages','notif.post','applied',24,1000,NULL)",
            )
            close()
        }

        val db = helper.runMigrationsAndValidate(TEST_DB_V8, 8, true, MIGRATION_7_8)
        db.query("SELECT COUNT(*) FROM activity_event WHERE eventId='protocol-event'").use { row ->
            assertTrue(row.moveToFirst())
            assertEquals(1, row.getInt(0))
        }
        db.execSQL(
            "INSERT INTO ui_activity_event(" +
                "eventId,msgId,packageName,appName,direction,kind,status,route,occurredAt) " +
                "VALUES('ui-event','message-1','example.messages','Messages','RECEIVED'," +
                "'NOTIFICATION','APPLIED','LAN',2000)",
        )
        db.query(
            "SELECT direction,kind,status,route FROM ui_activity_event WHERE eventId='ui-event'",
        ).use { row ->
            assertTrue(row.moveToFirst())
            assertEquals("RECEIVED", row.getString(0))
            assertEquals("NOTIFICATION", row.getString(1))
            assertEquals("APPLIED", row.getString(2))
            assertEquals("LAN", row.getString(3))
        }
        db.close()
    }

    @Test
    fun migrate8To9_preservesHistoryAndAddsActionJournalsAndDetailCache() {
        helper.createDatabase(TEST_DB_V9, 8).apply {
            execSQL(
                "INSERT INTO activity_event(" +
                    "eventId,msgId,packageName,eventType,status,byteSize,occurredAt,detailCode) " +
                    "VALUES('before-v9','message-9','example.messages','notif.post','applied',24,1000,NULL)",
            )
            close()
        }

        val db = helper.runMigrationsAndValidate(TEST_DB_V9, 9, true, MIGRATION_8_9)
        db.query("SELECT COUNT(*) FROM activity_event WHERE eventId='before-v9'").use { row ->
            assertTrue(row.moveToFirst())
            assertEquals(1, row.getInt(0))
        }
        val expectedIndices = mapOf(
            "action_invocation" to setOf(
                "index_action_invocation_canonId",
                "index_action_invocation_state",
                "index_action_invocation_expiresAt",
            ),
            "action_execution" to setOf(
                "index_action_execution_state",
                "index_action_execution_claimedAt",
                "index_action_execution_completedAt",
            ),
            "notification_detail_cache" to setOf(
                "index_notification_detail_cache_canonId",
                "index_notification_detail_cache_cancelledAt",
            ),
        )
        expectedIndices.forEach { (table, expected) ->
            db.query("PRAGMA index_list('$table')").use { rows ->
                val names = buildSet {
                    while (rows.moveToNext()) add(rows.getString(rows.getColumnIndexOrThrow("name")))
                }
                assertTrue(expected.all(names::contains), "$table indices: $names")
            }
        }
        db.close()
    }

    private companion object {
        const val TEST_DB = "reliable-delivery-migration-test"
        const val TEST_DB_V4 = "reliable-delivery-migration-v4-test"
        const val TEST_DB_V5 = "reliable-delivery-migration-v5-test"
        const val TEST_DB_V6 = "reliable-delivery-migration-v6-test"
        const val TEST_DB_V7 = "reliable-delivery-migration-v7-test"
        const val TEST_DB_V8 = "reliable-delivery-migration-v8-test"
        const val TEST_DB_V9 = "reliable-delivery-migration-v9-test"
    }
}
