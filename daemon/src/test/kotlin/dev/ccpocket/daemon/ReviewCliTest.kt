package dev.ccpocket.daemon

import com.github.ajalt.clikt.testing.test
import dev.ccpocket.daemon.review.encodeUri
import java.nio.file.Files
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The CLI's contract, checked WITHOUT a daemon: the command tree the Skill documents, the pre-flight
 * refusals that must happen before anything is sent anywhere, and the non-zero exit every failure owes
 * its caller.
 *
 * Deliberately only the paths that never reach the network. Everything a command would actually DO is
 * covered by LocalControlRoutesTest, against the real routes.
 */
class ReviewCliTest {

    private fun review(vararg argv: String) = reviewCommand().test(argv.toList())
    private fun collaborator(vararg argv: String) = collaboratorCommand().test(argv.toList())

    /** Clikt's own wording for an argv that names nothing this build ships. */
    private fun String.saysNoSuchSubcommand() = contains("no such subcommand", ignoreCase = true)

    @Test
    fun the_command_tree_is_the_one_the_skill_documents() {
        listOf("invite", "join", "list", "remove").forEach {
            assertTrue(!collaborator(it).output.saysNoSuchSubcommand(), "collaborator $it must exist — the Skill documents it")
        }
        listOf(
            "send", "list", "inbox", "show", "prepare",
            "acknowledge", "start", "decline", "respond", "cancel", "close",
        ).forEach {
            assertTrue(!review(it).output.saysNoSuchSubcommand(), "review $it must exist — the Skill documents it")
        }
        // deliberately absent in M1, so the Skill can't offer them: auto-launching an agent, campaigns
        assertTrue(review("run").output.saysNoSuchSubcommand(), "review run is out of scope for M1")
        assertTrue(review("campaign").output.saysNoSuchSubcommand(), "campaigns are out of scope for M1")
    }

    @Test
    fun send_refuses_before_the_network_when_a_required_piece_is_missing() {
        listOf(
            arrayOf("send", "--request", "x", "--artifact", "mr:https://g/1") to "--to",
            arrayOf("send", "--to", "Frank", "--artifact", "mr:https://g/1") to "--request",
            arrayOf("send", "--to", "Frank", "--request", "x") to "--artifact",
        ).forEach { (argv, expected) ->
            val res = review(*argv)
            assertEquals(1, res.statusCode, "a refusal must exit non-zero: ${res.output}")
            assertTrue(expected in res.output, "expected $expected in: ${res.output}")
        }
    }

    /** A typo'd artifact must fail on THIS machine, before a colleague is told anything. */
    @Test
    fun send_validates_the_artifact_syntax_locally() {
        val unrecognised = review("send", "--to", "Frank", "--request", "x", "--artifact", "https://g/1")
        assertEquals(1, unrecognised.statusCode)
        assertTrue("unrecognised" in unrecognised.output, unrecognised.output)

        val scheme = review("send", "--to", "Frank", "--request", "x", "--artifact", "document:file:///etc/passwd")
        assertEquals(1, scheme.statusCode)
        assertTrue("http(s)" in scheme.output, scheme.output)
        assertTrue("passwd" !in scheme.output, "the refusal must not echo the value back: ${scheme.output}")
    }

    @Test
    fun respond_refuses_a_missing_or_oversize_result_file_before_the_network() {
        assertTrue("--result" in review("respond", "rr_1").output)
        assertTrue("no such file" in review("respond", "rr_1", "--result", "/definitely/not/here.json").output)

        val big = Files.createTempFile("ccp-review-result", ".json").toFile()
        big.writeText("{\"summary\":\"" + "x".repeat(600 * 1024) + "\"}")
        val res = review("respond", "rr_1", "--result", big.path)
        assertEquals(1, res.statusCode)
        assertTrue("too large" in res.output, res.output)
    }

    @Test
    fun a_bad_iso_time_is_named_rather_than_silently_dropped() {
        val res = review(
            "send", "--to", "Frank", "--request", "x",
            "--artifact", "mr:https://g/1", "--due", "tomorrow afternoon",
        )
        assertEquals(1, res.statusCode)
        assertTrue("ISO-8601" in res.output, res.output)
    }

    @Test
    fun iso_times_parse_in_both_the_offset_and_the_instant_form() {
        assertEquals(parseIso("2026-08-03T17:00:00Z"), parseIso("2026-08-03T10:00:00-07:00"))
        assertEquals(Instant.parse("2026-08-03T07:00:00Z").toEpochMilli(), parseIso("2026-08-03T07:00:00Z"))
    }

    @Test
    fun a_missing_request_id_argument_is_a_usage_error_not_a_call() {
        listOf("show", "prepare", "acknowledge", "start", "decline", "cancel", "close").forEach { sub ->
            val res = review(sub)
            assertEquals(1, res.statusCode, "`review $sub` with no id must fail")
            assertTrue("missing argument" in res.output, "`review $sub`: ${res.output}")
        }
    }

    /**
     * The INVITER's half of the fingerprint check. Verification is bilateral or it is theatre: the joiner
     * has always been shown these words, and if the inviter is not, there is nobody to compare against.
     *
     * The fingerprint is computed from a REAL generated daemon key, the same way both ends compute it.
     */
    @Test
    fun the_invite_output_shows_the_inviter_the_same_fingerprint_the_joiner_will_see() {
        val daemonPub = dev.ccpocket.daemon.review.TestKeys.DAEMON_PUB
        val invite = dev.ccpocket.protocol.CollaboratorInvite(
            relay = "wss://relay.example", accountId = "acctA", daemonPub = daemonPub,
            ticket = "ONE-TIME-TICKET", ownerLabel = "Panda · MacBook", ttlSec = 120,
        )
        val theirs = dev.ccpocket.protocol.collaboratorFingerprint(daemonPub)
        val res = dev.ccpocket.daemon.control.LocalInviteRes(
            invite = invite.encodeUri(), ttlSec = 120, label = "Frank", fingerprint = theirs,
        )

        val text = inviteHumanLines(res, "Frank").joinToString("\n")
        assertTrue(theirs in text, "the inviter must see the word group: $text")
        assertTrue("fingerprint" in text, text)
        // and the invite is still there to hand over — showing the words replaces nothing
        assertTrue(res.invite in text)
        // key material never reaches the terminal, only its display derivative
        assertTrue(daemonPub !in text.substringAfter(res.invite), "the raw key must not be printed")
    }

    /** With no daemon reachable the CLI must say so plainly — and never try to start one. */
    @Test
    fun a_command_that_needs_the_daemon_fails_cleanly_when_it_is_not_running() {
        // port 1 is never a cc-pocket daemon
        val res = review("list", "--pair-port", "1")
        assertEquals(1, res.statusCode)
        assertTrue(
            "no cc-pocket daemon" in res.output || "local control token" in res.output,
            "expected an honest 'daemon not reachable' message: ${res.output}",
        )
    }
}
