package dev.ccpocket.daemon.relay

import dev.ccpocket.daemon.util.logger
import java.io.File
import java.security.KeyStore
import java.security.cert.CertificateException
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/**
 * Builds the [X509TrustManager] the relay client hands to Ktor CIO's TLS.
 *
 * DEFAULT BEHAVIOR IS UNCHANGED: with no extra sources configured this returns the JVM's own default
 * trust manager (the bundled JRE `cacerts`, which already carries the Let's Encrypt / ISRG roots), so
 * `pocket.ark-nexus.cc` validates out of the box exactly as before.
 *
 * This helper only ever ADDS trust anchors. It NEVER:
 *  - accepts arbitrary / self-signed certificates (no trust-all),
 *  - disables or relaxes chain / validity / signature checking,
 *  - touches hostname verification — Ktor CIO verifies the server hostname against the request host
 *    itself (`verifyHostnameInCertificate`), independently of this trust manager; we never set/clear
 *    `serverName`, so that check stays fully intact.
 *
 * The composite validates the presented chain against EACH configured source independently and accepts
 * only if AT LEAST ONE source builds a valid, in-date, correctly-signed path to a trusted root — the
 * same guarantee any single [X509TrustManager] gives, just over a larger, user-authorized anchor set.
 *
 * Extra anchors (issue #124 — Linux daemon behind a corporate TLS-intercepting proxy, or where a
 * locally-installed org root lives in the OS store the bundled JRE can't see, e.g. under WSL):
 *  - [ENV_CACERTS] (`CC_POCKET_CACERTS`): a user-supplied trust store the daemon should also trust —
 *    either a PEM bundle (one or more `-----BEGIN CERTIFICATE-----` blocks) or a Java keystore
 *    (PKCS12/JKS, password via [ENV_CACERTS_PASSWORD], default `changeit`).
 *  - Linux system CA bundle (`/etc/ssl/certs/ca-certificates.crt` and friends): merged automatically
 *    so a root added with `update-ca-certificates` (the standard corporate-proxy install path) is
 *    honored without any daemon config.
 *
 * A missing / unreadable / malformed extra source is logged and skipped — it degrades to the default
 * trust manager, never crashes and never silently drops the baseline anchors.
 */
internal object RelayTrust {
    private val log = logger("RelayTrust")

    const val ENV_CACERTS = "CC_POCKET_CACERTS"
    const val ENV_CACERTS_PASSWORD = "CC_POCKET_CACERTS_PASSWORD"

    /** Common OS trust-store bundles (PEM), across distro families. First readable one is merged. */
    private val SYSTEM_CA_BUNDLES = listOf(
        "/etc/ssl/certs/ca-certificates.crt",                     // Debian / Ubuntu / Alpine / Arch
        "/etc/pki/tls/certs/ca-bundle.crt",                       // Fedora / RHEL / CentOS
        "/etc/pki/ca-trust/extracted/pem/tls-ca-bundle.pem",      // RHEL (ca-trust extracted)
        "/etc/ssl/ca-bundle.pem",                                 // openSUSE
        "/etc/ssl/cert.pem",                                      // Alpine / BSD / macOS
    )

    /**
     * Assemble the trust manager. Sources are, in order: JVM default (always), the [ENV_CACERTS]
     * store if set, and the first readable Linux system CA bundle. When nothing extra is configured
     * the single default manager is returned as-is (identical to prior behavior).
     *
     * [env] and [systemBundlePaths] are injectable for tests; production uses the real environment.
     */
    fun build(
        env: (String) -> String? = System::getenv,
        systemBundlePaths: List<String> = SYSTEM_CA_BUNDLES,
    ): X509TrustManager {
        val sources = ArrayList<X509TrustManager>()

        // 1) JVM default: bundled cacerts (+ any -Djavax.net.ssl.trustStore the user set). Always the baseline.
        defaultTrustManager()?.let { sources += it }
            ?: log.warn("relay trust: JVM default trust manager unavailable")

        // 2) explicit user-supplied trust store
        env(ENV_CACERTS)?.trim()?.takeIf { it.isNotEmpty() }?.let { path ->
            runCatching { trustManagerFromFile(File(path), env(ENV_CACERTS_PASSWORD)) }
                .onSuccess { tm ->
                    if (tm != null) {
                        sources += tm
                        log.info("relay trust: loaded ${tm.acceptedIssuers.size} extra anchor(s) from $ENV_CACERTS=$path")
                    } else {
                        log.warn("relay trust: $ENV_CACERTS=$path had no usable certificates; ignoring")
                    }
                }
                .onFailure { log.warn("relay trust: could not load $ENV_CACERTS=$path (${it.message}); ignoring") }
        }

        // 3) Linux system CA bundle (auto). Skipped cleanly where no such file exists (macOS/Windows).
        systemBundlePaths.asSequence().map(::File).firstOrNull { it.isFile && it.canRead() }?.let { f ->
            runCatching { trustManagerFromPem(f) }
                .onSuccess { tm ->
                    if (tm != null) {
                        sources += tm
                        log.info("relay trust: merged system CA bundle ${f.path} (${tm.acceptedIssuers.size} anchor(s))")
                    }
                }
                .onFailure { log.warn("relay trust: could not read system CA bundle ${f.path} (${it.message}); ignoring") }
        }

        require(sources.isNotEmpty()) { "no usable trust anchors (JVM default missing)" }
        return if (sources.size == 1) sources.single() else CompositeTrustManager(sources.toList())
    }

    private fun defaultTrustManager(): X509TrustManager? = trustManagersFrom(null).firstOrNull()

    /** Load a user trust store: try it as a Java keystore first, then fall back to a PEM bundle. */
    private fun trustManagerFromFile(file: File, password: String?): X509TrustManager? {
        require(file.isFile && file.canRead()) { "not a readable file" }
        loadKeyStore(file, password)?.let { ks -> trustManagersFrom(ks).firstOrNull()?.let { return it } }
        return trustManagerFromPem(file)
    }

    private fun loadKeyStore(file: File, password: String?): KeyStore? {
        val pw = (password ?: "changeit").toCharArray()
        for (type in linkedSetOf(KeyStore.getDefaultType(), "PKCS12", "JKS")) {
            val ks = runCatching {
                KeyStore.getInstance(type).also { store -> file.inputStream().use { store.load(it, pw) } }
            }.getOrNull()
            if (ks != null && ks.size() > 0) return ks
        }
        return null
    }

    /** Parse every X.509 certificate in a PEM (or DER) bundle into an anchors-only keystore. */
    private fun trustManagerFromPem(file: File): X509TrustManager? {
        val certs = file.inputStream().buffered().use { input ->
            CertificateFactory.getInstance("X.509").generateCertificates(input)
        }.filterIsInstance<X509Certificate>()
        if (certs.isEmpty()) return null
        val ks = KeyStore.getInstance(KeyStore.getDefaultType()).apply { load(null, null) }
        certs.forEachIndexed { i, c -> ks.setCertificateEntry("ca-$i", c) }
        return trustManagersFrom(ks).firstOrNull()
    }

    private fun trustManagersFrom(ks: KeyStore?): List<X509TrustManager> {
        val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        tmf.init(ks) // null => JVM default trust store (bundled cacerts / -Djavax.net.ssl.trustStore)
        return tmf.trustManagers.filterIsInstance<X509TrustManager>()
    }

    /**
     * Accept a server/client chain if ANY delegate validates it; reject (throw) only when EVERY
     * delegate rejects. This strictly widens the trusted-anchor set — each delegate still runs its
     * full path/validity/signature check, so an invalid or untrusted certificate is never accepted.
     */
    private class CompositeTrustManager(private val delegates: List<X509TrustManager>) : X509TrustManager {
        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) =
            checkAny { it.checkServerTrusted(chain, authType) }

        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) =
            checkAny { it.checkClientTrusted(chain, authType) }

        private inline fun checkAny(check: (X509TrustManager) -> Unit) {
            var last: CertificateException? = null
            for (tm in delegates) {
                try {
                    check(tm)
                    return // one valid, trusted chain is sufficient
                } catch (e: CertificateException) {
                    last = e
                }
            }
            throw last ?: CertificateException("no configured trust source validated the certificate")
        }

        override fun getAcceptedIssuers(): Array<X509Certificate> =
            delegates.flatMap { it.acceptedIssuers.asList() }.toTypedArray()
    }
}
