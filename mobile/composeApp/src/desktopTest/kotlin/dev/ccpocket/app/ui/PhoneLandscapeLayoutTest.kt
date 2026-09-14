package dev.ccpocket.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.asSkiaBitmap
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SkikoComposeUiTest
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.v2.runSkikoComposeUiTest
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import dev.ccpocket.app.advanceFrameAndWait
import dev.ccpocket.app.data.PocketRepository
import dev.ccpocket.app.pairing.PairedDaemon
import dev.ccpocket.app.present
import dev.ccpocket.app.resources.Res
import dev.ccpocket.app.resources.dir_projects
import dev.ccpocket.app.resources.new_session_cta
import dev.ccpocket.app.resources.switcher_current
import dev.ccpocket.app.resources.switcher_open
import dev.ccpocket.app.resources.wide_pick_session
import dev.ccpocket.app.str
import dev.ccpocket.app.theme.PocketTheme
import dev.ccpocket.app.theme.Tok
import dev.ccpocket.protocol.ActiveSession
import dev.ccpocket.protocol.AgentKind
import dev.ccpocket.protocol.ChatRole
import dev.ccpocket.protocol.ConvoHistory
import dev.ccpocket.protocol.DirectoryEntry
import dev.ccpocket.protocol.HistoryMessage
import dev.ccpocket.protocol.PermissionMode
import dev.ccpocket.protocol.SessionLive
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A phone on its side is still a phone (issue #378).
 *
 * #334 decided two panes by width alone, so a phone held sideways (844×390) crossed the 700dp line and kept a
 * 380dp list pinned beside a squeezed chat. These mount the REAL routing root — the `WideLayoutScope {
 * ContentRouter }` pair App() uses — with the device handed in as a [WideLayoutPolicy], since the desktop test
 * JVM is not a phone. A rotation here is the root changing size inside ONE composition, which is what a host
 * that keeps its composition does: iOS, a resized window, and an Android Activity that takes its size changes in
 * place (the manifest's configChanges).
 *
 * What these are, honestly: offscreen Compose-desktop fixtures. They prove the layout decision, that the chat
 * stays the same composition, and what that composition keeps. They are NOT device acceptance: that a real
 * Android Activity does stay put through a rotation, a split-screen resize or a fold, the platform readings of
 * the device class (Android display metrics, the iOS idiom), the real keyboard inset (a plain padding stands in
 * for the root's imePadding) and touch scrolling are not exercised.
 */
@OptIn(ExperimentalTestApi::class)
class PhoneLandscapeLayoutTest {

    private val phone = WideLayoutPolicy(LayoutDeviceClass.PHONE)
    private val largeScreen = WideLayoutPolicy(LayoutDeviceClass.LARGE_SCREEN)

    private class Scene(start: DpSize) {
        var size by mutableStateOf(start)

        /** Stands in for the keyboard: App() pads INSIDE the layout scope, so it never reaches the decision. */
        var keyboard by mutableStateOf(0.dp)
        val listState = LazyListState()
        lateinit var repo: PocketRepository
        var wide: Boolean? = null
    }

    /**
     * An open chat long enough to scroll, plus one live session in another project — the only thing that gives
     * the composer its switcher chip. Ids are per test: drafts are keyed by session id in a store the whole test
     * JVM shares.
     */
    private fun PocketRepository.seedChat(tag: String) {
        receiveForTest(
            SessionLive(
                convoId = "c-378-$tag", workdir = "/Users/alex/code/relay-server", sessionId = "s-378-$tag",
                mode = PermissionMode.DEFAULT, executing = false, model = "claude-fable-5", agent = AgentKind.CLAUDE,
            ),
        )
        receiveForTest(
            ConvoHistory(
                "c-378-$tag",
                (1..40).map {
                    if (it % 2 == 0) {
                        HistoryMessage(ChatRole.ASSISTANT, "Step $it: retry the relay handshake with jittered backoff, then re-check the session lock before resuming.")
                    } else {
                        HistoryMessage(ChatRole.USER, "What does step ${it + 1} change?")
                    }
                },
            ),
        )
        directories.add(
            DirectoryEntry(
                path = "/Users/alex/code/billing", name = "billing", isDir = true, open = true,
                activeSessions = listOf(ActiveSession(sessionId = "s-378-other-$tag", title = OTHER_TITLE)),
                activeSessionId = "s-378-other-$tag", activeSessionTitle = OTHER_TITLE,
            ),
        )
    }

    /** Mounts the root pair at [start] on a [canvas] roomy enough for every size the test turns it to. */
    private fun scene(
        policy: WideLayoutPolicy,
        start: DpSize,
        tag: String,
        canvas: DpSize = DpSize(1100.dp, 1100.dp),
        density: Float = 1f,
        block: SkikoComposeUiTest.(Scene) -> Unit,
    ) = runSkikoComposeUiTest(Size(canvas.width.value * density, canvas.height.value * density), Density(density)) {
        // The v2 runner on purpose: its StandardTestDispatcher queues a launched effect for the test scheduler. The
        // v1 runner's UnconfinedTestDispatcher started it inline, so a chat remounted by a resize across the 700dp
        // line (BoxWithConstraints subcomposes it inside measure) ran its landing scrollToItem within that same
        // pass and failed with "performMeasureAndLayout called during measure layout".
        // a mounted chat animates (pulses, tickers); every assertion here is about a settled frame
        mainClock.autoAdvance = false
        val s = Scene(start)
        setContent {
            val scope = rememberCoroutineScope()
            s.repo = remember {
                PocketRepository(
                    scope,
                    PairedDaemon(relay = "wss://test.invalid", accountId = "acct-378-$tag", daemonPub = "pub", deviceId = "dev", credential = "cred"),
                ).apply { seedChat(tag) }
            }
            PocketTheme {
                // loose constraints, top-start: the scope measures exactly the size under test
                Box(Modifier.fillMaxSize()) {
                    WideLayoutScope(Modifier.size(s.size).background(Tok.base), policy = policy) {
                        s.wide = LocalWideLayout.current
                        Box(Modifier.fillMaxSize().padding(bottom = s.keyboard)) {
                            ContentRouter(s.repo, chatListStateForTest = s.listState)
                        }
                    }
                }
            }
        }
        awaitLanded(s)
        block(s)
    }

    @Test
    fun aPhoneOnItsSideGivesTheChatTheWholeWidth() = scene(phone, PHONE_LANDSCAPE, "land") { s ->
        assertEquals(false, s.wide, "a phone never takes the two-pane layout, however wide it is held")
        assertFalse(listPaneShowing(), "no list may be pinned beside a phone's chat")
        assertFalse(placeholderShowing(), "…and no empty pane either")
        assertEquals(
            PHONE_LANDSCAPE.width.value.toInt() - TRANSCRIPT_INSETS, s.listState.layoutInfo.viewportSize.width,
            "the transcript spans the whole 844dp window, less only its own 16dp sides",
        )
    }

    /** The same window under the width-only rule a large screen keeps — the contrast #378 draws for phones only. */
    @Test
    fun aLargeScreenThatWideStillSplits() = scene(largeScreen, PHONE_LANDSCAPE, "split") { s ->
        assertEquals(true, s.wide, "844dp clears the 700dp line on a large screen")
        assertTrue(listPaneShowing(), "a large screen keeps its list pane")
        val transcript = s.listState.layoutInfo.viewportSize.width
        assertTrue(
            transcript <= PHONE_LANDSCAPE.width.value.toInt() - LIST_PANE - TRANSCRIPT_INSETS,
            "…and its chat gives up the list's 380dp (transcript ${transcript}px)",
        )
    }

    @Test
    fun rotationAndTheKeyboardKeepAPhonesChatDraftAndReadingPosition() = scene(phone, PHONE_PORTRAIT, "rotate") { s ->
        val draft = "keep this draft through the turn"
        composer().performTextInput(draft)
        parkMidTranscript(s)
        val convo = s.repo.convoId.value

        fun sameChat(moment: String) {
            advanceFrameAndWait()
            assertEquals(false, s.wide, "$moment: a phone stays one column")
            assertFalse(listPaneShowing() || placeholderShowing(), "$moment: no second pane appeared")
            assertEquals(convo, s.repo.convoId.value, "$moment: the same conversation is open")
            assertEquals(draft, composerText(), "$moment: the unsent draft is still in the field")
            // a remounted chat lands back on its latest message — staying put is what shows it was never rebuilt
            assertEquals(READ_AT, s.listState.firstVisibleItemIndex, "$moment: the reader is still on the same message")
        }

        s.size = PHONE_LANDSCAPE
        sameChat("turned to landscape")
        assertEquals(
            PHONE_LANDSCAPE.width.value.toInt() - TRANSCRIPT_INSETS, s.listState.layoutInfo.viewportSize.width,
            "the turned chat takes the new width",
        )
        s.keyboard = 120.dp
        sameChat("keyboard up in landscape")
        s.keyboard = 0.dp
        s.size = PHONE_PORTRAIT
        sameChat("back upright")
        s.keyboard = 300.dp
        sameChat("keyboard up upright")
        // a host that resizes the WINDOW for the keyboard squeezes the root itself: height never enters
        s.keyboard = 0.dp
        s.size = DpSize(PHONE_LANDSCAPE.width, 270.dp)
        sameChat("landscape window squeezed by a keyboard")
    }

    @Test
    fun theSwitcherOpensOverAPhoneChatAndHandsItBackUntouched() = scene(phone, PHONE_LANDSCAPE, "switcher") { s ->
        val draft = "half-written follow-up"
        composer().performTextInput(draft)
        parkMidTranscript(s)
        val convo = s.repo.convoId.value
        val session = s.repo.sessionKey.value

        fun untouched(moment: String) {
            assertEquals(convo, s.repo.convoId.value, "$moment: the same conversation is open")
            assertEquals(session, s.repo.sessionKey.value, "$moment: …as the same session")
            assertFalse(s.repo.switchingSession.value, "$moment: nothing started switching")
            assertEquals(draft, composerText(), "$moment: the draft is still in the field")
            assertEquals(READ_AT, s.listState.firstVisibleItemIndex, "$moment: the reader is still on the same message")
            assertFalse(listPaneShowing(), "$moment: the list only ever comes up as the sheet")
        }

        // the list on demand is the switcher the composer already carries — no drawer, no pane
        onNode(hasContentDescription(str(Res.string.switcher_open))).performClick()
        advanceFrameAndWait()
        assertTrue(sheetShowing(), "the stack chip opens the session switcher")
        assertTrue(present(OTHER_TITLE), "…offering the other live session")
        untouched("switcher open")

        // the current session's row is where you already are: a tap on it goes nowhere
        onAllNodes(hasText(str(Res.string.switcher_current))).onFirst().performClick()
        advanceFrameAndWait()
        assertTrue(sheetShowing(), "tapping the current row leaves the switcher up")
        untouched("current row tapped")

        // a tap on the scrim above the sheet dismisses it
        onRoot().performTouchInput { click(Offset(PHONE_LANDSCAPE.width.value / 2, 12f)) }
        advanceFrameAndWait()
        assertFalse(sheetShowing(), "the scrim dismissed the switcher")
        untouched("switcher dismissed")

        // Back still LEAVES the chat — it is not a peek at the list
        s.repo.backToBrowse()
        advanceFrameAndWait()
        assertNull(s.repo.convoId.value, "back closes the conversation")
        assertTrue(listPaneShowing(), "back lands on the list, alone and full width")
        assertFalse(placeholderShowing())
    }

    @Test
    fun aTabletKeepsTwoPanesAndANarrowTabletWindowGetsOne() = scene(largeScreen, TABLET_LANDSCAPE, "tablet") { s ->
        assertEquals(true, s.wide, "a 1024dp tablet keeps two panes")
        assertTrue(listPaneShowing(), "…its list beside the chat")
        assertFalse(placeholderShowing(), "…and the chat, not the placeholder, on the right")

        // a keyboard squeezing a tablet's height does not make it a phone
        s.size = DpSize(TABLET_LANDSCAPE.width, 360.dp)
        advanceFrameAndWait()
        assertEquals(true, s.wide, "height never enters the decision")
        assertTrue(listPaneShowing())

        // a tablet window narrower than the line — split screen, a slim portrait — is one column. Crossing the
        // line only takes the list pane from beside a large screen's chat, which stays where it is
        // (WideLayoutContinuityTest); only phones never cross.
        s.size = TABLET_NARROW
        advanceFrameAndWait()
        advanceFrameAndWait()
        assertEquals(false, s.wide, "under 700dp a large screen is one column too")
        assertFalse(listPaneShowing() || placeholderShowing(), "…with no second pane")
        assertTrue(onAllNodes(hasSetTextAction()).fetchSemanticsNodes().isNotEmpty(), "…just the chat, composer and all")
    }

    @Test
    fun aDesktopWindowKeepsTheWidthOnlyRule() = runSkikoComposeUiTest(Size(1024f, 768f)) {
        var deviceClass: LayoutDeviceClass? = null
        var wide: Boolean? = null
        setContent {
            deviceClass = platformLayoutDeviceClass()
            WideLayoutScope(Modifier.fillMaxSize()) { wide = LocalWideLayout.current }
        }
        waitForIdle()
        assertEquals(LayoutDeviceClass.LARGE_SCREEN, deviceClass, "a desktop window is a resizable large screen")
        assertEquals(true, wide, "…so the default scope still splits a 1024dp window, as before #378")
    }

    @Test
    fun theDeviceIsAskedBeforeTheWidth() {
        for (width in listOf(390, 700, 844, 932, 1366)) {
            assertFalse(phone.isWide(width.dp), "a phone never splits (${width}dp)")
        }
        assertFalse(largeScreen.isWide(699.dp), "a large screen splits at 700dp, not before")
        assertTrue(largeScreen.isWide(700.dp))
        assertTrue(largeScreen.isWide(1024.dp))
        assertFalse(WideLayoutPolicy(LayoutDeviceClass.LARGE_SCREEN, minWidth = 900.dp).isWide(844.dp), "the line is injectable")

        // Android's smallest-width line: handsets and a folded foldable's cover screen stay phones…
        assertEquals(LayoutDeviceClass.PHONE, layoutDeviceClassForSmallestWidth(360.dp))
        assertEquals(LayoutDeviceClass.PHONE, layoutDeviceClassForSmallestWidth(599.dp))
        // …a 7" tablet and an unfolded inner screen do not
        assertEquals(LayoutDeviceClass.LARGE_SCREEN, layoutDeviceClassForSmallestWidth(600.dp))
        assertEquals(LayoutDeviceClass.LARGE_SCREEN, layoutDeviceClassForSmallestWidth(701.dp))
    }

    /**
     * Opt-in review frames, NOT a device capture: with PHONE_LANDSCAPE_SHOT_OUT set to a directory this renders
     * the 844×390 phone chat at 2× through the same fixture, then the switcher over it. A plain run writes nothing.
     */
    @Test
    fun renderPhoneLandscapeFrames() {
        val out = System.getenv("PHONE_LANDSCAPE_SHOT_OUT")?.let(::File)?.apply { mkdirs() } ?: return
        scene(phone, PHONE_LANDSCAPE, "shot", canvas = PHONE_LANDSCAPE, density = 2f) { s ->
            assertEquals(false, s.wide)
            save(out, "phone-landscape-chat-844x390@2x.png")
            onNode(hasContentDescription(str(Res.string.switcher_open))).performClick()
            advanceFrameAndWait()
            assertTrue(sheetShowing())
            save(out, "phone-landscape-switcher-844x390@2x.png")
        }
    }

    /** Frames until the chat has landed on its latest message, as every real open does. */
    private fun SkikoComposeUiTest.awaitLanded(s: Scene) {
        repeat(60) {
            if (s.listState.firstVisibleItemIndex > 0) return
            advanceFrameAndWait()
        }
        error("the chat never landed on its latest message")
    }

    /** Scroll back into the history, where someone partway through a long reply would be. */
    private fun SkikoComposeUiTest.parkMidTranscript(s: Scene) {
        runOnIdle { s.listState.requestScrollToItem(READ_AT) }
        advanceFrameAndWait()
        assertEquals(READ_AT, s.listState.firstVisibleItemIndex, "sanity: parked mid-transcript")
    }

    private fun SkikoComposeUiTest.composer() = onAllNodes(hasSetTextAction()).onFirst()

    private fun SkikoComposeUiTest.composerText() =
        composer().fetchSemanticsNode().config[SemanticsProperties.EditableText].text

    /** Either list screen: the sessions list by its one CTA, Projects by its title. */
    private fun SkikoComposeUiTest.listPaneShowing() =
        present(str(Res.string.new_session_cta)) || present(str(Res.string.dir_projects))

    private fun SkikoComposeUiTest.placeholderShowing() = present(str(Res.string.wide_pick_session))

    private fun SkikoComposeUiTest.sheetShowing() = present(str(Res.string.switcher_current))

    private fun SkikoComposeUiTest.save(out: File, name: String) {
        val png = Image.makeFromBitmap(onRoot().captureToImage().asSkiaBitmap()).encodeToData(EncodedImageFormat.PNG)
            ?: error("PNG encode failed for $name")
        val file = File(out, name).apply { writeBytes(png.bytes) }
        println("phone landscape fixture frame: ${file.absolutePath}")
    }

    private companion object {
        val PHONE_PORTRAIT = DpSize(390.dp, 844.dp)
        val PHONE_LANDSCAPE = DpSize(844.dp, 390.dp)
        val TABLET_LANDSCAPE = DpSize(1024.dp, 768.dp)
        val TABLET_NARROW = DpSize(600.dp, 960.dp)
        const val READ_AT = 12

        /** ChatScreen pads its transcript 16dp on each side. */
        const val TRANSCRIPT_INSETS = 32

        /** The two-pane list column (WideLayout's LEFT_PANE_WIDTH). */
        const val LIST_PANE = 380
        const val OTHER_TITLE = "Wire up usage endpoint"
    }
}
