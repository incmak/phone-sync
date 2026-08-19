package co.twinotify.core.storage

import android.content.Context
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first
import java.text.Normalizer

private val Context.peerDs by preferencesDataStore("twinotify_peer")

/**
 * Public peer identity. LAN secrets deliberately do not belong here: this store
 * remains plaintext so a LAN binding can only leave a public commit marker.
 */
class PeerRecord(
    val deviceId: String,
    encPubkey: ByteArray,
    signPubkey: ByteArray,
    val displayName: String? = null,
    val lanBindingId: String? = null,
    val relayRevocationRequired: Boolean? = null,
) {
    private val encryptionKey = encPubkey.copyOf()
    private val signingKey = signPubkey.copyOf()

    val encPubkey: ByteArray
        get() = encryptionKey.copyOf()

    val signPubkey: ByteArray
        get() = signingKey.copyOf()

    internal fun samePublicIdentity(other: PeerRecord): Boolean =
        deviceId == other.deviceId &&
            encryptionKey.contentEquals(other.encryptionKey) &&
            signingKey.contentEquals(other.signingKey) &&
            normalizedName(displayName) == normalizedName(other.displayName)

    private fun normalizedName(value: String?): String? = value?.let { Normalizer.normalize(it, Normalizer.Form.NFC) }
}

object PeerStore {
    private val KEY_PEER_DEVICE = stringPreferencesKey("peer_device_id")
    private val KEY_PEER_ENC    = byteArrayPreferencesKey("peer_enc_pubkey")
    private val KEY_PEER_SIGN   = byteArrayPreferencesKey("peer_sign_pubkey")
    private val KEY_PEER_NAME   = stringPreferencesKey("peer_display_name")
    private val KEY_LAN_BINDING = stringPreferencesKey("lan_binding_id")
    private val KEY_RELAY_REVOCATION_REQUIRED = booleanPreferencesKey("relay_revocation_required")

    suspend fun save(ctx: Context, r: PeerRecord) {
        ctx.peerDs.edit { e ->
            e[KEY_PEER_DEVICE] = r.deviceId
            e[KEY_PEER_ENC]    = r.encPubkey
            e[KEY_PEER_SIGN]   = r.signPubkey
            r.displayName?.let { e[KEY_PEER_NAME] = it } ?: e.remove(KEY_PEER_NAME)
            r.lanBindingId?.let { e[KEY_LAN_BINDING] = it } ?: e.remove(KEY_LAN_BINDING)
            r.relayRevocationRequired?.let { e[KEY_RELAY_REVOCATION_REQUIRED] = it }
                ?: e.remove(KEY_RELAY_REVOCATION_REQUIRED)
        }
    }

    suspend fun load(ctx: Context): PeerRecord? {
        val prefs = ctx.peerDs.data.first()
        val dev  = prefs[KEY_PEER_DEVICE] ?: return null
        val enc  = prefs[KEY_PEER_ENC]    ?: return null
        val sign = prefs[KEY_PEER_SIGN]   ?: return null
        val name = prefs[KEY_PEER_NAME]
        return PeerRecord(
            dev,
            enc,
            sign,
            name,
            prefs[KEY_LAN_BINDING],
            prefs[KEY_RELAY_REVOCATION_REQUIRED],
        )
    }

    /** Atomically adds the sole public marker only if the relay identity still matches. */
    internal suspend fun attachLanBinding(ctx: Context, expected: PeerRecord, bindingId: String): Boolean {
        var committed = false
        ctx.peerDs.edit { prefs ->
            val current = record(prefs)
            if (current != null && current.samePublicIdentity(expected) &&
                (current.lanBindingId == null || current.lanBindingId == bindingId)
            ) {
                prefs[KEY_LAN_BINDING] = bindingId
                committed = true
            }
        }
        return committed
    }

    /**
     * One DataStore edit either creates the first public peer with its marker,
     * or attaches only the marker to an unchanged relay peer. It never replaces
     * a concurrently created or rebound identity.
     */
    internal suspend fun commitLanBinding(
        ctx: Context,
        expectedCurrent: PeerRecord?,
        proposedPeer: PeerRecord,
        bindingId: String,
    ): Boolean {
        var committed = false
        ctx.peerDs.edit { prefs ->
            val current = record(prefs)
            if (expectedCurrent == null) {
                if (current == null) {
                    prefs[KEY_PEER_DEVICE] = proposedPeer.deviceId
                    prefs[KEY_PEER_ENC] = proposedPeer.encPubkey
                    prefs[KEY_PEER_SIGN] = proposedPeer.signPubkey
                    proposedPeer.displayName?.let { prefs[KEY_PEER_NAME] = it } ?: prefs.remove(KEY_PEER_NAME)
                    prefs[KEY_LAN_BINDING] = bindingId
                    proposedPeer.relayRevocationRequired?.let { prefs[KEY_RELAY_REVOCATION_REQUIRED] = it }
                        ?: prefs.remove(KEY_RELAY_REVOCATION_REQUIRED)
                    committed = true
                }
            } else if (current != null && current.samePublicIdentity(expectedCurrent) &&
                (current.lanBindingId == null || current.lanBindingId == bindingId)
            ) {
                prefs[KEY_LAN_BINDING] = bindingId
                committed = true
            }
        }
        return committed
    }

    /** Disables only LAN for the current pair, leaving its relay identity intact. */
    internal suspend fun clearLanBinding(ctx: Context, expectedBindingId: String? = null) {
        ctx.peerDs.edit { prefs ->
            if (expectedBindingId == null || prefs[KEY_LAN_BINDING] == expectedBindingId) {
                prefs.remove(KEY_LAN_BINDING)
            }
        }
    }

    suspend fun clear(ctx: Context) {
        ctx.peerDs.edit { it.clear() }
    }

    private fun record(prefs: Preferences): PeerRecord? {
        val dev = prefs[KEY_PEER_DEVICE] ?: return null
        val enc = prefs[KEY_PEER_ENC] ?: return null
        val sign = prefs[KEY_PEER_SIGN] ?: return null
        return PeerRecord(
            dev,
            enc,
            sign,
            prefs[KEY_PEER_NAME],
            prefs[KEY_LAN_BINDING],
            prefs[KEY_RELAY_REVOCATION_REQUIRED],
        )
    }
}
