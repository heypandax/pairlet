package dev.ccpocket.app.data

import androidx.compose.runtime.snapshotFlow
import dev.ccpocket.app.epochMillis
import dev.ccpocket.app.memo.ConfirmedMemoPrompt
import dev.ccpocket.app.memo.MemoBlock
import dev.ccpocket.app.memo.MemoClock
import dev.ccpocket.app.memo.MemoDispatchStop
import dev.ccpocket.app.memo.MemoEnterResult
import dev.ccpocket.app.memo.MemoTargetCatalog
import dev.ccpocket.app.memo.MemoProjectSessions
import dev.ccpocket.app.memo.MemoProjectRow
import dev.ccpocket.app.memo.MemoNewSessionOptions
import dev.ccpocket.app.memo.MemoListStatus
import dev.ccpocket.app.memo.MemoEnterFailure
import dev.ccpocket.app.memo.MemoLink
import dev.ccpocket.app.memo.MemoLinkSend
import dev.ccpocket.app.memo.MemoPrefs
import dev.ccpocket.app.memo.MemoPromptReceipt
import dev.ccpocket.app.memo.MemoReadiness
import dev.ccpocket.app.memo.MemoRecorder
import dev.ccpocket.app.memo.MemoRecorderStart
import dev.ccpocket.app.memo.MemoRecording
import dev.ccpocket.app.memo.MemoRemoteState
import dev.ccpocket.app.memo.MemoScope
import dev.ccpocket.app.memo.MemoSessionGateway
import dev.ccpocket.app.memo.MemoSubmitResult
import dev.ccpocket.app.memo.MemoTarget
import dev.ccpocket.app.memo.MemoTargetLease
import dev.ccpocket.app.memo.MemoTargetRow
import dev.ccpocket.app.memo.MemoTargetStatus
import dev.ccpocket.app.memo.memoPromptText
import dev.ccpocket.app.net.TransientDispatchFence
import dev.ccpocket.app.net.TransientDisposition
import dev.ccpocket.app.pairing.BindingRole
import dev.ccpocket.app.pairing.displayName
import dev.ccpocket.app.secure.SecureStore
import dev.ccpocket.app.voice.VoicePermissionDenied
import dev.ccpocket.app.voice.openAppSettings
import dev.ccpocket.protocol.AgentKind
import dev.ccpocket.protocol.DaemonInfo
import dev.ccpocket.protocol.PromptAck
import dev.ccpocket.protocol.SendPrompt
import dev.ccpocket.protocol.ToDaemon
import dev.ccpocket.protocol.VoiceMemoCancel
import dev.ccpocket.protocol.VoiceMemoGet
import dev.ccpocket.protocol.VoiceMemoLimits
import dev.ccpocket.protocol.VoiceMemoState
import dev.ccpocket.protocol.VoiceMemoStatus
import kotlin.concurrent.Volatile
import kotlin.time.TimeSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** One E2E connection a memo frame may be queued on: the binding, the transport and its generation. */
internal data class MemoTransport(
    val accountId: String,
    val deviceId: String,
    val viaDirect: Boolean,
    val connection: Int,
    val launch: Int,
)

/** What the computer [accountId] last said about voice memos. It describes the computer, not the leg the
 *  announcement happened to arrive on: a fall-back from the LAN leg to the relay leg reaches the same daemon. */
private data class MemoAdvertisement(val accountId: String, val version: Int, val agents: List<String>, val status: String)

/**
 * The voice-memo feature's view of the app: the ports in `app/memo/MemoContract.kt`, implemented over
 * [PocketRepository]. Everything that could re-deliver a confirmed prompt — the receipt watchdog, the retry
 * copy, the reconnect outbox — belongs to the ordinary chat path and is deliberately not reachable from here.
 */
internal class MemoHost(
    private val repo: PocketRepository,
    private val scope: CoroutineScope,
) : MemoLink, MemoSessionGateway {

    // ── readiness ────────────────────────────────────────────────────────────────────────────────────

    private val readinessState = MutableStateFlow(MemoReadiness())
    override val readiness: StateFlow<MemoReadiness> get() = readinessState

    private val remoteStates = MutableSharedFlow<MemoRemoteState>(extraBufferCapacity = 64)
    override val states: Flow<MemoRemoteState> get() = remoteStates

    @Volatile private var advertisement: MemoAdvertisement? = null
    @Volatile private var transport: MemoTransport? = null
    @Volatile private var generation = 0

    init {
        // The repository's state is Compose snapshot state; every input of the readiness lives there or in
        // the fields above, which call [recompute] themselves.
        scope.launch {
            snapshotFlow {
                // phase: the link's own liveness is not snapshot state, but every drop and recovery moves the phase
                listOf(repo.connected.value, repo.paired.value, repo.memoFeatureOn.value, repo.demoMode.value, repo.phase.value, repo.defaultAgent.value)
            }.distinctUntilChanged().collect { recompute() }
        }
        scope.launch {
            snapshotFlow { repo.convoId.value }.distinctUntilChanged().collect { convo ->
                val lease = activeLease
                if (lease != null && convo != lease.convoId) stop(MemoDispatchStop.LEFT_CHAT)
                // a chat opened over the memo surface (a push tap, a deep link) hides the recording page; a
                // capture nobody can see or stop must not run on to the limit and upload itself
                if (convo != null && micHeld) repo.memoInterruptCapture()
            }
        }
        scope.launch {
            snapshotFlow { repo.paired.value?.let { it.accountId to it.deviceId } }.distinctUntilChanged().collect { now ->
                val lease = activeLease
                if (lease != null && now?.first != lease.target.bindingId) stop(MemoDispatchStop.COMPUTER_CHANGED)
            }
        }
    }

    /** Every handshake is followed by an announcement; the latest one from this computer is what counts. */
    fun onDaemonInfo(info: DaemonInfo) {
        val account = repo.paired.value?.accountId ?: return
        advertisement = MemoAdvertisement(account, info.voiceMemoVersion, info.voiceMemoAgents, info.voiceMemoStatus)
        recompute()
    }

    /**
     * Follow the live connection. A different connection — a reconnect, the other leg, another computer — is a
     * new generation, so a frame built for the previous one is refused. The same connection keeps its
     * generation: a re-announcement (the daily update nudge) must not strand an upload in flight.
     */
    private fun followTransport() {
        val live = repo.memoTransport()
        if (live != transport) {
            transport = live
            if (live != null) generation++
        }
        if (advertisement?.accountId != repo.paired.value?.accountId) advertisement = null
    }

    /**
     * Re-derive the readiness from the repository as it is NOW. The collectors above keep the published value
     * fresh for the screens; every decision below calls this itself first, so whether a frame goes out never
     * depends on a notification having been delivered yet.
     */
    private fun recompute() {
        val paired = repo.paired.value
        followTransport()
        val ad = advertisement
        val block = when {
            !repo.memoFeatureOn.value -> MemoBlock.FEATURE_OFF
            paired == null -> MemoBlock.OFFLINE
            paired.role != BindingRole.OWNER -> MemoBlock.NOT_OWNER
            repo.demoMode.value || !repo.connected.value -> MemoBlock.OFFLINE
            !repo.useRelay -> MemoBlock.NOT_ENCRYPTED
            transport == null || ad == null -> MemoBlock.OFFLINE
            // the announced value is the highest contract the computer serves, and it serves the ones below
            ad.version < VoiceMemoLimits.VERSION -> MemoBlock.COMPUTER_OUTDATED
            ad.status == VoiceMemoStatus.WHISPER_MISSING -> MemoBlock.WHISPER_MISSING
            ad.status == VoiceMemoStatus.MODEL_MISSING -> MemoBlock.MODEL_MISSING
            ad.status == VoiceMemoStatus.CONVERTER_MISSING -> MemoBlock.CONVERTER_MISSING
            ad.status == VoiceMemoStatus.UNSUPPORTED_PLATFORM -> MemoBlock.UNSUPPORTED_PLATFORM
            ad.status != VoiceMemoStatus.READY -> MemoBlock.UNKNOWN
            else -> MemoBlock.NONE
        }
        // The organiser is a preference, never a prerequisite: the app's default agent when the computer has an
        // adapter for it, otherwise the first adapter the computer lists, otherwise none (transcribe only).
        val organizers = if (block == MemoBlock.NONE) ad!!.agents else emptyList()
        val defaultAgent = memoAgentWire(repo.defaultAgent.value)
        readinessState.value = MemoReadiness(
            featureOn = repo.memoFeatureOn.value,
            scope = paired?.takeIf { it.role == BindingRole.OWNER }?.let { MemoScope(it.accountId, it.deviceId) },
            computerName = paired?.displayName().orEmpty(),
            online = transport != null && repo.connected.value,
            block = block,
            connectionGeneration = generation,
            organizer = defaultAgent.takeIf { it in organizers } ?: organizers.firstOrNull(),
            organizers = organizers,
            defaultAgent = defaultAgent,
        )
    }

    // ── memo frames ──────────────────────────────────────────────────────────────────────────────────

    fun onRemoteState(state: VoiceMemoState) {
        val binding = repo.paired.value?.accountId ?: return
        remoteStates.tryEmit(MemoRemoteState(binding, state))
    }

    override suspend fun send(scope: MemoScope, generation: Int, frame: ToDaemon): MemoLinkSend {
        recompute()
        val on = transport ?: return MemoLinkSend.NotWritten
        if (generation != this.generation || on.accountId != scope.bindingId || on.deviceId != scope.deviceId) {
            return MemoLinkSend.NotWritten
        }
        // No memo frame of any kind goes to a computer that did not announce the contract: it could not decode it.
        if ((advertisement?.version ?: 0) < VoiceMemoLimits.VERSION) return MemoLinkSend.NotWritten
        // Beyond that the three kinds differ. Starting work needs everything in place. Asking about work
        // already handed over needs only the switch — the computer answers a query even while its
        // transcriber is missing. A cancel cleans up after the user and goes out after the switch was turned off.
        val allowed = when (frame) {
            is VoiceMemoCancel -> true
            is VoiceMemoGet -> repo.memoFeatureOn.value
            else -> readinessState.value.block == MemoBlock.NONE
        }
        if (!allowed) return MemoLinkSend.NotWritten
        val needsFeature = frame !is VoiceMemoCancel
        val ticket = repo.memoEnqueue(on, frame) {
            transport === on && this.generation == generation && (!needsFeature || repo.memoFeatureOn.value)
        }
        return ticket.outcome.await().toLinkSend()
    }

    private fun TransientDisposition.toLinkSend(): MemoLinkSend = when (this) {
        TransientDisposition.NOT_WRITTEN -> MemoLinkSend.NotWritten
        TransientDisposition.WRITTEN -> MemoLinkSend.Written
        TransientDisposition.INDETERMINATE -> MemoLinkSend.Indeterminate
    }

    // ── dispatch into a chat ─────────────────────────────────────────────────────────────────────────

    /** The daemon's refusal of the open in flight (its error code), for [awaitOpen]. */
    private val openRefusals = MutableStateFlow<String?>(null)

    /** An in-flight open was refused by the daemon — e.g. the agent's CLI is not installed there. Without
     *  this the memo would wait out the full open deadline and then blame a timeout. */
    fun onOpenRefused(code: String) { openRefusals.value = code }

    private object Opened

    /**
     * Wait for the open [enter] just requested to land, fail, be refused, or time out.
     * Returns null when the conversation is open, otherwise a [MemoEnterFailure] word.
     */
    private suspend fun awaitOpen(previous: String?): String? {
        val outcome: Any = withTimeoutOrNull(repo.firstPromptTimeoutMs) {
            merge(
                snapshotFlow { repo.convoId.value }.filter { it != null && it != previous }.map<String?, Any> { Opened },
                snapshotFlow { repo.openTimedOut.value }.filter { it }.map<Boolean, Any> { MemoEnterFailure.FAILED },
                openRefusals.filterNotNull().map<String, Any> { code ->
                    if (code == "agent_unavailable") MemoEnterFailure.AGENT_UNAVAILABLE else MemoEnterFailure.REFUSED
                },
            ).first()
        } ?: return MemoEnterFailure.TIMEOUT
        return if (outcome === Opened) null else outcome as String
    }

    @Volatile private var activeLease: MemoTargetLease? = null

    /** Conversations a memo prompt was submitted into, and when — see [fedRecently]. */
    private val fed = HashMap<String, Long>()

    private val receiptFlow = MutableSharedFlow<MemoPromptReceipt>(extraBufferCapacity = 64)
    override val receipts: Flow<MemoPromptReceipt> get() = receiptFlow

    private val stopFlow = MutableSharedFlow<MemoDispatchStop>(extraBufferCapacity = 16)
    override val stops: Flow<MemoDispatchStop> get() = stopFlow

    fun onReceipt(ack: PromptAck) {
        val binding = repo.paired.value?.accountId ?: return
        receiptFlow.tryEmit(MemoPromptReceipt(binding, ack.convoId, ack.promptId))
    }

    private fun stop(reason: MemoDispatchStop) {
        if (activeLease == null) return
        activeLease = null
        stopFlow.tryEmit(reason)
    }

    fun onManualSend() = stop(MemoDispatchStop.MANUAL_MESSAGE)
    fun onLeftChat() = stop(MemoDispatchStop.LEFT_CHAT)
    fun onBackground() = stop(MemoDispatchStop.BACKGROUND)
    fun onForeground() {}
    fun onFeatureOff() {
        stop(MemoDispatchStop.FEATURE_OFF)
        recompute()
    }

    /** True for a while after a memo prompt went into [convo]: the task may not have produced output yet. */
    fun fedRecently(convo: String): Boolean = fed[convo]?.let { epochMillis() - it < FED_WINDOW_MS } == true

    override suspend fun enter(target: MemoTarget, batchId: String): MemoEnterResult {
        activeLease = null
        recompute()
        val on = transport ?: return MemoEnterResult.Failed(MemoEnterFailure.OFFLINE)
        if (readinessState.value.block != MemoBlock.NONE) return MemoEnterResult.Failed(MemoEnterFailure.NOT_READY)
        if (on.accountId != target.bindingId) return MemoEnterResult.Failed(MemoEnterFailure.OTHER)
        if (target.newSession) {
            // Created here, once. A failure creates nothing to send into, and nothing is retried or re-routed:
            // not another agent, not another session.
            if (target.agent !in repo.availableAgents) return MemoEnterResult.Failed(MemoEnterFailure.AGENT_UNAVAILABLE)
            val previous = repo.convoId.value
            openRefusals.value = null
            if (!repo.openSession(target.workdir, resumeId = null, agent = target.agent)) return MemoEnterResult.Failed(MemoEnterFailure.REFUSED)
            awaitOpen(previous)?.let { return leaveAfterFailedOpen(it, target) }
        } else if (!repo.memoTargetOpen(target)) {
            val previous = repo.convoId.value
            openRefusals.value = null
            val sent = repo.openSession(target.workdir, resumeId = target.sessionId, title = target.title.ifBlank { null }, agent = target.agent)
            if (!sent) return MemoEnterResult.Failed(MemoEnterFailure.REFUSED)
            awaitOpen(previous)?.let { return leaveAfterFailedOpen(it, target) }
        }
        // the conversation id lands a moment before the rest of the announcement is applied; judging
        // the target on a half-applied open would refuse the very session the user asked for
        withTimeoutOrNull(OPEN_SETTLE_MS) { snapshotFlow { repo.opening.value }.first { !it } }
        // The open landed SOMETHING. It has to be exactly what the user confirmed: a fork, a takeover
        // prompt or a read-only view is another conversation, and nothing is sent into it.
        val convo = repo.convoId.value ?: return MemoEnterResult.Failed(MemoEnterFailure.FAILED)
        val opened = if (target.newSession) {
            target.copy(newSession = false, sessionId = repo.memoCurrentSessionId().orEmpty())
        } else target
        if (repo.observing.value) return leaveAfterFailedOpen(MemoEnterFailure.OBSERVING, target)
        if (!repo.memoChatIs(opened)) return leaveAfterFailedOpen(MemoEnterFailure.NOT_THE_TARGET, target)
        repo.memoSendRefusal(convo)?.let { return leaveAfterFailedOpen(it, target) }
        if (transport !== on) return leaveAfterFailedOpen(MemoEnterFailure.OFFLINE, target)
        if (!repo.memoForeground) return leaveAfterFailedOpen(MemoEnterFailure.OTHER, target)
        val lease = MemoTargetLease(opened, convo, batchId, generation)
        activeLease = lease
        return MemoEnterResult.Entered(lease)
    }

    /** Back to the memo — unless the chat on screen is one the user opened themselves in the meantime (a push
     *  tap, a deep link): that is where they chose to be, and nothing of ours is in it. */
    private fun leaveAfterFailedOpen(reason: String, target: MemoTarget): MemoEnterResult {
        if (repo.convoId.value == null || repo.memoChatIsAbout(target)) repo.memoLeaveChat()
        return MemoEnterResult.Failed(reason)
    }

    override fun holds(lease: MemoTargetLease): Boolean {
        recompute()
        return activeLease === lease && repo.convoId.value == lease.convoId && repo.memoForeground &&
            repo.memoFeatureOn.value && generation == lease.connectionGeneration && transport != null &&
            // the same conversation id re-announced as another session is not what the user confirmed
            repo.memoChatIs(lease.target) && repo.memoSendRefusal(lease.convoId) == null
    }

    override suspend fun submit(prompt: ConfirmedMemoPrompt): MemoSubmitResult {
        val lease = prompt.lease
        val on = transport
        if (on == null || !holds(lease)) return MemoSubmitResult.NotSubmitted
        val wire = memoPromptText(prompt.text)
        if (!repo.memoAddBubble(lease.convoId, wire, prompt.text.trim(), prompt.promptId)) return MemoSubmitResult.NotSubmitted
        val ticket = repo.memoEnqueue(on, SendPrompt(lease.convoId, wire, promptId = prompt.promptId), TransientDispatchFence { holds(lease) })
        val outcome = try {
            ticket.outcome.await()
        } catch (e: CancellationException) {
            // nobody is left to hear the verdict; the coordinator's own restart rule turns a stored
            // "sending" into "unknown"
            throw e
        }
        return when (outcome) {
            TransientDisposition.NOT_WRITTEN -> {
                repo.memoRemoveBubble(prompt.promptId)
                MemoSubmitResult.NotSubmitted
            }
            TransientDisposition.WRITTEN -> {
                fed[lease.convoId] = epochMillis()
                MemoSubmitResult.AwaitingReceipt
            }
            TransientDisposition.INDETERMINATE -> {
                fed[lease.convoId] = epochMillis()
                MemoSubmitResult.Unknown
            }
        }
    }

    override fun show(target: MemoTarget) {
        if (repo.paired.value?.accountId != target.bindingId) return
        if (repo.memoTargetOpen(target)) return
        repo.openSession(target.workdir, resumeId = target.sessionId, title = target.title.ifBlank { null }, agent = target.agent)
    }

    override fun showMemo(memoId: String) {
        activeLease = null
        repo.memoLeaveChat()
    }

    // ── target picker ────────────────────────────────────────────────────────────────────────────────
    // Two levels: the projects of the bound computer, then one project's sessions — with "new session" on
    // top. The project list is the one the home page already holds. Session lists are fetched per project:
    // the most recent projects when the picker loads (their counts are what the first level shows), any
    // other one when it is opened.

    private class SessionList(val items: List<dev.ccpocket.protocol.SessionSummary>, val at: Long)

    private var listsFor: String? = null
    private val sessionLists = HashMap<String, SessionList>()
    private val awaitedLists = HashMap<String, Long>()
    private val failedLists = HashSet<String>()

    private fun pickerProjects(): List<dev.ccpocket.protocol.DirectoryEntry> =
        repo.directories.filter { it.isDir && it.sharedBy == null }.sortedByDescending { it.lastModified }.take(PICKER_PROJECTS)

    /** Ask for [workdirs]' session lists unless a fresh answer or a pending question is already there. */
    private fun askFor(workdirs: List<String>, retryFailed: Boolean) {
        if (transport == null) return
        val now = epochMillis()
        val ask = workdirs.filter { wd ->
            val fresh = sessionLists[wd]?.let { now - it.at < LIST_FRESH_MS } == true
            val pending = awaitedLists[wd]?.let { now - it < LIST_TIMEOUT_MS } == true
            !fresh && !pending && (retryFailed || wd !in failedLists)
        }
        if (ask.isEmpty()) return
        ask.forEach { awaitedLists[it] = now; failedLists.remove(it); repo.memoListSessions(it) }
        scope.launch {
            delay(LIST_TIMEOUT_MS)
            val late = ask.filter { awaitedLists[it] == now }
            if (late.isEmpty()) return@launch
            late.forEach { awaitedLists.remove(it); failedLists.add(it) }
            repo.memoTargetsChanged() // the picker stops saying "loading" and offers a retry
        }
    }

    /**
     * A session list this host asked for. True = it was ours: the caller keeps it away from the page router
     * unless the user is looking at that very project — an unrequested list arriving while nothing is open
     * would otherwise walk the phone into that project's session page.
     */
    fun onSessions(f: dev.ccpocket.protocol.Sessions): Boolean {
        if (awaitedLists.remove(f.workdir) == null) return false
        failedLists.remove(f.workdir)
        sessionLists[f.workdir] = SessionList(f.items, epochMillis())
        repo.memoTargetsChanged()
        return true
    }

    /** The mode a dispatch would open a session with: what the chat has when it is the one on screen,
     *  otherwise the Settings default — a resume applies the default, not a remembered per-session mode.
     *  The backend-native default (Claude's `auto`) exists for Claude only; every other agent gets the plain one. */
    private fun modeFor(sessionId: String?, agent: AgentKind): String =
        if (sessionId != null && repo.memoCurrentSessionId() == sessionId) repo.permissionMode.value ?: repo.mode.value.name
        else repo.defaultPermissionMode.value?.takeIf { agent == AgentKind.CLAUDE } ?: repo.defaultMode.value.name

    private fun sessionRows(binding: String, workdir: String, project: String, items: List<dev.ccpocket.protocol.SessionSummary>, online: Boolean): List<MemoTargetRow> {
        // a session another row names as the one it rewound has been replaced; its successor is the row to offer
        val rewound = items.mapNotNull { it.rewindOf }.toSet()
        val observed = repo.memoCurrentSessionId().takeIf { repo.observing.value }
        return items.filter { it.sessionId.isNotBlank() && it.sessionId !in rewound }
            .distinctBy { it.sessionId }.sortedByDescending { it.lastModified }.map { s ->
                MemoTargetRow(
                    target = MemoTarget(
                        bindingId = binding, sessionId = s.sessionId, workdir = workdir, agent = s.agent ?: AgentKind.CLAUDE,
                        project = project, title = s.title.ifBlank { s.firstPrompt }.take(TARGET_TITLE_CHARS),
                    ),
                    status = when {
                        !online -> MemoTargetStatus.OFFLINE
                        s.sessionId == observed -> MemoTargetStatus.OBSERVING
                        s.live || s.busy -> MemoTargetStatus.RUNNING
                        else -> MemoTargetStatus.IDLE
                    },
                    mode = modeFor(s.sessionId, s.agent ?: AgentKind.CLAUDE),
                    lastModifiedMs = s.lastModified,
                )
            }
    }

    override fun catalog(project: String?): MemoTargetCatalog {
        recompute()
        val binding = repo.paired.value?.takeIf { it.role == BindingRole.OWNER }?.accountId
            ?: return MemoTargetCatalog(status = MemoListStatus.FAILED)
        if (listsFor != binding) { listsFor = binding; sessionLists.clear(); awaitedLists.clear(); failedLists.clear() }
        val online = transport != null
        val projects = pickerProjects()
        askFor(projects.filter { it.hasSessions }.take(PREFETCH_PROJECTS).map { it.path }, retryFailed = false)
        if (project != null) askFor(listOf(project), retryFailed = true) // opening a project is the retry

        val set = repo.workingSet()
        val recent = (listOfNotNull(set.current) + set.running + set.recent)
            .filter { it.sessionId.isNotBlank() && it.dirKey.isNotBlank() }
            .distinctBy { it.sessionId }.take(MemoTargetCatalog.MAX_RECENT)
            .map { item ->
                MemoTargetRow(
                    target = MemoTarget(
                        bindingId = binding, sessionId = item.sessionId, workdir = item.dirKey,
                        agent = item.agent ?: AgentKind.CLAUDE, project = item.project, title = item.title,
                    ),
                    status = when {
                        !online -> MemoTargetStatus.OFFLINE
                        item.current && repo.observing.value -> MemoTargetStatus.OBSERVING
                        item.executing -> MemoTargetStatus.RUNNING
                        else -> MemoTargetStatus.IDLE
                    },
                    mode = modeFor(item.sessionId, item.agent ?: AgentKind.CLAUDE),
                )
            }
        val names = repo.directories.associate { it.path to (it.name.ifBlank { projectLabelOf(it.path) }) }
        val agents = repo.availableAgents.let { all -> listOf(repo.sessionDefaultAgent) + all.filter { it != repo.sessionDefaultAgent } }
        return MemoTargetCatalog(
            status = when {
                repo.directoriesLoaded.value -> MemoListStatus.READY
                online -> MemoListStatus.LOADING
                else -> MemoListStatus.FAILED
            },
            recent = recent,
            projects = projects.map { e ->
                MemoProjectRow(
                    workdir = e.path, name = names[e.path] ?: projectLabelOf(e.path),
                    sessionCount = sessionLists[e.path]?.items?.size ?: if (e.hasSessions) null else 0,
                    running = e.open || e.executing || e.busy,
                )
            },
            project = project?.let { wd ->
                val name = names[wd] ?: projectLabelOf(wd)
                val list = sessionLists[wd]
                MemoProjectSessions(
                    workdir = wd, name = name,
                    status = when {
                        list != null -> MemoListStatus.READY
                        wd in failedLists || !online -> MemoListStatus.FAILED
                        else -> MemoListStatus.LOADING
                    },
                    sessions = list?.let { sessionRows(binding, wd, name, it.items, online) } ?: emptyList(),
                )
            },
            newSession = MemoNewSessionOptions(
                agents = if (online) agents else emptyList(),
                defaultAgent = repo.sessionDefaultAgent,
                mode = modeFor(null, repo.sessionDefaultAgent),
                modeByAgent = agents.associateWith { modeFor(null, it) },
            ),
        )
    }

    override fun resolved(lease: MemoTargetLease): MemoTarget? {
        if (repo.convoId.value != lease.convoId) return null
        val id = repo.memoCurrentSessionId() ?: lease.target.sessionId
        val title = repo.chatTitle.value?.takeIf { it.isNotBlank() } ?: lease.target.title
        return lease.target.copy(sessionId = id, title = title.take(TARGET_TITLE_CHARS), newSession = false)
    }

    // ── microphone ───────────────────────────────────────────────────────────────────────────────────

    @Volatile private var micHeld = false

    // Which start() the recorder belongs to. A cancel — or a newer start — moves it on, so a start that was
    // still waiting on the permission dialog finds out, when the recorder finally answers, that nobody wants
    // the recording any more.
    @Volatile private var micEpoch = 0

    /** A memo capture holds the one recorder; chat dictation waits. */
    val holdsMicrophone: Boolean get() = micHeld

    val recorder: MemoRecorder = object : MemoRecorder {
        override val levels: Flow<Float> get() = repo.memoRecorder.levels
        override val interruptions: Flow<Unit> get() = repo.memoRecorder.interruptions

        override suspend fun start(): MemoRecorderStart {
            if (micHeld) return MemoRecorderStart.Busy
            if (repo.voice.value is VoiceState.Recording || repo.voice.value is VoiceState.Transcribing) return MemoRecorderStart.Busy
            micHeld = true // claimed before the permission dialog, so a chat mic tap cannot slip in behind it
            val epoch = ++micEpoch
            return try {
                repo.memoRecorder.start()
                if (micEpoch != epoch) {
                    // cancelled while the permission dialog was up: the recorder has only just started, with no
                    // page, no timer and no limit watching it — stop it here, nothing else knows it is running
                    runCatching { repo.memoRecorder.cancel() }
                    MemoRecorderStart.Failed
                } else MemoRecorderStart.Started
            } catch (e: CancellationException) {
                runCatching { repo.memoRecorder.cancel() }
                micHeld = false
                throw e
            } catch (_: VoicePermissionDenied) {
                micHeld = false
                MemoRecorderStart.PermissionDenied
            } catch (_: Throwable) {
                runCatching { repo.memoRecorder.cancel() }
                micHeld = false
                MemoRecorderStart.Failed
            }
        }

        override suspend fun stop(): MemoRecording? {
            if (!micHeld) return null
            return try {
                val audio = repo.memoRecorder.stop()
                if (audio.bytes.isEmpty()) null else MemoRecording(audio.bytes, audio.mediaType, audio.durationMs)
            } catch (e: CancellationException) {
                runCatching { repo.memoRecorder.cancel() }
                throw e
            } catch (_: Throwable) {
                null
            } finally {
                micHeld = false
            }
        }

        override fun cancel() {
            micEpoch++ // also reaches a start that has not answered yet
            if (!micHeld) return
            runCatching { repo.memoRecorder.cancel() }
            micHeld = false
        }

        override fun openSettings() = openAppSettings()
    }

    // ── small facts ──────────────────────────────────────────────────────────────────────────────────

    val prefs: MemoPrefs = object : MemoPrefs {
        private fun key(scope: MemoScope) = PocketRepository.K_MEMO_CONSENT_PREFIX + scope.bindingId + ":" + scope.deviceId
        override fun consentAccepted(scope: MemoScope): Boolean = SecureStore.getString(key(scope)) == DISCLOSURE_VERSION
        override fun acceptConsent(scope: MemoScope) = SecureStore.putString(key(scope), DISCLOSURE_VERSION)
    }

    val clock: MemoClock = object : MemoClock {
        private val origin = TimeSource.Monotonic.markNow()
        override fun nowMs(): Long = epochMillis()
        override fun monotonicMs(): Long = origin.elapsedNow().inWholeMilliseconds
    }

    private companion object {
        /** Bump when the consent sheet's statement of where the data goes changes. */
        const val DISCLOSURE_VERSION = "1"

        /** How long after a memo prompt a chat is treated as busy even though nothing has streamed yet. */
        const val FED_WINDOW_MS = 10 * 60_000L

        const val OPEN_SETTLE_MS = 2_000L

        /** Projects on the picker's first level, and how many of them have their sessions fetched up front. */
        const val PICKER_PROJECTS = 40
        const val PREFETCH_PROJECTS = 8
        const val TARGET_TITLE_CHARS = 80

        /** How long a fetched session list counts as fresh; also what keeps a refresh from asking again. */
        const val LIST_FRESH_MS = 30_000L

        /** How long a session list may take before the picker offers a retry instead of waiting. */
        const val LIST_TIMEOUT_MS = 10_000L
    }
}

/** An [AgentKind] as the memo contract names it — the wire name the daemon lists in `voiceMemoAgents`. */
internal fun memoAgentWire(kind: AgentKind): String = kind.name.lowercase()
