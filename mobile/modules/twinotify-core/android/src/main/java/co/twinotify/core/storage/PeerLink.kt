package co.twinotify.core.storage

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Transaction

/** Migration placeholder only. Runtime service startup waits for the DataStore import. */
const val LEGACY_PEER_LINK_ID = "legacy"

@Entity(tableName = "peer_link", indices = [Index(value = ["deviceId"], unique = true), Index(value = ["relayPairId"], unique = true)])
data class PeerLink(
    @PrimaryKey val peerLinkId: String,
    val deviceId: String,
    val encPubkey: ByteArray,
    val signPubkey: ByteArray,
    val displayName: String?,
    val relayUrl: String?,
    val relayPairId: String?,
    val preferLan: Boolean,
    val lanBindingId: String?,
    val relayRevocationRequired: Boolean,
    val lifecycle: String,
    val createdAt: Long,
) {
    init { require(lifecycle == "ACTIVE" || lifecycle == "REMOVING") }
    fun record() = PeerRecord(deviceId, encPubkey, signPubkey, displayName, lanBindingId,
        relayRevocationRequired, peerLinkId, relayUrl, relayPairId, preferLan, lifecycle)
}

@Entity(tableName = "peer_import_state")
data class PeerImportState(@PrimaryKey val id: Int = 0, val localDeviceId: String)

@Entity(tableName = "pending_relay_revocation", indices = [Index("peerLinkId")])
data class PendingRelayRevocation(
    @PrimaryKey val revocationId: String,
    val peerLinkId: String,
    val localDeviceId: String,
    val relayUrl: String,
    val relayPairId: String?,
    val createdAt: Long,
)

@Dao
abstract class PeerLinkDao {
    @Query("SELECT * FROM pending_relay_revocation ORDER BY createdAt")
    abstract suspend fun pendingRevocations(): List<PendingRelayRevocation>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    protected abstract suspend fun insertRevocation(row: PendingRelayRevocation)

    @Query("DELETE FROM pending_relay_revocation WHERE revocationId=:id")
    abstract suspend fun finishRevocation(id: String)

    @Query("UPDATE peer_link SET relayUrl=NULL, relayPairId=NULL, relayRevocationRequired=0 WHERE peerLinkId=:link AND lifecycle='ACTIVE'")
    protected abstract suspend fun disableRelay(link: String): Int

    @Transaction
    open suspend fun detachRelay(link: String, localDeviceId: String): PendingRelayRevocation? {
        val peer = get(link) ?: return null
        check(peer.lifecycle == "ACTIVE") { "peer_removing" }
        val url = peer.relayUrl ?: return null
        val pending = PendingRelayRevocation(java.util.UUID.randomUUID().toString(), link, localDeviceId,
            url, peer.relayPairId, System.currentTimeMillis().coerceAtLeast(0L))
        // The endpoint cannot disappear before its exact revocation selector is durable.
        insertRevocation(pending)
        check(disableRelay(link) == 1)
        return pending
    }

    @Query("SELECT * FROM peer_import_state WHERE id=0")
    abstract suspend fun importState(): PeerImportState?

    @Query("SELECT * FROM peer_link ORDER BY createdAt, peerLinkId")
    abstract suspend fun all(): List<PeerLink>

    @Query("SELECT * FROM peer_link WHERE peerLinkId=:peerLinkId")
    abstract suspend fun get(peerLinkId: String): PeerLink?

    @Query("SELECT * FROM peer_link WHERE deviceId=:deviceId")
    abstract suspend fun forDevice(deviceId: String): PeerLink?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    protected abstract suspend fun insert(row: PeerLink)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    protected abstract suspend fun markImported(row: PeerImportState)

    @Query("UPDATE outbound_message SET peerLinkId=:link WHERE peerLinkId='legacy'")
    protected abstract suspend fun importOutbound(link: String)
    @Query("UPDATE inbound_message SET peerLinkId=:link WHERE peerLinkId='legacy'")
    protected abstract suspend fun importInbound(link: String)
    @Query("UPDATE snapshot_stage SET peerLinkId=:link WHERE peerLinkId='legacy'")
    protected abstract suspend fun importSnapshots(link: String)
    @Query("UPDATE action_invocation SET peerLinkId=:link WHERE peerLinkId='legacy'")
    protected abstract suspend fun importInvocations(link: String)
    @Query("UPDATE action_execution SET peerLinkId=:link WHERE peerLinkId='legacy'")
    protected abstract suspend fun importExecutions(link: String)
    @Query("UPDATE canonical_notification_state SET peerLinkId=:link WHERE originDevice != :localDevice")
    protected abstract suspend fun importMirrorState(link: String, localDevice: String)
    @Query("SELECT (SELECT COUNT(*) FROM outbound_message) + (SELECT COUNT(*) FROM inbound_message) + (SELECT COUNT(*) FROM snapshot_stage) + (SELECT COUNT(*) FROM outbound_queue)")
    protected abstract suspend fun legacyWorkCount(): Int

    /** The marker and all row ownership move together; retries never duplicate the peer. */
    @Transaction
    open suspend fun importLegacy(localDeviceId: String, peer: PeerLink?) {
        importState()?.let { check(it.localDeviceId == localDeviceId) { "identity_import_mismatch" }; return }
        check(all().isEmpty()) { "peer_import_inconsistent" }
        if (peer == null) {
            check(legacyWorkCount() == 0) { "peer_import_missing_identity" }
        } else {
            insert(peer)
            importOutbound(peer.peerLinkId)
            importInbound(peer.peerLinkId)
            importSnapshots(peer.peerLinkId)
            importInvocations(peer.peerLinkId)
            importExecutions(peer.peerLinkId)
            importMirrorState(peer.peerLinkId, localDeviceId)
        }
        markImported(PeerImportState(localDeviceId = localDeviceId))
    }

    @Transaction
    open suspend fun add(row: PeerLink, requireNew: Boolean = false): PeerLink {
        check(importState() != null) { "peer_import_required" }
        forDevice(row.deviceId)?.let { current ->
            check(!requireNew) { "peer_identity_conflict" }
            check(current.lifecycle == "ACTIVE" && current.encPubkey.contentEquals(row.encPubkey) &&
                current.signPubkey.contentEquals(row.signPubkey) &&
                (row.relayPairId == null || current.relayPairId == row.relayPairId)) { "peer_identity_conflict" }
            return current
        }
        check(all().size < 2) { "peer_limit" }
        require(row.peerLinkId != LEGACY_PEER_LINK_ID && row.lifecycle == "ACTIVE")
        insert(row)
        return row
    }

    @Query("UPDATE peer_link SET relayPairId=:pairId WHERE peerLinkId=:link AND lifecycle='ACTIVE' AND (relayPairId IS NULL OR relayPairId=:pairId)")
    abstract suspend fun bindRelayPair(link: String, pairId: String): Int

    @Query("UPDATE peer_link SET relayUrl=:url, relayPairId=:pairId, relayRevocationRequired=1 WHERE peerLinkId=:link AND lifecycle='ACTIVE' AND (relayPairId IS NULL OR relayPairId=:pairId)")
    abstract suspend fun attachRelay(link: String, url: String, pairId: String): Int

    @Query("UPDATE peer_link SET preferLan=:preferLan WHERE peerLinkId=:link AND lifecycle='ACTIVE'")
    abstract suspend fun setPreferLan(link: String, preferLan: Boolean): Int

    @Query("UPDATE peer_link SET lanBindingId=:bindingId WHERE peerLinkId=:link AND lifecycle='ACTIVE' AND (lanBindingId IS NULL OR lanBindingId=:bindingId)")
    abstract suspend fun bindLan(link: String, bindingId: String): Int

    @Query("UPDATE peer_link SET lanBindingId=NULL WHERE peerLinkId=:link AND (:expected IS NULL OR lanBindingId=:expected)")
    abstract suspend fun clearLan(link: String, expected: String?): Int

    @Query("UPDATE peer_link SET lifecycle='REMOVING' WHERE peerLinkId=:link")
    abstract suspend fun beginRemoval(link: String): Int

    @Query("UPDATE peer_link SET relayRevocationRequired=0 WHERE peerLinkId=:link AND lifecycle='REMOVING'")
    abstract suspend fun relayRevoked(link: String): Int

    @Query("DELETE FROM peer_link")
    abstract suspend fun clearAllForReset()

    @Query("DELETE FROM peer_link WHERE peerLinkId=:link AND lifecycle='REMOVING'")
    abstract suspend fun finishRemoval(link: String): Int
}
