package dev.ccpocket.daemon.relay

import io.ktor.network.tls.certificates.buildKeyStore
import io.ktor.network.tls.certificates.saveToFile
import java.io.File
import java.security.KeyStore
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.Base64
import javax.security.auth.x500.X500Principal
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Trust-source extension for the relay TLS link (issue #124). The through-line of every case: extra
 * sources only ever WIDEN the trusted-anchor set — the default JRE anchors are always retained and an
 * untrusted / invalid certificate is never accepted.
 *
 * We model a server whose certificate chains to a locally-trusted root as a self-signed certificate the
 * user adds to their trust store (the "self-signed server cert in the truststore" case, which is exactly
 * how a corporate MITM proxy root or a pinned custom CA is trusted). checkServerTrusted succeeds only
 * when that exact anchor is present; without it, or for an unrelated cert, it throws.
 */
class RelayTrustTest {
    private val tmp = mutableListOf<File>()

    @AfterTest fun cleanup() = tmp.forEach { it.delete() }

    private fun tmpFile(suffix: String): File =
        File.createTempFile("relaytrust", suffix).also { tmp += it }

    // --- test certs: distinct self-signed identities --------------------------------------------

    private fun selfSigned(cn: String): Pair<KeyStore, X509Certificate> {
        val ks = buildKeyStore {
            certificate("c") {
                password = "changeit"
                subject = X500Principal("CN=$cn")
                domains = listOf(cn)
                keySizeInBits = 2048
            }
        }
        return ks to (ks.getCertificate("c") as X509Certificate)
    }

    private val serverStore: KeyStore
    private val serverCert: X509Certificate
    private val serverChain: Array<X509Certificate>
    private val rogueChain: Array<X509Certificate>

    init {
        val (ss, sc) = selfSigned("pocket.example.test")
        serverStore = ss; serverCert = sc; serverChain = arrayOf(sc)
        rogueChain = arrayOf(selfSigned("evil.test").second)
    }

    private fun writePem(certs: List<X509Certificate>): File {
        val f = tmpFile(".pem")
        val enc = Base64.getMimeEncoder(64, "\n".toByteArray())
        f.printWriter().use { w ->
            certs.forEach { c ->
                w.println("-----BEGIN CERTIFICATE-----")
                w.println(enc.encodeToString(c.encoded))
                w.println("-----END CERTIFICATE-----")
            }
        }
        return f
    }

    /** A truststore-only PKCS12 (cert entry, no private key) holding the server anchor. */
    private fun writePkcs12(password: String): File {
        val f = tmpFile(".p12")
        val ts = KeyStore.getInstance("PKCS12").apply { load(null, null) }
        ts.setCertificateEntry("server", serverCert)
        ts.saveToFile(f, password)
        return f
    }

    private val noSystemBundles = emptyList<String>()
    private fun noEnv(@Suppress("UNUSED_PARAMETER") k: String): String? = null
    private fun hasLetsEncrypt(tm: javax.net.ssl.X509TrustManager) =
        tm.acceptedIssuers.any { it.subjectX500Principal.name.contains("ISRG Root X") }

    // --- default behavior (unchanged) -----------------------------------------------------------

    @Test fun `default build retains the JRE anchors including Lets Encrypt`() {
        val tm = RelayTrust.build(env = ::noEnv, systemBundlePaths = noSystemBundles)
        assertTrue(tm.acceptedIssuers.isNotEmpty(), "default anchors must be present")
        assertTrue(hasLetsEncrypt(tm), "bundled cacerts must still trust the Let's Encrypt roots")
    }

    @Test fun `default trust manager rejects unknown self-signed certs (no trust-all)`() {
        val tm = RelayTrust.build(env = ::noEnv, systemBundlePaths = noSystemBundles)
        assertFailsWith<CertificateException> { tm.checkServerTrusted(serverChain, "RSA") }
        assertFailsWith<CertificateException> { tm.checkServerTrusted(rogueChain, "RSA") }
    }

    // --- env-supplied PEM bundle ----------------------------------------------------------------

    @Test fun `CC_POCKET_CACERTS PEM adds the anchor and validates the server cert`() {
        val pem = writePem(listOf(serverCert))
        val env = { k: String -> if (k == RelayTrust.ENV_CACERTS) pem.path else null }
        val tm = RelayTrust.build(env = env, systemBundlePaths = noSystemBundles)

        tm.checkServerTrusted(serverChain, "RSA") // the added anchor now validates it …
        // … and the default anchors are still present (union), including the added anchor itself
        assertContains(tm.acceptedIssuers.map { it.subjectX500Principal }, serverCert.subjectX500Principal)
        assertTrue(hasLetsEncrypt(tm), "adding a source must not drop the default anchors")
    }

    @Test fun `composite still rejects a cert no source trusts`() {
        val pem = writePem(listOf(serverCert))
        val env = { k: String -> if (k == RelayTrust.ENV_CACERTS) pem.path else null }
        val tm = RelayTrust.build(env = env, systemBundlePaths = noSystemBundles)
        assertFailsWith<CertificateException> { tm.checkServerTrusted(rogueChain, "RSA") }
    }

    // --- env-supplied Java keystore (PKCS12) ----------------------------------------------------

    @Test fun `CC_POCKET_CACERTS PKCS12 keystore is loaded with its password`() {
        val p12 = writePkcs12("s3cret")
        val env = { k: String ->
            when (k) {
                RelayTrust.ENV_CACERTS -> p12.path
                RelayTrust.ENV_CACERTS_PASSWORD -> "s3cret"
                else -> null
            }
        }
        val tm = RelayTrust.build(env = env, systemBundlePaths = noSystemBundles)
        tm.checkServerTrusted(serverChain, "RSA")
        assertFailsWith<CertificateException> { tm.checkServerTrusted(rogueChain, "RSA") }
    }

    // --- Linux system CA bundle merge -----------------------------------------------------------

    @Test fun `system CA bundle is merged when present, first readable path wins`() {
        val bundle = writePem(listOf(serverCert))
        val tm = RelayTrust.build(env = ::noEnv, systemBundlePaths = listOf("/no/such/path", bundle.path))
        tm.checkServerTrusted(serverChain, "RSA")
        assertContains(tm.acceptedIssuers.map { it.subjectX500Principal }, serverCert.subjectX500Principal)
        assertTrue(hasLetsEncrypt(tm))
    }

    // --- graceful degradation -------------------------------------------------------------------

    @Test fun `missing CC_POCKET_CACERTS path degrades to default without crashing`() {
        val env = { k: String -> if (k == RelayTrust.ENV_CACERTS) "/definitely/not/here.pem" else null }
        val tm = RelayTrust.build(env = env, systemBundlePaths = noSystemBundles)
        assertTrue(hasLetsEncrypt(tm), "default anchors intact")
        assertFailsWith<CertificateException> { tm.checkServerTrusted(serverChain, "RSA") }
    }

    @Test fun `malformed CC_POCKET_CACERTS file degrades to default without crashing`() {
        val garbage = tmpFile(".pem").apply { writeText("this is not a certificate\n") }
        val env = { k: String -> if (k == RelayTrust.ENV_CACERTS) garbage.path else null }
        val tm = RelayTrust.build(env = env, systemBundlePaths = noSystemBundles)
        assertTrue(hasLetsEncrypt(tm), "default anchors intact")
        // the garbage file added nothing → the server cert is still untrusted
        assertFailsWith<CertificateException> { tm.checkServerTrusted(serverChain, "RSA") }
    }
}
