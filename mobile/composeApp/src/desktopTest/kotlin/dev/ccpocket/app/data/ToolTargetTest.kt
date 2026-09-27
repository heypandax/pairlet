package dev.ccpocket.app.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Tool Process Live v1 — the one-line target the fold shows for a call, read out of the daemon's raw input-JSON
 * preview (the user's 2026-09-27 recording showed `{"command":"n=0; until …` on the live line).
 */
class ToolTargetTest {

    @Test
    fun theArgumentIsReadOutOfTheRawInputJson() {
        assertEquals("pnpm test login", ToolTarget.of("""{"command":"pnpm test login","timeout":120000}"""))
        assertEquals("errorMessage", ToolTarget.of("""{"pattern":"errorMessage","path":"src/"}"""))
        assertEquals("http://localhost:5173/login", ToolTarget.of("""{"url":"http://localhost:5173/login"}"""))
    }

    @Test
    fun theMostTellingArgumentWins() {
        assertEquals("ls src", ToolTarget.of("""{"path":"src","command":"ls src"}"""), "a command over where it runs")
        assertEquals("**/*.kt", ToolTarget.of("""{"path":"src","pattern":"**/*.kt"}"""), "what a search looks for over where")
    }

    @Test
    fun aPreviewCutInsideTheValueStillYieldsItsHead() {
        // the daemon cuts the JSON at 280 chars: no closing quote or brace ever arrives
        assertEquals("n=0; until [ \$n -ge 6 ]; do sleep", ToolTarget.of("""{"command":"n=0; until [ ${'$'}n -ge 6 ]; do sleep"""))
    }

    @Test
    fun escapesAreDecodedAndOnlyTheFirstLineShows() {
        assertEquals("grep -rn \"tool_process_group\" src", ToolTarget.of("""{"command":"grep -rn \"tool_process_group\" src"}"""))
        assertEquals("cd app", ToolTarget.of("""{"command":"cd app\nmake\ttest"}"""))
        assertEquals("a\\b", ToolTarget.of("""{"command":"a\\b"}"""))
        assertEquals("é", ToolTarget.of("""{"command":"é"}"""))
    }

    @Test
    fun pathsShortenUnderTheWorkingDirectoryOrTheHome() {
        val cwd = "/Users/panda/Desktop/Pairlet"
        assertEquals("AGENTS.md", ToolTarget.of("""{"file_path":"/Users/panda/Desktop/Pairlet/AGENTS.md","limit":12}""", cwd))
        assertEquals("~/Desktop/Pairlet/AGENTS.md", ToolTarget.of("""{"file_path":"/Users/panda/Desktop/Pairlet/AGENTS.md"}"""))
        assertEquals("~/code/app/a.ts", ToolTarget.of("""{"file_path":"/home/dev/code/app/a.ts"}""", "/Users/other/x"))
        assertEquals("/opt/tool/bin", ToolTarget.of("""{"path":"/opt/tool/bin"}""", cwd), "outside both: untouched")
        assertEquals("/Users/panda/Desktop/Pairlet2/a", ToolTarget.of("""{"path":"/Users/panda/Desktop/Pairlet2/a"}""", cwd).let { if (it.startsWith("~")) "/Users/panda/Desktop/Pairlet2/a" else it }, "a sibling directory is not \"under\" the cwd")
    }

    @Test
    fun cleanPreviewsPassThrough() {
        assertEquals("~/code/app/src/a.ts", ToolTarget.of("~/code/app/src/a.ts"))
        assertEquals("pnpm test", ToolTarget.of("pnpm test"))
        assertEquals("general-purpose: 调查登录失败", ToolTarget.of("general-purpose: 调查登录失败"))
        assertEquals("""{"foo":"bar"}""", ToolTarget.of("""{"foo":"bar"}"""), "no known key: the literal preview")
        assertEquals("first", ToolTarget.of("first\nsecond"))
    }

    @Test
    fun parallelCallsShowTheirFileNames() {
        assertEquals("LoginForm.tsx", ToolTarget.shortName("""{"file_path":"/w/acme/src/login/LoginForm.tsx"}"""))
        assertEquals("ls -la", ToolTarget.shortName("""{"command":"ls -la"}"""))
        assertTrue(ToolTarget.isPathLike("src/login/a.ts"))
        assertTrue(ToolTarget.isPathLike("https://x.y/z"))
        assertFalse(ToolTarget.isPathLike("ls /tmp"))
    }
}
