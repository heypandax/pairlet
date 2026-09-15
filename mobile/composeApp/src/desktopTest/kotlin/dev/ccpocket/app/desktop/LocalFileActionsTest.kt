package dev.ccpocket.app.desktop

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The Changes panel hands `~/…` paths (its display form) to the file-manager verbs; a literal `~`
 *  never exists on disk, which is exactly how "Reveal in Finder" did nothing the first time it shipped. */
class LocalFileActionsTest {
    @Test
    fun tildeExpandsLikeTheLocalityTest() {
        val home = System.getProperty("user.home")
        assertEquals(File(home, "code/x"), LocalFileActions.resolve("~/code/x"))
        assertEquals(File(home), LocalFileActions.resolve("~"))
        assertEquals(File("/tmp/abs"), LocalFileActions.resolve("/tmp/abs"))
        // the button is shown iff canOpen(workdir); the same string must then resolve to a real dir
        assertTrue(TerminalLauncher.canOpen("~") && LocalFileActions.resolve("~").isDirectory)
    }
}
