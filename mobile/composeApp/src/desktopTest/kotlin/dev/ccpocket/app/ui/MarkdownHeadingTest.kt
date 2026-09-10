package dev.ccpocket.app.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Issue #355 — a paragraph opening with `#5` / `#include` must not render as a heading. */
class MarkdownHeadingTest {
    @Test
    fun realHeadingsKeepTheirLevel() {
        assertEquals(1, mdHeadingLevel("# Title"))
        assertEquals(2, mdHeadingLevel("## Sub"))
        assertEquals(6, mdHeadingLevel("###### deep"))
        assertEquals(3, mdHeadingLevel("###")) // empty heading is still a heading
        assertEquals(2, mdHeadingLevel("##\tTabbed"))
    }

    @Test
    fun hashPrefixedProseIsNotAHeading() {
        assertNull(mdHeadingLevel("#5 已修复（2026-09-03，COMPLETED）：漏扫 ~/.agents/skills 的兜底路由已补上。"))
        assertNull(mdHeadingLevel("#include <stdio.h>"))
        assertNull(mdHeadingLevel("#hashtag"))
        assertNull(mdHeadingLevel("####### seven is too many"))
        assertNull(mdHeadingLevel("plain"))
    }
}
