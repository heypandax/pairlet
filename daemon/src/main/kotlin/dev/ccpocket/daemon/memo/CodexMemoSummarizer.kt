package dev.ccpocket.daemon.memo

import dev.ccpocket.daemon.agent.ExecutableResolver
import dev.ccpocket.daemon.codex.CodexLauncher
import dev.ccpocket.daemon.util.logger
import dev.ccpocket.protocol.VOICE_MEMO_AGENT_CODEX
import dev.ccpocket.protocol.VoiceMemoLimits
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import kotlin.coroutines.cancellation.CancellationException
import kotlin.io.path.isRegularFile

/**
 * Memo organiser over a ONE-SHOT `codex exec` (argv probed against Codex CLI 0.155.1). Isolation comes from the
 * flags, not the prompt: an ephemeral session, rules ignored, a read-only sandbox, the shell and unified-exec
 * tools disabled, no MCP servers, and an empty private working directory. No `-m`: the owner's Codex
 * configuration decides the model; reasoning effort is pinned low for latency.
 *
 * The instruction text is [MemoOrganizerContract.INSTRUCTIONS] as the PROMPT argument; the transcript rides stdin
 * as the shared JSON payload (Codex appends stdin to the prompt as a `<stdin>` block), never argv. The answer is
 * read from the `-o` file — the last message, constrained by [MemoOrganizerContract.MINIMAL_SCHEMA] — and goes
 * through the same [MemoOrganizerContract.parseResult] as every other organiser.
 *
 * Codex echoes the prompt AND the stdin payload (the transcript) to stderr: stdout and stderr are read bounded
 * and then only ever measured, never logged or returned.
 */
class CodexMemoSummarizer(
    /** The owner's `--codex-bin` override, resolved exactly as the main Codex backend does. */
    private val codexBin: String?,
    private val runner: MemoProcessExecutor = MemoProcessRunner(),
    private val resolveBin: () -> Path? = { runCatching { CodexLauncher.resolveExecutable(codexBin) }.getOrNull() },
    /** Parent of the per-call private dirs; null = the system temp dir. */
    private val tempRoot: Path? = null,
) : MemoSummarizer {
    private val log = logger("MemoSummarize")

    override val agent: String get() = VOICE_MEMO_AGENT_CODEX

    override fun isAvailable(): Boolean = runCatching {
        val exe = resolveBin() ?: return false
        !ExecutableResolver.isBatchShim(exe.toString())
    }.getOrDefault(false)

    override suspend fun summarize(transcript: String, locale: String?, deadline: MemoDeadline): MemoSummaryResult =
        withContext(Dispatchers.IO) {
            val exe = runCatching { resolveBin() }.getOrNull()
                ?: return@withContext done(MemoSummaryResult.Unavailable, "resolve")
            // a .cmd/.bat shim is started through cmd.exe, which re-parses the command line: the multi-line
            // PROMPT and the quoted `-c` values can't survive that intact, so the adapter refuses to run
            if (ExecutableResolver.isBatchShim(exe.toString())) return@withContext done(MemoSummaryResult.Unavailable, "shim")
            val budget = minOf(deadline.remainingMs(), VoiceMemoLimits.SUMMARIZE_DEADLINE_MS)
            if (budget <= 0) return@withContext done(MemoSummaryResult.TimedOut, "deadline")
            val callDir = try {
                MemoWorkDir.createCallDir(tempRoot, "ccp-memo-codex")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                return@withContext done(MemoSummaryResult.Failed, "workdir")
            }
            try {
                // the model's working directory stays EMPTY; the schema and answer files sit beside it
                val workDir = MemoWorkDir.createCallDir(callDir, "work")
                val schema = callDir.resolve(SCHEMA_FILE)
                MemoWorkDir.writePrivateFile(schema, MemoOrganizerContract.MINIMAL_SCHEMA.toByteArray(Charsets.UTF_8))
                val out = callDir.resolve(OUT_FILE)
                val t0 = System.nanoTime()
                val run = runner.run(
                    MemoProcessSpec(
                        argv = buildArgv(exe.toString(), workDir, schema, out),
                        cwd = workDir,
                        stdin = MemoOrganizerContract.payload(transcript, locale).toByteArray(Charsets.UTF_8),
                        stdoutLimit = MAX_STDOUT_BYTES,
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
                        val outBytes = if (out.isRegularFile()) Files.size(out) else -1
                        log.info(
                            "memo summary agent=codex exit=${run.exitCode} in ${ms}ms " +
                                "stdout=${run.stdout.size}B stderr=${run.stderr.size}B out=${outBytes}B",
                        )
                        when {
                            run.exitCode != 0 -> done(MemoSummaryResult.Failed, "exit", ms)
                            outBytes < 0 -> done(MemoSummaryResult.Invalid, "no-output", ms)
                            outBytes > VoiceMemoLimits.MAX_MODEL_STDOUT_BYTES -> done(MemoSummaryResult.Invalid, "output-limit", ms)
                            else -> done(parseAnswer(Files.readString(out, Charsets.UTF_8)), "parse", ms)
                        }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                done(MemoSummaryResult.Failed, "io")
            } finally {
                runCatching { callDir.toFile().deleteRecursively() }
            }
        }

    private fun done(r: MemoSummaryResult, stage: String, ms: Long? = null): MemoSummaryResult {
        if (r !is MemoSummaryResult.Ok) log.info("memo summary agent=codex stage=$stage code=${r.errorCode}${ms?.let { " in ${it}ms" } ?: ""}")
        return r
    }

    companion object {
        const val MAX_STDOUT_BYTES = 16 * 1024
        const val MAX_STDERR_BYTES = 16 * 1024
        const val SCHEMA_FILE = "schema.json"
        const val OUT_FILE = "out.json"

        /** The probed argv, flag for flag. Nothing user-provided is in it: the transcript goes over stdin. */
        internal fun buildArgv(exe: String, workDir: Path, schema: Path, out: Path): List<String> = listOf(
            exe,
            "exec",
            "--ephemeral",
            "--ignore-rules",
            "--skip-git-repo-check",
            "-C", workDir.toString(),
            "-s", "read-only",
            "--disable", "shell_tool",
            "--disable", "unified_exec",
            "-c", "mcp_servers={}",
            "-c", "model_reasoning_effort=\"low\"",
            "--color", "never",
            "--output-schema", schema.toString(),
            "-o", out.toString(),
            MemoOrganizerContract.INSTRUCTIONS,
        )

        /** The `-o` file holds the result object itself (no envelope around it). */
        internal fun parseAnswer(text: String): MemoSummaryResult {
            if (text.encodeToByteArray().size > VoiceMemoLimits.MAX_MODEL_STDOUT_BYTES) return MemoSummaryResult.Invalid
            val element = runCatching { Json.parseToJsonElement(text) }.getOrNull() ?: return MemoSummaryResult.Invalid
            return MemoOrganizerContract.parseResult(element)
        }
    }
}
