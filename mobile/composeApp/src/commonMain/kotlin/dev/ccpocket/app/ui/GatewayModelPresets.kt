package dev.ccpocket.app.ui

/**
 * Built-in model-id presets for third-party gateway users (issue #139): people who route claude
 * through an Anthropic-compatible endpoint (cc-switch, one-line proxies, the vendors' own
 * `/anthropic` endpoints) pick model ids no fixed Claude alias list can know. These rows give the
 * common vendor ids one tap in the model picker — the free-form custom field stays for everything
 * else.
 *
 * SINGLE source of truth for both shells (mobile [ModelPicker] + desktop QuickActionsPopover).
 * Ids are the vendors' mainstream stable names; actual routing depends on the user's gateway —
 * each vendor's Claude-compatible endpoint decides what a given id maps to, so keep entries to
 * ids the vendors themselves publish (never invent one; when unsure, leave it out).
 */
data class GatewayModelPreset(
    /** Display vendor name ("DeepSeek", "GLM", …). */
    val vendor: String,
    /** The id passed to `--model` verbatim. */
    val id: String,
    /** Lowercase substrings matched against the gateway base URL to rank this vendor's rows first
     *  (e.g. "deepseek" matches api.deepseek.com). Empty = never auto-recommended by host. */
    val hostHints: List<String> = emptyList(),
)

val GATEWAY_MODEL_PRESETS: List<GatewayModelPreset> = listOf(
    GatewayModelPreset("DeepSeek", "deepseek-chat", listOf("deepseek")),
    GatewayModelPreset("DeepSeek", "deepseek-reasoner", listOf("deepseek")),
    GatewayModelPreset("GLM", "glm-4.6", listOf("bigmodel", "zhipu", "z.ai")),
    GatewayModelPreset("Kimi", "kimi-k2", listOf("moonshot", "kimi")),
    GatewayModelPreset("Qwen", "qwen3-coder", listOf("dashscope", "qwen", "aliyun")),
    GatewayModelPreset("MiniMax", "MiniMax-M2", listOf("minimax")),
)

/** Compact display host of a gateway base URL: scheme/path stripped, port kept
 *  ("http://127.0.0.1:3456/api" → "127.0.0.1:3456"). Null/blank → null. */
fun gatewayHostLabel(baseUrl: String?): String? = baseUrl?.trim()
    ?.substringAfter("://")
    ?.substringBefore('/')
    ?.takeIf { it.isNotBlank() }

/** The preset rows ordered for [gatewayBaseUrl]: rows whose vendor the URL names come first (a
 *  DeepSeek endpoint lists deepseek-* on top), everything else keeps table order. A null/unmatched
 *  URL returns the table as-is — an aggregator gateway routes any vendor. */
fun recommendedGatewayPresets(gatewayBaseUrl: String?): List<GatewayModelPreset> {
    val url = gatewayBaseUrl?.lowercase() ?: return GATEWAY_MODEL_PRESETS
    return GATEWAY_MODEL_PRESETS.sortedBy { p -> if (p.hostHints.any { it in url }) 0 else 1 }
}
