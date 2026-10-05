package dev.ccpocket.daemon

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.testing.test
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * `pairlet review`, `pairlet collaborator` and `pairlet share` were retired. The names stay, hidden, so whoever still calls
 * them is told so and gets a non-zero exit — never "no such subcommand", and never a call to the daemon.
 */
class RetiredCliTest {

    /** A stand-in for the real root (whose run() touches the managed-install aliases). */
    private class Parent : CliktCommand(name = "pairlet") {
        override fun run() = Unit
    }

    private fun pairlet(vararg argv: String) =
        Parent().subcommands(retiredCollaboratorCommand(), retiredReviewCommand(), retiredShareCommand(), agentCommand()).test(argv.toList())

    @Test
    fun the_retired_names_explain_themselves_and_exit_non_zero_whatever_follows() {
        val calls = listOf(
            listOf("review"), listOf("review", "send", "--to", "c-1", "--title", "x"), listOf("review", "inbox", "--json"),
            listOf("collaborator"), listOf("collaborator", "invite", "--label", "Frank"), listOf("collaborator", "list"),
            listOf("share"), listOf("share", "--workdir", "/w", "--tier", "review"), listOf("share", "--list"),
            listOf("share", "--revoke", "dev-g"),
        )
        for (argv in calls) {
            val r = pairlet(*argv.toTypedArray())
            assertNotEquals(0, r.statusCode, "$argv must fail")
            assertTrue(r.output.contains("has been retired"), "$argv: ${r.output}")
            assertFalse(r.output.contains("no such subcommand", ignoreCase = true), "$argv: ${r.output}")
        }
    }

    @Test
    fun the_retired_names_are_hidden_from_help_and_agent_is_untouched() {
        val help = pairlet("--help").output
        assertFalse(Regex("""^\s+review\b""", RegexOption.MULTILINE).containsMatchIn(help), help)
        assertFalse(Regex("""^\s+collaborator\b""", RegexOption.MULTILINE).containsMatchIn(help), help)
        assertFalse(Regex("""^\s+share\b""", RegexOption.MULTILINE).containsMatchIn(help), help)
        assertTrue(Regex("""^\s+agent\b""", RegexOption.MULTILINE).containsMatchIn(help), help)
    }
}
