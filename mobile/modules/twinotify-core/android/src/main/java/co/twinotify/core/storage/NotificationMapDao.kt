package co.twinotify.core.storage

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction

@Dao
abstract class NotificationMapDao {

    @Insert(onConflict = OnConflictStrategy.ABORT)
    protected abstract suspend fun insertMirror(row: MirroredFromPeer)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    protected abstract suspend fun insertLocalMapping(row: LocalIdToCanonId)

    @Transaction
    open suspend fun putMirror(
        canonId: String,
        originDevice: String,
        localId: Int,
        localTag: String?,
    ) {
        val nowMs = System.currentTimeMillis()
        insertMirror(MirroredFromPeer(canonId = canonId, originDeviceId = originDevice, createdTs = nowMs))
        insertLocalMapping(
            LocalIdToCanonId(
                localId = localId,
                localTag = localTag,
                canonId = canonId,
                createdTs = nowMs,
            )
        )
    }

    @Query("SELECT canonId FROM local_to_canon WHERE localId = :localId AND localTag IS :localTag LIMIT 1")
    abstract suspend fun lookupByLocal(localId: Int, localTag: String?): String?

    @Query("SELECT originDeviceId FROM mirrored_from_peer WHERE canonId = :canonId LIMIT 1")
    abstract suspend fun lookupOrigin(canonId: String): String?

    // FK CASCADE on local_to_canon.canonId → mirrored_from_peer.canonId (onDelete = CASCADE)
    // means deleting the parent row automatically removes the child row.
    @Query("DELETE FROM mirrored_from_peer WHERE canonId = :canonId")
    abstract suspend fun deleteByCanonId(canonId: String)

    // FK CASCADE handles local_to_canon child rows automatically.
    @Query("DELETE FROM mirrored_from_peer WHERE createdTs < :cutoffMs")
    abstract suspend fun sweepExpired(cutoffMs: Long)

    // Wipes all mirrored notifications; FK CASCADE removes local_to_canon rows automatically.
    @Query("DELETE FROM mirrored_from_peer")
    abstract suspend fun clearAll()

    @Query("SELECT localId, localTag FROM local_to_canon WHERE canonId = :canonId LIMIT 1")
    abstract suspend fun lookupLocalByCanonId(canonId: String): LocalIdTagPair?
}
