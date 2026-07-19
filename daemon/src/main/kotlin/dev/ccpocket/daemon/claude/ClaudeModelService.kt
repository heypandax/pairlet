package dev.ccpocket.daemon.claude

import dev.ccpocket.protocol.AgentKind
import dev.ccpocket.protocol.ModelsList
import java.nio.file.Path

/**
 * Claude has no cheap model-list command; surface config default first, then app-supported aliases.
 *
 * On a third-party gateway there IS an authoritative source, so issue #167 adds one: ask the gateway
 * for its own `/v1/models` and pass the answer through as [ModelsList.gatewayModels]. That replaces the
 * client's hand-written vendor-id table, which could only ever be a guess and rotted invisibly (a
 * retired id keeps failing on the user's gateway, where we never see the error). Failure to reach the
 * gateway is not an error here — the client still has its seed table.
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
) {
    fun fetch(workdir: String?): ModelsList {
        val configured = workdir?.let { ClaudeDefaultModel.resolve(it, userConfigDir) }
        val models = (listOfNotNull(configured) + CLAUDE_MODEL_ALIASES).distinct()
        // Null = official endpoint, unconfigured, or a layer that names a gateway but holds no
        // credential for it. All three mean: don't ask, don't send anything.
        val gateway = runCatching {
            resolveGateway(presetEnv())?.let { gw -> probe(gw) }
        }.getOrNull().orEmpty()
        // Aliases already lead the picker (#167 direction ①); don't repeat one that the gateway also lists.
        return ModelsList(
            agent = AgentKind.CLAUDE,
            models = models,
            gatewayModels = gateway.filterNot { it in models },
        )
    }

    companion object {
        val CLAUDE_MODEL_ALIASES = listOf("fable", "opus", "sonnet", "haiku")
    }
}
