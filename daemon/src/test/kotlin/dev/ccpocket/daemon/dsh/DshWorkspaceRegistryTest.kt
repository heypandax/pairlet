package dev.ccpocket.daemon.dsh

import dev.ccpocket.daemon.disk.ProjectPaths
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Registry shape copied from DSH 0.2.0-rc.2 (`~/.dsh/storages/workspace.json`), ids shortened. */
class DshWorkspaceRegistryTest {
    private val project: Path = Files.createTempDirectory("dsh-proj")
    private val other: Path = Files.createTempDirectory("dsh-other")

    private fun registry(extra: String = "", version: Int = 2, archived: String = ""): Path {
        val file = Files.createTempDirectory("dsh-storages").resolve("workspace.json")
        file.writeText(
            """{"unit":{"name":"workspace","version":$version},
               "global":{"initialized":true,"workspaceIds":["ws-1","ws-2"],"archivedSessionIds":[$archived],"pinnedSessionIds":[]$extra},
               "tables":{"workspaces":{
                 "ws-1":{"path":${Json.encodeToString(kotlinx.serialization.serializer<String>(), other.toString())},"title":"other","sessionIds":["session-o"],"createdAt":"2026-10-03T15:38:26.576Z","updatedAt":"2026-10-03T15:39:01.046Z"},
                 "ws-2":{"path":${Json.encodeToString(kotlinx.serialization.serializer<String>(), project.toString())},"title":"proj","sessionIds":["session-a","b"],"createdAt":"2026-10-03T15:38:26.576Z","updatedAt":"2026-10-03T15:39:01.046Z"}}}}""",
        )
        return file
    }

    private fun ids(file: Path, ws: String): List<String> =
        Json.parseToJsonElement(file.readText()).jsonObject.getValue("tables").jsonObject.getValue("workspaces")
            .jsonObject.getValue(ws).jsonObject.getValue("sessionIds").jsonArray.map { it.jsonPrimitive.content }

    @Test
    fun `a new session heads its project's list and nothing else changes`() {
        val file = registry()
        val before = Json.parseToJsonElement(file.readText()).jsonObject
        assertTrue(DshWorkspaceRegistry.adopt("phone-1", project.toString(), file))
        assertEquals(listOf("phone-1", "session-a", "b"), ids(file, "ws-2"))
        assertEquals(listOf("session-o"), ids(file, "ws-1"))
        val after = Json.parseToJsonElement(file.readText()).jsonObject
        assertEquals(before["unit"], after["unit"])
        assertEquals(before["global"], after["global"])
        val ws = after.getValue("tables").jsonObject.getValue("workspaces").jsonObject.getValue("ws-2").jsonObject
        assertEquals("proj", ws.getValue("title").jsonPrimitive.content)
        assertEquals("2026-10-03T15:39:01.046Z", ws.getValue("updatedAt").jsonPrimitive.content)
    }

    @Test
    fun `adopting twice is a no-op`() {
        val file = registry()
        assertTrue(DshWorkspaceRegistry.adopt("phone-1", project.toString(), file))
        val once = file.readText()
        assertFalse(DshWorkspaceRegistry.adopt("phone-1", project.toString(), file))
        assertEquals(once, file.readText())
    }

    @Test
    fun `a directory DSH has no project for is left alone`() {
        val file = registry()
        val before = file.readText()
        assertFalse(DshWorkspaceRegistry.adopt("phone-1", Files.createTempDirectory("dsh-none").toString(), file))
        assertEquals(before, file.readText())
    }

    @Test
    fun `an unknown layout, a pending DSH mutation, an archived session and a missing file are all left alone`() {
        val newer = registry(version = 3)
        assertFalse(DshWorkspaceRegistry.adopt("phone-1", project.toString(), newer))
        val pending = registry(extra = ""","pendingMutation":{"kind":"create","workspaceId":"ws-9"}""")
        assertFalse(DshWorkspaceRegistry.adopt("phone-1", project.toString(), pending))
        val archived = registry(archived = "\"phone-1\"")
        assertFalse(DshWorkspaceRegistry.adopt("phone-1", project.toString(), archived))
        assertFalse(DshWorkspaceRegistry.adopt("phone-1", project.toString(), project.resolve("absent.json")))
        val garbage = Files.createTempFile("dsh-ws", ".json").also { it.writeText("not json") }
        assertFalse(DshWorkspaceRegistry.adopt("phone-1", project.toString(), garbage))
        assertEquals("not json", garbage.readText())
    }

    @Test
    fun `a null pending marker does not block, and a session owned by another project is not moved`() {
        val root = Json.parseToJsonElement(registry(extra = ""","pendingMutation":null""").readText()) as JsonObject
        val key = ProjectPaths.canonicalKey(project.toString())
        assertTrue(DshWorkspaceRegistry.withSession(root, "phone-1", key) != null)
        assertEquals(null, DshWorkspaceRegistry.withSession(root, "session-o", key))
        assertTrue((root.getValue("tables").jsonObject.getValue("workspaces").jsonObject.getValue("ws-2").jsonObject["sessionIds"] as JsonArray).size == 2)
    }
}
