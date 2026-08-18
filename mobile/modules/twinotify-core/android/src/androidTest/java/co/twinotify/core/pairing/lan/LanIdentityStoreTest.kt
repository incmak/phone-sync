package co.twinotify.core.pairing.lan

import android.security.keystore.KeyInfo
import android.security.keystore.KeyProperties
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.security.KeyFactory
import java.security.KeyStore
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LanIdentityStoreTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @After
    fun removeLanIdentity() {
        LanIdentityStore.delete()
    }

    @Test
    fun firstLoadGeneratesP256SigningIdentityWithValidSelfSignedCertificate() {
        LanIdentityStore.delete()

        val identity = LanIdentityStore.loadOrCreate()
        val entry = androidKeyStore().getEntry(LanIdentityStore.ALIAS, null) as KeyStore.PrivateKeyEntry
        val certificate = entry.certificate as X509Certificate
        val keyInfo = KeyFactory.getInstance(entry.privateKey.algorithm, "AndroidKeyStore")
            .getKeySpec(entry.privateKey, KeyInfo::class.java)

        assertEquals("EC", entry.privateKey.algorithm)
        assertEquals("EC", entry.certificate.publicKey.algorithm)
        assertTrue(keyInfo.purposes and KeyProperties.PURPOSE_SIGN != 0)
        assertTrue(keyInfo.purposes and KeyProperties.PURPOSE_VERIFY != 0)
        assertTrue(
            keyInfo.digests.contains(KeyProperties.DIGEST_SHA256),
            "TLS identity must permit SHA-256 signatures",
        )
        assertTrue(certificate.notBefore.before(java.util.Date()))
        assertTrue(certificate.notAfter.after(java.util.Date()))
        assertEquals(certificate.subjectX500Principal, certificate.issuerX500Principal)
        assertContentEquals(certificate.publicKey.encoded, identity.certificateChain.single().publicKey.encoded)
    }

    @Test
    fun repeatedLoadKeepsSpkiAndPrivateKeyNonExportable() {
        LanIdentityStore.delete()
        val first = LanIdentityStore.loadOrCreate()
        val second = LanIdentityStore.loadOrCreate()
        val privateKey = (androidKeyStore().getEntry(LanIdentityStore.ALIAS, null) as KeyStore.PrivateKeyEntry).privateKey

        assertContentEquals(first.spkiSha256, second.spkiSha256)
        assertNull(privateKey.encoded, "Android Keystore private material must be non-exportable")

        val signed = first.sign("lan identity".toByteArray())
        assertTrue(second.verify("lan identity".toByteArray(), signed))
    }

    @Test
    fun spkiDigestIsStableAndExactly32Bytes() {
        LanIdentityStore.delete()

        val first = LanIdentityStore.loadOrCreate().spkiSha256
        val second = LanIdentityStore.loadOrCreate().spkiSha256

        assertEquals(32, first.size)
        assertContentEquals(first, second)
    }

    @Test
    fun serverContextInitializesFromAndroidKeyStoreKeyManager() {
        LanIdentityStore.delete()
        LanIdentityStore.loadOrCreate()

        val context = LanTlsContextFactory.serverContext()

        assertNotNull(context.socketFactory)
    }

    @Test
    fun pinTrustManagerAcceptsOnlyExactSpkiDigest() {
        LanIdentityStore.delete()
        val identity = LanIdentityStore.loadOrCreate()
        val chain = identity.certificateChain
        val accepting = LanTlsContextFactory.pinningTrustManager(identity.spkiSha256)
        val wrongPin = identity.spkiSha256.copyOf().also { it[0] = (it[0].toInt() xor 1).toByte() }
        val rejecting = LanTlsContextFactory.pinningTrustManager(wrongPin)

        accepting.checkServerTrusted(chain, "EC")
        assertFailsWith<CertificateException> { rejecting.checkServerTrusted(chain, "EC") }
    }

    @Test
    fun deleteRemovesAliasAndSubsequentLoadGetsNewPin() {
        LanIdentityStore.delete()
        val original = LanIdentityStore.loadOrCreate().spkiSha256

        LanIdentityStore.delete()
        assertTrue(!androidKeyStore().containsAlias(LanIdentityStore.ALIAS))
        val regenerated = LanIdentityStore.loadOrCreate().spkiSha256

        assertTrue(!original.contentEquals(regenerated))
    }

    @Test
    fun failuresExposeOnlyBoundedCodes() {
        val error = assertFailsWith<LanIdentityException> {
            LanTlsContextFactory.pinningTrustManager(ByteArray(31))
        }

        assertEquals(LanIdentityFailure.INVALID_PIN, error.failure)
        assertTrue(error.message.orEmpty().matches(Regex("[a-z_]+")))
        assertTrue(!error.toString().contains(LanIdentityStore.ALIAS))
        assertTrue(!error.toString().contains("BEGIN CERTIFICATE"))
    }

    private fun androidKeyStore(): KeyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
}
