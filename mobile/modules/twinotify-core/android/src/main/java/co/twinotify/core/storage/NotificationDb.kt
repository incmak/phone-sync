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
        }).addMigrations(MIGRATION_1_2).build().also { instance = it }
    }
}

@Database(
    entities = [MirroredFromPeer::class, LocalIdToCanonId::class, OutboundEvent::class],
    version = 2,
)
abstract class NotificationDbImpl : RoomDatabase() {
    abstract fun notificationMapDao(): NotificationMapDao
    abstract fun outboundEventDao(): OutboundEventDao
}
