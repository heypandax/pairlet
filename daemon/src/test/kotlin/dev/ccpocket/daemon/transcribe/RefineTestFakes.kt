package dev.ccpocket.daemon.transcribe

import dev.ccpocket.protocol.AgentKind
import dev.ccpocket.protocol.TextEdit
import kotlinx.coroutines.CancellationException
import java.util.concurrent.atomic.AtomicInteger

/** A refiner that starts nothing: [behavior] decides each answer, and every call and cancellation is counted. */
class FakeTranscriptRefiner(
    override val agent: AgentKind = AgentKind.CLAUDE,
    @Volatile var available: Boolean = true,
    @Volatile var behavior: suspend (text: String) -> RefineOutcome = { RefineOutcome.Edits(REFINE_EDITS) },
) : TranscriptRefiner {
    val calls = AtomicInteger()
    val cancelled = AtomicInteger()
    @Volatile var lastLocale: String? = null
    @Volatile var lastGlossary: List<String>? = null
    @Volatile var lastTimeoutMs: Long = -1

    override fun isAvailable(): Boolean = available

    override suspend fun refine(text: String, locale: String?, glossary: List<String>, timeoutMs: Long): RefineOutcome {
        calls.incrementAndGet()
        lastLocale = locale
        lastGlossary = glossary
        lastTimeoutMs = timeoutMs
        try {
            return behavior(text)
        } catch (e: CancellationException) {
            cancelled.incrementAndGet()
            throw e
        }
    }
}

/** A dictation with two recognition errors, well inside every validator limit. */
const val REFINE_TEXT = "请帮我看一下 cloud code 的守护进程日志里有没有报错，再把推理强度 edit 调成 low，最后给用功写一段说明"
const val REFINE_CORRECTED = "请帮我看一下 Claude Code 的守护进程日志里有没有报错，再把推理强度 effort 调成 low，最后给用功写一段说明"
val REFINE_EDITS: List<TextEdit> = listOf(TextEdit("cloud code", "Claude Code"), TextEdit("edit", "effort"))
