package dev.ccpocket.app.desktop

import dev.ccpocket.app.data.PocketRepository
import dev.ccpocket.app.data.availableAgentsFromDaemon
import dev.ccpocket.protocol.AGENT_WIRE_DSH
import dev.ccpocket.protocol.AGENT_WIRE_ZCODE
import dev.ccpocket.protocol.AgentKind
import dev.ccpocket.protocol.DAEMON_SUPPORTED_AGENT_WIRES
import dev.ccpocket.protocol.DaemonInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Issue #252: the desktop agent pickers (New session + Settings > Default agent) once read a static
 * desktop-only whitelist, so Kimi — shipped, supported by the daemon, selectable on mobile — was
 * unreachable from the desktop app. Both surfaces now read [DesktopModel.availableAgents], which is
 * the SAME projection mobile uses ([availableAgentsFromDaemon]: the whole enum minus what this daemon
 * can't take). These tests pin that: a new [AgentKind] must show up on desktop without touching any
 * desktop file, and if someone reintroduces a hand-maintained list it turns red here.
 */
class DesktopAgentChoicesTest {

    private fun model(scope: CoroutineScope): Pair<PocketRepository, RepoDesktopModel> {
        val repo = PocketRepository(scope)
        // FakeDesktopStore: never touch the developer's real store file from tests (issue #102)
        return repo to RepoDesktopModel(repo, scope, store = FakeDesktopStore())
    }

    @Test
    fun currentDaemonOffersEveryAgentKind() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val (repo, model) = model(scope)
            repo.receiveForTest(DaemonInfo(supportedAgents = DAEMON_SUPPORTED_AGENT_WIRES))

            assertEquals(AgentKind.entries, model.availableAgents)
            assertTrue(AgentKind.KIMI in model.availableAgents, "Kimi must be selectable on desktop")
            // issue #255: DSH was added to the enum with NO desktop file touched — this is the assertion
            // that proves the #252 projection really is the single source of desktop candidates.
            assertTrue(AgentKind.DSH in model.availableAgents, "DeepSeek Harness must be selectable on desktop")
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun seedAndPreviewModelsAlsoCoverTheWholeEnum() {
        // The interface default backs previews/screenshots; a whitelist there would hide agents too.
        assertEquals(AgentKind.entries, SeedDesktopModel().availableAgents)
    }

    @Test
    fun choicesAreExactlyTheSharedProjectionNotADesktopList() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val (repo, model) = model(scope)

            // Old daemon: nothing advertised — deny-by-omission drops the post-baseline ZCode and DSH.
            assertEquals(availableAgentsFromDaemon(emptySet()), model.availableAgents)
            assertEquals(model.availableAgents, repo.availableAgents, "desktop must not diverge from mobile")
            assertTrue(AgentKind.KIMI in model.availableAgents, "Kimi predates the advertisement")
            assertTrue(AgentKind.ZCODE !in model.availableAgents)
            assertTrue(AgentKind.DSH !in model.availableAgents, "dsh is post-baseline: hidden until advertised")

            repo.receiveForTest(DaemonInfo(supportedAgents = listOf(AGENT_WIRE_ZCODE)))

            assertEquals(availableAgentsFromDaemon(setOf(AGENT_WIRE_ZCODE)), model.availableAgents)
            assertEquals(model.availableAgents, repo.availableAgents, "desktop must not diverge from mobile")
            assertTrue(AgentKind.ZCODE in model.availableAgents)
            // each post-baseline agent is gated INDEPENDENTLY — advertising ZCode must not smuggle in DSH
            assertTrue(AgentKind.DSH !in model.availableAgents)

            repo.receiveForTest(DaemonInfo(supportedAgents = listOf(AGENT_WIRE_ZCODE, AGENT_WIRE_DSH)))
            assertTrue(AgentKind.DSH in model.availableAgents)
        } finally {
            scope.cancel()
        }
    }

    /**
     * Issue #333: the desktop new-session ladder must be the SAME ladder mobile shows.
     *
     * It was not. dsh and Kimi got Claude's four rungs here while [agentModeChoices] has given them three
     * since #255 — and the extra rung was not merely cosmetic: "Accept edits" maps to no dsh/Kimi
     * behaviour, so picking it started the session on plain DEFAULT while the label claimed otherwise for
     * the rest of the session's life. Compared rung-for-rung rather than by count, because two ladders of
     * equal length in a different order is the same lie.
     */
    @Test
    fun theDesktopModeLadderMatchesTheSharedOneForEveryAgent() {
        // OPENCODE: the desktop STATES the mode instead of listing it (the popover's warning card), so it
        // has no ladder to compare. CODEX: its rows come from the daemon-advertised preset vocabulary and
        // the two shells order them differently — a pre-existing, deliberate difference that predates #333
        // and is not this change's to settle. Everything else must agree rung for rung.
        val compared = AgentKind.entries - AgentKind.OPENCODE - AgentKind.CODEX
        for (agent in compared) {
            val shared = dev.ccpocket.app.ui.entry.agentModeChoices(agent)
            val desktop = desktopModeChoices(agent)
            assertEquals(
                shared.map { it.mode to it.nativeMode },
                desktop.map { it.mode to it.nativeMode },
                "$agent's desktop ladder diverges from the shared one",
            )
        }
    }

    /**
     * Issue #333 review (HIGH): the popover remembered a positional mode INDEX that was never re-keyed on
     * the agent, while the ladders differ in length and order. "Plan" is index 2 of Claude's four rungs;
     * dsh's ladder is default/plan/bypass, so the same index landed on **Full access** — the dangerous
     * rung, silently armed under a label the user never picked. [carryModeAcrossAgents] carries the
     * MEANING instead, so this asserts the rung, not an offset.
     */
    @Test
    fun switchingAgentKeepsTheChosenRungAndNeverSlidesOntoFullAccess() {
        val plan = CLAUDE_MODES.first { it.mode == dev.ccpocket.protocol.PermissionMode.PLAN }
        for (target in listOf(AgentKind.DSH, AgentKind.KIMI)) {
            val carried = carryModeAcrossAgents(
                previous = plan, agent = target,
                defaultMode = dev.ccpocket.protocol.PermissionMode.DEFAULT, defaultPermissionMode = null,
            )
            assertEquals(dev.ccpocket.protocol.PermissionMode.PLAN, carried.mode, "$target lost the chosen rung")
            assertTrue(
                carried.mode != dev.ccpocket.protocol.PermissionMode.BYPASS_PERMISSIONS,
                "$target slid the selection onto the dangerous rung",
            )
        }
    }

    /** A rung the new backend does NOT have falls back to that backend's default, never to an offset. */
    @Test
    fun aRungTheNewAgentLacksFallsBackToItsOwnDefault() {
        val acceptEdits = CLAUDE_MODES.first { it.mode == dev.ccpocket.protocol.PermissionMode.ACCEPT_EDITS }
        val carried = carryModeAcrossAgents(
            previous = acceptEdits, agent = AgentKind.DSH,
            defaultMode = dev.ccpocket.protocol.PermissionMode.DEFAULT, defaultPermissionMode = null,
        )
        assertEquals(dev.ccpocket.protocol.PermissionMode.DEFAULT, carried.mode)
    }

    /**
     * Claude's native Auto row appears only when the CLI advertises it, which makes Claude's ladder FIVE
     * long — one past the end of dsh's three. The old `getOrElse` swallowed that into a silent plain
     * Default; nothing may now run off the end at all.
     */
    @Test
    fun theAutoRowCannotRunOffTheEndOfAShorterLadder() {
        val auto = CLAUDE_AUTO_MODE
        val carried = carryModeAcrossAgents(
            previous = auto, agent = AgentKind.DSH,
            defaultMode = dev.ccpocket.protocol.PermissionMode.PLAN, defaultPermissionMode = null,
            autoAvailable = true,
        )
        assertTrue(carried in desktopModeChoices(AgentKind.DSH, autoAvailable = true), "resolved off-ladder")
        // dsh has no native Auto, so it falls back to the caller's stated default — Plan here, proving the
        // fallback is the DEFAULT and not merely "index 0".
        assertEquals(dev.ccpocket.protocol.PermissionMode.PLAN, carried.mode)
        assertEquals(null, carried.nativeMode)
    }

    /** Same rung, same agent: carrying must be a no-op rather than a reset to the default. */
    @Test
    fun carryingWithinOneAgentKeepsTheExactRung() {
        val bypass = CLAUDE_MODES.first { it.mode == dev.ccpocket.protocol.PermissionMode.BYPASS_PERMISSIONS }
        assertEquals(
            bypass,
            carryModeAcrossAgents(
                previous = bypass, agent = AgentKind.CLAUDE,
                defaultMode = dev.ccpocket.protocol.PermissionMode.DEFAULT, defaultPermissionMode = null,
            ),
        )
    }

    /** The specific rung that was wrong, named so a regression reads as itself rather than as a diff. */
    @Test
    fun dshAndKimiHaveNoAcceptEditsRungOnDesktop() {
        for (agent in listOf(AgentKind.DSH, AgentKind.KIMI)) {
            assertTrue(
                desktopModeChoices(agent).none { it.mode == dev.ccpocket.protocol.PermissionMode.ACCEPT_EDITS },
                "$agent has no accept-edits equivalent — offering the rung mislabels a DEFAULT session",
            )
        }
    }
}
