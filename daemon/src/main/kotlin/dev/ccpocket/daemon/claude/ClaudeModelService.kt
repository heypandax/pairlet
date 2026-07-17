package dev.ccpocket.daemon.claude

import dev.ccpocket.protocol.AgentKind
import dev.ccpocket.protocol.ModelsList
import java.nio.file.Path

/** Claude has no cheap model-list command; surface config default first, then app-supported aliases. */
class ClaudeModelService(
    private val userConfigDir: Path? = null,
) {
    fun fetch(workdir: String?): ModelsList {
        val configured = workdir?.let { ClaudeDefaultModel.resolve(it, userConfigDir) }
        val models = (listOfNotNull(configured) + CLAUDE_MODEL_ALIASES).distinct()
        return ModelsList(agent = AgentKind.CLAUDE, models = models)
    }

    companion object {
        val CLAUDE_MODEL_ALIASES = listOf("fable", "opus", "sonnet", "haiku")
    }
}
