package dev.ccpocket.daemon.dsh

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * The DeepSeek Harness ACP session's advertised configuration — the `configOptions` array that
 * `session/new`, `session/resume` and `session/set_config_option` all answer with
 * (the dsh 0.1.2 ACP switch).
 *
 * Pure translation, no IO, so every rule here is unit-testable against real frames.
 *
 * ```
 *  {id:"model", type:"select", currentValue:"[\"deepseek-official\",\"deepseek-v4-flash\"]",
 *   options:[{group:"deepseek-official", name:"DeepSeek",
 *             options:[{value:"[\"deepseek-official\",\"deepseek-v4-pro\"]", name:"DeepSeek-V4-Pro",
 *                       description:"…"}, …]}]}
 *  {id:"reasoning_effort", type:"select", currentValue:"high",
 *   options:[{value:"off", name:"Off", description:"…"}, {value:"low", …}, …]}
 * ```
 *
 * ## The one trap: a model's VALUE is not its id
 *
 * dsh's model values are opaque `["<provider>","<model>"]` JSON strings, and `set_config_option` rejects
 * anything else (`unknown model option`, probe 0.1.2-rc.1). cc-pocket, meanwhile, shows the user a plain
 * model id (`deepseek-v4-pro`) and persists that in session preferences and the model chip. So the id is
 * the wire currency everywhere in cc-pocket and the opaque value is joined back HERE, out of the same
 * catalogue that advertised it — never string-built, because the provider half is dsh's to choose.
 */
data class DshConfigOptions(
    /** The selected model's bare id, e.g. `deepseek-v4-pro`. Null when dsh advertised none. */
    val currentModel: String?,
    /** The selected reasoning effort (`off` / `low` / `high` / `max` on 0.1.2-rc.1). */
    val currentEffort: String?,
    val models: List<Model>,
    val efforts: List<Effort>,
) {
    /** One selectable model. [value] is dsh's opaque wire value; [id] is what cc-pocket shows and stores. */
    data class Model(
        val id: String,
        val value: String,
        val provider: String?,
        val displayName: String?,
        val description: String?,
    )

    data class Effort(val id: String, val displayName: String?, val description: String?)

    /** The opaque value to send for a bare model [id], or null when dsh does not offer that model. */
    fun modelValue(id: String): String? = models.firstOrNull { it.id == id }?.value

    /** The efforts dsh advertises, as bare ids. Empty = it advertised none (do not invent a ladder). */
    fun effortIds(): Set<String> = efforts.map { it.id }.toSet()

    val isEmpty: Boolean get() = models.isEmpty() && efforts.isEmpty() && currentModel == null

    companion object {
        /** dsh's own option ids — the `configId` a write must name (NOT `optionId`, which zod rejects). */
        const val MODEL = "model"
        const val EFFORT = "reasoning_effort"

        val EMPTY = DshConfigOptions(null, null, emptyList(), emptyList())

        private val json = Json { ignoreUnknownKeys = true; isLenient = true }

        /**
         * Parse one `configOptions` array. [fallback] survives whatever this frame does not mention: a
         * `set_config_option` answer is a complete state today, but a future dsh answering with only the
         * option it changed must not blank the other axis out of the header.
         */
        fun parse(array: JsonArray?, fallback: DshConfigOptions = EMPTY): DshConfigOptions {
            if (array == null) return fallback
            val entries = array.mapNotNull { it as? JsonObject }
            val model = entries.firstOrNull { it.str("id") == MODEL }
            val effort = entries.firstOrNull { it.str("id") == EFFORT }
            val models = model?.let { flattenModels(it.arr("options")) } ?: fallback.models
            val efforts = effort?.let { flattenEfforts(it.arr("options")) } ?: fallback.efforts
            return DshConfigOptions(
                currentModel = model?.str("currentValue")?.let(::modelIdOf) ?: fallback.currentModel,
                currentEffort = effort?.str("currentValue") ?: fallback.currentEffort,
                models = models,
                efforts = efforts,
            )
        }

        /**
         * `["<provider>","<model>"]` → `<model>`.
         *
         * A value that is not that shape is returned VERBATIM rather than dropped: an unfamiliar encoding
         * still names a real selection, and showing it beats showing nothing while the join below keeps
         * working (it matches on whatever this returned, on both sides).
         */
        fun modelIdOf(value: String): String {
            val parsed = runCatching { json.parseToJsonElement(value) as? JsonArray }.getOrNull()
                ?: return value
            val last = parsed.lastOrNull() as? JsonPrimitive ?: return value
            return last.contentOrNull?.takeIf { it.isNotBlank() } ?: value
        }

        /** The provider half of the same pair, when it has one. */
        private fun providerOf(value: String): String? {
            val parsed = runCatching { json.parseToJsonElement(value) as? JsonArray }.getOrNull() ?: return null
            if (parsed.size < 2) return null
            return (parsed.first() as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
        }

        /** dsh groups models by provider, but a deployment with one provider may send a flat list —
         *  accept both rather than betting the picker on the nesting. */
        private fun flattenModels(options: JsonArray?): List<Model> {
            val out = LinkedHashMap<String, Model>() // by id: two providers offering the same id collapse
            fun leaf(entry: JsonObject, group: String?) {
                val value = entry.str("value") ?: return
                val id = modelIdOf(value)
                out.putIfAbsent(
                    id,
                    Model(
                        id = id,
                        value = value,
                        provider = providerOf(value) ?: group,
                        displayName = entry.str("name"),
                        description = entry.str("description"),
                    ),
                )
            }
            options.orEmpty().mapNotNull { it as? JsonObject }.forEach { entry ->
                val nested = entry.arr("options")
                if (nested != null) {
                    nested.mapNotNull { it as? JsonObject }.forEach { leaf(it, entry.str("group")) }
                } else {
                    leaf(entry, null)
                }
            }
            return out.values.toList()
        }

        private fun flattenEfforts(options: JsonArray?): List<Effort> =
            options.orEmpty().mapNotNull { it as? JsonObject }.mapNotNull { entry ->
                entry.str("value")?.let { Effort(it, entry.str("name"), entry.str("description")) }
            }
    }
}
