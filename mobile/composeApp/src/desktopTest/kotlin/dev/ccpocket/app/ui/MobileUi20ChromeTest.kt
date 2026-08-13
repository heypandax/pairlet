package dev.ccpocket.app.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SkikoComposeUiTest
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.unit.Density
import dev.ccpocket.app.data.PocketRepository
import dev.ccpocket.app.pairing.PairedDaemon
import dev.ccpocket.app.present
import dev.ccpocket.app.resources.Res
import dev.ccpocket.app.resources.allow_once
import dev.ccpocket.app.resources.ap_required
import dev.ccpocket.app.resources.chat_context
import dev.ccpocket.app.resources.chat_session_info
import dev.ccpocket.app.resources.chat_src_tool
import dev.ccpocket.app.resources.chat_tool_failed
import dev.ccpocket.app.resources.chat_you
import dev.ccpocket.app.resources.copy_path
import dev.ccpocket.app.resources.deny
import dev.ccpocket.app.resources.done
import dev.ccpocket.app.resources.fl_switch_computer
import dev.ccpocket.app.resources.message_queued_hint
import dev.ccpocket.app.resources.mode_auto_short
import dev.ccpocket.app.resources.new_session_cta
import dev.ccpocket.app.resources.ses_active
import dev.ccpocket.app.resources.ses_conn_connecting
import dev.ccpocket.app.resources.ses_conn_offline
import dev.ccpocket.app.resources.ses_conn_online
import dev.ccpocket.app.resources.ses_messages
import dev.ccpocket.app.resources.ses_recent
import dev.ccpocket.app.resources.st_act_answer
import dev.ccpocket.app.resources.st_act_review
import dev.ccpocket.app.resources.st_also_running
import dev.ccpocket.app.resources.st_answer
import dev.ccpocket.app.resources.st_complete
import dev.ccpocket.app.resources.st_new_result
import dev.ccpocket.app.resources.st_running
import dev.ccpocket.app.resources.switcher_open
import dev.ccpocket.app.resources.time_just_now
import dev.ccpocket.app.str
import dev.ccpocket.app.theme.PocketTheme
import dev.ccpocket.app.ui.chat.CONTEXT_SEP
import dev.ccpocket.app.ui.chat.ChatHeader
import dev.ccpocket.app.ui.chat.ChatStateBlock
import dev.ccpocket.app.ui.chat.ContextLine
import dev.ccpocket.app.ui.chat.ToolTurnBand
import dev.ccpocket.app.ui.chat.chatStateUi
import dev.ccpocket.app.ui.session.SessionListRow
import dev.ccpocket.app.ui.session.SessionRowUi
import dev.ccpocket.app.ui.session.SurfaceState
import dev.ccpocket.protocol.AgentKind
import dev.ccpocket.protocol.ActiveSession
import dev.ccpocket.protocol.CLAUDE_PERMISSION_MODE_AUTO
import dev.ccpocket.protocol.ChatRole
import dev.ccpocket.protocol.ConvoHistory
import dev.ccpocket.protocol.Directories
import dev.ccpocket.protocol.DirectoryEntry
import dev.ccpocket.protocol.HistoryMessage
import dev.ccpocket.protocol.PendingApproval
import dev.ccpocket.protocol.PendingApprovals
import dev.ccpocket.protocol.PermissionAsk
import dev.ccpocket.protocol.PermissionMode
import dev.ccpocket.protocol.SessionLive
import dev.ccpocket.protocol.SessionSummary
import dev.ccpocket.protocol.Sessions
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Mobile UI 2.0 chrome for Sessions and Chat, composed at the release baseline (iPhone 17, 402 × 874 pt).
 *
 * The pure ladders are pinned in `SessionStateUiTest` / `ChatStateUiTest`; this file is what actually
 * reaches the screen: the context hierarchy, the Active/Recent split, one written state per row, the pinned
 * dock, the collapsible chat context, the turn source labels — and the things that must NOT appear (emoji
 * metadata, a `-` placeholder, a fabricated branch or timestamp, a second decision path).
 *
 * [runDesktopComposeUiTest] gives a real 402 × 874 scene, and pinning `Density(1f, fontScale)` makes one
 * scene pixel one dp — so the same assertions double as the overflow proof at 100% and at 200% type.
 */
@OptIn(ExperimentalTestApi::class)
class MobileUi20ChromeTest {

    private val dir = "/Users/alex/code/cc-pocket"
    private val deepDir = "/Users/alex/Desktop/Project/app/cc-pocket"
    private val convo = "c-ui20"

    private fun account() = PairedDaemon(
        relay = "wss://test.invalid", accountId = "acct-ui20", daemonPub = "pub",
        deviceId = "dev", credential = "cred", hostName = "alex-macbook",
    )

    private fun summary(
        id: String, title: String = id, live: Boolean = false, branch: String? = "main",
        prompt: String = "", count: Int = 0,
    ) = SessionSummary(
        sessionId = id, title = title, firstPrompt = prompt, messageCount = count, cwd = dir,
        lastModified = 0L, gitBranch = branch, live = live, agent = AgentKind.CLAUDE,
    )

    private fun approvalAsk(title: String = "Run command") = PermissionAsk(
        convoId = convo, askId = "ap-1", tool = "Bash", title = title,
        inputPreview = "./gradlew :protocol:test", timeoutSec = 600,
    )

    private fun sessionLive(executing: Boolean = false) = SessionLive(
        convoId = convo, workdir = dir, sessionId = "s1", mode = PermissionMode.DEFAULT,
        executing = executing, model = "claude-sonnet-4-5", agent = AgentKind.CLAUDE,
    )

    /**
     * The Chat context an ordinary production session really carries (the Pandaa-iPhone report): a Claude
     * session in Auto mode on a named machine, with a known model, out of a DEEP project path. The shallow
     * [dir] fixture is not enough here — the header used to overflow on exactly this much truth.
     */
    private fun ordinarySession(workdir: String = deepDir) = SessionLive(
        convoId = convo, workdir = workdir, sessionId = "s1", mode = PermissionMode.DEFAULT,
        executing = false, model = "claude-fable-5", agent = AgentKind.CLAUDE,
        permissionMode = CLAUDE_PERMISSION_MODE_AUTO,
    )

    /** The two compact rows those facts must collapse into, and the one line they read as when hidden. */
    private val identityRow get() = "Claude$CONTEXT_SEP${str(Res.string.mode_auto_short)}${CONTEXT_SEP}fable"
    private val placeRow = "alex-macbook${CONTEXT_SEP}cc-pocket"
    private val collapsedRow get() = "$identityRow$CONTEXT_SEP$placeRow"

    /**
     * Compose [content] against a paired repository in a real 402 × 874 pt scene at [fontScale].
     *
     * `autoAdvance = false` because a streaming scene animates forever — every assertion below is about a
     * settled first frame, never about waiting one out.
     */
    private fun baseline(
        fontScale: Float = 1f,
        dark: Boolean = true,
        seed: PocketRepository.() -> Unit = {},
        content: @Composable (PocketRepository) -> Unit,
        assertions: SkikoComposeUiTest.() -> Unit,
    ) = runDesktopComposeUiTest(W, H) {
        mainClock.autoAdvance = false
        setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, fontScale)) {
                val scope = rememberCoroutineScope()
                val repo = remember { PocketRepository(scope, account()).apply(seed) }
                PocketTheme(dark = dark) { Box(Modifier.fillMaxSize()) { content(repo) } }
            }
        }
        waitForIdle()
        assertions()
    }

    /** Icon-only affordances carry their label as a content description, not as text. */
    private fun SkikoComposeUiTest.described(label: String): Boolean =
        onAllNodes(hasContentDescription(label)).fetchSemanticsNodes().isNotEmpty()

    /**
     * Whether a node is WHOLLY on screen: its clipped bounds match its unclipped ones, so no ancestor's
     * scroller is hiding part of it. `assertIsDisplayed` is not enough — a row peeking out of an
     * overflowing region by a few pt is "displayed" and still unreadable, unreachable and unclickable.
     */
    private fun SkikoComposeUiTest.fullyVisible(matcher: SemanticsMatcher): Boolean {
        val node = onAllNodes(matcher).onFirst()
        val shown = node.getBoundsInRoot()
        val whole = node.getUnclippedBoundsInRoot()
        return kotlin.math.abs((shown.top - whole.top).value) < 0.5f &&
            kotlin.math.abs((shown.bottom - whole.bottom).value) < 0.5f
    }

    /** Nothing may spill past the 402 pt viewport — the "no horizontal scroll" half of the responsive gate. */
    private fun SkikoComposeUiTest.assertWithinViewport(text: String) {
        val b = onAllNodes(hasText(text, substring = true)).onFirst().getUnclippedBoundsInRoot()
        assertTrue(b.left.value >= -0.5f, "\"$text\" starts off-screen at ${b.left}")
        assertTrue(b.right.value <= W + 0.5f, "\"$text\" overflows the ${W}pt viewport, ending at ${b.right}")
    }

    // ══ Sessions ═══════════════════════════════════════════════════════════════════════════════════

    @Test
    fun sessionsShowsTheContextHierarchyTheActiveRecentSplitAndAPinnedDock() = baseline(
        seed = {
            receiveForTest(
                Sessions(
                    dir,
                    listOf(summary("s-live", "Refactor auth module", live = true), summary("s-done", "Release notes 1.6")),
                ),
            )
        },
        content = { SessionsScreen(it) },
    ) {
        // Computer → Project → path, every line from a real value
        assertTrue(present("alex-macbook", substring = true), "the paired machine names the computer")
        // the link names exactly one honest state — and this scene has no live socket, so never "online"
        val connLabels = listOf(Res.string.ses_conn_online, Res.string.ses_conn_connecting, Res.string.ses_conn_offline).map { str(it) }
        assertEquals(1, connLabels.count { present(it, substring = true) }, "the link states exactly one state")
        assertFalse(present(str(Res.string.ses_conn_online), substring = true), "…and never claims online while it is not")
        // the folder is named ONCE, at the path's tail — never as a duplicate row stacked above the path
        assertFalse(present("cc-pocket"), "no standalone folder row")
        assertTrue(present("~/code/cc-pocket", substring = true), "the FULL workdir is on screen, not a tail fragment")
        // both halves of the list, each under its own heading, each row writing its state
        assertTrue(present(str(Res.string.ses_active).uppercase()), "running work gets the Active section")
        assertTrue(present(str(Res.string.ses_recent).uppercase()), "settled work gets the Recent section")
        assertTrue(present(str(Res.string.st_running), substring = true))
        assertTrue(present(str(Res.string.st_complete), substring = true))
        // the dock is PINNED to the bottom: it must sit below every row of the list
        val dock = onAllNodes(hasText(str(Res.string.new_session_cta))).onFirst()
        dock.assertIsDisplayed()
        val lastRowBottom = onAllNodes(hasText("Release notes 1.6")).onFirst().getUnclippedBoundsInRoot().bottom
        assertTrue(
            dock.getUnclippedBoundsInRoot().top > lastRowBottom,
            "New session is a pinned bottom dock, not the first card in the list",
        )
        assertWithinViewport(str(Res.string.new_session_cta))
        assertWithinViewport("~/code/cc-pocket")
    }

    @Test
    fun aClientObservedCompletionStaysVisibleAsANewResult() = baseline(
        seed = {
            receiveForTest(
                Sessions(
                    dir,
                    listOf(summary("s-fresh", "Just finished", live = true), summary("s-old", "Older result")),
                ),
            )
            val active = DirectoryEntry(
                path = dir, name = "cc-pocket", isDir = true, open = true, executing = true,
                activeSessionId = "s-fresh", activeSessionTitle = "Just finished",
                activeSessions = listOf(
                    ActiveSession("s-fresh", "Just finished", executing = true, executingAuthoritative = true),
                ),
            )
            receiveForTest(Directories(listOf(active)))
            receiveForTest(
                Directories(
                    listOf(
                        active.copy(
                            executing = false,
                            activeSessions = listOf(
                                ActiveSession("s-fresh", "Just finished", executingAuthoritative = true),
                            ),
                        ),
                    ),
                ),
            )
        },
        content = { SessionsScreen(it) },
    ) {
        assertTrue(present(str(Res.string.ses_active).uppercase()))
        assertTrue(present("Just finished"))
        assertTrue(present(str(Res.string.st_new_result), substring = true))
        assertTrue(present(str(Res.string.ses_recent).uppercase()))
        assertTrue(present("Older result"))
        assertTrue(present(str(Res.string.st_complete), substring = true))
    }

    /**
     * #239: two sessions that finished while the user was elsewhere are two independent rows, not one
     * grouped notice — each keeps its own title, its own written state and its own place in the daemon's
     * order, above a session that is still running. The one that was never running is ordinary history.
     */
    @Test
    fun twoIndependentNewResultsStayActiveAboveRunningWithCompleteInRecent() = baseline(
        seed = {
            receiveForTest(
                Sessions(
                    dir,
                    listOf(
                        summary("s-a", "Refine composer states", live = true),
                        summary("s-b", "Audit state precedence", live = true),
                        summary("s-run", "Run the mobile suite", live = true),
                        summary("s-done", "Document the release"),
                    ),
                ),
            )
            fun entry(executing: Set<String>) = DirectoryEntry(
                path = dir, name = "cc-pocket", isDir = true, open = true, executing = executing.isNotEmpty(),
                activeSessionId = executing.firstOrNull() ?: "s-a", activeSessionTitle = "Refine composer states",
                activeSessions = listOf("s-a", "s-b", "s-run")
                    .map { ActiveSession(it, it, executing = it in executing, executingAuthoritative = true) },
            )
            receiveForTest(Directories(listOf(entry(setOf("s-a", "s-b", "s-run")))))
            // a and b settle while another surface is open; the third turn keeps running
            receiveForTest(Directories(listOf(entry(setOf("s-run")))))
        },
        content = { SessionsScreen(it) },
    ) {
        val newResult = str(Res.string.st_new_result)
        assertEquals(
            2, onAllNodes(hasText(newResult, substring = true)).fetchSemanticsNodes().size,
            "each observed completion keeps its OWN row rather than collapsing into one notice",
        )
        val a = onAllNodes(hasText("Refine composer states")).onFirst().getUnclippedBoundsInRoot()
        val b = onAllNodes(hasText("Audit state precedence")).onFirst().getUnclippedBoundsInRoot()
        val running = onAllNodes(hasText("Run the mobile suite")).onFirst().getUnclippedBoundsInRoot()
        val recentLabel = onAllNodes(hasText(str(Res.string.ses_recent).uppercase())).onFirst().getUnclippedBoundsInRoot()
        assertTrue(a.top < b.top && b.top < running.top, "the daemon's order survives inside Active")
        assertTrue(running.bottom <= recentLabel.top, "all three stay in Active, above the Recent heading")
        assertTrue(
            onAllNodes(hasText("Document the release")).onFirst().getUnclippedBoundsInRoot().top > recentLabel.top,
            "a session that never ran while away is ordinary history",
        )
        assertTrue(present(str(Res.string.st_running), substring = true), "the running row still reads Running")
        assertTrue(present(str(Res.string.st_complete), substring = true))
    }

    @Test
    fun sessionRowsCarryNoEmojiMetadataAndNoPlaceholderForAMissingBranch() = baseline(
        seed = { receiveForTest(Sessions(dir, listOf(summary("s1", "Fix flaky socket test", branch = null, count = 8)))) },
        content = { SessionsScreen(it) },
    ) {
        assertFalse(present("💬", substring = true), "the message count is a written count, not an emoji label")
        assertFalse(present("⑂", substring = true), "the branch is named, not glyphed")
        // the WHOLE metadata line, exactly: state, agent, count. lastModified is 0 and gitBranch is null,
        // so both are simply absent — no `-`, no empty separator, nothing standing in for what isn't known
        assertTrue(
            present("${str(Res.string.st_complete)} · Claude · ${str(Res.string.ses_messages, 8)}"),
            "a row omits the facts it lacks instead of padding them",
        )
    }

    @Test
    fun anUntitledSessionDoesNotPrintItsPromptTwice() = baseline(
        seed = {
            receiveForTest(
                Sessions(
                    dir,
                    listOf(
                        summary("s1", title = "Redesign the sessions screen", prompt = "Redesign the sessions screen"),
                        summary("s2", title = "Auth refactor", prompt = "refactor the auth module end to end"),
                    ),
                ),
            )
        },
        content = { SessionsScreen(it) },
    ) {
        // a scanner-fallback title IS the first prompt — the row prints those words once, never as a preview too
        assertEquals(
            1, onAllNodes(hasText("Redesign the sessions screen")).fetchSemanticsNodes().size,
            "title and preview must not stack the same sentence",
        )
        // a REAL title keeps its preview: the prompt under it adds information
        assertTrue(present("refactor the auth module end to end"), "a distinct prompt still previews")
    }

    @Test
    fun anAttentionRowStatesItsStateAndItsActionOpensTheSession() = runDesktopComposeUiTest(W, H) {
        var opened = 0
        setContent {
            PocketTheme {
                SessionListRow(
                    SessionRowUi(summary("s1", "Refactor auth module"), SurfaceState.APPROVAL),
                    onOpen = { opened++ }, onLongPress = null,
                )
            }
        }
        waitForIdle()
        assertTrue(present(str(Res.string.ap_required), substring = true), "the state is written, never colour-only")
        onAllNodes(hasText(str(Res.string.st_act_review))).onFirst().performClick()
        assertEquals(1, opened, "Review opens the session — the decision still belongs to Secure Approval")
    }

    @Test
    fun anAnswerRowSaysAnswerNotApprove() = runDesktopComposeUiTest(W, H) {
        setContent {
            PocketTheme {
                SessionListRow(
                    SessionRowUi(summary("s1", "Pick a palette"), SurfaceState.ANSWER),
                    onOpen = {}, onLongPress = null,
                )
            }
        }
        waitForIdle()
        assertTrue(present(str(Res.string.st_answer), substring = true))
        assertTrue(present(str(Res.string.st_act_answer)))
        assertFalse(present(str(Res.string.ap_required), substring = true), "a question is not a permission gate")
    }

    @Test
    fun aRealPendingApprovalPromotesItsOwnRowAndOnlyItsOwnRow() = baseline(
        seed = {
            receiveForTest(
                Sessions(dir, listOf(summary("s-blocked", "Refactor auth module"), summary("s-idle", "Release notes 1.6"))),
            )
            // the daemon's own account-wide snapshot, carrying the real session/workdir context
            receiveForTest(PendingApprovals(listOf(PendingApproval(approvalAsk(), workdir = dir, sessionId = "s-blocked"))))
        },
        content = { SessionsScreen(it) },
    ) {
        assertTrue(present(str(Res.string.ap_required), substring = true), "the blocked session states it")
        assertEquals(
            1,
            onAllNodes(hasText(str(Res.string.st_act_review))).fetchSemanticsNodes().size,
            "exactly one row owns the intervention — the idle session must not borrow it",
        )
    }

    @Test
    fun theSessionsDockAndContextSurviveTwoHundredPercentType() = baseline(
        fontScale = 2f,
        seed = { receiveForTest(Sessions(dir, listOf(summary("s1", "Refactor the authentication module end to end")))) },
        content = { SessionsScreen(it) },
    ) {
        onAllNodes(hasText(str(Res.string.new_session_cta))).onFirst().assertIsDisplayed()
        assertWithinViewport(str(Res.string.new_session_cta))
        assertWithinViewport("~/code/cc-pocket")
    }

    @Test
    fun sessionsRendersTheSameGrammarInLightTheme() = baseline(
        dark = false,
        seed = { receiveForTest(Sessions(dir, listOf(summary("s1", "Refactor auth module", live = true)))) },
        content = { SessionsScreen(it) },
    ) {
        assertTrue(present(str(Res.string.st_running), substring = true))
        onAllNodes(hasText(str(Res.string.new_session_cta))).onFirst().assertIsDisplayed()
    }

    // ══ Chat ═══════════════════════════════════════════════════════════════════════════════════════

    @Test
    fun chatContextExpandsToTheFullPathAndCollapsesBack() = runDesktopComposeUiTest(W, H) {
        val long = "/Users/alex/code/cc-pocket-android-client/app/src/main/kotlin"
        setContent {
            PocketTheme {
                var expanded by remember { mutableStateOf(false) }
                ChatHeader(
                    title = "Refactor auth module",
                    summary = listOf(ContextLine("Claude"), ContextLine("default"), ContextLine("alex-macbook")),
                    workdir = long,
                    expanded = expanded,
                    onToggleContext = { expanded = !expanded },
                    onBack = {},
                    onSessionInfo = {},
                )
            }
        }
        waitForIdle()
        assertTrue(present("Claude · default · alex-macbook"), "collapsed, the summary is one line of real facts")
        assertFalse(present(str(Res.string.chat_session_info)), "and the expanded-only controls stay away")
        assertFalse(present("⌄"), "the disclosure uses an optically centered drawn chevron, not a font glyph")

        onAllNodes(hasText("Claude · default · alex-macbook")).onFirst().performClick()
        waitForIdle()
        assertTrue(present("~/code/cc-pocket-android-client/app/src/main/kotlin"), "expanded reveals the FULL path")
        assertTrue(described(str(Res.string.copy_path)), "…beside its own copy affordance")
        assertTrue(present(str(Res.string.chat_session_info)), "…and session info stays explicitly reachable")
        assertFalse(present("⌃"), "the expanded mark is the same chevron rotated, not another font glyph")

        onAllNodes(hasText(str(Res.string.chat_context).uppercase())).onFirst().performClick()
        waitForIdle()
        assertFalse(present(str(Res.string.chat_session_info)), "collapsing gives the region back to the stream")
        assertTrue(present("Claude · default · alex-macbook"), "…and restores the summary")
    }

    @Test
    fun anOrdinarySessionsWholeContextFitsTheExpandedRegionWithoutASecondScroll() = baseline(
        seed = {
            receiveForTest(ordinarySession())
            receiveForTest(ConvoHistory(convo, listOf(HistoryMessage(ChatRole.USER, "add a unit test for the stream parser"))))
        },
        content = { ChatScreen(it) },
    ) {
        // collapsed: one dot-separated statement of every fact that exists, and only those
        assertTrue(present(collapsedRow), "collapsed, the summary states the real facts on one line")
        onAllNodes(hasText(collapsedRow)).onFirst().performClick()
        waitForIdle()

        // expanded: the same facts as two compact rows, the full path, and the action — in ONE frame
        assertTrue(present(identityRow), "agent, permission mode and model share the identity row")
        assertTrue(present(placeRow), "machine and project folder share the location row")
        assertTrue(fullyVisible(hasText("~/Desktop/Project/app/cc-pocket")), "the FULL workdir is wholly on screen")
        assertTrue(described(str(Res.string.copy_path)), "…beside its own copy affordance")
        assertTrue(
            fullyVisible(hasText(str(Res.string.chat_session_info))),
            "Session info is reachable in the same frame — the region must not clip it below the fold",
        )
    }

    @Test
    fun theExpandedContextNamesTheProjectFolderOnceNotAsItsOwnRowToo() = baseline(
        seed = { receiveForTest(ordinarySession()) },
        content = { ChatScreen(it) },
    ) {
        onAllNodes(hasText(collapsedRow)).onFirst().performClick()
        waitForIdle()
        assertTrue(present(placeRow), "the folder rides the location row…")
        assertTrue(present("~/Desktop/Project/app/cc-pocket"), "…and the full path still spells it out")
        assertEquals(
            0,
            onAllNodes(hasText("cc-pocket")).fetchSemanticsNodes().size,
            "so a third, bare folder row would only spend a line saying it a third time",
        )
    }

    @Test
    fun theGroupedLocationRowIsStillTheMachineSwitcher() = baseline(
        seed = { receiveForTest(ordinarySession()) },
        content = { ChatScreen(it) },
    ) {
        onAllNodes(hasText(collapsedRow)).onFirst().performClick()
        waitForIdle()
        val row = onAllNodes(hasText(placeRow)).onFirst()
        row.assertHasClickAction()
        assertEquals(
            str(Res.string.switcher_open),
            row.fetchSemanticsNode().config.getOrNull(SemanticsActions.OnClick)?.label,
            "grouping the folder into the row must not cost the machine its accessibility label",
        )
        row.performClick()
        waitForIdle()
        assertTrue(present(str(Res.string.fl_switch_computer), substring = true), "…and it still opens the switcher")
    }

    @Test
    fun onlyTheFactsAndPathScrollAtTwoHundredPercentTypeSessionInfoStaysPinned() = baseline(
        fontScale = 2f,
        // double type AND a path deep enough to wrap several times: the body genuinely cannot fit
        seed = { receiveForTest(ordinarySession("/Users/alex/Desktop/Project/app/cc-pocket-android-client/app/src/main/kotlin")) },
        content = { ChatScreen(it) },
    ) {
        onAllNodes(hasText(identityRow, substring = true)).onFirst().performClick()
        waitForIdle()
        val path = "~/Desktop/Project/app/cc-pocket-android-client/app/src/main/kotlin"
        assertTrue(present(path), "the path is still rendered whole, never shrunk or truncated")
        assertFalse(fullyVisible(hasText(path)), "the facts+path body is what overflows, so it is what scrolls")
        assertTrue(
            fullyVisible(hasText(str(Res.string.chat_session_info))),
            "…while Session info rides below that overflow, whole and reachable",
        )
        assertWithinViewport(str(Res.string.chat_session_info))
    }

    @Test
    fun theChatHeaderNeitherLiftsASessionBranchNorInventsATimestamp() = baseline(
        seed = {
            // the branch IS known here — it rode in on the session list — and must still not reach Chat:
            // gitBranch is per-session truth for that row, not a fact about the conversation
            receiveForTest(Sessions(dir, listOf(summary("s1", "Refactor auth module", branch = "feat/auth-refactor"))))
            receiveForTest(sessionLive())
            receiveForTest(ConvoHistory(convo, listOf(HistoryMessage(ChatRole.USER, "add a unit test for the stream parser"))))
        },
        content = { ChatScreen(it) },
    ) {
        assertTrue(present("Claude", substring = true), "the agent is real and named")
        assertFalse(present("feat/auth-refactor", substring = true), "no branch is lifted into the conversation header")
        assertFalse(present(str(Res.string.time_just_now), substring = true), "no chat event carries a clock, so none is drawn")
    }

    @Test
    fun theWorkStreamLabelsUserAgentAndToolTurns() = baseline(
        seed = {
            receiveForTest(sessionLive())
            receiveForTest(
                ConvoHistory(
                    convo,
                    listOf(
                        HistoryMessage(ChatRole.USER, "add a unit test for the stream parser"),
                        HistoryMessage(ChatRole.ASSISTANT, "The parser now emits exactly one event per frame."),
                        HistoryMessage(ChatRole.TOOL, "./gradlew :protocol:test", tool = "Bash", ok = true),
                        HistoryMessage(ChatRole.TOOL, "cat build/reports/summary.txt", tool = "Bash", ok = true),
                    ),
                ),
            )
        },
        content = { ChatScreen(it) },
    ) {
        assertTrue(present(str(Res.string.chat_you).uppercase()), "the user turn names its source")
        assertTrue(present("CLAUDE"), "the agent turn names the REAL backend, not a generic \"assistant\"")
        assertTrue(present(str(Res.string.chat_src_tool).uppercase()), "and a tool call says so")
        // …but a RUN of tool calls says so once: each band's own tool chip already names its call, so the
        // label repeating between consecutive bands was pure air
        assertEquals(
            1, onAllNodes(hasText(str(Res.string.chat_src_tool).uppercase())).fetchSemanticsNodes().size,
            "consecutive tool turns share one source label",
        )
        assertTrue(present("Bash"), "the tool token is the daemon's own")
        assertTrue(present("./gradlew :protocol:test"), "the payload is the literal command, never a summary")
        assertTrue(present("cat build/reports/summary.txt"), "…for every band in the run")
        assertTrue(present(str(Res.string.done)), "a real ok = true reads as Done")
    }

    @Test
    fun aToolWithNoRecordedOutcomeClaimsNone() = runDesktopComposeUiTest(W, H) {
        setContent { PocketTheme { ToolTurnBand(tool = "Bash", preview = "./gradlew :protocol:test", status = null) } }
        waitForIdle()
        assertTrue(present("./gradlew :protocol:test"))
        assertFalse(present(str(Res.string.done)), "ok == null is running-or-unknown; it must not read as success")
        assertFalse(present(str(Res.string.chat_tool_failed)), "…nor as failure")
        assertFalse(present("passed", substring = true), "and no test count is invented")
    }

    @Test
    fun theChatStateBlockLeadsWithApprovalDemotesRunningAndDecidesNothing() = runDesktopComposeUiTest(W, H) {
        setContent {
            PocketTheme {
                ChatStateBlock(chatStateUi(approvalAsk("Upload coverage to Codecov"), sessionDegraded = false, streaming = true)!!)
            }
        }
        waitForIdle()
        assertTrue(present(str(Res.string.ap_required), substring = true), "the intervention leads")
        assertTrue(present("Upload coverage to Codecov"), "quoting the ask's own title")
        assertTrue(present(str(Res.string.st_also_running)), "and Running is only the qualifying line")
        // Secure Approval owns the decision: this block offers no second way to answer
        assertFalse(present(str(Res.string.st_act_review)), "no second Review path")
        assertFalse(present(str(Res.string.deny)), "no second Deny")
        assertFalse(present(str(Res.string.allow_once)), "no second Allow")
    }

    @Test
    fun theChatHeaderAndComposerSurviveTwoHundredPercentTypeAndRunningIsWrittenOnce() = baseline(
        fontScale = 2f,
        seed = {
            receiveForTest(sessionLive(executing = true))
            receiveForTest(ConvoHistory(convo, listOf(HistoryMessage(ChatRole.USER, "add a unit test for the stream parser"))))
        },
        content = { ChatScreen(it) },
    ) {
        // the queue note — the ONE place a bare running turn is written — stays reachable at double type
        onAllNodes(hasText(str(Res.string.message_queued_hint), substring = true)).onFirst().assertIsDisplayed()
        assertWithinViewport(str(Res.string.message_queued_hint))
        // streaming alone pins no "Running" band above the stream: the note + Stop already say it, and a
        // second full-width band said the same thing twice while costing the transcript a row
        assertFalse(present(str(Res.string.st_running)), "a bare running turn must not pin a state band too")
    }

    private companion object {
        const val W = 402
        const val H = 874
    }
}
