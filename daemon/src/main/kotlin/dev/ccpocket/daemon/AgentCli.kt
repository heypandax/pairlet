package dev.ccpocket.daemon

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.CliktError
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.multiple
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.int
import dev.ccpocket.daemon.control.LocalExecCancelReq
import dev.ccpocket.daemon.control.LocalExecConfirmReq
import dev.ccpocket.daemon.control.LocalExecGrantCreatedRes
import dev.ccpocket.daemon.control.LocalExecGrantReq
import dev.ccpocket.daemon.control.LocalExecGrantRes
import dev.ccpocket.daemon.control.LocalExecGrantsRes
import dev.ccpocket.daemon.control.LocalExecJoinReq
import dev.ccpocket.daemon.control.LocalExecJoinRes
import dev.ccpocket.daemon.control.LocalExecResultRes
import dev.ccpocket.daemon.control.LocalExecRunReq
import dev.ccpocket.daemon.control.LocalExecRunRes
import dev.ccpocket.daemon.control.LocalExecStatusRes
import dev.ccpocket.daemon.control.LocalExecTargetsRes
import dev.ccpocket.protocol.PocketJson
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.KSerializer
import java.io.File

/**
 * `pairlet agent …` (#367) — the CLI an AGENT on THIS machine uses to make an already-authorised OTHER
 * machine run one task, plus the owner commands that create such an authorisation here.
 *
 * Contract, chosen for a machine caller rather than a human reader:
 *  - STDOUT IS ALWAYS ONE JSON OBJECT, on success and (via the daemon's error body) on failure. Progress,
 *    hints and the "read this fingerprint out loud" instructions go to STDERR, so a caller can pipe stdout
 *    straight into a parser without a `--json` flag and without stripping anything. `--json` (inherited
 *    from [LocalCmd]) additionally silences the stderr commentary.
 *  - THE PROMPT NEVER TOUCHES argv. `run` takes `--prompt-file <path>` (or `-` for stdin) because argv is
 *    world-readable in the local process table while the command runs.
 *  - An INVITE is read from a file or stdin for the same reason, and is never echoed or logged: it carries
 *    a one-time relay ticket AND the invite secret.
 *  - Nothing here holds a relay bearer, an E2E key or the target's credential. Every command is a call to
 *    the ALREADY-RUNNING daemon's token-authenticated loopback API; none of them can start a daemon.
 */
private class AgentCmd : CliktCommand(name = "agent") {
    override fun help(context: Context) =
        "run a task on another computer that has authorised this one, and manage those authorisations"
    override fun run() = Unit
}

/** Shared bits of the `agent` commands: JSON to stdout, commentary to stderr. */
private abstract class AgentSubCmd(name: String, helpLine: String) : LocalCmd(name, helpLine) {

    /** The machine-readable answer. Exactly one line on stdout, always. */
    protected fun <T> out(serializer: KSerializer<T>, value: T) {
        echo(PocketJson.encodeToString(serializer, value))
    }

    /** Human commentary. STDERR only — it must never end up in a caller's parsed output. */
    protected fun note(line: String) {
        if (!json) echo(line, err = true)
    }

    /** Read a secret-ish input WITHOUT putting it on the command line. `-` means stdin. */
    protected fun readInput(path: String, what: String): String {
        val text = if (path == "-") {
            generateSequence(::readLine).joinToString("\n")
        } else {
            val f = File(path)
            if (!f.isFile) throw CliktError("no such $what file: $path")
            if (f.length() > MAX_INPUT_BYTES) throw CliktError("$what file is too large (max ${MAX_INPUT_BYTES / 1024} KiB)")
            f.readText()
        }
        if (text.isBlank()) throw CliktError("the $what is empty")
        return text
    }

    protected companion object {
        const val MAX_INPUT_BYTES = 256L * 1024
    }
}

// ===========================================================================
//  source side
// ===========================================================================

private class AgentTargetsCmd : AgentSubCmd("targets", "list the computers that have authorised this one, with their workspaces") {
    override fun run() = runBlocking {
        val res = client.get("/execution/targets", LocalExecTargetsRes.serializer())
        out(LocalExecTargetsRes.serializer(), res)
        if (res.items.isEmpty()) note("no active execution targets — the other computer runs: pairlet agent grant approve …")
    }
}

private class AgentRunCmd : AgentSubCmd("run", "submit one task to an authorised computer and get a run id back") {
    private val target by option("--target", help = "grant id from `agent targets`").default("")
    private val workspace by option("--workspace", help = "workspace ALIAS the target allows (never a path)").default("")
    private val promptFile by option("--prompt-file", help = "file holding the task text, or - for stdin").default("")
    private val agent by option("--agent", help = "claude | codex — REQUIRED; see `agent targets` for what a grant allows")
    private val model by option("--model", help = "backend model id")
    private val mode by option("--mode", help = "requested permission mode; the target clamps it to its own ceiling")
    private val requestId by option("--request-id", help = "reuse the SAME id to retry safely — it returns the original run")

    override fun run() = runBlocking {
        if (target.isBlank()) throw CliktError("--target <grant-id> is required — see: pairlet agent targets")
        if (workspace.isBlank()) throw CliktError("--workspace <alias> is required — the target only accepts an alias it granted")
        if (promptFile.isBlank()) throw CliktError("--prompt-file <path|-> is required (the prompt never goes on the command line)")
        // never inferred, even when the grant allows exactly one backend: the day the owner widens the
        // grant, an inferring caller would silently move to a different backend (see ExecutionRunSubmit.agent)
        val chosen = agent?.takeIf { it.isNotBlank() }
            ?: throw CliktError("--agent <claude|codex> is required — see: pairlet agent targets")
        val prompt = readInput(promptFile, "prompt")
        val res = client.post(
            "/execution/run", LocalExecRunReq.serializer(),
            LocalExecRunReq(
                target = target, workspace = workspace, prompt = prompt,
                agent = chosen, model = model, mode = mode, requestId = requestId,
            ),
            LocalExecRunRes.serializer(),
        )
        out(LocalExecRunRes.serializer(), res)
        if (res.duplicate) note("this request id was already accepted — same run, not a second one.")
        note("poll it:  pairlet agent status ${res.runId}")
    }
}

private class AgentStatusCmd : AgentSubCmd("status", "where one run currently is") {
    private val runId by argument("run-id")

    override fun run() = runBlocking {
        val res = client.get("/execution/runs/$runId", LocalExecStatusRes.serializer())
        out(LocalExecStatusRes.serializer(), res)
        if (res.approvalPending) note("waiting for the TARGET owner to answer a permission prompt — you cannot answer it from here.")
        if (res.phase == "cancel_requested") note("cancel received by the target; it has not yet proven the process ended.")
    }
}

private class AgentResultCmd : AgentSubCmd("result", "fetch one page of a run's output") {
    private val runId by argument("run-id")
    private val cursor by option("--cursor", help = "resume from this cursor instead of the local mirror's")

    override fun run() = runBlocking {
        val query = cursor?.let { mapOf("cursor" to it) } ?: emptyMap()
        val res = client.get("/execution/runs/$runId/result", LocalExecResultRes.serializer(), query)
        out(LocalExecResultRes.serializer(), res)
        if (res.truncated) note("the target capped this run's stored output — the tail was never written.")
        if (res.nextCursor != null) note("more to read:  pairlet agent result $runId --cursor ${res.nextCursor}")
    }
}

private class AgentCancelCmd : AgentSubCmd("cancel", "ask the target to stop a run") {
    private val runId by argument("run-id")

    override fun run() = runBlocking {
        val res = client.post(
            "/execution/runs/$runId/cancel", LocalExecCancelReq.serializer(), LocalExecCancelReq(),
            LocalExecStatusRes.serializer(),
        )
        out(LocalExecStatusRes.serializer(), res)
        // the honest distinction the handoff doc insists on
        if (res.phase == "process_ended") note("the target confirmed the process ended.")
        else note("the cancel was RECEIVED; the target has not yet confirmed the process ended. A cancel also cannot undo work already done.")
    }
}

// ===========================================================================
//  grants (the OWNER plane on whichever machine runs the command)
// ===========================================================================

private class AgentGrantCmd : CliktCommand(name = "grant") {
    override fun help(context: Context) = "authorise another computer to run tasks here, or join one that authorised you"
    override fun run() = Unit
}

private class AgentGrantApproveCmd : AgentSubCmd("approve", "authorise ANOTHER computer to run tasks on THIS one") {
    private val label by option("--label", help = "what to call the other computer, e.g. 'Studio Mac'").default("")
    private val workspace by option(
        "--workspace",
        help = "repeatable: alias=/absolute/path — the alias is all the other side ever names",
    ).multiple()
    private val agent by option("--agent", help = "repeatable: claude | codex").multiple()
    private val ceiling by option("--ceiling", help = "highest permission mode a remote run may use: plan|default|acceptEdits").default("default")
    private val ttlHours by option("--ttl-hours", help = "how long the authorisation lasts").int().default(24 * 7)
    private val concurrent by option("--max-concurrent", help = "runs allowed at once").int().default(1)
    private val queued by option("--max-queued", help = "runs allowed to wait").int().default(4)
    private val timeoutMinutes by option("--run-timeout-minutes", help = "hard cut-off for one run").int().default(30)
    private val budget by option("--request-budget", help = "total runs this authorisation may ever accept").int().default(200)

    override fun run() = runBlocking {
        if (label.isBlank()) throw CliktError("--label <name> is required — you will read it back when revoking")
        if (workspace.isEmpty()) throw CliktError("at least one --workspace alias=/absolute/path is required")
        val roots = workspace.associate { entry ->
            val alias = entry.substringBefore('=', "").trim()
            val path = entry.substringAfter('=', "").trim()
            if (alias.isEmpty() || path.isEmpty()) throw CliktError("--workspace must be alias=/absolute/path, got: $entry")
            alias to path
        }
        val res = client.post(
            "/execution/grants", LocalExecGrantReq.serializer(),
            LocalExecGrantReq(
                label = label, workspaces = roots,
                agents = agent.ifEmpty { listOf("claude") },
                approvalCeiling = ceiling, ttlHours = ttlHours,
                maxConcurrentRuns = concurrent, maxQueuedRuns = queued,
                runTimeoutMinutes = timeoutMinutes, requestBudget = budget,
            ),
            LocalExecGrantCreatedRes.serializer(),
        )
        // the invite is in the JSON on stdout ONCE; the commentary never repeats it
        out(LocalExecGrantCreatedRes.serializer(), res)
        note("")
        // what the ceiling ACTUALLY permits, spelled out — acceptEdits means unattended writes in the
        // granted workspace, and the mode name alone does not say that
        note("  ${res.ceilingNote}")
        note("")
        note("  The `invite` field above works ONCE and expires in ${res.ttlSec}s. Hand it over out of band.")
        note("  Read them this fingerprint of THIS computer:  ${res.targetFingerprint}")
        note("  They run:  pairlet agent grant join --invite-file <file> --target-fingerprint '<the line above>'")
        note("  Then they read you back a LINK fingerprint, and you finish with:")
        note("      pairlet agent grant confirm ${res.grantId} --fingerprint '<what they read>'")
        note("  Until you confirm, the authorisation grants NO run authority.")
    }
}

private class AgentGrantConfirmCmd : AgentSubCmd("confirm", "finish an authorisation by confirming the link fingerprint") {
    private val grantId by argument("grant-id")
    private val fingerprint by option("--fingerprint", help = "the LINK fingerprint the other side read to you").default("")

    override fun run() = runBlocking {
        if (fingerprint.isBlank()) throw CliktError("--fingerprint '<words>' is required — it is the whole point of this step")
        val res = client.post(
            "/execution/grants/$grantId/confirm", LocalExecConfirmReq.serializer(), LocalExecConfirmReq(fingerprint),
            LocalExecGrantRes.serializer(),
        )
        out(LocalExecGrantRes.serializer(), res)
        note("authorisation ${res.grant.grantId} is now ${res.grant.state}.")
    }
}

private class AgentGrantRevokeCmd : AgentSubCmd("revoke", "withdraw an authorisation (in force immediately)") {
    private val grantId by argument("grant-id")

    override fun run() = runBlocking {
        val res = client.delete("/execution/grants/$grantId", LocalExecGrantRes.serializer())
        out(LocalExecGrantRes.serializer(), res)
        note("revoked — new requests are refused now. A run already in flight is stopped on the next tick.")
    }
}

private class AgentGrantListCmd : AgentSubCmd("list", "the authorisations THIS computer has given out") {
    override fun run() = runBlocking {
        val res = client.get("/execution/grants", LocalExecGrantsRes.serializer())
        out(LocalExecGrantsRes.serializer(), res)
        res.items.filter { it.state == "awaiting_owner_confirm" }.forEach {
            note("${it.grantId} is waiting for you to confirm ${it.sourceLinkFingerprint ?: "their link fingerprint"}")
        }
    }
}

private class AgentGrantJoinCmd : AgentSubCmd("join", "accept an authorisation another computer gave you") {
    private val inviteFile by option("--invite-file", help = "file holding the invite line, or - for stdin").default("")
    private val targetFingerprint by option("--target-fingerprint", help = "the fingerprint THEY read to you").default("")
    private val relayAuthority by option("--relay", help = "host[:port] you were shown, when the invite names an unknown relay")
    private val label by option("--label", help = "what to call that computer on your side")

    override fun run() = runBlocking {
        if (inviteFile.isBlank()) throw CliktError("--invite-file <path|-> is required (an invite never goes on the command line)")
        if (targetFingerprint.isBlank()) throw CliktError("--target-fingerprint '<words>' is required — without it you are trusting the invite alone")
        val invite = readInput(inviteFile, "invite").trim()
        val res = client.post(
            "/execution/join", LocalExecJoinReq.serializer(),
            LocalExecJoinReq(invite = invite, targetFingerprint = targetFingerprint, relayAuthority = relayAuthority, label = label),
            LocalExecJoinRes.serializer(),
        )
        out(LocalExecJoinRes.serializer(), res)
        note("")
        note("  Read this back to them, they confirm it, and only then can you send work:")
        note("      ${res.sourceLinkFingerprint}")
        note("  It identifies THIS LINK (本次链路指纹), not this machine — re-joining produces a different one.")
    }
}

/** Assembled here rather than in Main, so the whole #367 CLI lives in one file. */
internal fun agentCommand(): CliktCommand = AgentCmd().subcommands(
    AgentTargetsCmd(), AgentRunCmd(), AgentStatusCmd(), AgentResultCmd(), AgentCancelCmd(),
    AgentGrantCmd().subcommands(
        AgentGrantApproveCmd(), AgentGrantConfirmCmd(), AgentGrantRevokeCmd(), AgentGrantListCmd(), AgentGrantJoinCmd(),
    ),
)
