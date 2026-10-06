package dev.ccpocket.daemon.disk

import dev.ccpocket.daemon.pins.DurablePinFiles
import dev.ccpocket.daemon.session.ScanCompleteness
import dev.ccpocket.daemon.session.SessionScan
import dev.ccpocket.protocol.AgentKind
import dev.ccpocket.protocol.ManagedSessionErrors
import dev.ccpocket.protocol.ManagedSessionOrigin
import dev.ccpocket.protocol.ObservationAttributions
import dev.ccpocket.protocol.ObservationBinding
import dev.ccpocket.protocol.SessionSummary
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Read-only observation bindings in the managed store (docs/design/DOTS-SESSION-OBSERVABILITY.md §4.1 / §4.6):
 * import + bind is one write, a file without bindings keeps its schema-1 bytes, a file with one is schema 2 (an older
 * daemon refuses it read-only instead of dropping the policy), and a corrupt store answers "unavailable", never
 * "unbound".
 */
class ManagedSessionStoreObservationTest {
    private val tmp: Path = Files.createTempDirectory("ccp-managed-obs")
    private val root: File = Files.createDirectories(tmp.resolve("store")).toFile()
    private val project: Path = Files.createDirectories(tmp.resolve("proj"))
    private val workdir = project.toString()
    private val canonical = assertNotNull(canonicalManagedWorkdir(workdir))
    private var now = 1_000L
    private val binding = ObservationBinding(parentTaskRef = "Dot · refactor parser")

    @AfterTest
    fun cleanup() = tmp.toFile().deleteRecursively().let {}

    private fun store() = ManagedSessionStore(root, DurablePinFiles(), ::canonicalManagedWorkdir) { now++ }

    private fun scan(vararg ids: String) =
        SessionScan(AgentKind.CODEX, workdir, ids.map { SessionSummary(it, "t $it", "p", 1, workdir, 0, agent = AgentKind.CODEX) }, ScanCompleteness.COMPLETE)

    private fun fileText(s: ManagedSessionStore) = s.fileFor(canonical).readText()

    @Test
    fun plain_import_keeps_schema_one_and_writes_no_observation_key() {
        val s = store()
        assertIs<ManagedMutation.Committed>(s.import(workdir, AgentKind.CODEX, "a", scan("a")))
        val text = fileText(s)
        assertTrue("\"schemaVersion\":1" in text, text)
        assertFalse("observation" in text, "an unbound member must not carry the key (an old daemon would reject it): $text")
        assertEquals(ObservationLookup.Unbound, s.observationOf(workdir, AgentKind.CODEX, "a"))
    }

    @Test
    fun import_with_binding_is_one_write_under_schema_two_and_survives_restart() {
        val s = store()
        val out = assertIs<ManagedMutation.Committed>(s.import(workdir, AgentKind.CODEX, "a", scan("a"), binding))
        assertTrue(out.changed)
        val member = assertNotNull(out.state.member(AgentKind.CODEX, "a"))
        assertEquals(binding, member.observation)
        assertEquals(ManagedSessionOrigin.EXPLICIT_IMPORT, member.origin)
        assertEquals(2, out.state.schemaVersion)
        assertTrue("\"schemaVersion\":2" in fileText(s))
        // a fresh instance (restart) reads the policy back
        assertEquals(ObservationLookup.Bound(binding), store().observationOf(workdir, AgentKind.CODEX, "a"))
        // the same import again is idempotent; a different binding on an existing member updates it in place
        assertFalse(assertIs<ManagedMutation.Committed>(s.import(workdir, AgentKind.CODEX, "a", scan("a"), binding)).changed)
        val changed = assertIs<ManagedMutation.Committed>(s.import(workdir, AgentKind.CODEX, "a", scan("a"), binding.copy(parentTaskRef = "other")))
        assertTrue(changed.changed)
        assertEquals("other", changed.state.member(AgentKind.CODEX, "a")?.observation?.parentTaskRef)
    }

    @Test
    fun set_and_clear_binding_on_an_existing_member_only() {
        val s = store()
        assertIs<ManagedMutation.Refused>(s.setObservation(workdir, AgentKind.CODEX, "ghost", binding)).let {
            assertEquals(ManagedSessionErrors.NOT_FOUND, it.error)
        }
        assertIs<ManagedMutation.Committed>(s.import(workdir, AgentKind.CODEX, "a", scan("a")))
        assertTrue(assertIs<ManagedMutation.Committed>(s.setObservation(workdir, AgentKind.CODEX, "a", binding)).changed)
        assertEquals(ObservationLookup.Bound(binding), s.observationOf(workdir, AgentKind.CODEX, "a"))
        assertFalse(assertIs<ManagedMutation.Committed>(s.setObservation(workdir, AgentKind.CODEX, "a", binding)).changed)
        // clear: back to schema 1 bytes, the member itself stays
        val cleared = assertIs<ManagedMutation.Committed>(s.setObservation(workdir, AgentKind.CODEX, "a", null))
        assertTrue(cleared.changed)
        assertNull(cleared.state.member(AgentKind.CODEX, "a")?.observation)
        assertEquals(1, cleared.state.schemaVersion)
        assertFalse("observation" in fileText(s))
        assertEquals(ObservationLookup.Unbound, s.observationOf(workdir, AgentKind.CODEX, "a"))
        // removing the member drops the binding with it (the native transcript is not this store's business)
        assertIs<ManagedMutation.Committed>(s.setObservation(workdir, AgentKind.CODEX, "a", binding))
        assertIs<ManagedMutation.Committed>(s.remove(workdir, AgentKind.CODEX, "a"))
        assertEquals(ObservationLookup.Unbound, s.observationOf(workdir, AgentKind.CODEX, "a"))
    }

    @Test
    fun invalid_bindings_are_refused_untouched() {
        val s = store()
        assertIs<ManagedMutation.Committed>(s.import(workdir, AgentKind.CODEX, "a", scan("a")))
        for (bad in listOf(
            binding.copy(attribution = ObservationAttributions.VERIFIED),
            binding.copy(readOnly = false),
            binding.copy(source = "../x"),
            binding.copy(parentTaskRef = "x".repeat(600)),
        )) {
            assertEquals(ManagedSessionErrors.OBSERVATION_INVALID, assertIs<ManagedMutation.Refused>(s.setObservation(workdir, AgentKind.CODEX, "a", bad)).error)
            assertEquals(ManagedSessionErrors.OBSERVATION_INVALID, assertIs<ManagedMutation.Refused>(s.import(workdir, AgentKind.CODEX, "b", scan("a", "b"), bad)).error)
        }
        assertEquals(ObservationLookup.Unbound, s.observationOf(workdir, AgentKind.CODEX, "a"))
        assertNull(assertIs<ManagedProjectRead.Loaded>(s.read(workdir)).state.member(AgentKind.CODEX, "b"), "a refused import+bind imports nothing")
    }

    @Test
    fun a_binding_written_under_schema_one_or_a_corrupt_store_is_unavailable_not_unbound() {
        val s = store()
        assertIs<ManagedMutation.Committed>(s.import(workdir, AgentKind.CODEX, "a", scan("a"), binding))
        val file = s.fileFor(canonical)
        // tamper: claim schema 1 while carrying a binding — not something this build writes
        file.writeText(file.readText().replace("\"schemaVersion\":2", "\"schemaVersion\":1"))
        val fresh = store()
        assertIs<ManagedProjectRead.Corrupt>(fresh.read(workdir))
        val unavailable = assertIs<ObservationLookup.Unavailable>(fresh.observationOf(workdir, AgentKind.CODEX, "a"))
        // no automatic repair exists, so the reason must name the file the owner has to fix or remove
        assertTrue(unavailable.reason.contains(".json"), "reason names the store file: ${unavailable.reason}")
        assertIs<ObservationLookup.Unavailable>(fresh.observationOf(workdir, AgentKind.CODEX, "never-a-member"))
        assertEquals(ManagedSessionErrors.STORE_CORRUPT, assertIs<ManagedMutation.Refused>(fresh.setObservation(workdir, AgentKind.CODEX, "a", null)).error)
        // undecodable bytes: same answer
        file.writeText("{not json")
        assertIs<ObservationLookup.Unavailable>(store().observationOf(workdir, AgentKind.CODEX, "a"))
        // a missing file is plainly unbound
        file.delete()
        assertEquals(ObservationLookup.Unbound, store().observationOf(workdir, AgentKind.CODEX, "a"))
    }

    @Test
    fun an_older_daemon_shape_reads_a_schema_two_file_as_corrupt_rather_than_rewriting_it() {
        // the pre-observation reader is strict (unknown key / unknown schema → Corrupt). Simulate it with the same
        // strict JSON over the old member shape.
        val s = store()
        assertIs<ManagedMutation.Committed>(s.import(workdir, AgentKind.CODEX, "a", scan("a"), binding))
        val text = fileText(s)
        val oldReader = kotlinx.serialization.json.Json { ignoreUnknownKeys = false }
        val decoded = runCatching { oldReader.decodeFromString(OldMember.serializer(), extractFirstMember(text)) }
        assertTrue(decoded.isFailure, "an old member reader must trip on the observation key, not drop it")
        assertTrue("\"schemaVersion\":2" in text, "and the schema marker alone already refuses it on the old build")
    }

    @kotlinx.serialization.Serializable
    private data class OldMember(val key: ManagedSessionKey, val origin: ManagedSessionOrigin, val createdAt: Long, val lastKnownSummary: LastKnownSummary? = null)

    private fun extractFirstMember(text: String): String {
        val start = text.indexOf("\"members\":[") + "\"members\":[".length
        var depth = 0
        for (i in start until text.length) {
            when (text[i]) { '{' -> depth++; '}' -> { depth--; if (depth == 0) return text.substring(start, i + 1) } }
        }
        error("no member in $text")
    }
}
