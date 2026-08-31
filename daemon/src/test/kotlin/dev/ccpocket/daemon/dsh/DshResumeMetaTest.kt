package dev.ccpocket.daemon.dsh

import com.github.luben.zstd.Zstd
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Issue #320, the DISK half. The live wire already teaches a running dsh session its model, effort and
 * context window ([DshRuntimeMetaTest]) — but a RESUMED one emits none of those frames until it happens to
 * run another turn, so its header read "default" with no denominator for as long as the user just scrolled.
 *
 * These pin the transcript twin of that translation: the same three records, read off the same store layout
 * dsh writes (concatenated zstd frames under `~/.dsh/sessions`), through the real [DshBackend] hooks.
 *
 * The negative tests carry as much weight as the positive one: an absent fact must resume as UNKNOWN. A
 * window guessed from a model name, or a level lifted out of the local config, would be a number the phone
 * renders as fact and nobody could tell was invented.
 */
class DshResumeMetaTest {

    private fun store(): Path =
        Files.createTempDirectory("dsh-resume-meta").also { it.toFile().deleteOnExit() }

    /** Write one session the way dsh lays it out: header frame, then one frame of events. */
    private fun session(root: Path, cwd: String, id: String, events: String) {
        val dir = root.resolve(DshPaths.projectKey(cwd)).resolve(DshPaths.encodeSessionId(id))
        dir.createDirectories()
        val header =
            """{"type":"session","version":0,"id":"$id","cwd":"$cwd","createdAt":1700000000000,"delegationDepth":0}""" + "\n"
        val bytes = Zstd.compress(header.toByteArray()) +
            (if (events.isEmpty()) ByteArray(0) else Zstd.compress(events.toByteArray()))
        Files.write(dir.resolve("session.jsonl.zstd"), bytes)
    }

    private fun backend(root: Path) = DshBackend(null).apply { bindStoreRootForTest(root) }

    // ---- the real record shapes, copied off a local ~/.dsh/sessions/**/session.jsonl.zstd ----

    private fun requestContext(model: String, window: Long) =
        """{"type":"request/context","seq":1,"time":1,"data":{"provider":"deepseek-official","model":"$model","contextWindow":$window}}""" + "\n"

    private fun requestHeader(model: String, effort: String) =
        """{"type":"request/header","seq":2,"time":2,"data":{"header":{"config":{"provider":"deepseek-official","model":"$model","maxTokens":256000,"reasoningEffort":"$effort"}}}}""" + "\n"

    private fun assistantMessage(model: String) =
        """{"type":"assistant/message","seq":3,"time":3,"data":{"turn":1,"step":1,"message":{"role":"assistant","content":[{"type":"text","text":"hi"}],"source":{"kind":"model","provider":"deepseek-official","model":"$model"},"id":"m1"}}}""" + "\n"

    private fun userMessage(text: String) =
        """{"type":"user/message","seq":9,"time":9,"data":{"id":"u1","role":"user","content":[{"type":"text","text":"$text"}],"source":"user"}}""" + "\n"

    /**
     * A session that changed model AND level mid-chat: the resume must announce what it IS, not what it was
     * opened as. Every one of the three sources appears twice here, so a first-wins reader fails all three
     * assertions rather than accidentally passing on the one field it happened to order right.
     */
    @Test
    fun the_resume_reads_the_last_state_the_transcript_recorded_not_the_first() {
        val root = store()
        session(
            root, CWD, SESSION,
            requestHeader("deepseek-v4", "low") +
                requestContext("deepseek-v4", 128_000) +
                assistantMessage("deepseek-v4") +
                userMessage("switch, please") +
                requestHeader("deepseek-v4-flash", "high") +
                requestContext("deepseek-v4-flash", 1_000_000) +
                assistantMessage("deepseek-v4-flash"),
        )
        val backend = backend(root)
        assertEquals("deepseek-v4-flash", backend.resumeModel(CWD, SESSION))
        assertEquals(1_000_000L, backend.resumeContextWindow(CWD, SESSION))
        assertEquals("high", backend.resumeEffort(CWD, SESSION))
    }

    /** `assistant/message` is the only one of the three that names who ACTUALLY answered — a session whose
     *  request frames scrolled out of a bounded read still resumes on the right model through it. */
    @Test
    fun the_answering_model_alone_is_enough_to_name_the_model() {
        val root = store()
        session(root, CWD, SESSION, userMessage("hello") + assistantMessage("deepseek-v4-flash"))
        val backend = backend(root)
        assertEquals("deepseek-v4-flash", backend.resumeModel(CWD, SESSION))
        assertNull(backend.resumeContextWindow(CWD, SESSION)) // nothing said a window — so we say nothing
        assertNull(backend.resumeEffort(CWD, SESSION))
    }

    /**
     * ⚠️ THE TRAP, same one [DshRuntimeMetaTest] guards on the live side: `config.maxTokens` (256000) is the
     * OUTPUT cap of the very model whose context window is 1,000,000. Reading it as the window would
     * understate occupancy four-fold — and on the resume path nobody would ever see the frame that corrects it.
     */
    @Test
    fun maxTokens_is_never_mistaken_for_the_context_window() {
        val root = store()
        session(root, CWD, SESSION, requestHeader("deepseek-v4-flash", "high"))
        val backend = backend(root)
        assertEquals("high", backend.resumeEffort(CWD, SESSION))
        assertNull(backend.resumeContextWindow(CWD, SESSION), "maxTokens is the output cap, never the window")
    }

    /** A chat that only ever exchanged text says nothing about how it ran. Unknown must stay unknown: the
     *  phone renders a blank segment, which is honest, where an invented default would not be. */
    @Test
    fun a_transcript_without_metadata_resumes_as_unknown() {
        val root = store()
        session(root, CWD, SESSION, userMessage("hello") + userMessage("still here"))
        val backend = backend(root)
        assertNull(backend.resumeModel(CWD, SESSION))
        assertNull(backend.resumeContextWindow(CWD, SESSION))
        assertNull(backend.resumeEffort(CWD, SESSION))
    }

    /** dsh's own "this record carries no user-visible meaning" marker is respected here exactly as the live
     *  path respects it — otherwise a discarded request could name the session's model. */
    @Test
    fun an_ignorable_record_is_not_evidence() {
        val root = store()
        session(
            root, CWD, SESSION,
            """{"type":"request/context","seq":1,"time":1,"ignorable":true,"data":{"model":"ghost","contextWindow":4096}}""" + "\n",
        )
        val backend = backend(root)
        assertNull(backend.resumeModel(CWD, SESSION))
        assertNull(backend.resumeContextWindow(CWD, SESSION))
    }

    /** An id that resolves to nothing (deleted store, wrong cwd, a hostile id) is not an error — it is three
     *  nulls, and the open proceeds with an unknown header instead of failing. */
    @Test
    fun an_unknown_session_yields_nothing_rather_than_throwing() {
        val root = store()
        session(root, CWD, SESSION, requestContext("deepseek-v4-flash", 1_000_000))
        val backend = backend(root)
        assertNull(backend.resumeModel(CWD, "no-such-session"))
        assertNull(backend.resumeContextWindow(CWD, "no-such-session"))
        assertNull(backend.resumeEffort(CWD, "no-such-session"))
    }

    /** The store is re-read when the file MOVES, not once per daemon lifetime: a session that keeps running
     *  while the phone reopens it must not resume on a cached, stale window. */
    @Test
    fun a_grown_transcript_is_re_read_rather_than_served_from_the_cache() {
        val root = store()
        session(root, CWD, SESSION, requestContext("deepseek-v4", 128_000))
        val backend = backend(root)
        assertEquals(128_000L, backend.resumeContextWindow(CWD, SESSION))

        val file = root.resolve(DshPaths.projectKey(CWD)).resolve(DshPaths.encodeSessionId(SESSION))
            .resolve("session.jsonl.zstd")
        val before = Files.getLastModifiedTime(file).toMillis()
        Files.write(
            file,
            Files.readAllBytes(file) + Zstd.compress(requestContext("deepseek-v4-flash", 1_000_000).toByteArray()),
        )
        // the cache key is (path, mtime); a same-millisecond rewrite would make this test lie about what it proves
        Files.setLastModifiedTime(file, java.nio.file.attribute.FileTime.fromMillis(before + 2_000))

        assertEquals(1_000_000L, backend.resumeContextWindow(CWD, SESSION))
        assertEquals("deepseek-v4-flash", backend.resumeModel(CWD, SESSION))
    }

    private companion object {
        const val CWD = "/work/dsh-resume"
        const val SESSION = "session-resume-1"
    }
}
