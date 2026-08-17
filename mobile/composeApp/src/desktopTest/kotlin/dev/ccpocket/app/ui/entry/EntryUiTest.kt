package dev.ccpocket.app.ui.entry

import dev.ccpocket.app.data.ConnPhase
import dev.ccpocket.app.ui.CODEX_PRESETS
import dev.ccpocket.app.ui.session.StateMark
import dev.ccpocket.app.ui.session.StateTone
import dev.ccpocket.protocol.AgentKind
import dev.ccpocket.protocol.CLAUDE_PERMISSION_MODE_AUTO
import dev.ccpocket.protocol.PermissionMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The entry flow's product truth, pinned without a composition (Entry Flow UI 2.0 · Direction v1 §"Six
 * phases, six sentences, six action sets" and §"Configuration").
 *
 * These are the rules a visual refactor is most likely to quietly break: a Retry that cannot work, a
 * recovery that throws away the content it was still showing, or a mode selection that survives a switch to
 * a backend which does not implement it.
 */
class EntryUiTest {

    // ══ where a session-less root lands ════════════════════════════════════════════════════════════

    /**
     * Issue #278 batch 2 moved the front door: a first run opens on the install guide, because the pairing
     * screen was asking for six digits that no computer was printing yet — the largest single drop-off in
     * the activation funnel. The three cases have to stay separable, since two of them look identical from
     * inside the pairing screen (no session, and a code field on screen).
     */
    @Test
    fun aFirstRunLandsOnTheGuideAndAnExistingUserNeverDoes() {
        assertEquals(
            EntryLanding.FIRST_RUN_CONNECT,
            entryLanding(hasBindings = false, addingDevice = false),
            "nothing paired and nothing asked for: the install guide is the front door",
        )
        assertEquals(
            EntryLanding.COMPUTERS,
            entryLanding(hasBindings = true, addingDevice = false),
            "a returning user goes to their computers, not through onboarding again",
        )
        // "add a computer" is an EXISTING user: they have installed the daemon at least once, so replaying
        // the install guide at them is a step backwards. It wins over the binding count in both directions.
        assertEquals(
            EntryLanding.PAIR,
            entryLanding(hasBindings = true, addingDevice = true),
            "adding a second computer goes straight to pairing",
        )
        assertEquals(
            EntryLanding.PAIR,
            entryLanding(hasBindings = false, addingDevice = true),
            "an add-device request with no binding left still means pairing, never the guide",
        )
    }

    /**
     * A pure recipient (SESSION-HANDOFF.md §10) owns collaborator links and NO binding, so by binding count
     * alone they are indistinguishable from a first run — and they are the one audience for whom "install
     * the daemon on your computer" is the wrong instruction entirely. The pairing screen carries the line
     * that explains their actual state; the guide does not.
     */
    @Test
    fun aPureRecipientIsNotTreatedAsAFirstRun() {
        assertEquals(
            EntryLanding.PAIR,
            entryLanding(hasBindings = false, addingDevice = false, hasCollaboratorLinks = true),
        )
        assertNotEquals(
            EntryLanding.FIRST_RUN_CONNECT,
            entryLanding(hasBindings = false, addingDevice = false, hasCollaboratorLinks = true),
            "a recipient must never be sent through daemon onboarding",
        )
        // …and an owner who ALSO holds collaborator links still goes to their own computers
        assertEquals(
            EntryLanding.COMPUTERS,
            entryLanding(hasBindings = true, addingDevice = false, hasCollaboratorLinks = true),
        )
    }

    // ══ the six connection phases ══════════════════════════════════════════════════════════════════

    @Test
    fun everyPhaseKeepsItsOwnActionsAndRetention() {
        // Connecting is a FIRST attempt: a skeleton, and no recovery action — there is nothing to recover
        connRecovery(ConnPhase.Connecting).let {
            assertEquals(emptyList(), it.actions, "a first attempt offers no recovery yet")
            assertTrue(it.showsSkeleton, "Connecting renders the Projects-shaped skeleton")
            assertFalse(it.retainsContent, "there is no previous content on a first attempt")
            assertFalse(it.blocks)
        }
        // Reconnecting was ready and dropped: the list it already had stays on screen
        connRecovery(ConnPhase.Reconnecting).let {
            assertEquals(emptyList(), it.actions)
            assertTrue(it.retainsContent, "a dropped-but-was-ready link keeps the last known content")
            assertFalse(it.showsSkeleton, "…so it must not fall back to a skeleton")
        }
        // Relay unreachable: Retry can genuinely help, Exit leaves the binding
        connRecovery(ConnPhase.RelayUnreachable).let {
            assertEquals(listOf(ConnActionId.RETRY, ConnActionId.EXIT), it.actions)
            assertFalse(it.hasDaemonHint, "the relay failing says nothing about the daemon")
            assertTrue(it.blocks)
        }
        // Computer offline: same actions, plus the existing "we'll reconnect when it's back" hint
        connRecovery(ConnPhase.ComputerOffline).let {
            assertEquals(listOf(ConnActionId.RETRY, ConnActionId.EXIT), it.actions)
            assertTrue(it.hasDaemonHint, "the daemon hint is the one thing only this phase can say")
        }
        // Pairing invalid: retrying replays the same rejected credential, so it is NOT offered
        connRecovery(ConnPhase.PairingInvalid).let {
            assertEquals(listOf(ConnActionId.PAIR_AGAIN, ConnActionId.REMOVE), it.actions)
            assertFalse(ConnActionId.RETRY in it.actions, "Retry cannot fix a rejected credential")
            assertTrue(it.blocks)
        }
        // Ready owns the screen with the real content and no recovery chrome at all
        connRecovery(ConnPhase.Ready).let {
            assertEquals(emptyList(), it.actions)
            assertTrue(it.retainsContent)
            assertFalse(it.blocks)
        }
    }

    @Test
    fun theThreeFailuresAreNeverCollapsedIntoOneGenericError() {
        val relay = connRecovery(ConnPhase.RelayUnreachable)
        val offline = connRecovery(ConnPhase.ComputerOffline)
        val invalid = connRecovery(ConnPhase.PairingInvalid)
        // each keeps its own identity: the actions or the mark differ, so the surfaces cannot merge
        assertNotEquals(relay.actions, invalid.actions)
        assertNotEquals(relay.hasDaemonHint, offline.hasDaemonHint)
        listOf(relay, offline, invalid).forEach {
            assertEquals(StateMark.SQUARE, it.mark, "terminal failures use the master-design failure mark")
            assertEquals(StateTone.DANGER, it.tone)
        }
    }

    @Test
    fun stateIsCarriedByShapeNotOnlyByColour() {
        // every phase's mark is meaningful in greyscale: connecting ring, reconnecting diamond,
        // live dot, and terminal failure square
        val marks = ConnPhase.entries.associateWith { connRecovery(it).mark }
        assertEquals(StateMark.RING, marks.getValue(ConnPhase.Connecting))
        assertEquals(StateMark.DIAMOND, marks.getValue(ConnPhase.Reconnecting))
        assertEquals(StateMark.DOT, marks.getValue(ConnPhase.Ready))
        assertEquals(StateMark.SQUARE, marks.getValue(ConnPhase.RelayUnreachable))
        assertEquals(StateMark.SQUARE, marks.getValue(ConnPhase.ComputerOffline))
        assertEquals(StateMark.SQUARE, marks.getValue(ConnPhase.PairingInvalid))
    }

    // ══ new-session configuration ══════════════════════════════════════════════════════════════════

    @Test
    fun codexModesStayLockedToTheRealPresetTable() {
        // the sheet renders CODEX_PRESETS' names against these modes — if the two lists drift, a row would
        // print "Balanced" while the daemon receives another approval × sandbox pair
        assertEquals(
            CODEX_PRESETS.map { it.mode },
            agentModeChoices(AgentKind.CODEX).map { it.mode },
            "the Codex ladder is CODEX_PRESETS, in its order",
        )
        assertEquals(
            CODEX_PRESETS.first { it.recommended }.mode,
            agentDefaultMode(AgentKind.CODEX),
            "switching to Codex lands on its own recommended preset",
        )
        assertEquals(
            CODEX_PRESETS.filter { it.danger }.map { it.mode },
            agentModeChoices(AgentKind.CODEX).filter { it.danger }.map { it.mode },
            "danger is the preset table's verdict, not a second opinion",
        )
    }

    @Test
    fun eachAgentOffersOnlyTheModesItReallyImplements() {
        // Claude: the full ladder, and native Auto ONLY when the connected CLI advertises it
        assertNull(
            agentModeChoices(AgentKind.CLAUDE, autoAvailable = false).firstOrNull { it.nativeMode != null },
            "Auto is a capability, never an assumption",
        )
        assertEquals(
            CLAUDE_PERMISSION_MODE_AUTO,
            agentModeChoices(AgentKind.CLAUDE, autoAvailable = true).last().nativeMode,
        )
        // Kimi has no accept-edits equivalent — offering one would promise a rung that does not exist
        assertFalse(
            agentModeChoices(AgentKind.KIMI).any { it.mode == PermissionMode.ACCEPT_EDITS },
            "Kimi has no Accept edits",
        )
        assertEquals(
            listOf(
                PermissionMode.DEFAULT,
                PermissionMode.ACCEPT_EDITS,
                PermissionMode.PLAN,
                PermissionMode.BYPASS_PERMISSIONS,
            ),
            agentModeChoices(AgentKind.ZCODE).map { it.mode },
            "ZCode build/edit/plan/yolo map onto the existing shared ladder",
        )
        // OpenCode is not a ladder with rows disabled: it is one statement of what already happens
        val opencode = agentModeChoices(AgentKind.OPENCODE)
        assertEquals(1, opencode.size, "OpenCode has no ladder to choose from")
        assertEquals(ModeChoiceSet.OPENCODE_AUTOMATIC, modeChoiceSet(AgentKind.OPENCODE))
        assertEquals(
            PermissionMode.BYPASS_PERMISSIONS, agentDefaultMode(AgentKind.OPENCODE),
            "OpenCode resets to the mode it actually runs in",
        )
        assertFalse(
            opencode.single().needsFullAccessConfirm(AgentKind.OPENCODE),
            "OpenCode is honest backend behaviour, not a hazardous choice being confirmed",
        )
    }

    @Test
    fun onlyTheOpeningAgentInheritsThePersistedDefault() {
        // opened on Claude/Plan → Claude keeps Plan…
        val kept = seedModeChoice(
            agent = AgentKind.CLAUDE, openedAgent = AgentKind.CLAUDE,
            persisted = PermissionMode.PLAN, persistedNative = null,
        )
        assertEquals(PermissionMode.PLAN, kept.mode)
        // …but SWITCHING to Codex resets to Codex's own default, rather than reinterpreting "Plan" as
        // Codex's Cautious sandbox preset behind the user's back
        val switched = seedModeChoice(
            agent = AgentKind.CODEX, openedAgent = AgentKind.CLAUDE,
            persisted = PermissionMode.PLAN, persistedNative = null,
        )
        assertEquals(agentDefaultMode(AgentKind.CODEX), switched.mode, "a switch resets the mode")
        // …and a switch never carries a native Claude mode onto a backend that has none
        val toKimi = seedModeChoice(
            agent = AgentKind.KIMI, openedAgent = AgentKind.CLAUDE,
            persisted = PermissionMode.DEFAULT, persistedNative = CLAUDE_PERMISSION_MODE_AUTO,
            autoAvailable = true,
        )
        assertNull(toKimi.nativeMode, "Claude's native Auto is not a Kimi mode")
    }

    @Test
    fun fullAccessAlwaysRoutesThroughTheConfirmation() {
        for (agent in listOf(AgentKind.CLAUDE, AgentKind.CODEX, AgentKind.KIMI, AgentKind.ZCODE)) {
            val full = agentModeChoices(agent, autoAvailable = true)
                .single { it.mode == PermissionMode.BYPASS_PERMISSIONS }
            assertTrue(full.needsFullAccessConfirm(agent), "$agent full access must confirm before it starts")
        }
        // …and nothing else does
        val safe = agentModeChoices(AgentKind.CLAUDE).filterNot { it.mode == PermissionMode.BYPASS_PERMISSIONS }
        assertTrue(
            safe.none { it.needsFullAccessConfirm(AgentKind.CLAUDE) },
            "an ordinary mode must not borrow the danger gate",
        )
    }

    @Test
    fun everyAgentHasExactlyOneModeChoiceSet() {
        // the six supported agents, and nothing invented alongside them
        assertEquals(
            setOf(
                AgentKind.CLAUDE, AgentKind.CODEX, AgentKind.OPENCODE,
                AgentKind.KIMI, AgentKind.ZCODE, AgentKind.DSH,
            ),
            AgentKind.entries.toSet(),
        )
        AgentKind.entries.forEach { a ->
            assertTrue(agentModeChoices(a).isNotEmpty(), "$a must resolve to at least one real mode")
            assertTrue(
                agentModeChoices(a).any { it.mode == agentDefaultMode(a) },
                "$a's default must be one of the modes it offers",
            )
        }
    }
}
