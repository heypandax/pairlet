package dev.ccpocket.app.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The issue-107 reconnect-backfill merge: a ConvoHistory replay must fill the offline gap without
 * flashing (no scrollback loss, no dropped local-only rows), without duplicating (same rows pair up,
 * live-vs-replay races dedupe), and without reordering.
 */
class TranscriptMergeTest {

    private fun user(text: String, pending: Boolean = false, promptId: String? = null, delivered: Boolean = false) =
        ChatItem.User(text, pending = pending, promptId = promptId, delivered = delivered)

    // ---- wholesale cases ----

    @Test
    fun emptyReplayStillClears() { // the daemon's /clear wipe
        val local = listOf<ChatItem>(user("hi"), ChatItem.Assistant("yo"))
        assertEquals(emptyList(), TranscriptMerge.merge(local, emptyList()))
    }

    @Test
    fun emptyLocalTakesReplay() {
        val replay = listOf<ChatItem>(user("hi"), ChatItem.Assistant("yo"))
        assertEquals(replay, TranscriptMerge.merge(emptyList(), replay))
    }

    @Test
    fun identicalReplayIsANoOp() {
        val rows = listOf<ChatItem>(
            user("q1"), ChatItem.Assistant("a1"), ChatItem.Tool("Bash", "ls -la"), ChatItem.Assistant("a2"),
        )
        assertEquals(rows, TranscriptMerge.merge(rows, rows))
    }

    // ---- the core issue-107 case: the replay extends local with the offline gap ----

    @Test
    fun replayBackfillsTheGapAfterLocalTail() {
        val local = listOf<ChatItem>(user("q"), ChatItem.Assistant("part one."))
        val replay = listOf<ChatItem>(
            user("q"), ChatItem.Assistant("part one."), ChatItem.Tool("Read", "{\"file\":\"x\"}"),
            ChatItem.Assistant("part two, streamed while the phone was offline."),
        )
        val merged = TranscriptMerge.merge(local, replay)
        assertEquals(replay, merged)
    }

    @Test
    fun replayBehindLocalKeepsTheFresherBubble() {
        // the disk read lagged the live stream — never regress the visible text
        val local = listOf<ChatItem>(user("q"), ChatItem.Assistant("part one. part two."))
        val replay = listOf<ChatItem>(user("q"), ChatItem.Assistant("part one. "))
        val merged = TranscriptMerge.merge(local, replay)
        assertEquals(listOf<ChatItem>(user("q"), ChatItem.Assistant("part one. part two.")), merged)
    }

    @Test
    fun gapGluedBubbleIsRepairedByTheReplay() {
        // local streamed T1, missed T2 offline, then glued T3 onto the same bubble; the replay knows better
        val local = listOf<ChatItem>(user("q"), ChatItem.Assistant("T1-T3"))
        val replay = listOf<ChatItem>(
            user("q"), ChatItem.Assistant("T1-"), ChatItem.Assistant("T2-"), ChatItem.Assistant("T3"),
        )
        val merged = TranscriptMerge.merge(local, replay)
        assertEquals(replay, merged)
        // and no fragment of the glued text survives as a duplicate
        assertEquals(1, merged.count { it is ChatItem.Assistant && (it as ChatItem.Assistant).text.contains("T3") })
    }

    @Test
    fun oneLiveBubbleSpanningSeveralReplayRowsDoesNotDuplicate() {
        // appendChunk concatenates consecutive text blocks; TranscriptReplay keeps them as separate rows
        val local = listOf<ChatItem>(user("q"), ChatItem.Assistant("block1block2"))
        val replay = listOf<ChatItem>(user("q"), ChatItem.Assistant("block1"), ChatItem.Assistant("block2"))
        val merged = TranscriptMerge.merge(local, replay)
        assertEquals(listOf<ChatItem>(user("q"), ChatItem.Assistant("block1block2")), merged)
    }

    // ---- scrollback + local-only rows ----

    @Test
    fun scrollbackOlderThanTheReplayWindowSurvives() {
        val old = listOf<ChatItem>(user("ancient q"), ChatItem.Assistant("ancient a"))
        val recent = listOf<ChatItem>(user("recent q"), ChatItem.Assistant("recent a"))
        val local = old + recent
        val replay = recent + listOf<ChatItem>(ChatItem.Assistant("gap content")) // tail-capped window
        val merged = TranscriptMerge.merge(local, replay)
        assertEquals(old + recent + listOf<ChatItem>(ChatItem.Assistant("gap content")), merged)
    }

    @Test
    fun localOnlyRowsInsideTheOverlapAreCarriedThrough() {
        val local = listOf(
            user("q1"), ChatItem.Assistant("a1"), ChatItem.TurnEnded(7),
            user("q2"), ChatItem.Thinking("hmm", 3), ChatItem.Assistant("a2"),
        )
        val replay = listOf<ChatItem>(user("q1"), ChatItem.Assistant("a1"), user("q2"), ChatItem.Assistant("a2"))
        val merged = TranscriptMerge.merge(local, replay)
        assertEquals(local, merged) // dividers and thinking stay exactly where they were
    }

    @Test
    fun orderIsPreserved() {
        val local = listOf<ChatItem>(user("q1"), ChatItem.Assistant("a1"), ChatItem.TurnEnded(1), user("q2"))
        val replay = listOf<ChatItem>(
            user("q1"), ChatItem.Assistant("a1"), user("q2"), ChatItem.Assistant("a2"), ChatItem.Tool("Bash", "x"),
        )
        val merged = TranscriptMerge.merge(local, replay)
        // relative order of the shared rows + appended tail matches the replay's chronology
        val contentOnly = merged.filter { it !is ChatItem.TurnEnded }
        assertEquals(replay, contentOnly)
        assertTrue(merged.indexOfFirst { it is ChatItem.TurnEnded } in 2..2) // divider still after a1
    }

    // ---- pending prompt bubbles ----

    @Test
    fun pendingBubbleAfterTheReplayTailIsKept() {
        val local = listOf<ChatItem>(user("q"), ChatItem.Assistant("a"), user("typed offline", pending = true, promptId = "p9"))
        val replay = listOf<ChatItem>(user("q"), ChatItem.Assistant("a"))
        val merged = TranscriptMerge.merge(local, replay)
        assertEquals(local, merged)
        assertTrue((merged.last() as ChatItem.User).pending)
    }

    @Test
    fun deliveredButUnackedBubbleResolvesInPlaceWithoutDuplicating() {
        val local = listOf<ChatItem>(user("q"), ChatItem.Assistant("a"), user("sent right before the drop", pending = true, promptId = "p1"))
        val replay = listOf<ChatItem>(
            user("q"), ChatItem.Assistant("a"), user("sent right before the drop"),
            ChatItem.Assistant("the reply that streamed while offline"),
        )
        val merged = TranscriptMerge.merge(local, replay)
        assertEquals(4, merged.size)
        val bubble = merged[2] as ChatItem.User
        assertFalse(bubble.pending)
        assertEquals("p1", bubble.promptId) // receipt identity survives — a late PromptAck still lands
        assertEquals(ChatItem.Assistant("the reply that streamed while offline"), merged[3])
    }

    @Test
    fun divergenceRescuesTrailingPendingBubbles() {
        val local = listOf<ChatItem>(user("q"), ChatItem.Assistant("local-only stale"), user("typed", pending = true, promptId = "p2"))
        val replay = listOf<ChatItem>(user("q"), ChatItem.Assistant("authoritative different"), ChatItem.Assistant("more"))
        val merged = TranscriptMerge.merge(local, replay)
        assertEquals(replay + listOf<ChatItem>(user("typed", pending = true, promptId = "p2")), merged)
    }

    // ---- row enrichment ----

    @Test
    fun userRowKeepsReceiptFieldsAndImages() {
        val img = byteArrayOf(1, 2, 3)
        val local = listOf<ChatItem>(
            ChatItem.User("look", images = listOf(img), pending = false, promptId = "p5", delivered = true),
            ChatItem.Assistant("nice"),
        )
        val replay = listOf<ChatItem>(user("look"), ChatItem.Assistant("nice"), ChatItem.Assistant("gap"))
        val merged = TranscriptMerge.merge(local, replay)
        val u = merged[0] as ChatItem.User
        assertEquals("p5", u.promptId)
        assertTrue(u.delivered)
        assertEquals(1, u.images.size)
    }

    @Test
    fun toolCardMergesLiveIdentityWithReplayOutcome() {
        val local = listOf<ChatItem>(user("q"), ChatItem.Tool("Task", "explorer: scan", taskId = "t1", childCount = 4, lastChild = "Grep"))
        val replay = listOf<ChatItem>(user("q"), ChatItem.Tool("Task", "explorer: scan", ok = true, output = "report"))
        val merged = TranscriptMerge.merge(local, replay)
        val t = merged[1] as ChatItem.Tool
        assertEquals("t1", t.taskId)        // live correlation id kept
        assertEquals(4, t.childCount)       // live progress kept
        assertEquals(true, t.ok)            // replay outcome gained
        assertEquals("report", t.output)
    }

    @Test
    fun toolPreviewTakesTheLongerWhenOneExtendsTheOther() {
        val short = "{\"cmd\":\"long command line…"
        val full = "{\"cmd\":\"long command line… and the rest the live 280-cut dropped\"}"
        val local = listOf<ChatItem>(ChatItem.Tool("Bash", short, taskId = "t2"))
        val replay = listOf<ChatItem>(ChatItem.Tool("Bash", full), ChatItem.Assistant("after"))
        val merged = TranscriptMerge.merge(local, replay)
        assertEquals(full, (merged[0] as ChatItem.Tool).preview)
    }

    @Test
    fun toolPreviewKeepsDisplayOptimizedLocalWhenShapesDiffer() {
        // ExitPlanMode: live preview is the extracted plan text, replay is raw input JSON
        val local = listOf<ChatItem>(ChatItem.Tool("ExitPlanMode", "My plan:\n1. do things"))
        val replay = listOf<ChatItem>(ChatItem.Tool("ExitPlanMode", "{\"plan\":\"My plan…\"}"), ChatItem.Assistant("x"))
        val merged = TranscriptMerge.merge(local, replay)
        assertEquals("My plan:\n1. do things", (merged[0] as ChatItem.Tool).preview)
    }

    // ---- anchoring ----

    @Test
    fun repeatedRowsAnchorAtTheDeepestMatch() {
        // the same user text appears twice; the replay window starts at the SECOND occurrence
        val local = listOf<ChatItem>(
            user("continue"), ChatItem.Assistant("first run"),
            user("continue"), ChatItem.Assistant("second run"),
        )
        val replay = listOf<ChatItem>(user("continue"), ChatItem.Assistant("second run"), ChatItem.Assistant(" plus gap"))
        val merged = TranscriptMerge.merge(local, replay)
        assertEquals(
            listOf<ChatItem>(
                user("continue"), ChatItem.Assistant("first run"),
                user("continue"), ChatItem.Assistant("second run"), ChatItem.Assistant(" plus gap"),
            ),
            merged,
        )
    }

    @Test
    fun nothingPairsFallsBackToReplay() {
        val local = listOf<ChatItem>(user("totally"), ChatItem.Assistant("different"))
        val replay = listOf<ChatItem>(user("other session shape"), ChatItem.Assistant("content"))
        assertEquals(replay, TranscriptMerge.merge(local, replay))
    }

    @Test
    fun divergenceMidOverlapTakesReplayFromThatPoint() {
        // e.g. a turn another device ran: replay has a user row the phone never saw live
        val local = listOf<ChatItem>(user("q1"), ChatItem.Assistant("a1"), ChatItem.Assistant("stale tail"))
        val replay = listOf<ChatItem>(
            user("q1"), ChatItem.Assistant("a1"), user("desktop's q2"), ChatItem.Assistant("a2"),
        )
        val merged = TranscriptMerge.merge(local, replay)
        assertEquals(replay, merged)
    }

    // ---- the one-shot live-stream echo dedupe ----

    @Test
    fun echoTextMatchesTheMergedTail() {
        val messages = listOf<ChatItem>(user("q"), ChatItem.Assistant("part one. part two."))
        assertTrue(TranscriptMerge.isEchoText(messages, "part two."))
        assertFalse(TranscriptMerge.isEchoText(messages, "part three."))
        assertFalse(TranscriptMerge.isEchoText(messages, ""))
    }

    @Test
    fun echoTextSkipsTrailingLocalOnlyRows() {
        val messages = listOf<ChatItem>(
            user("q"), ChatItem.Assistant("tail block"), user("typed", pending = true), ChatItem.TurnEnded(2),
        )
        assertTrue(TranscriptMerge.isEchoText(messages, "tail block"))
    }

    @Test
    fun echoToolFoldsIntoTheReplayedCard() {
        val messages = listOf<ChatItem>(user("q"), ChatItem.Tool("Bash", "{\"cmd\":\"ls -la and more…\"}"))
        // live START carries the 280-cut prefix of the same input
        assertEquals(1, TranscriptMerge.echoToolIndex(messages, "Bash", "{\"cmd\":\"ls -la"))
        // a different tool, or a card that already has a live taskId, is never folded into
        assertEquals(-1, TranscriptMerge.echoToolIndex(messages, "Read", "{\"cmd\":\"ls -la"))
        val claimed = listOf<ChatItem>(ChatItem.Tool("Bash", "x", taskId = "live-1"))
        assertEquals(-1, TranscriptMerge.echoToolIndex(claimed, "Bash", "x"))
    }
}
