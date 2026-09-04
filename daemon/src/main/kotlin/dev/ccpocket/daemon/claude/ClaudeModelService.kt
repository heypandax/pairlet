package dev.ccpocket.daemon.claude

import dev.ccpocket.daemon.disk.ClaudeModelHistory
import dev.ccpocket.protocol.AgentKind
import dev.ccpocket.protocol.CLAUDE_PERMISSION_MODE_AUTO
import dev.ccpocket.protocol.ModelsList
import java.nio.file.Path

/**
 * Claude has no cheap model-list command; surface config default first, then app-supported aliases.
 *
 * On a third-party gateway there IS normally an authoritative source, so issue #167 asks the gateway
 * for its own `/v1/models` and passes the answer through as [ModelsList.gatewayModels]. That replaces
 * the client's hand-written vendor-id table, which could only ever be a guess and rotted invisibly.
 * Gateways without that endpoint fall back to ids this machine actually used, from
 * [ClaudeModelHistory]; failure of both paths is still silent and leaves the seed table intact.
 *
 * The gateway hooks are constructor-injected so tests can drive every branch without a network.
 */
class ClaudeModelService(
    private val userConfigDir: Path? = null,
    /** The ACTIVE preset's env, when one is activated. Must be the same source `DaemonInfo`'s gateway
     *  pill uses (`Main.kt`), or the host we contact would differ from the host the client displays —
     *  and the credential would be one `ClaudeLauncher.applyPresetEnv` has already scrubbed. */
    private val presetEnv: () -> Map<String, String>? = { null },
    private val resolveGateway: (Map<String, String>?) -> GatewayDetector.Paired? = { preset ->
        GatewayDetector.resolvePaired(presetEnv = preset, userConfigDir = userConfigDir)
    },
    private val probe: (GatewayDetector.Paired) -> List<String>? = { gw -> GatewayModelProbe.fetch(gw) },
    /** Direction ③: last-resort ids from this machine's Claude transcripts. Kept separate from
     *  UsageService because the dashboard's time-window/top-six/token-order contract is wrong here. */
    private val historyModels: () -> List<String> = { ClaudeModelHistory.recent() },
) {
    fun fetch(workdir: String?): ModelsList {
        val configured = workdir?.let { ClaudeDefaultModel.resolve(it, userConfigDir) }
        val models = (listOfNotNull(configured) + CLAUDE_MODEL_ALIASES).distinct()
        // Null resolution = official endpoint, unconfigured, or a layer that names a gateway but
        // holds no paired credential. All three mean: don't probe and don't infer from unrelated
        // history. A resolved gateway whose authoritative endpoint fails gets direction ③'s local
        // history fallback; both failure paths stay silent and keep the aliases intact.
        val resolved = runCatching { resolveGateway(presetEnv()) }.getOrNull()
        val authoritative = resolved?.let { gw -> runCatching { probe(gw) }.getOrNull() }.orEmpty()
        val gateway = when {
            authoritative.isNotEmpty() -> authoritative
            resolved != null -> runCatching(historyModels).getOrDefault(emptyList())
            else -> emptyList()
        }
        // Aliases already lead the picker (#167 direction ①); don't repeat one that the gateway also lists.
        return ModelsList(
            agent = AgentKind.CLAUDE,
            models = models,
            // Verified against the installed Claude Code 2.1.218 --help. `auto` is a permission
            // mode; `ultra` is not an accepted Claude effort and must never be advertised here.
            supportedEfforts = CLAUDE_EFFORTS,
            permissionModes = listOf(CLAUDE_PERMISSION_MODE_AUTO),
            // The CLI's `--thinking enabled|adaptive|disabled` (issue #345) — a gateway machine's
            // models may reject thinking content while the user still wants a high effort, so the
            // phone gets a separate switch. Gating on this field keeps the switch hidden against an
            // older daemon, which would silently drop the OpenSession field and show a lie.
            supportsThinkingToggle = true,
            gatewayModelsSource = when {
                authoritative.isNotEmpty() -> "gateway"
                gateway.isNotEmpty() -> "history"
                else -> null
            },
            gatewayModels = gateway.asSequence()
                .map(String::trim)
                .filter { it.isNotEmpty() && it.length <= MAX_MODEL_ID_LEN && it.none(Char::isISOControl) }
                .filterNot { it in models }
                .distinct()
                .take(MAX_GATEWAY_MODELS)
                .toList(),
        )
    }

    companion object {
        val CLAUDE_MODEL_ALIASES = listOf("fable", "opus", "sonnet", "haiku")
        val CLAUDE_EFFORTS = listOf("low", "medium", "high", "xhigh", "max")
        private const val MAX_GATEWAY_MODELS = 200
        private const val MAX_MODEL_ID_LEN = 128
    }
}
