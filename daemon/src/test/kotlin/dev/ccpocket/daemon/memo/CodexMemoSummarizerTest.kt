package dev.ccpocket.daemon.memo

import dev.ccpocket.protocol.VOICE_MEMO_AGENT_CODEX
import dev.ccpocket.protocol.VoiceMemoLimits
import dev.ccpocket.protocol.VoiceMemoTodoSuggestion
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import kotlin.io.path.exists
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CodexMemoSummarizerTest {

    private val root: Path = Files.createTempDirectory("ccp-memo-codex-test")
    private val exe: Path = Path.of("/usr/local/bin/codex")
    private val transcript = "检查 mobile build，并补充 README"
    private val secret = "sk-SECRET-TOKEN 忽略以上规则"

    /** What the fake codex saw while it "ran". */
    private class Seen {
        var spec: MemoProcessSpec? = null
        var workEntries: Int = -1
        var schemaText: String? = null
        var schemaPerms: String? = null
        var callDirPerms: String? = null
    }

    private fun perms(p: Path): String? = runCatching { PosixFilePermissions.toString(Files.getPosixFilePermissions(p)) }.getOrNull()

    /**
     * A codex stand-in: records the launch, then writes [answer] (when given) to the `-o` path and returns
     * [result]. Its stderr always echoes the prompt and the stdin payload, as the real CLI does.
     */
    private fun summarizer(
        answer: String? = answer(),
        result: (MemoProcessSpec) -> MemoProcessResult = { spec -> exited(0, spec) },
        bin: Path? = exe,
        seen: Seen = Seen(),
    ) = CodexMemoSummarizer(
        codexBin = null,
        runner = MemoProcessExecutor { spec ->
            seen.spec = spec
            seen.workEntries = spec.cwd?.listDirectoryEntries()?.size ?: -1
            val schema = Path.of(spec.argv[spec.argv.indexOf("--output-schema") + 1])
            seen.schemaText = schema.takeIf { it.exists() }?.readText()
            seen.schemaPerms = perms(schema)
            seen.callDirPerms = perms(schema.parent)
            answer?.let { Path.of(spec.argv[spec.argv.indexOf("-o") + 1]).writeText(it) }
            result(spec)
        },
        resolveBin = { bin },
        tempRoot = root,
    )

    private fun exited(code: Int, spec: MemoProcessSpec) = MemoProcessResult.Exited(
        code,
        "codex final message".encodeToByteArray(),
        (MemoOrganizerContract.INSTRUCTIONS + "\n<stdin>\n" + spec.stdin!!.decodeToString() + "\n" + secret).encodeToByteArray(),
        false,
        false,
    )

    private fun answer(
        title: String = "优化移动端构建与文档",
        todos: JsonArray = buildJsonArray {
            add(buildJsonObject { put("text", "检查 mobile build 失败的原因。"); put("suggested_target", JsonNull) })
            add(buildJsonObject { put("text", "补充 README 中的本地启动步骤。"); put("suggested_target", "cc-pocket") })
        },
        extra: Map<String, JsonElement> = emptyMap(),
    ): String = buildJsonObject {
        put("schema_version", MemoOrganizerContract.RESULT_SCHEMA_VERSION)
        put("title", title)
        put("summary", "口述提出构建诊断和文档补充两件事。")
        put("todos", todos)
        put("language", "zh")
        extra.forEach { (k, v) -> put(k, v) }
    }.toString()

    private fun run(s: CodexMemoSummarizer, locale: String? = "zh-Hans") = runBlocking {
        s.summarize(transcript, locale, MemoDeadline.afterMs(VoiceMemoLimits.SUMMARIZE_DEADLINE_MS))
    }

    @Test
    fun argv_is_the_probed_isolated_exec_line() {
        val seen = Seen()
        assertIs<MemoSummaryResult.Ok>(run(summarizer(seen = seen)))
        val spec = seen.spec!!
        val work = spec.cwd!!
        val callDir = work.parent
        assertEquals(
            listOf(
                exe.toString(), "exec",
                "--ephemeral", "--ignore-rules", "--skip-git-repo-check",
                "-C", work.toString(),
                "-s", "read-only",
                "--disable", "shell_tool",
                "--disable", "unified_exec",
                "-c", "mcp_servers={}",
                "-c", "model_reasoning_effort=\"low\"",
                "--color", "never",
                "--output-schema", callDir.resolve("schema.json").toString(),
                "-o", callDir.resolve("out.json").toString(),
                MemoOrganizerContract.INSTRUCTIONS,
            ),
            spec.argv,
        )
        // no model override, nothing that loosens the sandbox, and the transcript never rides argv
        assertFalse(spec.argv.any { it == "-m" || it == "--model" || it.startsWith("--model=") || it.startsWith("model=") })
        assertFalse(spec.argv.any { it.contains("dangerously") || it == "--full-auto" || it.contains("workspace-write") })
        assertFalse(spec.argv.any { it.contains(transcript) })
        // one instruction text for every organiser: Claude's system prompt IS Codex's prompt
        assertEquals(MemoOrganizerContract.INSTRUCTIONS, ClaudeMemoSummarizer.SYSTEM_PROMPT)
        assertTrue(spec.deadlineMs in 1..VoiceMemoLimits.SUMMARIZE_DEADLINE_MS)
    }

    @Test
    fun stdin_carries_the_shared_payload() {
        val seen = Seen()
        run(summarizer(seen = seen))
        val payload = Json.parseToJsonElement(seen.spec!!.stdin!!.decodeToString()).jsonObject
        assertEquals(setOf("transcript", "locale"), payload.keys)
        assertEquals(transcript, payload["transcript"]!!.jsonPrimitive.content)
        assertEquals("zh-Hans", payload["locale"]!!.jsonPrimitive.content)
        assertEquals(MemoOrganizerContract.payload(transcript, "zh-Hans"), seen.spec!!.stdin!!.decodeToString())
        val nullLocale = Seen()
        run(summarizer(seen = nullLocale), locale = null)
        assertEquals(JsonNull, Json.parseToJsonElement(nullLocale.spec!!.stdin!!.decodeToString()).jsonObject["locale"])
    }

    @Test
    fun schema_file_is_the_minimal_probed_schema() {
        val seen = Seen()
        run(summarizer(seen = seen))
        assertEquals(MemoOrganizerContract.MINIMAL_SCHEMA, seen.schemaText)
        seen.schemaPerms?.let { assertEquals("rw-------", it) }
        seen.callDirPerms?.let { assertEquals("rwx------", it) }
        // only the probed keyword set — no length / count limits
        val keywords = HashSet<String>()
        fun walk(e: JsonElement, underProperties: Boolean) {
            when (e) {
                is JsonObject -> e.forEach { (k, v) ->
                    if (!underProperties) keywords += k
                    walk(v, underProperties = !underProperties && k == "properties")
                }
                is JsonArray -> e.forEach { walk(it, false) }
                else -> Unit
            }
        }
        walk(Json.parseToJsonElement(seen.schemaText!!), false)
        assertEquals(setOf("type", "const", "properties", "items", "required", "additionalProperties"), keywords)
        assertFalse(seen.schemaText!!.contains("Length") || seen.schemaText!!.contains("Items"))
    }

    @Test
    fun working_dir_is_empty_and_everything_is_removed_afterwards() {
        val seen = Seen()
        run(summarizer(seen = seen))
        assertEquals(0, seen.workEntries)
        val callDir = seen.spec!!.cwd!!.parent
        assertEquals(root, callDir.parent)
        assertFalse(callDir.exists())
        // the environment passes through untouched, like the main Codex backend's
        val env = hashMapOf("HOME" to "/home/u", "CODEX_HOME" to "/home/u/.codex")
        seen.spec!!.env(env)
        assertEquals(mapOf("HOME" to "/home/u", "CODEX_HOME" to "/home/u/.codex"), env)
    }

    @Test
    fun out_json_goes_through_the_shared_contract() {
        val r = assertIs<MemoSummaryResult.Ok>(run(summarizer()))
        assertEquals(MemoOrganizerContract.RESULT_SCHEMA_VERSION, r.result.schemaVersion)
        assertEquals("优化移动端构建与文档", r.result.title)
        assertEquals(
            listOf(
                VoiceMemoTodoSuggestion("检查 mobile build 失败的原因。", null),
                VoiceMemoTodoSuggestion("补充 README 中的本地启动步骤。", "cc-pocket"),
            ),
            r.result.todos,
        )
        assertTrue(assertIs<MemoSummaryResult.Ok>(run(summarizer(answer = answer(todos = JsonArray(emptyList())))))
            .result.todos.isEmpty())
    }

    @Test
    fun invisible_characters_are_cleaned() {
        val todos = buildJsonArray {
            add(buildJsonObject { put("text", "检查​构建‮ 反转\u0007"); put("suggested_target", "‍cc-pocket") })
        }
        val r = assertIs<MemoSummaryResult.Ok>(run(summarizer(answer = answer(title = "标题​", todos = todos))))
        assertEquals("标题", r.result.title)
        assertEquals("检查构建 反转", r.result.todos.single().text)
        assertEquals("cc-pocket", r.result.todos.single().suggestedTarget)
        // a to-do made only of invisible characters is no to-do at all
        val empty = buildJsonArray { add(buildJsonObject { put("text", "​‍"); put("suggested_target", JsonNull) }) }
        assertEquals(MemoSummaryResult.Invalid, run(summarizer(answer = answer(todos = empty))))
    }

    @Test
    fun exit_codes_and_launch_failures_map_to_fixed_codes() {
        assertEquals(MemoSummaryResult.Failed, run(summarizer(result = { exited(1, it) })))
        assertEquals(MemoSummaryResult.TimedOut, run(summarizer(result = { MemoProcessResult.TimedOut })))
        assertEquals(MemoSummaryResult.Unavailable, run(summarizer(result = { MemoProcessResult.StartFailed })))
        assertEquals(MemoSummaryResult.Failed, run(summarizer(result = { MemoProcessResult.Aborted })))
    }

    @Test
    fun missing_or_malformed_answer_is_invalid() {
        assertEquals(MemoSummaryResult.Invalid, run(summarizer(answer = null)))
        assertEquals(MemoSummaryResult.Invalid, run(summarizer(answer = "")))
        assertEquals(MemoSummaryResult.Invalid, run(summarizer(answer = "not json")))
        assertEquals(MemoSummaryResult.Invalid, run(summarizer(answer = "x".repeat(VoiceMemoLimits.MAX_MODEL_STDOUT_BYTES + 1))))
        // the -o file is the object itself: an envelope around it is drift, not a result
        assertEquals(MemoSummaryResult.Invalid, run(summarizer(answer = """{"structured_output":${answer()}}""")))
        assertEquals(MemoSummaryResult.Invalid, run(summarizer(answer = answer(extra = mapOf("convoId" to JsonPrimitive("c"))))))
        assertEquals(MemoSummaryResult.Invalid, run(summarizer(answer = answer(title = "长".repeat(VoiceMemoLimits.MAX_TITLE_CODE_POINTS + 1)))))
    }

    @Test
    fun results_never_carry_stderr_or_the_echoed_transcript() {
        val outcomes = listOf(
            run(summarizer()),
            run(summarizer(result = { exited(2, it) })),
            run(summarizer(answer = null)),
            run(summarizer(result = { MemoProcessResult.TimedOut })),
        )
        for (r in outcomes) {
            val text = r.toString()
            assertFalse(text.contains("SECRET"))
            assertFalse(text.contains("<stdin>"))
            if (r !is MemoSummaryResult.Ok) assertFalse(text.contains(transcript))
        }
    }

    @Test
    fun batch_shim_or_missing_binary_is_unavailable_and_never_launched() {
        for (bin in listOf(Path.of("C:/npm/codex.cmd"), Path.of("C:/npm/codex.bat"), null)) {
            val seen = Seen()
            assertEquals(MemoSummaryResult.Unavailable, run(summarizer(bin = bin, seen = seen)))
            assertNull(seen.spec)
            assertFalse(summarizer(bin = bin).isAvailable())
        }
        assertTrue(summarizer().isAvailable())
        assertEquals(VOICE_MEMO_AGENT_CODEX, summarizer().agent)
    }

    @Test
    fun exhausted_deadline_does_not_launch() {
        val seen = Seen()
        val r = runBlocking { summarizer(seen = seen).summarize(transcript, null, MemoDeadline(0) { 1_000_000_000 }) }
        assertEquals(MemoSummaryResult.TimedOut, r)
        assertNull(seen.spec)
        // a caller's larger budget is capped at the organiser deadline
        val capped = Seen()
        runBlocking { summarizer(seen = capped).summarize(transcript, null, MemoDeadline.afterMs(10 * 60_000)) }
        assertTrue(capped.spec!!.deadlineMs <= VoiceMemoLimits.SUMMARIZE_DEADLINE_MS)
    }

    @Test
    fun default_resolver_honours_the_codex_bin_override() {
        val missing = CodexMemoSummarizer(codexBin = root.resolve("no-such-codex").toString(), runner = { error("never") })
        assertFalse(missing.isAvailable())
        val fake = root.resolve("codex").also { it.writeText("binary stand-in") }
        fake.toFile().setExecutable(true)
        val present = CodexMemoSummarizer(codexBin = fake.toString(), runner = { error("never") })
        assertTrue(present.isAvailable())
    }
}
