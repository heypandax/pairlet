package dev.ccpocket.daemon

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.CliktError
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.multiple

/**
 * A command name that used to exist and was retired. Kept HIDDEN (absent from `--help`) so an old habit,
 * script or Skill that still calls it gets a plain explanation and a non-zero exit instead of Clikt's
 * "no such subcommand". It accepts and ignores whatever followed it, and talks to nothing.
 */
private class RetiredCmd(name: String, private val what: String) : CliktCommand(name = name) {
    override val hiddenFromHelp = true
    override val treatUnknownOptionsAsArgs = true
    private val ignored by argument().multiple()

    override fun help(context: Context) = "$what (retired)"

    override fun run() {
        throw CliktError("pairlet $commandName: $what has been retired and is no longer available.", statusCode = 1)
    }
}

/** `pairlet review …` — review requests, retired 2026-10. */
internal fun retiredReviewCommand(): CliktCommand = RetiredCmd("review", "review requests")

/** `pairlet collaborator …` — the contacts review requests were exchanged with, retired with them. */
internal fun retiredCollaboratorCommand(): CliktCommand = RetiredCmd("collaborator", "review contacts")
