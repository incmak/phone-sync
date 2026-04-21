package co.twinotify.core.storage

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase

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
        }).build().also { instance = it }
    }
}

// TODO(phase-3/task-4): add OutboundEvent::class here + bump version to 2 +
// add Migration(1,2). Task 4 (SyncService) introduces OutboundEvent for the
// outbound message queue.
@Database(entities = [MirroredFromPeer::class, LocalIdToCanonId::class], version = 1)
abstract class NotificationDbImpl : RoomDatabase() {
    abstract fun notificationMapDao(): NotificationMapDao
}
