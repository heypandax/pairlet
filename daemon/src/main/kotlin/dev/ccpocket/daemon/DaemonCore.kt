package dev.ccpocket.daemon

import dev.ccpocket.observability.*

import dev.ccpocket.daemon.agent.AgentBackendFactory
import dev.ccpocket.daemon.claude.AuthService
import dev.ccpocket.daemon.disk.DirectoryService
import dev.ccpocket.daemon.disk.FileExportService
import dev.ccpocket.daemon.disk.FileInboxService
import dev.ccpocket.daemon.disk.SpawnedSessions
import dev.ccpocket.daemon.conversation.KeyedSink
import dev.ccpocket.daemon.conversation.OutboundSink
import dev.ccpocket.daemon.claude.ClaudeModelService
import dev.ccpocket.daemon.codex.CodexModelService
import dev.ccpocket.daemon.opencode.OpenCodeModelService
import dev.ccpocket.daemon.presets.PresetService
import dev.ccpocket.daemon.presets.PresetStore
import dev.ccpocket.daemon.schedule.ScheduleExecutor
import dev.ccpocket.daemon.schedule.ScheduleStore
import dev.ccpocket.daemon.schedule.SchedulerService
import dev.ccpocket.daemon.server.RequestRouter
import dev.ccpocket.daemon.session.SessionRegistry
import dev.ccpocket.daemon.shell.ShellService
import dev.ccpocket.daemon.transcribe.TranscribeService
import dev.ccpocket.protocol.AgentKind
import dev.ccpocket.protocol.OpenSession
import dev.ccpocket.protocol.SendPrompt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** The transport-agnostic core: registry + services + router. Shared by the local server and the relay client.
 *  [backends] maps each agent kind to a factory that builds a fresh per-conversation driver.
 *  [claudeConfigDir] non-null = credential isolation (issue #69): auth commands (and the claude
 *  backends, wired by the caller) operate on the daemon's own CLAUDE_CONFIG_DIR.
 *  [presetStore] holds the API presets (issue #113); the caller shares the SAME instance with its
 *  claude backend factory so activation and session-launch injection can't diverge. */
class DaemonCore(
    backends: Map<AgentKind, AgentBackendFactory>,
    val prefs: DaemonPrefs = DaemonPrefs.load(),
    claudeConfigDir: java.nio.file.Path? = null,
    /** The owner's --claude-bin override — must reach every auxiliary claude process (see [claudeRuntime]). */
    claudeBin: String? = null,
    /** The owner's --codex-bin override (issue #348). The quota reader spawns its own short-lived
     *  `codex app-server`, so it must resolve the SAME binary [CodexBackend] does — a reader that fell
     *  back to PATH would report a different account's allowance on a machine with two Codex installs. */
    codexBin: String? = null,
    presetStore: PresetStore = PresetStore.load(),
    scheduleStore: ScheduleStore = ScheduleStore.load(),
    openCodeModels: OpenCodeModelService = OpenCodeModelService(),
    kimiModels: dev.ccpocket.daemon.kimi.KimiModelService = dev.ccpocket.daemon.kimi.KimiModelService(),
    zcodeModels: dev.ccpocket.daemon.zcode.ZCodeModelService = dev.ccpocket.daemon.zcode.ZCodeModelService(),
    codexModels: CodexModelService = CodexModelService(),
    dshModels: dev.ccpocket.daemon.dsh.DshModelService = dev.ccpocket.daemon.dsh.DshModelService(),
    /** Session Handoff (SESSION-HANDOFF.md): registry + guard + fan-out, shared by both transports.
     *  Installed onto [SessionRegistry.handoffs] below so the router's drive gate, the §4.1 create
     *  checks, the graceful-recall turn control and the idle-reaper protection all read one truth.
     *  Injectable so a test can hand in a temp-store instance instead of the real ~/.cc-pocket one. */
    val handoffs: dev.ccpocket.daemon.handoff.HandoffService = dev.ccpocket.daemon.handoff.HandoffService(),
    /** ReviewRequest (REVIEW-REQUEST.md) — the TASK-context sibling of [handoffs], deliberately BESIDE it
     *  rather than inside it: it has its own store, its own state machine and its own capability set, and
     *  Session Handoff's runtime semantics must not leak into it (§13.2). Sender-authoritative side.
     *  Injectable so a test can hand in a temp-store instance instead of the real ~/.cc-pocket one. */
    val reviews: dev.ccpocket.daemon.review.ReviewService = dev.ccpocket.daemon.review.ReviewService(
        dev.ccpocket.daemon.review.ReviewRegistry(dev.ccpocket.daemon.review.ReviewStore.inMemory()),
    ),
    /** Production supplies the durable implementation explicitly in Main. The in-memory default keeps
     * unit tests and embedded cores from reading ~/.cc-pocket or opening peer relay connections. */
    peerInboxFactory: (CoroutineScope) -> dev.ccpocket.daemon.review.PeerInboxService =
        dev.ccpocket.daemon.review.PeerInboxService::inMemory,
    /** Project-pin sync (issue #362). The file store reads lazily — an embedded core that never receives a pin
     *  request never touches ~/.cc-pocket — and tests hand in a temp-file or in-memory store instead. */
    projectPinStore: dev.ccpocket.daemon.pins.ProjectPinStore =
        dev.ccpocket.daemon.pins.FileProjectPinStore(dev.ccpocket.daemon.pins.FileProjectPinStore.defaultFile()),
    /** Managed session list store directory (issue #360). Read lazily; tests hand in a temp directory. */
    managedSessionRoot: java.io.File = dev.ccpocket.daemon.disk.ManagedSessionStore.defaultRoot(),
    /** #367 run-journal root. Read lazily (see [executionRuns]); tests hand in a temp directory. */
    private val executionRunRoot: java.io.File = dev.ccpocket.daemon.execution.RunJournal.defaultRoot(),
) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default + kotlinx.coroutines.CoroutineExceptionHandler { _, error ->
        Diagnostics.report(ErrorPath.ASYNC_WORKER, Stage.EXECUTE, ErrorCode.UNEXPECTED, error)
        // Preserve the existing uncaught exception path after the explicit diagnostic capture.
        val thread = Thread.currentThread()
        thread.uncaughtExceptionHandler?.uncaughtException(thread, error)
    })

    /** The shared claude launch context (binary override + credential store + preset env) for auxiliary
     *  claude processes — e.g. the Feishu Guardian Reviewer (reviewed-trust §21.2): a helper that resolved
     *  its own binary or inherited raw env would diverge from the main backend on all three. */
    val claudeRuntime = dev.ccpocket.daemon.claude.ClaudeRuntime(claudeBin, claudeConfigDir) {
        runCatching { presetStore.activeEnv() }.getOrNull()
    }

    /** ONE pending-approval ledger for the whole daemon (approval design M1): agent tool asks, bridge
     *  request approvals, quick-shell commands and file exports all register here, so a verdict routes by
     *  askId in one place and timeout/withdraw/snapshot semantics can't drift between the gates. */
    val approvalHistory = dev.ccpocket.daemon.approval.ApprovalHistoryStore.load()
    val approvals = dev.ccpocket.daemon.approval.ApprovalCoordinator(scope, history = approvalHistory)

    /** ONE task-grant engine for the whole daemon (approval design M2): the agent's Bash tool and the
     *  quick terminal share it, so "允许本任务" from either surface covers both. */
    val grants = dev.ccpocket.daemon.approval.ApprovalGrantStore()
    val registry = SessionRegistry(scope, backends, approvals = approvals, grants = grants)

    init {
        registry.handoffs = handoffs
        // issue #201: mirror the persisted "wait for my decision" preference into the per-ask read. Done
        // here (not lazily in ApprovalTimeout) so the object never has to know about DaemonPrefs — the
        // router writes the same pair whenever a client flips it.
        dev.ccpocket.daemon.agent.ApprovalTimeout.noAutoDeny = prefs.askNoAutoDeny
        // issue #220: same mirror for the persisted Full Control expiry duration (0 = never expires)
        dev.ccpocket.daemon.agent.ApprovalTimeout.fullControlExpiryMs = prefs.fullControlExpiryMs
        // unhide transcripts a crashed previous instance stranded hidden (issue #70) — off the
        // constructor path (file IO over up to 200 journal entries must not delay startup). Then keep
        // sweeping periodically (issue #216 ②/④): an always-on daemon never reboots, so boot-only
        // convergence left crash/orphan leftovers — and sibling spawners' drop-in journals — hidden
        // for days. Live sessions are excluded per entry via the registry (their claudes hold the
        // files), and the sweep's own mtime/process guards cover external writers.
        scope.launch(Dispatchers.IO) {
            while (true) {
                runCatching { SpawnedSessions.sweep(held = registry::isLiveSession) }
                delay(SPAWNED_SWEEP_PERIOD_MS)
            }
        }
        // periodic handoff expiry sweep + HandoffUpdated fan-out — on the core scope like the schedule
        // pump below, so BOTH transports (relay client + local server) get it for free
        scope.launch { handoffs.sweepLoop() }
        // the ReviewRequest expiry sweep, on the same footing and for the same reason
        scope.launch { reviews.sweepLoop() }
    }

    /**
     * The RECIPIENT half of ReviewRequest (REVIEW-REQUEST.md §9): inbound peer links and their inbox
     * connections. Lives here — not on the relay client — because it is not this account's relay leg at
     * all: each link dials the PEER's relay with a credential minted in the PEER's account, so it works
     * (and must keep retrying) whether or not this machine's own relay link is up.
     */
    val peerInbox = peerInboxFactory(scope)

    init {
        // one supervised inbox per stored link; a peer that is unreachable simply keeps backing off
        runCatching { peerInbox.start() }
    }

    val dirs = DirectoryService()
    val transcribe = TranscribeService(scope, registry::workdirOf)
    val inbox = FileInboxService(registry::workdirOf)
    val shell = ShellService(scope, coordinator = approvals, grants = grants)
    val exports = FileExportService(scope, registry::workdirOf, coordinator = approvals)

    /**
     * The Git panel (#280) / worktree (#281) engine. Wired to the SAME two live-session truths the
     * project list uses — the daemon's own conversations by cwd, and `claude` processes started in an
     * outside terminal — because "a worktree with a running agent must not be removed" has to mean the
     * same thing here as the running dot means there. No new liveness heuristic is invented.
     */
    val git = dev.ccpocket.daemon.git.GitService(
        liveByCwd = { registry.liveByCwd() },
        externalCwds = { dev.ccpocket.daemon.disk.LiveProcesses.claudeCwds() },
    )
    val auth = AuthService(
        scope, registry::busyForAuth, registry::closeIdleForAuth, registry::closeBusyForAuth,
        claudeConfigDir = claudeConfigDir,
    )
    // same switch suppliers as auth: activating a preset swaps what new sessions run on, so the same
    // mid-task guard + idle auto-close semantics apply (issue #113)
    val presets = PresetService(presetStore, registry::busyForAuth, registry::closeIdleForAuth, registry::closeBusyForAuth)

    /**
     * Scheduled tasks (issue #137). The executor reuses the EXACT interactive session paths — no
     * parallel channel: [SessionRegistry.open] (reattaches a live conversation / lazy-spawns a cold
     * one) then [SessionRegistry.sendPrompt] (which queues into a running turn exactly like a mid-turn
     * phone send). The sink is a keyed no-op: nobody is watching the fire itself — a phone that opens
     * the session later replays the transcript, and the turn's completion/error rides the normal
     * [dev.ccpocket.daemon.conversation.PushHook] push closure. A session live in an OUTSIDE terminal
     * opens as a read-only observe (no prompt path) — reported as a miss, never a second writer.
     */
    val scheduler = SchedulerService(
        scheduleStore,
        executor = ScheduleExecutor { entry ->
            // watching=false: this sink is a black hole (headless fire, no client attached). Counting it
            // as a watcher would suppress the owner ask-push while nobody can see/answer the card (C1).
            val execution = dev.ccpocket.daemon.diagnostics.BackgroundExecutionDiagnostics()
            val sink = KeyedSink("scheduler", OutboundSink(execution::frame), watching = false)
            val wd = dirs.validateWorkdir(entry.workdir)
                ?: return@ScheduleExecutor "not a readable directory: ${entry.workdir}"
            val convoId = registry.open(
                OpenSession(
                    workdir = wd.toString(), resumeId = entry.resumeId,
                    model = entry.model, mode = entry.mode, agent = entry.agent,
                ),
                sink,
                // headless: same reason as watching=false above — an ask nobody can see must keep its
                // bounded window, or issue #201's wait would pin a process per fire (repeating schedules).
                headless = true,
            )
            // the handoff drive gate covers scheduled fires too (SESSION-HANDOFF.md §5.3: a WAITING/
            // handed-off session accepts input from its controller only — the scheduler is never that)
            val handoffDeny = if (convoId.isEmpty()) null else registry.driveDenied(convoId, "scheduler")
            val failure = when {
                convoId.isEmpty() -> "agent unavailable"
                handoffDeny != null -> handoffDeny.message
                !registry.sendPrompt(SendPrompt(convoId, entry.prompt, promptId = "sched-${entry.id}", diagnostic = execution.context)) ->
                    "session unavailable (live in another client?)"
                else -> null
            }
            if (failure != null) execution.dispatchFailed()
            failure
        },
    )

    init {
        // the schedule pump lives on the core scope so BOTH transports (relay client + local server)
        // get scheduling for free; it ticks absolute times, so a boot after downtime settles/back-runs
        // whatever came due while the daemon was off (see SchedulerService's missed policy)
        scope.launch { scheduler.runLoop() }
    }

    /**
     * The OWNER-LOCAL review plane (REVIEW-REQUEST.md §6 + §12). ONE instance for the whole daemon: the
     * wire router (App/desktop Review Center) and the local control API (CLI/Skill) both drive it, which
     * is what keeps "contacts, prepare and queued actions mean the same thing on every surface" true in
     * code rather than in a comment. [collaboratorControl] is read lazily — it only exists once the relay
     * link is up, and a review contact list must not capture the null it saw at construction.
     */
    val reviewOwner = dev.ccpocket.daemon.review.ReviewOwnerService({ collaboratorControl }, reviews, peerInbox)

    /**
     * This computer's project-pin list (issue #362), shared by both transports: the router commits requests,
     * the relay and LAN connections attach their owner push targets. A cursor is only ever reclaimed for a
     * device that is no longer paired (it can never authenticate a frame again); the allow-list is read only
     * when the cursor table is actually full.
     */
    val projectPins = dev.ccpocket.daemon.pins.ProjectPinService(
        projectPinStore, scope,
        deviceStillPaired = { id -> dev.ccpocket.daemon.identity.PairedDevices.load().containsKey(id) },
    )

    /**
     * The managed session list (issue #360): one store per daemon, shared by both transports through the router.
     * The store reads lazily; boot recovery only finishes registrations a previous process durably recorded.
     */
    val managedSessions = dev.ccpocket.daemon.session.ManagedSessionService.create(
        managedSessionRoot, scope, registry, dirs, backends.keys,
    ).also { svc ->
        registry.managedSessions = svc
        scope.launch(Dispatchers.IO) { runCatching { svc.recoverPending() } }
    }

    val router = RequestRouter(
        registry, dirs, transcribe, inbox, shell, exports, scope, auth, prefs, presets, scheduler,
        // presetEnv shares PresetStore with the DaemonInfo gateway pill (Main.kt): the host we ask for a
        // model list must be the host the client is showing, with that layer's own credential (#167 ②).
        openCodeModels = openCodeModels,
        kimiModels = kimiModels,
        zcodeModels = zcodeModels,
        codexModels = codexModels,
        dshModels = dshModels,
        claudeModels = ClaudeModelService(claudeConfigDir, presetEnv = { runCatching { presetStore.activeEnv() }.getOrNull() }),
        approvals = approvals,
        grants = grants,
        approvalHistory = approvalHistory,
        reviews = reviews,
        reviewOwner = reviewOwner,
        projectPins = projectPins,
        managedSessions = managedSessions, // issue #360: without this the router advertises and serves nothing
        git = git,
        codexQuota = dev.ccpocket.daemon.codex.CodexQuotaService(codexBin),
    )

    /**
     * The OWNER control planes (folder-share #115, bridges #91 follow-up), installed by RelayClient once
     * the relay link is up (minting a redeem ticket needs it) and null until then / on a LAN-only `serve`.
     *
     * They live HERE — not on the relay's DeviceSessions — because the relay is not the only transport an
     * owner arrives on: the desktop app on the daemon's own machine connects over the loopback LAN path,
     * and a control plane reachable only via the relay made Settings ▸ Shared/Bridges dead exactly where
     * they're most used. Every LAN peer is a full-power owner by construction (bridge/guest credentials
     * are structurally barred from the LAN gate — see BridgeStore), so both transports may serve these.
     */
    @Volatile
    var shareControl: dev.ccpocket.daemon.relay.ShareControl? = null
    @Volatile
    var bridgeControl: dev.ccpocket.daemon.relay.BridgeControl? = null
    /** The Collaborator Link contact plane (SESSION-HANDOFF.md §4.1) — same install/lifetime terms as
     *  the two above (minting a connect ticket needs the relay link). */
    @Volatile
    var collaboratorControl: dev.ccpocket.daemon.handoff.CollaboratorControl? = null

    /**
     * #367 G1: the EXECUTION credential BIND hook — same install/lifetime terms as the three above
     * (approving a grant mints a connect ticket, which needs the relay link).
     *
     * [dev.ccpocket.daemon.relay.DeviceSessions] calls it at the ONE moment an execution link's first
     * transport frame has proven the derived first-contact PSK. Null (LAN-only `serve`, or the link still
     * coming up) means no execution credential can be bound at all — which is the safe answer: an execution
     * link is only ever created by an owner approval that itself needed the relay.
     */
    @Volatile
    var executionControl: dev.ccpocket.daemon.execution.ExecutionControl? = null

    // ------------------------------------------------------------------ #367 remote execution

    /**
     * The TARGET's run journal. `by lazy` on purpose: an embedded core or a unit test that never receives
     * an execution frame must not touch `~/.cc-pocket/execution-runs` — and construction RECOVERS, i.e. it
     * writes (STARTING/RUNNING rows a dead process left behind become INTERRUPTED_UNKNOWN).
     */
    val executionRuns: dev.ccpocket.daemon.execution.RunJournal by lazy {
        dev.ccpocket.daemon.execution.RunJournal(executionRunRoot)
    }

    /**
     * The #367 planes, installed by the relay wiring through [installExecution] once the link is up —
     * same lifetime rule as [collaboratorControl] and for the same reason: approving a grant mints a relay
     * ticket, and the source client dials the relay. Null on a LAN-only `serve` and before the link opens.
     */
    @Volatile
    var executionTarget: dev.ccpocket.daemon.execution.ExecutionTarget? = null
        private set

    @Volatile
    var executionGrants: dev.ccpocket.daemon.execution.ExecutionGrantStore? = null
        private set

    @Volatile
    var executionClient: dev.ccpocket.daemon.execution.client.ExecutionClient? = null
        private set

    /** The run plane the transport hands EXECUTION frames to. Null until [installExecution]. */
    @Volatile
    var executionPlane: dev.ccpocket.daemon.execution.ExecutionRunPlane? = null
        private set

    /**
     * Wire the execution planes. [store] and [target] come from the relay leg (the grant store is bound to
     * this daemon's identity key, which lives there); [client] is the source half and may be null.
     *
     * Idempotent: a relay reconnect re-installs the same objects rather than stacking a second run plane —
     * two RunServices over one journal would each think they owned the concurrency ceiling.
     */
    fun installExecution(
        store: dev.ccpocket.daemon.execution.ExecutionGrantStore,
        target: dev.ccpocket.daemon.execution.ExecutionTarget,
        client: dev.ccpocket.daemon.execution.client.ExecutionClient? = null,
    ) {
        executionGrants = store
        executionTarget = target
        executionClient = client
        // the BIND hook and the transport gate track the current target/store on every (re)install — a
        // relay reconnect hands over the same objects, and an owner-visible refusal must never be answered
        // by a stale store
        executionControl = target
        router.executionGuard = dev.ccpocket.daemon.execution.ExecutionGuard(
            store,
            grantIdOf = executionCredentialGrantId,
            linkPubOf = executionCredentialPub,
            refusals = executionRefusals,
        )
        // IDEMPOTENT: the plane and its ticker are created ONCE. Two RunServices over one journal would
        // each think they owned the concurrency ceiling, and two tickers would double every timeout sweep.
        if (executionPlane == null) {
            val plane = dev.ccpocket.daemon.execution.RunService(store, executionRuns, registry, scope)
            executionPlane = plane
            router.executionPlane = plane
            scope.launch {
                while (true) {
                    // the run plane's own sweep (timeouts, queue pump, retention) AND the owner plane's
                    // (pending revoke writes, relay-revoke retry with backoff, clock high-water persistence)
                    runCatching { plane.maintain() }
                    runCatching { executionTarget?.maintain() }
                    delay(EXECUTION_MAINTAIN_PERIOD_MS)
                }
            }
        }
    }

    /**
     * How the execution gate resolves a credential's grant pointer and its proven static key. Installed by
     * the relay wiring (which owns [dev.ccpocket.daemon.relay.DeviceSessions] and therefore the
     * [dev.ccpocket.daemon.bridge.BridgeRegistry]); both default to "nothing is an execution credential",
     * so a core with no relay leg fails closed.
     */
    @Volatile
    var executionCredentialGrantId: (String) -> String? = { null }
    @Volatile
    var executionCredentialPub: (String) -> String? = { null }

    /** One refusal ledger for every execution layer (target bind, transport gate, run plane). */
    val executionRefusals = dev.ccpocket.daemon.execution.ExecutionRefusals()

    suspend fun shutdown() = registry.closeAll()

    private companion object {
        /** Cadence of the periodic spawned-session sweep (issue #216 ②). Convergence for crash/orphan
         *  leftovers only — the common paths (process end, idle reap) unhide in real time, so this just
         *  bounds how long a stranded transcript can stay hidden without a daemon restart. */
        const val SPAWNED_SWEEP_PERIOD_MS = 5 * 60_000L

        /** #367 maintenance cadence: run timeouts, grant-lifecycle stops, journal retention, queue pump.
         *  Short enough that a revoked grant stops an in-flight run promptly, long enough to be free. */
        const val EXECUTION_MAINTAIN_PERIOD_MS = 15_000L
    }
}
