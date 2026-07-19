package dev.ccpocket.app.desktop

import dev.ccpocket.app.data.PocketRepository
import dev.ccpocket.app.pairing.PairedDaemon
import dev.ccpocket.protocol.DirectoryEntry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Issue #163 "Open Folder…" — the seam worth testing is the SPLIT, not the dialog: a folder the daemon
 * already lists has history and opens as a project; anything else seeds the new-session popover so the
 * user still chooses agent + permission mode. The AWT/Swing chooser itself can't be driven from a
 * headless test and is on the manual pass instead.
 */
class OpenFolderTest {

    private fun model(dirs: List<String>): Pair<PocketRepository, RepoDesktopModel> {
        val scope = CoroutineScope(Dispatchers.Unconfined)
        val repo = PocketRepository(scope)
        repo.paired.value = PairedDaemon(
            relay = "wss://test", accountId = "acct-test", daemonPub = "pk", deviceId = "dev", credential = "cred",
        )
        repo.directories.clear()
        dirs.forEach { repo.directories.add(DirectoryEntry(path = it, name = it.substringAfterLast('/'), isDir = true, hasSessions = true)) }
        return repo to RepoDesktopModel(repo, scope, store = FakeDesktopStore())
    }

    @Test
    fun aFolderWithHistoryOpensAsThatProject() {
        val (_, m) = model(listOf("/Users/x/code/alpha"))
        m.openFolderPath("/Users/x/code/alpha")
        assertEquals("/Users/x/code/alpha", m.newSessionDir, "should have entered the existing project")
        assertTrue(!m.showNewSession, "an existing project must not detour through the new-session popover")
    }

    @Test
    fun aFolderWithNoHistorySeedsTheNewSessionPopover() {
        val (_, m) = model(listOf("/Users/x/code/alpha"))
        m.openFolderPath("/Users/x/code/beta")
        assertTrue(m.showNewSession, "an unknown folder must let the user pick agent + mode")
        assertEquals("/Users/x/code/beta", m.newSessionSeed, "seed must be the ABSOLUTE path picked from disk")
    }

    /** A trailing separator comes free from some file choosers; it must not turn a known project into
     *  an unknown one and silently route to the popover instead of opening the project. */
    @Test
    fun aTrailingSeparatorStillMatchesTheKnownProject() {
        val (_, m) = model(listOf("/Users/x/code/alpha"))
        m.openFolderPath("/Users/x/code/alpha/")
        assertEquals("/Users/x/code/alpha", m.newSessionDir)
        assertTrue(!m.showNewSession)
    }

    @Test
    fun aBlankPathDoesNothing() {
        val (_, m) = model(listOf("/Users/x/code/alpha"))
        m.openFolderPath("   ")
        assertTrue(!m.showNewSession)
        assertNull(m.newSessionSeed)
    }

    /**
     * The remote-machine guard: with no daemon reporting a hostname that matches this box, `thisMachine`
     * is false everywhere, so `activeIsThisMachine` must be false — a local Finder panel can only browse
     * local disk, and offering it for a remote daemon would list folders that daemon cannot open.
     */
    @Test
    fun anUnidentifiedMachineIsNotTreatedAsLocal() {
        val (_, m) = model(emptyList())
        assertTrue(!m.activeIsThisMachine, "unknown host must fall back to typing a path, which always works")
    }
}
