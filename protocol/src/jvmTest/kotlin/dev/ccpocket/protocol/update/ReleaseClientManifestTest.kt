package dev.ccpocket.protocol.update

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Pins the mirror `latest.json` contract between deploy/mirror-sync.sh (producer) and every
 * client (daemon self-update, desktop updater, install scripts). The parser must yield a
 * [ReleaseClient.Release] indistinguishable from the GitHub-API path — and must return null
 * (never throw, never a partial Release) on anything malformed, so a broken mirror body always
 * degrades to the GitHub fallback.
 */
class ReleaseClientManifestTest {

    @Test
    fun `parses a mirror manifest into the same Release shape as the GitHub path`() {
        val body = """
            {"version":"1.6.2","assets":{
              "cc-pocket-daemon-1.6.2-macos-arm64.tar.gz":"https://pocket.ark-nexus.cc/dl/v1.6.2/cc-pocket-daemon-1.6.2-macos-arm64.tar.gz",
              "SHA256SUMS":"https://pocket.ark-nexus.cc/dl/v1.6.2/SHA256SUMS",
              "cc-pocket-desktop-macos-arm64.dmg":"https://github.com/heypandax/cc-pocket/releases/download/v1.6.2/cc-pocket-desktop-macos-arm64.dmg"
            }}
        """.trimIndent()
        val release = ReleaseClient.parseManifest(body)!!
        assertEquals("1.6.2", release.version)
        assertEquals(
            "https://pocket.ark-nexus.cc/dl/v1.6.2/cc-pocket-daemon-1.6.2-macos-arm64.tar.gz",
            release.assetUrls["cc-pocket-daemon-1.6.2-macos-arm64.tar.gz"],
        )
        // non-mirrored assets keep their GitHub URL — the desktop updater relies on the map being complete
        assertEquals(
            "https://github.com/heypandax/cc-pocket/releases/download/v1.6.2/cc-pocket-desktop-macos-arm64.dmg",
            release.assetUrls["cc-pocket-desktop-macos-arm64.dmg"],
        )
        assertEquals("https://pocket.ark-nexus.cc/dl/v1.6.2/SHA256SUMS", release.assetUrls["SHA256SUMS"])
    }

    @Test
    fun `tolerates a v-prefixed version`() {
        val release = ReleaseClient.parseManifest("""{"version":"v1.6.2","assets":{"a":"https://x/a"}}""")
        assertEquals("1.6.2", release?.version)
    }

    @Test
    fun `rejects malformed bodies instead of throwing`() {
        assertNull(ReleaseClient.parseManifest(""))
        assertNull(ReleaseClient.parseManifest("<html>404</html>"))
        assertNull(ReleaseClient.parseManifest("{}"))
        assertNull(ReleaseClient.parseManifest("""{"version":"1.6.2"}"""))
        assertNull(ReleaseClient.parseManifest("""{"version":"","assets":{"a":"https://x/a"}}"""))
        assertNull(ReleaseClient.parseManifest("""{"version":"1.6.2","assets":{}}"""))
        // an asset whose URL isn't a string is dropped; if that empties the map the manifest is unusable
        assertNull(ReleaseClient.parseManifest("""{"version":"1.6.2","assets":{"a":{"nested":true}}}"""))
    }
}
