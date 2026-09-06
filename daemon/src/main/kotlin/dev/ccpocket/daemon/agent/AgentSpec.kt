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
    // Extended-thinking toggle (Claude only, issue #345): orthogonal to effort on purpose. null = don't
    // touch it (the CLI's own default incl. the user's global `alwaysThinkingEnabled` setting decides);
    // true/false = --thinking enabled/disabled. Exists because a gateway whose models reject thinking
    // content fails a high-effort session with "Content block is not a thinking block" while
    // effort=max + thinking off works — the same two controls the VS Code extension exposes. Other
    // backends ignore it (they don't advertise the toggle, so no client ever sets it on them).
    val thinking: Boolean? = null,
    /** Backend-native permission-mode id not representable by the legacy protocol enum (Claude `auto`). */
    val permissionMode: String? = null,
    /** Backend-native service tier (Codex `priority` = Fast); null follows the CLI/account default. */
    val serviceTier: String? = null,
    // Fork the resumed session into a fresh id instead of appending to the original transcript. Set when
    // the phone takes over a session another writer may hold: Claude maps this to --fork-session; Codex
    // maps it to the app-server's native thread/fork request.
    val forkSession: Boolean = false,
    // Claude only, issue #282 (docs/design/REWIND-FORK.md): truncate the resumed context at a chain entry
    // (`--resume-session-at <uuid>`) — the CLI keeps everything up to and including that entry and drops the
    // rest. ALWAYS set together with [forkSession]: without a fork the CLI keeps the original id and APPENDS
    // the branch to the same transcript, turning it into a tree that linear replay renders twice (probed);
    // with one, the original file is untouched byte-for-byte and the truncated context lands in a fresh copy.
    // Both flags are `.hideHelp()`-hidden on the CLI but are the same knobs the Agent SDK's resumeSessionAt
    // compiles to; scripts/probe-claude-wire.py `scenario_rewind` asserts they still exist on every upgrade.
    val resumeSessionAt: String? = null,
    // Claude only, issue #282: the guard rail for the above (`--resume-drops-turn <uuid>`) — declares WHICH
    // user turn this truncation means to discard. The CLI refuses to start if the cut would also take
    // anything outside that turn (an absorbed queued message, a task notification), which is exactly the
    // #122 queued-injection hazard. Only meaningful when the cut drops EXACTLY one turn: declaring a single
    // turn while dropping several is always rejected, so the daemon leaves it null there and relies on the
    // dry-run preview + explicit confirmation instead. Ignored unless [resumeSessionAt] is also set.
    val resumeDropsTurn: String? = null,
    // GUEST folder-share clean-room (issue #115): launch the agent WITHOUT the owner's private, machine-wide
    // context, so a scoped collaborator can't siphon it through the agent (the issue's "context & capability
    // overflow" threat). Claude honours it (Codex ignores it; v1 guests are Claude-only in practice) via:
    //   • --strict-mcp-config with NO --mcp-config   → NO MCP servers (the "biggest hole": no acting through
    //     the owner's already-authenticated Feishu / email / calendar / internal integrations), neither the
    //     owner's user-scope servers nor the shared root's own guest-writable .mcp.json,
    //   • --setting-sources= (empty)                 → drop EVERY settings source: the `user` one (~/.claude
    //     global CLAUDE.md, user skills / commands / settings) and the shared folder's own project/local
    //     .claude/settings.json, whose allow-rules would auto-approve tools past the daemon (review H2),
    //   • --exclude-dynamic-system-prompt-sections   → drop auto-memory paths, env info, git status from the
    //     system prompt (vectors #2/#5 — the owner's private memory + shell env don't bleed into replies).
    // The CLI's separate login store remains available. On a GATEWAY machine, however, the route/token often
    // live in the user settings' `env` and go down with the settings sources; `CleanRoomEnv` re-imports that
    // allow-listed transport slice at launch and blocks it from reaching Bash/hooks/MCP subprocesses.
    val cleanRoom: Boolean = false,
    // OpenCode only: the initial prompt text, passed as a CLI positional arg to `opencode run`.
    // KNOWN LIMITATION (review P1, issue #164): argv is readable in the local process table (`ps`)
    // while the turn runs — unlike the stdin delivery claude/codex use — and Windows caps a command
    // line at ~32K chars, so a very long pasted prompt can fail to spawn.
    //
    // CORRECTION (2026-07-19, issue #164): the stated REASON for argv — "opencode run takes the
    // message as a CLI arg, not stdin" — was never true. Upstream `cli/cmd/run.ts` has read piped
    // stdin since 2025-07 (commit da909d9), a year before we wrote this: with no positional it uses
    // the pipe as the prompt, with both it concatenates `argv + "\n" + piped`. So the limitation is
    // ours to remove, not a CLI constraint.
    //
    // NOT switched yet, deliberately: opencode isn't installed on any machine we can test from, and
    // a blind switch fails in the worst way — opencode would block on `Bun.stdin.text()` until EOF,
    // emit nothing, and get culled by the 45s startup watchdog, surfacing as a generic timeout. When
    // an opencode box is available, the move is three edits in OpenCodeLauncher.kt: drop the argv
    // positional (line ~55; leaving it would send the prompt TWICE via the concat path), switch
    // redirectInput off /dev/null to PIPE (~line 61), then write the prompt and CLOSE the stream.
    val initialPrompt: String? = null,
)
