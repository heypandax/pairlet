package dev.ccpocket.daemon.disk

import dev.ccpocket.protocol.FileChunk
import dev.ccpocket.protocol.MAX_UPLOAD_BYTES
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class UploadReassemblerTest {

    @TempDir
    lateinit var tmp: Path

    private var now = 1_000L
    private fun buf() = UploadReassembler(nowMs = { now })

    private fun chunk(
        convo: String = "c1",
        capture: String = "cap-1",
        idx: Int,
        last: Boolean = false,
        name: String = "report.pdf",
        payload: ByteArray = byteArrayOf(0x41),
        totalBytes: Long = 0,
    ) = FileChunk(convo, capture, idx, last, name, "application/pdf", Base64.getEncoder().encodeToString(payload), totalBytes)

    /** The phone chunks the RAW bytes and base64s each chunk independently — mirror that here. */
    private fun chunksOf(payload: ByteArray, parts: Int, name: String = "report.pdf", capture: String = "cap-1"): List<FileChunk> {
        val size = (payload.size + parts - 1) / parts
        return (0 until parts).map { i ->
            val slice = payload.copyOfRange(i * size, minOf((i + 1) * size, payload.size))
            chunk(capture = capture, idx = i, last = i == parts - 1, name = name, payload = slice)
        }
    }

    private fun landed(name: String, capture: String = "cap-1"): Path =
        tmp.resolve(".ccpocket").resolve("inbox").resolve(capture).resolve(name)

    // ---- reassembly ------------------------------------------------------------------------

    @Test
    fun single_chunk_upload_lands_with_relative_path_and_bytes() {
        val r = buf().add(chunk(idx = 0, last = true, payload = "hello file".toByteArray()), tmp)
        val c = assertIs<UploadReassembler.Result.Complete>(r)
        assertEquals("cap-1", c.captureId)
        assertEquals("report.pdf", c.name)
        assertEquals(Path.of(".ccpocket", "inbox", "cap-1", "report.pdf").toString(), c.relPath)
        assertEquals(10L, c.size)
        assertContentEquals("hello file".toByteArray(), Files.readAllBytes(landed("report.pdf")))
        assertFalse(Files.exists(landed("report.pdf.part"))) // no leftover partial
    }

    @Test
    fun chunks_join_in_idx_order_even_when_arriving_out_of_order() {
        val b = buf()
        val payload = "the quick brown fox jumps over the lazy dog".toByteArray()
        val (c0, c1, c2) = chunksOf(payload, parts = 3)
        assertIs<UploadReassembler.Result.Incomplete>(b.add(c2, tmp))
        assertIs<UploadReassembler.Result.Incomplete>(b.add(c0, tmp))
        val done = assertIs<UploadReassembler.Result.Complete>(b.add(c1, tmp))
        assertEquals(payload.size.toLong(), done.size)
        assertContentEquals(payload, Files.readAllBytes(landed("report.pdf")))
    }

    @Test
    fun duplicate_chunks_are_ignored_not_double_written() {
        val b = buf()
        val payload = "abcdefgh".toByteArray()
        val parts = chunksOf(payload, parts = 2)
        assertIs<UploadReassembler.Result.Incomplete>(b.add(parts[0], tmp))
        assertIs<UploadReassembler.Result.Incomplete>(b.add(parts[0], tmp)) // resend of an already-written idx
        val done = assertIs<UploadReassembler.Result.Complete>(b.add(parts[1], tmp))
        assertEquals(payload.size.toLong(), done.size)
        assertContentEquals(payload, Files.readAllBytes(landed("report.pdf")))
    }

    @Test
    fun late_chunks_of_a_finished_capture_are_stale() {
        val b = buf()
        assertIs<UploadReassembler.Result.Complete>(b.add(chunk(idx = 0, last = true), tmp))
        assertIs<UploadReassembler.Result.Stale>(b.add(chunk(idx = 0, last = true), tmp))
    }

    @Test
    fun concurrent_captures_do_not_interfere() {
        val b = buf()
        val a = chunksOf("AAAA".toByteArray(), parts = 2, name = "a.txt", capture = "cap-a")
        val z = chunksOf("ZZZZZZ".toByteArray(), parts = 2, name = "z.txt", capture = "cap-z")
        assertIs<UploadReassembler.Result.Incomplete>(b.add(a[0], tmp))
        assertIs<UploadReassembler.Result.Incomplete>(b.add(z[0], tmp))
        assertIs<UploadReassembler.Result.Complete>(b.add(a[1], tmp))
        assertIs<UploadReassembler.Result.Complete>(b.add(z[1], tmp))
        assertContentEquals("AAAA".toByteArray(), Files.readAllBytes(landed("a.txt", "cap-a")))
        assertContentEquals("ZZZZZZ".toByteArray(), Files.readAllBytes(landed("z.txt", "cap-z")))
    }

    // ---- cancel / expiry -------------------------------------------------------------------

    @Test
    fun cancel_drops_state_and_deletes_the_partial() {
        val b = buf()
        assertIs<UploadReassembler.Result.Incomplete>(b.add(chunk(idx = 0), tmp))
        assertTrue(Files.exists(landed("report.pdf.part")))
        b.cancel("c1", "cap-1")
        assertFalse(Files.exists(tmp.resolve(".ccpocket").resolve("inbox").resolve("cap-1"))) // dir gone
        assertIs<UploadReassembler.Result.Stale>(b.add(chunk(idx = 1, last = true), tmp)) // no resurrection
        assertEquals(0, b.inFlight("c1"))
    }

    @Test
    fun cancel_from_another_conversation_is_ignored() {
        val b = buf()
        assertIs<UploadReassembler.Result.Incomplete>(b.add(chunk(idx = 0), tmp))
        b.cancel("someone-else", "cap-1")
        assertEquals(1, b.inFlight("c1"))
        assertIs<UploadReassembler.Result.Complete>(b.add(chunk(idx = 1, last = true), tmp))
    }

    @Test
    fun idle_uploads_expire_and_their_partials_are_swept() {
        val b = buf()
        assertIs<UploadReassembler.Result.Incomplete>(b.add(chunk(idx = 0), tmp))
        assertTrue(Files.exists(landed("report.pdf.part")))
        now += UploadReassembler.IDLE_EXPIRY_MS + 1
        // any later activity triggers the sweep; the expired capture's tail chunk reads stale
        assertIs<UploadReassembler.Result.Stale>(b.add(chunk(idx = 1, last = true), tmp))
        assertFalse(Files.exists(landed("report.pdf.part")))
        assertEquals(0, b.inFlight("c1"))
    }

    // ---- limits ----------------------------------------------------------------------------

    @Test
    fun declared_oversize_is_refused_before_any_byte_lands() {
        val r = buf().add(chunk(idx = 0, totalBytes = MAX_UPLOAD_BYTES + 1), tmp)
        val refused = assertIs<UploadReassembler.Result.Refused>(r)
        assertEquals(UploadReassembler.ERR_TOO_LARGE, refused.error)
        assertFalse(Files.exists(tmp.resolve(".ccpocket"))) // nothing was created
    }

    @Test
    fun refusal_is_emitted_once_then_the_stream_goes_stale() {
        val b = buf()
        assertIs<UploadReassembler.Result.Refused>(b.add(chunk(idx = 0, totalBytes = MAX_UPLOAD_BYTES + 1), tmp))
        assertIs<UploadReassembler.Result.Stale>(b.add(chunk(idx = 1), tmp)) // no error spam per chunk
    }

    @Test
    fun corrupt_base64_aborts_and_cleans_up() {
        val b = buf()
        assertIs<UploadReassembler.Result.Incomplete>(b.add(chunk(idx = 0), tmp))
        val bad = chunk(idx = 1, last = true).copy(base64 = "!!!not-base64!!!")
        val r = assertIs<UploadReassembler.Result.Refused>(b.add(bad, tmp))
        assertTrue("corrupted" in r.error)
        assertFalse(Files.exists(tmp.resolve(".ccpocket").resolve("inbox").resolve("cap-1")))
    }

    @Test
    fun per_conversation_and_global_concurrency_are_capped() {
        val b = buf()
        // 4 in flight for one convo is the cap
        repeat(4) { i -> assertIs<UploadReassembler.Result.Incomplete>(b.add(chunk(capture = "cap-$i", idx = 0), tmp)) }
        val fifth = assertIs<UploadReassembler.Result.Refused>(b.add(chunk(capture = "cap-4", idx = 0), tmp))
        assertTrue("this session" in fifth.error)
        // other convos can still start (global cap is 8)
        repeat(4) { i ->
            assertIs<UploadReassembler.Result.Incomplete>(b.add(chunk(convo = "c2", capture = "other-$i", idx = 0), tmp))
        }
        val ninth = assertIs<UploadReassembler.Result.Refused>(b.add(chunk(convo = "c3", capture = "cap-9", idx = 0), tmp))
        assertTrue("wait" in ninth.error)
    }

    // ---- write-surface fencing ---------------------------------------------------------------

    @Test
    fun traversal_shaped_capture_ids_are_refused() {
        for (id in listOf("../evil", "a/b", "a\\b", "..", "x", "sneaky/../../etc")) {
            val r = buf().add(chunk(capture = id, idx = 0, last = true), tmp)
            val refused = assertIs<UploadReassembler.Result.Refused>(r, "captureId '$id' must be refused")
            assertEquals("malformed upload id", refused.error)
        }
        assertFalse(Files.exists(tmp.parent.resolve("evil")))
        assertFalse(Files.exists(tmp.resolve(".ccpocket")))
    }

    @Test
    fun hostile_names_are_reduced_to_safe_basenames_inside_the_inbox() {
        val b = buf()
        val r = b.add(chunk(idx = 0, last = true, name = "../../../../etc/passwd", payload = "x".toByteArray()), tmp)
        val c = assertIs<UploadReassembler.Result.Complete>(r)
        assertEquals("passwd", c.name) // basename only — landed inside the capture dir
        assertTrue(Files.exists(landed("passwd")))
        assertFalse(Files.exists(tmp.resolve("etc")))
    }

    @Test
    fun unusable_names_are_refused() {
        for (bad in listOf("", "   ", ".", "..", "...", "/", "\\\\")) {
            val r = buf().add(chunk(capture = "cap-${bad.hashCode().toString(16).replace('-', 'n')}", idx = 0, last = true, name = bad), tmp)
            assertIs<UploadReassembler.Result.Refused>(r, "name '$bad' must be refused")
        }
    }

    @Test
    fun sanitize_flattens_separators_whitespace_at_and_reserved_names() {
        assertEquals("my_report_final.pdf", UploadReassembler.sanitizeName("my report\tfinal.pdf"))
        assertEquals("passwd", UploadReassembler.sanitizeName("/etc/passwd"))
        assertEquals("evil.txt", UploadReassembler.sanitizeName("..\\..\\evil.txt"))
        assertEquals("notes_v2.md", UploadReassembler.sanitizeName("notes@v2.md")) // '@' would break the @-token
        assertEquals("_CON.txt", UploadReassembler.sanitizeName("CON.txt"))        // Windows device name
        assertNull(UploadReassembler.sanitizeName(".."))
        assertNull(UploadReassembler.sanitizeName(""))
        val long = UploadReassembler.sanitizeName("x".repeat(300) + ".csv")!!
        assertTrue(long.length <= 120 && long.endsWith(".csv")) // extension survives the cap
    }

    @Test
    fun symlinked_workdir_still_lands_inside_the_real_tree() {
        // the workdir the registry hands over may itself be a symlink — landing must follow to the
        // real tree and stay contained (mirrors DirectoryService's canonicalize-then-contain guard)
        val real = Files.createDirectories(tmp.resolve("real-project"))
        val link = Files.createSymbolicLink(tmp.resolve("link-project"), real)
        val r = buf().add(chunk(idx = 0, last = true, payload = "via link".toByteArray()), link)
        val c = assertIs<UploadReassembler.Result.Complete>(r)
        val landedReal = real.resolve(".ccpocket").resolve("inbox").resolve("cap-1").resolve("report.pdf")
        assertTrue(Files.exists(landedReal))
        assertContentEquals("via link".toByteArray(), Files.readAllBytes(landedReal))
        assertEquals(Path.of(".ccpocket", "inbox", "cap-1", "report.pdf").toString(), c.relPath)
    }

    @Test
    fun a_symlink_pre_planted_at_the_part_path_is_not_followed() {
        // hardening: a `.part` symlink pointing outside must not be written THROUGH. This needs local
        // FS write to set up (outside the real threat model) — pin the defense regardless.
        val outside = Files.createDirectories(tmp.parent.resolve("outside-${System.nanoTime()}"))
        val victim = outside.resolve("victim.txt").also { Files.write(it, "original".toByteArray()) }
        val inbox = Files.createDirectories(tmp.resolve(".ccpocket").resolve("inbox").resolve("cap-1"))
        Files.createSymbolicLink(inbox.resolve("report.pdf.part"), victim) // pre-plant the trap
        buf().add(chunk(idx = 0, last = true, payload = "attacker".toByteArray()), tmp)
        // the victim outside the tree is untouched; the real file landed inside the capture dir
        assertContentEquals("original".toByteArray(), Files.readAllBytes(victim))
        assertContentEquals("attacker".toByteArray(), Files.readAllBytes(landed("report.pdf")))
        runCatching { Files.walk(outside).sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) } }
    }

    @Test
    fun a_symlinked_inbox_root_pointing_outside_is_refused() {
        // if `.ccpocket/inbox` itself is a symlink out of the workspace, the upload is refused before
        // any byte lands (the canonical inbox root must stay inside the canonical workdir)
        val outside = Files.createDirectories(tmp.parent.resolve("inbox-escape-${System.nanoTime()}"))
        Files.createDirectories(tmp.resolve(".ccpocket"))
        Files.createSymbolicLink(tmp.resolve(".ccpocket").resolve("inbox"), outside)
        val r = buf().add(chunk(idx = 0, last = true, payload = "x".toByteArray()), tmp)
        assertIs<UploadReassembler.Result.Refused>(r)
        assertEquals(0, outside.toFile().list()?.size ?: 0) // nothing written into the escape target
        runCatching { Files.walk(outside).sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) } }
    }

    @Test
    fun inbox_writes_a_self_gitignore_so_the_repo_stays_clean() {
        buf().add(chunk(idx = 0, last = true), tmp)
        val gi = tmp.resolve(".ccpocket").resolve(".gitignore")
        assertTrue(Files.exists(gi))
        assertEquals("*", Files.readAllLines(gi).single())
    }

    @Test
    fun actual_bytes_beyond_the_cap_abort_even_when_totalBytes_lied() {
        // a client that under-declares (totalBytes=0) is still stopped by the write-side cap
        val b = UploadReassembler(nowMs = { now }, maxUploadBytes = 10)
        assertIs<UploadReassembler.Result.Incomplete>(b.add(chunk(idx = 0, payload = ByteArray(8)), tmp))
        val r = b.add(chunk(idx = 1, last = true, payload = ByteArray(8)), tmp)
        assertEquals(UploadReassembler.ERR_TOO_LARGE, assertIs<UploadReassembler.Result.Refused>(r).error)
        assertFalse(Files.exists(tmp.resolve(".ccpocket").resolve("inbox").resolve("cap-1"))) // partial swept
    }
}
