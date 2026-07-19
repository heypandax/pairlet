package dev.ccpocket.daemon.claude

import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Issue #167 ②, the security half: a gateway credential must never be paired with a destination from a
 * DIFFERENT configuration layer.
 *
 * The first draft resolved the base URL and the token independently, each with its own precedence list.
 * An adversarial review found that on an ordinary setup — `settings.json` naming a third-party gateway
 * (the shape every cc-switch / DeepSeek guide prints) plus a leftover `ANTHROPIC_API_KEY` in the
 * daemon's own environment (a `.zshrc` export) — the URL came from settings and the token came from
 * env. The daemon would have sent the user's OFFICIAL Anthropic key to a third party, which is exactly
 * the cross-endpoint leak `PresetEnv.SCRUBBED` and `ClaudeLauncher.applyPresetEnv` exist to prevent.
 *
 * [officialKeyInEnvNeverReachesGatewayFromSettings] is that case. It must stay red-on-regression.
 */
class GatewayCredentialPairingTest {

    private fun home(settingsJson: String? = null): File {
        val home = createTempDirectory("ccp-pair").toFile()
        if (settingsJson != null) {
            File(home, ".claude").apply { mkdirs() }
                .let { File(it, "settings.json").writeText(settingsJson) }
        }
        return home
    }

    private fun noEnv(): (String) -> String? = { null }

    // ── the leak the review found ───────────────────────────────────────────

    /**
     * THE regression guard. Gateway named in settings, official key sitting in the process env:
     * the two must not be combined. Nothing is probed at all, because the layer that owns the URL
     * has no credential of its own.
     */
    @Test
    fun officialKeyInEnvNeverReachesGatewayFromSettings() {
        val h = home("""{"env":{"ANTHROPIC_BASE_URL":"https://gw.thirdparty.com"}}""")
        val env = { k: String -> if (k == "ANTHROPIC_API_KEY") "sk-ant-api03-OFFICIAL" else null }
        assertNull(
            GatewayDetector.resolvePaired(env = env, home = h),
            "a settings-layer gateway must not borrow the env-layer official key",
        )
    }

    /** Same shape, other spelling — an ambient AUTH_TOKEN is equally not the settings gateway's. */
    @Test
    fun ambientAuthTokenIsAlsoNotBorrowed() {
        val h = home("""{"env":{"ANTHROPIC_BASE_URL":"https://gw.thirdparty.com"}}""")
        val env = { k: String -> if (k == "ANTHROPIC_AUTH_TOKEN") "ambient-token" else null }
        assertNull(GatewayDetector.resolvePaired(env = env, home = h))
    }

    // ── correct pairings, layer by layer ────────────────────────────────────

    /** Settings owns both → that pair is used, and the spelling is remembered for the header choice. */
    @Test
    fun settingsLayerPairsWithItsOwnToken() {
        val h = home("""{"env":{"ANTHROPIC_BASE_URL":"https://gw.example","ANTHROPIC_AUTH_TOKEN":"gw-tok"}}""")
        val got = GatewayDetector.resolvePaired(env = noEnv(), home = h)
        assertEquals("https://gw.example", got?.baseUrl)
        assertEquals("gw-tok", got?.token)
        assertEquals("ANTHROPIC_AUTH_TOKEN", got?.tokenVar)
    }

    /** Env owns both → used, and settings is never consulted for the token. */
    @Test
    fun envLayerPairsWithItsOwnToken() {
        val h = home("""{"env":{"ANTHROPIC_BASE_URL":"https://other.example","ANTHROPIC_AUTH_TOKEN":"settings-tok"}}""")
        val env = { k: String ->
            when (k) {
                "ANTHROPIC_BASE_URL" -> "https://gw.env"
                "ANTHROPIC_API_KEY" -> "env-key"
                else -> null
            }
        }
        val got = GatewayDetector.resolvePaired(env = env, home = h)
        assertEquals("https://gw.env", got?.baseUrl, "env layer outranks settings for the URL")
        assertEquals("env-key", got?.token, "and supplies its OWN token, not the settings one")
        assertEquals("ANTHROPIC_API_KEY", got?.tokenVar)
    }

    /**
     * An ACTIVE preset outranks everything — that is what the CLI actually launches with, and what the
     * client's gateway pill displays. Probing the stale settings gateway instead would send a live
     * credential to an endpoint the user already switched away from.
     */
    @Test
    fun activePresetOutranksStaleSettings() {
        val h = home("""{"env":{"ANTHROPIC_BASE_URL":"https://old.example","ANTHROPIC_AUTH_TOKEN":"old-tok"}}""")
        val preset = mapOf("ANTHROPIC_BASE_URL" to "https://new.example", "ANTHROPIC_AUTH_TOKEN" to "new-tok")
        val got = GatewayDetector.resolvePaired(presetEnv = preset, env = noEnv(), home = h)
        assertEquals("https://new.example", got?.baseUrl)
        assertEquals("new-tok", got?.token)
    }

    /** AUTH_TOKEN wins inside one layer (it is the default `tokenVar`). */
    @Test
    fun authTokenPreferredWithinALayer() {
        val preset = mapOf(
            "ANTHROPIC_BASE_URL" to "https://gw.example",
            "ANTHROPIC_AUTH_TOKEN" to "auth",
            "ANTHROPIC_API_KEY" to "key",
        )
        assertEquals("auth", GatewayDetector.resolvePaired(presetEnv = preset, env = noEnv(), home = home())?.token)
    }

    // ── nothing to do ───────────────────────────────────────────────────────

    /** Official endpoint: no probe, no credential, regardless of which layer names it. */
    @Test
    fun officialEndpointYieldsNothing() {
        val preset = mapOf(
            "ANTHROPIC_BASE_URL" to "https://api.anthropic.com",
            "ANTHROPIC_AUTH_TOKEN" to "sk-ant-OFFICIAL",
        )
        assertNull(GatewayDetector.resolvePaired(presetEnv = preset, env = noEnv(), home = home()))
    }

    /** No gateway configured anywhere. */
    @Test
    fun unconfiguredYieldsNothing() {
        assertNull(GatewayDetector.resolvePaired(env = noEnv(), home = home()))
    }
}
