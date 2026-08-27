package dev.ccpocket.daemon.agent

import dev.ccpocket.protocol.Frame

/**
 * The channels an [AgentBackend] needs once its process is live: [writeLine] pushes a raw line to the
 * agent's stdin; [emit] sends a protocol [Frame] to the phone. Rebound on every relaunch via
 * [AgentBackend.attach] so the backend never holds a stale process/sink.
 */
class AgentIo(
    val writeLine: suspend (String) -> Unit,
    val emit: suspend (Frame) -> Unit,
    /**
     * Feed a line into the process's stdout stream as if the agent had printed it (issue #255).
     *
     * Every backend before dsh spoke newline-delimited JSON over the child's stdout, so the Conversation
     * pump reading that one channel was the whole event path. dsh's live events arrive on a WebSocket
     * INSTEAD — its process stdout carries only server logs. Rather than give the pump a second source
     * (and with it a second ordering domain, since the pump is what assigns the monotonic `seq` and it
     * does so lock-free precisely because it is single-threaded), the WebSocket reader funnels its frames
     * back through the SAME channel. Ordering, sequencing and lifecycle stay exactly as they were.
     *
     * A write after the process died is dropped, not thrown: the channel closes on exit and a late frame
     * has nowhere meaningful to go. Default no-op for the stdio backends, which never need it.
     */
    val inject: suspend (String) -> Unit = {},
    /** Ask the owner to end this process after a provider-defined stable boundary. The callback launches
     * the bounded graceful-shutdown ladder off the stdout pump; default no-op keeps backend unit tests and
     * long-running providers unchanged. Codex uses it after turn/completed to release its cross-app writer. */
    val requestProcessExit: () -> Unit = {},
)
