package dev.ccpocket.app.pairing

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class LinkSchemeTest {
    @Test
    fun a_pairlet_link_reaches_the_same_parser_branch_as_its_legacy_twin() {
        assertEquals("ccpocket://pair?code=123456", canonicalLinkScheme("pairlet://pair?code=123456"))
        assertEquals("  ccpocket://share#abc", canonicalLinkScheme("  PAIRLET://share#abc"))
        assertIs<IncomingLink.Code>(parseIncomingLink(canonicalLinkScheme("pairlet://pair?code=123456")))
    }

    @Test
    fun everything_else_is_left_byte_for_byte() {
        for (raw in listOf("ccpocket://pair?code=123456", "123456", "", "   ", "https://pairlet.org/x", "see pairlet://pair")) {
            assertEquals(raw, canonicalLinkScheme(raw))
        }
    }
}
