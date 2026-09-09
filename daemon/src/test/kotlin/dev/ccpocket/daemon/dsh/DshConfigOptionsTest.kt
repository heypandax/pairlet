package dev.ccpocket.daemon.dsh

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The `configOptions` translation (the dsh 0.1.2 ACP switch). The shapes are copied verbatim out of
 * `scripts/probe-dsh-acp.py`'s log against dsh 0.1.2-rc.1 — the join tested here is what stands between
 * a model the user picked and `-32602 unknown model option`.
 */
class DshConfigOptionsTest {

    private fun parse(json: String) =
        DshConfigOptions.parse(Json.parseToJsonElement(json) as JsonArray)

    private val real = """
        [{"id":"model","name":"Model","category":"model","type":"select",
          "currentValue":"[\"deepseek-official\",\"deepseek-v4-flash\"]",
          "options":[{"group":"deepseek-official","name":"DeepSeek","options":[
            {"value":"[\"deepseek-official\",\"deepseek-v4-flash\"]","name":"DeepSeek-V4-Flash",
             "description":"Fast, efficient, and economical."},
            {"value":"[\"deepseek-official\",\"deepseek-v4-pro\"]","name":"DeepSeek-V4-Pro"}]}]},
         {"id":"reasoning_effort","name":"Reasoning effort","category":"thought_level","type":"select",
          "currentValue":"high",
          "options":[{"value":"off","name":"Off"},{"value":"low","name":"Low"},
                     {"value":"high","name":"High"},{"value":"max","name":"Max"}]}]
    """.trimIndent()

    @Test
    fun `the current selection is reported as a bare model id`() {
        val options = parse(real)
        assertEquals("deepseek-v4-flash", options.currentModel)
        assertEquals("high", options.currentEffort)
        assertEquals(listOf("deepseek-v4-flash", "deepseek-v4-pro"), options.models.map { it.id })
        assertEquals(setOf("off", "low", "high", "max"), options.effortIds())
        assertEquals("deepseek-official", options.models.first().provider)
    }

    /** The join every model switch depends on: cc-pocket's id → dsh's opaque `["provider","model"]`. */
    @Test
    fun `a model id maps back to the opaque value dsh advertised`() {
        val options = parse(real)
        assertEquals("""["deepseek-official","deepseek-v4-pro"]""", options.modelValue("deepseek-v4-pro"))
        assertNull(options.modelValue("gpt-5"), "a model dsh does not offer must not be guessed at")
    }

    /** A deployment with one provider may send the leaves un-grouped; the picker must not depend on nesting. */
    @Test
    fun `a flat option list parses too`() {
        val options = parse(
            """[{"id":"model","currentValue":"[\"p\",\"m1\"]","options":[
                 {"value":"[\"p\",\"m1\"]","name":"M1"},{"value":"[\"p\",\"m2\"]","name":"M2"}]}]""",
        )
        assertEquals(listOf("m1", "m2"), options.models.map { it.id })
        assertEquals("""["p","m2"]""", options.modelValue("m2"))
    }

    /** An encoding we do not recognize still names a real selection — showing it beats showing nothing,
     *  and the join keeps working because both sides go through the same normalization. */
    @Test
    fun `a non-pair value survives verbatim on both sides`() {
        val options = parse("""[{"id":"model","currentValue":"plain-model","options":[
                                 {"value":"plain-model","name":"Plain"}]}]""")
        assertEquals("plain-model", options.currentModel)
        assertEquals("plain-model", options.modelValue("plain-model"))
    }

    /** A future dsh answering a write with only the option it changed must not blank the other axis. */
    @Test
    fun `a partial answer keeps what it does not mention`() {
        val full = parse(real)
        val partial = DshConfigOptions.parse(
            Json.parseToJsonElement(
                """[{"id":"reasoning_effort","currentValue":"max","options":[{"value":"max","name":"Max"}]}]""",
            ) as JsonArray,
            fallback = full,
        )
        assertEquals("max", partial.currentEffort)
        assertEquals("deepseek-v4-flash", partial.currentModel, "the model axis survives")
        assertEquals(2, partial.models.size)
    }

    @Test
    fun `a missing array leaves the previous catalogue alone`() {
        val full = parse(real)
        assertEquals(full, DshConfigOptions.parse(null, fallback = full))
        assertTrue(DshConfigOptions.EMPTY.isEmpty)
    }
}
