package dev.ccpocket.app.ui

import androidx.compose.ui.graphics.Color
import dev.ccpocket.app.theme.Tok

/**
 * Built-in model-id presets for third-party gateway users (issue #139): people who route claude
 * through an Anthropic-compatible endpoint (cc-switch, one-line proxies, the vendors' own
 * `/anthropic` endpoints) pick model ids no fixed Claude alias list can know. These rows give the
 * common vendor ids one tap in the model picker — the free-form custom field stays for everything
 * else.
 *
 * SINGLE source of truth for both shells (mobile [ModelPicker] + desktop ModelPopover).
 * Ids are the vendors' mainstream stable names; actual routing depends on the user's gateway —
 * each vendor's Claude-compatible endpoint decides what a given id maps to, so keep entries to
 * ids the vendors themselves publish (never invent one; when unsure, leave it out).
 */
data class GatewayModelPreset(
    /** Display vendor name ("DeepSeek", "GLM", …). */
    val vendor: String,
    /** The id passed to `--model` verbatim. */
    val id: String,
    /** Two-letter avatar monogram (0714 design) — a tinted lettermark, never a vendor logo. */
    val monogram: String,
    /** Semantic tint the monogram is drawn in; see [GatewayTint] for the palette discipline. */
    val tint: GatewayTint,
    /** Lowercase substrings matched against the gateway base URL to rank this vendor's rows first
     *  (e.g. "deepseek" matches api.deepseek.com). Empty = never auto-recommended by host. */
    val hostHints: List<String> = emptyList(),
)

/**
 * Monogram tints, drawn from the app's existing semantic palette (0714 design): BLUE = `Tok.info`,
 * AMBER = `Tok.warn`, TEAL = `Tok.codex`; VIOLET/PINK have no semantic token, so they carry their
 * own dark/light pair in the same key as the palette. Terracotta (`Tok.accent`) is deliberately
 * absent — it stays reserved for the selected state and the "suggested" tick.
 */
enum class GatewayTint { BLUE, VIOLET, AMBER, TEAL, PINK }

/** Resolve a [GatewayTint] against the live palette (reads [Tok], so composition retints on theme flips). */
fun GatewayTint.color(): Color = when (this) {
    GatewayTint.BLUE -> Tok.info
    GatewayTint.AMBER -> Tok.warn
    GatewayTint.TEAL -> Tok.codex
    GatewayTint.VIOLET -> if (Tok.current.dark) Color(0xFF9B8CD9) else Color(0xFF7A63BE)
    GatewayTint.PINK -> if (Tok.current.dark) Color(0xFFD982A8) else Color(0xFFB25579)
}

/**
 * Audited 2026-07-19 (issue #168), every id re-checked against the vendor's own docs that day.
 * These ids ROT, and silently — a wrong one only fails at the USER's gateway, so we get zero signal:
 * `kimi-k2` sat here dead for two months (discontinued 2026-05-25) before anyone noticed, and
 * `deepseek-chat`/`deepseek-reasoner` expire 2026-07-24 15:59 UTC.
 *
 * The bar is "the vendor's current mainstream id", not merely "not yet retired": a two-generations-old
 * id still answers, so nothing looks broken, while every user who taps the row quietly gets a worse
 * model than they asked for. That is why `glm-4.6` → `glm-4.7` and `MiniMax-M2` → `MiniMax-M2.5`
 * moved here even though both old ids were still being served.
 *
 * Re-audit whenever a vendor ships a generation. The structural fix — stop hand-maintaining ids and
 * let the gateway answer for itself — is issue #167, whose step ① already demoted this table below
 * the Claude aliases; until the rest lands, this comment plus `GatewayModelPresetsTest` are the only
 * tripwires.
 */
val GATEWAY_MODEL_PRESETS: List<GatewayModelPreset> = listOf(
    GatewayModelPreset("DeepSeek", "deepseek-v4-pro", "DS", GatewayTint.BLUE, listOf("deepseek")),
    GatewayModelPreset("DeepSeek", "deepseek-v4-flash", "DS", GatewayTint.BLUE, listOf("deepseek")),
    GatewayModelPreset("GLM", "glm-4.7", "GL", GatewayTint.VIOLET, listOf("bigmodel", "zhipu", "z.ai")),
    GatewayModelPreset("Kimi", "kimi-k3", "KM", GatewayTint.AMBER, listOf("moonshot", "kimi")),
    GatewayModelPreset("Qwen", "qwen3-coder-plus", "QW", GatewayTint.TEAL, listOf("dashscope", "qwen", "aliyun")),
    GatewayModelPreset("MiniMax", "MiniMax-M2.5", "MM", GatewayTint.PINK, listOf("minimax")),
)

/** Compact display host of a gateway base URL: scheme/path stripped, port kept
 *  ("http://127.0.0.1:3456/api" → "127.0.0.1:3456"). Null/blank → null.
 *
 *  Userinfo is dropped BEFORE the host, because a URL may carry it and the real host is what follows
 *  the last `@`: "https://api.anthropic.com@evil.example" resolves to evil.example, but rendering it
 *  verbatim (and truncating) would read as "api.anthropic.com…". Since #167 this URL is no longer just
 *  a label — it's where the daemon sends a credential — so the pill must not be able to misname it. */
fun gatewayHostLabel(baseUrl: String?): String? = baseUrl?.trim()
    ?.substringAfter("://")
    ?.substringBefore('/')
    ?.substringAfterLast('@')
    ?.takeIf { it.isNotBlank() }

/** True when [gatewayBaseUrl] names this vendor — drives both the rank-first ordering and the
 *  "suggested" tick on the matched rows (0714 design). Null URL never matches. */
fun GatewayModelPreset.matchesGatewayHost(gatewayBaseUrl: String?): Boolean {
    val url = gatewayBaseUrl?.lowercase() ?: return false
    return hostHints.any { it in url }
}

/** The preset rows ordered for [gatewayBaseUrl]: rows whose vendor the URL names come first (a
 *  DeepSeek endpoint lists deepseek-* on top), everything else keeps table order. A null/unmatched
 *  URL returns the table as-is — an aggregator gateway routes any vendor. */
fun recommendedGatewayPresets(gatewayBaseUrl: String?): List<GatewayModelPreset> {
    if (gatewayBaseUrl == null) return GATEWAY_MODEL_PRESETS
    return GATEWAY_MODEL_PRESETS.sortedBy { if (it.matchesGatewayHost(gatewayBaseUrl)) 0 else 1 }
}

/**
 * #167 ②: the rows to actually show, preferring what the GATEWAY ITSELF reported over our table.
 *
 * The authoritative answer arrives as bare ids — no vendor name, no monogram, no tint. So the table
 * below stops being the SOURCE of ids and becomes a lookup for how to DRAW one: an id we happen to
 * know keeps its vendor identity, and an id we've never heard of still gets a row synthesized from
 * the id itself rather than being hidden because our list is a generation behind. That inverts the
 * old failure mode — being out of date now costs some polish, not a missing model.
 *
 * [authoritativeIds] empty (no gateway, gateway didn't answer, older daemon) → unchanged behaviour.
 */
fun gatewayRowsFrom(authoritativeIds: List<String>, gatewayBaseUrl: String?): List<GatewayModelPreset> {
    if (authoritativeIds.isEmpty()) return recommendedGatewayPresets(gatewayBaseUrl)
    val known = GATEWAY_MODEL_PRESETS.associateBy { it.id.lowercase() }
    return authoritativeIds.map { id -> known[id.trim().lowercase()] ?: syntheticPreset(id.trim()) }
}

/** A row for an id the table has never seen: identity derived from the id, never invented. The vendor
 *  label is the id's own first segment verbatim — guessing a vendor's preferred casing would be a
 *  fabrication, and this section's whole point is to stop guessing. */
private fun syntheticPreset(id: String): GatewayModelPreset = GatewayModelPreset(
    vendor = id.substringBefore('-').substringBefore('/').ifEmpty { id },
    id = id,
    monogram = id.filter(Char::isLetterOrDigit).take(2).uppercase().ifEmpty { "··" },
    tint = stableTint(id),
)

/** Deterministic tint so a given id keeps its colour across recompositions and restarts. Modulo is
 *  folded twice because `hashCode` can be negative (and `Int.MIN_VALUE` has no positive absolute). */
private fun stableTint(id: String): GatewayTint {
    val palette = GatewayTint.entries
    val h = id.lowercase().hashCode()
    return palette[((h % palette.size) + palette.size) % palette.size]
}
