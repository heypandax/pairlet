package dev.ccpocket.daemon.relay

import dev.ccpocket.daemon.bridge.BridgeRegistry
import dev.ccpocket.daemon.bridge.BridgeRunners
import dev.ccpocket.daemon.bridge.BridgeSpec
import dev.ccpocket.daemon.bridge.CredentialKind
import dev.ccpocket.daemon.util.logger
import dev.ccpocket.protocol.ActiveSession
import dev.ccpocket.protocol.BridgeCreated
import dev.ccpocket.protocol.BridgeCredential
import dev.ccpocket.protocol.BridgeInfo
import dev.ccpocket.protocol.BridgeListing
import dev.ccpocket.protocol.BridgeRevoked
import dev.ccpocket.protocol.BridgeRunnerStatus
import dev.ccpocket.protocol.ConfigureBridgeRunner
import dev.ccpocket.protocol.ControlBridgeRunner
import dev.ccpocket.protocol.CreateBridge
import dev.ccpocket.protocol.PairTicket
import dev.ccpocket.protocol.RUNNER_RESTART
import dev.ccpocket.protocol.RUNNER_START
import dev.ccpocket.protocol.RUNNER_STOP
import java.io.File

/**
 * The OWNER-side bridge control plane (issue #91 follow-up): mint a bridge credential, list bridges +
 * their activity, revoke. Handled ONLY for a full-power owner device — a bridge's own capability
 * whitelist denies every frame here and [DeviceSessions] only reaches this dispatch for a non-restricted
 * device, so a bridge minting another bridge (self-escalation) is structurally impossible, exactly as
 * [ShareControl] prevents a guest re-sharing the machine.
 *
 * This is the wire twin of `pair --headless` / `bridges`, and BOTH now route through this one service
 * (PairLoopback delegates) — the same reuse folder-share already does. Two implementations of "mint a
 * bridge" would be two places for the name check, the workdir-must-exist rule, and the mint-serialization
 * dance to drift apart, and a drift there mis-classifies a credential's power.
 */
/**
 * Dispatch an owner control-plane frame (folder-share #115 / bridge #91 follow-up) to its service.
 * Returns true when [frame] was a control frame and was handled — the caller returns; false lets an
 * ordinary owner frame fall through to the router.
 *
 * ONE dispatcher for BOTH owner transports — the relay's DeviceSessions and the loopback LAN's
 * WsConnection — so the set of control frames can't drift between them (the drift already happened once:
 * the LAN path knew neither plane, which made Settings ▸ Shared/Bridges dead for the desktop app sitting
 * on the daemon's own machine). Callers must only invoke this for a FULL-POWER owner peer; both
 * restricted credential kinds have these frames whitelisted away before any dispatch.
 *
 * Null controls (daemon still wiring up, or a LAN-only `serve` with no relay link to mint over) → false,
 * so the frame surfaces the router's "unsupported" rather than silently vanishing.
 */
/** The exact frame set [dispatchOwnerControl] handles — kept adjacent so the two can't drift. The relay
 *  transport needs this to decide BEFORE dispatching: mints suspend on a reply that arrives through the
 *  same single reader loop, so they must run OFF that loop (head-of-line deadlock otherwise). */
fun isOwnerControlFrame(frame: dev.ccpocket.protocol.Frame): Boolean = when (frame) {
    is dev.ccpocket.protocol.CreateShare, is dev.ccpocket.protocol.ListShares, is dev.ccpocket.protocol.RevokeShare,
    is CreateBridge, is dev.ccpocket.protocol.ListBridges, is dev.ccpocket.protocol.RevokeBridge,
    is ConfigureBridgeRunner, is ControlBridgeRunner, is dev.ccpocket.protocol.DetachBridgeRunner,
    is dev.ccpocket.protocol.CreateCollaboratorTicket, is dev.ccpocket.protocol.ListCollaborators, is dev.ccpocket.protocol.RemoveCollaborator,
    -> true
    else -> false
}

/**
 * Owner frames that the ROUTER handles but which still must not run on the relay reader loop.
 *
 * [isOwnerControlFrame] covers the frames intercepted before the router; this covers the ones that go
 * through it and nevertheless end up inside the same mint. `pocket/review.contact_invite` is one:
 * ReviewOwnerService.invite → CollaboratorControl.createTicket → mintTicket, which suspends until the
 * relay answers with a `PairTicket` — a frame only THIS reader can deliver. Dispatched inline it waits
 * for a reply it is itself blocking, times out, and starves every other device for the duration.
 *
 * NOT a serialization point — that lives in the mint itself (issue #207): every ticket-backed mint
 * claims the one [BridgeRegistry.reserveMint] slot BEFORE its suspending relay round-trip and releases
 * it in a `finally` once the intent is recorded (or the mint failed), so two owner mints dispatched
 * concurrently can no longer both pass the pre-mint check and both burn a ticket.
 */
fun isOffReaderRouterFrame(frame: dev.ccpocket.protocol.Frame): Boolean =
    frame is dev.ccpocket.protocol.CreateReviewInvite

suspend fun dispatchOwnerControl(
    frame: dev.ccpocket.protocol.Frame,
    share: ShareControl?,
    bridge: BridgeControl?,
    collaborator: dev.ccpocket.daemon.handoff.CollaboratorControl? = null,
    emit: suspend (dev.ccpocket.protocol.ToPhone) -> Unit,
): Boolean {
    when (frame) {
        is dev.ccpocket.protocol.CreateShare -> emit((share ?: return false).create(frame))
        is dev.ccpocket.protocol.ListShares -> emit((share ?: return false).list())
        is dev.ccpocket.protocol.RevokeShare -> emit((share ?: return false).revoke(frame.deviceId))
        is CreateBridge -> emit((bridge ?: return false).create(frame))
        is dev.ccpocket.protocol.ListBridges -> emit((bridge ?: return false).list())
        is dev.ccpocket.protocol.RevokeBridge -> emit((bridge ?: return false).revoke(frame.name))
        is ConfigureBridgeRunner -> emit((bridge ?: return false).configureRunner(frame))
        is ControlBridgeRunner -> emit((bridge ?: return false).controlRunner(frame))
        is dev.ccpocket.protocol.DetachBridgeRunner -> emit((bridge ?: return false).detachRunner(frame.name))
        // Collaborator Link contact management (SESSION-HANDOFF.md §4.1) — same owner-only footing
        // no purpose argument: this frame is the App's SESSION HANDOFF invite and always has been, so it
        // takes the mint's historical default. The Review Center mints through pocket/review.contact_invite.
        is dev.ccpocket.protocol.CreateCollaboratorTicket -> emit((collaborator ?: return false).createTicket(frame.label))
        is dev.ccpocket.protocol.ListCollaborators -> emit((collaborator ?: return false).list())
        is dev.ccpocket.protocol.RemoveCollaborator -> emit((collaborator ?: return false).remove(frame.deviceId))
        else -> return false
    }
    return true
}

interface BridgeControl {
    suspend fun create(req: CreateBridge): BridgeCreated
    suspend fun list(): BridgeListing
    /** Revoke by name or deviceId — the owner's page has the name, the CLI accepts either. */
    suspend fun revoke(nameOrId: String): BridgeRevoked
    suspend fun configureRunner(req: ConfigureBridgeRunner): BridgeRunnerStatus
    suspend fun controlRunner(req: ControlBridgeRunner): BridgeRunnerStatus
    suspend fun detachRunner(name: String): BridgeRunnerStatus
}

class BridgeService(
    private val accountId: String,
    private val daemonPubB64: String,
    private val relayWsBase: String,
    private val registry: BridgeRegistry,
    private val mintTicket: suspend (headless: Boolean) -> PairTicket?,
    private val interactivePairingPending: () -> Boolean,
    private val revokeCredential: suspend (deviceId: String) -> Unit,
    /** live daemon conversations as (cwd, session) pairs — the bridge activity signal. */
    private val liveSessions: suspend () -> List<Pair<String, ActiveSession>>,
    /** daemon-managed adapter processes; null on a build/path that doesn't manage any. */
    private val runners: BridgeRunners? = null,
    /** live-conversation count for a convo-id set — the in-process bridges' activity pulse. */
    private val liveCount: suspend (Collection<String>) -> Int = { 0 },
    private val now: () -> Long = System::currentTimeMillis,
) : BridgeControl {
    private val log = logger("BridgeService")

    /** Is [name] already claimed by ANY of the three bridge row sources — a confirmed credential, a
     *  minted-but-unredeemed intent, or a managed runner (external adapter OR built-in engine)? A bridge's
     *  name is its stable identity across all three, so create must reject a collision in any of them; kept
     *  in ONE place so the three sources can't drift apart between here and [list] (issue #91 review). */
    private fun nameTaken(name: String): Boolean =
        registry.list().any { it.second.name == name } ||
            registry.pendingIntents().any { it.name == name } ||
            runners?.isManaged(name) == true

    override suspend fun create(req: CreateBridge): BridgeCreated {
        val name = req.name.trim()
        if (name.isEmpty()) return BridgeCreated(ok = false, error = "a bridge needs a name")
        if (nameTaken(name)) {
            return BridgeCreated(ok = false, error = "a bridge named \"$name\" already exists — revoke it first or pick another name")
        }
        if (req.workdirs.isEmpty()) {
            return BridgeCreated(ok = false, error = "pick at least one project — these are the only directories this bridge may ever open sessions in")
        }
        // every allow-listed root must EXIST as a directory: a typo'd root would mint a credential that can
        // open sessions somewhere unintended once that path later appears (mirrors the loopback path)
        val roots = req.workdirs.map { File(it) }
        val bad = roots.firstOrNull { !it.isAbsolute || !it.isDirectory }
        if (bad != null) return BridgeCreated(ok = false, error = "not an existing absolute directory: $bad")

        // BUILT-IN adapter (blank scriptPath): entirely in-process — no ticket, no redeem, no relay
        // round-trip to fail on. The spec (this bridge's whole authority) lives with its runner entry, and
        // the engine's BridgeGuard enforces it exactly as DeviceSessions would for an external bridge.
        val runnerSpec = req.runner
        if (runnerSpec != null && runnerSpec.scriptPath.isBlank()) {
            val rs = runners ?: return BridgeCreated(ok = false, error = "this daemon can't manage adapter processes")
            if (!rs.hasBuiltIn(runnerSpec.kind)) {
                return BridgeCreated(ok = false, error = "no built-in adapter for kind \"${runnerSpec.kind}\" — set an adapter script path")
            }
            val spec = BridgeSpec.clamped(
                name, roots.map { runCatching { it.canonicalFile.path }.getOrDefault(it.path) },
                req.maxSessions, req.opensPerMin, req.promptsPerMin, tier = req.tier,
                allowedCommands = req.allowedCommands,
            )
            rs.attachInProcess(spec.name, runnerSpec, spec)
            val startErr = rs.start(spec.name)
            if (startErr != null) {
                log.warn("built-in bridge \"${spec.name}\" created but didn't start: $startErr")
                return BridgeCreated(ok = false, error = startErr, runner = rs.state(spec.name))
            }
            log.info("built-in bridge \"${spec.name}\" created (workdirs=${spec.workdirs}, tier=${spec.tier})")
            return BridgeCreated(ok = true, runner = rs.state(spec.name))
        }

        // mint serialization (issue #91): the PSK arming is LIFO, so an interactive ticket outstanding at
        // the same time could cross-bind. Fail BEFORE burning a ticket rather than after.
        if (interactivePairingPending()) {
            return BridgeCreated(ok = false, error = "a phone pairing is still valid — try again in ~2 minutes")
        }
        // issue #207: claim the one mint slot BEFORE the suspending relay round-trip (see
        // CollaboratorService.createTicket — the bare intentPending() check raced overlapping mints)
        if (!registry.reserveMint()) {
            return BridgeCreated(ok = false, error = "another pairing is in progress — try again shortly")
        }
        try {
            val spec = BridgeSpec.clamped(
                name,
                roots.map { runCatching { it.canonicalFile.path }.getOrDefault(it.path) },
                req.maxSessions, req.opensPerMin, req.promptsPerMin,
                tier = req.tier,
                allowedCommands = req.allowedCommands,
            )
            val ticket = mintTicket(true) ?: return BridgeCreated(ok = false, error = "can't reach the relay — check the connection")
            if (!registry.recordIntent(ticket.ticket, spec, ttlMs = ticket.expiresInSec * 1000L + BridgeRegistry.INTENT_GRACE_MS)) {
                return BridgeCreated(ok = false, error = "another pairing is in progress — try again shortly")
            }
            log.info("bridge credential minted for \"${spec.name}\" (workdirs=${spec.workdirs}, tier=${spec.tier})")
            val credential = BridgeCredential(
                name = spec.name, accountId = accountId, daemonPub = daemonPubB64, ticket = ticket.ticket,
                relay = relayWsBase, workdirs = spec.workdirs, ttlSec = ticket.expiresInSec,
            )
            if (runnerSpec == null) return BridgeCreated(ok = true, credential = credential)

            // MANAGED external adapter (a script path was given): this is the only moment the plaintext ticket
            // exists, so hand it to the runner now and never return it to the owner — nothing to copy, nothing
            // to leave lying in a downloads folder.
            val rs = runners
            if (rs == null) return BridgeCreated(ok = false, error = "this daemon can't manage adapter processes")
            rs.attach(spec.name, runnerSpec, credential)
            val startErr = rs.start(spec.name)
            if (startErr != null) {
                // The credential is already minted and bound; the process just won't run. Keep the bridge and
                // report why, rather than silently leaving a half-made thing the page can't explain.
                log.warn("bridge \"${spec.name}\" minted but its adapter didn't start: $startErr")
                return BridgeCreated(ok = false, error = startErr, runner = rs.state(spec.name))
            }
            return BridgeCreated(ok = true, runner = rs.state(spec.name))
        } finally {
            registry.releaseMint()
        }
    }

    override suspend fun configureRunner(req: ConfigureBridgeRunner): BridgeRunnerStatus {
        val rs = runners ?: return BridgeRunnerStatus(req.name, ok = false, error = "this daemon can't manage adapter processes")
        // editing the PROJECT allow-list (issue #91): validate EXACTLY like create — each root must be an
        // existing absolute directory — then canonicalize before it becomes the new authority. null = unchanged.
        val canonicalWorkdirs = req.workdirs?.let { list ->
            if (list.isEmpty()) return BridgeRunnerStatus(req.name, ok = false, error = "pick at least one project — a bridge with no allow-listed directory can't open anything")
            val roots = list.map { File(it) }
            roots.firstOrNull { !it.isAbsolute || !it.isDirectory }?.let {
                return BridgeRunnerStatus(req.name, ok = false, error = "not an existing absolute directory: $it")
            }
            roots.map { runCatching { it.canonicalFile.path }.getOrDefault(it.path) }
        }
        // editing the Bash command allow-list (issue #91): null = unchanged; a list REPLACES it. Normalized
        // (trim/dedupe/cap) the same way create does; still gated per-command by BridgeCommandPolicy at run time.
        val newAllowedCommands = req.allowedCommands?.let { BridgeSpec.normalizeAllowedCommands(it) }
        // Persistence, old-authority teardown and the replacement start are one per-name lifecycle
        // transaction. Concurrent owner control frames cannot rebuild the old config in the middle.
        val err = rs.reconfigure(
            req.name,
            req.spec,
            req.mergeEnv,
            canonicalWorkdirs,
            newAllowedCommands,
            restartIfRunning = true,
        )
        return BridgeRunnerStatus(req.name, ok = err == null, error = err, state = rs.state(req.name))
    }

    override suspend fun controlRunner(req: ControlBridgeRunner): BridgeRunnerStatus {
        val rs = runners ?: return BridgeRunnerStatus(req.name, ok = false, error = "this daemon can't manage adapter processes")
        val err = when (req.action) {
            RUNNER_START -> rs.start(req.name)
            RUNNER_STOP -> rs.stop(req.name)
            RUNNER_RESTART -> rs.restart(req.name)
            else -> "unknown action \"${req.action}\"" // refuse rather than guess
        }
        return BridgeRunnerStatus(req.name, ok = err == null, error = err, state = rs.state(req.name))
    }

    override suspend fun detachRunner(name: String): BridgeRunnerStatus {
        val rs = runners ?: return BridgeRunnerStatus(name, ok = false, error = "this daemon can't manage adapter processes")
        val err = rs.detach(name)
        return BridgeRunnerStatus(name, ok = err == null, error = err, state = rs.state(name))
    }

    override suspend fun list(): BridgeListing {
        val live = liveSessions()
        val confirmed = registry.bridges().map { (deviceId, spec, createdAt) ->
            // a bridge's sessions are tagged with its name as [ActiveSession.origin] — an exact marker,
            // so this never miscounts the owner's own sessions that happen to sit under the same workdir
            val active = live.count { (_, s) -> s.origin == spec.name }
            BridgeInfo(
                name = spec.name,
                workdirs = spec.workdirs,
                deviceId = deviceId,
                pendingTicket = false,
                online = active > 0,
                activeSessions = active,
                maxSessions = spec.maxSessions,
                createdAt = createdAt,
                tier = spec.tier,
                allowedCommands = spec.allowedCommands,
                runner = runners?.state(spec.name, BridgeRunners.LIST_LOG_LINES),
            )
        }
        // minted-but-unredeemed rows: the adapter hasn't connected yet. Shown so a just-created bridge
        // doesn't vanish from the page between "Create" and the adapter's first connect.
        val confirmedNames = confirmed.map { it.name }.toSet()
        val pending = registry.pendingIntents(now())
            .filter { it.kind == CredentialKind.BRIDGE && it.name !in confirmedNames }
            .map { spec ->
                BridgeInfo(
                    name = spec.name, workdirs = spec.workdirs, deviceId = null, pendingTicket = true,
                    maxSessions = spec.maxSessions, createdAt = now(), tier = spec.tier,
                    allowedCommands = spec.allowedCommands,
                    runner = runners?.state(spec.name, BridgeRunners.LIST_LOG_LINES),
                )
            }
        // BUILT-IN (in-process) bridges: no registry entry to enumerate — their authority lives with their
        // runner. online = the engine's feishu link is actually up, not merely "the entry exists".
        val inProcess = runners?.inProcessSpecs().orEmpty()
            .filter { it.name !in confirmedNames }
            .map { spec ->
                val active = runners?.inProcessActive(spec.name) { ids -> liveCount(ids) } ?: 0
                val state = runners?.state(spec.name, BridgeRunners.LIST_LOG_LINES)
                BridgeInfo(
                    name = spec.name, workdirs = spec.workdirs, deviceId = null, pendingTicket = false,
                    online = state?.running == true, activeSessions = active,
                    maxSessions = spec.maxSessions, createdAt = 0, tier = spec.tier,
                    allowedCommands = spec.allowedCommands, runner = state,
                )
            }
        return BridgeListing(confirmed + pending + inProcess)
    }

    override suspend fun revoke(nameOrId: String): BridgeRevoked {
        // an in-process bridge has no credential to revoke — detaching its runner IS the revoke (the
        // engine stops, the entry and its routes die)
        if (runners?.isInProcess(nameOrId) == true) {
            val err = runners.detach(nameOrId)
            log.info("built-in bridge \"$nameOrId\" revoked by owner")
            return BridgeRevoked(nameOrId, ok = err == null, error = err)
        }
        val match = registry.list().filter { (id, spec) -> id == nameOrId || spec.name == nameOrId }
        return when {
            match.isEmpty() -> BridgeRevoked(nameOrId, ok = false, error = "not an active bridge")
            match.size > 1 -> BridgeRevoked(nameOrId, ok = false, error = "multiple bridges match — use the deviceId")
            else -> {
                val (id, spec) = match.single()
                revokeCredential(id) // local prune (its handshake key dies) + relay RevokeDevice + socket close
                // A managed adapter must die WITH its credential. Left running it would spin forever
                // retrying a handshake its key can no longer pass — a process the owner thought they
                // deleted, still holding their IM app secret and still logging failures.
                runners?.takeIf { it.isManaged(spec.name) }?.detach(spec.name)
                log.info("bridge \"${spec.name}\" (${id.take(8)}…) revoked by owner")
                BridgeRevoked(spec.name, ok = true)
            }
        }
    }
}
