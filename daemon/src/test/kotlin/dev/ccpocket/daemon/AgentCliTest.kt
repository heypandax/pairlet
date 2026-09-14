package dev.ccpocket.daemon

import com.github.ajalt.clikt.testing.test
import dev.ccpocket.daemon.control.LocalError
import dev.ccpocket.protocol.PocketJson
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * `pairlet agent …` (#367) WITHOUT a daemon: the command tree an Agent is told to call, the refusals that
 * must happen before anything leaves this machine, and the two shape rules that are security decisions
 * rather than ergonomics —
 *
 *  - THE PROMPT IS NOT AN ARGUMENT. There is no `--prompt`; argv is readable in the local process table.
 *  - AN INVITE IS NOT AN ARGUMENT either: it carries a live one-time ticket AND the invite secret.
 *
 * Everything a command actually DOES is covered against the real routes by ExecutionControlRoutesTest.
 */
class AgentCliTest {

    private fun agent(vararg argv: String) = agentCommand().test(argv.toList())

    private fun String.saysNoSuchSubcommand() = contains("no such subcommand", ignoreCase = true)

    @Test
    fun the_command_tree_is_the_one_the_design_documents() {
        listOf("targets", "run", "status", "result", "cancel", "grant").forEach {
            assertFalse(agent(it).output.saysNoSuchSubcommand(), "agent $it must exist")
        }
        listOf("approve", "confirm", "revoke", "list", "join").forEach {
            assertFalse(agent("grant", it).output.saysNoSuchSubcommand(), "agent grant $it must exist")
        }
        // deliberately absent: nothing here browses the target's disk or resumes its existing sessions
        assertTrue(agent("ls").output.saysNoSuchSubcommand(), "the MVP does not browse a remote machine")
        assertTrue(agent("resume").output.saysNoSuchSubcommand(), "the MVP never continues someone else's session")
        assertTrue(agent("shell").output.saysNoSuchSubcommand(), "there is no remote shell command")
    }

    @Test
    fun the_prompt_can_only_arrive_as_a_file_never_as_an_argument() {
        val inline = agent("run", "--target", "xg_x", "--workspace", "app", "--prompt", "do the thing")
        assertEquals(1, inline.statusCode)
        assertTrue("no such option" in inline.output.lowercase(), inline.output)
        // …and the file form is what the missing-argument message points at
        val missing = agent("run", "--target", "xg_x", "--workspace", "app")
        assertEquals(1, missing.statusCode)
        assertTrue("--prompt-file" in missing.output, missing.output)
        assertTrue("command line" in missing.output, "the message should say WHY: ${missing.output}")
    }

    @Test
    fun run_refuses_before_the_network_when_a_required_piece_is_missing() {
        listOf(
            arrayOf("run", "--workspace", "app", "--prompt-file", "-") to "--target",
            arrayOf("run", "--target", "xg_x", "--prompt-file", "-") to "--workspace",
        ).forEach { (argv, expected) ->
            val res = agent(*argv)
            assertEquals(1, res.statusCode, res.output)
            assertTrue(expected in res.output, "expected $expected in: ${res.output}")
        }
    }

    @Test
    fun a_missing_empty_or_oversize_prompt_file_never_reaches_the_daemon() {
        val absent = agent("run", "--target", "xg_x", "--workspace", "app", "--agent", "claude", "--prompt-file", "/definitely/not/here.txt")
        assertEquals(1, absent.statusCode)
        assertTrue("no such prompt file" in absent.output, absent.output)

        val empty = Files.createTempFile("ccp-agent-prompt", ".txt").toFile().apply { writeText("   ") }
        assertTrue("is empty" in agent("run", "--target", "xg_x", "--workspace", "app", "--agent", "claude", "--prompt-file", empty.path).output)

        val big = Files.createTempFile("ccp-agent-prompt", ".txt").toFile().apply { writeText("x".repeat(300 * 1024)) }
        val res = agent("run", "--target", "xg_x", "--workspace", "app", "--agent", "claude", "--prompt-file", big.path)
        assertEquals(1, res.statusCode)
        assertTrue("too large" in res.output, res.output)
    }

    @Test
    fun the_backend_is_required_and_never_inferred() {
        // #367 contract: the agent is part of the grant's SCOPE. Inferring "the only one allowed" would
        // silently move an existing caller to a different backend the day the owner widens the grant.
        val f = Files.createTempFile("ccp-agent-prompt", ".txt").toFile().apply { writeText("do the thing") }
        val res = agent("run", "--target", "xg_x", "--workspace", "app", "--prompt-file", f.path)
        assertEquals(1, res.statusCode)
        assertTrue("--agent" in res.output, res.output)
        assertTrue("agent targets" in res.output, "the error must say where to look: ${res.output}")
    }

    @Test
    fun an_invite_is_read_from_a_file_and_the_fingerprint_is_not_optional() {
        val noFile = agent("grant", "join", "--target-fingerprint", "abcd-efgh")
        assertEquals(1, noFile.statusCode)
        assertTrue("--invite-file" in noFile.output, noFile.output)
        assertTrue("command line" in noFile.output, "the message must say why: ${noFile.output}")

        val f = Files.createTempFile("ccp-agent-invite", ".txt").toFile().apply { writeText("ccpocket://execution-grant#abc") }
        val noFingerprint = agent("grant", "join", "--invite-file", f.path)
        assertEquals(1, noFingerprint.statusCode)
        assertTrue("--target-fingerprint" in noFingerprint.output, noFingerprint.output)
        // refusing without the fingerprint is the whole point — an invite alone proves nothing
        assertTrue("trusting the invite alone" in noFingerprint.output, noFingerprint.output)
    }

    @Test
    fun approve_refuses_a_workspace_that_is_not_alias_equals_path() {
        assertTrue("--label" in agent("grant", "approve", "--workspace", "app=/tmp").output)
        assertTrue("--workspace" in agent("grant", "approve", "--label", "Studio Mac").output)
        val bad = agent("grant", "approve", "--label", "Studio Mac", "--workspace", "/tmp/project")
        assertEquals(1, bad.statusCode)
        assertTrue("alias=/absolute/path" in bad.output, bad.output)
    }

    @Test
    fun confirm_and_the_id_arguments_are_usage_errors_rather_than_calls() {
        listOf("status", "result", "cancel").forEach { sub ->
            val res = agent(sub)
            assertEquals(1, res.statusCode, "`agent $sub` with no id must fail")
            assertTrue("missing argument" in res.output, "`agent $sub`: ${res.output}")
        }
        assertTrue("--fingerprint" in agent("grant", "confirm", "xg_x").output)
    }

    /** With no daemon reachable the CLI must say so plainly — and never try to start one. */
    @Test
    fun a_command_that_needs_the_daemon_fails_cleanly_when_it_is_not_running() {
        val res = agent("targets", "--pair-port", "1")
        assertEquals(1, res.statusCode)
        assertTrue(
            "no cc-pocket daemon" in res.output || "local control token" in res.output,
            "expected an honest 'daemon not reachable' message: ${res.output}",
        )
    }

    /** The machine contract: with --json even a FAILURE is one parseable object carrying a stable code. */
    @Test
    fun json_mode_answers_a_machine_even_when_it_fails() {
        val res = agent("targets", "--pair-port", "1", "--json")
        assertEquals(1, res.statusCode)
        val line = res.output.trim().lines().first { it.startsWith("{") }
        val err = PocketJson.decodeFromString(LocalError.serializer(), line)
        assertFalse(err.ok)
        assertTrue(err.code == "daemon_unreachable" || err.code == "token_missing", "unexpected code: ${err.code}")
    }
}
