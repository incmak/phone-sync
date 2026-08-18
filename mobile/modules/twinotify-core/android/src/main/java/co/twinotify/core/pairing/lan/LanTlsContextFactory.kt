package co.twinotify.core.pairing.lan

import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager
import kotlinx.coroutines.CancellationException

/** TLS contexts scoped to the LAN pairing pin trust boundary. */
object LanTlsContextFactory {
    private const val SHA256_PIN_LENGTH = 32
    private val serverOperations = LanTlsContextOperations(
        identityLoader = { LanIdentityStore.loadOrCreate() },
        keyStoreLoader = { LanIdentityStore.keyStoreForTls() },
        keyManagerFactoryLoader = { KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()) },
        sslContextLoader = { SSLContext.getInstance("TLS") },
    )

    /**
     * Uses the installation's Android Keystore TLS identity. There is no
     * caller-supplied key material and no export of the private key.
     */
    fun serverContext(): SSLContext = serverOperations.serverContext()

    /**
     * Client-only context for a pin that was authenticated in the pairing
     * transcript. It does not alter any process-wide hostname or trust policy.
     */
    fun clientContext(expectedSpkiSha256: ByteArray): SSLContext = try {
        SSLContext.getInstance("TLS").apply {
            init(null, arrayOf<TrustManager>(pinningTrustManager(expectedSpkiSha256)), null)
        }
    } catch (error: LanIdentityException) {
        throw error
    } catch (error: CancellationException) {
        throw error
    } catch (_: Exception) {
        throw LanIdentityException(LanIdentityFailure.TLS_CONTEXT_FAILED)
    }

    fun pinningTrustManager(expectedSpkiSha256: ByteArray): X509TrustManager {
        if (expectedSpkiSha256.size != SHA256_PIN_LENGTH) {
            throw LanIdentityException(LanIdentityFailure.INVALID_PIN)
        }
        return SpkiPinningTrustManager(expectedSpkiSha256.copyOf())
    }
}

/**
 * Instance-local provider seam for failure mapping tests. Production constructs
 * this once with AndroidKeyStore and the platform TLS providers; public callers
 * cannot replace its key or trust source.
 */
internal class LanTlsContextOperations(
    private val identityLoader: () -> Unit,
    private val keyStoreLoader: () -> java.security.KeyStore,
    private val keyManagerFactoryLoader: () -> KeyManagerFactory,
    private val sslContextLoader: () -> SSLContext,
) {
    fun serverContext(): SSLContext {
        identityLoader()
        return try {
            val keyManagerFactory = keyManagerFactoryLoader()
            keyManagerFactory.init(keyStoreLoader(), null)
            sslContextLoader().apply {
                init(keyManagerFactory.keyManagers, null, null)
            }
        } catch (error: LanIdentityException) {
            throw error
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            throw LanIdentityException(LanIdentityFailure.TLS_CONTEXT_FAILED)
        }
    }
}

@Suppress("CustomX509TrustManager")
private class SpkiPinningTrustManager(
    private val expectedPin: ByteArray,
) : X509TrustManager {
    override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {
        throw CertificateException("lan_tls_client_auth_not_supported")
    }

    override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
        val certificate = chain.firstOrNull()
            ?: throw CertificateException("lan_tls_pin_mismatch")
        val actualPin = MessageDigest.getInstance("SHA-256").digest(certificate.publicKey.encoded)
        if (!MessageDigest.isEqual(expectedPin, actualPin)) {
            throw CertificateException("lan_tls_pin_mismatch")
        }
    }

    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
}
