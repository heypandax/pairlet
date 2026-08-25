package dev.ccpocket.daemon.claude

import dev.ccpocket.daemon.util.logger
import dev.ccpocket.protocol.PresetEnv
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import java.io.File
import java.net.URI
import java.nio.file.Path

/**
 * The API-TRANSPORT exception to the GUEST/BRIDGE clean room.
 *
 * A clean-room launch passes `--setting-sources=` so NO settings layer loads (issue #115 review H2: the
 * shared root's own guest-writable settings could auto-approve tools past the daemon's permission guard).
 * That stays right for permissions, hooks, skills, CLAUDE.md and MCP — but the same switch also drops the
 * USER settings file's `env` block, which on a gateway machine (cc-switch, and every "Anthropic-compatible
 * endpoint" guide) is the ONLY place the API route lives: `ANTHROPIC_BASE_URL` + its token + the model
 * aliases. Without them the child reaches the official endpoint with no usable credential and the turn
 * comes back as the CLI's `model:"<synthetic>"` API-failure placeholder — while the owner's ordinary
 * terminal claude, which DOES load settings, works on the same machine. That asymmetry was the v1.7.6
 * Windows bridge regression.
 *
 * So this re-imports the transport, and only the transport: a closed allow-list of route / credential /
 * model-routing variables, read out of the USER settings file alone (never project or local — that is the
 * guest-writable layer). Everything else an `env` block may carry — PATH, shell injection variables,
 * permission or hook toggles — stays out by construction.
 *
 * Two rules keep it from re-opening the sandbox:
 *  1. LAYER PAIRING — the invariant [GatewayDetector.resolvePaired] and [PresetEnv.SCRUBBED] already
 *     enforce: a destination and a credential must come from the SAME configuration layer, or the user's
 *     official key gets shipped to somebody else's gateway. The settings layer is therefore taken WHOLE
 *     (scrub the competing ambient values, then apply it) or not at all: it never lends its token to an
 *     ambient endpoint, never borrows an ambient token for its own, and a settings endpoint with no
 *     credential of its own fails CLOSED (launch env left exactly as it was) rather than guessing.
 *  2. NO ONWARD SPREAD — every clean-room launch also sets [SUBPROCESS_ENV_SCRUB], the CLI's own switch
 *     for "keep the credential in the parent's API client, strip it from Bash / hook / MCP subprocess
 *     environments". A restricted requester must not be able to `echo $ANTHROPIC_AUTH_TOKEN` the owner's
 *     key back out through a tool call. (Var name verified present in the installed CLI 2.1.218.)
 *
 * Reads config files only, never launches anything, never throws — an unreadable or malformed settings
 * file degrades to "import nothing" (same contract as [GatewayDetector] / [ClaudeDefaultModel]). No value
 * read here is ever logged or put in an error: half of them are secrets.
 */
object CleanRoomEnv {
    private val log = logger("CleanRoomEnv")
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** CLI switch: the API credential stays in claude's own HTTP client and is stripped from every
     *  subprocess environment it spawns (Bash, hooks, MCP servers). */
    const val SUBPROCESS_ENV_SCRUB = "CLAUDE_CODE_SUBPROCESS_ENV_SCRUB"

    // Model ROUTING only — which model id an alias resolves to. Not secrets, but they belong to the
    // endpoint that serves them, so they travel with their own layer (see [importSettingsRoute]).
    private val MODEL_VARS = listOf(
        PresetEnv.MODEL,
        PresetEnv.SMALL_FAST_MODEL,
        "ANTHROPIC_DEFAULT_OPUS_MODEL",
        "ANTHROPIC_DEFAULT_SONNET_MODEL",
        "ANTHROPIC_DEFAULT_HAIKU_MODEL",
        "CLAUDE_CODE_SUBAGENT_MODEL",
    )

    /** The CLOSED allow-list: destination, the two credential spellings, model routing. Nothing else in a
     *  settings `env` block may reach a clean-room child. Deliberately WITHOUT [PresetEnv.CUSTOM_HEADERS]
     *  — that one is scrub-only, because a raw header line is an arbitrary credential we can't reason about. */
    internal val ALLOWED: List<String> = listOf(PresetEnv.BASE_URL) + PresetEnv.TOKEN_VARS + MODEL_VARS

    /** What an accepted settings layer wipes first — [PresetEnv.SCRUBBED] (the preset launch's discipline,
     *  incl. CUSTOM_HEADERS) widened to every model-routing var we may set, so no ambient leftover survives
     *  next to the layer we just chose. */
    private val SCRUBBED: List<String> = (PresetEnv.SCRUBBED + MODEL_VARS).distinct()

    /**
     * Apply the clean-room transport policy to a launch environment. [presetActive] = the caller already
     * injected an active CC Pocket preset (issue #113), which outranks every settings file and must not be
     * mixed with one — then only the subprocess scrub is added.
     */
    fun applyTo(
        env: MutableMap<String, String>,
        presetActive: Boolean,
        userConfigDir: Path?,
        systemEnv: (String) -> String? = System::getenv,
        home: File = File(System.getProperty("user.home")),
    ) {
        env[SUBPROCESS_ENV_SCRUB] = "1"
        if (presetActive) {
            log.info("clean-room API route: active preset")
            return
        }
        importSettingsRoute(env, readAllowed(userSettingsFile(userConfigDir, systemEnv, home)))
    }

    /** The settings file THIS launch's CLI would call the `user` source: the daemon's isolated config dir
     *  when credential isolation is on (issue #69 — where `settings.json` is a symlink back to the real
     *  one), else `$CLAUDE_CONFIG_DIR`, else `~/.claude`. Same resolution as [GatewayDetector.resolve]. */
    internal fun userSettingsFile(userConfigDir: Path?, systemEnv: (String) -> String?, home: File): File {
        val root = userConfigDir?.toFile()
            ?: systemEnv("CLAUDE_CONFIG_DIR")?.takeIf { it.isNotBlank() }?.let(::File)
            ?: File(home, ".claude")
        return File(root, "settings.json")
    }

    /** The allow-listed `env` entries of one settings file. Absent / unreadable / malformed → empty map;
     *  non-string (a number, a bool, a nested object) and blank values are dropped, never coerced. */
    internal fun readAllowed(file: File): Map<String, String> = runCatching {
        if (!file.isFile) return emptyMap()
        val envObj = json.parseToJsonElement(file.readText()).jsonObject["env"] as? JsonObject
            ?: return emptyMap()
        ALLOWED.mapNotNull { key ->
            val prim = envObj[key] as? JsonPrimitive ?: return@mapNotNull null
            if (!prim.isString) return@mapNotNull null
            // ProcessBuilder rejects NUL and includes the rejected value in its exception message. Letting a
            // credential reach that exception would both abort the launch and risk echoing the secret through
            // the daemon's generic error path, so malformed process-env values are dropped before the merge.
            prim.content.trim().takeIf { it.isNotEmpty() && '\u0000' !in it }?.let { key to it }
        }.toMap()
    }.getOrElse { emptyMap() }

    /**
     * Merge one settings layer into [env] under the pairing rule. The layer is all-or-nothing:
     *
     *  • it names a route and/or a credential → it may own the launch, but only if nothing above it does.
     *    The daemon's OWN `ANTHROPIC_BASE_URL` outranks it (that env var is what a plain child inherits
     *    today, and it already picked the destination); a settings destination with no credential of its
     *    own is refused outright — borrowing the ambient one is precisely the cross-endpoint leak
     *    `GatewayCredentialPairingTest` pins. When it does win, every competing ambient value is scrubbed
     *    first, exactly like an active preset.
     *  • it names NEITHER (model routing only) → nothing about the endpoint/credential layer changes, so
     *    the models are merged in without displacing an ambient value that some other layer set.
     */
    internal fun importSettingsRoute(env: MutableMap<String, String>, settings: Map<String, String>) {
        if (settings.isEmpty()) return
        val base = settings[PresetEnv.BASE_URL]
        // AUTH_TOKEN first — the default tokenVar and the spelling relays use (mirrors resolvePaired)
        val credential = PresetEnv.TOKEN_VARS.firstNotNullOfOrNull { v -> settings[v]?.let { v to it } }
        val models = settings.filterKeys { it in MODEL_VARS }

        if (base != null && !validBaseUrl(base)) {
            // Do not mention the value: URLs can themselves carry credentials, and this branch is reachable
            // from the bridge's user-visible launch-error path. The whole settings layer fails closed.
            log.warn("clean-room API route: user settings contain an invalid endpoint — left as is")
            return
        }

        if (base == null && credential == null) {
            models.forEach { (k, v) -> if (env[k].isNullOrBlank()) env[k] = v }
            if (models.isNotEmpty()) log.info("clean-room API route: ambient, with ${models.size} settings model alias(es)")
            return
        }
        if (!env[PresetEnv.BASE_URL].isNullOrBlank()) {
            // The launch env already owns a destination. Its credential (or the account login behind it)
            // is not this layer's to replace, and this layer's token is not that endpoint's to receive —
            // including the model aliases, which name models only the refused gateway serves.
            log.info("clean-room API route: ambient env (settings layer not paired with it)")
            return
        }
        if (credential == null) {
            // Fail closed. Injecting this destination while an ambient key sits in the env would hand the
            // owner's official credential to a third party on the very next request.
            log.warn("clean-room API route: user settings name an endpoint with no credential of their own — left as is")
            return
        }
        SCRUBBED.forEach(env::remove)
        base?.let { env[PresetEnv.BASE_URL] = it }
        env[credential.first] = credential.second
        env.putAll(models)
        log.info("clean-room API route: user settings (${if (base != null) "endpoint+credential" else "credential only"})")
    }

    /** Accept an absolute HTTP(S) URI with a host. Embedded user-info is refused because BASE_URL itself
     *  remains visible to subprocesses; credentials must use the separately scrubbed token variables. */
    private fun validBaseUrl(value: String): Boolean = runCatching {
        val uri = URI(value)
        uri.scheme?.lowercase() in setOf("http", "https") &&
            !uri.host.isNullOrBlank() &&
            uri.rawUserInfo == null
    }.getOrDefault(false)
}
