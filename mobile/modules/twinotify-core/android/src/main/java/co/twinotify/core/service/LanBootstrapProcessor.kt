package co.twinotify.core.service

import android.content.Context
import co.twinotify.core.crypto.CryptoStore
import co.twinotify.core.lan.LanBootstrapCrypto
import co.twinotify.core.lan.LanBootstrapIdentity
import co.twinotify.core.lan.LanBootstrapMaterial
import co.twinotify.core.pairing.lan.LanIdentityStore
import co.twinotify.core.storage.DeviceIdentity
import co.twinotify.core.storage.LanBinding
import co.twinotify.core.storage.LanPairStore
import co.twinotify.core.storage.PeerRecord
import co.twinotify.core.storage.PeerStore
import co.twinotify.core.storage.sameTrustMaterial
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException

sealed interface LanBootstrapProcessResult {
    data class Applied(val bindingChanged: Boolean) : LanBootstrapProcessResult
    data class Rejected(val code: String) : LanBootstrapProcessResult
}

fun interface LanBootstrapProcessor {
    suspend fun process(payload: LanBootstrapPayload): LanBootstrapProcessResult
}

internal data class LanBootstrapIdentityState(
    val local: LanBootstrapIdentity,
    val peer: PeerRecord,
    val localEncryptionSecretKey: ByteArray,
)

internal class DefaultLanBootstrapProcessor(
    private val loadIdentities: suspend () -> LanBootstrapIdentityState,
    private val localTlsPin: () -> ByteArray,
    private val derive: (LanBootstrapIdentity, LanBootstrapIdentity, ByteArray) -> LanBootstrapMaterial,
    private val loadBinding: suspend (PeerRecord) -> LanBinding?,
    private val ensureAnnouncement: suspend (LanBootstrapPayload) -> Unit,
    private val commitBinding: suspend (PeerRecord, LanBinding) -> Unit,
    private val clock: () -> Long = { System.currentTimeMillis().coerceAtLeast(0L) },
) : LanBootstrapProcessor {
    constructor(
        context: Context,
        controls: PeerControlOutbox,
        generation: () -> Int,
    ) : this(
        loadIdentities = {
            val localDevice = DeviceIdentity.getOrCreate(context)
            val peer = PeerStore.load(context)
                ?: throw IllegalStateException("LAN bootstrap requires a paired peer")
            val (box, sign) = CryptoStore.loadOrGenerate(context)
            LanBootstrapIdentityState(
                local = LanBootstrapIdentity(localDevice, box.publicKey, sign.publicKey),
                peer = peer,
                localEncryptionSecretKey = box.secretKey,
            )
        },
        localTlsPin = { LanIdentityStore.loadOrCreate().spkiSha256 },
        derive = LanBootstrapCrypto::derive,
        loadBinding = { peer -> LanPairStore.loadValidated(context, peer) },
        ensureAnnouncement = { payload -> controls.ensureBootstrap(generation(), payload) },
        commitBinding = { peer, binding ->
            LanPairStore.commit(context, LanPairStore.prepare(context, peer, binding))
        },
    )

    override suspend fun process(payload: LanBootstrapPayload): LanBootstrapProcessResult {
        val announcedPin = decodeDigest(payload.tlsSpkiSha256)
            ?: return LanBootstrapProcessResult.Rejected(PAYLOAD_INVALID)
        val announcedContext = decodeDigest(payload.bindingContextSha256)
            ?: return LanBootstrapProcessResult.Rejected(PAYLOAD_INVALID)
        if (payload.protocolVersion != 1) {
            announcedPin.fill(0)
            announcedContext.fill(0)
            return LanBootstrapProcessResult.Rejected(PAYLOAD_INVALID)
        }

        val identities = try {
            loadIdentities()
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            announcedPin.fill(0)
            announcedContext.fill(0)
            return LanBootstrapProcessResult.Rejected(CRYPTO_UNAVAILABLE)
        }
        val localSecret = identities.localEncryptionSecretKey.copyOf()
        var derivedSecret: ByteArray? = null
        var derivedContext: ByteArray? = null
        var ownPin: ByteArray? = null
        try {
            val peerIdentity = LanBootstrapIdentity(
                deviceId = identities.peer.deviceId,
                encryptionPublicKey = identities.peer.encPubkey,
                signingPublicKey = identities.peer.signPubkey,
            )
            val material = try {
                derive(identities.local, peerIdentity, localSecret)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Exception) {
                return LanBootstrapProcessResult.Rejected(CRYPTO_UNAVAILABLE)
            }
            derivedSecret = material.lanSecret
            derivedContext = material.bindingContextSha256
            if (!MessageDigest.isEqual(derivedContext, announcedContext)) {
                return LanBootstrapProcessResult.Rejected(CONTEXT_MISMATCH)
            }

            val candidate = try {
                LanBinding(
                    peerTlsSpkiSha256 = announcedPin,
                    lanSecret = derivedSecret,
                    protocolVersion = payload.protocolVersion,
                    pairedAtMillis = clock(),
                )
            } catch (_: IllegalArgumentException) {
                return LanBootstrapProcessResult.Rejected(PAYLOAD_INVALID)
            }
            val existing = try {
                loadBinding(identities.peer)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Exception) {
                return LanBootstrapProcessResult.Rejected(STORE_FAILED)
            }
            if (existing != null && !existing.sameTrustMaterial(candidate)) {
                return LanBootstrapProcessResult.Rejected(BINDING_CONFLICT)
            }

            ownPin = try {
                localTlsPin()
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Exception) {
                return LanBootstrapProcessResult.Rejected(STORE_FAILED)
            }
            if (ownPin.size != 32) return LanBootstrapProcessResult.Rejected(STORE_FAILED)
            val ownAnnouncement = LanBootstrapPayload(
                tlsSpkiSha256 = ownPin.toHex(),
                bindingContextSha256 = derivedContext.toHex(),
            )
            try {
                ensureAnnouncement(ownAnnouncement)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Exception) {
                return LanBootstrapProcessResult.Rejected(STORE_FAILED)
            }
            if (existing != null) return LanBootstrapProcessResult.Applied(bindingChanged = false)

            return try {
                commitBinding(identities.peer, candidate)
                LanBootstrapProcessResult.Applied(bindingChanged = true)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Exception) {
                LanBootstrapProcessResult.Rejected(STORE_FAILED)
            }
        } finally {
            announcedPin.fill(0)
            announcedContext.fill(0)
            localSecret.fill(0)
            identities.localEncryptionSecretKey.fill(0)
            derivedSecret?.fill(0)
            derivedContext?.fill(0)
            ownPin?.fill(0)
        }
    }

    private fun decodeDigest(value: String): ByteArray? {
        if (!value.matches(Regex("^[0-9a-f]{64}$"))) return null
        return ByteArray(32) { index -> value.substring(index * 2, index * 2 + 2).toInt(16).toByte() }
    }

    private fun ByteArray.toHex(): String = joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }

    private companion object {
        const val CRYPTO_UNAVAILABLE = "lan_bootstrap_crypto_unavailable"
        const val CONTEXT_MISMATCH = "lan_bootstrap_context_mismatch"
        const val PAYLOAD_INVALID = "lan_bootstrap_payload_invalid"
        const val STORE_FAILED = "lan_bootstrap_store_failed"
        const val BINDING_CONFLICT = "lan_binding_conflict"
    }
}
