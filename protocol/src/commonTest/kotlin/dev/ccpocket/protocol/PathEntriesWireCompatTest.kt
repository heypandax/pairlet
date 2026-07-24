package dev.ccpocket.protocol

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The pre-#176 `pocket/path.entries` shape — proves an OLD app skips the new trailing [PathEntries.roots]. */
@kotlinx.serialization.Serializable
private data class OldPathEntries(
    val workdir: String,
    val subPath: String,
    val entries: List<PathEntry> = emptyList(),
    val truncated: Boolean = false,
    val ok: Boolean = true,
    val error: String? = null,
)

/**
 * Wire compatibility for the filesystem-roots tail on [PathEntries] (issue #176): the round-trip, plus
 * the two mixed-version directions — an old daemon's reply (no `roots` key) decodes on a new app with
 * the harmless empty default (switcher hidden), and a new daemon's reply (extra `roots` key) still
 * decodes on an old app (ignoreUnknownKeys). Browsing a chosen root needs NO new frame at all: it is
 * the existing [ListPathEntries] with the root as its workdir, which every shipped daemon serves.
 */
class PathEntriesWireCompatTest {

    @Test
    fun pathEntries_roundtrips_with_roots() {
        val env = Envelope(
            id = "1", ts = 7,
            body = PathEntries(
                workdir = "~", subPath = "",
                entries = listOf(PathEntry("Projects", true)),
                roots = listOf("C:\\", "D:\\"),
            ),
        )
        val json = PocketJson.encodeToString(env)
        assertTrue("\"roots\":[\"C:\\\\\",\"D:\\\\\"]" in json, json)
        assertEquals(env, PocketJson.decodeFromString<Envelope>(json))
    }

    @Test
    fun an_old_daemons_reply_without_roots_decodes_to_the_empty_default() {
        // exactly what a pre-#176 daemon emits: no `roots` key at all → the new app reads emptyList()
        // and hides the root switcher (today's behavior, manual path stays the off-home escape hatch)
        val old = """{"id":"1","ts":7,"body":{"t":"pocket/path.entries","workdir":"~","subPath":"","entries":[{"name":"Projects","isDir":true}],"truncated":false,"ok":true}}"""
        val decoded = PocketJson.decodeFromString<Envelope>(old).body as PathEntries
        assertEquals(emptyList(), decoded.roots)
        assertEquals("Projects", decoded.entries.single().name)
    }

    @Test
    fun an_old_app_skips_the_new_roots_key() {
        // a NEW daemon's reply decoded by the OLD app's shape: ignoreUnknownKeys drops `roots`
        val newJson = PocketJson.encodeToString(
            Envelope(id = "2", ts = 0, body = PathEntries(workdir = "~", subPath = "", roots = listOf("/"))),
        )
        val oldSide = PocketJson.decodeFromString<OldPathEntries>(
            newJson.substringAfter("\"body\":").removeSuffix("}"),
        )
        assertEquals("~", oldSide.workdir)
        assertTrue(oldSide.ok)
    }
}
