package dev.ccpocket.daemon.transcribe

import dev.ccpocket.daemon.conversation.OutboundSink
import dev.ccpocket.daemon.transcribe.TranscriptRefiners.Companion.wireName
import dev.ccpocket.daemon.util.logger
import dev.ccpocket.protocol.AgentKind
import dev.ccpocket.protocol.AudioCancel
import dev.ccpocket.protocol.TranscriptRefine
import dev.ccpocket.protocol.TranscriptRefineError
import dev.ccpocket.protocol.TranscriptRefineLimits
import dev.ccpocket.protocol.TranscriptRefined
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Voice input v2's proofreading step (design §4.4): one [TranscriptRefine] in, exactly one [TranscriptRefined] back
 * on the sink the request arrived on — like [TranscribeService]'s [dev.ccpocket.protocol.Transcript], the result only
 * reaches the device that dictated — unless the phone cancelled, in which case nothing is sent.
 *
 * - Which refiner: the conversation's own agent when it has one here, else the phone's default agent
 *   ([TranscriptRefine.agentHint]) when THAT has one, else none → [TranscriptRefineError.UNAVAILABLE]. Deliberately no
 *   further fallback: the owner's quota is spent only on the agent they are using (a Codex user's dictation must not
 *   quietly run on their Claude account).
 * - One refine per conversation. A different request replaces the running one, whose requester is answered
 *   [TranscriptRefineError.SUPERSEDED]; the identical request re-sent (a reconnect) joins the running one instead.
 * - A hard limit of [HARD_TIMEOUT_MS] — the phone's own budget plus margin for its late-result rule — after which the
 *   answer is [TranscriptRefineError.TIMEOUT] and the refiner's process is gone.
 * - [AudioCancel] for the running capture cancels it silently: nobody is waiting any more.
 * - Text longer than [TranscriptRefineLimits.MAX_TEXT_CHARS] is answered [TranscriptRefineError.UNAVAILABLE] without
 *   starting a model.
 * - Privacy: the log carries agent, duration, edit count and result code — never the transcript, the edits or the
 *   model's output (the same rule as dictation's whisper runs).
 */
class TranscriptRefineService(
    private val scope: CoroutineScope,
    private val refiners: TranscriptRefiners,
    /** The agent driving a live conversation; null when it is not live here. */
    private val agentOf: suspend (convoId: String) -> AgentKind?,
    /** The glossary for a conversation's refine ([RefineGlossary]); production reads the project off disk. */
    private val glossaryOf: suspend (convoId: String) -> List<String>,
    private val hardTimeoutMs: Long = HARD_TIMEOUT_MS,
) {
    /** One conversation's refine in flight, and every sink waiting on it (more than one only for a re-send). */
    private class Run(val request: TranscriptRefine) {
        lateinit var job: Job
        val sinks = ArrayList<OutboundSink>() // guarded by the service lock
        @Volatile var agent: AgentKind? = null
    }

    private val log = logger("Refine")
    private val lock = Any()
    private val runs = HashMap<String, Run>() // convoId -> refine in flight; guarded by [lock]

    /** [dev.ccpocket.protocol.DaemonInfo.transcriptRefineAgents]: the refiners that can launch right now. */
    fun advertisedAgents(): List<String> = refiners.available()

    /** True while any refine is running (the auto-update idle gate reads this). */
    fun isRefining(): Boolean = synchronized(lock) { runs.isNotEmpty() }

    suspend fun onRefine(f: TranscriptRefine, sink: OutboundSink) {
        var superseded: Run? = null
        val run: Run
        // one critical section from lookup to install: a request racing another for the same conversation must end
        // with exactly one of them running and the other one answered
        synchronized(lock) {
            val running = runs[f.convoId]
            if (running != null && running.request == f) {
                if (running.sinks.none { it === sink }) running.sinks += sink
                log.info("${f.convoId} refine re-sent while running — sharing the running refine")
                return
            }
            if (running != null) {
                runs.remove(f.convoId)
                running.job.cancel()
                superseded = running
            }
            run = Run(f)
            run.sinks += sink
            run.job = scope.launch(CoroutineName("refine-${f.convoId}"), start = CoroutineStart.LAZY) { execute(run) }
            run.job.invokeOnCompletion { synchronized(lock) { if (runs[f.convoId] === run) runs.remove(f.convoId) } }
            runs[f.convoId] = run
        }
        superseded?.let { old ->
            log.info("${f.convoId} refine agent=${old.agent?.wireName() ?: "none"} result=${TranscriptRefineError.SUPERSEDED}")
            val reply = failure(old.request, TranscriptRefineError.SUPERSEDED, old.agent)
            synchronized(lock) { old.sinks.toList() }.forEach { emitQuietly(it, reply) }
        }
        run.job.start()
    }

    /** The phone cancelled this capture: drop its refine and send nothing. Another capture's refine is left alone. */
    fun onCancel(f: AudioCancel) {
        val cancelled = synchronized(lock) {
            val running = runs[f.convoId]?.takeIf { it.request.captureId == f.captureId } ?: return
            runs.remove(f.convoId)
            running
        }
        cancelled.job.cancel()
        log.info("${f.convoId} refine cancelled")
    }

    /** Daemon shutdown: cancel every refine and wait for its process teardown. */
    suspend fun close() {
        val jobs = synchronized(lock) { runs.values.map { it.job }.also { runs.clear() } }
        jobs.forEach { it.cancel() }
        withTimeoutOrNull(CLOSE_WAIT_MS) { jobs.joinAll() }
    }

    private suspend fun execute(run: Run) {
        val f = run.request
        val t0 = System.nanoTime()
        val reply = try {
            produce(run)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn("${f.convoId} refine failed unexpectedly: ${e::class.simpleName}")
            failure(f, TranscriptRefineError.FAILED, run.agent)
        }
        val ms = (System.nanoTime() - t0) / 1_000_000
        val sinks = synchronized(lock) {
            // superseded or cancelled meanwhile: whoever removed this run already settled (or silenced) its requesters
            if (runs[f.convoId] !== run) return
            runs.remove(f.convoId)
            run.sinks.toList()
        }
        log.info(
            "${f.convoId} refine agent=${reply.agent ?: "none"} result=${reply.error ?: "ok"} " +
                "edits=${reply.edits.size} in ${ms}ms",
        )
        sinks.forEach { emitQuietly(it, reply) }
    }

    private suspend fun produce(run: Run): TranscriptRefined {
        val f = run.request
        if (f.text.length > TranscriptRefineLimits.MAX_TEXT_CHARS) return failure(f, TranscriptRefineError.UNAVAILABLE, null)
        val refiner = refiners.usableFor(agentOf(f.convoId))
            ?: refiners.usableFor(TranscriptRefiners.agentOf(f.agentHint))
            ?: return failure(f, TranscriptRefineError.UNAVAILABLE, null)
        run.agent = refiner.agent
        // nothing to correct, and nothing for a model to answer
        if (f.text.isBlank()) return TranscriptRefined(f.convoId, f.captureId, ok = true, text = f.text, agent = refiner.agent.wireName(), autoSend = true)
        // the hard limit covers the glossary read too: it walks the project on disk, and a slow disk must not stretch
        // the phone's wait past the 12 s either
        // the validator's glossary rule checks against the same list the refiner was handed
        var glossary = emptyList<String>()
        val outcome = withTimeoutOrNull(hardTimeoutMs) {
            glossary = try {
                glossaryOf(f.convoId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                emptyList()
            }
            refiner.refine(f.text, f.locale, glossary, hardTimeoutMs)
        } ?: RefineOutcome.TimedOut
        return when (outcome) {
            is RefineOutcome.Edits -> when (val checked = TranscriptEditValidator.check(f.text, outcome.edits, glossary)) {
                is TranscriptEditValidator.Result.Accepted -> {
                    log.info(
                        "${f.convoId} refine edits applied=${checked.edits.size} " +
                            "dropped=${outcome.edits.size - checked.edits.size} autoSend=${checked.autoSend}",
                    )
                    TranscriptRefined(
                        f.convoId, f.captureId, ok = true, text = checked.text, edits = checked.edits,
                        agent = refiner.agent.wireName(), autoSend = checked.autoSend,
                    )
                }
                is TranscriptEditValidator.Result.Rejected -> {
                    log.info("${f.convoId} refine edits rejected rule=${checked.rule} proposed=${outcome.edits.size}")
                    failure(f, TranscriptRefineError.INVALID, refiner.agent)
                }
            }
            RefineOutcome.Unavailable -> failure(f, TranscriptRefineError.UNAVAILABLE, refiner.agent)
            RefineOutcome.Failed -> failure(f, TranscriptRefineError.FAILED, refiner.agent)
            RefineOutcome.TimedOut -> failure(f, TranscriptRefineError.TIMEOUT, refiner.agent)
        }
    }

    private fun failure(f: TranscriptRefine, code: String, agent: AgentKind?) =
        TranscriptRefined(f.convoId, f.captureId, ok = false, agent = agent?.wireName(), error = code)

    /** A requester whose connection went away just misses its answer; that is not the refine failing. */
    private suspend fun emitQuietly(sink: OutboundSink, reply: TranscriptRefined) {
        try {
            sink.emit(reply)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.info("${reply.convoId} refine reply not delivered: ${e::class.simpleName}")
        }
    }

    companion object {
        /** The phone waits 8 s before falling back to the composer and applies a late result for 7 s more
         *  (design §3.4); 12 s bounds the work well inside that window. */
        const val HARD_TIMEOUT_MS = 12_000L

        /** How long shutdown waits for cancelled refines to finish tearing their processes down. */
        const val CLOSE_WAIT_MS = 5_000L
    }
}
