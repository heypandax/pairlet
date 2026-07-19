package dev.ccpocket.daemon.opencode

import kotlin.test.Test
import kotlin.test.assertEquals

class ToolNameMapperTest {

    @Test
    fun `bash maps correctly`() {
        assertEquals("Bash", ToolNameMapper.map("bash"))
        assertEquals("Bash", ToolNameMapper.map("BASH"))
        assertEquals("Bash", ToolNameMapper.map("Bash"))
    }

    @Test
    fun `read maps correctly`() {
        assertEquals("Read", ToolNameMapper.map("read"))
    }

    @Test
    fun `write maps correctly`() {
        assertEquals("Write", ToolNameMapper.map("write"))
    }

    @Test
    fun `edit maps correctly`() {
        assertEquals("Edit", ToolNameMapper.map("edit"))
    }

    @Test
    fun `glob maps correctly`() {
        assertEquals("Glob", ToolNameMapper.map("glob"))
    }

    @Test
    fun `grep maps correctly`() {
        assertEquals("Grep", ToolNameMapper.map("grep"))
    }

    @Test
    fun `webfetch maps correctly`() {
        assertEquals("WebFetch", ToolNameMapper.map("webfetch"))
    }

    @Test
    fun `websearch maps correctly`() {
        assertEquals("WebSearch", ToolNameMapper.map("websearch"))
    }

    @Test
    fun `task maps correctly`() {
        assertEquals("Task", ToolNameMapper.map("task"))
    }

    @Test
    fun `unknown tool is uppercased`() {
        assertEquals("Filesystem", ToolNameMapper.map("filesystem"))
        assertEquals("Mcp", ToolNameMapper.map("mcp"))
        assertEquals("Custom_tool", ToolNameMapper.map("Custom_tool"))
    }

    @Test
    fun `all known tools are mapped`() {
        val known = listOf("bash", "read", "write", "edit", "glob", "grep", "webfetch", "websearch", "task")
        for (tool in known) {
            val mapped = ToolNameMapper.map(tool)
            assertEquals(mapped, mapped.replaceFirstChar { it.uppercase() }, "tool=$tool should start with uppercase")
        }
    }
}
