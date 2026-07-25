package dev.ccpocket.protocol

import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The pre-#188 directory shape, proving a shipped app skips the additive sessionAgents field. */
@Serializable
private data class OldDirectoryEntry(
    val path: String,
    val name: String,
    val isDir: Boolean,
    val hasSessions: Boolean = false,
)

/**
 * Wire compatibility for project-level agent provenance (issue #188): a new app + old daemon keeps
 * unknown projects visible via the empty default, while an old app ignores the populated trailing key.
 */
class DirectoryEntryAgentsWireCompatTest {

    @Test
    fun directory_entry_roundtrips_all_history_agents() {
        val entry = DirectoryEntry(
            path = "/p", name = "p", isDir = true, hasSessions = true,
            sessionAgents = listOf(AgentKind.CLAUDE, AgentKind.OPENCODE),
        )
        val json = PocketJson.encodeToString(entry)
        assertTrue("\"sessionAgents\":[\"claude\",\"opencode\"]" in json, json)
        assertEquals(entry, PocketJson.decodeFromString<DirectoryEntry>(json))
    }

    @Test
    fun an_old_daemons_entry_decodes_to_unknown_history_agents() {
        val old = """{"path":"/p","name":"p","isDir":true,"hasSessions":true}"""
        assertEquals(emptyList(), PocketJson.decodeFromString<DirectoryEntry>(old).sessionAgents)
    }

    @Test
    fun an_old_app_skips_the_new_history_agents_key() {
        val newJson = PocketJson.encodeToString(
            DirectoryEntry(
                path = "/p", name = "p", isDir = true, hasSessions = true,
                sessionAgents = listOf(AgentKind.CODEX),
            ),
        )
        val oldSide = PocketJson.decodeFromString<OldDirectoryEntry>(newJson)
        assertEquals("/p", oldSide.path)
        assertTrue(oldSide.hasSessions)
    }
}
