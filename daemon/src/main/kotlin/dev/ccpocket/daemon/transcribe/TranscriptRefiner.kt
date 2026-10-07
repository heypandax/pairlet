package dev.ccpocket.daemon.transcribe

import dev.ccpocket.protocol.AgentKind
import dev.ccpocket.protocol.TextEdit

/**
 * One transcript-refiner adapter: a one-shot, tool-less call to an agent CLI that proposes replacements for a
 * dictated transcript (design §4). Adapters are registered per [AgentKind] in [TranscriptRefiners]; adding one for
 * another agent touches neither [TranscriptRefineService] nor the wire.
 */
interface TranscriptRefiner {
    val agent: AgentKind

    /** LOCAL check only (binary present and launchable this way) — never starts the model. */
    fun isAvailable(): Boolean

    /**
     * Ask the model for replacements to [text]. The list comes back RAW: the caller runs [TranscriptEditValidator]
     * before any of it may reach a phone. The text must travel as data (stdin, a JSON field), never on argv.
     *
     * [locale] picks the instruction language ([RefineContract.systemPrompt]); [glossary] holds words the speaker is
     * likely to use — untrusted reference data that travels next to the text ([RefineContract.userMessage]), never
     * in the instructions. The call must finish within [timeoutMs] and leave no process behind — also when the calling
     * coroutine is cancelled, which it may be at any time (a newer request, the phone's cancel, the hard limit).
     * No outcome carries model output or CLI stderr except as a checked [RefineOutcome.Edits].
     */
    suspend fun refine(text: String, locale: String?, glossary: List<String>, timeoutMs: Long): RefineOutcome
}

sealed interface RefineOutcome {
    /** The model's replacement list, shape-checked against [RefineContract.SCHEMA] but not yet validated. */
    data class Edits(val edits: List<TextEdit>) : RefineOutcome

    /** The CLI is missing or could not be started (also a Windows batch shim, see [ClaudeTranscriptRefiner]). */
    data object Unavailable : RefineOutcome

    /** It ran and failed: non-zero exit, an error envelope, unreadable output, output outside the schema. */
    data object Failed : RefineOutcome

    data object TimedOut : RefineOutcome
}

/**
 * The refiners this daemon was built with, at most one per agent, in registration order (the order
 * [available] advertises them in [dev.ccpocket.protocol.DaemonInfo.transcriptRefineAgents]). An empty list, or a
 * machine without any of their CLIs, is a legal state: the phone then keeps putting dictation in the composer.
 */
class TranscriptRefiners(adapters: List<TranscriptRefiner>) {
    val all: List<TranscriptRefiner> = adapters.toList()

    init {
        require(all.map { it.agent }.distinct().size == all.size) { "duplicate transcript refiner agent" }
    }

    /** Wire names of the agents whose refiner can launch right now (a LOCAL check per adapter), in order. */
    fun available(): List<String> = all.filter { usable(it) }.map { it.agent.wireName() }

    /** The refiner for [agent] when it can launch right now; null for no agent, no adapter, or a missing CLI. */
    fun usableFor(agent: AgentKind?): TranscriptRefiner? =
        agent?.let { a -> all.firstOrNull { it.agent == a } }?.takeIf { usable(it) }

    private fun usable(r: TranscriptRefiner): Boolean = runCatching { r.isAvailable() }.getOrDefault(false)

    companion object {
        val EMPTY = TranscriptRefiners(emptyList())

        /** [AgentKind]'s wire name — its `@SerialName`, which is the enum name in lower case for every agent. */
        fun AgentKind.wireName(): String = name.lowercase()

        /** The agent a wire name names, exactly; null for null, blank or a name this build does not know. */
        fun agentOf(wire: String?): AgentKind? = wire?.let { w -> AgentKind.entries.firstOrNull { it.wireName() == w } }
    }
}
