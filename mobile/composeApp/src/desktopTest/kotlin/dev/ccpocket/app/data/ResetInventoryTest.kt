package dev.ccpocket.app.data

import androidx.compose.runtime.MutableState
import androidx.compose.runtime.State
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.runtime.snapshots.SnapshotStateMap
import dev.ccpocket.app.pairing.PairedDaemon
import dev.ccpocket.app.pairing.Pairing
import dev.ccpocket.app.telemetry.ProductOutcome
import dev.ccpocket.app.telemetry.TelEvent
import dev.ccpocket.protocol.AgentKind
import dev.ccpocket.protocol.ApprovalPrefs
import dev.ccpocket.protocol.ArchivedSessions
import dev.ccpocket.protocol.AuthState
import dev.ccpocket.protocol.BridgeListing
import dev.ccpocket.protocol.ChatRole
import dev.ccpocket.protocol.ConvoHistory
import dev.ccpocket.protocol.DaemonInfo
import dev.ccpocket.protocol.Directories
import dev.ccpocket.protocol.DirectoryEntry
import dev.ccpocket.protocol.HistoryMessage
import dev.ccpocket.protocol.PathEntries
import dev.ccpocket.protocol.PermissionAsk
import dev.ccpocket.protocol.PermissionMode
import dev.ccpocket.protocol.PresetsState
import dev.ccpocket.protocol.PushPrefs
import dev.ccpocket.protocol.ScheduleInfo
import dev.ccpocket.protocol.ScheduleState
import dev.ccpocket.protocol.SessionLive
import dev.ccpocket.protocol.SessionSummary
import dev.ccpocket.protocol.SkillCatalog
import dev.ccpocket.protocol.TurnDone
import dev.ccpocket.protocol.Usage
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Delay
import kotlinx.coroutines.InternalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.lang.reflect.Field
import java.lang.reflect.GenericArrayType
import java.lang.reflect.Modifier
import java.lang.reflect.ParameterizedType
import java.lang.reflect.Type
import java.lang.reflect.TypeVariable
import java.lang.reflect.WildcardType
import kotlin.coroutines.CoroutineContext
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.TimeSource

/**
 * S0 of the PocketRepository split (`_local/audit-2026-10-04/design-repository-split.md` §4.4): a golden
 * inventory of every piece of mutable state the repository holds, and of what each of the eight exits does
 * to it TODAY. It pins current behaviour — including the cells the proposal calls out as "should probably
 * clear but doesn't" (marked `GAP` below). A refactor must keep this table identical; a deliberate fix
 * changes the table in the same commit.
 *
 * How it works, per exit: build a repository, dirty every inventoried field (real frames and public calls
 * first, then a reflective top-up for whatever is still at its initial value), snapshot, fire the exit,
 * snapshot again, and classify each field against a freshly constructed instance's initial value:
 *
 *  - `R` reset: was dirty, now equals the fresh instance's initial value
 *  - `K` kept: was dirty, untouched by the exit
 *  - `C` changed: was dirty, now holds some third value (a counter bump, a new status message, …)
 *  - `S` set: was at its initial value (not dirtied), the exit wrote something else
 *  - `-` neither dirtied nor touched (fields deliberately left clean — see [NO_DIRTY])
 *
 * Why reflection (JVM `java.lang.reflect`, no kotlin-reflect on the classpath): desktopTest runs on the JVM,
 * so the backing fields are enumerable and stable; an explicit hand list would let a new field slip in
 * unnoticed, which is exactly the regression class this guards (a new per-computer field forgotten in
 * `disconnect()`). [inventoryCoversEveryField] fails when a field is added, removed or renamed without the
 * table below taking a position on it.
 */
@OptIn(InternalCoroutinesApi::class)
class ResetInventoryTest {

    enum class Exit { DISCONNECT, SWITCH_COLD, DEMOTE, OPEN_OTHER, BACK, STOP, TAKEOVER, BACK_TO_DIRS }

    /**
     * Inline like `Dispatchers.Unconfined`, but (1) `delay`/`withTimeout` never fire — every deadline the
     * repository arms stays pending, so no timer races a snapshot — and (2) it can be parked: launches and
     * resumptions after [parked] are queued and never run. The cold switch parks before dialing so its
     * snapshot is the synchronous part of [PocketRepository.switchDaemon] only; how the dial ends belongs to
     * the transport, not to this inventory.
     */
    private class InventoryDispatcher : CoroutineDispatcher(), Delay {
        @Volatile var parked = false
        override fun isDispatchNeeded(context: CoroutineContext) = parked
        override fun dispatch(context: CoroutineContext, block: Runnable) { if (!parked) block.run() }
        override fun scheduleResumeAfterDelay(timeMillis: Long, continuation: CancellableContinuation<Unit>) = Unit
    }

    private lateinit var dispatcher: InventoryDispatcher
    private lateinit var scope: CoroutineScope
    private var savedActive: String? = null

    @BeforeTest
    fun setUp() {
        dispatcher = InventoryDispatcher()
        scope = CoroutineScope(SupervisorJob() + dispatcher)
        savedActive = Pairing.activeAccount() // the cold switch persists the active account
    }

    @AfterTest
    fun tearDown() {
        Pairing.setActive(savedActive)
        scope.cancel()
    }

    // ── the guards ────────────────────────────────────────────────────────────────────────────────────

    @Test
    fun inventoryCoversEveryField() {
        val discovered = allKeys()
        val tabled = EXPECTED.keys
        val ignored = IGNORED.keys
        val both = tabled intersect ignored
        val unknown = discovered - tabled - ignored
        val stale = (tabled + ignored) - discovered
        val msg = buildString {
            if (unknown.isNotEmpty()) appendLine("New fields with no position in the reset table (add a row to MATRIX or to IGNORED): $unknown")
            if (stale.isNotEmpty()) appendLine("Table rows naming fields that no longer exist (renamed/moved? update the key): $stale")
            if (both.isNotEmpty()) appendLine("Listed both in MATRIX and IGNORED: $both")
        }
        assertTrue(msg.isEmpty(), msg)
    }

    @Test
    fun initialValuesAreComparableAcrossInstances() {
        val a = snapshot(PocketRepository(scope))
        val b = snapshot(PocketRepository(scope))
        val differing = a.keys.filter { a[it] != b[it] }
        assertTrue(differing.isEmpty(), "two fresh instances disagree on $differing — their value has no stable equality; normalise it in norm() or IGNORE it")
    }

    @Test
    fun noDirtyListOnlyNamesTabledFields() {
        assertTrue((NO_DIRTY.keys - EXPECTED.keys).isEmpty(), "NO_DIRTY names fields absent from MATRIX: ${NO_DIRTY.keys - EXPECTED.keys}")
    }

    @Test fun disconnect() = check(Exit.DISCONNECT)
    @Test fun coldSwitch() = check(Exit.SWITCH_COLD)
    @Test fun hotSwitchDemote() = check(Exit.DEMOTE)
    @Test fun openAnotherSession() = check(Exit.OPEN_OTHER)
    @Test fun backToBrowse() = check(Exit.BACK)
    @Test fun stopSession() = check(Exit.STOP)
    @Test fun takeOver() = check(Exit.TAKEOVER)
    @Test fun backToDirectories() = check(Exit.BACK_TO_DIRS)

    private fun check(exit: Exit) {
        val actual = run(exit).fates
        // coverage: every field the dirtying could not move off its initial value must be declared, so a gap in
        // the script is visible instead of reading as a silent '-'
        val undirtied = actual.filterValues { it == '-' || it == 'S' }.keys - NO_DIRTY.keys
        assertTrue(undirtied.isEmpty(), "not dirtied before $exit, and not declared in NO_DIRTY: $undirtied")
        val col = exit.ordinal
        val diffs = EXPECTED.mapNotNull { (key, row) ->
            val want = row[col]
            val got = actual[key] ?: '?'
            if (want == got) null else "  $key: expected $want, actual $got"
        }
        assertTrue(diffs.isEmpty(), "reset inventory drifted at $exit (${diffs.size} fields). A refactor must not change this; " +
            "a deliberate behaviour change updates MATRIX in the same commit:\n" + diffs.joinToString("\n"))
    }

    // ── driving one exit ──────────────────────────────────────────────────────────────────────────────

    internal class Outcome(val fates: Map<String, Char>, val dirtiedByRealPath: Set<String>, val dirtiedByTopUp: Set<String>)

    // ids are namespaced (inv-…): the Test task shares one store / pin directory across classes, and another test's
    // persisted session params, working set or pins for "acct-b" / "sid-2" would otherwise leak into these exits
    private val a = binding("inv-acct-a")
    private val b = binding("inv-acct-b")

    private fun binding(id: String) = PairedDaemon(
        // a closed loopback port, never actually dialed (the dispatcher is parked before the cold switch)
        relay = "wss://127.0.0.1:9", accountId = id, daemonPub = "pk-$id", deviceId = "dev", credential = "c-$id",
    )

    internal fun run(exit: Exit): Outcome {
        val init = snapshot(PocketRepository(scope))
        val r = PocketRepository(scope)
        dirtyByRealPath(r)
        val afterReal = snapshot(r)
        val real = init.keys.filter { afterReal[it] != init[it] }.toSet()
        topUp(r, init)
        val before = snapshot(r)
        val topped = init.keys.filter { before[it] != init[it] }.toSet() - real

        when (exit) {
            Exit.DISCONNECT -> r.disconnect()
            Exit.SWITCH_COLD -> { dispatcher.parked = true; r.switchDaemon(b) }
            Exit.DEMOTE -> r.demoteToSatellite()
            // a Codex session: a different backend than the open Claude one, so sessionAgent visibly changes
            Exit.OPEN_OTHER -> assertTrue(r.openSession("/inv/w2", "inv-sid-2", agent = AgentKind.CODEX), "the other session's open must be accepted")
            Exit.BACK -> r.backToBrowse()
            Exit.STOP -> r.stopSession()
            Exit.TAKEOVER -> r.takeOver()
            Exit.BACK_TO_DIRS -> r.backToDirectories()
        }
        val after = snapshot(r)
        val fates = init.keys.associateWith { k ->
            val i = init[k]; val bf = before[k]; val af = after[k]
            when {
                bf != i && af == i -> 'R'
                bf != i && af == bf -> 'K'
                bf != i -> 'C'
                af == i -> '-'
                else -> 'S'
            }
        }
        return Outcome(fates, real, topped)
    }

    /** As close to the real path as is cheap: the frames a live session on computer A would have produced. */
    private fun dirtyByRealPath(r: PocketRepository) {
        r.paired.value = a
        r.pairedList.addAll(listOf(a, b))
        r.sessionActive.value = true
        r.receiveForTest(DaemonInfo(
            gatewayBaseUrl = "http://gw", bridgeControl = true, daemonVersion = "9.9.9", latestVersion = "9.9.10",
            supportedAgents = listOf("claude", "codex"), supportsUsageAgentFilter = true, supportsPromptRecovery = true,
            quotaAgents = listOf("claude", "codex"), supportsDiagnostics = true,
            supportsManagedSessions = true, managedAgents = listOf("claude"), supportsLeanHistory = true,
        ))
        r.receiveForTest(Directories(listOf(
            DirectoryEntry(path = "/inv/w", name = "w", isDir = true),
            DirectoryEntry(path = "/inv/w2", name = "w2", isDir = true),
        )))
        r.defaultMode.value = PermissionMode.PLAN // in memory only (the setter would persist)
        r.openSession("/inv/w", "inv-sid-1", agent = AgentKind.CLAUDE)
        r.receiveForTest(SessionLive("inv-c1", "/inv/w", "inv-sid-1", mode = PermissionMode.ACCEPT_EDITS, executing = false, model = "claude-opus", effort = "high",
            contextWindow = 200_000, contextUsed = 1_000, agent = AgentKind.CLAUDE, origin = "bot", title = "Fix it"))
        // its replayed window: the cursor, the paging anchor and the replayed-rows snapshot a left session parks
        r.receiveForTest(ConvoHistory("inv-c1", listOf(HistoryMessage(ChatRole.USER, "inv-q")), lastSeq = 9, firstSeq = 1, hasMore = true))
        r.sidePanes.open("/inv/w2", "inv-sid-pane", "Column", AgentKind.CLAUDE, PermissionMode.DEFAULT)
        r.receiveForTest(PermissionAsk("inv-c1", "ask1", "Bash", "rm -rf build"))
        r.receiveForTest(TurnDone("inv-c1", error = "usage limit reached|1720000000", usageLimitResetAt = 1_720_000_000_000))
        r.receiveForTest(ScheduleState(items = listOf(ScheduleInfo(id = "sched-a", workdir = "/inv/w", prompt = "p", nextRunAtMs = 5))))
        r.fetchUsage()
        r.receiveForTest(Usage(tokensToday = 42))
        r.receiveForTest(ArchivedSessions(listOf(SessionSummary("old-a", "t", "p", 1, "/inv/w", 0))))
        r.receiveForTest(PathEntries(workdir = PocketRepository.BROWSE_HOME, subPath = "", roots = listOf("C:\\")))
        // the frozen / per-daemon features: contents don't matter, only that the repository took them in
        listOf(BridgeListing::class.java,
            AuthState::class.java, PresetsState::class.java, SkillCatalog::class.java, ApprovalPrefs::class.java)
            .forEach { r.receiveForTest(assertNotNull(Arbitrary.of(it) as? dev.ccpocket.protocol.Frame, "build ${it.simpleName}")) }
        r.receiveForTest(PushPrefs(enabled = false))
    }

    /** Everything the real path left at its initial value, set directly — except [NO_DIRTY]. */
    private fun topUp(r: PocketRepository, init: Map<String, Any?>) {
        for (slot in slots()) {
            if (slot.key in NO_DIRTY) continue
            if (read(slot, r) != init[slot.key]) continue
            val owner = slot.owner(r)
            runCatching { Arbitrary.dirty(owner, slot.field, slot.field.get(owner), salt = slot.key) }
        }
    }

    // ── enumeration and snapshots ─────────────────────────────────────────────────────────────────────

    internal class Slot(val key: String, val field: Field, val owner: (PocketRepository) -> Any)

    internal fun slots(): List<Slot> {
        val out = ArrayList<Slot>()
        for (f in instanceFields(PocketRepository::class.java)) {
            if (f.name in EXPANDED) {
                val prefix = EXPANDED.getValue(f.name)
                for (g in instanceFields(f.type)) {
                    val key = "$prefix.${g.name}"
                    if (key !in IGNORED) out += Slot(key, g) { r -> f.get(r) }
                }
                continue
            }
            if (f.name !in IGNORED) out += Slot(f.name, f) { it }
        }
        return out
    }

    /** All keys, including ignored and expanded-sub-object keys — what the guard compares against the table. */
    private fun allKeys(): Set<String> = buildSet {
        for (f in instanceFields(PocketRepository::class.java)) {
            add(f.name)
            EXPANDED[f.name]?.let { prefix -> instanceFields(f.type).forEach { add("$prefix.${it.name}") } }
        }
    }

    private fun instanceFields(c: Class<*>): List<Field> =
        c.declaredFields.filter { !Modifier.isStatic(it.modifiers) && !it.isSynthetic }.onEach { it.isAccessible = true }

    internal fun snapshot(r: PocketRepository): Map<String, Any?> = slots().associate { it.key to read(it, r) }

    /** A `Job?` slot reads as "is a live job here": null and cancelled/completed are the same reset. */
    private fun read(slot: Slot, r: PocketRepository): Any? {
        val v = slot.field.get(slot.owner(r))
        return if (Job::class.java.isAssignableFrom(slot.field.type)) (v as Job?)?.isActive == true else norm(v)
    }

    companion object {
        /** Value view used for every comparison: observable holders by content, jobs by liveness, mutable
         *  collections copied (they are mutated in place by the exits). */
        fun norm(v: Any?): Any? = when (v) {
            null -> null
            is Job -> v.isActive
            is SnapshotStateList<*> -> v.toList().map(::norm)
            is SnapshotStateMap<*, *> -> v.toMap().entries.associate { norm(it.key) to norm(it.value) }
            is State<*> -> norm(v.value)
            is StateFlow<*> -> norm(v.value)
            is Map<*, *> -> v.entries.associate { norm(it.key) to norm(it.value) }
            is Set<*> -> v.map(::norm).toSet()
            is List<*> -> v.map(::norm)
            is Collection<*> -> v.map(::norm)
            is StringBuilder -> v.toString()
            else -> v
        }

        /** Owned helper objects whose fields are inventoried individually, under this key prefix. */
        val EXPANDED = mapOf("transcript" to "transcript", "sidePanes" to "sidePanes", "sessionCache" to "sessionCache", "modelCatalogPolicy" to "modelCatalogPolicy")

        /** Fields outside the inventory, each with the reason. Not state, or state owned by a helper. */
        val IGNORED: Map<String, String> = buildMap {
            listOf("scope", "pinnedTo", "projectPinRegistry", "relay", "directE2E")
                .forEach { put(it, "constructor dependency / transport instance") }
            listOf("directLinkUp", "pushDial", "registrarOverride", "linkHealthOverride", "downlinkFramesOverride", "onSendForTest",
                "pinWriterForTest", "memoWriterForTest", "memoStoreForTest", "redeemForTest", "dialForTest", "voiceUploadForTest",
                "directConnectForTest", "networkSnapshotProvider", "sameMachineClient", "tcpProbeForTest", "repathEnabledOverride")
                .forEach { put(it, "test seam / injected function") }
            listOf("onBeforeSwitch", "onTurnFinished", "onApprovalArrived", "onClaudeQuotaReply")
                .forEach { put(it, "shell callback wiring, not state") }
            put("leanHistory", "platform capability (which chat UI is running) / test seam, not session state")
            listOf("stableLinkResetMs", "presenceProbeMs", "managedCallTimeoutMs", "managedListPageTimeoutMs", "managedEnableTimeoutMs",
                "managedLoadingMaxMs", "promptReceiptTimeoutMs", "promptTurnTimeoutMs", "firstPromptTimeoutMs", "sessionsOpenTimeoutMs")
                .forEach { put(it, "test seam: timeout configuration") }
            listOf("appLock\$delegate", "recorder\$delegate", "memo\$delegate").forEach { put(it, "lazy platform service") }
            listOf("pinLink", "memoHost", "approvalOutcomes", "promptOutcomes", "backgroundOutcomes", "fileChunks")
                .forEach { put(it, "owned helper; its internal state is outside S0 (modules join the inventory from S1)") }
            listOf("repath")
                .forEach { put(it, "owned helper (#404); disconnect() calls its reset(), which voids every timer and pending evaluation") }
            listOf("claudeQuota")
                .forEach { put(it, "derived view over the CLAUDE slot of quotaByAgent") }
            EXPANDED.keys.forEach { put(it, "expanded: its fields are tabled under the '${EXPANDED.getValue(it)}.' prefix") }
            listOf("scope", "send", "newPromptId", "daemonOwnsPromptRecovery", "diagnosticsSupported", "productDimensions",
                "receiptExpired", "responseExpired", "receiptTimeoutMs", "turnTimeoutMs")
                .forEach { put("sidePanes.$it", "SidePanes wiring / timeout configuration") }
            listOf("maxEntries", "maxBytes").forEach { put("sessionCache.$it", "SessionHistoryCache bounds (constructor configuration)") }
            put("modelCatalogPolicy.now", "ModelCatalogRefreshPolicy clock (constructor configuration)")
            put("modelCatalogStore", "stateless adapter over SecureStore (three function fields, no state of its own)")
        }

        /** Tabled fields the dirtying deliberately leaves at their initial value. */
        val NO_DIRTY: Map<String, String> = mapOf(
            "demoMode" to "true would route send() into the demo loopback and change every exit's path; leaving demo is not one of the eight exits",
            "useRelay" to "true would route send() to the (unconnected) relay transport instead of the inert no-transport sink",
            "connectionDiagnostic" to "an OperationTrace needs an installed diagnostics reporter (Diagnostics.begin is null in tests)",
            "openDiagnostic" to "an OperationTrace needs an installed diagnostics reporter (Diagnostics.begin is null in tests)",
        )

        // ── the table: today's behaviour, not the desired one ────────────────────────────────────────
        // columns: DSC disconnect() · SWC switchDaemon() (cold switch = disconnect + re-dial's synchronous writes, so
        // e.g. sessionActive reads K: dropped, then raised again) · DEM demoteToSatellite() · OPN openSession() on another
        // (Codex) session · BCK backToBrowse() · STP stopSession() · TKO takeOver() · DIR backToDirectories()
        //
        // GAP-* = 疑似缺陷，待单独决定 — the cells marked K are the "should probably clear but doesn't" points of the
        // proposal's §4.4, confirmed by this run. Do NOT fix them here; a fix is its own commit and edits these rows.
        //   GAP-桥接   DSC/SWC keep the bridge request state (busy/error/credential/merge check) — frozen feature
        //   GAP-会话视图 DSC/SWC keep allow rules (DEM clears them) — verified harmless, deliberately untouched
        //   FIX-桥接: DSC/SWC drop only the frozen features' cached LISTINGS (+ their loaded flags /
        //              list deadline), which each surface re-pulls from the next daemon on open
        // FIX-* = a former GAP that disconnect() now clears (the cold switch inherits it); OK-* = a former GAP judged
        // harmless after reading every reader, kept on purpose:
        //   FIX-偏好   push / approval prefs are daemon truth whose null means "not answered" — a next daemon too old
        //              to answer showed the last computer's switches, which then did nothing
        //   FIX-回退   the rewind sheet (confirm sent the last computer's anchor into the next chat) and its refusal bar
        //   OK-回退    sessionLineage / rewindAwaiting match on a daemon-minted UUID convoId no other computer reproduces
        //   FIX-提示   the archive toast's action re-sent the last computer's (workdir, session); archiveTarget is what
        //              a later archive_failed resurrected
        //   OK-提示    renameError is read only through a sessionId match (desktop sidebar), renameTarget only feeds it
        //   FIX-会话视图 slash commands: a backend that never sends CommandList inherited the last computer's
        //   FIX-面板   DSC/SWC drop the session panels + their armed deadlines (clearSessionPanels, shared with DEM).
        //              BCK/STP/DIR still keep them — same computer, and openSession clears them before the next chat
        //   OK-降级    DEM keeps limitOffer/limitConfirmed/repairOffer/repairProgress: both banners render only for a
        //              matching convoId, the desktop (the only shell that demotes) renders neither, and the next
        //              openSession on the promoted repo clears all four before any chat exists
        // The NOTE-* asymmetries this run first found (not named by the proposal), now judged:
        //   FIX-流式   transcript.streaming is cleared on DSC/SWC/BCK (and DIR, which delegates to BCK): only a frame of
        //              the on-screen conversation clears it, so leaving a running chat left it true, and the project
        //              list's busy/finished poll skips while it reads true. TKO keeps it — its own SessionLive re-sets it
        //   FIX-身份   chatTitle: the computer switcher's current row reads it ungated, so B's row named A's chat
        //   OK-身份    sessionKey / currentSessionId / observing: every reader is gated on convoId (or on a workdir that
        //              DSC clears), and SessionLive re-sets all three together with convoId
        //   OK-不对称  DEM clears these, DSC/SWC do not: each spinner/flag is cleared by the next list reply (or its own
        //              8 s safety timer for `switching`), and its screen is not reachable before that reply
        //   OK-从不清  no exit clears it: timedOutAskId only matches the exact (UUID convoId, askId); the single-slot
        //              changedFilesDeadline is cancelled by every new fetch and no-ops unless a fetch is loading
        // The in-memory session cache (session-open latency plan #1), judged when it was added:
        //   缓存-存    OPN/BCK/STP/DIR park the session being left (C: one entry more, bytes up). TKO keeps it: the same
        //              session continues on screen, nothing is left
        //   缓存-清    DSC/SWC clear it: disconnect() is every way off a computer (exit, unpair, cold switch, add-device,
        //              leaving the demo). DEM clears it too: a headless satellite never shows a chat, so its entries
        //              would only hold memory, once per satellite
        private val MATRIX = """
            # field                      D S D O B S T D
            #                            S W E P C T K I
            #                            C C M N K P O R
            useRelay                     - S - - - - - -
            directCooldownUntil          K K K K K K K K
            badDirectUrl                 K K K K K K K K
            directAttemptInFlight        R R K K K K K K
            firstTicket                  K R K K K K K K
            inboundJob                   R K K K K K K K
            connectJob                   R K K K K K K K
            retryJob                     R R K K K K K K
            retryAttempts                K R K K K K K K
            controlJob                   R K K K K K K K
            deafJob                      R K K K K K K K
            graceJob                     R K K K K K K K
            listWaitJob                  R R K K K K K K
            connectWatchdog              R K K K K K K K
            listWaitRetried              R R K K K K K K
            lastTransportLaunchAt        K C K K K K K K
            transportLaunches            K C K K K K K K
            linkStableJob                R R K K K K K K
            presenceProbeJob             R R K K K K K K
            directoriesRev               K K K K K K K K
            attachedThisSession          R R K K K K K K
            diagnosticConnectionId       R R K K K K K K
            daemonOffline                R R K K K K K K
            pairingInvalid               R R K K K K K K
            hadReadyThisSession          R R K K K K K K
            relayDeadlinePassed          R R K K K K K K
            reconnectGraceJob            R R K K K K K K
            reconnectGracePassed         R R K K K K K K
            pushDesired                  K K K K K K K K
            pushConnected                R R K K K K K K
            attachedPushKey              K K K K K K K K
            notificationsOn              K K K K K K K K
            privacyConsented             K K K K K K K K
            defaultMode                  K K K K K K K K
            defaultPermissionMode        K K K K K K K K
            fullAccessConfirmed          K K K K K K K K
            defaultEffort                K K K K K K K K
            defaultCodexEffort           K K K K K K K K
            defaultOpenCodeEffort        K K K K K K K K
            defaultKimiEffort            K K K K K K K K
            defaultZCodeEffort           K K K K K K K K
            defaultDshEffort             K K K K K K K K
            defaultServiceTier           K K K K K K K K
            defaultModel                 K K K K K K K K
            defaultCodexModel            K K K K K K K K
            defaultOpenCodeModel         K K K K K K K K
            defaultKimiModel             K K K K K K K K
            defaultZCodeModel            K K K K K K K K
            defaultDshModel              K K K K K K K K
            contextWindowOverride        K K K K K K K K
            contextWindowOverrides       K K K K K K K K
            defaultAgent                 K K K K K K K K
            agentFilter                  K K K K K K K K
            treeView                     K K K K K K K K
            fontScale                    K K K K K K K K
            themeMode                    K K K K K K K K
            accentTheme                  K K K K K K K K
            voiceWhisper                 K K K K K K K K
            pinnedPaths                  K R K K K K K K
            workingSetMru                K R K K K K K K
            unseenSessions               R R K K K K K K
            lastWorkingSessions          R R K K K K K K
            lastWorkingDirectories       R R K K K K K K
            sessionKey                   K K R C K K K K  # OK-身份
            composerEpoch                K K K C K K K K
            browsePath                   K K K K K K K K
            pendingOpen                  R R R K K K K K
            sessionActive                R K K K K K K K
            connected                    R K K K K K K K
            connGen                      K K K K K K K K
            phase                        R R K K K K K K
            status                       R C K K K K K K
            paired                       K C K K K K K K
            pairedList                   K K K K K K K K
            addingDevice                 K K K K K K K K
            demoMode                     - - - - - - - -
            demoConnecting               R R K K K K K K
            directories                  R R K K K K K K
            directoriesLoaded            R R K K K K K K
            refreshing                   K K R K K K K K  # OK-不对称
            sessions                     R R R K K K K R
            sessionsDir                  R R R K K K K R
            sessionGroups                R R K K K K K K
            daemonManagedSessions        R R K K K K K K
            daemonSessionObservation     R R K K K K K K  # read-only observation capability: per daemon, like the flag above
            sessionObservationBusy       R R K K K K K K  # a bind/unbind in flight; its own request clears it, DSC/SWC drop it with the computer
            sessionObservationError      R R K K K K K K  # last bind/unbind failure (carries its session id); cleared by the next attempt
            daemonManagedAgents          R R K K K K K K
            managedList                  R R K K K K K K
            managedMissing               R R K K K K K K
            managedListLoading           K K R K K K K R  # OK-不对称
            legacySessions               R R R K K K K R
            managedByDir                 R R K K K K K K
            managedPending               R R K K K K K K
            managedGen                   C C K K K K K K
            managedSeq                   K K K K K K K K
            managedFetches               R R K K K K K K
            managedAmbiguous             K K K K K K K K
            managedAccepted              K K K K K K K K
            managedListStale             K K K K K K K K
            managedSeenHere              R R K K K K K K
            managedOpeningNew            R R K R K K K K
            managedPriorSessionId        K K K C K K K K
            groupsSupported              K K K K K K K K
            renameSupported              K K K K K K K K
            archiveSupported             K K K K K K K K
            archivedSessions             R R K K K K K K
            archivedRefreshing           R R K K K K K K
            archiveToast                 R R K K K K K K  # FIX-提示
            archiveTarget                R R K K K K K K  # FIX-提示
            renameError                  K K K K K K K K  # OK-提示
            renameTarget                 K K K K K K K K  # OK-提示
            rewindSheet                  R R K K K K K K  # FIX-回退
            rewindError                  R R K K K K K K  # FIX-回退
            sessionLineage               K K K K K K K K  # OK-回退
            rewindAwaiting               K K K K K K K K  # OK-回退
            transcript.messages          R R R R R R R R
            transcript.sessionNotice     R R R R R R R R
            transcript.streaming         R R R R R R K R  # FIX-流式
            transcript.toolOutcomesLive  K K K R K K K K
            transcript.childCallIds      K K K R K K K K
            transcript.replayEcho        K K K R K K K K
            transcript.thinkStartMs      K K K R K K K K
            transcript.cachedRows        R R R R R R R R
            sessionCache.entries         R R R C C C K C  # 缓存-存 / 缓存-清
            sessionCache.bytes           R R R C C C K C  # 缓存-存 / 缓存-清
            sidePanes.panes              R R R K K K K K
            sidePanes.paneSeq            K K K K K K K K
            sidePanes.focusedSlot        R R R K K K K K
            sidePanes.disowned           R R R K K K K K
            sidePanes.openCount          R R R C K K K K
            pendingImages                R R R K R R K R
            pendingFiles                 R R K K R R K R
            fileUploadJob                R R K K R R K R
            fileAckDeadline              R R K K R R K R
            pendingIdSeq                 K K K K K K K K
            convoId                      R R R R R R R R
            workdir                      R R R K K K K K
            chatTitle                    R R R R R R K R  # FIX-身份
            pendingAsk                   R R R R K K K K
            askQueue                     R R R R K K K K
            askQueueProgress             R R R R K K K K
            askRisk                      R R R R K K K K
            askBurstTotal                R R R R K K K K
            askBurstDone                 R R R R K K K K
            pendingApprovals             R R K K K K K K
            timedOutAskId                K K K K K K K K  # OK-从不清
            slashCommands                R R R K K K K K  # FIX-会话视图
            terminalEntries              R R R R K K K K  # FIX-面板
            terminalBusy                 R R R R K K K K  # FIX-面板
            changedFiles                 R R R R K K K K  # FIX-面板
            changedFilesLoading          R R R R K K K K  # FIX-面板
            changedFilesUnavailable      R R R K K K K K  # FIX-面板
            viewedFilePath               R R R R K K K K  # FIX-面板
            viewerDeferred               R R R R K K K K  # FIX-面板
            viewedFile                   R R R R K K K K  # FIX-面板
            viewedFileProgress           R R R R K K K K  # FIX-面板
            viewedFileDiff               R R R R K K K K  # FIX-面板
            exportWaiting                R R R R K K K K  # FIX-面板
            gitStatus                    R R R R K K K K  # FIX-面板
            gitStatusLoading             R R R R K K K K  # FIX-面板
            gitStatusUnavailable         R R R R K K K K  # FIX-面板
            gitDiff                      R R R R K K K K  # FIX-面板
            gitDiffPath                  R R R R K K K K  # FIX-面板
            gitDiffStaged                R R R R K K K K  # FIX-面板
            gitBusyOp                    R R R R K K K K  # FIX-面板
            gitError                     R R R R K K K K  # FIX-面板
            gitFetchNote                 R R R R K K K K  # FIX-面板
            gitPendingConfirm            R R R R K K K K  # FIX-面板
            worktrees                    R R R R K K K K  # FIX-面板
            worktreeCreated              R R R R K K K K  # FIX-面板
            worktreesLoading             R R R R K K K K  # FIX-面板
            worktreesUnavailable         R R R R K K K K  # FIX-面板
            pathListing                  R R R K K K K K  # FIX-面板
            browseListing                K K K K K K K K
            fileTree                     R R R R K K K K  # FIX-面板
            fileTreePending              R R R R K K K K  # FIX-面板
            filesAllView                 R R R R K K K K  # FIX-面板
            fileTreeSubPath              R R R R K K K K  # FIX-面板
            browseRoots                  R R K K K K K K
            lastBrowseAnchor             K K K K K K K K
            lastBrowseSub                K K K K K K K K
            mode                         K K K C K K C K
            permissionMode               K K K R K K C K
            model                        K K R R K K K K
            sessionAgent                 K K R C K K K K
            effort                       K K R R K K K K
            thinking                     K K R R K K R K
            serviceTier                  K K K C K K R K
            sessionAgentPreset           K K K K K K K K
            sessionOrigin                K K K K K K K K
            contextWindow                K K R K K K K K
            contextUsed                  K K R R K K K K
            backgroundJobs               R R R R R R K R
            workflowRuns                 R R R R R R K R
            workflowAgentDetails         R R R R R R K R
            viewedWorkflowRunId          R R R R R R K R
            allowRules                   K K R R K K K K  # GAP-会话视图
            pendingGrantMutations        K K K K K K K K
            switching                    K K R K K K K K  # OK-不对称
            opening                      R R R K R R K R
            switchingSession             R R R K R R K R
            openTimedOut                 R R R R R R K R
            openTimedOutReason           K K K K K K K K
            openGen                      C C C C C C K C
            openDispatchedGen            K K K C K K K K
            appIsForeground              K K K K K K K K
            connectionRecovery           R R K K K K K K
            connectionDiagnostic         - - - - - - - -
            connectionDiagnosticEnded    R R K K K K K K
            daemonDiagnostics            R R K K K K K K
            openObservation              R R R C R R K R
            historyDiagnosticDeadline    R R R K R R K R
            historyLayoutToken           R R R R R R K R
            latestDiagnosticId           K K K C K K K K
            openDiagnostic               - - - - - - - -
            openJob                      R R R K R R K R
            sessionNavigationFenced      K K K R K K K K
            browseIntentDir              R R R K C K K R
            pendingNewOpenWd             R R R R R R K R
            openInFlight                 R R R C R R K R
            lastOpenAttempt              R R R C R R K R
            autoFocusComposer            K K R R K K K K
            observing                    K K R K R K R R  # OK-身份
            sessionObservation           K K R K R K R R  # the open chat's read-only snapshot: written and cleared together with observing
            currentSessionId             K K R K K K K K  # OK-身份
            historySeq                   R R R R R R R R
            historySeqSession            R R R R R R R R
            historyFirstSeq              R R R R R R R R
            historyRows                  R R R R R R R R
            historyHasMore               R R R R R R R R
            historyLoadingOlder          R R R R R R R R
            historyPageDeadline          R R R R R R R R
            historyPageCooldown          R R R R R R R R
            historyPageAnchor            R R R R R R R R
            lastHistoryPrependCount      R R R R R R R R
            historyPrependGen            K K K K K K K K
            historyWindowGen             K K K K K K K K  # a counter, like the row above
            fullImages                   R R R R R R R R  # lean history: fetched full pictures go with the transcript they belong to
            fullImageOrder               R R R R R R R R
            fullImagePending             R R R R R R R R
            fullImageUnavailable         R R R R R R R R
            promptRetry                  R R R R R R R R
            promptResendArmed            R R R R R R R R
            promptPending                R R R R R R R R
            activePromptId               R R R R R R R R
            sendStalled                  R R R R R R R R
            promptWatchdog               R R R R R R R R
            turnStalled                  R R R R R R R R
            turnQueued                   R R R R R R R R
            turnWatchdog                 R R R R R R R R
            awaitingTurn                 R R R R R R R R
            promptQueued                 K K K K K K K K
            sessionDegraded              K K R R K K K K
            degradedSendArmed            K K R R K K K K
            turnStartMark                K K K R K K K K
            sessionParams                K K K K K K K K
            voice                        R R R R R R K R
            voiceLevels                  K K K K K K K K
            liveDictation                R R R R R R K R
            liveFinal                    R R R R R R K R
            livePartial                  R R R R R R K R
            micPermissionSheet           R R R R R R K R
            voiceNotice                  R R R R R R K R
            pendingVoiceText             K K K K K K K K
            usingNative                  R R R R R R K R
            preferRemote                 K K K K K K K K
            keptAudio                    R R R R R R K R
            captureId                    R R R R R R K R
            voiceAttempts                R R R R R R K R  # every exit but TKO abandons the capture (clearVoice)
            voiceUploading               R R R R R R K R  # likewise; TKO keeps the capture running
            voiceTicker                  R R R R R R K R
            voiceTimeout                 R R R R R R K R
            levelsJob                    R R R R R R K R
            dictationJob                 R R R R R R K R
            noticeJob                    R R R R R R K R
            voiceStartJob                R R R R R R K R
            interruptJob                 R R R R R R K R
            pairFailure                  K K K K K K K K
            pairFailureSeq               K K K K K K K K
            pairVerifying                K K K K K K K K
            pairAttempt                  K K K K K K K K
            demoSeq                      K K K K K K K K
            demoAsked                    K K K K K K K K
            demoPendingReply             K K K K K K K K
            demoDepth                    K K K K K K K K
            demoNewOpens                 K K K K K K K K
            authState                    R R K K K K K K
            pushPrefs                    R R K K K K K K  # FIX-偏好
            approvalPrefs                R R K K K K K K  # FIX-偏好
            approvalFullControlExpiryMs  R R K K K K K K  # FIX-偏好
            presetsState                 R R K K K K K K
            presetsStateRev              R R K K K K K K
            gatewayBaseUrl               R R K K K K K K
            bridgeControl                R R K K K K K K
            daemonSupportedAgents        R R K K K K K K
            daemonAgentsKnown            R R K K K K K K
            daemonUsageAgentFilter       R R K K K K K K
            daemonOwnsPromptRecovery     R R K K K K K K
            daemonLeanHistory            R R K K K K K K  # a daemon capability: cleared with the binding, like the row above
            newTaskDraft                 K K K K K K K K
            newTaskDir                   K K K K K K K K
            newTaskAgent                 K K K K K K K K
            newTaskError                 K K K K K K K K
            newTaskStarting              K K K K K K K K
            versionStatus                R R K K K K K K
            agentModels                  R R K K K K K K
            agentModelPreview            R R K K K K K K  # Codex catalog cache: the restored rows belong to the computer we left
            agentModelsRefreshing        R R K K K K K K  # … and the cue
            codexCatalogRequest          R R K C K K K K  # the outstanding correlated request leaves with the link; OPN re-targets it at the opened session's directory (new token)
            codexCatalogTimeout          R R K K K K K K  # its timer, likewise
            codexCatalogTarget           R R K C K K K K  # the directory the catalog is asked for; OPN re-targets it at the opened session's
            codexCatalogContext          R R K K K K K K  # which context the authoritative Codex list was confirmed for
            codexCatalogScope            R R K K K K K K  # …and the daemon's scope marker on it
            modelCatalogPolicy.lastReply R R K K K K K K  # per-computer answer ages
            usage                        R R K K K K K K
            usageLoading                 R R K K K K K K
            usageAgent                   R R K K K K K K
            usageRequestedAgent          R R K K K K K K
            turnCompletions              K K K K K K K K
            quotaByAgent                 R R K K K K K K
            quotaLoadingByAgent          R R K K K K K K
            quotaStatusByAgent           R R K K K K K K
            daemonQuotaAgents            R R K K K K K K
            quotaDeadlines               R R K K K K K K
            quotaOutstanding             R R K K K K K K
            skillCatalog                 R R K K K K K K
            skillCatalogLoading          R R K K K K K K
            skillCatalogUnavailable      R R K K K K K K
            skillCatalogDeadline         R R K K K K K K
            bridges                      R R K K K K K K  # FIX-桥接
            bridgesLoaded                R R K K K K K K  # FIX-桥接
            bridgesUnavailable           R R K K K K K K  # FIX-桥接
            bridgeError                  K K K K K K K K  # GAP-桥接
            bridgeCredential             K K K K K K K K  # GAP-桥接
            bridgeBusy                   K K K K K K K K  # GAP-桥接
            bridgeMergeLost              K K K K K K K K  # GAP-桥接
            pendingMergeCheck            K K K K K K K K  # GAP-桥接
            bridgesDeadline              R R K K K K K K  # FIX-桥接
            bridgeBusyDeadline           K K K K K K K K  # GAP-桥接
            schedules                    R R K K K K K K
            schedulesLoaded              R R K K K K K K
            schedulesUnavailable         R R K K K K K K
            scheduleError                R R K K K K K K
            scheduleDeadline             R R K K K K K K
            limitOffer                   R R K R K K K K  # OK-降级
            limitConfirmed               R R K R K K K K  # OK-降级
            repairOffer                  R R K R K K K K  # OK-降级
            repairProgress               R R K R K K K K  # OK-降级
            sessionsOpening              R R R K K K K R
            sessionsOpeningJob           R R R K K K K R
            managedFailed                R R K K K K K K
            managedDirty                 R R K K K K K K
            managedLoadingExpired        R R K K K K K K
            managedLoadingTimers         R R K K K K K K
            legacyByKey                  R R K K K K K K
            managedAcceptedSeq           K K K K K K K K
            managedAcceptedByKey         R R K K K K K K
            managedStale                 R R K K K K K K
            managedTombstones            R R K K K K K K
            sessionsRefreshing           K K R K K K K K  # OK-不对称
            changedFilesDeadline         K K K K K K K K  # OK-从不清
            viewedFileDeadline           R R R R K K K K  # FIX-面板
            fileViewObservation          R R R R K K K K  # FIX-面板
            exportDeadline               R R R R K K K K  # FIX-面板
            gitStatusDeadline            R R R R K K K K  # FIX-面板
            gitDiffDeadline              R R R R K K K K  # FIX-面板
            gitActionDeadline            R R R R K K K K  # FIX-面板
            worktreesDeadline            R R R R K K K K  # FIX-面板
            gitPendingAction             R R R R K K K K  # FIX-面板
            gitPendingRemove             R R R R K K K K  # FIX-面板
            pendingWorktreeAddBranch     R R R R K K K K  # FIX-面板
            filesShowHidden              K K K K K K K K
            memoFeatureOn                K K K K K K K K
            memoOpen                     K K K K K K K K
        """.trimIndent()

        val EXPECTED: Map<String, String> by lazy {
            MATRIX.lines().map { it.substringBefore('#').trim() }.filter { it.isNotEmpty() }.associate { line ->
                val parts = line.split(Regex("\\s+"))
                require(parts.size == 1 + Exit.entries.size) { "bad MATRIX row: $line" }
                parts[0] to parts.drop(1).joinToString("")
            }
        }
    }

    /** Reflective construction of an arbitrary non-default value for a declared type (JVM reflection only). */
    internal object Arbitrary {
        fun of(t: Type, depth: Int = 0): Any? {
            val c = raw(t) ?: return null
            return when {
                c == String::class.java || c == CharSequence::class.java -> "dirty"
                c == java.lang.Boolean::class.java || c == java.lang.Boolean.TYPE -> true
                c == java.lang.Integer::class.java || c == Integer.TYPE -> 7
                c == java.lang.Long::class.java || c == java.lang.Long.TYPE -> 7L
                c == java.lang.Float::class.java || c == java.lang.Float.TYPE -> 1.5f
                c == java.lang.Double::class.java || c == java.lang.Double.TYPE -> 1.5
                c == Any::class.java -> "dirty"
                c.isEnum -> c.enumConstants.last()
                c == ByteArray::class.java -> byteArrayOf(1)
                c.isArray -> java.lang.reflect.Array.newInstance(c.componentType, 0)
                c == CompletableDeferred::class.java -> CompletableDeferred<Any?>()
                Job::class.java.isAssignableFrom(c) -> Job()
                c == ProductOutcome::class.java -> ProductOutcome(TelEvent.ConnectionRecoveryResult)
                c.name == "kotlin.time.TimeSource\$Monotonic\$ValueTimeMark" -> TimeSource.Monotonic.markNow()
                java.util.Map::class.java.isAssignableFrom(c) -> collection(c, linkedMapOf(of(arg(t, 0), depth + 1) to of(arg(t, 1), depth + 1)))
                java.util.Set::class.java.isAssignableFrom(c) -> collection(c, linkedSetOf(of(arg(t, 0), depth + 1)))
                java.lang.Iterable::class.java.isAssignableFrom(c) -> collection(c, arrayListOf(of(arg(t, 0), depth + 1)))
                depth > 6 -> null
                else -> singleton(c) ?: construct(c, depth) ?: subclass(c, depth)
            }
        }

        /** Make [field]'s value differ from [cur] (which equals the fresh instance's initial value). */
        /**
         * Make [field]'s value differ from [cur] (which equals the fresh instance's initial value). Scalars get a
         * value unique to the field ([salt]) where the type allows, so an exit that copies one field into another
         * (takeOver: `permissionMode = defaultPermissionMode`) shows up as `C`, not as a coincidental `K`.
         */
        @Suppress("UNCHECKED_CAST")
        fun dirty(owner: Any, field: Field, cur: Any?, salt: String) {
            val t = field.genericType
            // an element/value that could not be built stays out: a null where the repository expects an
            // object would make the exit itself throw, which pins nothing
            fun el(i: Int): Any = of(arg(t, i)) ?: error("cannot build ${arg(t, i)}")
            fun nextOf(v: Any?, type: Type): Any = next(v, type, salt) ?: error("cannot build $type")
            when (cur) {
                is SnapshotStateList<*> -> (cur as MutableList<Any?>).add(el(0))
                is SnapshotStateMap<*, *> -> (cur as MutableMap<Any?, Any?>)[el(0)] = el(1)
                is MutableState<*> -> (cur as MutableState<Any?>).value = nextOf(cur.value, arg(t, 0))
                is MutableStateFlow<*> -> (cur as MutableStateFlow<Any?>).value = nextOf(cur.value, arg(t, 0))
                is java.util.Map<*, *> -> { val k = el(0); val v = el(1)
                    runCatching { (cur as MutableMap<Any?, Any?>)[k] = v }.onFailure { field.set(owner, mapOf(k to v)) } }
                is java.util.Set<*> -> { val e = el(0)
                    runCatching { check((cur as MutableSet<Any?>).add(e)) }.onFailure { field.set(owner, setOf(e)) } }
                is java.util.List<*> -> { val e = el(0)
                    runCatching { (cur as MutableList<Any?>).add(e) }.onFailure { field.set(owner, listOf(e)) } }
                else -> field.set(owner, nextOf(cur, t))
            }
        }

        private fun next(cur: Any?, t: Type, salt: String): Any? {
            val h = (salt.hashCode() and 0x7fff) + 1
            return when (cur) {
                is Boolean -> !cur
                is Int -> cur + h
                is Long -> cur + h
                is Float -> cur + h
                is Double -> cur + h
                is String -> "dirty:$salt"
                is Enum<*> -> cur.declaringJavaClass.enumConstants.filter { it != cur }.let { it[h % it.size] }
                is Job -> Job()
                null -> when (raw(t)) {
                    String::class.java -> "dirty:$salt"
                    java.lang.Integer::class.java -> h
                    java.lang.Long::class.java -> h.toLong()
                    else -> of(t)
                }
                else -> of(t)
            }
        }

        private fun collection(c: Class<*>, value: Any): Any? = when {
            c.isInstance(value) -> value
            else -> runCatching {
                @Suppress("UNCHECKED_CAST")
                val inst = c.getDeclaredConstructor().newInstance()
                when (inst) {
                    is MutableMap<*, *> -> (inst as MutableMap<Any?, Any?>).putAll(value as Map<Any?, Any?>)
                    is MutableCollection<*> -> (inst as MutableCollection<Any?>).addAll(value as Collection<Any?>)
                }
                inst
            }.getOrNull()
        }

        private fun singleton(c: Class<*>): Any? =
            runCatching { c.getDeclaredField("INSTANCE").takeIf { Modifier.isStatic(it.modifiers) }?.get(null) }.getOrNull()

        private fun construct(c: Class<*>, depth: Int): Any? {
            if (c.isInterface || Modifier.isAbstract(c.modifiers)) return null
            val ctors = c.declaredConstructors
                .filter { k -> !k.isSynthetic && k.parameterTypes.none { it.name.endsWith("ConstructorMarker") } }
                .sortedByDescending { it.parameterCount }
            for (k in ctors) {
                // every argument non-null: private classes carry no Kotlin null checks, and a half-built
                // object would only make the exit throw
                val args = runCatching { k.genericParameterTypes.map { of(it, depth + 1)!! } }.getOrNull() ?: continue
                val made = runCatching { k.isAccessible = true; k.newInstance(*args.toTypedArray()) }.getOrNull()
                if (made != null) return made
            }
            return null
        }

        private fun subclass(c: Class<*>, depth: Int): Any? {
            val candidates = (runCatching { c.permittedSubclasses?.toList() }.getOrNull() ?: emptyList()) +
                c.declaredClasses.filter { c.isAssignableFrom(it) && it != c }
            for (s in candidates) of(s, depth + 1)?.let { return it }
            return null
        }

        private fun raw(t: Type): Class<*>? = when (t) {
            is Class<*> -> t
            is ParameterizedType -> t.rawType as? Class<*>
            is WildcardType -> raw(t.upperBounds.first())
            is TypeVariable<*> -> raw(t.bounds.first())
            is GenericArrayType -> null
            else -> null
        }

        private fun arg(t: Type, i: Int): Type =
            (t as? ParameterizedType)?.actualTypeArguments?.getOrNull(i)?.let { if (it is WildcardType) it.upperBounds.first() else it }
                ?: Any::class.java
    }
}
