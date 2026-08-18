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
import kotlinx.coroutines.CancellationException

/**
 * Installation-local TLS identity for direct LAN connections.
 *
 * This key intentionally has no relationship to the envelope X25519/Ed25519
 * keys. Its private material stays in Android Keystore and is only used by the
 * TLS key manager or [LanIdentity.sign].
 */
object LanIdentityStore {
    const val ALIAS = "twinotify.lan.tls.v1"

    private val operations = LanIdentityOperations(AndroidKeyStoreLanIdentityProvider)

    fun loadOrCreate(): LanIdentity = operations.loadOrCreate()

    /** Deletes the identity only as part of a complete unpair/reset. */
    fun delete() = operations.delete()

    internal fun keyStoreForTls(): KeyStore = operations.keyStoreForTls()
}

/**
 * Internal, instance-local keystore boundary. Production always supplies
 * [AndroidKeyStoreLanIdentityProvider]; callers cannot replace it through
 * [LanIdentityStore].
 */
internal interface LanIdentityKeyStoreProvider {
    fun loadIdentity(alias: String): KeyStore.PrivateKeyEntry?
    fun generateIdentity(alias: String, spec: KeyGenParameterSpec): KeyStore.PrivateKeyEntry
    fun deleteIdentity(alias: String)
    fun keyStoreForTls(): KeyStore
}

private object AndroidKeyStoreLanIdentityProvider : LanIdentityKeyStoreProvider {
    private const val KEYSTORE = "AndroidKeyStore"

    override fun loadIdentity(alias: String): KeyStore.PrivateKeyEntry? {
        val keyStore = keyStore()
        if (!keyStore.containsAlias(alias)) return null
        return keyStore.getEntry(alias, null) as? KeyStore.PrivateKeyEntry
            ?: throw LanIdentityException(LanIdentityFailure.IDENTITY_INVALID)
    }

    override fun generateIdentity(alias: String, spec: KeyGenParameterSpec): KeyStore.PrivateKeyEntry {
        KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, KEYSTORE).apply {
            initialize(spec)
            generateKeyPair()
        }
        return loadIdentity(alias)
            ?: throw LanIdentityException(LanIdentityFailure.IDENTITY_INVALID)
    }

    override fun deleteIdentity(alias: String) {
        val keyStore = keyStore()
        if (keyStore.containsAlias(alias)) keyStore.deleteEntry(alias)
    }

    override fun keyStoreForTls(): KeyStore = keyStore()

    private fun keyStore(): KeyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }
}

/**
 * A deliberately local seam for provider failure mapping tests. This is not a
 * trust authority: the public store always binds this operation to AndroidKeyStore.
 */
internal class LanIdentityOperations(
    private val provider: LanIdentityKeyStoreProvider,
) {
    private val lock = Any()

    fun loadOrCreate(): LanIdentity = synchronized(lock) {
        val existing = try {
            provider.loadIdentity(LanIdentityStore.ALIAS)
        } catch (error: CancellationException) {
            throw error
        } catch (error: LanIdentityException) {
            throw error
        } catch (_: Exception) {
            throw LanIdentityException(LanIdentityFailure.LOAD_FAILED)
        }
        if (existing != null) return@synchronized identityFrom(existing)

        try {
            identityFrom(provider.generateIdentity(LanIdentityStore.ALIAS, keyGenSpec()))
        } catch (error: CancellationException) {
            throw error
        } catch (error: LanIdentityException) {
            throw error
        } catch (_: Exception) {
            throw LanIdentityException(LanIdentityFailure.GENERATE_FAILED)
        }
    }

    fun delete() = synchronized(lock) {
        try {
            provider.deleteIdentity(LanIdentityStore.ALIAS)
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            throw LanIdentityException(LanIdentityFailure.DELETE_FAILED)
        }
    }

    fun keyStoreForTls(): KeyStore = try {
        provider.keyStoreForTls()
    } catch (error: CancellationException) {
        throw error
    } catch (_: Exception) {
        throw LanIdentityException(LanIdentityFailure.LOAD_FAILED)
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

    private fun keyGenSpec(): KeyGenParameterSpec {
        val now = System.currentTimeMillis()
        return KeyGenParameterSpec.Builder(
            LanIdentityStore.ALIAS,
            KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY,
        )
            .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
            .setDigests(KeyProperties.DIGEST_SHA256, KeyProperties.DIGEST_NONE)
            .setCertificateSubject(X500Principal("CN=Twinotify LAN"))
            .setCertificateSerialNumber(BigInteger.ONE)
            .setCertificateNotBefore(Date(now - CLOCK_SKEW_MILLIS))
            .setCertificateNotAfter(Date(now + CERTIFICATE_LIFETIME_MILLIS))
            .setUserAuthenticationRequired(false)
            .build()
    }

    private companion object {
        const val CERTIFICATE_LIFETIME_MILLIS = 20L * 365 * 24 * 60 * 60 * 1_000
        const val CLOCK_SKEW_MILLIS = 24L * 60 * 60 * 1_000
    }
}

class LanIdentity internal constructor(
    private val privateKey: PrivateKey,
    certificateChain: Array<X509Certificate>,
    private val signatureFactory: () -> Signature = { Signature.getInstance("SHA256withECDSA") },
) {
    private val chain = certificateChain.copyOf()
    private val pin = MessageDigest.getInstance("SHA-256").digest(chain.first().publicKey.encoded)

    val certificateChain: Array<X509Certificate>
        get() = chain.copyOf()

    val spkiSha256: ByteArray
        get() = pin.copyOf()

    fun sign(data: ByteArray): ByteArray = try {
        signatureFactory().run {
            initSign(privateKey)
            update(data)
            sign()
        }
    } catch (error: CancellationException) {
        throw error
    } catch (_: Exception) {
        throw LanIdentityException(LanIdentityFailure.SIGN_FAILED)
    }

    fun verify(data: ByteArray, signature: ByteArray): Boolean = try {
        signatureFactory().run {
            initVerify(chain.first().publicKey)
            update(data)
            verify(signature)
        }
    } catch (error: CancellationException) {
        throw error
    } catch (_: Exception) {
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
