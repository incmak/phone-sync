package co.twinotify.core.storage

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "mirrored_from_peer")
data class MirroredFromPeer(
    @PrimaryKey val canonId: String,
    val originDeviceId: String,
    val createdTs: Long,
)
