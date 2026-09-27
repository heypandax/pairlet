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
            ChatItem.Tool("Task", "sub-agent", ok = true),
            ChatItem.Tool("Agent", "sub-agent", ok = true),
            ChatItem.Tool("Workflow", "wf", ok = true),
            ChatItem.Tool("Bash", "bound", ok = true, workflowRunId = "run-1"),
            ChatItem.Tool("ExitPlanMode", "the plan", ok = true),
            ChatItem.Sys("boom"),
            ChatItem.Sys("note", isError = false),
            ChatItem.TurnEnded(3),
            ChatItem.RuleChip("Bash(ls)"),
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
    fun aLoneStepIsAOneStepFoldSoLiveAndReopenedLookTheSame() {
        // Tool Process Live v1: the run was born a fold while it ran, and settling must not burst it into a band
        val p = present(ChatRowIdentity(), listOf(ChatItem.User("go"), ok(), ChatItem.Assistant("A")))
        assertEquals(3, p.rows.size)
        val g = p.rows[1] as ChatRow.ProcessGroup
        assertEquals(1..1, g.sourceIndices)
        assertEquals(ProcessSummary(1, 0, 0, false), g.summary)
    }

    @Test
    fun aGrantsAuditChipJoinsTheRunItLandsInAndIsCountedThere() {
        // the daemon records an auto-approval right after the call starts: in a grant-covered run that is a chip
        // between every two steps, and cutting the fold at each one would bring the bounce straight back
        val items = listOf(
            ChatItem.User("go"), ok("Edit", "a.ts"), ChatItem.AutoRun("e1", "Edit a.ts", "task-grant"),
            ok("Edit", "b.ts"), ChatItem.AutoRun("e2", "Edit b.ts", "task-grant"), ChatItem.Assistant("done"),
        )
        val p = present(ChatRowIdentity(), items)
        assertEquals(3, p.rows.size)
        val g = p.rows[1] as ChatRow.ProcessGroup
        assertEquals(1..4, g.sourceIndices)
        assertEquals(ProcessSummary(tools = 2, thoughts = 0, images = 0, imagesTruncated = false, autoRuns = 2), g.summary)
        // …but a chip never STARTS a run: one landing after prose stays its own row
        val lone = present(ChatRowIdentity(), listOf(ChatItem.Assistant("x"), ChatItem.AutoRun("e3", "ls", "session-rule")))
        assertTrue(lone.rows.all { it is ChatRow.Original })
    }

    @Test
    fun anEarlierTurnsFoldKeepsItsCountsWhileTheNextTurnRuns() {
        // review: "pushed off the tail" must mean the fold nearest the tail, not every fold of the transcript
        val lost = running("Bash", "killed mid-run", "t-old")
        val items = listOf(
            ChatItem.User("first"), ok("Read"), lost, ChatItem.Assistant("stopped"),
            ChatItem.User("second"), running("Grep", "now", "t-new"),
        )
        val p = presentLive(ChatRowIdentity(), items)
        val earlier = p.rows[1] as ChatRow.ProcessGroup
        assertEquals(ProcessSummary(tools = 2, thoughts = 0, images = 0, imagesTruncated = false, unknown = 1), earlier.summary)
        assertEquals(StepState.UNKNOWN, p.stepState(2))
        assertEquals(listOf(5), (p.rows.last() as ChatRow.ProcessGroup).live?.inFlight)
    }

    @Test
    fun withoutOutcomeReportsOnlyTheNewestStartedCallIsRunning() {
        // a daemon before 2.1.1 never reports an ordinary tool's outcome: calls are not "running" forever
        val items = listOf(ChatItem.User("go"), running("Read", "a.kt", "t1"), running("Grep", "b", "t2"), running("Edit", "c.kt", "t3"))
        val p = presentLive(ChatRowIdentity(), items, liveOutcomes = false)
        val g = p.rows[1] as ChatRow.ProcessGroup
        assertEquals(listOf(3), g.live?.inFlight, "only the newest call can be told running")
        assertEquals(ProcessSummary(2, 0, 0, false), g.summary, "the rest are counted, with no claim either way")
        assertEquals(StepState.QUIET, p.stepState(1))
        // …and once the turn is over none of them is marked "without result": nobody was ever going to say
        val done = presentLive(ChatRowIdentity(), items, live = false, liveOutcomes = false)
        assertEquals(ProcessSummary(3, 0, 0, false), (done.rows[1] as ChatRow.ProcessGroup).summary)
    }

    @Test
    fun aRowReplayedWhileItsCallRanIsRunningUntilTheTurnEndsThenNotReturned() {
        // the list attached mid-turn: the history replay carries the running call without an id
        val replayed = ChatItem.Tool("Bash", "pnpm test")
        val items = listOf(ChatItem.User("go"), replayed)
        val live = presentLive(ChatRowIdentity(), items)
        val g = live.rows[1] as ChatRow.ProcessGroup
        assertEquals(listOf(1), g.live?.inFlight, "running, id or not — its outcome finds it by name")
        assertEquals(StepState.RUNNING, live.stepState(1))
        assertEquals(0, g.summary.unknown)
        // the turn ended and nothing ever came back for it: only now is it "not returned"
        val over = presentLive(ChatRowIdentity(), items, live = false)
        assertEquals(StepState.UNKNOWN, over.stepState(1))
        assertEquals(1, (over.rows[1] as ChatRow.ProcessGroup).summary.unknown)
    }

    @Test
    fun parallelCallsKeepOneClockSoTheFirstToFinishNeverResetsIt() {
        val id = ChatRowIdentity()
        val three = listOf(ChatItem.User("go"), running("Read", "a.kt", "p1"), running("Read", "b.kt", "p2"), running("Read", "c.kt", "p3"))
        val before = presentLive(id, three)
        val line = dev.ccpocket.app.ui.chat.liveLineState(before, before.rows.last() as ChatRow.ProcessGroup, ask = null)
        assertEquals(listOf("p1", "p2", "p3").map(::stepClockKeyOf), line.clockKeys, "every call running together")
        val after = presentLive(id, listOf(three[0], (three[1] as ChatItem.Tool).copy(ok = true), three[2], three[3]))
        val next = dev.ccpocket.app.ui.chat.liveLineState(after, after.rows.last() as ChatRow.ProcessGroup, ask = null)
        assertEquals(listOf("p2", "p3").map(::stepClockKeyOf), next.clockKeys, "the timer keeps the earliest start of those still running")
    }

    @Test
    fun aPromptTypedMidTurnDoesNotTurnTheCallsItPushedOffTheTailIntoUnknowns() {
        val items = listOf(ChatItem.User("go"), running("Bash", "pnpm test", "t1"), ChatItem.User("顺便把分页游标也重置一下"))
        val p = presentLive(ChatRowIdentity(), items)
        val g = p.rows[1] as ChatRow.ProcessGroup
        assertEquals(null, g.live, "the prompt is the tail now")
        assertEquals(ProcessSummary(0, 0, 0, false), g.summary, "still running: not counted yet, and not unknown")
    }

    @Test
    fun failuresAndMissingOutcomesStayInsideAndAreCountedOnTheFold() {
        val items = listOf(
            ChatItem.User("go"), ok("Read"), ChatItem.Tool("Bash", "pnpm test", ok = false),
            thought(), ChatItem.Tool("Edit", "a.ts", ok = null), ok("Bash", "pnpm test"), ChatItem.Assistant("fixed"),
        )
        val p = present(ChatRowIdentity(), items)
        assertEquals(3, p.rows.size, "a failing test the agent then fixes no longer cuts the process in three")
        val g = p.rows[1] as ChatRow.ProcessGroup
        assertEquals(1..5, g.sourceIndices)
        assertEquals(ProcessSummary(tools = 4, thoughts = 1, images = 0, imagesTruncated = false, failed = 1, unknown = 1), g.summary)
        assertEquals(null, g.live, "outside a running turn an outcome that never arrived is unknown — not running")
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

    // ── live run (Tool Process Live v1) ──────────────────────────────────────────────────────────

    private fun running(tool: String, preview: String, id: String) = ChatItem.Tool(tool, preview, taskId = id)

    private fun presentLive(
        identity: ChatRowIdentity,
        items: List<ChatItem>,
        expanded: Set<String> = emptySet(),
        live: Boolean = true,
        liveOutcomes: Boolean = true,
    ): ChatPresentation {
        val ids = identity.assign(items)
        return ChatPresentation.build(items, ids, identity.generation, collapse = true, expanded = expanded, live = live, liveOutcomes = liveOutcomes)
    }

    @Test
    fun aRunningTurnsFirstStepIsAlreadyAFold() {
        val items = listOf(ChatItem.User("go"), ChatItem.Assistant("我先看一下"), running("Read", "a.kt", "t1"))
        val p = presentLive(ChatRowIdentity(), items)
        assertEquals(3, p.rows.size)
        val g = p.rows[2] as ChatRow.ProcessGroup
        assertEquals(2..2, g.sourceIndices)
        assertEquals(listOf(2), g.live?.inFlight, "the running step is the live region's, not a row of its own")
        assertEquals(ProcessSummary(0, 0, 0, false), g.summary, "nothing has finished yet")
        assertEquals(2, p.rowOfSource(2))
    }

    @Test
    fun stepsStartingAndFinishingNeverChangeTheLiveFoldsShape() {
        val id = ChatRowIdentity()
        val head = listOf(ChatItem.User("go"), ChatItem.Assistant("我先看一下"))
        val read = running("Read", "a.kt", "t1")
        val grep = running("Grep", "err", "t2")
        val frames = listOf(
            head + read,
            head + read.copy(ok = true),
            head + read.copy(ok = true) + grep,
            head + read.copy(ok = true) + grep.copy(ok = true),
            head + read.copy(ok = true) + grep.copy(ok = true) + ChatItem.Thinking("next?"),
            head + read.copy(ok = true) + grep.copy(ok = true) + ChatItem.Thinking("next?", seconds = 2) +
                running("Read", "b.kt", "t3") + running("Read", "c.kt", "t4"),
        )
        val shapes = frames.map { presentLive(id, it) }
        val keys = shapes.map { (it.rows.last() as ChatRow.ProcessGroup).groupKey }.toSet()
        assertEquals(1, keys.size, "one fold for the whole run — same key, so the list never remounts it")
        shapes.forEach { assertEquals(3, it.rows.size, "a step starting or finishing adds no row and removes none") }
        val last = shapes.last().rows.last() as ChatRow.ProcessGroup
        assertEquals(listOf(5, 6), last.live?.inFlight, "parallel steps are all in flight, in transcript order")
        assertEquals(ProcessSummary(tools = 2, thoughts = 1, images = 0, imagesTruncated = false), last.summary)
        val between = shapes[3].rows.last() as ChatRow.ProcessGroup
        assertEquals(emptyList(), between.live?.inFlight, "between steps the fold stays live with nothing in flight")
    }

    @Test
    fun aStreamingThinkingBlockOpensTheLiveFold() {
        val p = presentLive(ChatRowIdentity(), listOf(ChatItem.User("go"), ChatItem.Thinking("hmm")))
        val g = p.rows.last() as ChatRow.ProcessGroup
        assertEquals(listOf(1), g.live?.inFlight)
    }

    @Test
    fun onlyTheTailOfARunningTurnIsLive() {
        // prose after the steps: the run is over even though the turn is not
        val closed = presentLive(
            ChatRowIdentity(),
            listOf(ChatItem.User("go"), ok("A"), ok("B"), running("Bash", "slow", "t9"), ChatItem.Assistant("while that runs…")),
        )
        assertTrue(closed.rows.none { it is ChatRow.ProcessGroup && it.live != null }, "nothing is live once prose follows")
        assertEquals(
            ProcessSummary(tools = 2, thoughts = 0, images = 0, imagesTruncated = false),
            (closed.rows[1] as ChatRow.ProcessGroup).summary,
            "a started call of the running turn is still running — not counted yet, and never unknown",
        )
        // a card at the tail ends the run too
        val card = presentLive(ChatRowIdentity(), listOf(ChatItem.User("go"), ok("A"), ChatItem.Tool("Task", "sub", taskId = "s1")))
        assertEquals(null, (card.rows[1] as ChatRow.ProcessGroup).live)
        assertTrue(card.rows[2] is ChatRow.Original, "the sub-agent card stays its own row")
        // and a turn that is not running has no live fold at all: its outcome-less tail is unknown
        val items = listOf(ChatItem.User("go"), ok("A"), running("Bash", "x", "t1"))
        val ids = ChatRowIdentity().let { it.assign(items) to it.generation }
        val idle = ChatPresentation.build(items, ids.first, ids.second, collapse = true)
        val g = idle.rows[1] as ChatRow.ProcessGroup
        assertEquals(null, g.live)
        assertEquals(ProcessSummary(tools = 2, thoughts = 0, images = 0, imagesTruncated = false, unknown = 1), g.summary)
    }

    @Test
    fun anOpenedLiveFoldListsItsFinishedMembersAndKeepsTheLiveLineAtTheBottom() {
        val id = ChatRowIdentity()
        val items = listOf(ChatItem.User("go"), ok("A"), running("Bash", "x", "t1"))
        val key = (presentLive(id, items).rows[1] as ChatRow.ProcessGroup).groupKey
        val open = presentLive(id, items, expanded = setOf(key))
        val g = open.rows[1] as ChatRow.ProcessGroup
        assertTrue(g.expanded)
        assertEquals(listOf(2), g.live?.inFlight)
        assertFalse(g.carriesLive, "the live line hangs under the last finished member, not the header")
        val members = open.rows.drop(2).map { it as ChatRow.Original }
        assertEquals(listOf(1), members.map { it.sourceIndex }, "the running step is the live line's, never a member row")
        assertTrue(members.single().last && members.single().liveTail)
        assertEquals(2, open.rowOfSource(2), "the running step maps to the row that shows it")
        // …and an opened fold whose steps all still run has no member to hang it under: the header carries it
        val fresh = listOf(ChatItem.User("go"), running("Read", "a.kt", "t2"))
        val key2 = (presentLive(id, fresh).rows[1] as ChatRow.ProcessGroup).groupKey
        val header = presentLive(id, fresh, expanded = setOf(key2)).rows[1] as ChatRow.ProcessGroup
        assertTrue(header.expanded && header.carriesLive && !header.hasMemberRows)
    }

    @Test
    fun aLiveFoldOnlyOfARunningToolStillProvesContentLanded() {
        val items = listOf(ChatItem.User("go"), running("Bash", "x", "t1"))
        val p = presentLive(ChatRowIdentity(), items)
        assertTrue(p.layoutEvidence(listOf(1), items).hasVisibleContent)
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
        assertEquals(1, p.seamRow(2), "…and so does one above a later member: the members sit inside the fold's card")
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
