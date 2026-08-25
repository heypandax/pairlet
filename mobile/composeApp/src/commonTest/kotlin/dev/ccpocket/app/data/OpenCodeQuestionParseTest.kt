package dev.ccpocket.app.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** issue #210: OpenCode's `question` tool preview → read-only question card model, tolerant + safe. */
class OpenCodeQuestionParseTest {

    // the canonical shape a new daemon sends (untruncated): {questions:[{question,header,multiple,options:[{label,description}]}]}
    private val canonical = """
        {"questions":[{"question":"Which database should we use for the cache layer?","header":"Cache DB",
        "multiple":false,"options":[
        {"label":"Redis","description":"In-memory, fastest"},
        {"label":"SQLite","description":"Embedded, zero-ops"}]}]}
    """.trimIndent()

    @Test
    fun parses_canonical_question_shape() {
        val qs = OpenCodeQuestionParse.parse("Question", canonical)!!
        assertEquals(1, qs.size)
        val q = qs.single()
        assertEquals("Which database should we use for the cache layer?", q.question)
        assertEquals("Cache DB", q.header)
        assertEquals(false, q.multiSelect)
        assertEquals(listOf("Redis", "SQLite"), q.options.map { it.label })
        assertEquals("In-memory, fastest", q.options.first().description)
    }

    @Test
    fun only_the_question_tool_name_is_recognized() {
        assertNull(OpenCodeQuestionParse.parse("Bash", canonical))
        assertNull(OpenCodeQuestionParse.parse("Read", canonical))
    }

    @Test
    fun tolerates_pascalcased_keys_from_an_old_daemon() {
        // a pre-#210 daemon PascalCases the top-level key via its generic tool-input mapping
        val old = """{"Questions":[{"question":"Proceed?","options":[{"label":"Yes"},{"label":"No"}]}]}"""
        val qs = OpenCodeQuestionParse.parse("Question", old)!!
        assertEquals("Proceed?", qs.single().question)
        assertEquals(listOf("Yes", "No"), qs.single().options.map { it.label })
    }

    @Test
    fun multi_select_flag_is_read_from_multiple() {
        val m = """{"questions":[{"question":"Pick any","multiple":true,"options":[{"label":"A"},{"label":"B"}]}]}"""
        assertTrue(OpenCodeQuestionParse.parse("Question", m)!!.single().multiSelect)
    }

    @Test
    fun truncated_or_malformed_preview_degrades_to_null() {
        // an old daemon's 280-char cap can slice the JSON mid-array → must fall back to the plain row
        val truncated = canonical.substring(0, 90)
        assertNull(OpenCodeQuestionParse.parse("Question", truncated))
        assertNull(OpenCodeQuestionParse.parse("Question", "not json at all"))
        assertNull(OpenCodeQuestionParse.parse("Question", null))
        // a well-formed object that isn't a question payload
        assertNull(OpenCodeQuestionParse.parse("Question", """{"command":"ls"}"""))
    }
}
