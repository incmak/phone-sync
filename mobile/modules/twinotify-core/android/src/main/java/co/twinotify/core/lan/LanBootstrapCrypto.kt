package co.twinotify.core.lan

import com.goterl.lazysodium.LazySodiumAndroid
import com.goterl.lazysodium.SodiumAndroid
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

data class LanBootstrapIdentity(
    val deviceId: String,
    val encryptionPublicKey: ByteArray,
    val signingPublicKey: ByteArray,
)

class LanBootstrapMaterial(
    lanSecret: ByteArray,
    bindingContextSha256: ByteArray,
) {
    private val secret = lanSecret.copyOf()
    private val contextDigest = bindingContextSha256.copyOf()

    val lanSecret: ByteArray
        get() = secret.copyOf()

    val bindingContextSha256: ByteArray
        get() = contextDigest.copyOf()
}

fun interface BoxPrecomputer {
    fun sharedKey(peerPublicKey: ByteArray, localSecretKey: ByteArray): ByteArray
}

enum class LanBootstrapCryptoFailure(val code: String) {
    UNAVAILABLE("lan_bootstrap_crypto_unavailable"),
}

class LanBootstrapCryptoException(val failure: LanBootstrapCryptoFailure) : RuntimeException(failure.code)

object LanBootstrapCrypto {
    private const val KEY_BYTES = 32
    private val contextDomain = "twinotify-lan-binding-context-v1\n".toByteArray(Charsets.UTF_8)
    private val secretDomain = "twinotify-lan-secret-v1\n".toByteArray(Charsets.UTF_8) + byteArrayOf(1)

    fun derive(
        local: LanBootstrapIdentity,
        peer: LanBootstrapIdentity,
        localEncryptionSecretKey: ByteArray,
    ): LanBootstrapMaterial = derive(local, peer, localEncryptionSecretKey, SodiumBoxPrecomputer)

    internal fun derive(
        local: LanBootstrapIdentity,
        peer: LanBootstrapIdentity,
        localEncryptionSecretKey: ByteArray,
        precomputer: BoxPrecomputer,
    ): LanBootstrapMaterial {
        validate(local)
        validate(peer)
        require(localEncryptionSecretKey.size == KEY_BYTES) { "invalid local encryption secret key" }

        val localDevice = local.deviceId.toByteArray(Charsets.UTF_8)
        val peerDevice = peer.deviceId.toByteArray(Charsets.UTF_8)
        require(!localDevice.contentEquals(peerDevice)) { "LAN bootstrap device IDs must differ" }

        val localSecret = localEncryptionSecretKey.copyOf()
        val peerPublic = peer.encryptionPublicKey.copyOf()
        var context: ByteArray? = null
        var contextDigest: ByteArray? = null
        var sharedKey: ByteArray? = null
        var prk: ByteArray? = null
        var lanSecret: ByteArray? = null
        try {
            val ordered = if (compareUnsigned(localDevice, peerDevice) < 0) {
                listOf(local, peer)
            } else {
                listOf(peer, local)
            }
            context = encodeContext(ordered)
            contextDigest = MessageDigest.getInstance("SHA-256").digest(context)
            sharedKey = try {
                precomputer.sharedKey(peerPublic, localSecret)
            } catch (_: Exception) {
                throw LanBootstrapCryptoException(LanBootstrapCryptoFailure.UNAVAILABLE)
            } catch (_: LinkageError) {
                throw LanBootstrapCryptoException(LanBootstrapCryptoFailure.UNAVAILABLE)
            }
            if (sharedKey.size != KEY_BYTES) {
                throw LanBootstrapCryptoException(LanBootstrapCryptoFailure.UNAVAILABLE)
            }
            prk = hmac(contextDigest, sharedKey)
            lanSecret = hmac(prk, secretDomain)
            return LanBootstrapMaterial(lanSecret, contextDigest)
        } finally {
            localDevice.fill(0)
            peerDevice.fill(0)
            localSecret.fill(0)
            peerPublic.fill(0)
            context?.fill(0)
            contextDigest?.fill(0)
            sharedKey?.fill(0)
            prk?.fill(0)
            lanSecret?.fill(0)
        }
    }

    private fun validate(identity: LanBootstrapIdentity) {
        require(identity.deviceId.isNotEmpty()) { "invalid LAN bootstrap device ID" }
        require(identity.encryptionPublicKey.size == KEY_BYTES) { "invalid encryption public key" }
        require(identity.signingPublicKey.size == KEY_BYTES) { "invalid signing public key" }
    }

    private fun encodeContext(identities: List<LanBootstrapIdentity>): ByteArray =
        ByteArrayOutputStream().use { bytes ->
            bytes.write(contextDomain)
            DataOutputStream(bytes).use { out ->
                identities.forEach { identity ->
                    val device = identity.deviceId.toByteArray(Charsets.UTF_8)
                    val encryptionKey = identity.encryptionPublicKey.copyOf()
                    val signingKey = identity.signingPublicKey.copyOf()
                    try {
                        writeLengthDelimited(out, device)
                        writeLengthDelimited(out, encryptionKey)
                        writeLengthDelimited(out, signingKey)
                    } finally {
                        device.fill(0)
                        encryptionKey.fill(0)
                        signingKey.fill(0)
                    }
                }
            }
            bytes.toByteArray()
        }

    private fun writeLengthDelimited(out: DataOutputStream, value: ByteArray) {
        out.writeInt(value.size)
        out.write(value)
    }

    private fun compareUnsigned(left: ByteArray, right: ByteArray): Int {
        for (index in 0 until minOf(left.size, right.size)) {
            val compared = (left[index].toInt() and 0xff).compareTo(right[index].toInt() and 0xff)
            if (compared != 0) return compared
        }
        return left.size.compareTo(right.size)
    }

    private fun hmac(key: ByteArray, value: ByteArray): ByteArray = Mac.getInstance("HmacSHA256").run {
        init(SecretKeySpec(key, "HmacSHA256"))
        doFinal(value)
    }
}

private object SodiumBoxPrecomputer : BoxPrecomputer {
    private val sodium by lazy { LazySodiumAndroid(SodiumAndroid()).sodium }

    override fun sharedKey(peerPublicKey: ByteArray, localSecretKey: ByteArray): ByteArray {
        val shared = ByteArray(32)
        if (sodium.crypto_box_beforenm(shared, peerPublicKey, localSecretKey) != 0) {
            shared.fill(0)
            throw IllegalStateException("crypto_box_beforenm failed")
        }
        return shared
    }
}
