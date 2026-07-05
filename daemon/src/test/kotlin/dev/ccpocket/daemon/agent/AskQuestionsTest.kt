package dev.ccpocket.daemon.agent

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The AskUserQuestion wire helpers: input parsing + updatedInput assembly (shapes verified on CLI 2.1.201). */
class AskQuestionsTest {

    private val input = Json.parseToJsonElement(
        """
        {"questions":[
          {"question":"Which color do you prefer?","header":"Color","multiSelect":false,
           "options":[{"label":"Red","description":"Choose red"},{"label":"Blue","description":"Choose blue"}]},
          {"question":"Which sections?","header":"Sections","multiSelect":true,
           "options":[{"label":"Intro","description":"Opening"},{"label":"Conclusion","description":"Closing"}]}
        ]}
        """,
    ) as JsonObject

    @Test
    fun parses_questions_headers_options_and_multiselect() {
        val qs = AskQuestions.parse(input)!!
        assertEquals(2, qs.size)
        assertEquals("Which color do you prefer?", qs[0].question)
        assertEquals("Color", qs[0].header)
        assertEquals(listOf("Red", "Blue"), qs[0].options.map { it.label })
        assertEquals("Choose red", qs[0].options[0].description)
        assertTrue(qs[1].multiSelect)
    }

    @Test
    fun malformed_or_missing_questions_parse_to_null() {
        assertNull(AskQuestions.parse(null))
        assertNull(AskQuestions.parse(Json.parseToJsonElement("""{"foo":1}""") as JsonObject))
        assertNull(AskQuestions.parse(Json.parseToJsonElement("""{"questions":[]}""") as JsonObject))
        assertNull(AskQuestions.parse(Json.parseToJsonElement("""{"questions":["bogus"]}""") as JsonObject))
    }

    @Test
    fun answeredInput_merges_answers_into_the_original_input() {
        val updated = AskQuestions.answeredInput(input, mapOf("Which color do you prefer?" to "Red"), null)!!
        val obj = Json.parseToJsonElement(updated) as JsonObject
        assertTrue("questions" in obj) // original input preserved — claude requires it alongside the answers
        assertTrue(""""Which color do you prefer?":"Red"""" in updated)
        assertTrue("response" !in obj)
    }

    @Test
    fun answeredInput_carries_freeform_response() {
        val updated = AskQuestions.answeredInput(input, null, "just pick whichever is faster")!!
        assertTrue(""""response":"just pick whichever is faster"""" in updated)
    }

    @Test
    fun answeredInput_is_null_when_nothing_was_answered() {
        assertNull(AskQuestions.answeredInput(input, emptyMap(), null))
        assertNull(AskQuestions.answeredInput(input, mapOf("q" to "  "), "  "))
    }
}
