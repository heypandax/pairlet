package dev.ccpocket.protocol

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** issue #208: attribute the CLI's <synthetic> placeholder by the evidence in its own text. */
class SyntheticAttributionTest {

    @Test
    fun explicit_gateway_5xx_is_read_as_api_failure() {
        // the small-red-book病例: context at 2%, placeholder blames a local inference gateway 502
        val placeholder = "API Error: 502 — check your inference gateway (127.0.0.1:8089)"
        assertTrue(SyntheticAttribution.looksLikeApiFailure(placeholder))
        val text = SyntheticAttribution.attribution(placeholder)
        assertTrue("gateway" in text.lowercase(), "gateway branch should name the API/gateway")
        assertFalse("/clear" in text, "an explicit API failure must NOT push the user at /clear")
    }

    @Test
    fun other_5xx_and_overloaded_wording_also_read_as_api_failure() {
        assertTrue(SyntheticAttribution.looksLikeApiFailure("Bad Gateway"))
        assertTrue(SyntheticAttribution.looksLikeApiFailure("503 Service Unavailable"))
        assertTrue(SyntheticAttribution.looksLikeApiFailure("Overloaded"))
        assertTrue(SyntheticAttribution.looksLikeApiFailure("Connection error"))
    }

    @Test
    fun no_signal_keeps_the_context_window_fallback_with_a_low_usage_hedge() {
        val placeholder = "No response requested."
        assertFalse(SyntheticAttribution.looksLikeApiFailure(placeholder))
        val text = SyntheticAttribution.attribution(placeholder)
        assertTrue("/clear" in text, "fallback still offers the context-window remedy")
        // the new hedge that splits low-context users off toward the API-link cause
        assertTrue("low" in text.lowercase() && "api" in text.lowercase())
    }

    @Test
    fun blank_or_null_is_not_an_api_failure() {
        assertFalse(SyntheticAttribution.looksLikeApiFailure(null))
        assertFalse(SyntheticAttribution.looksLikeApiFailure("   "))
    }
}
