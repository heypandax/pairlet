package dev.ccpocket.daemon.dsh

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
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
    fun `the current selection is reported as a provider-qualified model id`() {
        val options = parse(real)
        assertEquals("deepseek-official/deepseek-v4-flash", options.currentModel)
        assertEquals("high", options.currentEffort)
        assertEquals(
            listOf("deepseek-official/deepseek-v4-flash", "deepseek-official/deepseek-v4-pro"),
            options.models.map { it.id },
        )
        assertEquals("deepseek-v4-flash", options.models.first().name)
        assertEquals("deepseek-official", options.models.first().provider)
    }

    /** The join every model switch depends on: cc-pocket's id → dsh's opaque `["provider","model"]`. */
    @Test
    fun `a model id maps back to the opaque value dsh advertised`() {
        val options = parse(real)
        assertEquals("""["deepseek-official","deepseek-v4-pro"]""", options.modelValue("deepseek-official/deepseek-v4-pro"))
        assertNull(options.modelValue("gpt-5"), "a model dsh does not offer must not be guessed at")
    }

    /** A deployment with one provider may send the leaves un-grouped; the picker must not depend on nesting. */
    @Test
    fun `a flat option list parses too`() {
        val options = parse(
            """[{"id":"model","currentValue":"[\"p\",\"m1\"]","options":[
                 {"value":"[\"p\",\"m1\"]","name":"M1"},{"value":"[\"p\",\"m2\"]","name":"M2"}]}]""",
        )
        assertEquals(listOf("p/m1", "p/m2"), options.models.map { it.id })
        assertEquals("""["p","m2"]""", options.modelValue("p/m2"))
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
        assertEquals("deepseek-official/deepseek-v4-flash", partial.currentModel, "the model axis survives")
        assertEquals(2, partial.models.size)
    }

    @Test
    fun `a missing array leaves the previous catalogue alone`() {
        val full = parse(real)
        assertEquals(full, DshConfigOptions.parse(null, fallback = full))
        assertTrue(DshConfigOptions.EMPTY.isEmpty)
    }

    /** A dsh plugin (Qoder, WorkBuddy, …) adds a provider; two providers may offer the same model name. */
    private val twoProviders = """
        [{"id":"model","type":"select","currentValue":"[\"qoder\",\"deepseek-v4-pro\"]",
          "options":[{"group":"deepseek-official","name":"DeepSeek","options":[
            {"value":"[\"deepseek-official\",\"deepseek-v4-pro\"]","name":"DeepSeek-V4-Pro"},
            {"value":"[\"deepseek-official\",\"deepseek-v4-flash\"]","name":"DeepSeek-V4-Flash"}]},
           {"group":"qoder","name":"Qoder","options":[
            {"value":"[\"qoder\",\"deepseek-v4-pro\"]","name":"DeepSeek-V4-Pro"}]}]}]
    """.trimIndent()

    @Test
    fun `two providers offering the same model name stay two models`() {
        val options = parse(twoProviders)
        assertEquals(
            listOf("deepseek-official/deepseek-v4-pro", "deepseek-official/deepseek-v4-flash", "qoder/deepseek-v4-pro"),
            options.models.map { it.id },
        )
        assertEquals("qoder/deepseek-v4-pro", options.currentModel, "the selection names the provider that runs it")
        assertEquals("""["qoder","deepseek-v4-pro"]""", options.modelValue("qoder/deepseek-v4-pro"))
    }

    @Test
    fun `a pre-provider bare id migrates only when exactly one provider offers it`() {
        val options = parse(twoProviders)
        val unique = assertIs<DshConfigOptions.Resolution.Found>(options.resolve("deepseek-v4-flash"))
        assertEquals("deepseek-official/deepseek-v4-flash", unique.model.id)
        assertTrue(unique.migrated)
        val exact = assertIs<DshConfigOptions.Resolution.Found>(options.resolve("qoder/deepseek-v4-pro"))
        assertTrue(!exact.migrated)
        val ambiguous = assertIs<DshConfigOptions.Resolution.Ambiguous>(options.resolve("deepseek-v4-pro"))
        assertEquals(2, ambiguous.candidates.size)
        assertNull(options.modelValue("deepseek-v4-pro"), "an ambiguous name must never pick a provider")
        assertEquals(DshConfigOptions.Resolution.Missing, options.resolve("workbuddy/deepseek-v4-pro"))
    }

    /** A provider named only by its group (a flat value) is still joined into the id, on both sides. */
    @Test
    fun `a group-only provider names the current selection the same way as the catalogue`() {
        val options = parse(
            """[{"id":"model","currentValue":"m1","options":[{"group":"g","options":[{"value":"m1","name":"M1"}]}]}]""",
        )
        assertEquals(listOf("g/m1"), options.models.map { it.id })
        assertEquals("g/m1", options.currentModel)
    }

    @Test
    fun `the transcript join spells a model like the catalogue does`() {
        assertEquals("deepseek-official/deepseek-v4-pro", DshConfigOptions.qualifiedId("deepseek-official", "deepseek-v4-pro"))
        assertEquals("deepseek-v4-pro", DshConfigOptions.qualifiedId(null, "deepseek-v4-pro"))
        assertEquals("deepseek-v4-pro", DshConfigOptions.qualifiedId(" ", "deepseek-v4-pro"))
    }
}
