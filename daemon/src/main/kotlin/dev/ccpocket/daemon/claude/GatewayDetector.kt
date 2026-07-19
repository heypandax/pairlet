package dev.ccpocket.daemon.claude

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.net.URI
import java.nio.file.Path

/**
 * Best-effort detect of a third-party `ANTHROPIC_BASE_URL` — a gateway / API-relay user (cc-switch,
 * DeepSeek/GLM/Kimi Anthropic-compatible endpoints, corporate proxies). Advertised to clients via
 * [dev.ccpocket.protocol.DaemonInfo.gatewayBaseUrl] so their model picker surfaces the gateway model
 * presets first instead of the built-in Claude aliases (issue #139).
 *
 * Sources, highest first (mirrors what an actual session launch would see):
 *  1. the ACTIVE preset's base URL — the launch scrubs ambient env and injects it (issue #113);
 *  2. the daemon process env `$ANTHROPIC_BASE_URL` — inherited by every child;
 *  3. the user `settings.json` `env.ANTHROPIC_BASE_URL` (under [userConfigDir] when credential
 *     isolation is on, else `$CLAUDE_CONFIG_DIR` / `~/.claude`) — the CLI applies it at startup.
 * Project-level settings are deliberately NOT read: this signal is per-daemon (one DaemonInfo per
 * handshake), not per-workdir.
 *
 * The first CONFIGURED value decides; it is then reported only when it points OFF `api.anthropic.com`
 * (an explicit official URL is not a gateway). Reads config files only, never launches anything, and
 * never throws — any unreadable file / malformed value degrades to null (same contract as
 * [ClaudeDefaultModel], which crash-looped daemons before it made that promise).
 */
object GatewayDetector {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    fun resolve(
        presetBaseUrl: String? = null,
        env: (String) -> String? = System::getenv,
        userConfigDir: Path? = null,
        home: File = File(System.getProperty("user.home")),
    ): String? {
        val userRoot = userConfigDir?.toFile()
            ?: env("CLAUDE_CONFIG_DIR")?.takeIf { it.isNotBlank() }?.let(::File)
            ?: File(home, ".claude")
        val candidates: List<() -> String?> = listOf(
            { presetBaseUrl?.trim()?.takeIf { it.isNotEmpty() } },
            { env("ANTHROPIC_BASE_URL")?.trim()?.takeIf { it.isNotEmpty() } },
            { baseUrlFromSettings(File(userRoot, "settings.json")) },
        )
        val configured = candidates.firstNotNullOfOrNull { runCatching(it).getOrNull() } ?: return null
        return configured.takeUnless { isOfficial(it) }
    }

    /** True when [url]'s host is Anthropic's own API — configured, but not a gateway. An unparseable
     *  value stays reported as-is: the CLI would still hand it to its HTTP stack, so the user IS off
     *  the official endpoint. */
    internal fun isOfficial(url: String): Boolean {
        val host = runCatching { URI(url).host }.getOrNull()?.lowercase() ?: return false
        return host == "api.anthropic.com" || host == "anthropic.com" || host.endsWith(".anthropic.com")
    }

    /** `env.ANTHROPIC_BASE_URL` from one settings file; null when absent / unreadable / blank. */
    private fun baseUrlFromSettings(file: File): String? = envFromSettings(file, "ANTHROPIC_BASE_URL")

    /** One `env.<key>` out of a settings file; null when absent / unreadable / blank. Same
     *  never-throws contract as the rest of this object. */
    internal fun envFromSettings(file: File, key: String): String? {
        if (!file.isFile) return null
        val obj = runCatching { json.parseToJsonElement(file.readText()).jsonObject }.getOrNull() ?: return null
        return runCatching {
            (obj["env"] as? JsonObject)?.get(key)?.jsonPrimitive?.contentOrNull
        }.getOrNull()?.trim()?.takeIf { it.isNotEmpty() }
    }

    /** A gateway and the credential that BELONGS to it, both read out of the SAME configuration layer.
     *  [tokenVar] records which spelling it came from, so only the matching header is ever sent. */
    data class Paired(val baseUrl: String, val token: String, val tokenVar: String)

    /**
     * The gateway to ask for a model list, together with the credential that layer pairs it with
     * (issue #167 ②).
     *
     * WHY PAIRED, NOT TWO LOOKUPS: resolving the URL and the token independently silently mixes layers.
     * A `settings.json` pointing at a third-party gateway plus a leftover `ANTHROPIC_API_KEY` in the
     * daemon's own env — a very ordinary setup — would otherwise send the user's OFFICIAL Anthropic key
     * to that third party. This repo already names that hazard: [dev.ccpocket.protocol.PresetEnv.SCRUBBED]
     * exists precisely so a preset launch cannot leak an ambient credential to its own base URL, and
     * `ClaudeLauncher.applyPresetEnv` wipes those variables for exactly this reason. Reading them back
     * out through a second door would undo that.
     *
     * So: the FIRST layer that supplies a base URL also has to supply the token. If it doesn't, this
     * returns null and no probe happens — losing a model list is strictly better than mispairing a
     * secret with a destination.
     *
     * Layer order mirrors [resolve] exactly, so the host the client shows and the host we contact can
     * never diverge: active preset → daemon env → user `settings.json`.
     */
    fun resolvePaired(
        presetEnv: Map<String, String>? = null,
        env: (String) -> String? = System::getenv,
        userConfigDir: Path? = null,
        home: File = File(System.getProperty("user.home")),
    ): Paired? {
        val userRoot = userConfigDir?.toFile()
            ?: env("CLAUDE_CONFIG_DIR")?.takeIf { it.isNotBlank() }?.let(::File)
            ?: File(home, ".claude")
        val settings = File(userRoot, "settings.json")

        fun clean(s: String?) = s?.trim()?.takeIf { it.isNotEmpty() }
        // Each entry reads BOTH values from one layer only — that pairing is the whole point.
        val layers: List<Pair<() -> String?, (String) -> String?>> = listOf(
            { clean(presetEnv?.get("ANTHROPIC_BASE_URL")) } to { k: String -> clean(presetEnv?.get(k)) },
            { clean(env("ANTHROPIC_BASE_URL")) } to { k: String -> clean(env(k)) },
            { envFromSettings(settings, "ANTHROPIC_BASE_URL") } to { k: String -> envFromSettings(settings, k) },
        )

        for ((urlOf, tokenOf) in layers) {
            val url = runCatching(urlOf).getOrNull() ?: continue
            if (isOfficial(url)) return null // official endpoint: nothing to ask, and no reason to send a key
            // AUTH_TOKEN first: it is the default `tokenVar` and the spelling relays actually use.
            for (v in listOf("ANTHROPIC_AUTH_TOKEN", "ANTHROPIC_API_KEY")) {
                val tok = runCatching { tokenOf(v) }.getOrNull()
                if (tok != null) return Paired(url, tok, v)
            }
            return null // this layer owns the URL but has no credential — do NOT borrow one from below
        }
        return null
    }
}
