package dev.ccpocket.daemon.transcribe

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WhisperTranscriberTest {

    private fun tmp(): Path = Files.createTempDirectory("ccp-whisper")

    // ── args ────────────────────────────────────────────────────

    @Test
    fun buildArgs_has_stable_flag_set_and_txt_output() {
        val args = WhisperTranscriber.buildArgs(Path.of("/m/ggml-small.bin"), Path.of("/t/in.wav"), Path.of("/t/out"), "cc-pocket, main")
        assertEquals(
            listOf(
                "-m", "/m/ggml-small.bin", "-l", "auto", "--no-timestamps", "-np",
                "-otxt", "-of", "/t/out", "--prompt", "cc-pocket, main", "-f", "/t/in.wav",
            ),
            args,
        )
    }

    @Test
    fun buildArgs_honors_pinned_language() {
        val args = WhisperTranscriber.buildArgs(Path.of("/m"), Path.of("/w"), Path.of("/o"), "", lang = "zh")
        assertEquals("zh", args[args.indexOf("-l") + 1])
    }

    @Test
    fun buildArgs_omits_empty_prompt() {
        val args = WhisperTranscriber.buildArgs(Path.of("/m"), Path.of("/w"), Path.of("/o"), "")
        assertTrue("--prompt" !in args)
    }

    // ── term prompt ─────────────────────────────────────────────

    @Test
    fun buildPrompt_includes_dirname_branch_and_toplevel_names() {
        val wd = tmp().resolve("cc-pocket").createDirectories()
        wd.resolve(".git").createDirectories()
        wd.resolve(".git/HEAD").writeText("ref: refs/heads/feat-voice\n")
        wd.resolve("daemon").createDirectories()
        wd.resolve("README.md").writeText("x")
        val p = WhisperTranscriber.buildPrompt(wd)
        assertEquals(
            "以下是 cc-pocket 项目的中英混合开发口述，英文单词一律保留英文原文不音译，例如：hello，帮我 review 一下这个 function，" +
                "可能提到 feat-voice、README.md、daemon、iOS、Whisper、GitHub、API 等术语。",
            p,
        )
    }

    @Test
    fun buildPrompt_caps_at_200_chars_and_handles_null_workdir() {
        val wd = tmp().resolve("proj").createDirectories()
        repeat(60) { wd.resolve("rather-long-module-name-$it").createDirectories() }
        assertTrue(WhisperTranscriber.buildPrompt(wd).length <= 200)
        assertEquals("", WhisperTranscriber.buildPrompt(null))
    }

    @Test
    fun buildPrompt_survives_detached_head_and_missing_git() {
        val wd = tmp().resolve("p").createDirectories()
        wd.resolve(".git").createDirectories()
        wd.resolve(".git/HEAD").writeText("0123456789abcdef0123456789abcdef01234567\n") // detached: no ref line
        val p = WhisperTranscriber.buildPrompt(wd)
        assertEquals(
            "以下是 p 项目的中英混合开发口述，英文单词一律保留英文原文不音译，例如：hello，帮我 review 一下这个 function，" +
                "可能提到 iOS、Whisper、GitHub、API 等术语。",
            p,
        ) // project terms collapse to just the seeds; no crash, no hash leaked
    }

    // ── transcript cleanup ──────────────────────────────────────

    @Test
    fun cleanTranscript_strips_sound_events_and_blank_audio() {
        val raw = """
            [BLANK_AUDIO]
            rename the relay module
            (wind blowing)
            and run the tests
            [Music]
        """.trimIndent()
        assertEquals("rename the relay module and run the tests", WhisperTranscriber.cleanTranscript(raw))
    }

    @Test
    fun cleanTranscript_collapses_consecutive_duplicate_lines() {
        val raw = "thank you\nthank you\nthank you\nbye"
        assertEquals("thank you bye", WhisperTranscriber.cleanTranscript(raw))
    }

    @Test
    fun cleanTranscript_keeps_brackets_inside_sentences() {
        val raw = "rename foo[0] to bar (the old one)"
        assertEquals(raw, WhisperTranscriber.cleanTranscript(raw))
    }

    // ── discovery ───────────────────────────────────────────────

    @Test
    fun resolveWhisper_honors_explicit_path_and_rejects_missing() {
        val dir = tmp()
        val bin = dir.resolve("whisper-cli")
        Files.writeString(bin, "#!/bin/sh\n")
        bin.toFile().setExecutable(true)
        assertEquals(bin, WhisperTranscriber.resolveWhisper(explicit = bin.toString()))
        // explicit-but-absent falls through to PATH/fallback scan; a bogus dir won't match
        assertNull(WhisperTranscriber.resolveWhisper(explicit = dir.resolve("nope").toString())?.takeIf { it.parent == dir })
    }

    @Test
    fun resolveModel_prefers_best_quality_tier_then_any_then_null() {
        val home = tmp()
        assertNull(WhisperTranscriber.resolveModel(home))
        val models = home.resolve(".cache/cc-pocket/models").createDirectories()
        Files.writeString(models.resolve("ggml-tiny.bin"), "x")
        assertEquals("ggml-tiny.bin", WhisperTranscriber.resolveModel(home)?.fileName?.toString())
        Files.writeString(models.resolve("ggml-small.bin"), "x")
        assertEquals("ggml-small.bin", WhisperTranscriber.resolveModel(home)?.fileName?.toString())
        Files.writeString(models.resolve("ggml-large-v3-turbo-q5_0.bin"), "x")
        assertEquals("ggml-large-v3-turbo-q5_0.bin", WhisperTranscriber.resolveModel(home)?.fileName?.toString())
        // unknown names still resolve (rank below every known tier, above nothing)
        val only = tmp().also { it.resolve(".cache/cc-pocket/models").createDirectories() }
        Files.writeString(only.resolve(".cache/cc-pocket/models/ggml-custom.bin"), "x")
        assertEquals("ggml-custom.bin", WhisperTranscriber.resolveModel(only)?.fileName?.toString())
    }
}
