package dev.ccpocket.daemon.agent

import dev.ccpocket.protocol.Frame

/**
 * The two channels an [AgentBackend] needs once its process is live: [writeLine] pushes a raw line to
 * the agent's stdin; [emit] sends a protocol [Frame] to the phone. Rebound on every relaunch via
 * [AgentBackend.attach] so the backend never holds a stale process/sink.
 */
class AgentIo(
    val writeLine: suspend (String) -> Unit,
    val emit: suspend (Frame) -> Unit,
)
