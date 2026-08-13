package dev.ccpocket.app.data

import dev.ccpocket.protocol.ActiveSession
import dev.ccpocket.protocol.BackgroundJob
import dev.ccpocket.protocol.BackgroundJobs
import dev.ccpocket.protocol.Directories
import dev.ccpocket.protocol.DirectoryEntry
import dev.ccpocket.protocol.Frame
import dev.ccpocket.protocol.ListSessions
import dev.ccpocket.protocol.JobKind
import dev.ccpocket.protocol.JobStatus
import dev.ccpocket.protocol.PermissionMode
import dev.ccpocket.protocol.SessionLive
import dev.ccpocket.protocol.Sessions
import dev.ccpocket.protocol.TurnDone
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** End-to-end repository edges for #239's client-observed completion mark. */
class SessionCompletionVisibilityTest {
    private fun frame(vararg sessions: ActiveSession) = Directories(
        listOf(
            DirectoryEntry(
                path = "/work/project",
                name = "project",
                isDir = true,
                open = sessions.isNotEmpty(),
                busy = sessions.any { it.busy },
                executing = sessions.any { it.executing },
                activeSessionId = sessions.firstOrNull()?.sessionId,
                activeSessionTitle = sessions.firstOrNull()?.title,
                activeSessions = sessions.toList(),
            ),
        ),
    )

    @Test
    fun externallyStartedCompletionsAreRetainedUntilAnAuthoritativeOpen() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val repo = PocketRepository(scope)
        try {
            repo.receiveForTest(
                frame(
                    ActiveSession("s-a", "A", executing = true, executingAuthoritative = true),
                    ActiveSession("s-b", "B", busy = true),
                ),
            )
            repo.receiveForTest(
                frame(
                    ActiveSession("s-a", "A", executingAuthoritative = true),
                    ActiveSession("s-b", "B", busy = true),
                ),
            )
            assertEquals(setOf("s-a"), repo.unseenSessions.value, "the first result remains visible while B works")
            repo.receiveForTest(
                frame(
                    ActiveSession("s-a", "A", executingAuthoritative = true),
                    ActiveSession("s-b", "B", executingAuthoritative = true),
                ),
            )

            assertEquals(setOf("s-a", "s-b"), repo.unseenSessions.value)

            repo.rememberOpenedSession("/work/project", "s-a", "A", null, markSeen = false)
            assertTrue("s-a" in repo.unseenSessions.value, "an optimistic tap must not claim the result was seen")

            repo.rememberOpenedSession("/work/project", "s-a", "A", null)
            assertEquals(setOf("s-b"), repo.unseenSessions.value, "the authoritative open clears only its own result")
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun idleLiveConversationClosingIsNotInventedAsACompletion() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val repo = PocketRepository(scope)
        try {
            repo.receiveForTest(frame(ActiveSession("idle", "Already read")))
            repo.receiveForTest(frame())
            assertTrue(repo.unseenSessions.value.isEmpty())
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun backgroundWorkMustAlsoSettleBeforeTheResultIsMarked() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val repo = PocketRepository(scope)
        try {
            repo.receiveForTest(frame(ActiveSession("s", executing = true, executingAuthoritative = true)))
            repo.receiveForTest(frame(ActiveSession("s", busy = true)))
            assertTrue(repo.unseenSessions.value.isEmpty(), "background work still running is not complete")

            repo.receiveForTest(frame(ActiveSession("s", executingAuthoritative = true)))
            assertEquals(setOf("s"), repo.unseenSessions.value)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun aCompletionRefreshesTheVisibleProjectSoAnExternalRowCanAppear() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val sent = mutableListOf<Frame>()
        val repo = PocketRepository(scope).apply { onSendForTest = { sent += it } }
        try {
            repo.receiveForTest(Sessions("/work/project", emptyList()))
            repo.receiveForTest(frame(ActiveSession("external", executing = true, executingAuthoritative = true)))
            repo.receiveForTest(frame(ActiveSession("external", executingAuthoritative = true)))

            assertEquals(
                listOf("/work/project"),
                sent.filterIsInstance<ListSessions>().map { it.workdir },
                "the completion edge, not a busy timer, refreshes the visible session history",
            )
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun aTurnSeenOnScreenIsNotMarkedIfTheUserBacksOutBeforeTheNextPoll() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val repo = PocketRepository(scope)
        try {
            repo.receiveForTest(
                SessionLive(
                    convoId = "c-current",
                    workdir = "/work/project",
                    sessionId = "current",
                    mode = PermissionMode.DEFAULT,
                    executing = true,
                ),
            )
            repo.receiveForTest(frame(ActiveSession("current", executing = true, executingAuthoritative = true)))
            repo.receiveForTest(TurnDone("c-current"))
            repo.backToBrowse()
            repo.receiveForTest(frame(ActiveSession("current", executingAuthoritative = true)))

            assertTrue(repo.unseenSessions.value.isEmpty(), "the visible TurnDone was already seen")
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun aRunningBackgroundJobKeepsTheCompletionEdgeAfterTurnDone() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val repo = PocketRepository(scope)
        try {
            repo.receiveForTest(
                SessionLive(
                    convoId = "c-bg",
                    workdir = "/work/project",
                    sessionId = "bg-session",
                    mode = PermissionMode.DEFAULT,
                    executing = true,
                ),
            )
            repo.receiveForTest(frame(ActiveSession("bg-session", executing = true, executingAuthoritative = true)))
            val running = BackgroundJob(
                id = "job-1",
                kind = JobKind.BASH_BACKGROUND,
                label = "long build",
                status = JobStatus.RUNNING,
                startedAt = 1L,
                lastUpdate = 1L,
            )
            repo.receiveForTest(BackgroundJobs("c-bg", listOf(running)))
            repo.receiveForTest(TurnDone("c-bg"))
            repo.backToBrowse()

            // The background job can finish entirely before the next 5-second directory poll. The first
            // snapshot after leaving is therefore already idle; TurnDone must not have erased the baseline.
            repo.receiveForTest(frame(ActiveSession("bg-session", executingAuthoritative = true)))

            assertEquals(setOf("bg-session"), repo.unseenSessions.value)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun terminalTranscriptRecencyExpiringNeverCreatesANewResult() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val repo = PocketRepository(scope)
        try {
            repo.receiveForTest(frame(ActiveSession("terminal", executing = true, executingAuthoritative = false)))
            repo.receiveForTest(frame(ActiveSession("terminal", executing = false, executingAuthoritative = false)))

            assertTrue(repo.unseenSessions.value.isEmpty(), "a 30-second mtime window expiring is not a completion")
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun oldDaemonBusyToUntrustedForegroundExecutionNeverCreatesANewResult() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val sent = mutableListOf<Frame>()
        val repo = PocketRepository(scope).apply { onSendForTest = { sent += it } }
        try {
            repo.receiveForTest(Sessions("/work/project", emptyList()))
            repo.receiveForTest(frame(ActiveSession("legacy", busy = true)))
            repo.receiveForTest(
                frame(ActiveSession("legacy", executing = true, executingAuthoritative = false)),
            )
            assertTrue(repo.unseenSessions.value.isEmpty(), "foreground work is still running, but its source is unknown")
            repo.receiveForTest(
                frame(ActiveSession("legacy", executing = false, executingAuthoritative = false)),
            )

            assertTrue(repo.unseenSessions.value.isEmpty(), "mtime expiry stays unknown and cannot complete the old baseline")
            assertTrue(sent.filterIsInstance<ListSessions>().isEmpty(), "a false edge must not refresh history either")
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun disappearingWorkingRowFailsClosedInsteadOfInventingAResult() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val repo = PocketRepository(scope)
        try {
            repo.receiveForTest(frame(ActiveSession("gone", executing = true, executingAuthoritative = true)))
            repo.receiveForTest(frame())
            assertTrue(repo.unseenSessions.value.isEmpty(), "an absent row is unknown, not authoritative settlement")
        } finally {
            scope.cancel()
        }
    }
}
