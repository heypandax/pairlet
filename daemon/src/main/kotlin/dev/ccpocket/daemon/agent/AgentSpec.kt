package dev.ccpocket.daemon.agent

import dev.ccpocket.protocol.PermissionMode
import java.nio.file.Path

/**
 * What to launch: working directory + optional resume/model/effort/mode. The union of every backend's
 * launch knobs; a backend ignores fields that don't apply to it (Codex ignores [forkSession] /
 * [appendSystemPrompt]; it sets model/mode/effort per turn instead of at launch).
 */
data class AgentSpec(
    val workdir: Path,
    val resumeId: String? = null,
    val model: String? = null,
    val mode: PermissionMode = PermissionMode.DEFAULT,
    val appendSystemPrompt: String? = null,
    val effort: String? = null, // reasoning effort: low|medium|high|xhigh|max
    // Claude only: fork the resumed session into a fresh id (--fork-session) instead of appending to the
    // original transcript. Set when the phone takes over / cold-resumes a session another writer may hold.
    val forkSession: Boolean = false,
)
