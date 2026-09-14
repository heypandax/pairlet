package dev.ccpocket.app.desktop

import androidx.compose.foundation.LocalContextMenuRepresentation
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performScrollToKey
import androidx.compose.ui.test.rightClick
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
import dev.ccpocket.app.data.PocketRepository
import dev.ccpocket.app.pairing.PairedDaemon
import dev.ccpocket.app.present
import dev.ccpocket.app.resources.Res
import dev.ccpocket.app.resources.group_delete
import dev.ccpocket.app.resources.group_move_out
import dev.ccpocket.app.resources.group_move_to
import dev.ccpocket.app.resources.group_rename
import dev.ccpocket.app.resources.session_rename
import dev.ccpocket.app.resources.unpin_project
import dev.ccpocket.app.str
import dev.ccpocket.app.theme.PocketTheme
import dev.ccpocket.protocol.ActiveSession
import dev.ccpocket.protocol.Directories
import dev.ccpocket.protocol.DirectoryEntry
import dev.ccpocket.protocol.Frame
import dev.ccpocket.protocol.GroupAssign
import dev.ccpocket.protocol.GroupDelete
import dev.ccpocket.protocol.GroupRename
import dev.ccpocket.protocol.ListSessions
import dev.ccpocket.protocol.SessionGroup
import dev.ccpocket.protocol.SessionSummary
import dev.ccpocket.protocol.Sessions
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel

/**
 * Issue #360, phase one — a project's custom session groups stay on screen after it stops being the listed project.
 *
 * The daemon lists groups per directory, and RECENT kept only the ROWS of a project the listing moved away from. The rows
 * still named their groups by id, but with no definitions to resolve them against the project rendered flat: its sections
 * gone, its sessions back in recency order, and a project holding nothing but empty groups dropped out of the list. That
 * happened on every change of listed project — visiting another one, a header refresh, the refill after a restart.
 *
 * Every case walks the live [RepoDesktopModel] and asks the real [Sidebar]'s RECENT LazyColumn where each row sits
 * (IndexForKey, -1 = the list does not emit it). Only the daemon is stood in for, by [Daemon]. Offscreen desktop
 * rendering; none of this stands in for acceptance on a real machine.
 */
@OptIn(ExperimentalTestApi::class)
class SidebarRecentGroupsTest {

    /**
     * Stands in for the daemon behind each binding. Every (account, project) keeps its listing — rows newest first, the way
     * the daemon lists them — and its group store; a group verb is applied there, then answered with that project's
     * re-pushed Sessions, as the real daemon answers it. Replies go through the repository's own frame handler, as a frame
     * off the wire would land, and the throw then ends the send before any transport (or the demo's canned replies) runs.
     */
    private class Daemon(private val repo: PocketRepository) {
        private class Project(var rows: List<SessionSummary>, var groups: List<SessionGroup>?, val renameSupported: Boolean)

        private val projects = HashMap<Pair<String, String>, Project>()
        val sent = mutableListOf<Frame>()

        /** [groups] null = a daemon that predates groups (a guest sees the same), empty = a group-aware owner with none yet. */
        fun put(
            dir: String,
            rows: List<SessionSummary>,
            groups: List<SessionGroup>?,
            account: String = ACCT,
            renameSupported: Boolean = false,
        ) {
            projects[account to dir] = Project(rows, groups, renameSupported)
        }

        /** A rename made somewhere else (the phone): the store changes, nothing is pushed to this client. */
        fun rename(dir: String, groupId: String, name: String) {
            project(dir)?.let { p -> p.groups = p.groups?.map { if (it.id == groupId) it.copy(name = name) else it } }
        }

        private fun delete(dir: String, groupId: String) {
            project(dir)?.let { p ->
                p.groups = p.groups?.filterNot { it.id == groupId }
                p.rows = p.rows.map { if (it.group == groupId) it.copy(group = null) else it }
            }
        }

        private fun assign(dir: String, sessionId: String, groupId: String?) {
            project(dir)?.let { p -> p.rows = p.rows.map { if (it.sessionId == sessionId) it.copy(group = groupId) else it } }
        }

        private fun project(dir: String) = projects[repo.paired.value?.accountId.orEmpty() to dir]

        private fun push(dir: String) {
            val p = project(dir) ?: return
            repo.receiveForTest(Sessions(dir, p.rows, p.groups, renameSupported = p.renameSupported))
        }

        init {
            repo.onSendForTest = { frame ->
                sent += frame
                when (frame) {
                    is ListSessions -> push(frame.workdir)
                    is GroupRename -> { rename(frame.workdir, frame.groupId, frame.name); push(frame.workdir) }
                    is GroupDelete -> { delete(frame.workdir, frame.groupId); push(frame.workdir) }
                    is GroupAssign -> { assign(frame.workdir, frame.sessionId, frame.groupId); push(frame.workdir) }
                    else -> Unit
                }
                throw CancellationException("no transport in this test")
            }
        }
    }

    /**
     * The live model over a repository whose daemon is [Daemon], seeded BEFORE the model exists: a restart's refill sweep
     * starts listing while the model is built. Demo mode only keeps the repository off the network and out of the real
     * store (its canned replies never run — [Daemon] ends every send first); it also counts as a ready link, which is what
     * lets that sweep start. The scope is cancelled on the way out, so the model's collectors can't keep writing on timers
     * after the test (see [RepoDesktopModelRunningDotTest]).
     */
    private fun <T> withLiveModel(
        store: DesktopStore = FakeDesktopStore(),
        seed: Daemon.() -> Unit,
        block: (PocketRepository, Daemon, RepoDesktopModel) -> T,
    ): T {
        val scope = CoroutineScope(Dispatchers.Unconfined)
        try {
            val repo = PocketRepository(scope)
            repo.demoMode.value = true
            repo.paired.value = binding(ACCT)
            val daemon = Daemon(repo).apply(seed)
            return block(repo, daemon, RepoDesktopModel(repo, scope, store = store))
        } finally {
            scope.cancel()
        }
    }

    private fun binding(account: String) = PairedDaemon(
        relay = "wss://test", accountId = account, daemonPub = "pk-$account", deviceId = "dev", credential = "c-$account",
    )

    private fun row(id: String, dir: String, group: String? = null, modified: Long = 1L) =
        SessionSummary(id, title = id, firstPrompt = "", messageCount = 1, cwd = dir, lastModified = modified, group = group)

    /**
     * A: First and Second, then a group left empty on purpose. The daemon lists the NEWER session first and it belongs to
     * the SECOND group, so a flat render also reorders the project — the other half of the report.
     */
    private fun Daemon.putAlpha(account: String = ACCT, renameSupported: Boolean = false) = put(
        A,
        rows = listOf(row(NEWER, A, group = "two", modified = 2L), row(OLDER, A, group = "one", modified = 1L)),
        groups = listOf(SessionGroup("one", "First", 0), SessionGroup("two", "Second", 1), SessionGroup("empty", "Empty", 2)),
        account = account,
        renameSupported = renameSupported,
    )

    /** B with no groups yet — a group-aware owner, so it is editable while listed. */
    private fun Daemon.putFlatBeta() = put(B, listOf(row("b1", B), row("b2", B)), groups = emptyList())

    /** B with one group whose id happens to be one of A's: any verb on A's copy that reached the listing would hit it. */
    private fun Daemon.putGroupedBeta() = put(
        B, listOf(row("b1", B, group = "two"), row("b2", B)), groups = listOf(SessionGroup("two", "Review", 0)),
        renameSupported = true,
    )

    /** The sidebar in a fixed 640dp column, wearing the app's own context menu — its "Move to group ·" lead-in is a node. */
    private fun ComposeUiTest.showSidebar(model: DesktopModel) {
        setContent {
            PocketTheme {
                CompositionLocalProvider(LocalContextMenuRepresentation provides PocketContextMenuRepresentation) {
                    Box(Modifier.height(640.dp)) { Sidebar(model) }
                }
            }
        }
        waitForIdle()
    }

    /** Where the RECENT list holds [key], by its own answer; -1 = the list does not emit that row. */
    private fun ComposeUiTest.rowIndex(key: String): Int =
        onNodeWithTag("sidebar-list").fetchSemanticsNode().config[SemanticsProperties.IndexForKey](key)

    /** [keys] are rows RECENT emits back to back, in exactly this order. */
    private fun ComposeUiTest.assertRows(vararg keys: String) {
        val at = keys.map { rowIndex(it) }
        assertTrue(
            at.first() >= 0 && at.zipWithNext().all { (a, b) -> b == a + 1 },
            "expected ${keys.toList()} back to back in RECENT, found them at $at",
        )
    }

    /** A while it is NOT the listed project: its header, then each section in group order followed by its own session. */
    private fun ComposeUiTest.assertAlphaSections() =
        assertRows("h:$A", "gh:$A:one", "s:$A:$OLDER", "gh:$A:two", "s:$A:$NEWER", "gh:$A:empty")

    /** The row inside RECENT reading exactly [text], scrolled to [key] first. */
    private fun ComposeUiTest.listRow(text: String, key: String? = null): SemanticsNodeInteraction {
        if (key != null) {
            onNodeWithTag("sidebar-list").performScrollToKey(key)
            waitForIdle()
        }
        return onAllNodes(hasText(text) and hasAnyAncestor(hasTestTag("sidebar-list"))).onFirst()
    }

    /** A node reading [text] outside RECENT — a pin row, or an item of an open context menu. */
    private fun ComposeUiTest.outsideList(text: String): SemanticsNodeInteraction =
        onAllNodes(hasText(text) and !hasAnyAncestor(hasTestTag("sidebar-list"))).onFirst()

    private fun ComposeUiTest.hover(node: SemanticsNodeInteraction) {
        node.performMouseInput { moveTo(center) }
        waitForIdle()
    }

    private fun ComposeUiTest.openMenuOn(node: SemanticsNodeInteraction) {
        node.performMouseInput { rightClick(center) }
        waitForIdle()
    }

    private fun ComposeUiTest.editorsInList(): Int =
        onAllNodes(hasSetTextAction() and hasAnyAncestor(hasTestTag("sidebar-list"))).fetchSemanticsNodes().size

    private val moveToLabel get() = "${str(Res.string.group_move_to)} ·"

    /** The refill sweep resumes off a delay timer (not the test thread) — poll for its completion. */
    private fun waitUntil(ms: Long, cond: () -> Boolean): Boolean {
        val end = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < end) {
            if (cond()) return true
            Thread.sleep(20)
        }
        return cond()
    }

    @Test
    fun aProjectKeepsItsSectionsInOrderEveryTimeItStopsBeingTheListedOne() = runComposeUiTest {
        withLiveModel(seed = { putAlpha(); putFlatBeta() }) { repo, _, m ->
            m.openProject(DkProject(A, "alpha"))
            showSidebar(m)
            // precondition: listed, A has always rendered its sections (and "+ New group" above them)
            assertRows("h:$A", "ng:$A", "gh:$A:one", "s:$A:$OLDER", "gh:$A:two", "s:$A:$NEWER", "gh:$A:empty")

            m.openProject(DkProject(B, "beta")) // visiting another project
            waitForIdle()
            assertEquals(B, repo.sessionsDir.value)
            assertAlphaSections()
            assertEquals(-1, rowIndex("ng:$A"), "a project that is not listed offers no way to create a group")

            m.refresh(m.sessionGroups.first { it.path == A }) // A's header refresh lists it again…
            waitForIdle()
            assertEquals(A, repo.sessionsDir.value)
            assertEquals(listOf(B, A), m.sessionGroups.map { it.path }, "…without moving it in RECENT")

            m.refresh(m.sessionGroups.first { it.path == B }) // …and B's moves the listing away from A once more
            waitForIdle()
            assertEquals(B, repo.sessionsDir.value)
            assertEquals(listOf(B, A), m.sessionGroups.map { it.path })
            assertAlphaSections()
        }
    }

    @Test
    fun aProjectHoldingOnlyEmptyGroupsStaysInRecent() = runComposeUiTest {
        withLiveModel(seed = {
            put(E, rows = emptyList(), groups = listOf(SessionGroup("plans", "Plans", 0)))
            put(F, rows = emptyList(), groups = emptyList())
            putFlatBeta()
        }) { _, _, m ->
            m.openProject(DkProject(E, "plans"))
            m.openProject(DkProject(F, "bare"))
            m.openProject(DkProject(B, "beta"))
            showSidebar(m)

            assertRows("h:$E", "gh:$E:plans") // a group created ahead of its sessions is structure the user made
            assertEquals(-1, rowIndex("h:$F"), "a project with neither sessions nor groups still leaves RECENT while not listed")
        }
    }

    @Test
    fun aCopiedSectionFoldsLikeTheListedProjectsOwn() = runComposeUiTest {
        withLiveModel(seed = { putAlpha(); putFlatBeta() }) { _, _, m ->
            m.openProject(DkProject(A, "alpha"))
            m.openProject(DkProject(B, "beta"))
            showSidebar(m)
            assertAlphaSections()

            listRow("First").performClick()
            waitForIdle()
            assertTrue(m.groupCollapsed(A, "one"), "the fold is the same per-project memory the listed project uses")
            assertEquals(-1, rowIndex("s:$A:$OLDER"), "folding the section hides its session")
            assertRows("h:$A", "gh:$A:one", "gh:$A:two", "s:$A:$NEWER", "gh:$A:empty")

            listRow("First").performClick()
            waitForIdle()
            assertAlphaSections()
        }
    }

    @Test
    fun copiedGroupHeadersAreReadOnlyWhileTheListedProjectsStayEditableAndActOnIt() = runComposeUiTest {
        withLiveModel(seed = { putAlpha(renameSupported = true); putGroupedBeta() }) { _, daemon, m ->
            m.openProject(DkProject(A, "alpha"))
            m.openProject(DkProject(B, "beta"))
            showSidebar(m)
            assertAlphaSections()
            assertRows("h:$B", "ng:$B", "gh:$B:two", "s:$B:b1")

            hover(listRow("Review")) // the listed project's header shows its edit verbs on hover…
            assertTrue(present(str(Res.string.group_rename)), "precondition: hovering an editable header reveals Rename")
            hover(listRow("Second")) // …A's copy of the group with the same id shows none
            assertFalse(present(str(Res.string.group_rename)), "a copied header must not offer Rename")
            assertFalse(present(str(Res.string.group_delete)), "…nor Delete")
            hover(listRow("First"))
            assertFalse(present(str(Res.string.group_rename)))

            hover(listRow("Review"))
            listRow(str(Res.string.group_delete)).performClick()
            waitForIdle()
            listRow(str(Res.string.group_delete)).performClick() // the confirm bar's own Delete
            waitForIdle()
            assertEquals(listOf(GroupDelete(B, "two")), daemon.sent.filterIsInstance<GroupDelete>(), "the delete names B")
            assertEquals(-1, rowIndex("gh:$B:two"), "B's re-pushed listing has no such group")
            assertAlphaSections()
            assertTrue(present("Second"), "A's group of the same id is untouched")
        }
    }

    @Test
    fun aRenameLeftOpenOnAHeaderThatStopsBeingEditableIsDropped() = runComposeUiTest {
        withLiveModel(seed = { putAlpha(renameSupported = true); putGroupedBeta() }) { repo, daemon, m ->
            m.openProject(DkProject(A, "alpha"))
            m.openProject(DkProject(B, "beta"))
            showSidebar(m)

            hover(listRow("Review"))
            listRow(str(Res.string.group_rename)).performClick()
            waitForIdle()
            assertEquals(1, editorsInList(), "precondition: B's rename field is open")

            m.refresh(m.sessionGroups.first { it.path == A }) // the listing moves to A while that field is still open
            waitForIdle()
            assertEquals(A, repo.sessionsDir.value)
            assertRows("h:$B", "gh:$B:two", "s:$B:b1", "gh:$B:__ungrouped__", "s:$B:b2")
            assertEquals(0, editorsInList(), "the field must go: its Enter would rename the LISTED project's group \"two\" — A's")
            hover(listRow("Review"))
            assertFalse(present(str(Res.string.group_rename)))
            assertTrue(daemon.sent.none { it is GroupRename })
        }
    }

    @Test
    fun sessionMenusMoveOnlyTheListedProjectsSessions() = runComposeUiTest {
        withLiveModel(seed = { putAlpha(renameSupported = true); putGroupedBeta() }) { _, daemon, m ->
            m.openProject(DkProject(A, "alpha"))
            m.openProject(DkProject(B, "beta"))
            showSidebar(m)
            assertAlphaSections()

            openMenuOn(listRow("b2", key = "s:$B:b2")) // a listed project's row moves between its own groups…
            assertTrue(present(moveToLabel), "precondition: the listed project's row offers its groups")
            outsideList("Review").performClick()
            waitForIdle()
            assertEquals(listOf(GroupAssign(B, "b2", "two")), daemon.sent.filterIsInstance<GroupAssign>(), "…and the move names B")

            openMenuOn(listRow(OLDER, key = "s:$A:$OLDER")) // a copied project's row, in a group of its own
            assertTrue(present(str(Res.string.session_rename)), "precondition: the row's menu is open")
            assertFalse(present(moveToLabel), "no move into the copied groups")
            assertFalse(present(str(Res.string.group_move_out)), "…nor out of its own: either would reach the listed project")
            assertEquals(1, daemon.sent.count { it is GroupAssign })
        }
    }

    @Test
    fun aPinnedSessionOfACopiedProjectOffersNoGroupMoves() = runComposeUiTest {
        withLiveModel(seed = { putAlpha(renameSupported = true); putGroupedBeta() }) { _, _, m ->
            m.openProject(DkProject(A, "alpha"))
            m.openProject(DkProject(B, "beta"))
            m.pin(m.sessionGroups.first { it.path == A }.sessions.first { it.sessionId == OLDER })
            showSidebar(m)

            openMenuOn(outsideList(OLDER))
            assertTrue(present(str(Res.string.unpin_project)), "precondition: the pin row's menu is open")
            assertFalse(present(moveToLabel))
            assertFalse(present(str(Res.string.group_move_out)), "its session is grouped in A, and A is not the listed project")
        }
    }

    @Test
    fun renamesAndDeletesReplaceTheCopyInsteadOfResurrectingOldGroups() = runComposeUiTest {
        withLiveModel(seed = { putAlpha(); putFlatBeta() }) { repo, daemon, m ->
            m.openProject(DkProject(A, "alpha"))
            m.openProject(DkProject(B, "beta")) // A's first copy: First / Second / Empty
            daemon.rename(A, "two", "Second v2") // renamed on another client meanwhile
            m.refresh(m.sessionGroups.first { it.path == A }) // listed again: the daemon's groups win
            m.deleteGroup("empty") // and deleted from this sidebar while listed
            showSidebar(m)
            assertEquals(A, repo.sessionsDir.value)
            assertTrue(present("Second v2"), "precondition: the listed project shows the rename")
            assertEquals(-1, rowIndex("gh:$A:empty"), "precondition: …and the delete")

            m.refresh(m.sessionGroups.first { it.path == B }) // A stops being listed again
            waitForIdle()
            assertRows("h:$A", "gh:$A:one", "s:$A:$OLDER", "gh:$A:two", "s:$A:$NEWER")
            assertEquals(-1, rowIndex("gh:$A:empty"), "the deleted group must not come back with the copy")
            assertTrue(present("Second v2"), "the copy carries the new name…")
            assertFalse(present("Second"), "…not the one A had the first time it was left")
        }
    }

    @Test
    fun anOlderDaemonOrAProjectWithoutGroupsStaysFlatAndUneditable() = runComposeUiTest {
        withLiveModel(seed = {
            // a row may still name a group the listing carries no definition for: it renders flat all the same
            put(OLD, listOf(row("o1", OLD), row("o2", OLD, group = "ghost")), groups = null)
            put(NONE, listOf(row("n1", NONE)), groups = emptyList())
            putAlpha()
        }) { _, _, m ->
            m.openProject(DkProject(OLD, "legacy"))
            showSidebar(m)
            assertRows("h:$OLD", "s:$OLD:o1", "s:$OLD:o2")
            assertEquals(-1, rowIndex("ng:$OLD"), "an older daemon offers no group editing, listed or not")

            m.openProject(DkProject(NONE, "fresh"))
            waitForIdle()
            assertRows("h:$NONE", "ng:$NONE", "s:$NONE:n1") // listed and group-aware: the first group is creatable
            assertRows("h:$OLD", "s:$OLD:o1", "s:$OLD:o2")

            m.openProject(DkProject(A, "alpha"))
            waitForIdle()
            assertRows("h:$NONE", "s:$NONE:n1") // a copy of a flat listing stays flat, with nothing to edit
            assertRows("h:$OLD", "s:$OLD:o1", "s:$OLD:o2")
        }
    }

    @Test
    fun theSameFolderOnTwoMachinesKeepsEachMachinesOwnGroups() = runComposeUiTest {
        withLiveModel(seed = {
            putAlpha()
            putFlatBeta()
            put(A, listOf(row(OLDER, A, group = "one")), groups = listOf(SessionGroup("one", "Theirs", 0)), account = OTHER)
            put(B, listOf(row("b1", B)), groups = emptyList(), account = OTHER)
        }) { repo, _, m ->
            m.openProject(DkProject(A, "alpha"))
            m.openProject(DkProject(B, "beta"))
            // The other machine lists the SAME folder with its own groups. Only the binding moves: one repository stands in
            // for both links, which is all the model's per-account RECENT can tell apart.
            repo.paired.value = binding(OTHER)
            m.openProject(DkProject(A, "alpha"))
            m.openProject(DkProject(B, "beta"))
            showSidebar(m)
            assertRows("h:$A", "gh:$A:one", "s:$A:$OLDER")
            assertTrue(present("Theirs"))
            assertFalse(present("First"), "this machine's groups stay on this machine")

            repo.paired.value = binding(ACCT)
            waitForIdle()
            assertAlphaSections()
            assertTrue(present("First"))
            assertFalse(present("Theirs"), "…and the other machine's on the other")
        }
    }

    @Test
    fun aCopiedProjectsRunningDotsFollowTheDaemonWithoutMovingItsSections() = runComposeUiTest {
        withLiveModel(seed = { putAlpha(); putFlatBeta() }) { repo, _, m ->
            m.openProject(DkProject(A, "alpha"))
            m.openProject(DkProject(B, "beta"))
            showSidebar(m)
            fun olderRunning() = m.sessionGroups.first { it.path == A }.sessions.first { it.sessionId == OLDER }.running
            assertFalse(olderRunning())

            // the directory poll: A's older session is mid-turn now
            repo.receiveForTest(Directories(listOf(
                DirectoryEntry(
                    A, "alpha", isDir = true, hasSessions = true, open = true, executing = true, activeSessionId = OLDER,
                    activeSessions = listOf(ActiveSession(OLDER, executing = true)),
                ),
                DirectoryEntry(B, "beta", isDir = true, hasSessions = true),
            )))
            waitForIdle()
            assertTrue(olderRunning(), "the copied row lights up from the daemon's answer")
            assertAlphaSections()
            assertEquals(listOf(B, A), m.sessionGroups.map { it.path }, "a directory update moves no project")

            // …and the turn ends
            repo.receiveForTest(Directories(listOf(
                DirectoryEntry(A, "alpha", isDir = true, hasSessions = true),
                DirectoryEntry(B, "beta", isDir = true, hasSessions = true),
            )))
            waitForIdle()
            assertFalse(olderRunning())
            assertAlphaSections()
            assertEquals(listOf(B, A), m.sessionGroups.map { it.path })
        }
    }

    @Test
    fun restoredProjectsRefillWithTheirOwnSections() {
        val store = FakeDesktopStore()
        val seed: Daemon.() -> Unit = {
            putAlpha()
            put(OLD, listOf(row("o1", OLD)), groups = null)
            put(B, listOf(row("b1", B, group = "review", modified = 2L), row("b2", B)), groups = listOf(SessionGroup("review", "Review", 0)))
        }
        // the run before the restart: A, then the older daemon's project, then B — only the visit KEYS reach the store
        withLiveModel(store, seed) { _, _, m ->
            m.openProject(DkProject(A, "alpha"))
            m.openProject(DkProject(OLD, "legacy"))
            m.openProject(DkProject(B, "beta"))
        }
        assertFalse(store.map.values.any { "First" in it || "Review" in it }, "no group definition is stored client-side")

        // Restart: a fresh repository and model over the same store. Cold-idle on a ready link, the refill sweep re-lists
        // the restored projects oldest first, and each reply carries that project's own groups.
        withLiveModel(store, seed) { repo, _, m ->
            assertTrue(
                waitUntil(5_000) { repo.sessionsDir.value == B && m.sessionGroups.size == 3 && m.sessionGroups.all { it.sessions.isNotEmpty() } },
                "the restored projects refill through the listing path",
            )
            runComposeUiTest {
                showSidebar(m)
                assertRows("h:$B", "ng:$B", "gh:$B:review", "s:$B:b1", "gh:$B:__ungrouped__", "s:$B:b2") // listed last: live
                assertRows("h:$OLD", "s:$OLD:o1")
                assertAlphaSections()
                assertTrue(rowIndex("h:$B") < rowIndex("h:$OLD") && rowIndex("h:$OLD") < rowIndex("h:$A"), "in the order they were left")
            }
        }
    }

    private companion object {
        const val ACCT = "acct-this"
        const val OTHER = "acct-other"
        const val A = "/work/alpha"
        const val B = "/work/beta"
        const val E = "/work/plans"
        const val F = "/work/bare"
        const val OLD = "/work/legacy"
        const val NONE = "/work/fresh"
        const val OLDER = "older"
        const val NEWER = "newer"
    }
}
