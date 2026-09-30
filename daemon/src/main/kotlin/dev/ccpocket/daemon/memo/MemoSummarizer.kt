package dev.ccpocket.daemon.memo

import dev.ccpocket.daemon.agent.ExecutableResolver
import dev.ccpocket.daemon.claude.ClaudeRuntime
import dev.ccpocket.daemon.util.logger
import dev.ccpocket.protocol.VOICE_MEMO_AGENT_CLAUDE
import dev.ccpocket.protocol.VoiceMemoError
import dev.ccpocket.protocol.VoiceMemoLimits
import dev.ccpocket.protocol.VoiceMemoResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.nio.file.Path
import kotlin.coroutines.cancellation.CancellationException

/**
 * One organiser adapter: a CLI that turns a transcript into a [VoiceMemoResult] under [MemoOrganizerContract].
 * Adapters are pluggable — [MemoSummarizers] lists the ones this daemon was built with, and a machine that has
 * none of their CLIs still transcribes.
 */
interface MemoSummarizer {
    /** The [dev.ccpocket.protocol.VoiceMemoStart.agent] word this adapter serves. */
    val agent: String

    /** LOCAL check only (binary present and launchable this way) — never starts the model. */
    fun isAvailable(): Boolean

    suspend fun summarize(transcript: String, locale: String?, deadline: MemoDeadline): MemoSummaryResult
}

/** No variant carries text: CLI stderr and model output never leave the summarizer except as a checked result. */
sealed interface MemoSummaryResult {
    val errorCode: String?

    data class Ok(val result: VoiceMemoResult) : MemoSummaryResult {
        override val errorCode: String? get() = null
    }

    /** The CLI answered, but not with a structured result that passes the local checks → degraded. */
    data object Invalid : MemoSummaryResult {
        override val errorCode: String get() = VoiceMemoError.INVALID_RESULT
    }

    data object Unavailable : MemoSummaryResult {
        override val errorCode: String get() = VoiceMemoError.AGENT_UNAVAILABLE
    }

    data object Failed : MemoSummaryResult {
        override val errorCode: String get() = VoiceMemoError.SUMMARY_FAILED
    }

    data object TimedOut : MemoSummaryResult {
        override val errorCode: String get() = VoiceMemoError.SUMMARY_TIMEOUT
    }
}

/**
 * Memo organiser over a ONE-SHOT, tool-less `claude --print` (overall design §8, code design §6.4). Same
 * isolation recipe as [dev.ccpocket.daemon.feishu.ClaudeFeishuPromptReviewer]: no tools, no MCP, safe mode,
 * no slash commands, no session persistence, a fixed system prompt, and a cwd that holds no project
 * material. Unlike the reviewer it passes NO `--model` / `--effort` — the owner's current preset and the CLI
 * default decide. The binary, credential store and preset env all come from [runtime] (the main backend's),
 * read per launch.
 *
 * Only a zero exit carrying `structured_output` that passes [MemoOrganizerContract.parseResult] is a result;
 * everything else maps to a fixed code. The transcript travels over stdin as a JSON field and is data, never
 * instructions.
 */
class ClaudeMemoSummarizer(
    private val runtime: ClaudeRuntime,
    private val runner: MemoProcessExecutor = MemoProcessRunner(),
    private val resolveBin: () -> Path? = { runtime.resolveExecutable() },
    /** Parent of the per-call empty working dirs; null = the system temp dir. */
    private val tempRoot: Path? = null,
) : MemoSummarizer {
    private val log = logger("MemoSummarize")

    override val agent: String get() = VOICE_MEMO_AGENT_CLAUDE

    override fun isAvailable(): Boolean = runCatching {
        val exe = resolveBin() ?: return false
        !ExecutableResolver.isBatchShim(exe.toString())
    }.getOrDefault(false)

    override suspend fun summarize(transcript: String, locale: String?, deadline: MemoDeadline): MemoSummaryResult =
        withContext(Dispatchers.IO) {
            val exe = runCatching { resolveBin() }.getOrNull()
                ?: return@withContext done(MemoSummaryResult.Unavailable, "resolve")
            // a Windows batch shim goes through cmd.exe, which re-parses argv — the multi-line system
            // prompt and schema can't survive that intact (see ClaudeFeishuPromptReviewer), so: unavailable
            if (ExecutableResolver.isBatchShim(exe.toString())) return@withContext done(MemoSummaryResult.Unavailable, "shim")
            val budget = minOf(deadline.remainingMs(), VoiceMemoLimits.SUMMARIZE_DEADLINE_MS)
            if (budget <= 0) return@withContext done(MemoSummaryResult.TimedOut, "deadline")
            val cwd = try {
                MemoWorkDir.createCallDir(tempRoot, "ccp-memo-sum")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                return@withContext done(MemoSummaryResult.Failed, "workdir")
            }
            try {
                val t0 = System.nanoTime()
                val run = runner.run(
                    MemoProcessSpec(
                        argv = buildArgv(exe.toString()),
                        cwd = cwd,
                        env = { env -> runtime.applyTo(env) },
                        stdin = MemoOrganizerContract.payload(transcript, locale).toByteArray(Charsets.UTF_8),
                        stdoutLimit = VoiceMemoLimits.MAX_MODEL_STDOUT_BYTES,
                        stderrLimit = MAX_STDERR_BYTES,
                        deadlineMs = budget,
                    ),
                )
                val ms = (System.nanoTime() - t0) / 1_000_000
                when (run) {
                    MemoProcessResult.TimedOut -> done(MemoSummaryResult.TimedOut, "run", ms)
                    MemoProcessResult.StartFailed -> done(MemoSummaryResult.Unavailable, "start", ms)
                    MemoProcessResult.Aborted -> done(MemoSummaryResult.Failed, "aborted", ms)
                    is MemoProcessResult.Exited -> {
                        log.info("memo summary exit=${run.exitCode} in ${ms}ms stdout=${run.stdout.size}B stderr=${run.stderr.size}B")
                        when {
                            run.exitCode != 0 -> done(MemoSummaryResult.Failed, "exit", ms)
                            run.stdoutTruncated -> done(MemoSummaryResult.Invalid, "stdout-limit", ms)
                            else -> done(parseOutput(run.stdout.toString(Charsets.UTF_8)), "parse", ms)
                        }
                    }
                }
            } finally {
                runCatching { cwd.toFile().deleteRecursively() }
            }
        }

    private fun done(r: MemoSummaryResult, stage: String, ms: Long? = null): MemoSummaryResult {
        if (r !is MemoSummaryResult.Ok) log.info("memo summary stage=$stage code=${r.errorCode}${ms?.let { " in ${it}ms" } ?: ""}")
        return r
    }

    companion object {
        const val MAX_STDERR_BYTES = 16 * 1024

        /** Claude takes the schema WITH its length limits; the local check re-applies them regardless. */
        val SCHEMA: String get() = MemoOrganizerContract.SCHEMA
        val SYSTEM_PROMPT: String get() = MemoOrganizerContract.INSTRUCTIONS

        internal fun buildArgv(exe: String): List<String> = listOf(
            exe,
            "--print",
            "--output-format", "json",
            "--json-schema", SCHEMA,
            // tool-less and MCP-less as single tokens (see ClaudeFeishuPromptReviewer.buildArgv for why);
            // deliberately NO --model / --effort: the owner's preset and the CLI default decide
            "--tools=",
            "--strict-mcp-config",
            "--safe-mode",
            "--disable-slash-commands",
            "--no-session-persistence",
            "--system-prompt", SYSTEM_PROMPT,
        )

        /**
         * The CLI's `--output-format json` envelope → a checked result. Only `structured_output` counts — the
         * assistant's free text is never parsed for JSON. The object itself goes through the shared
         * [MemoOrganizerContract.parseResult].
         */
        internal fun parseOutput(stdout: String): MemoSummaryResult {
            if (stdout.encodeToByteArray().size > VoiceMemoLimits.MAX_MODEL_STDOUT_BYTES) return MemoSummaryResult.Invalid
            val outer = runCatching { Json.parseToJsonElement(stdout) }.getOrNull() as? JsonObject
                ?: return MemoSummaryResult.Invalid
            // an error envelope on exit 0 is a failed call, not a malformed answer
            if ((outer["is_error"] as? JsonPrimitive)?.takeIf { !it.isString }?.content == "true") return MemoSummaryResult.Failed
            return MemoOrganizerContract.parseResult(outer["structured_output"])
        }
    }
}
