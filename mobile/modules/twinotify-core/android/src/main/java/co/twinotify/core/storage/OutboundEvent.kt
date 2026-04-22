package co.twinotify.core.storage

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Transaction

@Entity(tableName = "outbound_queue")
data class OutboundEvent(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val ciphertextB64: String,
    val nonceB64: String,
    val msgId: String,
    val createdTs: Long,
)

@Dao
abstract class OutboundEventDao {
    @Insert abstract suspend fun insertRaw(event: OutboundEvent): Long

    @Query("SELECT * FROM outbound_queue ORDER BY id ASC LIMIT :limit")
    abstract suspend fun drain(limit: Int): List<OutboundEvent>

    @Query("DELETE FROM outbound_queue WHERE id = :id")
    abstract suspend fun ack(id: Long)

    @Query("SELECT COUNT(*) FROM outbound_queue")
    abstract suspend fun count(): Int

    @Query("DELETE FROM outbound_queue WHERE id IN (SELECT id FROM outbound_queue ORDER BY id ASC LIMIT :n)")
    abstract suspend fun dropOldest(n: Int)

    /** Atomic cap-checked insert: drops oldest row(s) + inserts in a single write transaction. */
    @Transaction
    open suspend fun enqueueCapped(event: OutboundEvent, maxSize: Int): Long {
        val size = count()
        if (size >= maxSize) dropOldest(1 + (size - maxSize))
        return insertRaw(event)
    }

    @Query("DELETE FROM outbound_queue")
    abstract suspend fun clearAll()
}
