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
    val peerLinkId: String = LEGACY_PEER_LINK_ID,
    val relayUrl: String? = null,
    val relayPairId: String? = null,
    val preferLan: Boolean = true,
    val lifecycle: String = "ACTIVE",
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

    private val importMutex = kotlinx.coroutines.sync.Mutex()

    suspend fun ensureImported(ctx: Context) {
        importMutex.lock()
        try {
            val app = ctx.applicationContext
            val dao = NotificationDb.get(app).peerLinkDao()
            val local = DeviceIdentity.getOrCreate(app)
            dao.importState()?.let {
                check(it.localDeviceId == local) { "identity_import_mismatch" }
                return
            }
            val legacy = legacyRecord(app.peerDs.data.first())
            val config = co.twinotify.core.service.ServiceConfigStore.read(app)
            val link = legacy?.let {
                PeerLink(
                    peerLinkId = java.util.UUID.nameUUIDFromBytes(("twinotify-import-v1\u0000" + local + "\u0000" + it.deviceId).toByteArray()).toString(),
                    deviceId = it.deviceId, encPubkey = it.encPubkey, signPubkey = it.signPubkey,
                    displayName = it.displayName, relayUrl = config.relayUrl, relayPairId = null,
                    preferLan = config.preferLan, lanBindingId = it.lanBindingId,
                    relayRevocationRequired = it.relayRevocationRequired ?: !config.relayUrl.isNullOrBlank(),
                    lifecycle = if (config.revocationRequestedAt != null) "REMOVING" else "ACTIVE",
                    createdAt = System.currentTimeMillis().coerceAtLeast(0L),
                )
            }
            // Convert v1 ciphertext while ownership is still singular, before publishing the import marker.
            co.twinotify.core.service.migrateLegacyOutboxBeforeRelay(
                NotificationDb.get(app).reliableDeliveryDao(), local,
            )
            dao.importLegacy(local, link)
        } finally { importMutex.unlock() }
    }

    suspend fun list(ctx: Context, includeRemoving: Boolean = false): List<PeerRecord> {
        ensureImported(ctx)
        return NotificationDb.get(ctx).peerLinkDao().all()
            .filter { includeRemoving || it.lifecycle == "ACTIVE" }.map { it.record() }
    }

    /** Missing selection is supported only while there is one active peer. */
    suspend fun load(ctx: Context, peerLinkId: String? = null): PeerRecord? {
        val peers = list(ctx)
        if (peerLinkId != null) return peers.singleOrNull { it.peerLinkId == peerLinkId }
        check(peers.size <= 1) { "peer_selection_required" }
        return peers.singleOrNull()
    }

    suspend fun forDevice(ctx: Context, deviceId: String): PeerRecord? =
        list(ctx).singleOrNull { it.deviceId == deviceId }

    suspend fun attachRelay(ctx: Context, peerLinkId: String, url: String, pairId: String) {
        val peer = checkNotNull(load(ctx, peerLinkId)) { "peer_removed" }
        val local = DeviceIdentity.getOrCreate(ctx)
        val sign = co.twinotify.core.crypto.CryptoStore.loadOrGenerate(ctx).second
        check(co.twinotify.core.pairing.PairProtocol.sessionPairId(url,
            co.twinotify.core.auth.JwtMinter.mint(local, sign.secretKey, pairId = pairId), local, peer.deviceId,
            debug = co.twinotify.core.BuildConfig.DEBUG) == pairId) { "pair_session_mismatch" }
        check(NotificationDb.get(ctx).peerLinkDao().attachRelay(peerLinkId, url, pairId) == 1) { "peer_changed" }
    }

    suspend fun prepareAdditionalPair(ctx: Context) {
        val peers = list(ctx, includeRemoving = true)
        check(NotificationDb.get(ctx).peerLinkDao().pendingRevocations().isEmpty()) { "relay_cleanup_pending" }
        check(peers.size < 2) { "peer_limit" }
        val local = DeviceIdentity.getOrCreate(ctx)
        for (peer in peers) {
            check(peer.lifecycle == "ACTIVE") { "peer_removal_pending" }
            val url = peer.relayUrl?.takeIf { it.isNotBlank() } ?: continue
            if (peer.relayPairId != null) continue
            val sign = co.twinotify.core.crypto.CryptoStore.loadOrGenerate(ctx).second
            val pairId = co.twinotify.core.pairing.PairProtocol.sessionPairId(url,
                co.twinotify.core.auth.JwtMinter.mint(local, sign.secretKey), local, peer.deviceId,
                debug = co.twinotify.core.BuildConfig.DEBUG)
            check(NotificationDb.get(ctx).peerLinkDao().bindRelayPair(peer.peerLinkId, pairId) == 1) {
                "pair_session_changed"
            }
        }
    }

    suspend fun save(ctx: Context, r: PeerRecord, requireNew: Boolean = false): PeerRecord {
        ensureImported(ctx)
        require(r.deviceId.isNotEmpty() && r.encPubkey.size == 32 && r.signPubkey.size == 32)
        val config = co.twinotify.core.service.ServiceConfigStore.read(ctx)
        val relayUrl = r.relayUrl ?: config.relayUrl.takeUnless { r.relayRevocationRequired == false }
        val stored = NotificationDb.get(ctx).peerLinkDao().add(PeerLink(
            peerLinkId = r.peerLinkId.takeUnless { it == LEGACY_PEER_LINK_ID } ?: java.util.UUID.randomUUID().toString(),
            deviceId = r.deviceId, encPubkey = r.encPubkey, signPubkey = r.signPubkey,
            displayName = r.displayName, relayUrl = relayUrl,
            relayPairId = r.relayPairId, preferLan = r.preferLan,
            lanBindingId = r.lanBindingId,
            relayRevocationRequired = r.relayRevocationRequired ?: !relayUrl.isNullOrBlank(),
            lifecycle = "ACTIVE", createdAt = System.currentTimeMillis().coerceAtLeast(0L),
        ), requireNew = requireNew).record()
        co.twinotify.core.service.SyncService.notifyRoutePreferenceChanged()
        return stored
    }

    internal suspend fun attachLanBinding(ctx: Context, expected: PeerRecord, bindingId: String): Boolean {
        val current = forDevice(ctx, expected.deviceId) ?: return false
        if (!current.samePublicIdentity(expected)) return false
        val attached = NotificationDb.get(ctx).peerLinkDao().bindLan(current.peerLinkId, bindingId) == 1
        if (attached) co.twinotify.core.service.SyncService.notifyRoutePreferenceChanged()
        return attached
    }

    internal suspend fun commitLanBinding(ctx: Context, expectedCurrent: PeerRecord?, proposedPeer: PeerRecord, bindingId: String): Boolean {
        if (expectedCurrent != null) return attachLanBinding(ctx, expectedCurrent, bindingId)
        if (forDevice(ctx, proposedPeer.deviceId) != null) return false
        return try {
            save(ctx, PeerRecord(proposedPeer.deviceId, proposedPeer.encPubkey, proposedPeer.signPubkey,
                proposedPeer.displayName, bindingId, proposedPeer.relayRevocationRequired,
                relayUrl = proposedPeer.relayUrl, relayPairId = proposedPeer.relayPairId), requireNew = true)
            true
        } catch (failure: IllegalStateException) { false }
    }

    internal suspend fun clearLanBinding(ctx: Context, expectedBindingId: String? = null, peerLinkId: String? = null) {
        val peers = list(ctx, includeRemoving = true)
        val selected = when {
            peerLinkId != null -> peers.singleOrNull { it.peerLinkId == peerLinkId }
            expectedBindingId != null -> peers.singleOrNull { it.lanBindingId == expectedBindingId }
            else -> { check(peers.size <= 1) { "peer_selection_required" }; peers.singleOrNull() }
        } ?: return
        NotificationDb.get(ctx).peerLinkDao().clearLan(selected.peerLinkId, expectedBindingId)
    }

    /** Full reset only; ordinary unpair uses the scoped removal lifecycle. */
    suspend fun clear(ctx: Context) {
        ensureImported(ctx)
        NotificationDb.get(ctx).peerLinkDao().clearAllForReset()
        ctx.peerDs.edit { it.clear() }
    }

    private fun legacyRecord(prefs: Preferences): PeerRecord? {
        val dev = prefs[KEY_PEER_DEVICE] ?: return null
        val enc = checkNotNull(prefs[KEY_PEER_ENC]) { "peer_import_missing_key" }
        val sign = checkNotNull(prefs[KEY_PEER_SIGN]) { "peer_import_missing_key" }
        require(enc.size == 32 && sign.size == 32) { "peer_import_invalid_key" }
        return PeerRecord(dev, enc, sign, prefs[KEY_PEER_NAME], prefs[KEY_LAN_BINDING], prefs[KEY_RELAY_REVOCATION_REQUIRED])
    }
}
