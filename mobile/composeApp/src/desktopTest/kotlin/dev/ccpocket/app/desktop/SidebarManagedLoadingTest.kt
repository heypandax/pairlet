package dev.ccpocket.app.desktop

import dev.ccpocket.app.data.PocketRepository
import dev.ccpocket.app.pairing.PairedDaemon
import dev.ccpocket.protocol.AgentKind
import dev.ccpocket.protocol.DaemonInfo
import dev.ccpocket.protocol.Frame
import dev.ccpocket.protocol.ListManagedSessions
import dev.ccpocket.protocol.ListSessions
import dev.ccpocket.protocol.ManagedAgentStatus
import dev.ccpocket.protocol.ManagedAvailability
import dev.ccpocket.protocol.ManagedMigrationState
import dev.ccpocket.protocol.ManagedSessionEntry
import dev.ccpocket.protocol.ManagedSessionsState
import dev.ccpocket.protocol.SessionSummary
import dev.ccpocket.protocol.Sessions
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * #360 P1: RECENT snapshots while a managed list is still loading. The listed project's managed agents are held back
 * during that window; a snapshot taken then (switching project, a restart's refill) must not freeze that half-empty list —
 * an empty non-current group is hidden, so the project would drop out of RECENT.
 */
class SidebarManagedLoadingTest {
    private val acct = "acct-l"
    private val alpha = "/w/alpha"
    private val beta = "/w/beta"

    private fun row(id: String, dir: String) = SessionSummary(id, "title-$id", "", 1, dir, 1)

    private val rows = mapOf(
        alpha to listOf(row("a-out", alpha), row("a1", alpha)),
        beta to listOf(row("b-out", beta), row("b1", beta)),
    )
    private val members = mapOf(alpha to listOf("a1"), beta to listOf("b1"))

    private class Live(val repo: PocketRepository, val model: RepoDesktopModel, val pending: MutableList<ListManagedSessions>)

    private fun <T> live(store: DesktopStore, autoAnswer: Boolean, block: (Live) -> T): T {
        val scope = CoroutineScope(Dispatchers.Unconfined)
        try {
            val repo = PocketRepository(scope)
            repo.demoMode.value = true
            repo.paired.value = PairedDaemon(relay = "wss://test", accountId = acct, daemonPub = "pk", deviceId = "dev", credential = "c")
            val pending = mutableListOf<ListManagedSessions>()
            repo.onSendForTest = { f: Frame ->
                when (f) {
                    is ListSessions -> { repo.receiveForTest(Sessions(f.workdir, rows[f.workdir].orEmpty(), groups = emptyList())); throw CancellationException("answered") }
                    is ListManagedSessions -> if (autoAnswer) { repo.receiveForTest(state(f)); throw CancellationException("answered") } else pending += f
                    else -> throw CancellationException("no transport in this test")
                }
            }
            repo.receiveForTest(DaemonInfo(supportsManagedSessions = true, managedAgents = listOf("claude")))
            return block(Live(repo, RepoDesktopModel(repo, scope, store = store), pending))
        } finally {
            scope.cancel()
        }
    }

    private fun state(req: ListManagedSessions) = ManagedSessionsState(
        requestId = req.requestId, workdir = req.workdir, canonicalWorkdir = req.workdir, revision = 1, allAgents = true, complete = true,
        agents = listOf(ManagedAgentStatus(AgentKind.CLAUDE, ManagedMigrationState.READY, scanComplete = true)),
        items = members[req.workdir].orEmpty().map { id -> ManagedSessionEntry(id, AgentKind.CLAUDE, availability = ManagedAvailability.AVAILABLE, summary = row(id, "")) },
    )

    private fun Live.group(path: String) = model.sessionGroups.firstOrNull { it.path == path }

    private fun waitUntil(ms: Long, cond: () -> Boolean): Boolean {
        val end = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < end) { if (cond()) return true; Thread.sleep(20) }
        return cond()
    }

    @Test
    fun leaving_a_project_while_its_list_loads_keeps_its_rows_and_the_late_answer_rewrites_its_snapshot() {
        live(FakeDesktopStore(), autoAnswer = false) { l ->
            l.model.openProject(DkProject(path = beta, name = "beta"))
            assertTrue(l.repo.managedListLoading.value)
            l.model.openProject(DkProject(path = alpha, name = "alpha")) // beta's snapshot is taken while it loads
            val betaRows = l.group(beta)?.sessions?.map { it.sessionId }.orEmpty()
            assertTrue(betaRows.isNotEmpty(), "beta must not be frozen empty (it would vanish from RECENT)")

            l.repo.receiveForTest(state(l.pending.first { it.workdir == beta })) // beta's answer lands after the switch
            assertEquals(listOf("b1"), l.group(beta)?.sessions?.map { it.sessionId }, "the snapshot now shows beta's managed list")

            l.repo.receiveForTest(state(l.pending.first { it.workdir == alpha }))
            assertEquals(listOf("a1"), l.group(alpha)?.sessions?.map { it.sessionId })
        }
    }

    @Test
    fun a_restart_refill_with_slow_managed_answers_keeps_every_restored_project() {
        val store = FakeDesktopStore()
        live(store, autoAnswer = true) { l ->
            l.model.openProject(DkProject(path = alpha, name = "alpha"))
            l.model.openProject(DkProject(path = beta, name = "beta"))
        }
        live(store, autoAnswer = false) { l ->
            // beta is listed last and still loading (its hint says so); alpha was snapshotted DURING its own loading window
            // and must not have been frozen empty — an empty non-current group is hidden, i.e. gone from RECENT
            assertTrue(
                waitUntil(5_000) {
                    l.repo.sessionsDir.value == beta && l.model.sessionGroups.size == 2 && l.group(alpha)?.sessions?.isNotEmpty() == true
                },
                "restored groups: ${l.model.sessionGroups.map { it.path to it.sessions.map { s -> s.sessionId } }}",
            )
            l.pending.toList().forEach { l.repo.receiveForTest(state(it)) }
            assertTrue(waitUntil(2_000) { l.group(alpha)?.sessions?.map { it.sessionId } == listOf("a1") }, "alpha: ${l.group(alpha)?.sessions}")
            assertEquals(listOf("b1"), l.group(beta)?.sessions?.map { it.sessionId })
        }
    }
}
