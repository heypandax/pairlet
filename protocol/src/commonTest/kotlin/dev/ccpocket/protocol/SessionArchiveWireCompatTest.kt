package dev.ccpocket.protocol

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The pre-#202 `pocket/sessions` body shape — proves an already-shipped app skips `archiveSupported`
 *  AND the populated `items` array beside it (the structured-unknown-key path that has bitten before). */
@Serializable
private data class PreArchiveSessions(
    val workdir: String,
    val items: List<SessionSummary> = emptyList(),
    val renameSupported: Boolean = false,
)

/**
 * Wire compatibility for the session archive (issue #202). The daemon and the app ship on independent
 * schedules, so all four directions matter — and this feature has an asymmetry worth pinning: the daemon
 * filters archived rows out of [Sessions] for EVERY client, so an old app gets the tidied list with no way
 * to restore from within itself. That is a deliberate call (an updated client on the same account restores),
 * and the test below records it so it can't become an accident.
 */
class SessionArchiveWireCompatTest {

    @Test
    fun setSessionArchived_roundtrips_and_defaults_its_view_flag() {
        val env = Envelope(id = "ar1", ts = 0, body = SetSessionArchived("/x", "sid-1", archived = true))
        val json = PocketJson.encodeToString(env)
        assertTrue("\"t\":\"pocket/session.archive\"" in json, json)
        assertEquals(env, PocketJson.decodeFromString<Envelope>(json))

        // fromArchiveView defaults false and, being a default, still rides the wire (encodeDefaults)
        val body = PocketJson.decodeFromString<Envelope>(json).body as SetSessionArchived
        assertFalse(body.fromArchiveView)

        val fromView = Envelope(id = "ar2", ts = 0, body = SetSessionArchived("/x", "sid-1", false, fromArchiveView = true))
        assertEquals(fromView, PocketJson.decodeFromString<Envelope>(PocketJson.encodeToString(fromView)))
    }

    @Test
    fun listArchivedSessions_and_its_reply_roundtrip() {
        val req = Envelope(id = "ar3", ts = 0, body = ListArchivedSessions)
        val reqJson = PocketJson.encodeToString(req)
        assertTrue("\"t\":\"pocket/sessions.archived.list\"" in reqJson, reqJson)
        assertEquals(req, PocketJson.decodeFromString<Envelope>(reqJson))

        val rows = listOf(
            SessionSummary("s1", "Auth refactor", "fix login", 4, "/Users/x/a", 100),
            SessionSummary("s2", "Docs", "write docs", 2, "/Users/x/b", 90),
        )
        val reply = Envelope(id = "ar4", ts = 0, body = ArchivedSessions(rows))
        val replyJson = PocketJson.encodeToString(reply)
        assertTrue("\"t\":\"pocket/sessions.archived\"" in replyJson, replyJson)
        assertEquals(reply, PocketJson.decodeFromString<Envelope>(replyJson))

        // an empty archive is a real answer, not an omission
        val empty = Envelope(id = "ar5", ts = 0, body = ArchivedSessions())
        assertTrue((PocketJson.decodeFromString<Envelope>(PocketJson.encodeToString(empty)).body as ArchivedSessions).items.isEmpty())
    }

    @Test
    fun sessions_archiveSupported_is_a_trailing_optional() {
        // OLD daemon → new app: no key ⇒ false ⇒ the client hides every archive affordance rather than
        // firing frames that daemon would silently drop.
        val legacy = """{"workdir":"/x","items":[]}"""
        val old = PocketJson.decodeFromString<Sessions>(legacy)
        assertFalse(old.archiveSupported)
        assertFalse(old.renameSupported)

        val stamped = Sessions("/x", emptyList(), archiveSupported = true)
        assertEquals(stamped, PocketJson.decodeFromString<Sessions>(PocketJson.encodeToString(stamped)))
    }

    @Test
    fun old_app_skips_archiveSupported_and_still_reads_the_filtered_list() {
        // NEW daemon → OLD app, as a structured unknown-key skip: the old schema lacks archiveSupported but
        // must still decode the frame AND its populated items array. Note what it receives — the archived
        // row is ALREADY gone, because the daemon filtered it. That is the deliberate asymmetry: an old app
        // gets the tidy list for free and restores from an updated client.
        val newFrame = """{"workdir":"/x","items":[{"sessionId":"s1","title":"T","firstPrompt":"p","messageCount":1,"cwd":"/x","lastModified":5}],"groups":[],"renameSupported":true,"archiveSupported":true}"""
        val back = PocketJson.decodeFromString<PreArchiveSessions>(newFrame)
        assertEquals("/x", back.workdir)
        assertEquals(listOf("s1"), back.items.map { it.sessionId })
        assertTrue(back.renameSupported)
    }

    @Test
    fun an_archived_row_carries_its_own_project_so_the_view_can_group_without_a_second_index() {
        // the archive spans projects; cwd is the only grouping key that exists in the reply
        val rows = listOf(
            SessionSummary("s1", "A", "p", 1, "/Users/x/proj-a", 10),
            SessionSummary("s2", "B", "p", 1, "/Users/x/proj-b", 20),
        )
        val back = PocketJson.decodeFromString<Envelope>(
            PocketJson.encodeToString(Envelope(id = "ar6", ts = 0, body = ArchivedSessions(rows))),
        ).body as ArchivedSessions
        assertEquals(listOf("/Users/x/proj-a", "/Users/x/proj-b"), back.items.map { it.cwd })
    }
}
