package co.twinotify.core.storage

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS outbound_queue (
                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                ciphertextB64 TEXT NOT NULL,
                nonceB64 TEXT NOT NULL,
                msgId TEXT NOT NULL,
                createdTs INTEGER NOT NULL
            )
        """.trimIndent())
    }
}

val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS outbound_message (
                msgId TEXT NOT NULL PRIMARY KEY, canonId TEXT, sequence INTEGER, eventType TEXT NOT NULL,
                protocolVersion INTEGER NOT NULL, envelopeJson TEXT NOT NULL, envelopeSha256 TEXT NOT NULL,
                byteSize INTEGER NOT NULL, createdAt INTEGER NOT NULL, expiresAt INTEGER NOT NULL,
                relayAcceptedAt INTEGER, attempts INTEGER NOT NULL, nextAttemptAt INTEGER NOT NULL,
                state TEXT NOT NULL, lastError TEXT, requiresPeerReceipt INTEGER NOT NULL)""",
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS index_outbound_message_state ON outbound_message(state)")
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS index_outbound_message_nextAttemptAt " +
                "ON outbound_message(nextAttemptAt)",
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS index_outbound_message_canonId ON outbound_message(canonId)",
        )
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS inbound_message (
                msgId TEXT NOT NULL PRIMARY KEY, originDevice TEXT NOT NULL, envelopeSha256 TEXT NOT NULL,
                eventType TEXT NOT NULL, canonId TEXT, sequence INTEGER, outcome TEXT NOT NULL,
                committedAt INTEGER NOT NULL, appliedAt INTEGER, receiptMsgId TEXT, relayAckState TEXT NOT NULL)""",
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS index_inbound_message_outcome ON inbound_message(outcome)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_inbound_message_canonId ON inbound_message(canonId)")
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS canonical_notification_state (
                canonId TEXT NOT NULL PRIMARY KEY, originDevice TEXT NOT NULL, latestSequence INTEGER NOT NULL,
                state TEXT NOT NULL, desiredPayloadJson TEXT, materializedSequence INTEGER NOT NULL,
                sourceNotificationKey TEXT, mirrorLocalId INTEGER, mirrorLocalTag TEXT,
                peerCancelPending INTEGER NOT NULL, updatedAt INTEGER NOT NULL)""",
        )
        db.execSQL(
            "CREATE UNIQUE INDEX IF NOT EXISTS " +
                "index_canonical_notification_state_mirrorLocalTag_mirrorLocalId " +
                "ON canonical_notification_state(mirrorLocalTag, mirrorLocalId)",
        )
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS origin_sequence " +
                "(canonId TEXT NOT NULL PRIMARY KEY, nextSequence INTEGER NOT NULL)",
        )
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS activity_event (
                eventId TEXT NOT NULL PRIMARY KEY, msgId TEXT, packageName TEXT, eventType TEXT NOT NULL,
                status TEXT NOT NULL, byteSize INTEGER NOT NULL, occurredAt INTEGER NOT NULL, detailCode TEXT)""",
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS index_activity_event_occurredAt ON activity_event(occurredAt)")
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS snapshot_stage (
                snapshotId TEXT NOT NULL, canonId TEXT NOT NULL, sequence INTEGER NOT NULL,
                payloadJson TEXT NOT NULL, receivedAt INTEGER NOT NULL,
                PRIMARY KEY(snapshotId, canonId))""",
        )
    }
}

val MIGRATION_3_4 = object : Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("""CREATE TABLE IF NOT EXISTS materialization_retry (
            canonId TEXT NOT NULL PRIMARY KEY, nextAttemptAt INTEGER NOT NULL,
            attempts INTEGER NOT NULL, lastError TEXT)""")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_materialization_retry_nextAttemptAt ON materialization_retry(nextAttemptAt)")
    }
}

val MIGRATION_4_5 = object : Migration(4, 5) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE outbound_message RENAME TO outbound_message_v4")
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS outbound_message (
                msgId TEXT NOT NULL PRIMARY KEY, canonId TEXT, sequence INTEGER, eventType TEXT NOT NULL,
                protocolVersion INTEGER NOT NULL, envelopeJson TEXT NOT NULL, envelopeSha256 TEXT NOT NULL,
                byteSize INTEGER NOT NULL, createdAt INTEGER NOT NULL, expiresAt INTEGER NOT NULL,
                custodyAcceptedAt INTEGER, custodyRoute TEXT, attempts INTEGER NOT NULL,
                nextAttemptAt INTEGER NOT NULL, state TEXT NOT NULL, lastError TEXT,
                requiresPeerReceipt INTEGER NOT NULL)""",
        )
        db.execSQL(
            """INSERT INTO outbound_message (
                msgId, canonId, sequence, eventType, protocolVersion, envelopeJson, envelopeSha256,
                byteSize, createdAt, expiresAt, custodyAcceptedAt, custodyRoute, attempts,
                nextAttemptAt, state, lastError, requiresPeerReceipt)
                SELECT msgId, canonId, sequence, eventType, protocolVersion, envelopeJson, envelopeSha256,
                byteSize, createdAt, expiresAt, relayAcceptedAt,
                CASE WHEN relayAcceptedAt IS NULL THEN NULL ELSE 'RELAY' END,
                attempts, nextAttemptAt, state, lastError, requiresPeerReceipt
                FROM outbound_message_v4""",
        )
        db.execSQL("DROP TABLE outbound_message_v4")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_outbound_message_state ON outbound_message(state)")
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS index_outbound_message_nextAttemptAt " +
                "ON outbound_message(nextAttemptAt)",
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS index_outbound_message_canonId ON outbound_message(canonId)",
        )
    }
}

val MIGRATION_5_6 = object : Migration(5, 6) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "ALTER TABLE outbound_message ADD COLUMN relayCustodyState TEXT NOT NULL DEFAULT 'NONE'",
        )
        db.execSQL(
            "UPDATE outbound_message SET relayCustodyState = CASE " +
                "WHEN state='NEW' THEN 'NONE' " +
                "WHEN custodyRoute='RELAY' THEN 'ACCEPTED' " +
                "WHEN state='ACCEPTED' THEN 'UNKNOWN' " +
                "ELSE 'NONE' END",
        )
    }
}

val MIGRATION_6_7 = object : Migration(6, 7) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS materialization_retry_v7 (
                canonId TEXT NOT NULL PRIMARY KEY,
                sequence INTEGER NOT NULL,
                nextAttemptAt INTEGER,
                attempts INTEGER NOT NULL,
                disposition TEXT NOT NULL,
                lastError TEXT)""",
        )
        db.execSQL(
            """INSERT INTO materialization_retry_v7(
                canonId, sequence, nextAttemptAt, attempts, disposition, lastError)
                SELECT retry.canonId,
                    COALESCE(state.latestSequence, 0),
                    retry.nextAttemptAt,
                    retry.attempts,
                    'RETRYABLE',
                    retry.lastError
                FROM materialization_retry AS retry
                LEFT JOIN canonical_notification_state AS state ON state.canonId = retry.canonId""",
        )
        db.execSQL("DROP TABLE materialization_retry")
        db.execSQL("ALTER TABLE materialization_retry_v7 RENAME TO materialization_retry")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_materialization_retry_nextAttemptAt ON materialization_retry(nextAttemptAt)")
    }
}

val MIGRATION_7_8 = object : Migration(7, 8) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS ui_activity_event (
                eventId TEXT NOT NULL PRIMARY KEY,
                msgId TEXT,
                packageName TEXT,
                appName TEXT,
                direction TEXT NOT NULL,
                kind TEXT NOT NULL,
                status TEXT NOT NULL,
                route TEXT,
                occurredAt INTEGER NOT NULL)""",
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS index_ui_activity_event_occurredAt " +
                "ON ui_activity_event(occurredAt)",
        )
        db.execSQL(
            "CREATE UNIQUE INDEX IF NOT EXISTS index_ui_activity_event_msgId " +
                "ON ui_activity_event(msgId)",
        )
    }
}

val MIGRATION_8_9 = object : Migration(8, 9) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS action_invocation (
                invocationId TEXT NOT NULL PRIMARY KEY,
                canonId TEXT NOT NULL,
                actionId TEXT NOT NULL,
                notificationSequence INTEGER NOT NULL,
                replyText TEXT,
                state TEXT NOT NULL,
                createdAt INTEGER NOT NULL,
                expiresAt INTEGER NOT NULL,
                updatedAt INTEGER NOT NULL)""",
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS index_action_invocation_canonId ON action_invocation(canonId)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_action_invocation_state ON action_invocation(state)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_action_invocation_expiresAt ON action_invocation(expiresAt)")
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS action_execution (
                invocationId TEXT NOT NULL PRIMARY KEY,
                canonId TEXT NOT NULL,
                actionId TEXT NOT NULL,
                state TEXT NOT NULL,
                resultStatus TEXT,
                claimedAt INTEGER NOT NULL,
                completedAt INTEGER)""",
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS index_action_execution_state ON action_execution(state)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_action_execution_claimedAt ON action_execution(claimedAt)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_action_execution_completedAt ON action_execution(completedAt)")
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS notification_detail_cache (
                detailId TEXT NOT NULL PRIMARY KEY,
                canonId TEXT NOT NULL,
                payloadJson TEXT NOT NULL,
                originDevice TEXT NOT NULL,
                receivedAt INTEGER NOT NULL,
                updatedAt INTEGER NOT NULL,
                cancelledAt INTEGER)""",
        )
        db.execSQL(
            "CREATE UNIQUE INDEX IF NOT EXISTS index_notification_detail_cache_canonId " +
                "ON notification_detail_cache(canonId)",
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS index_notification_detail_cache_cancelledAt " +
                "ON notification_detail_cache(cancelledAt)",
        )
    }
}

val MIGRATION_9_10 = object : Migration(9, 10) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS ui_activity_content (
                eventId TEXT NOT NULL PRIMARY KEY,
                ciphertext BLOB NOT NULL,
                iv BLOB NOT NULL,
                byteSize INTEGER NOT NULL,
                createdAt INTEGER NOT NULL,
                FOREIGN KEY(eventId) REFERENCES ui_activity_event(eventId) ON UPDATE NO ACTION ON DELETE CASCADE)""",
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS index_ui_activity_content_createdAt " +
                "ON ui_activity_content(createdAt)",
        )
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS ui_history_policy (
                id INTEGER NOT NULL PRIMARY KEY,
                contentEnabled INTEGER NOT NULL,
                retentionDays INTEGER NOT NULL)""",
        )
    }
}

object NotificationDb {
    @Volatile private var instance: NotificationDbImpl? = null

    fun get(ctx: Context): NotificationDbImpl = instance ?: synchronized(this) {
        instance ?: Room.databaseBuilder(
            ctx.applicationContext,
            NotificationDbImpl::class.java,
            "twinotify_notifications.db",
        ).addCallback(object : RoomDatabase.Callback() {
            override fun onOpen(db: SupportSQLiteDatabase) {
                super.onOpen(db)
                db.execSQL("PRAGMA foreign_keys = ON")
            }
        }).addMigrations(
            MIGRATION_1_2,
            MIGRATION_2_3,
            MIGRATION_3_4,
            MIGRATION_4_5,
            MIGRATION_5_6,
            MIGRATION_6_7,
            MIGRATION_7_8,
            MIGRATION_8_9,
            MIGRATION_9_10,
        ).build().also { instance = it }
    }
}

@TypeConverters(ReliableDeliveryTypeConverters::class)
@Database(
    entities = [
        MirroredFromPeer::class,
        LocalIdToCanonId::class,
        LegacyOutboundEvent::class,
        OutboundMessage::class,
        InboundMessage::class,
        CanonicalNotificationState::class,
        OriginSequence::class,
        ActivityEvent::class,
        UiActivityEvent::class,
        UiActivityContent::class,
        UiHistoryPolicy::class,
        SnapshotStage::class,
        MaterializationRetry::class,
        ActionInvocation::class,
        ActionExecution::class,
        NotificationDetailCache::class,
    ],
    version = 10,
)
abstract class NotificationDbImpl : RoomDatabase() {
    abstract fun notificationMapDao(): NotificationMapDao
    abstract fun outboundEventDao(): OutboundEventDao
    abstract fun reliableDeliveryDao(): ReliableDeliveryDao
}
