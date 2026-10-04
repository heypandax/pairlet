package dev.ccpocket.app.desktop

import dev.ccpocket.app.data.ApprovalKey
import dev.ccpocket.app.data.FleetCoordinator
import dev.ccpocket.app.data.FleetRuntime
import dev.ccpocket.app.data.PocketRepository
import dev.ccpocket.app.epochMillis
import dev.ccpocket.app.pairing.PairedDaemon
import dev.ccpocket.protocol.AskQuestion
import dev.ccpocket.protocol.Decision
import dev.ccpocket.protocol.OpenSession
import dev.ccpocket.protocol.PendingApproval
import dev.ccpocket.protocol.SessionSummary
import dev.ccpocket.protocol.PendingApprovals
import dev.ccpocket.protocol.PermissionAsk
import dev.ccpocket.protocol.PermissionVerdict
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Audit 2026-10-04 desktop H1: the bell, tray, Dock badge and machine rows read the daemon's ACCOUNT-WIDE
 * approval list (the phone's `fleetAttention()`), not just the open chat's first card. Before this, an ask
 * from a session the user had switched away from, from a session nobody had open, or from another paired
 * computer never reached any of those surfaces.
 */
class FleetAttentionDesktopTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val previousFleet = FleetRuntime.coordinator
    private val sent = mutableListOf<PermissionVerdict>()

    @AfterTest fun tearDown() {
        FleetRuntime.coordinator = previousFleet
        scope.cancel()
    }

    private fun binding(id: String) = PairedDaemon(
        relay = "wss://127.0.0.1:9", accountId = id, daemonPub = "pk-$id", deviceId = "dev", credential = "c-$id",
    )

    private fun primary() = PocketRepository(scope).apply {
        paired.value = binding(HERE)
        pairedList.clear(); pairedList.addAll(listOf(binding(HERE), binding(OTHER)))
        onSendForTest = { if (it is PermissionVerdict) sent += it }
    }

    private fun ask(convo: String, id: String = "3", cmd: String = "git push") =
        PermissionAsk(convo, id, "Bash", cmd, timeoutSec = 60)

    private fun row(convo: String, session: String, wd: String, id: String = "3") = PendingApproval(
        ask(convo, id), expiresAt = epochMillis() + 45_000, workdir = wd, sessionId = session,
    )

    @Test
    fun anotherSessionsApprovalReachesTheBell() {
        val repo = primary()
        val model = RepoDesktopModel(repo, scope, store = FakeDesktopStore())
        repo.convoId.value = "convo-b" // the user is looking at session B
        repo.receiveForTest(PendingApprovals(listOf(row("convo-a", "sess-a", "/repo-a"))))

        val a = model.attention.singleOrNull()
        assertEquals("convo-a", a?.convoId, "session A's ask must reach the bell while B is open: ${model.attention}")
        assertEquals("sess-a", a?.sessionId)
        assertEquals("/repo-a", a?.workdir)
        val s = assertNotNull(a?.seconds, "the daemon's deadline is known, so the row carries what is left of it")
        assertTrue(s in 40..45, "remaining seconds off expiresAt: $s")
        assertEquals(1, model.machines.single { it.computer.accountId == HERE }.pending)
    }

    @Test
    fun theSessionRowThatAsksIsLitByIdNotByTitle() {
        val repo = primary()
        val model = RepoDesktopModel(repo, scope, store = FakeDesktopStore())
        repo.sessions.addAll(listOf(summary("sess-a", "/repo-a"), summary("sess-twin", "/repo-a")))
        repo.receiveForTest(PendingApprovals(listOf(row("convo-a", "sess-a", "/repo-a"))))

        assertEquals(mapOf("sess-a" to 1, "sess-twin" to 0), model.sessions.associate { it.sessionId to it.pending })
    }

    @Test
    fun theOpenChatsQuestionStaysInTheBell() {
        // questions are not in the approval list; the bell listed them before and still does (tray routes them)
        val repo = primary()
        val model = RepoDesktopModel(repo, scope, store = FakeDesktopStore())
        repo.convoId.value = "convo-q"
        repo.receiveForTest(
            PermissionAsk("convo-q", "q1", "AskUserQuestion", "pick one", questions = listOf(AskQuestion("Which?"))),
        )

        val a = model.attention.single()
        assertTrue(a.question)
        assertEquals("convo-q", a.convoId)
    }

    @Test
    fun aRowNewInTheListIsAnArrivalButARepeatIsNot() {
        val repo = primary()
        val arrivals = mutableListOf<ApprovalKey>()
        repo.onApprovalArrived = { arrivals += it }

        repo.receiveForTest(PendingApprovals(listOf(row("convo-a", "sess-a", "/repo-a"))))
        repo.receiveForTest(PendingApprovals(listOf(row("convo-a", "sess-a", "/repo-a")))) // the next poll
        repo.convoId.value = "convo-a"
        repo.receiveForTest(ask("convo-a")) // the same request's live frame (resurface on attach)
        repo.receiveForTest(ask("convo-c", id = "9"))

        assertEquals(listOf(ApprovalKey("convo-a", "3"), ApprovalKey("convo-c", "9")), arrivals)
    }

    @Test
    fun openingARowOpensTheAskingSession() {
        val repo = primary()
        val opened = mutableListOf<OpenSession>()
        repo.onSendForTest = { if (it is OpenSession) opened += it }
        val model = RepoDesktopModel(repo, scope, store = FakeDesktopStore())
        repo.sessions.add(summary("sess-a", "/repo-a"))
        repo.receiveForTest(PendingApprovals(listOf(row("convo-a", "sess-a", "/repo-a"))))

        model.openAttention(model.attention.single())

        assertEquals(listOf("sess-a"), opened.map { it.resumeId })
    }

    @Test
    fun anApprovalBannerTargetRoundTrips() {
        val t = ApprovalNotifyTarget.encode(HERE, "convo-a")
        assertEquals(HERE to "convo-a", ApprovalNotifyTarget.decode(t))
        assertNull(ApprovalNotifyTarget.decode("4f1c2b7e-session-id"), "a turn-finished banner's sessionId is not one")
        assertNull(ApprovalNotifyTarget.decode(null))
    }

    private fun summary(id: String, cwd: String) =
        SessionSummary(id, "same title", firstPrompt = "", messageCount = 1, cwd = cwd, lastModified = 1L)

    @Test
    fun anAskFromASessionTheUserLeftStaysInTheBell() {
        val repo = primary()
        val model = RepoDesktopModel(repo, scope, store = FakeDesktopStore())
        repo.convoId.value = "convo-a"
        repo.receiveForTest(ask("convo-a"))
        assertEquals(1, model.attention.size, "precondition: the open chat's ask is listed")

        // switching sessions drops the focused card queue (clearAskQueue) — the daemon still waits on A
        repo.pendingAsk.value = null
        repo.convoId.value = "convo-b"

        assertEquals(listOf("convo-a"), model.attention.map { it.convoId })
    }

    @Test
    fun anotherComputersApprovalReachesTheBell() {
        val repo = primary()
        val other = PocketRepository(scope, pinnedTo = binding(OTHER))
        val fleet = FleetCoordinator(scope, repo)
        fleet.satellites[OTHER] = other
        FleetRuntime.coordinator = fleet
        val model = RepoDesktopModel(repo, scope, fleet, FakeDesktopStore())

        other.receiveForTest(PendingApprovals(listOf(row("convo-x", "sess-x", "/srv"))))

        assertEquals(listOf(OTHER), model.attention.map { it.accountId })
        assertEquals(1, model.machines.single { it.computer.accountId == OTHER }.pending)
    }

    @Test
    fun approvingFromTheBellSendsOnceAndDropsTheRow() {
        val repo = primary()
        val model = RepoDesktopModel(repo, scope, store = FakeDesktopStore())
        repo.convoId.value = "convo-b"
        repo.receiveForTest(PendingApprovals(listOf(row("convo-a", "sess-a", "/repo-a"))))
        val a = model.attention.single()

        model.resolveAttention(a, allow = true)
        model.resolveAttention(a, allow = true) // a second click on the stale row

        assertEquals(listOf(PermissionVerdict("convo-a", "3", Decision.ALLOW)), sent)
        assertTrue(model.attention.isEmpty(), "the decided row leaves the list: ${model.attention}")
        assertTrue(ApprovalKey("convo-a", "3") !in repo.pendingApprovals)
    }

    private companion object {
        const val HERE = "acct-here"
        const val OTHER = "acct-other"
    }
}
