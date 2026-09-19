package dev.ccpocket.daemon.dsh

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.io.path.createTempDirectory
import kotlin.io.path.exists
import kotlin.io.path.listDirectoryEntries
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The model probe's three lifecycles — process, scratch directory, persisted session (issue #387).
 *
 * WHY THESE ARE FIXTURE TESTS AND NOT A LIVE dsh RUN: the behaviour under test is a DELETE inside the
 * user's own session store. The interesting cases are exactly the ones a live run will never produce on
 * demand — a colliding project key, a neighbouring real session, a header that names a different cwd, a
 * child that refuses to die, a launch that throws. Each one is injected here, and every "must not touch"
 * assertion compares BYTES, not existence.
 *
 * The on-disk shapes are taken from dsh 0.1.2-rc.1's `dsh-session-persistence-jsonl`: one session
 * directory holds exactly one `session[.vN].jsonl[.zstd]`, the header line is
 * `{type:"session",version,id,createdAt,cwd,delegationDepth}`, and an ACP `session/new` materializes that
 * header (and nothing else) for a session nobody has spoken in. Fixtures use the uncompressed spelling,
 * which the same reader accepts.
 */
class DshProbeSessionTest {

    // ── fixtures ────────────────────────────────────────────────────────────────────────────────────

    private val isWindows = System.getProperty("os.name").lowercase().contains("win")

    /** A plausible absolute cwd that need not exist: [DshPaths.projectKey] is pure string work, and the
     *  header's cwd is compared as a path, so nothing here touches the real filesystem. */
    private fun cwd(vararg parts: String): String =
        (listOf(if (isWindows) "C:\\ccp387" else "/tmp/ccp387") + parts).joinToString(File.separator)

    private fun header(id: String, cwd: String): String =
        """{"type":"session","version":0,"id":"$id","createdAt":1,""" +
            """"cwd":${JsonPrimitive(cwd)},"delegationDepth":0}"""

    /** Write a session exactly where dsh would put it. [extra] lines make it a session with content. */
    private fun writeSession(
        root: Path,
        cwd: String,
        id: String,
        vararg extra: String,
        headerCwd: String = cwd,
        fileName: String = "session.jsonl",
    ): Path {
        val dir = root.resolve(DshPaths.projectKey(cwd)).resolve(DshPaths.encodeSessionId(id))
        Files.createDirectories(dir)
        val text = (listOf(header(id, headerCwd)) + extra).joinToString("\n") + "\n"
        Files.writeString(dir.resolve(fileName), text)
        return dir
    }

    /** What dsh 0.1.2-rc.1 really writes under the header of a promptless ACP session — verified on a
     *  live run against the real store, not assumed from the persistence README. */
    private val bootRecords = arrayOf(
        """{"type":"permission/preset","seq":0,"time":1,"data":{"preset":"workspace-write"}}""",
        """{"type":"sandbox/mode","seq":1,"time":1,"data":{"mode":"workspace-write"}}""",
        """{"type":"approval/policy","seq":2,"time":1,"data":{"policy":"ask"}}""",
    )

    /** One line of somebody's conversation, in dsh's own vocabulary. */
    private val USER_MESSAGE = """{"type":"user/message","seq":3,"time":1,"data":{"text":"mine"}}"""

    private fun root(): Path = createTempDirectory("ccp-387-store")

    private fun snapshot(dir: Path): Map<String, ByteArray> =
        dir.listDirectoryEntries().associate { it.fileName.toString() to Files.readAllBytes(it) }

    private fun assertUnchanged(dir: Path, before: Map<String, ByteArray>) {
        assertTrue(dir.exists(), "a session that was not ours was removed")
        val after = snapshot(dir)
        assertEquals(before.keys, after.keys, "a file appeared or vanished in someone else's session")
        for ((name, bytes) in before) {
            assertTrue(bytes.contentEquals(after.getValue(name)), "someone else's $name was rewritten")
        }
    }

    // ── the happy path ──────────────────────────────────────────────────────────────────────────────

    @Test
    fun the_probes_own_empty_session_is_removed_with_the_project_directory_it_was_alone_in() {
        val store = root()
        val probeCwd = cwd("scratch")
        // Written exactly as a live dsh writes one: header + the boot settings batch, no conversation.
        val dir = writeSession(store, probeCwd, "3cad11be-de2e-4e9f-a70a-14593e045408", *bootRecords)

        val outcome = DshProbeSessionCleanup.remove("3cad11be-de2e-4e9f-a70a-14593e045408", probeCwd, store)

        assertEquals(DshProbeSessionCleanup.Outcome.Removed, outcome)
        assertFalse(dir.exists())
        assertFalse(dir.parent.exists(), "the project directory the probe made for itself must go too")
        assertTrue(store.exists(), "the store root is never a delete target")
    }

    @Test
    fun a_session_that_is_already_gone_is_absent_not_a_failure() {
        val store = root()
        assertEquals(
            DshProbeSessionCleanup.Outcome.Absent,
            DshProbeSessionCleanup.remove("session-never-written", cwd("scratch"), store),
        )
    }

    @Test
    fun a_custom_dsh_home_is_where_the_sweep_looks() {
        val elsewhere = createTempDirectory("ccp-387-custom-home").resolve("sessions")
        Files.createDirectories(elsewhere)
        val probeCwd = cwd("scratch")
        val dir = writeSession(elsewhere, probeCwd, "id-custom-home")
        // The default root is the real ~/.dsh; nothing there may be touched by a test.
        assertEquals(DshProbeSessionCleanup.Outcome.Removed, DshProbeSessionCleanup.remove("id-custom-home", probeCwd, elsewhere))
        assertFalse(dir.exists())
    }

    // ── refusals: everything that is not provably ours ──────────────────────────────────────────────

    @Test
    fun a_real_session_next_to_the_probes_in_the_same_project_directory_survives_byte_for_byte() {
        val store = root()
        val probeCwd = cwd("scratch")
        val probe = writeSession(store, probeCwd, "probe-id")
        val neighbour = writeSession(store, probeCwd, "real-id", USER_MESSAGE)
        val before = snapshot(neighbour)

        assertEquals(DshProbeSessionCleanup.Outcome.Removed, DshProbeSessionCleanup.remove("probe-id", probeCwd, store))

        assertFalse(probe.exists())
        assertUnchanged(neighbour, before)
        assertTrue(neighbour.parent.exists(), "a project directory with sessions left in it must not be pruned")
    }

    /**
     * [DshPaths.projectKey] COLLIDES: `…/a/b` and `…/a-b` normalize to the same directory name, so the
     * probe's project directory can legitimately contain a stranger's session that never shared its cwd.
     * The header's verbatim cwd is the only thing that separates them.
     */
    @Test
    fun a_stranger_sharing_the_probes_colliding_project_key_is_untouched() {
        val store = root()
        val probeCwd = cwd("a", "b")
        val strangerCwd = cwd("a-b")
        assertEquals(
            DshPaths.projectKey(probeCwd), DshPaths.projectKey(strangerCwd),
            "this test is pointless unless the two cwds really collide",
        )
        val probe = writeSession(store, probeCwd, "probe-id")
        val stranger = writeSession(store, strangerCwd, "stranger-id", USER_MESSAGE)
        val before = snapshot(stranger)

        assertEquals(DshProbeSessionCleanup.Outcome.Removed, DshProbeSessionCleanup.remove("probe-id", probeCwd, store))

        assertFalse(probe.exists())
        assertUnchanged(stranger, before)
    }

    @Test
    fun a_session_whose_header_names_a_different_cwd_is_refused() {
        val store = root()
        val probeCwd = cwd("models1")
        // Same directory, different truth: only the header decides.
        val dir = writeSession(store, probeCwd, "probe-id", headerCwd = cwd("models12"))
        val before = snapshot(dir)

        val outcome = DshProbeSessionCleanup.remove("probe-id", probeCwd, store)

        assertTrue(outcome is DshProbeSessionCleanup.Outcome.Refused, outcome.toString())
        assertUnchanged(dir, before)
    }

    @Test
    fun a_neighbouring_project_whose_cwd_merely_shares_a_prefix_is_untouched() {
        val store = root()
        val probeCwd = cwd("models1")
        val otherCwd = cwd("models12")
        val probe = writeSession(store, probeCwd, "probe-id")
        val other = writeSession(store, otherCwd, "other-id", USER_MESSAGE)
        val before = snapshot(other)

        assertEquals(DshProbeSessionCleanup.Outcome.Removed, DshProbeSessionCleanup.remove("probe-id", probeCwd, store))

        assertFalse(probe.exists())
        assertUnchanged(other, before)
    }

    @Test
    fun a_session_with_recorded_activity_is_never_removed() {
        val store = root()
        val probeCwd = cwd("scratch")
        // Same id, same cwd — and still not ours to delete, because somebody spoke in it.
        val dir = writeSession(store, probeCwd, "probe-id", USER_MESSAGE)
        val before = snapshot(dir)

        val outcome = DshProbeSessionCleanup.remove("probe-id", probeCwd, store)

        assertEquals(DshProbeSessionCleanup.Outcome.Refused("session has recorded activity"), outcome)
        assertUnchanged(dir, before)
    }

    @Test
    fun a_header_carrying_a_different_id_is_refused() {
        val store = root()
        val probeCwd = cwd("scratch")
        val dir = store.resolve(DshPaths.projectKey(probeCwd)).resolve(DshPaths.encodeSessionId("probe-id"))
        Files.createDirectories(dir)
        Files.writeString(dir.resolve("session.jsonl"), header("someone-else", probeCwd) + "\n")
        val before = snapshot(dir)

        val outcome = DshProbeSessionCleanup.remove("probe-id", probeCwd, store)

        assertEquals(DshProbeSessionCleanup.Outcome.Refused("session header carries a different id"), outcome)
        assertUnchanged(dir, before)
    }

    @Test
    fun a_session_directory_holding_anything_unexpected_is_refused() {
        val store = root()
        val probeCwd = cwd("scratch")
        val dir = writeSession(store, probeCwd, "probe-id")
        Files.writeString(dir.resolve("notes.txt"), "not dsh's")
        val before = snapshot(dir)

        val outcome = DshProbeSessionCleanup.remove("probe-id", probeCwd, store)

        assertEquals(DshProbeSessionCleanup.Outcome.Refused("session directory holds an unexpected file"), outcome)
        assertUnchanged(dir, before)
    }

    @Test
    fun a_second_transcript_generation_marks_a_migrated_session_and_is_refused() {
        val store = root()
        val probeCwd = cwd("scratch")
        val dir = writeSession(store, probeCwd, "probe-id")
        Files.writeString(dir.resolve("session.v3.jsonl"), header("probe-id", probeCwd) + "\n")
        val before = snapshot(dir)

        val outcome = DshProbeSessionCleanup.remove("probe-id", probeCwd, store)

        assertEquals(DshProbeSessionCleanup.Outcome.Refused("session has more than one generation"), outcome)
        assertUnchanged(dir, before)
    }

    @Test
    fun a_crashed_materialization_that_left_only_a_sidecar_cannot_prove_ownership() {
        val store = root()
        val probeCwd = cwd("scratch")
        val dir = store.resolve(DshPaths.projectKey(probeCwd)).resolve(DshPaths.encodeSessionId("probe-id"))
        Files.createDirectories(dir)
        Files.writeString(dir.resolve("session.jsonl.zstd.abc123.tmp"), "half a frame")
        val before = snapshot(dir)

        val outcome = DshProbeSessionCleanup.remove("probe-id", probeCwd, store)

        assertEquals(
            DshProbeSessionCleanup.Outcome.Refused("session has no transcript to prove ownership"), outcome,
        )
        assertUnchanged(dir, before)
    }

    @Test
    fun a_session_directory_holding_a_sub_directory_is_refused() {
        val store = root()
        val probeCwd = cwd("scratch")
        val dir = writeSession(store, probeCwd, "probe-id")
        Files.createDirectories(dir.resolve("nested"))

        val outcome = DshProbeSessionCleanup.remove("probe-id", probeCwd, store)

        assertEquals(DshProbeSessionCleanup.Outcome.Refused("session directory holds a sub-directory"), outcome)
        assertTrue(dir.resolve("session.jsonl").exists())
    }

    @Test
    fun an_id_that_tries_to_walk_out_of_the_store_can_never_resolve_to_a_parent() {
        val store = root()
        val outside = store.parent.resolve("ccp-387-bystander")
        Files.createDirectories(outside)
        Files.writeString(outside.resolve("keep.txt"), "untouched")

        // encodeSessionId escapes `/ \ : .` — the traversal cannot even become a path segment.
        for (evil in listOf("..", ".", "../../ccp-387-bystander", "..\\..\\ccp-387-bystander")) {
            val outcome = DshProbeSessionCleanup.remove(evil, cwd("scratch"), store)
            assertTrue(
                outcome is DshProbeSessionCleanup.Outcome.Absent ||
                    outcome is DshProbeSessionCleanup.Outcome.Refused,
                "$evil produced $outcome",
            )
        }
        assertTrue(outside.resolve("keep.txt").exists(), "a traversal id reached outside the store")
    }

    @Test
    fun a_blank_session_id_is_refused_before_anything_is_resolved() {
        val outcome = DshProbeSessionCleanup.remove("  ", cwd("scratch"), root())
        assertEquals(DshProbeSessionCleanup.Outcome.Refused("no session id in the session/new answer"), outcome)
    }

    /** A junctioned/symlinked project directory must not let the delete land outside the store. Skipped
     *  where the platform will not let an unprivileged test create the link at all. */
    @Test
    fun a_project_directory_that_is_a_link_out_of_the_store_is_refused() {
        val store = root()
        val probeCwd = cwd("scratch")
        val target = createTempDirectory("ccp-387-link-target")
        val real = target.resolve(DshPaths.encodeSessionId("probe-id"))
        Files.createDirectories(real)
        Files.writeString(real.resolve("session.jsonl"), header("probe-id", probeCwd) + "\n")
        val link = store.resolve(DshPaths.projectKey(probeCwd))
        val linked = runCatching { Files.createSymbolicLink(link, target) }.isSuccess
        if (!linked) return // unprivileged Windows / restricted filesystem: nothing to assert

        val outcome = DshProbeSessionCleanup.remove("probe-id", probeCwd, store)

        assertEquals(DshProbeSessionCleanup.Outcome.Refused("project directory leaves the session root"), outcome)
        assertTrue(real.resolve("session.jsonl").exists(), "the delete followed a link out of the store")
    }

    @Test
    fun a_project_link_inside_the_store_is_also_refused_without_changing_its_target() {
        val store = root()
        val probeCwd = cwd("scratch")
        val target = store.resolve("other-project")
        val session = target.resolve(DshPaths.encodeSessionId("probe-id"))
        Files.createDirectories(session)
        Files.writeString(session.resolve("session.jsonl"), header("probe-id", probeCwd) + "\n")
        val before = snapshot(session)
        val link = store.resolve(DshPaths.projectKey(probeCwd))
        if (runCatching { Files.createSymbolicLink(link, target) }.isFailure) return

        assertEquals(
            DshProbeSessionCleanup.Outcome.Refused("project directory is a link"),
            DshProbeSessionCleanup.remove("probe-id", probeCwd, store),
        )
        assertUnchanged(session, before)
        assertTrue(Files.isSymbolicLink(link))
    }

    /**
     * The allow-list fails CLOSED. A dsh that adds a boot record this build has never seen must make the
     * sweep refuse and SAY so — the alternative, a deny-list, would delete a future conversation row by
     * default. This test is the tripwire for the next dsh upgrade.
     */
    @Test
    fun a_record_this_build_does_not_recognize_stops_the_sweep() {
        val store = root()
        val probeCwd = cwd("scratch")
        val dir = writeSession(
            store, probeCwd, "probe-id", *bootRecords,
            """{"type":"telemetry/whatever","seq":3,"time":1,"data":{}}""",
        )
        val before = snapshot(dir)

        val outcome = DshProbeSessionCleanup.remove("probe-id", probeCwd, store)

        assertEquals(DshProbeSessionCleanup.Outcome.Refused("session holds an unrecognized record"), outcome)
        assertUnchanged(dir, before)
    }

    /** A boot batch cannot grow without bound: past the cap it is treated as content, not settings. */
    @Test
    fun a_transcript_far_longer_than_a_boot_batch_is_refused() {
        val store = root()
        val probeCwd = cwd("scratch")
        val many = Array(20) { """{"type":"permission/preset","seq":$it,"time":1,"data":{}}""" }
        val dir = writeSession(store, probeCwd, "probe-id", *many)
        val before = snapshot(dir)

        assertEquals(
            DshProbeSessionCleanup.Outcome.Refused("session has recorded activity"),
            DshProbeSessionCleanup.remove("probe-id", probeCwd, store),
        )
        assertUnchanged(dir, before)
    }

    // ── the process / scratch lifecycle ─────────────────────────────────────────────────────────────

    /** A canned ACP peer: it replays [stdout] and remembers what was written to it. [dies] = false is a
     *  child that ignores every escalation, which is the case that must refuse to touch any file. */
    private class FakeDsh(stdout: String, private val dies: Boolean = true) : Process() {
        val stdin = ByteArrayOutputStream()
        private val out = stdout.byteInputStream()
        private val err = "".byteInputStream()
        @Volatile private var alive = true
        override fun getOutputStream(): OutputStream = stdin
        override fun getInputStream(): InputStream = out
        override fun getErrorStream(): InputStream = err
        override fun waitFor(): Int { alive = false; return 0 }
        override fun waitFor(timeout: Long, unit: TimeUnit): Boolean {
            if (dies) alive = false
            return !alive
        }
        override fun exitValue(): Int = if (alive) throw IllegalThreadStateException() else 0
        override fun destroy() { if (dies) alive = false }
        override fun destroyForcibly(): Process { if (dies) alive = false; return this }
        override fun isAlive(): Boolean = alive
    }

    private val configOptions = """
        [{"id":"model","currentValue":"[\"deepseek-official\",\"deepseek-v4-flash\"]",
          "options":[{"group":"deepseek-official","options":[
            {"value":"[\"deepseek-official\",\"deepseek-v4-flash\"]","name":"Flash"}]}]}]
    """.trimIndent().replace("\n", "")

    private fun acpScript(sessionId: String): String =
        """{"jsonrpc":"2.0","id":1,"result":{"protocolVersion":1}}""" + "\n" +
            """{"jsonrpc":"2.0","id":2,"result":{"sessionId":"$sessionId","configOptions":$configOptions}}""" +
            "\n" + """{"jsonrpc":"2.0","id":3,"result":{}}""" + "\n"

    private fun probe(
        proc: () -> Process,
        scratch: Path,
        store: Path = root(),
        cleanup: (String, String, Path) -> DshProbeSessionCleanup.Outcome = DshProbeSessionCleanup::remove,
    ) = DshProbeSession(
        launch = { proc() },
        newScratch = { scratch.toFile() },
        sessionsRoot = { store },
        cleanup = cleanup,
        answerTimeoutMs = 5_000,
        closeTimeoutMs = 500,
        exitTimeoutMs = 200,
    )

    @Test
    fun a_successful_probe_reads_the_catalogue_and_sweeps_its_own_session() {
        val store = root()
        val scratch = createTempDirectory("cc-pocket-dsh-models")
        // dsh keys the session under the cwd we send, which is the scratch directory's canonical path.
        val sent = scratch.toFile().canonicalPath
        val dir = writeSession(store, sent, "probe-id", *bootRecords)

        val result = probe({ FakeDsh(acpScript("probe-id")) }, scratch, store).run()

        val options = assertNotNull(result.options)
        assertEquals(listOf("deepseek-v4-flash"), options.models.map { it.id })
        assertEquals(DshProbeSessionCleanup.Outcome.Removed, result.session)
        assertTrue(result.processExited)
        assertTrue(result.scratchRemoved)
        assertFalse(dir.exists(), "the probe's own row is still in dsh's list")
        assertFalse(scratch.exists())
    }

    @Test
    fun the_close_and_the_id_on_the_wire_are_the_ones_the_sweep_is_given() {
        val store = root()
        val scratch = createTempDirectory("cc-pocket-dsh-models")
        var seen: Pair<String, String>? = null
        val fake = FakeDsh(acpScript("3cad11be-de2e-4e9f-a70a-14593e045408"))
        probe({ fake }, scratch, store, cleanup = { id, cwd, _ ->
            seen = id to cwd
            DshProbeSessionCleanup.Outcome.Removed
        }).run()

        assertEquals("3cad11be-de2e-4e9f-a70a-14593e045408", seen?.first)
        assertEquals(scratch.toFile().canonicalPath, seen?.second)
        val wire = fake.stdin.toString(Charsets.UTF_8)
        assertTrue("\"method\":\"session/close\"" in wire, "the session must be closed so its write is final")
        assertTrue("3cad11be-de2e-4e9f-a70a-14593e045408" in wire, wire)
    }

    @Test
    fun a_launch_that_throws_still_releases_the_scratch_directory() {
        val scratch = createTempDirectory("cc-pocket-dsh-models")
        val thrown = runCatching {
            probe({ error("dsh executable not found") }, scratch).run()
        }.exceptionOrNull()
        assertNotNull(thrown)
        assertFalse(scratch.exists(), "a ProcessBuilder.start that throws must not leak the scratch dir")
    }

    @Test
    fun a_probe_that_is_never_answered_deletes_nothing() {
        val store = root()
        val scratch = createTempDirectory("cc-pocket-dsh-models")
        val dir = writeSession(store, scratch.toFile().canonicalPath, "orphan-id")
        val before = snapshot(dir)

        // stdout closes with no session/new answer at all — the id we would need is simply not there.
        val result = probe({ FakeDsh("") }, scratch, store).run()

        assertNull(result.options)
        assertTrue(result.session is DshProbeSessionCleanup.Outcome.Refused, result.session.toString())
        assertUnchanged(dir, before)
        assertTrue(result.scratchRemoved)
    }

    @Test
    fun a_child_that_will_not_die_is_never_followed_by_a_delete() {
        val store = root()
        val scratch = createTempDirectory("cc-pocket-dsh-models")
        val dir = writeSession(store, scratch.toFile().canonicalPath, "probe-id", *bootRecords)
        val before = snapshot(dir)

        val result = probe({ FakeDsh(acpScript("probe-id"), dies = false) }, scratch, store).run()

        assertFalse(result.processExited)
        assertEquals(DshProbeSessionCleanup.Outcome.Refused("the probe process did not exit"), result.session)
        assertUnchanged(dir, before)
    }

    /** The sweep is hygiene, not the answer: a picker must still open when the litter stays behind. */
    @Test
    fun a_sweep_that_fails_still_returns_the_catalogue_it_read() {
        val scratch = createTempDirectory("cc-pocket-dsh-models")
        val result = probe({ FakeDsh(acpScript("probe-id")) }, scratch, cleanup = { _, _, _ ->
            throw java.io.IOException("the store is read-only")
        }).run()

        assertNotNull(result.options, "a cleanup failure must not become a blank model picker")
        assertEquals(DshProbeSessionCleanup.Outcome.Failed("the cleanup pass did not complete"), result.session)
    }

    /** A dsh that answers `session/new` with an error still has to leave nothing behind. */
    @Test
    fun an_error_answer_yields_no_catalogue_and_no_delete() {
        val store = root()
        val scratch = createTempDirectory("cc-pocket-dsh-models")
        val result = probe(
            { FakeDsh("""{"jsonrpc":"2.0","id":2,"error":{"code":-32602,"message":"bad cwd"}}""" + "\n") },
            scratch, store,
        ).run()

        assertNull(result.options)
        assertTrue(result.session is DshProbeSessionCleanup.Outcome.Refused)
        assertFalse(scratch.exists())
    }

    /** Sanity: the fixture's option payload is the shape [DshConfigOptions] parses, so a failure above is
     *  about the lifecycle rather than about the test's own JSON. */
    @Test
    fun the_fixture_payload_is_a_catalogue_the_parser_accepts() {
        val parsed = DshConfigOptions.parse(Json.parseToJsonElement(configOptions) as JsonArray)
        assertEquals(listOf("deepseek-v4-flash"), parsed.models.map { it.id })
    }
}
