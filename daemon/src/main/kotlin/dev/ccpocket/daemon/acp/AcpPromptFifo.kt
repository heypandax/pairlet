package dev.ccpocket.daemon.acp

import dev.ccpocket.daemon.agent.AgentEvent
import dev.ccpocket.protocol.ImageData
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import java.util.concurrent.ConcurrentHashMap

/** One user prompt as Conversation handed it over: its text and the images riding with it. */
data class AcpPrompt(val text: String, val images: List<ImageData>) {
    /** `session/prompt` params. Images ride as ACP image blocks after the text ([ImageData] is already the
     *  Base64 + MIME pair a block holds); a prompt with images but blank text sends the images alone, never
     *  an empty text block. */
    fun sessionPromptParams(sessionId: String): JsonObject = buildJsonObject {
        put("sessionId", sessionId)
        putJsonArray("prompt") {
            if (text.isNotBlank() || images.isEmpty()) {
                addJsonObject { put("type", "text"); put("text", text) }
            }
            images.forEach { image ->
                addJsonObject { put("type", "image"); put("data", image.base64); put("mimeType", image.mediaType) }
            }
        }
    }
}

/**
 * The single-flight prompt FIFO every ACP agent needs (pure state — no IO).
 *
 * WHY IT LIVES HERE: an ACP agent refuses a second `session/prompt` while a turn runs (kimi: `-32600
 * turn.agent_busy`; dsh: `-32602 a prompt is already in flight`) — unlike the Claude CLI, which queues
 * stdin itself. Conversation hands every prompt straight to the backend (its ledger settles on the
 * UserReplay synthesized at prompt settle), so at most one prompt is in flight and the rest wait here,
 * flushed when the in-flight one settles (any stopReason, error included).
 *
 * THE GATE: prompts that arrive before the session can take them wait in the SAME queue, behind a gate the
 * client opens once the session is ready (for dsh: once its launch-time model/effort landed too). Entries
 * still queued when the process dies stay unsettled in Conversation's ledger, which re-injects them into
 * the fresh process — so [reset] loses nothing.
 *
 * Every decision and its in-flight mark happen under ONE lock: registering after the write left a window
 * where a racing send saw "idle" and double-sent — the very rejection the FIFO exists to prevent.
 */
class AcpPromptFifo(private val nextId: () -> Long) {
    private val lock = Mutex()
    private val queue = ArrayDeque<AcpPrompt>() // guarded by [lock]
    @Volatile private var gateOpen = false // guarded by [lock] for writes

    /** In-flight `session/prompt` request id → its text, replayed as the consumption receipt on settle (no
     *  ACP agent we drive sends a live `user_message_chunk`, so the settle IS the receipt). */
    private val inFlight = ConcurrentHashMap<Long, String>()

    /** A fresh process: nothing queued, nothing in flight, gate shut. */
    suspend fun reset() {
        lock.withLock { queue.clear(); gateOpen = false }
        inFlight.clear()
    }

    /** Queue [prompt] behind a shut gate or a running turn; otherwise reserve its request id (the caller
     *  writes it). Returns the reserved id, or null when queued. */
    suspend fun admit(prompt: AcpPrompt): Long? = lock.withLock {
        if (!gateOpen || inFlight.isNotEmpty()) {
            queue.addLast(prompt)
            null
        } else {
            reserveLocked(prompt.text)
        }
    }

    /** Reserve an id for a prompt that will settle WITHOUT being written (a refusal) — it still holds the
     *  FIFO until that settle, like an in-flight prompt. */
    suspend fun reserve(text: String): Long = lock.withLock { reserveLocked(text) }

    /**
     * Open the gate and reserve the head, all under the lock. [prepare] runs first, inside the lock, and may
     * veto the opening (returning false) — kimi binds the session id there so the binding and the release
     * are atomic; dsh refuses to open with no session. A head the session cannot take ([acceptable] false)
     * moves to [refused] and the next is tried.
     */
    suspend fun open(
        prepare: () -> Boolean,
        acceptable: (AcpPrompt) -> Boolean,
        refused: MutableList<AcpPrompt>,
    ): Pair<Long, AcpPrompt>? = lock.withLock {
        if (!prepare()) return null
        gateOpen = true
        takeLocked(acceptable, refused)
    }

    /** The in-flight prompt settled: reserve the next head, if the gate is open and nothing runs. */
    suspend fun next(acceptable: (AcpPrompt) -> Boolean, refused: MutableList<AcpPrompt>): Pair<Long, AcpPrompt>? =
        lock.withLock { takeLocked(acceptable, refused) }

    /** Settle the in-flight prompt [id]: its text, or null for an id that is not (or no longer) in flight. */
    fun settle(id: Long): String? = inFlight.remove(id)

    fun isInFlight(id: Long): Boolean = inFlight.containsKey(id)

    /** Everything still queued, removed in arrival order — for a session that will never take it. */
    suspend fun drain(): List<AcpPrompt> = lock.withLock { queue.toList().also { queue.clear() } }

    private fun reserveLocked(text: String): Long = nextId().also { inFlight[it] = text }

    private fun takeLocked(acceptable: (AcpPrompt) -> Boolean, refused: MutableList<AcpPrompt>): Pair<Long, AcpPrompt>? {
        if (!gateOpen || inFlight.isNotEmpty()) return null
        while (true) {
            val next = queue.removeFirstOrNull() ?: return null
            if (acceptable(next)) return reserveLocked(next.text) to next
            refused += next
        }
    }
}

/** A turn that failed for [why] while carrying the user's [text]: the consumption receipt (so the ledger
 *  entry goes away and a relaunch does not re-run it), the reason, and an error result. */
fun acpErrorTurn(text: String, why: String): List<AgentEvent> = listOf(
    AgentEvent.UserReplay(text),
    AgentEvent.AssistantText("⚠️ $why"),
    AgentEvent.TurnResult(finalText = null, usage = null, isError = true),
)

/** A failure with no prompt to settle: the reason and an error result. */
fun acpErrorEvents(message: String): List<AgentEvent> = listOf(
    AgentEvent.AssistantText("⚠️ $message"),
    AgentEvent.TurnResult(finalText = null, usage = null, isError = true),
)

/** The chat wording of an image prompt refused because [product] did not advertise image input (issue
 *  #377) — counts only, the image bytes reach neither the chat nor the log. */
fun acpImageRefusal(product: String, imageCount: Int): String =
    "not sent: $product did not advertise image input for this session, so nothing reached the agent. " +
        "Send the message again without the ${if (imageCount == 1) "image" else "$imageCount images"}."
