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
    private fun baseUrlFromSettings(file: File): String? {
        if (!file.isFile) return null
        val obj = runCatching { json.parseToJsonElement(file.readText()).jsonObject }.getOrNull() ?: return null
        return runCatching {
            (obj["env"] as? JsonObject)?.get("ANTHROPIC_BASE_URL")?.jsonPrimitive?.contentOrNull
        }.getOrNull()?.trim()?.takeIf { it.isNotEmpty() }
    }
}
