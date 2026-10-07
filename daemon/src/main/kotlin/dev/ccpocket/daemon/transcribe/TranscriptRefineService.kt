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
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Voice input v2's proofreading step (design §4.4): one [TranscriptRefine] in, exactly one [TranscriptRefined] back
 * on the sink the request arrived on — like [TranscribeService]'s [dev.ccpocket.protocol.Transcript], the result only
 * reaches the device that dictated — unless the phone cancelled, in which case nothing is sent.
 *
 * - Which refiner: when the daemon knows the conversation's agent (it is live here), that agent's refiner and no
 *   other — none, or one whose CLI is missing, is [TranscriptRefineError.UNAVAILABLE]. Only for a conversation with no
 *   agent here yet (not started, or no longer live) does the phone's default agent ([TranscriptRefine.agentHint])
 *   pick, with the same rule. Deliberately no fallback between the two: the owner's quota is spent only on the agent
 *   they are using (a Codex user's dictation must not quietly run on their Claude account).
 * - One refine per conversation. A different request replaces the running one, whose requester is answered
 *   [TranscriptRefineError.SUPERSEDED]; the identical request re-sent (a reconnect) joins the running one instead.
 * - One model at a time across the whole daemon: a refine whose conversation differs from the running one waits for
 *   the single slot, at most [MAX_WAITING] of them; one more is answered [TranscriptRefineError.UNAVAILABLE] at once.
 *   Supersede and [AudioCancel] work on a waiting refine the same as on a running one.
 * - A hard limit of [HARD_TIMEOUT_MS], counting the wait for the slot too, after which the answer is
 *   [TranscriptRefineError.TIMEOUT] and the refiner's process is gone. The phone gives up on its own, earlier.
 * - [AudioCancel] for the running capture cancels it silently: nobody is waiting any more.
 * - Text longer than [TranscriptRefineLimits.MAX_TEXT_CHARS] is answered [TranscriptRefineError.UNAVAILABLE] without
 *   starting a model.
 * - Privacy: the log carries agent, duration, edit count and result code — never the transcript, the edits or the
 *   model's output (the same rule as dictation's whisper runs).
 */
class TranscriptRefineService(
    private val scope: CoroutineScope,
    private val refiners: TranscriptRefiners,
    /** The agent driving a live conversation; null when it is not live here (production: not started, or reaped). */
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
    private val runs = HashMap<String, Run>() // convoId -> refine in flight, waiting or running; guarded by [lock]

    /** The one refiner call allowed to run at a time, daemon-wide (one service per daemon). */
    private val slot = Semaphore(1)
    private var waiting = 0 // refines suspended on [slot]; guarded by [lock]

    /** [dev.ccpocket.protocol.DaemonInfo.transcriptRefineAgents]: the refiners that can launch right now. */
    fun advertisedAgents(): List<String> = refiners.available()

    /** True while any refine is waiting or running (the auto-update idle gate reads this). */
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
        // the conversation's known agent decides alone; the phone's hint only stands in when there is none
        val agent = agentOf(f.convoId) ?: TranscriptRefiners.agentOf(f.agentHint)
        val refiner = refiners.usableFor(agent) ?: return failure(f, TranscriptRefineError.UNAVAILABLE, null)
        run.agent = refiner.agent
        // nothing to correct, and nothing for a model to answer — and nothing to send either: autoSend stays false
        if (f.text.isBlank()) return TranscriptRefined(f.convoId, f.captureId, ok = true, text = f.text, agent = refiner.agent.wireName())
        // the hard limit covers the glossary read and the wait for the slot too: a slow disk or a queue must not
        // stretch the phone's wait past the 12 s either — a refine that runs out while queued is a TIMEOUT
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
            withSlot(f) { refiner.refine(f.text, f.locale, glossary, hardTimeoutMs) }
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

    /**
     * Runs [block] holding the daemon-wide [slot], waiting for it when another refine holds it — or answers
     * [RefineOutcome.Unavailable] at once when [MAX_WAITING] refines are already waiting. A refine cancelled while
     * waiting never held the permit and gives nothing back; one cancelled while running releases it only once
     * [block] has returned, i.e. after the refiner tore its process down.
     */
    private suspend fun withSlot(f: TranscriptRefine, block: suspend () -> RefineOutcome): RefineOutcome {
        if (!slot.tryAcquire()) {
            synchronized(lock) {
                if (waiting >= MAX_WAITING) {
                    log.info("${f.convoId} refine queue full ($MAX_WAITING waiting)")
                    return RefineOutcome.Unavailable
                }
                waiting++
            }
            try {
                slot.acquire()
            } finally {
                synchronized(lock) { waiting-- }
            }
        }
        try {
            return block()
        } finally {
            slot.release()
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
        /** The phone waits 10 s from its own clock, then falls back to the composer and ignores a late answer
         *  (review §11); 12 s only bounds the work and the process here. */
        const val HARD_TIMEOUT_MS = 12_000L

        /** Refines that may wait for the slot behind the running one; the next is turned away. */
        const val MAX_WAITING = 4

        /** How long shutdown waits for cancelled refines to finish tearing their processes down. */
        const val CLOSE_WAIT_MS = 5_000L
    }
}
