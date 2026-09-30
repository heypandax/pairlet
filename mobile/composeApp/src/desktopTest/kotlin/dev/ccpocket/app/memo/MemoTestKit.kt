@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package dev.ccpocket.app.memo

import dev.ccpocket.protocol.AgentKind
import dev.ccpocket.protocol.VOICE_MEMO_AGENT_CLAUDE
import dev.ccpocket.protocol.ToDaemon
import dev.ccpocket.protocol.VoiceMemoLimits
import dev.ccpocket.protocol.VoiceMemoMetrics
import dev.ccpocket.protocol.VoiceMemoResult
import dev.ccpocket.protocol.VoiceMemoStage
import dev.ccpocket.protocol.VoiceMemoState
import dev.ccpocket.protocol.VoiceMemoTodoSuggestion
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent

// In-memory ports for the memo data layer tests. Nothing here touches the developer's files or a network.

internal fun uuid(n: Int): String = "00000000-0000-4000-8000-" + n.toString(16).padStart(12, '0')

internal val SCOPE = MemoScope("binding-a", "device-1")
internal val OTHER_SCOPE = MemoScope("binding-b", "device-1")

/** voice-memos/ in a map. Failures are switchable; an Indeterminate write stores the bytes but reports doubt. */
internal class InMemoryMemoFiles : MemoFiles {
    val files = LinkedHashMap<String, ByteArray>()
    var failListDirs = false
    var failListFiles = false
    var failRead: (String, String) -> Boolean = { _, _ -> false }
    var writeResult: (String, String) -> MemoWrite<Unit> = { _, _ -> MemoWrite.Durable(Unit) }
    var deleteResult: (String, String) -> MemoWrite<Unit> = { _, _ -> MemoWrite.Durable(Unit) }

    override fun read(dir: String, name: String): MemoRead<ByteArray> = synchronized(this) {
        if (failRead(dir, name)) return MemoRead.Unreadable("io")
        files["$dir/$name"]?.let { MemoRead.Found(it.copyOf()) } ?: MemoRead.Missing
    }

    override fun write(dir: String, name: String, bytes: ByteArray): MemoWrite<Unit> = synchronized(this) {
        val r = writeResult(dir, name)
        if (r !is MemoWrite.NotWritten) files["$dir/$name"] = bytes.copyOf()
        r
    }

    override fun delete(dir: String, name: String): MemoWrite<Unit> = synchronized(this) {
        val r = deleteResult(dir, name)
        if (r !is MemoWrite.NotWritten) files.remove("$dir/$name")
        r
    }

    override fun listDirs(dir: String): List<String>? = synchronized(this) {
        if (failListDirs) return null
        val prefix = if (dir.isEmpty()) "" else "$dir/"
        files.keys.filter { it.startsWith(prefix) }.map { it.removePrefix(prefix) }.filter { '/' in it }
            .map { it.substringBefore('/') }.distinct().sorted()
    }

    override fun listFiles(dir: String): List<String>? = synchronized(this) {
        if (failListFiles) return null
        files.keys.filter { it.startsWith("$dir/") && '/' !in it.removePrefix("$dir/") }.map { it.removePrefix("$dir/") }.sorted()
    }

    override fun deleteDir(dir: String): MemoWrite<Unit> = synchronized(this) {
        files.keys.filter { it.startsWith("$dir/") }.forEach { files.remove(it) }
        MemoWrite.Durable(Unit)
    }

    fun has(scope: MemoScope, memoId: String, name: String) = synchronized(this) {
        files.containsKey(DefaultVoiceMemoStore.memoDir(scope, memoId) + "/" + name)
    }
}

/** The real store over [files], with an ordering log and switchable commit / audio failures. */
internal class RecordingStore(val files: InMemoryMemoFiles, private val log: MutableList<String>) : VoiceMemoStore {
    private val real = DefaultVoiceMemoStore(files)
    var commitResult: ((MemoDocument) -> MemoWrite<Unit>?)? = null
    var audioResult: MemoWrite<AudioRef>? = null
    val commits = mutableListOf<MemoDocument>()

    override suspend fun list(scope: MemoScope, offset: Int, limit: Int) = real.list(scope, offset, limit)
    override suspend fun count(scope: MemoScope) = real.count(scope)
    var reads = 0
    override suspend fun read(scope: MemoScope, memoId: String): MemoRead<MemoDocument> {
        reads++
        return real.read(scope, memoId)
    }
    override suspend fun readAudio(scope: MemoScope, memoId: String) = real.readAudio(scope, memoId)

    override suspend fun writeAudio(scope: MemoScope, memoId: String, bytes: ByteArray, mediaType: String): MemoWrite<AudioRef> {
        audioResult?.let { return it }
        return real.writeAudio(scope, memoId, bytes, mediaType)
    }

    override suspend fun commit(document: MemoDocument, expectedRevision: Long?): MemoWrite<Unit> {
        val forced = commitResult?.invoke(document)
        val r = forced ?: real.commit(document, expectedRevision)
        synchronized(log) {
            log += "commit:" + document.dispatches.joinToString(",") { "${it.promptId}=${it.state}" } +
                (if (r is MemoWrite.Durable) "" else ":failed")
        }
        if (r is MemoWrite.Durable) commits += document
        return r
    }

    override suspend fun deleteAudio(scope: MemoScope, memoId: String) = real.deleteAudio(scope, memoId)
    override suspend fun markDeleted(scope: MemoScope, memoId: String) = real.markDeleted(scope, memoId)
    override suspend fun purgeDeleted(scope: MemoScope, memoId: String) = real.purgeDeleted(scope, memoId)
    override suspend fun pendingPurges(scope: MemoScope) = real.pendingPurges(scope)

    /** What the store holds now (bypassing the log). */
    suspend fun stored(scope: MemoScope, memoId: String): MemoDocument? = (real.read(scope, memoId) as? MemoRead.Found)?.value
}

internal class FakeLink(initial: MemoReadiness, private val scheduler: TestCoroutineScheduler) : MemoLink {
    override val readiness = MutableStateFlow(initial)
    override val states = MutableSharedFlow<MemoRemoteState>(extraBufferCapacity = 64)
    val sent = mutableListOf<Pair<Int, ToDaemon>>()
    val sentAt = mutableListOf<Long>()
    var result: (ToDaemon) -> MemoLinkSend = { MemoLinkSend.Written }
    /** A gate the send waits on before it counts as sent (a slow socket). */
    var hold: ((ToDaemon) -> CompletableDeferred<Unit>?)? = null

    override suspend fun send(scope: MemoScope, generation: Int, frame: ToDaemon): MemoLinkSend {
        hold?.invoke(frame)?.await()
        val r = result(frame)
        sent += generation to frame
        sentAt += scheduler.currentTime
        return r
    }

    inline fun <reified T : ToDaemon> frames(): List<T> = sent.map { it.second }.filterIsInstance<T>()
}

internal class FakeRecorder : MemoRecorder {
    override val levels = MutableSharedFlow<Float>(extraBufferCapacity = 8)
    override val interruptions = MutableSharedFlow<Unit>(extraBufferCapacity = 8)
    var startResult: MemoRecorderStart = MemoRecorderStart.Started
    var startGate: CompletableDeferred<MemoRecorderStart>? = null
    var recording: MemoRecording? = MemoRecording(ByteArray(1000) { it.toByte() }, VoiceMemoLimits.AUDIO_MEDIA_TYPE, 12_000)
    var starts = 0
    var stops = 0
    var cancels = 0
    var settingsOpened = 0

    override suspend fun start(): MemoRecorderStart {
        starts++
        return startGate?.await() ?: startResult
    }

    override suspend fun stop(): MemoRecording? {
        stops++
        return recording
    }

    override fun cancel() {
        cancels++
    }

    override fun openSettings() {
        settingsOpened++
    }
}

internal class FakeGateway(private val log: MutableList<String>) : MemoSessionGateway {
    override val receipts = MutableSharedFlow<MemoPromptReceipt>(extraBufferCapacity = 64)
    override val stops = MutableSharedFlow<MemoDispatchStop>(extraBufferCapacity = 8)
    /** "Recent" rows. */
    var rows: List<MemoTargetRow> = emptyList()
    var projects: List<MemoProjectRow> = emptyList()
    var sessions: Map<String, List<MemoTargetRow>> = emptyMap()
    var agents: List<AgentKind> = listOf(AgentKind.CLAUDE, AgentKind.CODEX)
    var mode: String? = "acceptEdits"
    var catalogThrows = false
    val catalogRequests = mutableListOf<String?>()
    var resolve: (MemoTargetLease) -> MemoTarget? = { it.target }
    val resolveCalls = mutableListOf<MemoTargetLease>()
    var enterResult: ((MemoTarget, String) -> MemoEnterResult)? = null
    var holds = true
    var submitResult: (ConfirmedMemoPrompt) -> MemoSubmitResult = { MemoSubmitResult.AwaitingReceipt }
    val submitted = mutableListOf<ConfirmedMemoPrompt>()
    val entered = mutableListOf<Pair<MemoTarget, String>>()
    val shown = mutableListOf<MemoTarget>()
    val shownMemos = mutableListOf<String>()
    var convo = "convo-1"

    override suspend fun enter(target: MemoTarget, batchId: String): MemoEnterResult {
        entered += target to batchId
        return enterResult?.invoke(target, batchId)
            ?: MemoEnterResult.Entered(MemoTargetLease(target, convo, batchId, connectionGeneration = 1))
    }

    override fun holds(lease: MemoTargetLease): Boolean = holds

    /** When set, submit waits here — a receipt can then arrive before submit returns. */
    var submitGate: CompletableDeferred<MemoSubmitResult>? = null

    override suspend fun submit(prompt: ConfirmedMemoPrompt): MemoSubmitResult {
        synchronized(log) { log += "submit:${prompt.promptId}" }
        submitted += prompt
        submitGate?.let { return it.await() }
        return submitResult(prompt)
    }

    override fun show(target: MemoTarget) {
        shown += target
    }

    override fun showMemo(memoId: String) {
        shownMemos += memoId
    }

    override fun catalog(project: String?): MemoTargetCatalog {
        catalogRequests += project
        if (catalogThrows) error("catalog unavailable")
        return MemoTargetCatalog(
            status = MemoListStatus.READY,
            recent = rows,
            projects = projects,
            project = project?.let { p ->
                MemoProjectSessions(p, projects.firstOrNull { it.workdir == p }?.name ?: p, MemoListStatus.READY, sessions[p].orEmpty())
            },
            newSession = MemoNewSessionOptions(agents, agents.firstOrNull() ?: AgentKind.CLAUDE, mode),
        )
    }

    override fun resolved(lease: MemoTargetLease): MemoTarget? {
        resolveCalls += lease
        return resolve(lease)
    }
}

internal class FakePrefs(var accepted: Boolean = true) : MemoPrefs {
    override fun consentAccepted(scope: MemoScope) = accepted
    override fun acceptConsent(scope: MemoScope) {
        accepted = true
    }
}

internal class TestClock(private val scheduler: TestCoroutineScheduler) : MemoClock {
    override fun nowMs(): Long = 1_700_000_000_000 + scheduler.currentTime
    override fun monotonicMs(): Long = scheduler.currentTime
}

internal val TARGET = MemoTarget(SCOPE.bindingId, "session-1", "/work/app", project = "app", title = "Build")
internal val TARGET_ROW = MemoTargetRow(TARGET, MemoTargetStatus.IDLE)

/** A ready computer. By default it can organise with Claude, like most; pass [organizer] = null for one without
 *  any organiser (transcribe only). */
internal fun ready(
    online: Boolean = true,
    generation: Int = 1,
    scope: MemoScope? = SCOPE,
    organizer: String? = VOICE_MEMO_AGENT_CLAUDE,
) = MemoReadiness(
    featureOn = true, scope = scope, computerName = "mac", online = online, block = MemoBlock.NONE, connectionGeneration = generation,
    organizer = organizer, organizers = listOfNotNull(organizer),
)

/** One repository over fakes, driven on the test scheduler: every effect runs on virtual time. */
@OptIn(ExperimentalCoroutinesApi::class)
internal class MemoHarness(val test: TestScope, readiness: MemoReadiness = ready(), receiptTimeoutMs: Long = DEFAULT_RECEIPT_TIMEOUT_MS) {
    val log = mutableListOf<String>()
    val files = InMemoryMemoFiles()
    val store = RecordingStore(files, log)
    val link = FakeLink(readiness, test.testScheduler)
    val recorder = FakeRecorder()
    val gateway = FakeGateway(log)
    val prefs = FakePrefs()
    val clock = TestClock(test.testScheduler)
    val errors = mutableListOf<Throwable>()
    private var ids = 0
    private var prompts = 0
    private val receiptTimeout = receiptTimeoutMs
    lateinit var repo: DefaultVoiceMemoRepository

    fun start(): MemoHarness {
        repo = DefaultVoiceMemoRepository(
            test.backgroundScope, store, link, recorder, gateway, prefs, clock,
            newId = { uuid(++ids) }, newPromptId = { "prompt-${++prompts}" },
            io = StandardTestDispatcher(test.testScheduler), receiptTimeoutMs = receiptTimeout,
            onError = { errors += it },
        )
        test.runCurrent()
        act(MemoAction.Opened)
        return this
    }

    val state: MemoUiState get() = repo.state.value

    fun act(action: MemoAction) {
        repo.accept(action)
        test.runCurrent()
    }

    fun push(state: VoiceMemoState, bindingId: String = SCOPE.bindingId) {
        check(link.states.tryEmit(MemoRemoteState(bindingId, state)))
        test.runCurrent()
    }

    /** The confirm sheet's button, carrying exactly what the sheet shows now. */
    fun confirm() = act(MemoAction.ConfirmDispatch(state.selection.shown, state.selection.target?.target ?: TARGET))

    fun receipt(promptId: String, convoId: String = gateway.convo, bindingId: String = SCOPE.bindingId) {
        check(gateway.receipts.tryEmit(MemoPromptReceipt(bindingId, convoId, promptId)))
        test.runCurrent()
    }

    fun stored(memoId: String): MemoDocument? {
        var doc: MemoDocument? = null
        kotlinx.coroutines.runBlocking { doc = store.stored(SCOPE, memoId) }
        return doc
    }

    /** Records, stops and lets the upload run; returns the new memo id. */
    fun recordMemo(bytes: ByteArray = ByteArray(1000) { it.toByte() }, durationMs: Long = 12_000): String {
        recorder.recording = MemoRecording(bytes, VoiceMemoLimits.AUDIO_MEDIA_TYPE, durationMs)
        act(MemoAction.NewMemo)
        act(MemoAction.StopRecording)
        return checkNotNull(state.processing?.memoId ?: state.document?.memoId) { "no memo after stop: ${state.capture}" }
    }

    fun seed(doc: MemoDocument) {
        kotlinx.coroutines.runBlocking { check(store.commit(doc.copy(revision = 1), null) is MemoWrite.Durable) }
    }
}

internal fun memoState(
    memoId: String,
    attemptId: String,
    revision: Long,
    stage: String,
    transcript: String? = null,
    result: VoiceMemoResult? = null,
    errorCode: String? = null,
    metrics: VoiceMemoMetrics = VoiceMemoMetrics(),
) = VoiceMemoState(memoId, attemptId, revision, stage, transcript, result, metrics, errorCode)

internal fun result(vararg todos: String, title: String = "Plan") =
    VoiceMemoResult(title = title, summary = "Summary of the memo.", todos = todos.map { VoiceMemoTodoSuggestion(it) }, language = "en")

internal fun readyState(memoId: String, attemptId: String, revision: Long, vararg todos: String) =
    memoState(memoId, attemptId, revision, VoiceMemoStage.READY, transcript = "the transcript", result = result(*todos))

/** A memo with a result and [todos] drafts, ready to be opened on DETAIL. */
internal fun seededDoc(memoId: String, vararg todos: Pair<String, String>, dispatches: List<MemoDispatchRecord> = emptyList()) = MemoDocument(
    scope = SCOPE, memoId = memoId, createdAtMs = 1_000, updatedAtMs = 1_000,
    processing = MemoAttempt(uuid(900), "audio", "a".repeat(64), 0, stage = VoiceMemoStage.READY, remoteRevision = 4, accepted = true),
    content = MemoContent(title = "Seeded", summary = "S", transcript = "t", language = "en", audioDurationMs = 5_000),
    todos = todos.map { (id, text) -> MemoTodo(id, text) },
    dispatches = dispatches,
)

