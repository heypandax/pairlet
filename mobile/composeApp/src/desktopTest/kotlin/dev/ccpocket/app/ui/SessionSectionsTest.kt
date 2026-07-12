package dev.ccpocket.app.ui

import dev.ccpocket.protocol.SessionGroup
import dev.ccpocket.protocol.SessionSummary
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Locks the group-folding math for the mobile session list (issue #119): the flat degrade, ordering,
 *  the ungrouped bucket, empty-group retention, and stale-id fallout. */
class SessionSectionsTest {

    private fun s(id: String, group: String? = null) =
        SessionSummary(sessionId = id, title = id, firstPrompt = "", messageCount = 0, cwd = "/p", lastModified = 0L, group = group)

    private fun g(id: String, order: Int) = SessionGroup(id = id, name = "grp-$id", order = order)

    @Test
    fun no_groups_degrades_to_one_headerless_flat_section() {
        val rows = listOf(s("a"), s("b", group = "stale"))
        val sections = sessionSections(rows, emptyList())
        assertEquals(1, sections.size)
        assertNull(sections[0].group) // null group → the caller renders no header
        assertEquals(rows, sections[0].sessions) // every row present, order preserved
    }

    @Test
    fun groups_render_in_order_with_trailing_ungrouped_bucket() {
        val groups = listOf(g("g2", 1), g("g1", 0)) // deliberately out of order
        val rows = listOf(s("x", "g1"), s("y", "g2"), s("z", null))
        val sections = sessionSections(rows, groups)
        assertEquals(listOf("g1", "g2", null), sections.map { it.group?.id }) // sorted by order, ungrouped last
        assertEquals(listOf("x"), sections[0].sessions.map { it.sessionId })
        assertEquals(listOf("y"), sections[1].sessions.map { it.sessionId })
        assertEquals(listOf("z"), sections[2].sessions.map { it.sessionId }) // the ungrouped tail
    }

    @Test
    fun empty_group_is_kept_but_empty_ungrouped_is_dropped() {
        val groups = listOf(g("g1", 0), g("g2", 1))
        val rows = listOf(s("x", "g1")) // g2 empty, nothing ungrouped
        val sections = sessionSections(rows, groups)
        assertEquals(listOf("g1", "g2"), sections.map { it.group?.id }) // both groups stay, no ungrouped section
        assertTrue(sections[1].sessions.isEmpty()) // freshly-created empty group still visible/manageable
    }

    @Test
    fun row_with_unknown_group_id_falls_into_ungrouped() {
        val groups = listOf(g("g1", 0))
        val rows = listOf(s("x", "g1"), s("orphan", "deleted-id"))
        val sections = sessionSections(rows, groups)
        assertEquals(listOf("g1", null), sections.map { it.group?.id })
        assertEquals(listOf("orphan"), sections[1].sessions.map { it.sessionId }) // never vanishes
    }
}
