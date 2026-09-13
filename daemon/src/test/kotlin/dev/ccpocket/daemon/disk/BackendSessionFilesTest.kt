package dev.ccpocket.daemon.disk

import com.github.luben.zstd.Zstd
import dev.ccpocket.daemon.dsh.DshPaths
import dev.ccpocket.protocol.AgentKind
import dev.ccpocket.protocol.FileContent
import dev.ccpocket.protocol.FileContentChunk
import dev.ccpocket.protocol.Frame
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.sqlite.SQLiteConfig
import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager
import kotlin.test.*

class BackendSessionFilesTest {
    @TempDir lateinit var tmp: Path
    private val sid = "session-test"
    private fun project() = Files.createDirectories(tmp.resolve("project")).toRealPath()
    private fun sources() = BackendSessionFiles(dshRoot = { tmp.resolve("store") }, zcodeConnection = {
        val config = SQLiteConfig().apply { setReadOnly(true) }
        DriverManager.getConnection("jdbc:sqlite:${tmp.resolve("zcode.sqlite")}", config.toProperties())
    })
    private fun obj(vararg fields: Pair<String, JsonElement>) = JsonObject(fields.toMap())
    private fun str(s: String) = JsonPrimitive(s)
    private fun args(path: String, command: String = "str_replace") = obj(
        "command" to str(command), "path" to str(path), "old_str" to str("before"), "new_str" to str("after"),
        "file_text" to str("created\n"), "insert_line" to JsonPrimitive(2),
    )
    private fun call(arguments: JsonObject, id: String = "c1", tool: String = "str_replace_editor") = obj(
        "type" to str("tool/call"), "data" to obj("callId" to str(id), "name" to str(tool), "arguments" to str(arguments.toString())),
    )
    private fun result(id: String = "c1", failed: Boolean? = null, placement: JsonElement = str("append"), text: String = "success"): JsonObject {
        val block = mutableMapOf<String, JsonElement>("type" to str("tool-result"), "toolCallId" to str(id),
            "content" to JsonArray(listOf(obj("type" to str("text"), "text" to str(text)))))
        if (failed != null) block["isError"] = JsonPrimitive(failed)
        return obj("type" to str("tool/result"), "surfaceOp" to placement,
            "data" to obj("message" to obj("content" to JsonArray(listOf(JsonObject(block))))))
    }
    private fun dsh(
        cwd: Path = project(), events: List<JsonObject> = emptyList(), version: Int = 3,
        headerId: String = sid, filenameVersion: Int = version, compressed: Boolean = false,
    ): Path {
        val folder = Files.createDirectories(tmp.resolve("store").resolve(DshPaths.projectKey(cwd.toString())).resolve(sid))
        val name = (if (filenameVersion == 0) "session" else "session.v$filenameVersion") + ".jsonl" + if (compressed) ".zstd" else ""
        val header = obj("type" to str("session"), "id" to str(headerId), "cwd" to str(cwd.toString()), "version" to JsonPrimitive(version))
        val lines = (listOf(header) + events).map { (it.toString() + "\n").toByteArray() }
        val bytes = if (compressed) lines.flatMap { Zstd.compress(it).toList() }.toByteArray() else lines.flatMap { it.toList() }.toByteArray()
        return Files.write(folder.resolve(name), bytes)
    }
    private fun read(agent: AgentKind, cwd: Path, path: String, id: String = sid) =
        SessionFilesService.readFileWithSources(agent, cwd.toString(), id, path, sources())
    private fun changed(agent: AgentKind, cwd: Path) =
        SessionFilesService.changedFilesWithSources(agent, cwd.toString(), sid, sources())
    private fun diff(agent: AgentKind, cwd: Path, path: String) =
        SessionFilesService.fileDiffWithSources(agent, cwd.toString(), sid, path, sources())

    @Test fun dshAllSupportedGenerationsResolveAndExposeOnlySuccessfulChanges() {
        val cwd = project()
        val target = Files.writeString(cwd.resolve("a.txt"), "after")
        for (v in 0..3) {
            dsh(cwd, listOf(call(args(target.toString())), result()), v, compressed = true)
            assertEquals("after", read(AgentKind.DSH, cwd, "a.txt").text)
            assertEquals(listOf(target.toString()), changed(AgentKind.DSH, cwd).map { it.path })
            val delta = diff(AgentKind.DSH, cwd, "a.txt")
            assertTrue(delta.ok)
            assertEquals("@@ -0,1 +0,1 @@\n-before\n+after\n", delta.diff)
            assertEquals(1, delta.adds)
            assertEquals(1, delta.dels)
        }
    }

    @Test fun dshVerifiedSessionReadsUntouchedFilesAndStreamsThroughTheProductionGate() = runBlocking {
        val cwd = project()
        dsh(cwd)
        Files.writeString(cwd.resolve("AGENTS.md"), "local instructions")
        val frames = mutableListOf<Frame>()
        SessionFilesService.streamFileWithSources(AgentKind.DSH, cwd.toString(), sid, "AGENTS.md", true, sources(), frames::add)
        assertEquals("local instructions", (frames.single() as FileContent).text)
        val bytes = ByteArray(SessionFilesService.BINARY_CAP_BYTES + 3) { 1 }
        Files.write(cwd.resolve("large.png"), bytes)
        frames.clear()
        SessionFilesService.streamFileWithSources(AgentKind.DSH, cwd.toString(), sid, "large.png", true, sources(), frames::add)
        assertTrue(frames.all { it is FileContentChunk })
        assertTrue((frames.last() as FileContentChunk).last)
    }

    @Test fun dshUnknownSessionWrongHeaderAndCollidingDirectoryNeverUnlockReads() {
        val cwd = Files.createDirectories(tmp.resolve("a/b")).toRealPath()
        val collision = Files.createDirectories(tmp.resolve("a-b")).toRealPath()
        assertEquals(DshPaths.projectKey(cwd.toString()), DshPaths.projectKey(collision.toString()))
        Files.writeString(cwd.resolve("secret"), "must remain private")
        dsh(collision)
        assertFalse(read(AgentKind.DSH, cwd, "secret").ok)
        assertFalse(read(AgentKind.DSH, collision, "x", "unknown").ok)
        dsh(cwd, headerId = "different-id")
        assertFalse(read(AgentKind.DSH, cwd, "secret").ok)
        assertFalse(read(AgentKind.DSH, cwd, "secret", "../../other").ok)
    }

    @Test fun dshNewestUnknownOrMismatchedGenerationNeverFallsBack() {
        val cwd = project()
        Files.writeString(cwd.resolve("a"), "safe")
        dsh(cwd, version = 0)
        dsh(cwd, version = 4)
        assertFalse(read(AgentKind.DSH, cwd, "a").ok)
        dsh(cwd, version = 3, filenameVersion = 4)
        assertFalse(read(AgentKind.DSH, cwd, "a").ok)
    }

    @Test fun dshIncompleteTailKeepsInTreeReadsButCannotAuthorizeOutsidePaths() {
        val cwd = project()
        Files.writeString(cwd.resolve("a"), "safe")
        val target = Files.writeString(tmp.resolve("outside"), "external").toRealPath()
        val file = dsh(cwd, listOf(call(args(target.toString())), result()), compressed = true)
        Files.write(file, Files.readAllBytes(file).dropLast(4).toByteArray())
        assertTrue(read(AgentKind.DSH, cwd, "a").ok)
        assertFalse(read(AgentKind.DSH, cwd, target.toString()).ok)
        assertTrue(changed(AgentKind.DSH, cwd).isEmpty())
        dsh(cwd, listOf(call(args(target.toString())), result()), compressed = true)
        Files.write(file, Files.readAllBytes(file) + Zstd.compress("unfinished\n".toByteArray()).take(5).toByteArray())
        assertFalse(read(AgentKind.DSH, cwd, target.toString()).ok, "a complete successful prefix is not sufficient outside-path evidence")
        assertTrue(changed(AgentKind.DSH, cwd).isEmpty())
        Files.write(file, byteArrayOf(1, 2, 3))
        assertFalse(read(AgentKind.DSH, cwd, "a").ok)
    }

    @Test fun dshOnlyCompletedMutationsAuthorizeOutsidePathsAndRejectSymlinkRetargeting() {
        val cwd = project()
        val outside = Files.writeString(tmp.resolve("outside"), "external").toRealPath()
        for (events in listOf(listOf(call(args(outside.toString()))),
            listOf(call(args(outside.toString())), result(failed = true)),
            listOf(call(args(outside.toString(), "view")), result()),
            listOf(call(args(outside.toString()), tool = "shell"), result()),
            listOf(call(args(outside.toString())), result(placement = obj("op" to str("replace"), "startSeq" to JsonPrimitive(1), "endSeq" to JsonPrimitive(2)))))) {
            dsh(cwd, events)
            assertFalse(read(AgentKind.DSH, cwd, outside.toString()).ok)
            assertTrue(changed(AgentKind.DSH, cwd).isEmpty())
        }
        dsh(cwd, listOf(call(args(outside.toString())), result()))
        assertTrue(read(AgentKind.DSH, cwd, outside.toString()).ok)
        val secret = Files.writeString(tmp.resolve("secret"), "not edited").toRealPath()
        Files.delete(outside)
        Files.createSymbolicLink(outside, secret)
        assertFalse(read(AgentKind.DSH, cwd, outside.toString()).ok)
        Files.createSymbolicLink(cwd.resolve("escape"), secret)
        assertFalse(read(AgentKind.DSH, cwd, "escape").ok)
        assertFalse(read(AgentKind.DSH, cwd, "../secret").ok)
    }

    @Test fun dshCreationInsertionAndRetouchesPreserveOrderWithoutCountingResultDuplicates() {
        val cwd = project()
        dsh(cwd, listOf(call(args(cwd.resolve("a").toString(), "create"), "a"), result("a"), result("a"),
            call(args(cwd.resolve("b").toString(), "insert"), "b"), result("b"), call(args(cwd.resolve("a").toString()), "c"), result("c")))
        val rows = changed(AgentKind.DSH, cwd)
        assertEquals(listOf(cwd.resolve("a").toString(), cwd.resolve("b").toString()), rows.map { it.path })
        assertEquals(listOf(2, 1), rows.map { it.edits })
        assertTrue(diff(AgentKind.DSH, cwd, "a").diff!!.contains("+created"))
        assertTrue(diff(AgentKind.DSH, cwd, "b").diff!!.contains("@@ -2,0 +3,1 @@"))
    }

    private fun database(cwd: Path, parts: List<JsonObject>) {
        DriverManager.getConnection("jdbc:sqlite:${tmp.resolve("zcode.sqlite")}").use { c ->
            c.createStatement().use { s ->
                s.execute("CREATE TABLE session(id TEXT PRIMARY KEY,directory TEXT)")
                s.execute("CREATE TABLE message(id TEXT PRIMARY KEY,session_id TEXT,data TEXT,sequence INTEGER,time_created INTEGER)")
                s.execute("CREATE TABLE part(id TEXT PRIMARY KEY,message_id TEXT,session_id TEXT,data TEXT,sequence INTEGER,time_created INTEGER)")
            }
            c.prepareStatement("INSERT INTO session VALUES(?,?)").use { s -> s.setString(1, sid); s.setString(2, cwd.toString()); s.executeUpdate() }
            c.prepareStatement("INSERT INTO message VALUES('m',?, ?,0,0)").use { s ->
                s.setString(1, sid); s.setString(2, """{"role":"assistant"}"""); s.executeUpdate()
            }
            parts.forEachIndexed { i, p -> c.prepareStatement("INSERT INTO part VALUES(?, 'm', ?, ?, ?,0)").use { s ->
                s.setString(1, "p$i"); s.setString(2, sid); s.setString(3, p.toString()); s.setInt(4, i); s.executeUpdate()
            } }
        }
    }
    private fun zpart(path: String, status: String = "completed", display: JsonObject? = null, error: Boolean = false, id: String = "z1"): JsonObject {
        val state = mutableMapOf<String, JsonElement>("status" to str(status),
            "input" to obj("file_path" to str(path), "old_string" to str("proposal"), "new_string" to str("proposed")),
            "output" to str("The file has been updated successfully."))
        if (display != null) state["metadata"] = obj("display" to display)
        if (error) state["error"] = str("failed")
        return obj("type" to str("tool"), "callID" to str(id), "tool" to str("Edit"), "state" to JsonObject(state))
    }
    private fun display(path: String) = obj("kind" to str("file_diff"), "filePath" to str(path),
        "structuredPatch" to JsonArray(listOf(obj("oldStart" to JsonPrimitive(4), "oldLines" to JsonPrimitive(1),
            "newStart" to JsonPrimitive(4), "newLines" to JsonPrimitive(1), "lines" to JsonArray(listOf(str("-actual-before"), str("+actual-after")))))))

    // Official ZCode 3.11.2 Write-create: empty structuredPatch omits display, while
    // formatWriteModelContent persists the actual result filePath in this exact text.
    private fun createOutput(path: String, modified: Boolean = false) =
        "File created successfully at: $path" + if (modified) " The user modified your proposed content before accepting it."
        else " (file state is current in your context — no need to Read it back)"

    private fun zcreate(proposed: String, output: JsonElement, id: String = "create", status: String = "completed", error: Boolean = false): JsonObject {
        val state = mutableMapOf<String, JsonElement>("status" to str(status),
            "input" to obj("file_path" to str(proposed), "content" to str("unexecuted proposal")),
            "output" to output, "title" to str("Write"),
            "metadata" to obj("schemaVersion" to JsonPrimitive(1)),
            "time" to obj("start" to JsonPrimitive(1), "end" to JsonPrimitive(2)))
        if (error) state["error"] = str("failed")
        return obj("type" to str("tool"), "callID" to str(id), "tool" to str("Write"), "state" to JsonObject(state))
    }

    @Test fun zcodeSuccessfulCreateWithoutDisplayUsesFinalPathAndHasNoInventedDiff() = runBlocking {
        val cwd = project()
        val proposed = Files.writeString(tmp.resolve("proposal"), "private").toRealPath()
        val paths = listOf(cwd.resolve("nonempty"), cwd.resolve("empty"), tmp.toRealPath().resolve("outside-nonempty"), tmp.toRealPath().resolve("outside-empty"))
        paths.forEachIndexed { i, path -> Files.writeString(path, if (i % 2 == 0) "normalized actual\n" else "") }
        val parts = paths.mapIndexed { i, path -> zcreate(proposed.toString(), str(createOutput(path.toString(), modified = i >= 2)), "create$i") }
        database(cwd, parts + parts.first())
        val rows = changed(AgentKind.ZCODE, cwd)
        assertEquals(paths.map { it.toString() }.toSet(), rows.map { it.path }.toSet())
        assertTrue(rows.all { it.edits == 1 })
        for ((i, path) in paths.withIndex()) {
            val expected = if (i % 2 == 0) "normalized actual\n" else ""
            assertTrue(SessionFilesService.isChangedWithSources(AgentKind.ZCODE, cwd.toString(), sid, path.toString(), sources()))
            assertEquals(expected, read(AgentKind.ZCODE, cwd, path.toString()).text)
            val frames = mutableListOf<Frame>()
            SessionFilesService.streamFileWithSources(AgentKind.ZCODE, cwd.toString(), sid, path.toString(), true, sources(), frames::add)
            assertEquals(expected, (frames.single() as FileContent).text)
            val delta = diff(AgentKind.ZCODE, cwd, path.toString())
            assertFalse(delta.ok)
            assertTrue(delta.error!!.contains("no complete edit diff"))
            assertNull(delta.diff)
        }
        assertFalse(read(AgentKind.ZCODE, cwd, proposed.toString()).ok)
        assertFalse(SessionFilesService.isChangedWithSources(AgentKind.ZCODE, cwd.toString(), sid, proposed.toString(), sources()))
    }

    /** Exercise the real fixed alias, never replace a system link or consult real provider data. */
    private inline fun withMacTmp(block: (Path) -> Unit) {
        assumeTrue(System.getProperty("os.name") == "Mac OS X")
        val alias = Path.of("/tmp")
        assumeTrue(Files.isSymbolicLink(alias) && alias.toRealPath() == Path.of("/private/tmp"))
        val dir = Files.createTempDirectory(alias, "issue354-test-")
        try { block(dir) } finally {
            Files.walk(dir).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach { Files.delete(it) } }
        }
    }

    @Test fun fixedMacAliasVerifierAcceptsOnlyExpectedPlatformOwnershipAndDestinations() {
        for (name in listOf("tmp", "var", "etc")) {
            val alias = Path.of("/$name")
            val destination = Path.of("/private/$name")
            fun verify(os: String = "Mac OS X", target: Path? = Path.of("private/$name"),
                root: Boolean = true, real: Path? = destination) =
                SessionFilesService.verifiedMacSystemAlias(alias, os, target, root, real)
            assertEquals(destination, verify())
            assertEquals(destination, verify(target = destination))
            assertNull(verify(os = "Linux"))
            assertNull(verify(os = "Windows 11"))
            assertNull(verify(root = false))
            assertNull(verify(target = null)) // not a symlink / unreadable observation
            assertNull(verify(target = Path.of("/arbitrary")))
            assertNull(verify(target = Path.of("private/other/../$name")))
            assertNull(verify(real = Path.of("/retargeted")))
            assertNull(verify(real = null))
        }
        assertNull(SessionFilesService.verifiedMacSystemAlias(Path.of("/tmpX"), "Mac OS X",
            Path.of("/private/tmpX"), true, Path.of("/private/tmpX")))
    }

    @Test fun zcodeMacTmpFailedAndUnfinishedCreatesNeverAuthorizeEitherSpelling() {
        withMacTmp { base ->
            val cwd = project()
            val original = Files.writeString(base.resolve("private.txt"), "private")
            database(cwd, listOf(zcreate(original.toString(), str(createOutput(original.toString())), "failed", error = true),
                zcreate(original.toString(), str(createOutput(original.toString())), "pending", status = "running")))
            assertTrue(changed(AgentKind.ZCODE, cwd).isEmpty())
            for (path in listOf(original, original.toRealPath())) {
                assertFalse(read(AgentKind.ZCODE, cwd, path.toString()).ok)
                assertFalse(SessionFilesService.isChangedWithSources(AgentKind.ZCODE, cwd.toString(), sid, path.toString(), sources()))
            }
        }
    }

    @Test fun evidencePathIdentityUsesSegmentsAndKeepsMalformedPathsClosed() {
        val project = project()
        val cwd = project.toString()
        // Use the filesystem's absolute root: Windows needs its drive and native separators.
        val root = project.root
        for (relative in listOf("tmpX/file", "tmp-file", "nested/tmp/file", "private/tmp/missing")) {
            val path = root.resolve(relative).toString()
            assertEquals(path, SessionFilesService.resolveEvidencePath(path, cwd))
        }
        assertNull(SessionFilesService.resolveEvidencePath("bad\u0000path", cwd))
        val missing = root.resolve("tmp/missing").toString()
        assertEquals(SessionFilesService.resolveEvidencePath(missing, cwd),
            SessionFilesService.resolveEvidencePath(root.resolve("tmp/./unused/../missing").toString(), cwd))
        if (System.getProperty("os.name") != "Mac OS X") {
            assertEquals(missing, SessionFilesService.resolveEvidencePath(missing, cwd))
            assertEquals(project.resolve("relative/missing").toString(),
                SessionFilesService.resolveEvidencePath("relative/./unused/../missing", cwd))
        }
    }

    @Test fun zcodeMacTmpAliasAndCanonicalEditsMergeAndMatchRealDiff() {
        withMacTmp { base ->
            val cwd = project()
            val original = Files.writeString(base.resolve("edited.txt"), "actual-after")
            val canonical = original.toRealPath()
            database(cwd, listOf(zpart(original.toString(), display = display(original.toString()), id = "first"),
                zpart(canonical.toString(), display = display(canonical.toString()), id = "second")))
            val rows = changed(AgentKind.ZCODE, cwd)
            assertEquals(1, rows.size)
            assertEquals(2, rows.single().edits)
            val expected = "@@ -4,1 +4,1 @@\n-actual-before\n+actual-after\n".repeat(2)
            for (path in listOf(original, canonical, base.resolve("./unused/../edited.txt"))) {
                assertEquals(expected, diff(AgentKind.ZCODE, cwd, path.toString()).diff)
                assertEquals("actual-after", read(AgentKind.ZCODE, cwd, path.toString()).text)
            }
            assertFalse(read(AgentKind.ZCODE, tmp.toRealPath(), original.toString()).ok)
        }
    }

    @Test fun zcodeMacTmpCreateSupportsBothPathSpellingsWithoutInventingDiff() = runBlocking {
        withMacTmp { base ->
            val cwd = project()
            val original = Files.writeString(base.resolve("created.txt"), "actual result")
            val canonical = original.toRealPath()
            database(cwd, listOf(zcreate(original.toString(), str(createOutput(original.toString())))))
            assertEquals(1, changed(AgentKind.ZCODE, cwd).size)
            for (path in listOf(original, canonical)) {
                assertEquals("actual result", read(AgentKind.ZCODE, cwd, path.toString()).text, path.toString())
                assertTrue(SessionFilesService.isChangedWithSources(AgentKind.ZCODE, cwd.toString(), sid, path.toString(), sources()))
                val frames = mutableListOf<Frame>()
                SessionFilesService.streamFileWithSources(AgentKind.ZCODE, cwd.toString(), sid, path.toString(), true, sources(), frames::add)
                assertEquals("actual result", (frames.single() as FileContent).text)
                assertTrue(diff(AgentKind.ZCODE, cwd, path.toString()).error!!.contains("no complete edit diff"))
                assertFalse(read(AgentKind.ZCODE, cwd, path.toString(), "unknown").ok)
            }
            Files.delete(original)
            for (path in listOf(original, canonical)) {
                assertFalse(read(AgentKind.ZCODE, cwd, path.toString()).ok)
                assertTrue(diff(AgentKind.ZCODE, cwd, path.toString()).error!!.contains("no complete edit diff"))
            }
        }
    }

    @Test fun dshMacTmpPathsShareRecordedDiffButNeverFollowNestedRetargetedLinks() = runBlocking {
        withMacTmp { base ->
            val cwd = project()
            val folder = Files.createDirectory(base.resolve("output"))
            val original = Files.writeString(folder.resolve("result.txt"), "after")
            val canonical = original.toRealPath()
            dsh(cwd, listOf(call(args(original.toString())), result()))
            for (path in listOf(original, canonical)) {
                assertEquals("after", read(AgentKind.DSH, cwd, path.toString()).text)
                assertTrue(SessionFilesService.isChangedWithSources(AgentKind.DSH, cwd.toString(), sid, path.toString(), sources()))
                assertEquals("@@ -0,1 +0,1 @@\n-before\n+after\n", diff(AgentKind.DSH, cwd, path.toString()).diff)
            }
            val secretDir = Files.createDirectory(base.resolve("secret"))
            val secret = Files.writeString(secretDir.resolve("result.txt"), "private").toRealPath()
            Files.delete(original)
            Files.delete(folder)
            Files.createSymbolicLink(folder, secretDir.toRealPath())
            for (path in listOf(original, canonical, secret)) {
                assertFalse(read(AgentKind.DSH, cwd, path.toString()).ok)
                assertFalse(SessionFilesService.isChangedWithSources(AgentKind.DSH, cwd.toString(), sid, path.toString(), sources()))
                val frames = mutableListOf<Frame>()
                SessionFilesService.streamFileWithSources(AgentKind.DSH, cwd.toString(), sid, path.toString(), true, sources(), frames::add)
                assertFalse((frames.single() as FileContent).ok)
            }
        }
    }

    @Test fun zcodeCreateMalformedFailedUnfinishedAndMultiSegmentResultsNeverAuthorize() = runBlocking {
        val cwd = project()
        val outside = Files.writeString(tmp.resolve("outside"), "private").toRealPath()
        val output = createOutput(outside.toString())
        val bad = listOf(str("prefix $output"), str("$output\n"), str("$output\n$output"),
            str("$output$output"), str(output + " The user modified your proposed content before accepting it."),
            str("File created successfully at: $outside"),
            str(createOutput("../outside")), str(createOutput("$outside\nother")), str(createOutput("$outside\u0000")),
            JsonArray(listOf(str(output))), obj("text" to str(output)), JsonNull)
        database(cwd, bad.mapIndexed { i, text -> zcreate(outside.toString(), text, "bad$i") } +
            listOf(zcreate(outside.toString(), str(output), "failed", error = true),
                zcreate(outside.toString(), str(output), "pending", status = "running"),
                zcreate(outside.toString(), str(output), "error", status = "error")))
        assertTrue(changed(AgentKind.ZCODE, cwd).isEmpty())
        assertFalse(read(AgentKind.ZCODE, cwd, outside.toString()).ok)
        assertFalse(SessionFilesService.isChangedWithSources(AgentKind.ZCODE, cwd.toString(), sid, outside.toString(), sources()))
        val frames = mutableListOf<Frame>()
        SessionFilesService.streamFileWithSources(AgentKind.ZCODE, cwd.toString(), sid, outside.toString(), true, sources(), frames::add)
        assertFalse((frames.single() as FileContent).ok)
    }

    @Test fun zcodeCreateConfirmedOutsidePathStillRejectsSymlinkRetargeting() {
        val cwd = project()
        val outside = Files.writeString(tmp.resolve("outside"), "actual").toRealPath()
        val privateFile = Files.writeString(tmp.resolve("private"), "private").toRealPath()
        database(cwd, listOf(zcreate(outside.toString(), str(createOutput(outside.toString())))))
        assertTrue(read(AgentKind.ZCODE, cwd, outside.toString()).ok)
        Files.delete(outside)
        Files.createSymbolicLink(outside, privateFile)
        assertFalse(read(AgentKind.ZCODE, cwd, outside.toString()).ok)
    }

    @Test fun zcodeDatabaseEntryReadsAndUsesActualCompletedDisplayPatch() {
        val cwd = project()
        val outside = Files.writeString(tmp.resolve("outside"), "actual-after").toRealPath()
        val part = zpart(outside.toString(), display = display(outside.toString()))
        database(cwd, listOf(part, part)) // copied duplicate must not count twice
        assertTrue(read(AgentKind.ZCODE, cwd, outside.toString()).ok)
        assertEquals(1, changed(AgentKind.ZCODE, cwd).single().edits)
        val delta = diff(AgentKind.ZCODE, cwd, outside.toString())
        assertEquals("@@ -4,1 +4,1 @@\n-actual-before\n+actual-after\n", delta.diff)
        assertFalse(delta.diff!!.contains("proposal"))
    }

    @Test fun zcodeValidSessionCanReadUntouchedProjectFileAndMissingPatchIsHonest() = runBlocking {
        val cwd = project()
        Files.writeString(cwd.resolve("AGENTS.md"), "instructions")
        database(cwd, listOf(zpart(cwd.resolve("a").toString(), display = obj("kind" to str("file_diff"), "filePath" to str(cwd.resolve("a").toString())))))
        assertEquals("instructions", read(AgentKind.ZCODE, cwd, "AGENTS.md").text)
        val frames = mutableListOf<Frame>()
        SessionFilesService.streamFileWithSources(AgentKind.ZCODE, cwd.toString(), sid, "AGENTS.md", false, sources(), frames::add)
        assertEquals("instructions", (frames.single() as FileContent).text)
        assertEquals(1, changed(AgentKind.ZCODE, cwd).size)
        assertFalse(diff(AgentKind.ZCODE, cwd, "a").ok)
        assertTrue(diff(AgentKind.ZCODE, cwd, "a").error!!.contains("no complete edit diff"))
    }

    @Test fun zcodeWrongCwdUnknownIdFailedCallsAndOutsideSymlinksStayDenied() {
        val cwd = project()
        val outside = Files.writeString(tmp.resolve("outside"), "secret").toRealPath()
        val link = Files.createSymbolicLink(cwd.resolve("escape"), outside)
        database(cwd, listOf(zpart(outside.toString(), "error"), zpart(outside.toString(), error = true),
            zpart(link.toString(), display = display(link.toString()), id = "linked")))
        assertFalse(read(AgentKind.ZCODE, cwd, outside.toString()).ok)
        assertFalse(read(AgentKind.ZCODE, cwd, "escape").ok)
        assertFalse(read(AgentKind.ZCODE, cwd, "../outside").ok)
        assertFalse(read(AgentKind.ZCODE, tmp.toRealPath(), "outside").ok)
        assertFalse(read(AgentKind.ZCODE, cwd, "escape", "unknown").ok)
        assertFalse(read(AgentKind.ZCODE, cwd, "escape", "' OR 1=1 --").ok)
    }

    @Test fun zcodeProposalPathCannotAuthorizeAndActualDisplayPathWins() {
        val cwd = project()
        val original = Files.writeString(tmp.resolve("proposed"), "private").toRealPath()
        val actual = Files.writeString(tmp.resolve("actual"), "edited").toRealPath()
        database(cwd, listOf(zpart(original.toString(), display = display(actual.toString()))))
        assertFalse(read(AgentKind.ZCODE, cwd, original.toString()).ok)
        assertTrue(read(AgentKind.ZCODE, cwd, actual.toString()).ok)
        assertEquals(listOf(actual.toString()), changed(AgentKind.ZCODE, cwd).map { it.path })
        assertFalse(SessionFilesService.isChangedWithSources(AgentKind.ZCODE, cwd.toString(), sid, original.toString(), sources()))
        assertTrue(SessionFilesService.isChangedWithSources(AgentKind.ZCODE, cwd.toString(), sid, actual.toString(), sources()))
    }

    @Test fun zcodeMissingDisplayNeitherListsProposalNorAuthorizesOutsideRead() {
        val cwd = project()
        val outside = Files.writeString(tmp.resolve("outside"), "private").toRealPath()
        database(cwd, listOf(zpart(outside.toString())))
        assertTrue(changed(AgentKind.ZCODE, cwd).isEmpty())
        assertFalse(read(AgentKind.ZCODE, cwd, outside.toString()).ok)
        assertFalse(SessionFilesService.isChangedWithSources(AgentKind.ZCODE, cwd.toString(), sid, outside.toString(), sources()))
    }

    @Test fun zcodeEvidenceBudgetsFailClosedBeforeReadingLargeRowsButDoNotBlockInTree() {
        val cwd = project()
        val target = Files.writeString(cwd.resolve("a"), "safe")
        database(cwd, listOf(zpart(target.toString(), display = display(target.toString()))))
        fun bounded(bytes: Long, rows: Int) = BackendSessionFiles(zcodeConnection = {
            val cfg = SQLiteConfig().apply { setReadOnly(true) }
            DriverManager.getConnection("jdbc:sqlite:${tmp.resolve("zcode.sqlite")}", cfg.toProperties())
        }, maxEvidenceBytes = bytes, maxEvidenceRows = rows)
        for (source in listOf(bounded(1, 100), bounded(100_000, 0))) {
            assertTrue(source.load(AgentKind.ZCODE, cwd.toString(), sid).isFailure)
            assertTrue(SessionFilesService.changedFilesWithSources(AgentKind.ZCODE, cwd.toString(), sid, source).isEmpty())
            assertTrue(SessionFilesService.readFileWithSources(AgentKind.ZCODE, cwd.toString(), sid, "a", source).ok)
        }
    }

    @Test fun dshFsWriteAndEditUseOfficialResultPathsAndDoNotInventOverwriteHistory() {
        val cwd = project()
        val actual = Files.writeString(tmp.resolve("actual"), "after").toRealPath()
        val proposed = Files.writeString(tmp.resolve("proposed"), "private").toRealPath()
        val writeArgs = obj("file_path" to str(proposed.toString()), "content" to str("after"))
        fun writeOutput(verb: String) = "<path>$actual</path>\n<type>file</type>\n<content>\n$verb file\n</content>"
        dsh(cwd, listOf(call(writeArgs, tool = "write"), result(text = writeOutput("Created"))))
        assertTrue(read(AgentKind.DSH, cwd, actual.toString()).ok)
        assertFalse(read(AgentKind.DSH, cwd, proposed.toString()).ok)
        assertEquals("@@ -0,0 +1,1 @@\n+after\n", diff(AgentKind.DSH, cwd, actual.toString()).diff)
        dsh(cwd, listOf(call(writeArgs, tool = "write"), result(text = writeOutput("Updated"))))
        assertFalse(diff(AgentKind.DSH, cwd, actual.toString()).ok)
        val editArgs = obj("file_path" to str(actual.toString()), "old_string" to str("before"), "new_string" to str("after"))
        dsh(cwd, listOf(call(editArgs, tool = "edit"), result(text = "The file $actual has been updated successfully.")))
        assertEquals("@@ -0,1 +0,1 @@\n-before\n+after\n", diff(AgentKind.DSH, cwd, actual.toString()).diff)
        dsh(cwd, listOf(call(editArgs, tool = "edit"), result(text = "The file $actual has been updated. All occurrences were successfully replaced.")))
        assertTrue(read(AgentKind.DSH, cwd, actual.toString()).ok)
        assertFalse(diff(AgentKind.DSH, cwd, actual.toString()).ok)
    }

    @Test fun dshStrReplaceRejectsRelativePathsOutsideItsOfficialContract() {
        val cwd = project()
        dsh(cwd, listOf(call(args("../outside")), result()))
        assertTrue(changed(AgentKind.DSH, cwd).isEmpty())
    }

    @Test fun zcodeOversizedPayloadIsRefusedBeforeAnyContentQuery() {
        val cwd = project()
        database(cwd, listOf(zpart(cwd.resolve("a").toString(), display = obj("kind" to str("file_diff"), "filePath" to str(cwd.resolve("a").toString())))))
        DriverManager.getConnection("jdbc:sqlite:${tmp.resolve("zcode.sqlite")}").use { c ->
            c.createStatement().use { it.execute("UPDATE part SET data=zeroblob(8388609)") }
        }
        var contentQueries = 0
        val source = BackendSessionFiles(zcodeConnection = {
            val cfg = SQLiteConfig().apply { setReadOnly(true) }
            val real = DriverManager.getConnection("jdbc:sqlite:${tmp.resolve("zcode.sqlite")}", cfg.toProperties())
            java.lang.reflect.Proxy.newProxyInstance(java.sql.Connection::class.java.classLoader,
                arrayOf(java.sql.Connection::class.java)) { _, method, arguments ->
                if (method.name == "prepareStatement" && (arguments?.firstOrNull() as? String)?.startsWith("SELECT p.data,") == true) contentQueries++
                try { method.invoke(real, *(arguments ?: emptyArray())) }
                catch (failure: java.lang.reflect.InvocationTargetException) { throw failure.targetException }
            } as java.sql.Connection
        })
        assertTrue(source.load(AgentKind.ZCODE, cwd.toString(), sid).isFailure)
        assertEquals(0, contentQueries, "size preflight must refuse before querying payload text")
    }
}
