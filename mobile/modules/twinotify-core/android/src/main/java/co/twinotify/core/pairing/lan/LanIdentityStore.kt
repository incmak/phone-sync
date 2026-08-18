package co.twinotify.core.pairing.lan

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.Signature
import java.security.cert.X509Certificate
import java.security.spec.ECGenParameterSpec
import java.util.Date
import javax.security.auth.x500.X500Principal

/**
 * Installation-local TLS identity for direct LAN connections.
 *
 * This key intentionally has no relationship to the envelope X25519/Ed25519
 * keys. Its private material stays in Android Keystore and is only used by the
 * TLS key manager or [LanIdentity.sign].
 */
object LanIdentityStore {
    const val ALIAS = "twinotify.lan.tls.v1"

    private const val KEYSTORE = "AndroidKeyStore"
    private const val CERTIFICATE_COMMON_NAME = "Twinotify LAN"
    private const val CERTIFICATE_LIFETIME_MILLIS = 20L * 365 * 24 * 60 * 60 * 1_000
    private const val CLOCK_SKEW_MILLIS = 24L * 60 * 60 * 1_000
    private val lock = Any()

    fun loadOrCreate(): LanIdentity = synchronized(lock) {
        try {
            val keyStore = keyStore()
            existingIdentity(keyStore)?.let { return@synchronized it }
            generateIdentity(keyStore)
        } catch (error: LanIdentityException) {
            throw error
        } catch (_: Throwable) {
            throw LanIdentityException(LanIdentityFailure.LOAD_FAILED)
        }
    }

    /** Deletes the identity only as part of a complete unpair/reset. */
    fun delete() = synchronized(lock) {
        try {
            val keyStore = keyStore()
            if (keyStore.containsAlias(ALIAS)) keyStore.deleteEntry(ALIAS)
        } catch (_: Throwable) {
            throw LanIdentityException(LanIdentityFailure.DELETE_FAILED)
        }
    }

    internal fun keyStoreForTls(): KeyStore = try {
        keyStore()
    } catch (_: Throwable) {
        throw LanIdentityException(LanIdentityFailure.LOAD_FAILED)
    }

    private fun existingIdentity(keyStore: KeyStore): LanIdentity? {
        if (!keyStore.containsAlias(ALIAS)) return null
        val entry = keyStore.getEntry(ALIAS, null) as? KeyStore.PrivateKeyEntry
            ?: throw LanIdentityException(LanIdentityFailure.IDENTITY_INVALID)
        return identityFrom(entry)
    }

    private fun generateIdentity(keyStore: KeyStore): LanIdentity {
        val now = System.currentTimeMillis()
        val spec = KeyGenParameterSpec.Builder(
            ALIAS,
            KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY,
        )
            .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
            .setDigests(KeyProperties.DIGEST_SHA256)
            .setCertificateSubject(X500Principal("CN=$CERTIFICATE_COMMON_NAME"))
            .setCertificateSerialNumber(BigInteger.ONE)
            .setCertificateNotBefore(Date(now - CLOCK_SKEW_MILLIS))
            .setCertificateNotAfter(Date(now + CERTIFICATE_LIFETIME_MILLIS))
            .setUserAuthenticationRequired(false)
            .build()

        try {
            KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, KEYSTORE).apply {
                initialize(spec)
                generateKeyPair()
            }
            val entry = keyStore.getEntry(ALIAS, null) as? KeyStore.PrivateKeyEntry
                ?: throw LanIdentityException(LanIdentityFailure.IDENTITY_INVALID)
            return identityFrom(entry)
        } catch (error: LanIdentityException) {
            throw error
        } catch (_: Throwable) {
            throw LanIdentityException(LanIdentityFailure.GENERATE_FAILED)
        }
    }

    private fun identityFrom(entry: KeyStore.PrivateKeyEntry): LanIdentity {
        val chain = entry.certificateChain.map { certificate ->
            certificate as? X509Certificate
                ?: throw LanIdentityException(LanIdentityFailure.IDENTITY_INVALID)
        }.toTypedArray()
        if (chain.isEmpty() || entry.privateKey.encoded != null) {
            throw LanIdentityException(LanIdentityFailure.IDENTITY_INVALID)
        }
        return LanIdentity(entry.privateKey, chain)
    }

    private fun keyStore(): KeyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }
}

class LanIdentity internal constructor(
    private val privateKey: PrivateKey,
    certificateChain: Array<X509Certificate>,
) {
    private val chain = certificateChain.copyOf()
    private val pin = MessageDigest.getInstance("SHA-256").digest(chain.first().publicKey.encoded)

    val certificateChain: Array<X509Certificate>
        get() = chain.copyOf()

    val spkiSha256: ByteArray
        get() = pin.copyOf()

    fun sign(data: ByteArray): ByteArray = try {
        Signature.getInstance("SHA256withECDSA").run {
            initSign(privateKey)
            update(data)
            sign()
        }
    } catch (_: Throwable) {
        throw LanIdentityException(LanIdentityFailure.SIGN_FAILED)
    }

    fun verify(data: ByteArray, signature: ByteArray): Boolean = try {
        Signature.getInstance("SHA256withECDSA").run {
            initVerify(chain.first().publicKey)
            update(data)
            verify(signature)
        }
    } catch (_: Throwable) {
        throw LanIdentityException(LanIdentityFailure.VERIFY_FAILED)
    }
}

enum class LanIdentityFailure(val code: String) {
    LOAD_FAILED("lan_identity_load_failed"),
    GENERATE_FAILED("lan_identity_generate_failed"),
    IDENTITY_INVALID("lan_identity_invalid"),
    DELETE_FAILED("lan_identity_delete_failed"),
    SIGN_FAILED("lan_identity_sign_failed"),
    VERIFY_FAILED("lan_identity_verify_failed"),
    INVALID_PIN("lan_tls_invalid_pin"),
    TLS_CONTEXT_FAILED("lan_tls_context_failed"),
}

class LanIdentityException(val failure: LanIdentityFailure) : RuntimeException(failure.code)
