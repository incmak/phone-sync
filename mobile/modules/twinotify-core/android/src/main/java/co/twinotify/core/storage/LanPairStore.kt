package co.twinotify.core.storage

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.byteArrayPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import co.twinotify.core.crypto.Sealed
import co.twinotify.core.crypto.WrappedKeys
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.security.MessageDigest
import java.security.SecureRandom
import java.text.Normalizer
import java.util.Base64
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

private val Context.lanPairDs by preferencesDataStore("twinotify_lan_pair")

/** Native-only LAN trust material. Every byte-array boundary copies its value. */
class LanBinding(
    peerTlsSpkiSha256: ByteArray,
    lanSecret: ByteArray,
    val protocolVersion: Int,
    val pairedAtMillis: Long,
) {
    private val tlsPin = peerTlsSpkiSha256.copyOf()
    private val secret = lanSecret.copyOf()

    init {
        require(tlsPin.size == SHA256_BYTES) { "invalid LAN TLS pin" }
        require(secret.size == LAN_SECRET_BYTES) { "invalid LAN secret" }
        require(protocolVersion in 1..MAX_PROTOCOL_VERSION) { "invalid LAN protocol version" }
        require(pairedAtMillis >= 0) { "invalid LAN pairing time" }
    }

    val peerTlsSpkiSha256: ByteArray
        get() = tlsPin.copyOf()

    val lanSecret: ByteArray
        get() = secret.copyOf()

    internal fun sameValue(other: LanBinding): Boolean =
        protocolVersion == other.protocolVersion && pairedAtMillis == other.pairedAtMillis &&
            MessageDigest.isEqual(tlsPin, other.tlsPin) && MessageDigest.isEqual(secret, other.secret)

    internal companion object {
        const val SHA256_BYTES = 32
        const val LAN_SECRET_BYTES = 32
        const val MAX_PROTOCOL_VERSION = 1
    }
}

class PreparedLanBinding internal constructor(
    internal val peer: PeerRecord,
    internal val bindingId: String,
    internal val identityDigest: ByteArray,
    internal val binding: LanBinding,
    internal val sealed: Sealed,
)

enum class LanPairStoreFailure(val code: String) {
    INVALID_BINDING("lan_binding_invalid"),
    SEALED_RECORD_INVALID("lan_binding_sealed_invalid"),
    PEER_CHANGED("lan_binding_peer_changed"),
    REPLACEMENT_REJECTED("lan_binding_replacement_rejected"),
}

class LanPairStoreException(val failure: LanPairStoreFailure) : RuntimeException(failure.code)

/**
 * A one-record trust store. The outer DataStore has no secret plaintext; its
 * payload is a Keystore AES-GCM sealed, fixed-size, closed-world record.
 */
object LanPairStore {
    private const val OUTER_VERSION = 1
    private const val BINDING_ID_BYTES = 16
    private const val MAX_SEALED_BYTES = 512
    private const val GCM_IV_BYTES = 12
    private const val MAX_DEVICE_ID_BYTES = 256
    private const val MAX_DISPLAY_NAME_BYTES = 256
    private const val INNER_MAGIC = 0x544c5042 // TLPB

    private val KEY_VERSION = intPreferencesKey("version")
    private val KEY_BINDING_ID = stringPreferencesKey("binding_id")
    private val KEY_CIPHERTEXT = byteArrayPreferencesKey("ciphertext")
    private val KEY_IV = byteArrayPreferencesKey("iv")
    private val lock = Mutex()
    private val random = SecureRandom()

    suspend fun prepare(context: Context, peer: PeerRecord, binding: LanBinding): PreparedLanBinding {
        // Keep the public API context-shaped with the other stores; sealing uses
        // the installation Keystore rather than an application-held key.
        context.applicationContext
        val idBytes = ByteArray(BINDING_ID_BYTES).also(random::nextBytes)
        val bindingId = Base64.getUrlEncoder().withoutPadding().encodeToString(idBytes)
        val digest = identityDigest(peer)
        val inner = encodeInner(idBytes, digest, binding)
        val sealed = try {
            WrappedKeys.seal(inner)
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            throw LanPairStoreException(LanPairStoreFailure.SEALED_RECORD_INVALID)
        }
        return PreparedLanBinding(
            peer = copyPeer(peer),
            bindingId = bindingId,
            identityDigest = digest.copyOf(),
            binding = copyBinding(binding),
            sealed = Sealed(sealed.ciphertext.copyOf(), sealed.iv.copyOf()),
        )
    }

    /**
     * Phase one persists and verifies the sealed record. Phase two is exactly
     * one [PeerStore] edit that adds the public commit marker.
     */
    suspend fun commit(context: Context, prepared: PreparedLanBinding) = lock.withLock {
        val current = PeerStore.load(context)
            ?: throw LanPairStoreException(LanPairStoreFailure.PEER_CHANGED)
        if (!current.samePublicIdentity(prepared.peer)) {
            throw LanPairStoreException(LanPairStoreFailure.PEER_CHANGED)
        }

        val existing = readOuter(context)
        if (current.lanBindingId != null) {
            if (current.lanBindingId == prepared.bindingId && existing != null && verify(existing, prepared)) {
                return@withLock // exact retry after a process crash or lost caller acknowledgement
            }
            if (existing != null && isValidFor(existing, current)) {
                throw LanPairStoreException(LanPairStoreFailure.REPLACEMENT_REJECTED)
            }
            // An invalid public marker is never LAN trust. Disable it but keep
            // the relay pair, then permit the authenticated caller to upgrade.
            PeerStore.clearLanBinding(context, current.lanBindingId)
            clearOuter(context)
        } else if (existing != null) {
            // A prior crash left a phase-one orphan. It is unusable.
            clearOuter(context)
        }

        writeOuter(context, OuterRecord(prepared.bindingId, prepared.sealed))
        val written = readOuter(context)
        if (written == null || !verify(written, prepared)) {
            clearOuterIfId(context, prepared.bindingId)
            throw LanPairStoreException(LanPairStoreFailure.SEALED_RECORD_INVALID)
        }
        if (!PeerStore.attachLanBinding(context, prepared.peer, prepared.bindingId)) {
            clearOuterIfId(context, prepared.bindingId)
            throw LanPairStoreException(LanPairStoreFailure.PEER_CHANGED)
        }
    }

    suspend fun loadValidated(context: Context, peer: PeerRecord): LanBinding? = lock.withLock {
        val marker = peer.lanBindingId ?: return@withLock null
        val outer = readOuter(context)
        if (outer != null && outer.bindingId == marker) {
            val stored = decodeStored(outer)
            if (stored != null && MessageDigest.isEqual(stored.identityDigest, identityDigest(peer))) {
                return@withLock copyBinding(stored.binding)
            }
        }
        // A public marker without a verified sealed match only disables LAN.
        PeerStore.clearLanBinding(context, marker)
        clearOuter(context)
        null
    }

    /** Removes phase-one orphans, without deleting a valid relay-only peer. */
    suspend fun recover(context: Context, peer: PeerRecord?) = lock.withLock {
        val outer = readOuter(context) ?: run {
            // A partial/corrupt outer preferences record is not a recoverable
            // binding and must not survive startup as latent state.
            clearOuter(context)
            return@withLock
        }
        val marker = peer?.lanBindingId
        if (peer != null && marker == outer.bindingId && isValidFor(outer, peer)) return@withLock

        clearOuterIfId(context, outer.bindingId)
        if (marker != null) PeerStore.clearLanBinding(context, marker)
    }

    suspend fun clear(context: Context) = lock.withLock { clearOuter(context) }

    /** Digest is public, domain-separated, length-delimited, and normalizes NFC names. */
    internal fun identityDigest(peer: PeerRecord): ByteArray = try {
        val device = peer.deviceId.toByteArray(Charsets.UTF_8)
        val name = peer.displayName?.let { Normalizer.normalize(it, Normalizer.Form.NFC).toByteArray(Charsets.UTF_8) }
        if (device.isEmpty() || device.size > MAX_DEVICE_ID_BYTES || name?.size ?: 0 > MAX_DISPLAY_NAME_BYTES ||
            peer.encPubkey.size != LanBinding.SHA256_BYTES || peer.signPubkey.size != LanBinding.SHA256_BYTES
        ) throw LanPairStoreException(LanPairStoreFailure.INVALID_BINDING)
        ByteArrayOutputStream().use { bytes ->
            DataOutputStream(bytes).use { out ->
                out.writeInt(1)
                writeBounded(out, device, MAX_DEVICE_ID_BYTES)
                writeBounded(out, peer.encPubkey, LanBinding.SHA256_BYTES)
                writeBounded(out, peer.signPubkey, LanBinding.SHA256_BYTES)
                out.writeBoolean(name != null)
                if (name != null) writeBounded(out, name, MAX_DISPLAY_NAME_BYTES)
            }
            MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray())
        }
    } catch (error: LanPairStoreException) {
        throw error
    } catch (_: Exception) {
        throw LanPairStoreException(LanPairStoreFailure.INVALID_BINDING)
    }

    private suspend fun readOuter(context: Context): OuterRecord? {
        val prefs = context.lanPairDs.data.first()
        val version = prefs[KEY_VERSION] ?: return null
        val id = prefs[KEY_BINDING_ID] ?: return null
        val ciphertext = prefs[KEY_CIPHERTEXT] ?: return null
        val iv = prefs[KEY_IV] ?: return null
        if (version != OUTER_VERSION || !validBindingId(id) || ciphertext.isEmpty() || ciphertext.size > MAX_SEALED_BYTES || iv.size != GCM_IV_BYTES) {
            return null
        }
        return OuterRecord(id, Sealed(ciphertext.copyOf(), iv.copyOf()))
    }

    private suspend fun writeOuter(context: Context, record: OuterRecord) {
        context.lanPairDs.edit { prefs ->
            prefs[KEY_VERSION] = OUTER_VERSION
            prefs[KEY_BINDING_ID] = record.bindingId
            prefs[KEY_CIPHERTEXT] = record.sealed.ciphertext.copyOf()
            prefs[KEY_IV] = record.sealed.iv.copyOf()
        }
    }

    private suspend fun clearOuter(context: Context) {
        context.lanPairDs.edit { it.clear() }
    }

    private suspend fun clearOuterIfId(context: Context, bindingId: String) {
        context.lanPairDs.edit { prefs ->
            if (prefs[KEY_BINDING_ID] == bindingId) prefs.clear()
        }
    }

    private fun verify(outer: OuterRecord, prepared: PreparedLanBinding): Boolean {
        if (outer.bindingId != prepared.bindingId) return false
        val stored = decodeStored(outer) ?: return false
        return MessageDigest.isEqual(stored.identityDigest, prepared.identityDigest) && stored.binding.sameValue(prepared.binding)
    }

    private fun isValidFor(outer: OuterRecord, peer: PeerRecord): Boolean {
        val stored = decodeStored(outer) ?: return false
        return MessageDigest.isEqual(stored.identityDigest, identityDigest(peer))
    }

    private fun decodeStored(outer: OuterRecord): StoredBinding? = try {
        val plaintext = WrappedKeys.unseal(outer.sealed)
        decodeInner(plaintext, outer.bindingId)
    } catch (error: CancellationException) {
        throw error
    } catch (_: Exception) {
        null
    }

    private fun encodeInner(bindingId: ByteArray, digest: ByteArray, binding: LanBinding): ByteArray =
        ByteArrayOutputStream().use { bytes ->
            DataOutputStream(bytes).use { out ->
                out.writeInt(INNER_MAGIC)
                out.writeByte(OUTER_VERSION)
                out.write(bindingId)
                out.write(digest)
                out.write(binding.peerTlsSpkiSha256)
                out.write(binding.lanSecret)
                out.writeInt(binding.protocolVersion)
                out.writeLong(binding.pairedAtMillis)
            }
            bytes.toByteArray()
        }

    private fun decodeInner(plaintext: ByteArray, expectedId: String): StoredBinding? {
        val expectedSize = 4 + 1 + BINDING_ID_BYTES + (LanBinding.SHA256_BYTES * 3) + 4 + 8
        if (plaintext.size != expectedSize) return null
        DataInputStream(ByteArrayInputStream(plaintext)).use { input ->
            if (input.readInt() != INNER_MAGIC || input.readUnsignedByte() != OUTER_VERSION) return null
            val id = ByteArray(BINDING_ID_BYTES).also(input::readFully)
            if (Base64.getUrlEncoder().withoutPadding().encodeToString(id) != expectedId) return null
            val digest = ByteArray(LanBinding.SHA256_BYTES).also(input::readFully)
            val pin = ByteArray(LanBinding.SHA256_BYTES).also(input::readFully)
            val secret = ByteArray(LanBinding.LAN_SECRET_BYTES).also(input::readFully)
            val version = input.readInt()
            val pairedAt = input.readLong()
            if (input.available() != 0) return null
            return try {
                StoredBinding(digest, LanBinding(pin, secret, version, pairedAt))
            } catch (_: IllegalArgumentException) {
                null
            }
        }
    }

    private fun validBindingId(value: String): Boolean = try {
        val decoded = Base64.getUrlDecoder().decode(value)
        decoded.size == BINDING_ID_BYTES && Base64.getUrlEncoder().withoutPadding().encodeToString(decoded) == value
    } catch (_: IllegalArgumentException) {
        false
    }

    private fun writeBounded(out: DataOutputStream, value: ByteArray, maximum: Int) {
        require(value.size <= maximum) { "oversized identity field" }
        out.writeInt(value.size)
        out.write(value)
    }

    private fun copyPeer(peer: PeerRecord) = PeerRecord(
        peer.deviceId, peer.encPubkey, peer.signPubkey, peer.displayName, peer.lanBindingId,
    )

    private fun copyBinding(binding: LanBinding) = LanBinding(
        binding.peerTlsSpkiSha256, binding.lanSecret, binding.protocolVersion, binding.pairedAtMillis,
    )

    private data class OuterRecord(val bindingId: String, val sealed: Sealed)
    private data class StoredBinding(val identityDigest: ByteArray, val binding: LanBinding)

    // Instrumentation-only crash simulation seams. They never accept or reveal a secret.
    internal suspend fun writeSealedForTest(context: Context, prepared: PreparedLanBinding) = lock.withLock {
        writeOuter(context, OuterRecord(prepared.bindingId, prepared.sealed))
    }

    internal suspend fun writeCorruptForTest(context: Context) = lock.withLock {
        val current = readOuter(context) ?: return@withLock
        writeOuter(context, OuterRecord(current.bindingId, Sealed(byteArrayOf(1), ByteArray(GCM_IV_BYTES))))
    }

    internal suspend fun sealedBindingIdForTest(context: Context): String? = lock.withLock { readOuter(context)?.bindingId }
}
