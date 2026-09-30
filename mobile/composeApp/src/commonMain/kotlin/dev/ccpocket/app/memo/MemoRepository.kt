package dev.ccpocket.app.memo

import dev.ccpocket.protocol.ToDaemon
import dev.ccpocket.protocol.VoiceMemoAudio
import dev.ccpocket.protocol.VoiceMemoCancel
import dev.ccpocket.protocol.VoiceMemoHash
import dev.ccpocket.protocol.VoiceMemoIds
import dev.ccpocket.protocol.VoiceMemoInputKind
import dev.ccpocket.protocol.VoiceMemoLimits
import dev.ccpocket.protocol.VoiceMemoStage
import dev.ccpocket.protocol.VoiceMemoStart
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.io.encoding.Base64

/**
 * The memo data layer: one actor that owns all state. Every [MemoAction] and every port callback becomes a
 * [MemoEvent] on one channel; the actor stamps it with the clock, runs the pure [MemoReducer], publishes the
 * projected [MemoUiState] and starts the effects. Effects never touch the model — their results come back as
 * events — so ordering questions reduce to the order of one queue.
 *
 * Store calls run on [io] through a single FIFO lane (a commit issued before another is written before it);
 * audio reads, Base64 and hashing also run on [io]. Link, recorder and gateway calls run in their own
 * coroutines, keyed where they must be cancellable (the upload, timers, the ticker).
 *
 * [receiptTimeoutMs] bounds the wait for one dispatched item's receipt; [onError] sees a failure of the actor
 * itself (a bug) — the actor keeps running.
 */
class DefaultVoiceMemoRepository(
    private val scope: CoroutineScope,
    private val store: VoiceMemoStore,
    private val link: MemoLink,
    private val recorder: MemoRecorder,
    private val gateway: MemoSessionGateway,
    private val prefs: MemoPrefs,
    private val clock: MemoClock,
    newId: () -> String = { VoiceMemoIds.newId() },
    newPromptId: () -> String,
    private val io: CoroutineDispatcher = Dispatchers.Default,
    receiptTimeoutMs: Long = DEFAULT_RECEIPT_TIMEOUT_MS,
    private val onError: (Throwable) -> Unit = {},
) : VoiceMemoRepository {

    private val reducer = MemoReducer(newId, newPromptId, receiptTimeoutMs)
    private val inbox = Channel<MemoEvent>(Channel.UNLIMITED)
    private val storeLane = Channel<suspend () -> Unit>(Channel.UNLIMITED)
    private val _state = MutableStateFlow(MemoUiState())
    private val _levels = MutableStateFlow(0f)

    // Actor-confined.
    private var model = MemoModel()
    private val jobs = HashMap<String, Job>()

    override val state: StateFlow<MemoUiState> = _state.asStateFlow()
    override val levels: StateFlow<Float> = _levels.asStateFlow()

    init {
        scope.launch(io) {
            for (job in storeLane) {
                try {
                    job()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    onError(e)
                }
            }
        }
        scope.launch { for (e in inbox) step(e) }
        scope.launch { link.readiness.collect { post(MemoEvent.Readiness(it)) } }
        scope.launch { link.states.collect { post(MemoEvent.Remote(it)) } }
        scope.launch { recorder.interruptions.collect { post(MemoEvent.RecorderInterrupted) } }
        scope.launch {
            recorder.levels.collect { level ->
                _levels.value = if (_state.value.capture.phase == MemoCapturePhase.RECORDING) level.coerceIn(0f, 1f) else 0f
            }
        }
        scope.launch { gateway.receipts.collect { post(MemoEvent.Receipt(it)) } }
        scope.launch { gateway.stops.collect { post(MemoEvent.DispatchStopped(it)) } }
    }

    override fun accept(action: MemoAction) {
        post(MemoEvent.Act(action))
    }

    private fun post(e: MemoEvent) {
        inbox.trySend(e)
    }

    private fun step(e: MemoEvent) {
        try {
            val r = reducer.reduce(model, e, MemoNow(clock.monotonicMs(), clock.nowMs()))
            model = r.model
            _state.value = model.project()
            if (model.capture.phase != MemoCapturePhase.RECORDING) _levels.value = 0f
            r.effects.forEach(::run)
        } catch (t: CancellationException) {
            throw t
        } catch (t: Exception) {
            onError(t)
        }
    }

    // ── effects ───────────────────────────────────────────────────────────────────────────────────────

    private fun run(e: MemoEffect) {
        when (e) {
            is MemoEffect.LoadList -> lane {
                val rows = read { store.list(e.scope, e.offset, e.limit) }
                val count = read { store.count(e.scope) }
                post(MemoEvent.ListLoaded(e.token, e.scope, e.append, rows, count))
            }
            is MemoEffect.LoadConsent -> scope.launch(io) {
                post(MemoEvent.ConsentLoaded(e.scope, runCatching { prefs.consentAccepted(e.scope) }.getOrDefault(false)))
            }
            is MemoEffect.AcceptConsent -> scope.launch(io) { runCatching { prefs.acceptConsent(e.scope) } }
            is MemoEffect.StartRecorder -> scope.launch {
                val result = try {
                    recorder.start()
                } catch (c: CancellationException) {
                    throw c
                } catch (x: Exception) {
                    MemoRecorderStart.Failed
                }
                post(MemoEvent.RecorderStarted(e.gen, result))
            }
            is MemoEffect.StopAndSave -> scope.launch {
                val recording = try {
                    recorder.stop()
                } catch (c: CancellationException) {
                    throw c
                } catch (x: Exception) {
                    null
                }
                if (recording == null) {
                    post(MemoEvent.AudioSaved(e.gen, e.memoId, null, 0))
                } else {
                    val w = laneCall { write { store.writeAudio(e.scope, e.memoId, recording.bytes, recording.mediaType) } }
                    post(MemoEvent.AudioSaved(e.gen, e.memoId, w, recording.durationMs))
                }
            }
            MemoEffect.CancelRecorder -> runCatching { recorder.cancel() }
            MemoEffect.OpenMicSettings -> runCatching { recorder.openSettings() }
            MemoEffect.StartTicker -> keyed(TICKER) {
                while (true) {
                    delay(1_000)
                    post(MemoEvent.Tick)
                }
            }
            MemoEffect.StopTicker -> cancel(TICKER)
            is MemoEffect.StartTimer -> keyed(e.key) {
                delay(e.delayMs)
                post(e.event)
            }
            is MemoEffect.CancelTimer -> cancel(e.key)
            is MemoEffect.LoadDoc -> lane { post(MemoEvent.DocLoaded(e.memoId, read { store.read(e.scope, e.memoId) })) }
            is MemoEffect.Commit -> lane {
                post(MemoEvent.Committed(e.memoId, e.seq, e.document.revision, write { store.commit(e.document, e.expectedRevision) }))
            }
            is MemoEffect.Reread -> lane { post(MemoEvent.Reread(e.memoId, read { store.read(e.scope, e.memoId) })) }
            is MemoEffect.DeleteAudio -> lane { write { store.deleteAudio(e.scope, e.memoId) } }
            is MemoEffect.Upload -> keyed(uploadKey(e.memoId), io) { upload(e) }
            is MemoEffect.CancelUpload -> cancel(uploadKey(e.memoId))
            is MemoEffect.HashTranscript -> scope.launch(io) {
                post(MemoEvent.TranscriptHashed(e.memoId, e.token, VoiceMemoHash.sha256Hex(e.text.encodeToByteArray())))
            }
            is MemoEffect.Send -> {
                val body: suspend CoroutineScope.() -> Unit = {
                    post(MemoEvent.SendFinished(e.memoId, e.token, e.kind, send(e.scope, e.generation, e.frame)))
                }
                // The organise-transcript start is this attempt's upload: cancelled with it.
                if (e.kind == SendKind.START) keyed(uploadKey(e.memoId), block = body) else scope.launch(block = body)
            }
            is MemoEffect.Delete -> scope.launch { delete(e) }
            is MemoEffect.Startup -> scope.launch { startup(e.scope) }
            is MemoEffect.LoadCatalog -> post(MemoEvent.CatalogLoaded(e.project, runCatching { gateway.catalog(e.project) }.getOrNull()))
            is MemoEffect.Resolve ->
                post(MemoEvent.Resolved(e.batchId, runCatching { gateway.resolved(e.lease) }.getOrNull(), e.final))
            is MemoEffect.Enter -> scope.launch {
                val result = try {
                    gateway.enter(e.target, e.batchId)
                } catch (c: CancellationException) {
                    throw c
                } catch (x: Exception) {
                    MemoEnterResult.Failed("exception")
                }
                post(MemoEvent.Entered(e.batchId, result))
            }
            is MemoEffect.CheckHolds ->
                post(MemoEvent.HoldsChecked(e.batchId, e.promptId, runCatching { gateway.holds(e.lease) }.getOrDefault(false)))
            is MemoEffect.Submit -> scope.launch {
                // An exception says nothing about whether the prompt reached a writer: treat it as unknown.
                val result = try {
                    gateway.submit(e.prompt)
                } catch (c: CancellationException) {
                    throw c
                } catch (x: Exception) {
                    MemoSubmitResult.Unknown
                }
                post(MemoEvent.Submitted(e.batchId, e.prompt.promptId, result))
            }
            is MemoEffect.ShowTarget -> runCatching { gateway.show(e.target) }
            is MemoEffect.ShowMemo -> runCatching { gateway.showMemo(e.memoId) }
        }
    }

    /**
     * Start frame, then every chunk in index order — each chunk is its own Base64 of [VoiceMemoLimits.CHUNK_BYTES]
     * raw bytes. The first send that is not [MemoLinkSend.Written] ends the upload; nothing is retried here.
     */
    private suspend fun upload(e: MemoEffect.Upload) {
        val read = laneCall { read { store.readAudio(e.scope, e.memoId) } }
        val bytes = (read as? MemoRead.Found)?.value
        if (bytes == null) {
            post(MemoEvent.UploadFinished(e.memoId, e.token, UploadOutcome.AudioUnavailable))
            return
        }
        val size = bytes.size.toLong()
        val chunks = VoiceMemoLimits.chunkCountFor(size)
        if (size == 0L || size > VoiceMemoLimits.MAX_AUDIO_BYTES || chunks > VoiceMemoLimits.MAX_CHUNKS) {
            post(MemoEvent.UploadFinished(e.memoId, e.token, UploadOutcome.TooLarge))
            return
        }
        val hash = VoiceMemoHash.sha256Hex(bytes)
        if (hash != e.audio.sha256 || size != e.audio.byteLength) {
            // The stored audio is not what the document references: never upload something else under its hash.
            post(MemoEvent.UploadFinished(e.memoId, e.token, UploadOutcome.AudioUnavailable))
            return
        }
        val start = VoiceMemoStart(
            memoId = e.memoId, attemptId = e.attemptId, inputKind = VoiceMemoInputKind.AUDIO, agent = e.agent, mediaType = e.audio.mediaType,
            durationMs = e.durationMs, byteLength = size, sha256 = hash, chunkCount = chunks,
        )
        send(e.scope, e.generation, start).let {
            if (it != MemoLinkSend.Written) {
                post(MemoEvent.UploadFinished(e.memoId, e.token, UploadOutcome.LinkFailed(it)))
                return
            }
        }
        for (index in 0 until chunks) {
            val from = index * VoiceMemoLimits.CHUNK_BYTES
            val to = minOf(bytes.size, from + VoiceMemoLimits.CHUNK_BYTES)
            val chunk = VoiceMemoAudio(e.memoId, e.attemptId, index, Base64.Default.encode(bytes, from, to))
            if (index == 0) post(MemoEvent.UploadFirstChunk(e.memoId, e.token))
            val sent = send(e.scope, e.generation, chunk)
            if (sent != MemoLinkSend.Written) {
                post(MemoEvent.UploadFinished(e.memoId, e.token, UploadOutcome.LinkFailed(sent)))
                return
            }
        }
        post(MemoEvent.UploadFinished(e.memoId, e.token, UploadOutcome.Completed))
    }

    /** Tombstone first (durable), then a best-effort cancel of an attempt still running, then the content. */
    private suspend fun delete(e: MemoEffect.Delete) {
        val cancel = e.cancel ?: laneCall {
            val doc = (read { store.read(e.scope, e.memoId) } as? MemoRead.Found)?.value
            doc?.processing?.takeIf { !it.accepted && it.stage in QUERYABLE_STAGES && it.stage != VoiceMemoStage.CANCELLED }
                ?.let { VoiceMemoCancel(e.memoId, it.attemptId) }
        }
        val marked = laneCall { write { store.markDeleted(e.scope, e.memoId) } }
        if (marked is MemoWrite.Durable) {
            cancel?.let { send(e.scope, e.generation, it) }
            laneCall { write { store.purgeDeleted(e.scope, e.memoId) } }
        }
        post(MemoEvent.Deleted(e.memoId, marked))
    }

    /** Finishes purges an earlier run left behind, then names the memos still holding a `sending` record. */
    private suspend fun startup(scope: MemoScope) {
        val withSending = laneCall {
            val purges = try {
                store.pendingPurges(scope)
            } catch (c: CancellationException) {
                throw c
            } catch (x: Exception) {
                emptyList()
            }
            purges.forEach { write { store.purgeDeleted(scope, it) } }
            (read { store.list(scope, 0, Int.MAX_VALUE) } as? MemoRead.Found)?.value.orEmpty()
                .filter { it.sending > 0 }.map { it.memoId }
        }
        post(MemoEvent.StartupScanned(scope, withSending))
    }

    private suspend fun send(scope: MemoScope, generation: Int, frame: ToDaemon): MemoLinkSend = try {
        link.send(scope, generation, frame)
    } catch (c: CancellationException) {
        throw c
    } catch (x: Exception) {
        MemoLinkSend.Indeterminate
    }

    // ── plumbing ──────────────────────────────────────────────────────────────────────────────────────

    /** Enqueue from the actor: store work runs in exactly the order the reducer asked for it. */
    private fun lane(block: suspend () -> Unit) {
        storeLane.trySend(block)
    }

    private suspend fun <T> laneCall(block: suspend () -> T): T {
        val done = CompletableDeferred<T>()
        storeLane.send {
            try {
                done.complete(block())
            } catch (e: Throwable) {
                done.completeExceptionally(e)
                if (e is CancellationException) throw e
            }
        }
        return done.await()
    }

    private suspend fun <T> read(block: suspend () -> MemoRead<T>): MemoRead<T> = try {
        block()
    } catch (c: CancellationException) {
        throw c
    } catch (x: Exception) {
        MemoRead.Unreadable(x::class.simpleName ?: "exception")
    }

    /** A write that threw may or may not have landed. */
    private suspend fun <T> write(block: suspend () -> MemoWrite<T>): MemoWrite<T> = try {
        block()
    } catch (c: CancellationException) {
        throw c
    } catch (x: Exception) {
        MemoWrite.Indeterminate
    }

    private fun keyed(key: String, dispatcher: CoroutineDispatcher? = null, block: suspend CoroutineScope.() -> Unit) {
        jobs.remove(key)?.cancel()
        jobs[key] = if (dispatcher != null) scope.launch(dispatcher, block = block) else scope.launch(block = block)
    }

    private fun cancel(key: String) {
        jobs.remove(key)?.cancel()
    }

    private companion object {
        const val TICKER = "ticker"
        fun uploadKey(memoId: String) = "upload:$memoId"
    }
}
