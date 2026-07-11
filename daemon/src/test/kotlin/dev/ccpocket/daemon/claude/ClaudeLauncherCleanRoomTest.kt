package dev.ccpocket.daemon.claude

import dev.ccpocket.daemon.agent.AgentSpec
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The GUEST clean-room launch flags (issue #115): a scoped guest's claude gets no MCP servers, no user
 *  setting source, and no dynamic (memory/env/git) system-prompt sections. */
class ClaudeLauncherCleanRoomTest {

    private fun args(cleanRoom: Boolean) =
        ClaudeLauncher.buildArgs(AgentSpec(Path.of("/w"), cleanRoom = cleanRoom))

    @Test
    fun clean_room_strips_mcp_user_settings_and_dynamic_sections() {
        val a = args(cleanRoom = true)
        assertTrue("--strict-mcp-config" in a, a.toString())
        // the empty MCP config immediately follows --mcp-config
        val mcpIdx = a.indexOf("--mcp-config")
        assertTrue(mcpIdx >= 0 && a[mcpIdx + 1] == """{"mcpServers":{}}""", a.toString())
        // NO setting sources at all (empty) — not the owner's `user` config, and NOT the shared root's own
        // guest-writable/repo-committed project/local .claude/settings.json whose allow-rules/hooks would let
        // the CLI auto-approve tools past the daemon guard (issue #115 crypto review H2). The daemon stays the
        // permission authority via --permission-prompt-tool + --permission-mode.
        val srcIdx = a.indexOf("--setting-sources")
        assertTrue(srcIdx >= 0 && a[srcIdx + 1] == "", a.toString())
        assertTrue("--exclude-dynamic-system-prompt-sections" in a, a.toString())
    }

    @Test
    fun an_ordinary_owner_launch_carries_none_of_the_clean_room_flags() {
        val a = args(cleanRoom = false)
        assertFalse("--strict-mcp-config" in a)
        assertFalse("--mcp-config" in a)
        assertFalse("--setting-sources" in a)
        assertFalse("--exclude-dynamic-system-prompt-sections" in a)
    }

    @Test
    fun clean_room_keeps_the_normal_headless_stream_json_flags() {
        // the guest still runs the same -p stream-json headless session — the clean-room only ADDS restrictions
        val a = args(cleanRoom = true)
        assertEquals("-p", a.first())
        assertTrue("stream-json" in a)
        assertTrue("--permission-mode" in a)
    }
}
