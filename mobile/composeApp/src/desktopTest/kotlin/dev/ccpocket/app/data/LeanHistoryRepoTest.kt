package dev.ccpocket.app.data

import dev.ccpocket.app.pairing.PairedDaemon
import dev.ccpocket.protocol.ChatRole
import dev.ccpocket.protocol.ClientCaps
import dev.ccpocket.protocol.ConvoHistory
import dev.ccpocket.protocol.DaemonInfo
import dev.ccpocket.protocol.FetchImage
import dev.ccpocket.protocol.Frame
import dev.ccpocket.protocol.HistoryMessage
import dev.ccpocket.protocol.ImageContent
import dev.ccpocket.protocol.ImageData
import dev.ccpocket.protocol.ImageRefs
import dev.ccpocket.protocol.SessionLive
import dev.ccpocket.protocol.ToolEvent
import dev.ccpocket.protocol.ToolPhase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Lean history, client half (docs/design/SLOW-LINK-RESILIENCE.md §6): what the app declares, how a preview
 * rides a chat row, and the exchange that turns an opened preview into the full picture.
 */
@OptIn(ExperimentalEncodingApi::class)
class LeanHistoryRepoTest {

    private val sent = mutableListOf<Frame>()

    private fun repo(lean: Boolean = true) = PocketRepository(CoroutineScope(Dispatchers.Unconfined)).apply {
        leanHistory = lean
        onSendForTest = { sent += it }
        paired.value = PairedDaemon(relay = "wss://test", accountId = "acct-lean", daemonPub = "pk", deviceId = "dev", credential = "cred")
        convoId.value = "c1"
        receiveForTest(SessionLive("c1", "/w", "sid-1", executing = false))
    }

    private val fullBytes = ByteArray(4_000) { (it * 7).toByte() }
    private val ref = ImageRefs.of(fullBytes)
    private fun preview(tag: String = "preview", r: String? = ref) = ImageData("image/jpeg", Base64.Default.encode(tag.encodeToByteArray()), ref = r)
    private fun full() = ImageData("image/jpeg", Base64.Default.encode(fullBytes))

    private fun toolRow(seq: Long, vararg images: ImageData) = HistoryMessage(ChatRole.TOOL, "shot.png", tool = "Read", ok = true, images = images.toList(), seq = seq)

    // ---- what the app tells the daemon ----

    @Test
    fun the_capabilities_follow_the_ui_that_is_running() {
        for (lean in listOf(true, false)) {
            sent.clear()
            repo(lean).receiveForTest(DaemonInfo()) // a handshaken session: the app re-declares on it
            val caps = sent.filterIsInstance<ClientCaps>().last()
            assertEquals(lean, caps.supportsImagePreviews)
            assertEquals(lean, caps.supportsShortHistoryWindow)
        }
    }

    // ---- a preview on a row ----

    @Test
    fun a_replayed_row_keeps_each_pictures_ref_and_its_transcript_cursor() {
        val r = repo()
        r.receiveForTest(ConvoHistory("c1", listOf(
            toolRow(7, preview("a"), ImageData("image/jpeg", Base64.Default.encode("whole".encodeToByteArray()))),
            HistoryMessage(ChatRole.USER, "mine", images = listOf(preview("b")), seq = 9, uuid = "u9"),
        ), lastSeq = 9, firstSeq = 7))
        val tool = assertIs<ChatItem.Tool>(r.messages[0])
        assertEquals(listOf(ref, null), tool.imageRefs) // index for index with the pictures
        assertEquals(2, tool.images.size)
        assertEquals(7L, tool.seq)
        val user = assertIs<ChatItem.User>(r.messages[1])
        assertEquals(listOf(ref), user.imageRefs)
    }

    @Test
    fun rows_without_previews_look_exactly_as_they_always_did() {
        val r = repo()
        val whole = ImageData("image/jpeg", Base64.Default.encode("whole".encodeToByteArray()))
        r.receiveForTest(ConvoHistory("c1", listOf(toolRow(3, whole)), lastSeq = 3, firstSeq = 3))
        assertTrue(assertIs<ChatItem.Tool>(r.messages.single()).imageRefs.isEmpty())
    }

    @Test
    fun a_picture_that_fails_to_decode_takes_its_ref_with_it() {
        val broken = ImageData("image/jpeg", "not base64 !!!", ref = ImageRefs.of(byteArrayOf(1)))
        val refs = decodeImageRefs(listOf(broken, preview("ok")))
        assertEquals(listOf(ref), refs)
        assertEquals(1, decodeImages(listOf(broken, preview("ok"))).size)
    }

    @Test
    fun a_live_tool_result_carries_its_previews_onto_the_card() {
        val t = ChatTranscript()
        t.onToolEvent(ToolEvent("c1", 1, ToolPhase.START, "Read", "shot.png", toolUseId = "t1"))
        t.onToolEvent(ToolEvent("c1", 2, ToolPhase.RESULT, "Read", ok = true, toolUseId = "t1", images = listOf(preview())))
        val card = assertIs<ChatItem.Tool>(t.messages.single())
        assertEquals(listOf(ref), card.imageRefs)
        assertNull(card.seq) // a card that only arrived live has no cursor yet
    }

    // ---- the exchange ----

    @Test
    fun opening_a_preview_fetches_the_full_picture_once_and_swaps_it_in() {
        val r = repo()
        r.requestFullImage(ref, seq = 7, index = 1)
        val fetch = sent.filterIsInstance<FetchImage>().single()
        assertEquals("c1", fetch.convoId); assertEquals(ref, fetch.ref); assertEquals(7L, fetch.seq); assertEquals(1, fetch.index)
        assertTrue(ref in r.fullImagePending)

        r.requestFullImage(ref, seq = 7, index = 1) // the viewer recomposed, the page was swiped back to…
        assertEquals(1, sent.filterIsInstance<FetchImage>().size, "one request per picture while it is in flight")

        r.receiveForTest(ImageContent("c1", ref, image = full(), requestId = fetch.requestId))
        assertContentEquals(fullBytes, r.fullImages[ref])
        assertFalse(ref in r.fullImagePending)
        r.requestFullImage(ref, seq = 7, index = 1)
        assertEquals(1, sent.filterIsInstance<FetchImage>().size, "a picture already here is not fetched again")
    }

    @Test
    fun a_refusal_keeps_the_preview_and_says_so_and_opening_again_asks_again() {
        val r = repo()
        r.requestFullImage(ref, seq = null, index = 0)
        val first = sent.filterIsInstance<FetchImage>().single()
        r.receiveForTest(ImageContent("c1", ref, error = ImageContent.ERROR_UNAVAILABLE, requestId = first.requestId))
        assertNull(r.fullImages[ref])
        assertEquals(true, r.fullImageUnavailable[ref])
        assertFalse(ref in r.fullImagePending)

        r.requestFullImage(ref, seq = 7, index = 0)
        assertEquals(2, sent.filterIsInstance<FetchImage>().size)
        assertNull(r.fullImageUnavailable[ref], "asking again clears the note until the answer comes")
    }

    @Test
    fun only_the_answer_to_the_outstanding_request_counts() {
        val r = repo()
        r.requestFullImage(ref, seq = 7, index = 0)
        val fetch = sent.filterIsInstance<FetchImage>().single()
        r.receiveForTest(ImageContent("c1", ref, image = full(), requestId = "someone-elses"))
        r.receiveForTest(ImageContent("another-conversation", ref, image = full(), requestId = fetch.requestId))
        assertNull(r.fullImages[ref])
        assertTrue(ref in r.fullImagePending)
        // an answer nobody asked for (no request in flight at all) is ignored too
        val other = ImageRefs.of(byteArrayOf(5))
        r.receiveForTest(ImageContent("c1", other, image = full()))
        assertNull(r.fullImages[other])
    }

    @Test
    fun leaving_the_conversation_drops_what_was_fetched_and_what_was_pending() {
        val r = repo()
        r.requestFullImage(ref, seq = 7, index = 0)
        val fetch = sent.filterIsInstance<FetchImage>().single()
        r.receiveForTest(ImageContent("c1", ref, image = full(), requestId = fetch.requestId))
        val second = ImageRefs.of(byteArrayOf(2))
        r.requestFullImage(second, seq = 8, index = 0)
        val pending = sent.filterIsInstance<FetchImage>().last()
        r.backToBrowse()
        assertTrue(r.fullImages.isEmpty() && r.fullImagePending.isEmpty() && r.fullImageUnavailable.isEmpty())
        // the answer that was still on its way finds nothing to land on
        r.receiveForTest(ImageContent("c1", second, image = full(), requestId = pending.requestId))
        assertTrue(r.fullImages.isEmpty())
    }

    @Test
    fun only_a_handful_of_full_pictures_are_kept() {
        val r = repo()
        val refs = (0 until PocketRepository.FULL_IMAGES_KEPT + 3).map { ImageRefs.of(byteArrayOf(it.toByte())) }
        for (x in refs) {
            r.requestFullImage(x, seq = 1, index = 0)
            r.receiveForTest(ImageContent("c1", x, image = full(), requestId = sent.filterIsInstance<FetchImage>().last().requestId))
        }
        assertEquals(PocketRepository.FULL_IMAGES_KEPT, r.fullImages.size)
        assertFalse(refs.first() in r.fullImages) // the oldest went first
        assertTrue(refs.last() in r.fullImages)
    }

    // ---- an older page is not asked for twice while it may still be on its way ----

    @Test
    fun the_same_page_is_not_requested_again_while_its_request_is_fresh_but_the_next_page_is() {
        val r = repo()
        r.receiveForTest(ConvoHistory("c1", listOf(HistoryMessage(ChatRole.USER, "q", seq = 20)), lastSeq = 40, firstSeq = 20, hasMore = true))
        fun pages() = sent.filterIsInstance<dev.ccpocket.protocol.FetchHistoryPage>().map { it.beforeSeq }
        r.loadOlderHistory()
        assertEquals(listOf(20L), pages())
        // the spinner's deadline passed (simulated: it collapses the flag, nothing else) and something on screen
        // changed, so the list asks again — the page may simply be slow, and it is still accepted when it lands
        r.historyLoadingOlder.value = false
        r.loadOlderHistory()
        assertEquals(listOf(20L), pages(), "not the same 64 KB twice")
        assertFalse(r.historyLoadingOlder.value)
        // the page lands: the NEXT page is a different request and goes out at once
        r.receiveForTest(dev.ccpocket.protocol.ConvoHistoryPage("c1", listOf(HistoryMessage(ChatRole.USER, "older", seq = 10)), firstSeq = 10, hasMore = true))
        r.loadOlderHistory()
        assertEquals(listOf(20L, 10L), pages())
    }

    @Test
    fun a_second_answer_to_a_page_already_received_is_not_taken_for_the_next_page() {
        val r = repo()
        fun row(seq: Long) = HistoryMessage(ChatRole.USER, "row $seq", seq = seq)
        fun texts() = r.messages.filterIsInstance<ChatItem.User>().map { it.text }
        r.receiveForTest(ConvoHistory("c1", listOf(row(100)), lastSeq = 100, firstSeq = 100, hasMore = true))
        r.loadOlderHistory() // page A: rows before 100 … and, on a slow link, asked for a second time later
        val pageA = dev.ccpocket.protocol.ConvoHistoryPage("c1", listOf(row(60), row(80)), firstSeq = 60, hasMore = true)
        r.receiveForTest(pageA)
        r.loadOlderHistory() // page B: rows before 60
        r.receiveForTest(pageA) // the duplicate answer to A arrives while B is outstanding
        assertEquals(listOf("row 60", "row 80", "row 100"), texts(), "the same rows must not be prepended twice")
        assertTrue(r.historyLoadingOlder.value, "…and B is still awaited")
        r.receiveForTest(dev.ccpocket.protocol.ConvoHistoryPage("c1", listOf(row(20), row(40)), firstSeq = 20, hasMore = false))
        assertEquals(listOf("row 20", "row 40", "row 60", "row 80", "row 100"), texts())
        assertFalse(r.historyHasMore.value)
    }

    @Test
    fun a_full_window_starts_a_new_window_generation_and_a_delta_does_not() {
        val r = repo()
        val before = r.historyWindowGen.value
        r.receiveForTest(ConvoHistory("c1", listOf(HistoryMessage(ChatRole.USER, "q", seq = 5)), lastSeq = 5, firstSeq = 5))
        assertEquals(before + 1, r.historyWindowGen.value)
        r.receiveForTest(ConvoHistory("c1", listOf(HistoryMessage(ChatRole.ASSISTANT, "a", seq = 6)), lastSeq = 6, firstSeq = 6, delta = true))
        assertEquals(before + 1, r.historyWindowGen.value)
    }

    // ---- pairing a sent prompt with its replayed preview ----

    @Test
    fun an_image_only_prompt_is_recognised_in_the_replay_by_its_pictures_ref() {
        val pending = ChatItem.User("", images = listOf(fullBytes), pending = true)
        val replayed = ChatItem.User("", images = listOf("a preview of it".encodeToByteArray()), imageRefs = listOf(ref), seq = 4, uuid = "u4")
        val merged = TranscriptMerge.merge(listOf(pending), listOf(replayed))
        val row = assertIs<ChatItem.User>(merged.single()) // resolved in place — not the bubble plus a twin
        assertFalse(row.pending)
        assertContentEquals(fullBytes, row.images.single()) // the bubble keeps its own full picture
        assertTrue(row.imageRefs.isEmpty())
        assertEquals(4L, row.seq)
    }

    @Test
    fun a_different_picture_is_not_mistaken_for_the_pending_one() {
        val pending = ChatItem.User("", images = listOf(fullBytes), pending = true)
        val someoneElses = ChatItem.User("", images = listOf("preview".encodeToByteArray()), imageRefs = listOf(ImageRefs.of(byteArrayOf(9))), seq = 4, uuid = "u4")
        val merged = TranscriptMerge.merge(listOf(pending), listOf(someoneElses))
        assertEquals(2, merged.size)
        assertTrue(merged.filterIsInstance<ChatItem.User>().any { it.pending })
    }

    @Test
    fun a_bubble_without_pictures_adopts_the_replays_previews_with_their_refs() {
        val local = ChatItem.User("typed at the computer", pending = true)
        val replayed = ChatItem.User("typed at the computer", images = listOf("p".encodeToByteArray()), imageRefs = listOf(ref), seq = 4, uuid = "u4")
        val row = assertIs<ChatItem.User>(TranscriptMerge.merge(listOf(local), listOf(replayed)).single())
        assertEquals(listOf(ref), row.imageRefs)
        assertEquals(1, row.images.size)
    }

    @Test
    fun a_live_tool_card_learns_its_transcript_cursor_from_the_replay() {
        val live = ChatItem.Tool("Read", "shot.png", taskId = "t1", ok = true, images = listOf("p".encodeToByteArray()), imageRefs = listOf(ref))
        val replayed = ChatItem.Tool("Read", "shot.png", ok = true, images = listOf("p".encodeToByteArray()), imageRefs = listOf(ref), seq = 12)
        val row = assertIs<ChatItem.Tool>(TranscriptMerge.merge(listOf(live), listOf(replayed)).single())
        assertEquals(12L, row.seq)
        assertEquals("t1", row.taskId)
    }
}
