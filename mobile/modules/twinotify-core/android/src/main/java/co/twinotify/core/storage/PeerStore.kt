package co.twinotify.core.storage

import android.content.Context
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first

private val Context.peerDs by preferencesDataStore("twinotify_peer")

data class PeerRecord(
    val deviceId: String,
    val encPubkey: ByteArray,
    val signPubkey: ByteArray,
    val displayName: String? = null,
)

object PeerStore {
    private val KEY_PEER_DEVICE = stringPreferencesKey("peer_device_id")
    private val KEY_PEER_ENC    = byteArrayPreferencesKey("peer_enc_pubkey")
    private val KEY_PEER_SIGN   = byteArrayPreferencesKey("peer_sign_pubkey")
    private val KEY_PEER_NAME   = stringPreferencesKey("peer_display_name")

    suspend fun save(ctx: Context, r: PeerRecord) {
        ctx.peerDs.edit { e ->
            e[KEY_PEER_DEVICE] = r.deviceId
            e[KEY_PEER_ENC]    = r.encPubkey
            e[KEY_PEER_SIGN]   = r.signPubkey
            r.displayName?.let { e[KEY_PEER_NAME] = it } ?: e.remove(KEY_PEER_NAME)
        }
    }

    suspend fun load(ctx: Context): PeerRecord? {
        val prefs = ctx.peerDs.data.first()
        val dev  = prefs[KEY_PEER_DEVICE] ?: return null
        val enc  = prefs[KEY_PEER_ENC]    ?: return null
        val sign = prefs[KEY_PEER_SIGN]   ?: return null
        val name = prefs[KEY_PEER_NAME]
        return PeerRecord(dev, enc, sign, name)
    }

    suspend fun clear(ctx: Context) {
        ctx.peerDs.edit { it.clear() }
    }
}
