package dev.ccpocket.daemon.disk

import dev.ccpocket.protocol.ConvoHistory
import dev.ccpocket.protocol.DiagnosticContext
import dev.ccpocket.protocol.Envelope
import dev.ccpocket.protocol.HistoryMessage
import dev.ccpocket.protocol.PocketJson
import java.nio.file.Path
import kotlin.io.path.fileSize
import kotlin.io.path.readLines
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The first window against real-size transcripts (7 MB and 20 MB, screenshot lines near 1 MB): the narrowed
 * window stays under its encoded budget, keeps its floor, and — walked up page by page the way the phone does —
 * yields exactly the rows of the whole transcript, once each, in order. Off, it is today's window.
 *
 * Also prints the build time and frame size of both windows as a baseline (stdout of the test report; never
 * asserted — timings on a shared machine are not a pass/fail signal).
 */
class LargeTranscriptFirstWindowTest {

    private companion object {
        val dir: Path by lazy { LargeTranscriptFixture.tempDir() }
        // targets count characters; the CJK in the prose makes the files ~10% larger in bytes (~7 MB / ~20 MB)
        val mb7: Path by lazy { LargeTranscriptFixture.write(dir, 6_350_000, seed = 7, images = 3) }
        val mb20: Path by lazy { LargeTranscriptFixture.write(dir, 17_800_000, seed = 20, images = 8) }
    }

    private fun frame(slice: ReplaySlice): Int = PocketJson.encodeToString(
        Envelope.serializer(),
        Envelope(
            "123456", 0L,
            body = ConvoHistory(
                "00000000-0000-0000-0000-000000000000", slice.messages, slice.lastSeq, slice.firstSeq,
                slice.delta, slice.hasMore, DiagnosticContext("1234567890abcdef1234567890abcdef", 1, "abcdef1234567890"),
            ),
        ),
    ).encodeToByteArray().size

    /** Every replayed row of the file, with the same per-message image cap every replay frame applies. */
    private fun everything(f: Path): List<HistoryMessage> =
        TranscriptReplay.page(f, beforeSeq = Long.MAX_VALUE, limit = Int.MAX_VALUE, maxFrameTextBytes = Long.MAX_VALUE).messages

    private fun walkUp(f: Path, first: ReplaySlice): List<HistoryMessage> {
        val out = first.messages.toMutableList()
        var before = first.firstSeq
        var more = first.hasMore
        while (more && before != null) {
            val page = TranscriptReplay.page(f, before) // the phone's FetchHistoryPage: default limit 100
            assertTrue(page.messages.isNotEmpty(), "hasMore promised rows before $before")
            out.addAll(0, page.messages)
            before = page.firstSeq
            more = page.hasMore
        }
        return out
    }

    private fun timed(block: () -> ReplaySlice): Long {
        val t = System.nanoTime(); block(); return (System.nanoTime() - t) / 1_000_000
    }

    private fun check(f: Path, label: String, walkToday: Boolean) {
        // cold = the first read of this file in the JVM: JIT still warming, and its screenshots get thumbnailed
        val coldMs = timed { TranscriptReplay.slice(f, 0) }
        val all = everything(f)
        // today's window (off): exactly the newest-100 window the old code built — the same rows the page path
        // returns for "the newest 100 rows", byte for byte once encoded
        val off = TranscriptReplay.slice(f, sinceSeq = 0)
        val legacy = TranscriptReplay.page(f, beforeSeq = Long.MAX_VALUE, limit = 100)
        assertEquals(legacy.messages, off.messages)
        assertEquals(legacy.firstSeq, off.firstSeq)
        assertEquals(legacy.hasMore, off.hasMore)

        val first = TranscriptReplay.slice(f, sinceSeq = 0, firstWindowBytes = ReplaySlicer.FIRST_WINDOW_BYTES)
        val thumbs = all.flatMap { it.images }.map { it.base64.length }
        println("[first-window] $label shape: today ${off.messages.size} rows ${frame(off)} B, narrowed ${first.messages.size} rows " +
            "${frame(first)} B, thumbnails $thumbs (originals ~${LargeTranscriptFixture.screenshotPng(1).size * 4 / 3} B base64), " +
            "image rows from the end: " +
            off.messages.withIndex().filter { it.value.images.isNotEmpty() }.map { off.messages.size - it.index })
        assertTrue(first.messages.size >= ReplaySlicer.FIRST_WINDOW_MIN_ROWS)
        assertTrue(first.messages.size < off.messages.size, "$label: the budget should bite on this fixture")
        if (first.messages.size > ReplaySlicer.FIRST_WINDOW_MIN_ROWS) {
            assertTrue(frame(first) <= ReplaySlicer.FIRST_WINDOW_BYTES, "$label: ${frame(first)} B")
        }
        assertEquals(all.takeLast(first.messages.size), first.messages) // newest rows, unaltered
        // the seam: first window + every page up to the top == the whole transcript, row for row
        assertEquals(all, walkUp(f, first), "$label: paging after the narrowed window lost or repeated rows")
        if (walkToday) assertEquals(all, walkUp(f, off), "$label: paging after today's window lost or repeated rows")

        // warm = median of three
        val offMs = (1..3).map { timed { TranscriptReplay.slice(f, 0) } }.sorted()[1]
        val newMs = (1..3).map { timed { TranscriptReplay.slice(f, 0, firstWindowBytes = ReplaySlicer.FIRST_WINDOW_BYTES) } }.sorted()[1]
        val images = off.messages.sumOf { m -> m.images.sumOf { it.base64.length } }
        val lines = f.readLines()
        println(
            "[first-window] $label: file ${f.fileSize()} B / ${lines.size} lines (longest ${lines.maxOf { it.length }} chars), " +
                "${all.size} visible rows | today: ${off.messages.size} rows, frame ${frame(off)} B (images $images B), " +
                "build cold ${coldMs} ms / warm ${offMs} ms | narrowed: ${first.messages.size} rows, frame ${frame(first)} B, " +
                "build warm ${newMs} ms",
        )
    }

    @Test
    fun seven_mb_transcript() = check(mb7, "7MB", walkToday = true)

    @Test
    fun twenty_mb_transcript() = check(mb20, "20MB", walkToday = false)

    @Test
    fun a_screenshot_stored_twice_in_the_transcript_replays_once_as_one_thumbnail() {
        val all = everything(mb7)
        val shots = all.filter { it.images.isNotEmpty() }
        assertEquals(3, shots.size) // one row per screenshot tool result, never a second row from toolUseResult
        shots.forEach { m ->
            assertEquals(1, m.images.size)
            assertEquals("Read", m.tool)
            // the thumbnail, not either stored copy of the ~0.5 MB original
            assertTrue(m.images.single().base64.length <= dev.ccpocket.daemon.media.ImageThumbnail.MAX_BASE64_BYTES)
        }
    }
}
