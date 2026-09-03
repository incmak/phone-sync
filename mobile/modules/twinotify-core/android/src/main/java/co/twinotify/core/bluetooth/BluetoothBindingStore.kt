package co.twinotify.core.bluetooth

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import co.twinotify.core.storage.PeerRecord
import java.security.MessageDigest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

private val Context.bluetoothBindingDs by preferencesDataStore("twinotify_bluetooth_binding")

/**
 * Public binding facts between a Companion Device Manager association and the Twinotify peer.
 * The raw Bluetooth address is deliberately absent: only the CDM association ID is durable.
 */
data class BluetoothBinding(
    val associationId: Int,
    val peerDeviceId: String,
    val peerSigningKeySha256: String,
    val protocolVersion: Int = BluetoothConstants.PROTOCOL_VERSION,
) {
    init {
        require(peerDeviceId.isNotEmpty()) { "invalid Bluetooth peer device id" }
        require(peerSigningKeySha256.length == SHA256_HEX_LENGTH) { "invalid Bluetooth peer signing digest" }
        require(protocolVersion > 0) { "invalid Bluetooth protocol version" }
    }

    companion object {
        private const val SHA256_HEX_LENGTH = 64

        fun signingKeyDigest(signPubkey: ByteArray): String =
            MessageDigest.getInstance("SHA-256").digest(signPubkey).joinToString("") { "%02x".format(it) }
    }
}

/**
 * Dedicated preferences file for the Bluetooth route. A binding that no longer matches the
 * current peer, or whose association is gone, is cleared here and nowhere else: LAN and relay
 * pairing state are never touched by this store.
 */
class BluetoothBindingStore(private val dataStore: DataStore<Preferences>) {
    private val lock = Mutex()

    suspend fun save(binding: BluetoothBinding) = lock.withLock {
        dataStore.edit { prefs ->
            prefs[KEY_ASSOCIATION_ID] = binding.associationId
            prefs[KEY_PEER_DEVICE_ID] = binding.peerDeviceId
            prefs[KEY_PEER_SIGNING_KEY_SHA256] = binding.peerSigningKeySha256
            prefs[KEY_PROTOCOL_VERSION] = binding.protocolVersion
        }
    }

    /**
     * The stored binding when it still names [peer] (exact device ID, constant-time digest of the
     * current signing key, protocol version 1) and [currentAssociationIds] still contains its
     * association. Any mismatch clears the Bluetooth binding and its route enablement.
     */
    suspend fun loadValidated(peer: PeerRecord, currentAssociationIds: Set<Int>): BluetoothBinding? = lock.withLock {
        val stored = readBinding() ?: return@withLock null
        if (isValidFor(stored, peer, currentAssociationIds)) {
            stored
        } else {
            dataStore.edit { it.clear() }
            null
        }
    }

    /** The association ID regardless of validity, so explicit removal can disassociate it. */
    suspend fun storedAssociationId(): Int? = lock.withLock { readBinding()?.associationId }

    suspend fun setRouteEnabled(enabled: Boolean) = lock.withLock {
        dataStore.edit { prefs ->
            if (enabled) prefs[KEY_ROUTE_ENABLED] = true else prefs.remove(KEY_ROUTE_ENABLED)
        }
    }

    suspend fun routeEnabled(): Boolean = dataStore.data.first()[KEY_ROUTE_ENABLED] ?: false

    suspend fun clear() = lock.withLock {
        dataStore.edit { it.clear() }
    }

    private suspend fun readBinding(): BluetoothBinding? {
        val prefs = dataStore.data.first()
        val associationId = prefs[KEY_ASSOCIATION_ID] ?: return null
        val peerDeviceId = prefs[KEY_PEER_DEVICE_ID] ?: return null
        val digest = prefs[KEY_PEER_SIGNING_KEY_SHA256] ?: return null
        val version = prefs[KEY_PROTOCOL_VERSION] ?: return null
        return try {
            BluetoothBinding(associationId, peerDeviceId, digest, version)
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    private fun isValidFor(stored: BluetoothBinding, peer: PeerRecord, currentAssociationIds: Set<Int>): Boolean {
        val sameDevice = MessageDigest.isEqual(
            stored.peerDeviceId.toByteArray(Charsets.UTF_8),
            peer.deviceId.toByteArray(Charsets.UTF_8),
        )
        val sameKey = MessageDigest.isEqual(
            stored.peerSigningKeySha256.toByteArray(Charsets.US_ASCII),
            BluetoothBinding.signingKeyDigest(peer.signPubkey).toByteArray(Charsets.US_ASCII),
        )
        val supportedVersion = stored.protocolVersion == BluetoothConstants.PROTOCOL_VERSION
        val associated = stored.associationId in currentAssociationIds
        return sameDevice && sameKey && supportedVersion && associated
    }

    companion object {
        private val KEY_ASSOCIATION_ID = intPreferencesKey("association_id")
        private val KEY_PEER_DEVICE_ID = stringPreferencesKey("peer_device_id")
        private val KEY_PEER_SIGNING_KEY_SHA256 = stringPreferencesKey("peer_signing_key_sha256")
        private val KEY_PROTOCOL_VERSION = intPreferencesKey("protocol_version")
        private val KEY_ROUTE_ENABLED = booleanPreferencesKey("route_enabled")

        fun forContext(context: Context): BluetoothBindingStore =
            BluetoothBindingStore(context.applicationContext.bluetoothBindingDs)
    }
}
