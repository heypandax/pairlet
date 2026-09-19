package dev.ccpocket.app.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** #390 ("scroll a long HTML attachment on iOS"). The fixtures under
 * `src/desktopTest/resources/htmlpreview/` are the documents a tester opens on a real device; see the
 * README there. Two things about them are decidable without a device, and those are what this pins:
 * the samples are genuinely offline, and the shared wrapper still leaves the srcdoc iframe as the
 * scrolling viewport while keeping its opaque sandbox origin.
 *
 * Whether a finger actually moves that scroll range on iOS is a native-host question (UIKit interop
 * touch delivery), which no desktop test can answer — a green run here is not a fix for #390. */
class HtmlPreviewScrollSampleTest {
    private val samples = listOf(
        "long-text.html", "nested-scroll.html", "dynamic-growth.html", "viewport-fixed.html", "wide-content.html",
    )

    private fun sample(name: String): String =
        javaClass.classLoader.getResourceAsStream("htmlpreview/$name")
            ?.use { it.readBytes().decodeToString() }
            ?: error("missing sample htmlpreview/$name")

    /** A sample that reaches the network would make a device run untrustworthy (offline phone, proxy,
     * CSP) — the only absolute URL allowed is the SVG namespace, which is an identifier, not a fetch. */
    @Test
    fun samplesAreOfflineAndCarryUniqueMarkers() {
        for (name in samples) {
            val body = sample(name).replace("http://www.w3.org/2000/svg", "svg-namespace")
            for (needle in listOf("http://", "https://", "src=\"//", "src='//", "@import", "url(http")) {
                assertFalse(needle in body, "$name reaches outside: $needle")
            }
            assertTrue(Regex("[A-Z-]+-390").containsMatchIn(body), "$name has no -390 marker to look for")
        }
    }

    /** `overflow:hidden` on the wrapper's own html/body is deliberate: it pins a device-sized viewport
     * so the iframe — which is a scroll container in its own right — owns the scrolling. The iframe must
     * therefore never gain `scrolling="no"`, and must stay exactly the viewport size. */
    @Test
    fun wrapperLeavesTheIframeAsTheScrollingViewport() {
        val chrome = htmlPreviewDocument(sample("long-text.html")).substringBefore("srcdoc=")
        assertTrue("html,body{margin:0;width:100%;height:100%;overflow:hidden" in chrome)
        assertTrue("iframe{display:block;border:0;width:100%;height:100%}" in chrome)
        assertTrue("content=\"width=device-width, initial-scale=1\"" in chrome)
        assertFalse("scrolling=" in chrome, "the iframe must keep its own scroll container")
        assertEquals(1, Regex("<iframe").findAll(chrome).count())
    }

    /** The isolation boundary the issue must not trade away for easier scroll measurement. */
    @Test
    fun wrapperKeepsTheOpaqueSandboxOrigin() {
        val chrome = htmlPreviewDocument(sample("wide-content.html")).substringBefore("srcdoc=")
        assertTrue("sandbox=\"allow-scripts\"" in chrome)
        assertFalse("allow-same-origin" in chrome)
        assertFalse("allow-top-navigation" in chrome)
        assertTrue("default-src 'none'" in chrome)
        assertTrue("base-uri 'none'" in chrome)
        assertTrue("form-action 'none'" in chrome)
    }

    /** Every sample must survive the srcdoc round trip byte for byte: nothing may escape the attribute
     * (that is the breakout the rendering test also guards), and nothing may be silently dropped —
     * a mangled sample would make a device run measure the wrong document. */
    @Test
    fun samplesSurviveSrcdocEscapingUnchanged() {
        for (name in samples) {
            val original = sample(name)
            val srcdoc = htmlPreviewDocument(original).substringAfter("srcdoc=\"").substringBeforeLast("\">")
            for (raw in listOf('"', '<', '>')) assertFalse(raw in srcdoc, "$name leaks a raw $raw out of srcdoc")
            val decoded = srcdoc.replace("&lt;", "<").replace("&gt;", ">")
                .replace("&quot;", "\"").replace("&amp;", "&") // &amp; last: it was escaped first
            assertEquals(original, decoded, "$name does not round-trip through srcdoc")
        }
    }
}
