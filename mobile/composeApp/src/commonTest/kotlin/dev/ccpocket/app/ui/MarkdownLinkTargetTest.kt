package dev.ccpocket.app.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MarkdownLinkTargetTest {
    @Test
    fun labelledChineseDocumentKeepsTheCompleteDestination() {
        val target = "/Users/panda/Desktop/Pairlet/_local/用户反馈修改交接.md"
        val text = inline("请看 [交接文档]($target)。")
        assertEquals("请看 交接文档。", text.text)
        val entity = recognizeEntities(text, null) { true }.single()
        assertEquals("交接文档", entity.display)
        assertEquals(target, entity.target)
        assertEquals(target, entity.copyValue)
        assertEquals("交接文档", text.text.substring(entity.start, entity.end))
    }

    @Test
    fun spacesParenthesesAndTitlesDoNotTruncateThePath() {
        val target = "/Users/alex/My Project/设计 (最终).md"
        val text = inline("[设计](<$target> \"optional title\")")
        assertEquals(target, recognizeEntities(text, null) { false }.single().copyValue)
        assertEquals("设计", text.text)
    }

    @Test
    fun aUrlRetainsItsQueryFragmentAndBalancedParentheses() {
        val url = "https://example.org/a_(b)?name=%E4%B8%AD&x=1#part-2"
        val entity = recognizeEntities(inline("[网站]($url)"), null) { false }.single()
        assertEquals(EntityKind.URL, entity.kind)
        assertEquals(url, entity.copyValue)
    }

    @Test
    fun relativeAndWindowsDestinationsUseTheSessionDirectoryOnlyForCopy() {
        val entity = recognizeEntities(inline("[说明](docs/readme.md)"), "C:\\work\\project") { true }.single()
        assertEquals("docs/readme.md", entity.target)
        assertEquals("C:\\work\\project\\docs/readme.md", entity.copyValue)
        val drive = "C:\\work\\project\\readme.md"
        assertEquals(drive, recognizeEntities(inline("[Windows]($drive)"), "/other") { true }.single().copyValue)
    }

    @Test
    fun aPathShapedLabelDoesNotOverrideItsExplicitTarget() {
        val e = recognizeEntities(inline("[docs/wrong.md](/srv/right.md) and /srv/other.md"), null) { true }
        assertEquals(listOf("/srv/right.md", "/srv/other.md"), e.map { it.copyValue })
    }

    @Test
    fun unsupportedSchemesAndIncompleteMarkdownStayLiteral() {
        val original = "[mail](mailto:hello@example.org) [pending](/srv/readme.md"
        assertEquals(original, inline(original).text)
        assertTrue(inline(original).getStringAnnotations(MARKDOWN_LINK_TARGET, 0, original.length).isEmpty())
    }
}
