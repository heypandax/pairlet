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

/** The pre-browser `pocket/path.list` shape — proves an OLD daemon skips [ListPathEntries.filter]. */
@kotlinx.serialization.Serializable
private data class OldListPathEntries(
    val workdir: String,
    val subPath: String = "",
    val limit: Int = 500,
)

/**
 * Wire compatibility for the filesystem-roots tail on [PathEntries] (issue #176): the round-trip, plus
 * the two mixed-version directions — an old daemon's reply (no `roots` key) decodes on a new app with
 * the harmless empty default (switcher hidden), and a new daemon's reply (extra `roots` key) still
 * decodes on an old app (ignoreUnknownKeys). Browsing a chosen root needs NO new frame at all: it is
 * the existing [ListPathEntries] with the root as its workdir, which every shipped daemon serves.
 *
 * The file browser's [ListPathEntries.filter] rides the same request frame on the same terms, and the
 * bottom three tests hold it to them: absent unless asked for (so no shipped daemon sees a single new
 * byte), null when a pre-browser app omits it, and skippable by a pre-browser daemon.
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

    @Test
    fun a_request_without_a_filter_encodes_byte_for_byte_as_it_always_did() {
        // explicitNulls=false keeps the absent filter OFF the wire, so @-completion and the folder
        // picker send a shipped daemon exactly the bytes it has always parsed — the whole basis for
        // calling this trailing field additive.
        val body = PocketJson.encodeToString(Envelope(id = "1", ts = 7, body = ListPathEntries("~", "src", 200)))
            .substringAfter("\"body\":").removeSuffix("}")
        assertEquals("""{"t":"pocket/path.list","workdir":"~","subPath":"src","limit":200}""", body)
        assertTrue("filter" !in body)
    }

    @Test
    fun an_old_apps_request_without_the_filter_key_decodes_to_no_filter() {
        val old = """{"id":"1","ts":7,"body":{"t":"pocket/path.list","workdir":"/home/p","subPath":"","limit":500}}"""
        val decoded = PocketJson.decodeFromString<Envelope>(old).body as ListPathEntries
        assertEquals(null, decoded.filter, "absent must mean the unfiltered listing, never a browser view")
    }

    @Test
    fun an_old_daemon_skips_the_new_filter_key() {
        // a NEW app asking for the browser view, parsed by the OLD daemon's shape: ignoreUnknownKeys
        // drops `filter` and it answers the full listing — degraded (the noise is back), never wrong
        val newJson = PocketJson.encodeToString(
            Envelope(id = "2", ts = 0, body = ListPathEntries("/home/p", filter = PATH_FILTER_SMART)),
        )
        val oldSide = PocketJson.decodeFromString<OldListPathEntries>(
            newJson.substringAfter("\"body\":").removeSuffix("}"),
        )
        assertEquals("/home/p", oldSide.workdir)
        assertEquals(500, oldSide.limit)
    }
}
