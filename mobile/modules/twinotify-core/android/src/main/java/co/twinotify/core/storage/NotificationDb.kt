package co.twinotify.core.storage

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
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
        }).addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4).build().also { instance = it }
    }
}

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
        SnapshotStage::class,
        MaterializationRetry::class,
    ],
    version = 4,
)
abstract class NotificationDbImpl : RoomDatabase() {
    abstract fun notificationMapDao(): NotificationMapDao
    abstract fun outboundEventDao(): OutboundEventDao
    abstract fun reliableDeliveryDao(): ReliableDeliveryDao
}
