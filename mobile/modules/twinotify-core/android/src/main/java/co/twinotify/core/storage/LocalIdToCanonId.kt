package co.twinotify.core.storage

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "local_to_canon",
    indices = [
        Index(value = ["localId", "localTag"], unique = true),
        Index(value = ["canonId"]),
    ],
    foreignKeys = [
        ForeignKey(
            entity = MirroredFromPeer::class,
            parentColumns = ["canonId"],
            childColumns = ["canonId"],
            onDelete = ForeignKey.CASCADE,
        )
    ],
)
data class LocalIdToCanonId(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val localId: Int,
    val localTag: String?,
    val canonId: String,
    val createdTs: Long,
)
