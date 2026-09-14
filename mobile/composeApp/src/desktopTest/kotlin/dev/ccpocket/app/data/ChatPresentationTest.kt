package dev.ccpocket.app.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlin.time.TimeSource

/**
 * Issue #380 — the pure half of "collapse tool process": row identity, the display projection and its
 * source-coordinate mapping. Everything the two chat lists (phone / desktop) do with a collapsed stream is
 * built on these, so the invariants are pinned here rather than inferred from pixels.
 */
class ChatPresentationTest {

    private fun ok(tool: String = "Bash", preview: String = "ls") = ChatItem.Tool(tool, preview, ok = true)
    private fun thought(s: Int = 2) = ChatItem.Thinking("hmm", seconds = s)

    /** Assign + build in one step, the way the UI state holder does. */
    private fun present(
        identity: ChatRowIdentity,
        items: List<ChatItem>,
        collapse: Boolean = true,
        expanded: Set<String> = emptySet(),
    ): ChatPresentation {
        val ids = identity.assign(items)
        return ChatPresentation.build(items, ids, identity.generation, collapse, expanded)
    }

    // ── identity ─────────────────────────────────────────────────────────────────────────────────

    @Test
    fun identicalToolCallsRepeatedAreDistinctOccurrences() {
        val id = ChatRowIdentity()
        val items = listOf(ok(), ok(), ok())
        val ids = id.assign(items)
        assertEquals(3, ids.toSet().size, "the same command run three times is three records, never one identity")
    }

    @Test
    fun anInPlaceUpdateKeepsTheRowsIdentity() {
        val id = ChatRowIdentity()
        val running = ChatItem.Tool("Bash", "ls", taskId = "t1")
        val before = id.assign(listOf(ChatItem.User("go"), running))
        val after = id.assign(listOf(ChatItem.User("go"), running.copy(ok = true, output = "a b")))
        assertEquals(before.toList(), after.toList(), "a RESULT updates the card in place — same occurrence")
        val streamed = id.assign(listOf(ChatItem.User("go"), running.copy(ok = true), ChatItem.Assistant("he")))
        val more = id.assign(listOf(ChatItem.User("go"), running.copy(ok = true), ChatItem.Assistant("hello")))
        assertEquals(streamed.toList(), more.toList(), "a streaming bubble keeps its identity as text grows")
    }

    @Test
    fun aPrependMintsNewIdsAndKeepsTheExistingOnes() {
        val id = ChatRowIdentity()
        val window = listOf(ChatItem.User("b"), ok(), ChatItem.Assistant("B"))
        val before = id.assign(window)
        val gen = id.generation
        val after = id.assign(listOf(ChatItem.User("a"), ok(), ChatItem.Assistant("A")) + window)
        assertEquals(before.toList(), after.drop(3), "rows already on screen keep their identity across a page")
        assertTrue(after.take(3).none { it in before.toList() }, "the older page gets fresh identities")
        assertEquals(gen, id.generation, "a page is not a new history generation")
    }

    @Test
    fun aFullReplacementOpensANewGenerationAndAClearDoesToo() {
        val id = ChatRowIdentity()
        id.assign(listOf(ChatItem.User("one"), ChatItem.Assistant("reply one")))
        val g0 = id.generation
        id.assign(listOf(ChatItem.User("other session"), ChatItem.Assistant("something unrelated")))
        assertNotEquals(g0, id.generation, "nothing survived — that is a replaced history, not an update")
        val g1 = id.generation
        id.assign(emptyList())
        assertNotEquals(g1, id.generation, "a cleared transcript ends the generation")
    }

    @Test
    fun aMergedReplayThatRebuildsRowsKeepsIdentityAndGeneration() {
        // TranscriptMerge re-creates the Assistant bubble and copies the Tool card; the conversation is the same
        val id = ChatRowIdentity()
        val live = listOf(ChatItem.User("go"), ChatItem.Tool("Bash", "ls", taskId = "t"), ChatItem.Assistant("done"))
        val before = id.assign(live)
        val g = id.generation
        val merged = TranscriptMerge.merge(
            live,
            listOf(ChatItem.User("go"), ChatItem.Tool("Bash", "ls -la", ok = true), ChatItem.Assistant("done")),
        )
        val after = id.assign(merged)
        assertEquals(before.toList(), after.toList())
        assertEquals(g, id.generation)
    }

    @Test
    fun idsAreUniqueAcrossTrackersSoListKeysNeverCollide() {
        val a = ChatRowIdentity().assign(listOf(ok()))
        val b = ChatRowIdentity().assign(listOf(ok()))
        assertNotEquals(a[0], b[0], "two windows / two sessions never hand the same key to a LazyColumn")
    }

    // ── projection ───────────────────────────────────────────────────────────────────────────────

    @Test
    fun collapseOffIsTheIdentityProjection() {
        val items = listOf(ChatItem.User("go"), ok(), ok(), ChatItem.Assistant("A"))
        val p = present(ChatRowIdentity(), items, collapse = false)
        assertEquals(items.size, p.rows.size)
        assertTrue(p.rows.all { it is ChatRow.Original })
        items.indices.forEach { assertEquals(it, p.rowOfSource(it)); assertEquals(it..it, p.sourceIndicesAt(it)) }
    }

    @Test
    fun consecutiveFinishedToolsAndThoughtsFoldIntoOneRowBetweenUserAndAssistant() {
        val items = listOf(ChatItem.User("go"), thought(), ok(), ok("Read", "a.kt"), thought(), ChatItem.Assistant("A"))
        val p = present(ChatRowIdentity(), items)
        assertEquals(3, p.rows.size)
        val g = p.rows[1] as ChatRow.ProcessGroup
        assertEquals(1..4, g.sourceIndices)
        assertEquals(ProcessSummary(tools = 2, thoughts = 2, images = 0, imagesTruncated = false), g.summary)
        assertFalse(g.expanded)
        assertEquals(0, p.rowOfSource(0))
        (1..4).forEach { assertEquals(1, p.rowOfSource(it), "every folded source row maps to its group row") }
        assertEquals(2, p.rowOfSource(5))
        assertEquals((1..4).map(p::sourceKey), p.sourceKeysAt(1))
    }

    @Test
    fun everythingThatNeedsAttentionStaysVisibleAndCutsTheSegment() {
        val breakers = listOf(
            ChatItem.Tool("Bash", "x", ok = false),
            ChatItem.Tool("Bash", "x", ok = null),
            ChatItem.Tool("Bash", "x", taskId = "running"),
            ChatItem.Thinking("streaming…"),
            ChatItem.Tool("Task", "sub-agent", ok = true),
            ChatItem.Tool("Agent", "sub-agent", ok = true),
            ChatItem.Tool("Workflow", "wf", ok = true),
            ChatItem.Tool("Bash", "bound", ok = true, workflowRunId = "run-1"),
            ChatItem.Tool("ExitPlanMode", "the plan", ok = true),
            ChatItem.Sys("boom"),
            ChatItem.Sys("note", isError = false),
            ChatItem.TurnEnded(3),
            ChatItem.RuleChip("Bash(ls)"),
            ChatItem.AutoRun("e1", "ls", "task-grant"),
            ChatItem.QuestionsAnswered(listOf("q" to "a")),
            ChatItem.QuestionsWithdrawn,
            ChatItem.QuestionsUnanswered("which?"),
            ChatItem.OpenCodeQuestion(emptyList()),
            ChatItem.Assistant("prose"),
            ChatItem.User("prompt"),
        )
        for (breaker in breakers) {
            val items = listOf(ok(), ok(), breaker, ok(), ok())
            val p = present(ChatRowIdentity(), items)
            assertEquals(3, p.rows.size, "$breaker must stand alone between two separate process rows")
            val mid = p.rows[1] as? ChatRow.Original
            assertEquals(2, mid?.sourceIndex, "$breaker is its own visible row")
            assertEquals(0..1, (p.rows[0] as ChatRow.ProcessGroup).sourceIndices)
            assertEquals(3..4, (p.rows[2] as ChatRow.ProcessGroup).sourceIndices)
        }
    }

    @Test
    fun aLoneFinishedToolIsNotWorthAFoldAndStaysAsItIs() {
        val p = present(ChatRowIdentity(), listOf(ChatItem.User("go"), ok(), ChatItem.Assistant("A")))
        assertTrue(p.rows.all { it is ChatRow.Original })
    }

    @Test
    fun toolImagesAreCountedOnTheFoldedRow() {
        val pics = ChatItem.Tool("Read", "a.png", ok = true, images = listOf(byteArrayOf(1), byteArrayOf(2)))
        val cut = ChatItem.Tool("Browser", "shot", ok = true, imagesTruncated = true)
        val p = present(ChatRowIdentity(), listOf(pics, cut, ok()))
        val s = (p.rows.single() as ChatRow.ProcessGroup).summary
        assertEquals(2, s.images)
        assertTrue(s.imagesTruncated)
    }

    @Test
    fun anExpandedGroupShowsAHeaderThenEveryMemberInOrder() {
        val id = ChatRowIdentity()
        val items = listOf(ChatItem.User("go"), ok("A"), ok("B"), ok("C"), ChatItem.Assistant("done"))
        val collapsed = present(id, items)
        val key = (collapsed.rows[1] as ChatRow.ProcessGroup).groupKey
        val open = present(id, items, expanded = setOf(key))
        assertEquals(listOf("m", "g", "m", "m", "m", "m"), open.rows.map { if (it is ChatRow.ProcessGroup) "g" else "m" })
        assertTrue((open.rows[1] as ChatRow.ProcessGroup).expanded)
        assertEquals(listOf(1, 2, 3), open.rows.subList(2, 5).map { (it as ChatRow.Original).sourceIndex })
        assertEquals(listOf(key, key, key), open.rows.subList(2, 5).map { (it as ChatRow.Original).groupKey })
        (1..3).forEach { assertEquals(it + 1, open.rowOfSource(it), "expanded members map to their own rows") }
        assertEquals(1..3, open.sourceIndicesAt(1), "the header still names what it folds")
    }

    @Test
    fun aTailAppendDoesNotReKeyAnOpenGroup() {
        val id = ChatRowIdentity()
        val base = listOf(ChatItem.User("go"), ok("A"), ok("B"))
        val key = (present(id, base).rows[1] as ChatRow.ProcessGroup).groupKey
        val grown = present(id, base + ok("C"), expanded = setOf(key))
        val g = grown.rows[1] as ChatRow.ProcessGroup
        assertEquals(key, g.groupKey, "the group key is its generation + first member, not its length")
        assertTrue(g.expanded, "a tool finishing at the tail must not snap the reader's open group shut")
        assertEquals(1..3, g.sourceIndices)
    }

    @Test
    fun groupKeysCarryTheGenerationSoAReplacedHistoryStartsCollapsed() {
        val id = ChatRowIdentity()
        val key = (present(id, listOf(ok("A"), ok("B"))).rows[0] as ChatRow.ProcessGroup).groupKey
        id.assign(emptyList())
        val next = present(id, listOf(ok("A"), ok("B")), expanded = setOf(key))
        val g = next.rows[0] as ChatRow.ProcessGroup
        assertNotEquals(key, g.groupKey)
        assertFalse(g.expanded)
    }

    @Test
    fun displayAndSourceMappingsRoundTrip() {
        val items = buildList {
            repeat(40) { i ->
                add(ChatItem.User("u$i"))
                if (i % 3 == 0) add(ChatItem.Tool("Bash", "x", ok = false))
                repeat(i % 4) { add(ok()) }
                if (i % 5 == 0) add(thought())
                add(ChatItem.Assistant("a$i"))
            }
        }
        val id = ChatRowIdentity()
        val first = present(id, items)
        val expanded = first.rows.filterIsInstance<ChatRow.ProcessGroup>().map { it.groupKey }.filterIndexed { i, _ -> i % 2 == 0 }.toSet()
        for (p in listOf(first, present(id, items, expanded = expanded), present(id, items, collapse = false))) {
            val covered = mutableListOf<Int>()
            p.rows.forEachIndexed { r, row ->
                if (row is ChatRow.ProcessGroup && row.expanded) return@forEachIndexed
                p.sourceIndicesAt(r).forEach { s -> covered += s; assertEquals(r, p.rowOfSource(s)) }
            }
            assertEquals(items.indices.toList(), covered, "every source row is represented exactly once, in order")
            items.indices.forEach { s -> assertEquals(p.rowOfSource(s), p.rowOfKey(p.sourceKey(s))) }
        }
    }

    // ── layout evidence (onHistoryLaidOut) ───────────────────────────────────────────────────────

    @Test
    fun aFoldedGroupProvesContentLandedButNeverCountsAsReadOutput() {
        val items = listOf(ChatItem.User("go"), ok(), ok(), ChatItem.Assistant("A"))
        val p = present(ChatRowIdentity(), items)
        val onlyGroup = p.layoutEvidence(listOf(1), items)
        assertTrue(onlyGroup.hasVisibleContent, "a folded tool row on screen is laid-out content")
        assertEquals(-1, onlyGroup.lastVisibleOutput, "the tools hidden inside it were not seen")
        val withReply = p.layoutEvidence(listOf(1, 2), items)
        assertEquals(3, withReply.lastVisibleOutput, "visible rows report their SOURCE index")
    }

    @Test
    fun expandedMembersReportTheirOwnSourceIndex() {
        val id = ChatRowIdentity()
        val items = listOf(ChatItem.User("go"), ok(), ok(), ChatItem.Assistant("A"))
        val key = (present(id, items).rows[1] as ChatRow.ProcessGroup).groupKey
        val p = present(id, items, expanded = setOf(key))
        assertEquals(2, p.layoutEvidence(listOf(0, 1, 2, 3), items).lastVisibleOutput)
        assertFalse(p.layoutEvidence(listOf(99, -1), items).hasVisibleContent, "out-of-range rows are ignored")
    }

    // ── reading anchor ───────────────────────────────────────────────────────────────────────────

    @Test
    fun aReadingAnchorFollowsItsSourceRowAcrossToggleAndExpansion() {
        val id = ChatRowIdentity()
        val items = listOf(ChatItem.User("go"), ok("A"), ok("B"), ok("C"), ChatItem.Assistant("reading this"), ChatItem.User("next"))
        val plain = present(id, items, collapse = false)
        val anchor = plain.anchorAt(4, offset = 37)!!
        val folded = present(id, items)
        assertEquals(2, folded.rowFor(anchor), "the reply moves up three rows when its tools fold")
        // reading a tool that then folds → land on the group that now holds it
        assertEquals(1, folded.rowFor(plain.anchorAt(2, 0)!!))
        // anchored on a collapsed header → after expanding, still the header, not its first member
        val header = folded.anchorAt(1, 5)!!
        val key = (folded.rows[1] as ChatRow.ProcessGroup).groupKey
        assertEquals(1, present(id, items, expanded = setOf(key)).rowFor(header))
        // …and on turning collapse off, the header's first member
        assertEquals(1, present(id, items, collapse = false).rowFor(header))
        assertEquals(37, anchor.offset)
    }

    @Test
    fun aHeaderAnchorFindsItsFoldAfterTheFoldGainsANewHead() {
        val id = ChatRowIdentity()
        val a = ok("A"); val b = ok("B")
        val first = present(id, listOf(ChatItem.User("go"), a, b, ChatItem.Assistant("x")))
        val header = first.anchorAt(1, 9)!!
        val members = first.sourceKeysAt(1).toSet()
        val grown = listOf(ChatItem.User("go"), ok("Z"), a, b, ChatItem.Assistant("x"))
        val ids = id.assign(grown)
        val p = ChatPresentation.build(grown, ids, id.generation, collapse = true, expandedMembers = members)
        assertTrue((p.rows[1] as ChatRow.ProcessGroup).expanded, "the open fold inherited its members' state")
        assertEquals(1, p.rowFor(header), "…and the reader's header anchor still lands on its header")
        assertEquals(1, p.seamRow(1), "a seam above the record that OPENS an expanded fold sits on the fold's header")
        assertEquals(3, p.seamRow(2), "…while one above a later member sits on that member's own row")
    }

    @Test
    fun aRemovedAnchorRowHasNoTarget() {
        val id = ChatRowIdentity()
        val p = present(id, listOf(ChatItem.User("x"), ChatItem.Assistant("y")))
        val a = p.anchorAt(1, 0)!!
        assertEquals(-1, present(id, listOf(ChatItem.User("x"))).rowFor(a))
    }

    // ── cost ─────────────────────────────────────────────────────────────────────────────────────

    @Test
    fun streamingIntoAThousandRowTranscriptStaysCheap() {
        val base = buildList {
            repeat(250) { i ->
                add(ChatItem.User("prompt $i"))
                add(thought()); add(ok("Bash", "cmd $i ".repeat(20))); add(ok("Read", "file$i.kt"))
                add(ChatItem.Assistant("answer $i ".repeat(30)))
            }
        }.toMutableList()
        assertTrue(base.size >= 1000)
        val id = ChatRowIdentity()
        present(id, base.toList())
        val mark = TimeSource.Monotonic.markNow()
        var last: ChatPresentation? = null
        repeat(2000) { n ->
            base[base.lastIndex] = ChatItem.Assistant((base.last() as ChatItem.Assistant).text + "x")
            if (n % 200 == 0) base.add(base.lastIndex, ok("Bash", "tail $n")) // a tool lands before the live bubble
            last = present(id, base.toList())
        }
        val elapsed = mark.elapsedNow()
        assertTrue(elapsed.inWholeMilliseconds < 6_000, "2000 streaming frames over ${base.size} rows took $elapsed")
        assertEquals(base.size, last!!.sourceSize)
    }
}
