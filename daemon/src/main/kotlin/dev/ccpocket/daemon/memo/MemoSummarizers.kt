package dev.ccpocket.daemon.memo

import dev.ccpocket.protocol.VOICE_MEMO_AGENT_NONE

/**
 * The organiser adapters this daemon was built with, in preference order (the order [available] advertises
 * them in [dev.ccpocket.protocol.DaemonInfo.voiceMemoAgents]). Pluggable by construction: adding an organiser
 * is one more [MemoSummarizer] in the list, and an empty list — or a machine with none of the CLIs — is a
 * legal state in which memos are transcribed and nothing is organised.
 */
class MemoSummarizers(adapters: List<MemoSummarizer>) {
    val all: List<MemoSummarizer> = adapters.toList()

    init {
        // wiring errors, caught by tests rather than surfacing as a phone picking the wrong organiser
        require(all.map { it.agent }.distinct().size == all.size) { "duplicate memo organiser agent" }
        require(all.none { it.agent == VOICE_MEMO_AGENT_NONE || it.agent.isBlank() }) { "reserved memo organiser agent name" }
    }

    /** Agents whose adapter can launch right now (a LOCAL check per adapter — no model is started), in order. */
    fun available(): List<String> = all.filter { runCatching { it.isAvailable() }.getOrDefault(false) }.map { it.agent }

    /** The adapter serving [agent], available or not; null for an agent this daemon has no adapter for. */
    fun forAgent(agent: String): MemoSummarizer? = all.firstOrNull { it.agent == agent }

    companion object {
        /** No organiser at all: every memo ends transcribed. */
        val EMPTY = MemoSummarizers(emptyList())
    }
}
