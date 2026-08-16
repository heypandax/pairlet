package dev.ccpocket.app.data

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TranscriptMergePromptReceiptTest {

    @Test
    fun deltaHistoryResolvesAFilePromptByItsActualWireText() {
        val file = SentFile("report.pdf", 42, "/tmp/inbox/report.pdf")
        val pending = ChatItem.User(
            "review this",
            pending = true,
            promptId = "prompt-1",
            files = listOf(file),
        )

        val merged = TranscriptMerge.mergeDelta(
            local = listOf(ChatItem.Assistant("older output"), pending),
            delta = listOf(
                ChatItem.User("review this\n\n@/tmp/inbox/report.pdf"),
                ChatItem.Assistant("done"),
            ),
        )

        val users = merged.filterIsInstance<ChatItem.User>()
        assertEquals(1, users.size, "the replay must resolve the local bubble, not append a duplicate")
        assertFalse(users.single().pending)
        assertEquals("prompt-1", users.single().promptId, "receipt identity survives the replay merge")
        assertEquals(listOf(file), users.single().files, "the user-facing attachment chip survives too")
    }

    @Test
    fun imageOnlyPromptNeedsMatchingImageEvidence() {
        val pendingBytes = byteArrayOf(1, 2, 3)
        val pending = ChatItem.User("", images = listOf(pendingBytes), pending = true, promptId = "prompt-image")

        val unrelated = TranscriptMerge.mergeDelta(
            local = listOf(pending),
            delta = listOf(ChatItem.User("", images = listOf(byteArrayOf(9, 9, 9)))),
        )
        assertTrue(
            unrelated.filterIsInstance<ChatItem.User>().any { it.promptId == "prompt-image" && it.pending },
            "blank text alone must not claim a different image prompt was delivered",
        )

        val matching = TranscriptMerge.mergeDelta(
            local = listOf(pending),
            delta = listOf(ChatItem.User("", images = listOf(pendingBytes.copyOf()))),
        )
        val resolved = matching.filterIsInstance<ChatItem.User>().single()
        assertFalse(resolved.pending)
        assertEquals("prompt-image", resolved.promptId)
        assertContentEquals(pendingBytes, resolved.images.single())
    }
}
