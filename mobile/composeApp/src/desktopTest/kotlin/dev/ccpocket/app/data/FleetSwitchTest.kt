package dev.ccpocket.app.data

import dev.ccpocket.app.pairing.PairedDaemon
import dev.ccpocket.app.pairing.Pairing
import dev.ccpocket.app.theme.ThemeMode
import dev.ccpocket.protocol.DirectoryEntry
import dev.ccpocket.protocol.PermissionAsk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * [FleetCoordinator.switchTo] — the hot-satellite promote (issue #103). These tests build the fleet by
 * hand (repos never dial: satellites are constructed pre-loaded instead of startRelay()'d, and the
 * coordinator's collectors are never started), so every assertion is about the SWAP itself: identity
 * reuse (the strongest "no re-handshake" proof — the promoted object IS the satellite that was already
 * connected), the single-link invariant (the outgoing primary re-keys into the satellite map within the
 * same call), shell-state adoption, and the demote quiesce.
 */
class FleetSwitchTest {

    private lateinit var scope: CoroutineScope
    private var savedActive: String? = null

    // switchTo persists the active account exactly like a cold switch does; the desktop test store is the
    // developer's real ~/.cc-pocket-app — snapshot and restore so running tests never re-points the app
    @BeforeTest
    fun setUp() {
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        savedActive = Pairing.activeAccount()
    }

    @AfterTest
    fun tearDown() {
        Pairing.setActive(savedActive)
        scope.cancel() // kills any cold-path dial/retry a fallback test kicked off
    }

    private fun binding(id: String, label: String? = null) = PairedDaemon(
        // a closed loopback port: the cold-fallback tests fail their dial instantly instead of hanging
        relay = "wss://127.0.0.1:9", accountId = id, daemonPub = "pk-$id", deviceId = "dev", credential = "c-$id",
        label = label,
    )

    private fun dir(path: String) = DirectoryEntry(path = path, name = path.substringAfterLast('/'), isDir = true)

    /** A connected-looking primary parked on [active] with [all] bound (fleet mode on, never dialed). */
    private fun primary(active: PairedDaemon, all: List<PairedDaemon>) = PocketRepository(scope).apply {
        paired.value = active
        pairedList.clear(); pairedList.addAll(all)
        sessionActive.value = true
        phase.value = ConnPhase.Ready
        directories.add(dir("/home/a/proj"))
        directoriesLoaded.value = true
    }

    /** A READY satellite for [target]: live link state + preloaded projects, exactly what sync() maintains. */
    private fun hotSatellite(target: PairedDaemon) = PocketRepository(scope, pinnedTo = target).apply {
        sessionActive.value = true
        phase.value = ConnPhase.Ready
        directories.add(dir("/home/b/work"))
        directoriesLoaded.value = true
    }

    private fun fleet(primary: PocketRepository) = FleetCoordinator(scope, primary).apply {
        promoteHotSatellites = true
        primary.onBeforeSwitch = ::retireSatellite // what start() wires, without starting the collectors
    }

    @Test
    fun promoteReusesTheHotSatelliteWithoutDialing() {
        val a = binding("acct-a"); val b = binding("acct-b")
        val pri = primary(a, listOf(a, b))
        val sat = hotSatellite(b)
        val fleet = fleet(pri)
        fleet.satellites["acct-b"] = sat

        fleet.switchTo(b)

        assertSame(sat, fleet.primary, "the hot satellite object itself becomes the primary — no new link")
        assertEquals("acct-b", fleet.primary.paired.value?.accountId)
        assertEquals(ConnPhase.Ready, fleet.primary.phase.value, "promoted link is Ready at once — that's the win")
        assertTrue(fleet.primary.directoriesLoaded.value && fleet.primary.directories.isNotEmpty(), "preloaded projects survive")
        assertNull(fleet.satellites["acct-b"], "the promoted link left the satellite set — one owner per link")
        assertSame(pri, fleet.satellites["acct-a"], "the outgoing primary is its machine's satellite, link kept")
        assertTrue(pri.sessionActive.value, "demote never tears the old link down")
        assertEquals("acct-b", Pairing.activeAccount(), "promote persists the active account like a cold switch")
    }

    @Test
    fun fastSwitchBackPromotesTheDemotedPrimaryAgain() {
        val a = binding("acct-a"); val b = binding("acct-b")
        val pri = primary(a, listOf(a, b))
        val fleet = fleet(pri)
        fleet.satellites["acct-b"] = hotSatellite(b)

        fleet.switchTo(b)
        val demoted = fleet.satellites["acct-a"]!!
        fleet.switchTo(a) // straight back — must reuse the just-demoted repo, not dial

        assertSame(pri, fleet.primary, "A→B→A round-trip lands on the original object — both hops hot")
        assertSame(demoted, fleet.primary)
        assertEquals("acct-a", fleet.primary.paired.value?.accountId)
        assertTrue(fleet.satellites["acct-b"]!!.sessionActive.value, "B's link is parked hot again")
    }

    @Test
    fun demoteClosesTheChatViewButKeepsMachineState() {
        val a = binding("acct-a"); val b = binding("acct-b")
        val pri = primary(a, listOf(a, b)).apply {
            convoId.value = "c1"
            chatTitle.value = "Fix the build"
            workdir.value = "/home/a/proj"
            sessionsDir.value = "/home/a/proj"
            messages.add(ChatItem.Assistant("hello"))
            pendingAsk.value = PermissionAsk("c1", "ask1", "Bash", "rm -rf build")
            slashCommands.add(dev.ccpocket.protocol.SlashCommand("compact", ""))
        }
        val fleet = fleet(pri)
        fleet.satellites["acct-b"] = hotSatellite(b)

        fleet.switchTo(b)

        // chat-scoped state cleared exactly like a cold switch (disconnect) would…
        assertNull(pri.convoId.value); assertNull(pri.chatTitle.value)
        assertNull(pri.workdir.value); assertNull(pri.sessionsDir.value)
        assertTrue(pri.messages.isEmpty()); assertNull(pri.pendingAsk.value)
        assertTrue(pri.slashCommands.isEmpty())
        // …but the machine-scoped state that makes the link worth keeping hot survives
        assertTrue(pri.sessionActive.value, "link stays up")
        assertTrue(pri.directoriesLoaded.value && pri.directories.isNotEmpty(), "its project list keeps the fleet rows lit")
    }

    @Test
    fun promoteAdoptsShellPrefsAndTheFreshPairingList() {
        val a = binding("acct-a"); val b = binding("acct-b")
        val sat = hotSatellite(b) // constructed BEFORE the user tweaks settings / renames machines
        val pri = primary(a, listOf(a, binding("acct-b", label = "Studio"))).apply {
            themeMode.value = ThemeMode.LIGHT // in-memory only — tests must not write the dev store
            defaultModel.value = "claude-fable-5"
            fontScale.value = 1.2f
        }
        val fleet = fleet(pri)
        fleet.satellites["acct-b"] = sat

        fleet.switchTo(b)

        assertEquals(ThemeMode.LIGHT, sat.themeMode.value, "a theme picked after app start survives the switch")
        assertEquals("claude-fable-5", sat.defaultModel.value)
        assertEquals(1.2f, sat.fontScale.value)
        assertEquals("Studio", sat.pairedList.first { it.accountId == "acct-b" }.label, "renames ride along")
        assertEquals("Studio", sat.paired.value?.label, "its own binding copy is freshened too")
    }

    @Test
    fun noHotSatelliteFallsBackToTheColdSwitch() {
        val a = binding("acct-a"); val b = binding("acct-b")
        val pri = primary(a, listOf(a, b))
        val fleet = fleet(pri) // satellites empty — e.g. the fleet only just came up

        fleet.switchTo(b)

        assertSame(pri, fleet.primary, "no swap happened — same repo, re-pointed")
        assertEquals("acct-b", pri.paired.value?.accountId)
        assertTrue(pri.sessionActive.value, "switchDaemon re-dialed (the dial itself fails fast in tests)")
    }

    @Test
    fun promoteDisabledKeepsTodaysRetireAndRedialPath() {
        val a = binding("acct-a"); val b = binding("acct-b")
        val pri = primary(a, listOf(a, b))
        val sat = hotSatellite(b)
        val fleet = fleet(pri).apply { promoteHotSatellites = false } // the mobile shell's configuration
        fleet.satellites["acct-b"] = sat

        fleet.switchTo(b)

        assertSame(pri, fleet.primary, "primary identity never changes with promotion off")
        assertEquals("acct-b", pri.paired.value?.accountId)
        assertNull(fleet.satellites["acct-b"], "the target's satellite was retired before the dial (invariant)")
        assertFalse(sat.sessionActive.value, "retired = disconnected, exactly today's behavior")
    }

    @Test
    fun switchingAgainMidColdSwitchParksTheDialingRepoAsThatMachinesSatellite() {
        val a = binding("acct-a"); val b = binding("acct-b"); val c = binding("acct-c")
        val pri = primary(a, listOf(a, b, c))
        val satB = hotSatellite(b)
        val fleet = fleet(pri)
        fleet.satellites["acct-b"] = satB

        fleet.switchTo(c) // no satellite for C → cold: pri re-points and starts dialing C
        assertSame(pri, fleet.primary)
        assertEquals("acct-c", pri.paired.value?.accountId)

        fleet.switchTo(b) // impatient user switches again mid-dial — B is hot, promote

        assertSame(satB, fleet.primary, "the second switch promotes instead of waiting out C's dial")
        assertSame(pri, fleet.satellites["acct-c"], "the mid-dial repo keeps dialing as C's satellite — nothing wasted")
        assertNull(fleet.satellites["acct-b"])
    }

    @Test
    fun offlineTargetStillPromotesAndShowsItsHonestPhase() {
        val a = binding("acct-a"); val b = binding("acct-b")
        val pri = primary(a, listOf(a, b))
        val sat = hotSatellite(b).apply { phase.value = ConnPhase.ComputerOffline } // daemon down, link retrying
        val fleet = fleet(pri)
        fleet.satellites["acct-b"] = sat

        fleet.switchTo(b)

        assertSame(sat, fleet.primary, "an un-Ready satellite still promotes — its reconnect loop IS the cold path's endgame")
        assertEquals(ConnPhase.ComputerOffline, fleet.primary.phase.value, "the UI shows the truth instantly instead of a fake Connecting")
        assertSame(pri, fleet.satellites["acct-a"])
    }

    @Test
    fun switchingToTheCurrentMachineIsANoop() {
        val a = binding("acct-a"); val b = binding("acct-b")
        val pri = primary(a, listOf(a, b))
        val sat = hotSatellite(b)
        val fleet = fleet(pri)
        fleet.satellites["acct-b"] = sat

        fleet.switchTo(a)

        assertSame(pri, fleet.primary)
        assertSame(sat, fleet.satellites["acct-b"], "nothing retired, nothing swapped")
        assertEquals("acct-a", pri.paired.value?.accountId)
    }
}
