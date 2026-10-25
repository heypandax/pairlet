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
 * anything else (`unknown model option`, probe 0.1.2-rc.1). cc-pocket shows and persists a readable id
 * instead, and the opaque value is joined back HERE, out of the same catalogue that advertised it — never
 * string-built, because the provider half is dsh's to choose.
 *
 * ## The id always names the provider
 *
 * That readable id is `<provider>/<model>` (`deepseek-official/deepseek-v4-pro`), the same shape ZCode and
 * OpenCode already use — never the bare model name alone. dsh plugins add providers (a Qoder or WorkBuddy
 * route bills a different account), and two providers may offer the same model name: an id without the
 * provider half could not say which one a session runs on or which one the user picked, and the old
 * bare-name catalogue silently collapsed the duplicates onto whichever came first. The id is never PARSED
 * back into its halves (a model name may itself contain `/`); it is only ever looked up in [models].
 *
 * A pre-provider id (a bare `deepseek-v4-pro` a phone saved before this change) is accepted by [resolve]
 * only when exactly ONE advertised model carries that name — see [Resolution].
 */
data class DshConfigOptions(
    /** The selected model's id, e.g. `deepseek-official/deepseek-v4-pro`. Null when dsh advertised none. */
    val currentModel: String?,
    /** The selected reasoning effort (`off` / `low` / `high` / `max` on 0.1.2-rc.1). */
    val currentEffort: String?,
    val models: List<Model>,
    val efforts: List<Effort>,
) {
    /** One selectable model. [value] is dsh's opaque wire value; [id] is what cc-pocket shows and stores;
     *  [name] is the bare model name, kept only to recognise a pre-provider id ([resolve]). */
    data class Model(
        val id: String,
        val value: String,
        val name: String,
        val provider: String?,
        val displayName: String?,
        val description: String?,
    )

    /** What a requested model id means against THIS catalogue. */
    sealed interface Resolution {
        /** Exactly one advertised model. [migrated] = it was matched by a pre-provider bare name. */
        data class Found(val model: Model, val migrated: Boolean = false) : Resolution

        /** Nothing advertised under that id or name. */
        data object Missing : Resolution

        /** A bare name several providers offer: picking one would be guessing which account pays. */
        data class Ambiguous(val candidates: List<Model>) : Resolution
    }

    data class Effort(val id: String, val displayName: String?, val description: String?)

    /**
     * Look [requested] up: an exact id first, then — for an id saved before ids named their provider — the
     * bare model name, accepted only when it is unique. Never falls back to anything else.
     */
    fun resolve(requested: String): Resolution {
        models.firstOrNull { it.id == requested }?.let { return Resolution.Found(it) }
        val byName = models.filter { it.name == requested }
        return when (byName.size) {
            0 -> Resolution.Missing
            1 -> Resolution.Found(byName.single(), migrated = true)
            else -> Resolution.Ambiguous(byName)
        }
    }

    /** The opaque value to send for [id], or null when it does not resolve to exactly one model. */
    fun modelValue(id: String): String? = (resolve(id) as? Resolution.Found)?.model?.value

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
                // the advertised row's id when there is one, so the current selection and the catalogue always
                // spell the same model the same way (a group-only provider included)
                currentModel = model?.str("currentValue")
                    ?.let { value -> models.firstOrNull { it.value == value }?.id ?: modelIdOf(value) }
                    ?: fallback.currentModel,
                currentEffort = effort?.str("currentValue") ?: fallback.currentEffort,
                models = models,
                efforts = efforts,
            )
        }

        /**
         * `["<provider>","<model>"]` → `<provider>/<model>`; a pair without a usable provider → `<model>`.
         *
         * A value that is not that shape is returned VERBATIM rather than dropped: an unfamiliar encoding
         * still names a real selection, and showing it beats showing nothing while the join below keeps
         * working (it matches on whatever this returned, on both sides).
         */
        fun modelIdOf(value: String): String {
            val name = modelNameOf(value)
            val provider = providerOf(value) ?: return name
            return qualifiedId(provider, name)
        }

        /** The wire id for one (provider, model) pair — the ONE place the two halves are joined, shared with
         *  the transcript reader so a resumed session names its model exactly as the live catalogue does. */
        fun qualifiedId(provider: String?, model: String): String =
            provider?.takeIf { it.isNotBlank() }?.let { "$it/$model" } ?: model

        /** `["<provider>","<model>"]` → `<model>`; anything else verbatim. */
        private fun modelNameOf(value: String): String {
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
            // Keyed by the provider-qualified id, so two providers offering the same model name stay two rows.
            val out = LinkedHashMap<String, Model>()
            fun leaf(entry: JsonObject, group: String?) {
                val value = entry.str("value") ?: return
                val name = modelNameOf(value)
                val provider = providerOf(value) ?: group
                val id = qualifiedId(provider, name)
                out.putIfAbsent(
                    id,
                    Model(
                        id = id,
                        value = value,
                        name = name,
                        provider = provider,
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
