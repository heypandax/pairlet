package dev.ccpocket.daemon.review

import dev.ccpocket.protocol.ArtifactKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The `--artifact` grammar (REVIEW-REQUEST.md §4.2). This is the contract the CLI, the Skill and any
 * future UI share, so what it accepts and — more importantly — what it REFUSES is worth pinning down:
 * a guessed artifact kind sends a colleague to review the wrong thing.
 */
class ArtifactSyntaxTest {

    private fun ok(raw: String) = ArtifactSyntax.parse(raw).getOrElse { error("expected success for \"$raw\": ${it.message}") }
    private fun err(raw: String): String {
        val r = ArtifactSyntax.parse(raw)
        assertTrue(r.isFailure, "expected \"$raw\" to be refused, got ${r.getOrNull()}")
        return r.exceptionOrNull()!!.message.orEmpty()
    }

    @Test
    fun parses_the_three_M1_kinds() {
        val mr = ok("mr:https://git.example.com/team/repo/-/merge_requests/42")
        assertEquals(ArtifactKind.MERGE_REQUEST, mr.kind)
        assertEquals("https://git.example.com/team/repo/-/merge_requests/42", mr.url)

        val doc = ok("document:https://docs.example.com/design/relay")
        assertEquals(ArtifactKind.DOCUMENT_URL, doc.kind)

        val range = ok("commits:git.example.com/team/repo#aaa1111..bbb2222")
        assertEquals(ArtifactKind.COMMIT_RANGE, range.kind)
        assertEquals("git.example.com/team/repo", range.repo)
        assertEquals("aaa1111", range.base)
        assertEquals("bbb2222", range.head)
        assertNull(range.url, "a commit range has no canonical URL to invent")
    }

    @Test
    fun a_trailing_title_is_split_off_and_may_itself_contain_a_pipe() {
        val a = ok("mr:https://git.example/mr/1 | relay ACK fence")
        assertEquals("https://git.example/mr/1", a.url)
        assertEquals("relay ACK fence", a.title)

        val b = ok("mr:https://git.example/mr/1 | a | b")
        assertEquals("https://git.example/mr/1", b.url, "the URL never keeps part of the title")
        assertEquals("a | b", b.title, "the FIRST separator wins, so a title may contain a pipe")
    }

    @Test
    fun an_unrecognised_prefix_is_refused_rather_than_guessed() {
        assertTrue("unrecognised" in err("https://git.example/mr/1"))
        assertTrue("unrecognised" in err("pr:https://git.example/pr/1"))
        assertTrue("unrecognised" in err("MR:https://git.example/mr/1"), "the prefixes are case-sensitive on purpose")
        assertTrue(err("").isNotEmpty())
    }

    @Test
    fun a_non_http_url_is_refused_and_the_value_is_not_echoed_back() {
        val msg = err("document:file:///etc/passwd")
        assertTrue("http(s)" in msg, msg)
        assertTrue("passwd" !in msg, "a refusal reaches the logs — it must not carry the value: $msg")
        assertTrue("http(s)" in err("mr:javascript:alert(1)"))
    }

    @Test
    fun a_commit_range_needs_all_three_parts() {
        assertTrue("commits:" in err("commits:git.example/repo"))
        assertTrue("commits:" in err("commits:git.example/repo#aaa"))
        assertTrue("commits:" in err("commits:#aaa..bbb"))
    }

    @Test
    fun the_length_bounds_are_the_stores_bounds() {
        assertTrue("too long" in err("mr:https://x/" + "a".repeat(ReviewLimits.MAX_URL)))
        assertTrue("too long" in err("commits:" + "r".repeat(ReviewLimits.MAX_REPO + 1) + "#a..b"))
    }

    @Test
    fun render_round_trips_back_into_a_token_the_parser_accepts() {
        listOf(
            "mr:https://git.example/mr/1",
            "mr:https://git.example/mr/1 | ACK fence",
            "document:https://docs.example/x",
            "commits:git.example/team/repo#aaa..bbb",
            "commits:git.example/team/repo#aaa..bbb | the retry rework",
        ).forEach { token ->
            assertEquals(token, ArtifactSyntax.render(ok(token)), "render must be the parser's inverse")
        }
    }
}
