package dev.ccpocket.app.ui

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.v2.runComposeUiTest
import dev.ccpocket.app.data.PinIssueBoard
import dev.ccpocket.app.data.PinIssueKind
import dev.ccpocket.app.data.PocketRepository
import dev.ccpocket.app.net.PinEnqueueResult
import dev.ccpocket.app.pairing.PairedDaemon
import dev.ccpocket.app.pins.FileProjectPinPersistence
import dev.ccpocket.app.pins.PinScopeKey
import dev.ccpocket.app.pins.ProjectPinRegistry
import dev.ccpocket.app.resources.Res
import dev.ccpocket.app.resources.pin_issue_capacity
import dev.ccpocket.app.resources.pin_issue_dismiss
import dev.ccpocket.app.resources.pin_issue_pairing_changed
import dev.ccpocket.app.resources.pin_issue_retry
import dev.ccpocket.app.theme.PocketTheme
import dev.ccpocket.protocol.DaemonInfo
import dev.ccpocket.protocol.ProjectPin
import dev.ccpocket.protocol.ProjectPinErrors
import dev.ccpocket.protocol.ProjectPinsSnapshot
import dev.ccpocket.protocol.ProjectPinsState
import dev.ccpocket.protocol.SyncProjectPins
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.jetbrains.compose.resources.getString
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The one pin-sync notice (issue #362) fed by a real repository's real issue event: what it says, the root guards the
 * phone and desktop mounts pass it, that retry only asks the computer again, and that dismissal holds for that
 * computer's problem until a newer explicit failure.
 */
@OptIn(ExperimentalTestApi::class)
class ProjectPinIssueNoticeTest {

    private val dir = Files.createTempDirectory("ccp-pin-notice").toFile()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val owner = PairedDaemon(relay = "wss://127.0.0.1:9", accountId = "acct-n", daemonPub = "pk", deviceId = "dev-n", credential = "c-n")
    private val registry = ProjectPinRegistry(FileProjectPinPersistence(dir)).apply { initializeAuthorityOnce { listOf(owner) } }

    @BeforeTest
    fun setUp() = PinIssueBoard.shared.clearForTest()

    @AfterTest
    fun tearDown() {
        scope.cancel()
        dir.deleteRecursively()
    }

    private fun refuse(repo: PocketRepository, request: SyncProjectPins, ackSeq: Long = 0) = repo.receiveForTest(
        ProjectPinsState(request.subscriptionId, request.requestId, request.streamId, ackSeq,ProjectPinsSnapshot("inc-1", 0), ProjectPinErrors.CAPACITY, "raw daemon text /secret/path"),
    )

    @Test
    fun the_mobile_and_desktop_guards_hide_the_notice_under_lock_cover_approval_background_minimize_and_overlays() {
        assertTrue(pinNoticeAllowedOnMobile(foreground = true, locked = false, covered = false, approvalPending = false))
        assertFalse(pinNoticeAllowedOnMobile(foreground = true, locked = true, covered = false, approvalPending = false))
        assertFalse(pinNoticeAllowedOnMobile(foreground = true, locked = false, covered = true, approvalPending = false))
        assertFalse(pinNoticeAllowedOnMobile(foreground = true, locked = false, covered = false, approvalPending = true))
        assertFalse(pinNoticeAllowedOnMobile(foreground = false, locked = false, covered = false, approvalPending = false))
        assertTrue(pinNoticeAllowedOnDesktop(windowVisible = true, minimized = false, overlayOpen = false))
        assertFalse(pinNoticeAllowedOnDesktop(windowVisible = false, minimized = false, overlayOpen = false))
        assertFalse(pinNoticeAllowedOnDesktop(windowVisible = true, minimized = true, overlayOpen = false))
        assertFalse(pinNoticeAllowedOnDesktop(windowVisible = true, minimized = false, overlayOpen = true))
    }

    @Test
    fun the_notice_shows_the_repositorys_actual_issue_retries_by_fetching_and_stays_dismissed_for_that_problem() = runComposeUiTest {
        val sent = mutableListOf<SyncProjectPins>()
        val repo = PocketRepository(scope, pinnedTo = owner, projectPinRegistry = registry).apply {
            useRelay = true
            onSendForTest = {}
            pinWriterForTest = { frame, _ -> sent += frame; PinEnqueueResult.ACCEPTED }
        }
        repo.receiveForTest(DaemonInfo(supportsProjectPins = true))
        repo.receiveForTest(ProjectPinsState(sent.last().subscriptionId, sent.last().requestId, sent.last().streamId, 0, ProjectPinsSnapshot("inc-1", 0)))
        repo.togglePin("/p/1")
        refuse(repo, sent.last())

        val allowed = mutableStateOf(true)
        setContent { PocketTheme { ProjectPinIssueNotice(repo, allowed.value) } }
        val capacity = runBlocking { getString(Res.string.pin_issue_capacity) }
        val retry = runBlocking { getString(Res.string.pin_issue_retry) }
        val dismiss = runBlocking { getString(Res.string.pin_issue_dismiss) }
        onNodeWithText(capacity).assertExists()
        onAllNodes(hasText("/secret/path", substring = true)).assertCountEquals(0)
        onAllNodes(hasText(ProjectPinErrors.CAPACITY, substring = true)).assertCountEquals(0)

        // each root guard hides it without consuming the event
        for (guard in listOf(
            pinNoticeAllowedOnMobile(foreground = true, locked = true, covered = false, approvalPending = false),
            pinNoticeAllowedOnMobile(foreground = true, locked = false, covered = true, approvalPending = false),
            pinNoticeAllowedOnDesktop(windowVisible = true, minimized = true, overlayOpen = false),
            pinNoticeAllowedOnDesktop(windowVisible = true, minimized = false, overlayOpen = true),
        )) {
            allowed.value = guard
            waitForIdle()
            onAllNodesWithTag(PIN_ISSUE_NOTICE_TAG).assertCountEquals(0)
        }
        allowed.value = true
        waitForIdle()
        onNodeWithTag(PIN_ISSUE_NOTICE_TAG).assertExists()

        // retry asks the computer again with a pin fetch; the connection state is not touched
        val phase = repo.phase.value
        val status = repo.status.value
        val frames = sent.size
        onNodeWithText(retry).performSemanticsAction(SemanticsActions.OnClick) // the button's own onClick
        waitForIdle()
        assertEquals(frames + 1, sent.size)
        assertTrue(sent.last().ops.isEmpty())
        assertEquals(phase, repo.phase.value)
        assertEquals(status, repo.status.value)

        refuse(repo, sent.last()) // the same problem again: still the same notice
        waitForIdle()
        onNodeWithText(dismiss).performSemanticsAction(SemanticsActions.OnClick)
        waitForIdle()
        onAllNodesWithTag(PIN_ISSUE_NOTICE_TAG).assertCountEquals(0)

        repo.togglePin("/p/2") // the computer refuses the same way once more: it stays dismissed
        refuse(repo, sent.last())
        waitForIdle()
        onAllNodesWithTag(PIN_ISSUE_NOTICE_TAG).assertCountEquals(0)
        assertTrue(repo.projectPinSyncIssue.value != null && repo.projectPinIssueNotice.value == null, "dismissed, not resolved")

        // a newer explicit failure for this computer is a new notice
        registry.refreshAfterPairingChange { listOf(owner.copy(credential = "c-replaced")) }
        repo.togglePin("/p/3")
        waitForIdle()
        val pairingChanged = runBlocking { getString(Res.string.pin_issue_pairing_changed) }
        onNodeWithText(pairingChanged).assertExists()
        assertEquals(PinIssueKind.PAIRING_CHANGED, repo.projectPinIssueNotice.value?.kind)
    }

    private fun ownerRepo(sent: MutableList<SyncProjectPins>) = PocketRepository(scope, pinnedTo = owner, projectPinRegistry = registry).apply {
        useRelay = true
        onSendForTest = {}
        pinWriterForTest = { frame, _ -> sent += frame; PinEnqueueResult.ACCEPTED }
    }

    /** A working answer to [request]: the computer's cursor for this stream is [ackSeq]. */
    private fun answer(repo: PocketRepository, request: SyncProjectPins, ackSeq: Long, revision: Long, vararg paths: String) = repo.receiveForTest(
        ProjectPinsState(request.subscriptionId, request.requestId, request.streamId, ackSeq, ProjectPinsSnapshot("inc-1", revision, paths.map { ProjectPin(it, "K$it") })),
    )

    private fun pendingSeqs() = registry.scope(PinScopeKey.Owner(owner.accountId)).document().pending.map { it.seq }

    @Test
    fun a_dismissed_refusal_ends_only_when_its_own_work_is_acknowledged_and_newly_refused_work_is_a_new_notice() {
        val sent = mutableListOf<SyncProjectPins>()
        val repo = ownerRepo(sent)
        repo.receiveForTest(DaemonInfo(supportsProjectPins = true))
        answer(repo, sent.last(), 0, 0)
        repo.togglePin("/p/1")
        assertEquals(listOf(1L), sent.last().ops.map { it.seq })
        refuse(repo, sent.last())
        val first = repo.projectPinIssueNotice.value
        assertEquals(PinIssueKind.CAPACITY, first?.kind)
        repo.dismissProjectPinIssue(first!!)
        assertNull(repo.projectPinIssueNotice.value)

        repo.togglePin("/p/2") // a local edit settles nothing: the computer refuses the original work once more
        assertEquals(listOf(1L, 2L), sent.last().ops.map { it.seq })
        refuse(repo, sent.last())
        assertNull(repo.projectPinIssueNotice.value, "the same ongoing refusal stays dismissed")
        assertTrue(repo.projectPinSyncIssue.value != null, "dismissed, not resolved")

        repo.retryProjectPins() // a working fetch that acknowledges nothing is not a recovery
        assertTrue(sent.last().ops.isEmpty())
        answer(repo, sent.last(), 0, 1)
        val resend = sent.last()
        assertEquals(listOf(1L, 2L), resend.ops.map { it.seq })
        refuse(repo, resend)
        assertNull(repo.projectPinIssueNotice.value, "seq 1 is still pending: still that dismissed occurrence")

        repo.retryProjectPins() // the computer now proves seq 1 committed while the newer seq 2 still waits
        answer(repo, sent.last(), 1, 2, "/p/1")
        assertEquals(listOf(2L), pendingSeqs(), "newer work is still pending")
        assertNull(repo.projectPinSyncIssue.value)
        val next = sent.last()
        assertEquals(listOf(2L), next.ops.map { it.seq })
        refuse(repo, next, ackSeq = 1) // an ordinary reply refuses the newer work
        val again = repo.projectPinIssueNotice.value
        assertEquals(PinIssueKind.CAPACITY, again?.kind, "a new occurrence is shown")
        assertTrue(again!!.id > first.id)
        assertFalse(again.explicit)
        assertEquals(again.id, PinIssueBoard.shared.occurrenceIdForTest(again.scope, PinIssueKind.CAPACITY))
    }

    @Test
    fun another_holders_working_fetch_neither_revives_nor_ends_a_dismissed_refusal_of_still_pending_work() {
        val sentA = mutableListOf<SyncProjectPins>()
        val sentB = mutableListOf<SyncProjectPins>()
        val a = ownerRepo(sentA)
        val b = ownerRepo(sentB) // a second holder of the same computer's pins
        a.receiveForTest(DaemonInfo(supportsProjectPins = true))
        answer(a, sentA.last(), 0, 0)
        b.receiveForTest(DaemonInfo(supportsProjectPins = true))
        answer(b, sentB.last(), 0, 0)
        a.togglePin("/p/1")
        assertEquals(listOf(1L), sentA.last().ops.map { it.seq })
        refuse(a, sentA.last())
        val first = a.projectPinIssueNotice.value
        assertEquals(PinIssueKind.CAPACITY, first?.kind)
        a.dismissProjectPinIssue(first!!)

        b.retryProjectPins() // the other holder's fetch works, and acknowledges nothing
        assertTrue(sentB.last().ops.isEmpty())
        answer(b, sentB.last(), 0, 1)
        assertEquals(listOf(1L), pendingSeqs())
        assertNull(a.projectPinIssueNotice.value, "the dismissed refusal is not revived")
        assertTrue(a.projectPinSyncIssue.value != null)
        assertNull(b.projectPinIssueNotice.value)
        assertEquals(first.id, PinIssueBoard.shared.occurrenceIdForTest(first.scope, PinIssueKind.CAPACITY), "…nor ended")

        val resend = sentB.last()
        assertEquals(listOf(1L), resend.ops.map { it.seq }, "the original work goes out again")
        refuse(b, resend)
        assertTrue(b.projectPinSyncIssue.value != null)
        assertNull(b.projectPinIssueNotice.value, "the other holder's refusal is that same dismissed occurrence")
        assertNull(a.projectPinIssueNotice.value)
        assertEquals(first.id, PinIssueBoard.shared.occurrenceIdForTest(first.scope, PinIssueKind.CAPACITY))

        b.togglePin("/p/2")
        assertEquals(listOf(1L, 2L), sentB.last().ops.map { it.seq })
        refuse(b, sentB.last(), ackSeq = 1) // seq 1 committed; the newer seq 2 is refused
        assertEquals(listOf(2L), pendingSeqs())
        val again = b.projectPinIssueNotice.value
        assertEquals(PinIssueKind.CAPACITY, again?.kind, "newly refused work is a new notice")
        assertTrue(again!!.id > first.id)
        assertFalse(again.explicit)
        assertNull(a.projectPinIssueNotice.value, "the ended occurrence's old notice is never shown again")
    }
}
