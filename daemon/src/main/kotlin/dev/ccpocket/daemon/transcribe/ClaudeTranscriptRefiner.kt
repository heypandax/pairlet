package dev.ccpocket.daemon.transcribe

import dev.ccpocket.daemon.agent.ExecutableResolver
import dev.ccpocket.daemon.claude.ClaudeRuntime
import dev.ccpocket.daemon.claude.GatewayDetector
import dev.ccpocket.daemon.memo.MemoProcessExecutor
import dev.ccpocket.daemon.memo.MemoProcessResult
import dev.ccpocket.daemon.memo.MemoProcessRunner
import dev.ccpocket.daemon.memo.MemoProcessSpec
import dev.ccpocket.daemon.memo.MemoWorkDir
import dev.ccpocket.daemon.util.logger
import dev.ccpocket.protocol.AgentKind
import dev.ccpocket.protocol.PresetEnv
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.nio.file.Path
import kotlin.coroutines.cancellation.CancellationException

/**
 * Transcript refiner over a ONE-SHOT, tool-less `claude --print` (design §4.3). The isolation recipe is
 * [dev.ccpocket.daemon.memo.ClaudeMemoSummarizer]'s: no tools, no MCP, safe mode, no slash commands, no session
 * persistence, a fixed system prompt (no variable part at all), a fresh owner-only empty working directory, and the
 * main backend's [ClaudeRuntime] for binary, credential store and preset env. Process lifetime is the memo pipeline's
 * [MemoProcessRunner]: stdin carries the user message ([RefineContract.userMessage]: glossary, then the transcript),
 * timeout and cancellation both tear the whole tree down.
 *
 * Model (design §4.3, measured 2026-10-05): sonnet at low effort answers a replacement list in ~2–2.5 s of API time;
 * haiku was 44–100 s on the owner's account and the session's own model may be opus/max. The memo summarizer passes
 * no `--model` at all so that an owner on an API preset or a third-party gateway (#113, #139) keeps the model their
 * route defines — there an alias like `sonnet` may not exist. This adapter keeps that guarantee and only adds the
 * speed pick where it is known to resolve: `--model sonnet` is passed on the owner's NATIVE login only — no active
 * preset and no third-party `ANTHROPIC_BASE_URL` (the same detection [dev.ccpocket.protocol.DaemonInfo.gatewayBaseUrl]
 * reports) — and `--effort low` always. `CC_POCKET_REFINE_CLAUDE_MODEL` names a model explicitly and is passed on
 * any route: the owner who sets it knows what their route serves.
 */
class ClaudeTranscriptRefiner(
    private val runtime: ClaudeRuntime,
    private val runner: MemoProcessExecutor = MemoProcessRunner(),
    private val resolveBin: () -> Path? = { runtime.resolveExecutable() },
    /** Parent of the per-call empty working dirs; null = the system temp dir. */
    private val tempRoot: Path? = null,
    /** True when claude runs on the owner's own Anthropic login; see the class doc. */
    private val nativeLogin: () -> Boolean = { isNativeLogin(runtime) },
    private val modelOverride: () -> String? = { System.getenv(MODEL_ENV) },
) : TranscriptRefiner {
    private val log = logger("Refine")

    override val agent: AgentKind get() = AgentKind.CLAUDE

    /** The same answer [dev.ccpocket.daemon.memo.ClaudeMemoSummarizer.isAvailable] gives for voice memos: the main
     *  backend's binary resolves, and it is not a Windows batch shim. */
    override fun isAvailable(): Boolean = runCatching {
        val exe = resolveBin() ?: return false
        !ExecutableResolver.isBatchShim(exe.toString())
    }.getOrDefault(false)

    override suspend fun refine(text: String, locale: String?, glossary: List<String>, timeoutMs: Long): RefineOutcome =
        withContext(Dispatchers.IO) {
            val exe = runCatching { resolveBin() }.getOrNull() ?: return@withContext done(RefineOutcome.Unavailable, "resolve")
            // a Windows batch shim goes through cmd.exe, which re-parses argv — the multi-line system prompt and the
            // schema can't survive that intact (see ClaudeFeishuPromptReviewer), so: unavailable
            if (ExecutableResolver.isBatchShim(exe.toString())) return@withContext done(RefineOutcome.Unavailable, "shim")
            if (timeoutMs <= 0) return@withContext done(RefineOutcome.TimedOut, "deadline")
            val model = modelArgs(modelOverride(), runCatching { nativeLogin() }.getOrDefault(false))
            val cwd = try {
                MemoWorkDir.createCallDir(tempRoot, "ccp-refine")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                return@withContext done(RefineOutcome.Failed, "workdir")
            }
            try {
                val t0 = System.nanoTime()
                val run = runner.run(
                    MemoProcessSpec(
                        argv = buildArgv(exe.toString(), RefineContract.systemPrompt(locale), model),
                        cwd = cwd,
                        env = { env -> runtime.applyTo(env) },
                        // the transcript and the glossary are the user message — never on argv, which `ps` shows,
                        // and never in the instructions: both are text the daemon does not control
                        stdin = RefineContract.userMessage(text, glossary).toByteArray(Charsets.UTF_8),
                        stdoutLimit = MAX_STDOUT_BYTES,
                        stderrLimit = MAX_STDERR_BYTES,
                        deadlineMs = timeoutMs,
                    ),
                )
                val ms = (System.nanoTime() - t0) / 1_000_000
                when (run) {
                    MemoProcessResult.TimedOut -> done(RefineOutcome.TimedOut, "run", ms)
                    MemoProcessResult.StartFailed -> done(RefineOutcome.Unavailable, "start", ms)
                    MemoProcessResult.Aborted -> done(RefineOutcome.Failed, "aborted", ms)
                    is MemoProcessResult.Exited -> {
                        // sizes and codes only: stdout holds the transcript's fragments, stderr may echo either
                        log.info(
                            "refine claude exit=${run.exitCode} model=${model.modelName() ?: "default"} in ${ms}ms " +
                                "stdout=${run.stdout.size}B stderr=${run.stderr.size}B",
                        )
                        when {
                            run.exitCode != 0 -> done(RefineOutcome.Failed, "exit", ms)
                            run.stdoutTruncated -> done(RefineOutcome.Failed, "stdout-limit", ms)
                            else -> done(parseOutput(run.stdout.toString(Charsets.UTF_8)), "parse", ms)
                        }
                    }
                }
            } finally {
                runCatching { cwd.toFile().deleteRecursively() }
            }
        }

    private fun done(r: RefineOutcome, stage: String, ms: Long? = null): RefineOutcome {
        if (r !is RefineOutcome.Edits) log.info("refine claude stage=$stage outcome=${r::class.simpleName}${ms?.let { " in ${it}ms" } ?: ""}")
        return r
    }

    companion object {
        const val MODEL_ENV = "CC_POCKET_REFINE_CLAUDE_MODEL"
        const val DEFAULT_MODEL = "sonnet"
        const val EFFORT = "low"
        const val MAX_STDOUT_BYTES = 64 * 1024
        const val MAX_STDERR_BYTES = 16 * 1024

        internal fun buildArgv(exe: String, systemPrompt: String, modelArgs: List<String>): List<String> = buildList {
            add(exe)
            add("--print")
            add("--output-format"); add("json")
            add("--json-schema"); add(RefineContract.SCHEMA)
            addAll(modelArgs)
            // tool-less and MCP-less as single tokens (see ClaudeFeishuPromptReviewer.buildArgv for why)
            add("--tools=")
            add("--strict-mcp-config")
            add("--safe-mode")
            add("--disable-slash-commands")
            add("--no-session-persistence")
            add("--system-prompt"); add(systemPrompt)
        }

        /**
         * `--model` only when it is known to resolve (see the class doc), `--effort low` always. An override that
         * could read as a flag, or that carries whitespace or control characters, is ignored rather than passed.
         */
        internal fun modelArgs(override: String?, nativeLogin: Boolean): List<String> {
            val explicit = override?.trim()?.takeIf { m ->
                m.isNotEmpty() && !m.startsWith("-") && m.none { it.isWhitespace() || it.isISOControl() }
            }
            val model = explicit ?: DEFAULT_MODEL.takeIf { nativeLogin }
            return buildList {
                if (model != null) { add("--model"); add(model) }
                add("--effort"); add(EFFORT)
            }
        }

        private fun List<String>.modelName(): String? = indexOf("--model").takeIf { it >= 0 }?.let { getOrNull(it + 1) }

        /**
         * Native login = no API preset is active (#113) and no third-party base URL is configured in the daemon's
         * environment or the claude user settings (#139) — the two routes on which a Claude model alias may not
         * exist. Reads config only, never launches anything; when it cannot tell, it answers "not native", which
         * just leaves the model to the CLI.
         */
        fun isNativeLogin(runtime: ClaudeRuntime): Boolean {
            val preset = runCatching { runtime.presetEnv() }.getOrElse { return false }
            return isNativeLogin(
                presetActive = !preset.isNullOrEmpty(),
                gatewayBaseUrl = GatewayDetector.resolve(
                    presetBaseUrl = preset?.get(PresetEnv.BASE_URL),
                    userConfigDir = runtime.configDir,
                ),
            )
        }

        internal fun isNativeLogin(presetActive: Boolean, gatewayBaseUrl: String?): Boolean =
            !presetActive && gatewayBaseUrl == null

        /**
         * The CLI's `--output-format json` envelope → the model's replacement list. Only `structured_output` counts —
         * the assistant's free text is never parsed for JSON. An error envelope, unreadable output and a shape outside
         * [RefineContract.SCHEMA] all mean the call failed; judging the edits themselves is the validator's job.
         */
        internal fun parseOutput(stdout: String): RefineOutcome {
            if (stdout.encodeToByteArray().size > MAX_STDOUT_BYTES) return RefineOutcome.Failed
            val outer = runCatching { Json.parseToJsonElement(stdout) }.getOrNull() as? JsonObject
                ?: return RefineOutcome.Failed
            // an error envelope on exit 0 is a failed call, not a malformed answer
            if ((outer["is_error"] as? JsonPrimitive)?.takeIf { !it.isString }?.content == "true") return RefineOutcome.Failed
            val edits = RefineContract.parseEdits(outer["structured_output"]) ?: return RefineOutcome.Failed
            return RefineOutcome.Edits(edits)
        }
    }
}
