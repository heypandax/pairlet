package dev.ccpocket.daemon.disk

import dev.ccpocket.daemon.approval.ApprovalCoordinator
import dev.ccpocket.daemon.approval.ApprovalOutcome
import dev.ccpocket.daemon.approval.ApprovalSource
import dev.ccpocket.daemon.util.logger
import dev.ccpocket.protocol.Decision
import dev.ccpocket.protocol.ExportFile
import dev.ccpocket.protocol.FileContent
import dev.ccpocket.protocol.Frame
import dev.ccpocket.protocol.PendingApproval
import dev.ccpocket.protocol.PermissionAsk
import dev.ccpocket.protocol.PermissionMode
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * The approval gate in front of [ExportFile] (issue #67 v2 / #79): exporting a file the session did NOT
 * change — Bash/script-generated documents and read-only files that [SessionFilesService.readFile]'s
 * changed-set firewall deliberately refuses.
 *
 * The widening is opt-in, per-file, human-approved, and workspace-bounded — the opposite of an
 * arbitrary-path read:
 *  1. a path already in the changed set is served straight away (ReadFile would serve it anyway);
 *  2. the export is bound to the LIVE conversation's own workdir ([liveWorkdirOf]) — a client can't
 *     point the gate at a different root, and a closed conversation can't export at all;
 *  3. the path must sit canonically inside that workdir ([SessionFilesService.containedForExport] —
 *     `..`/symlink escapes are refused outright, never even prompted for);
 *  4. everything else goes through the SAME approval firewall as the quick terminal ([PermissionAsk] →
 *     [PermissionVerdict]), and every ask is a ONE-OFF decision ([PermissionAsk.neverRemember], like a
 *     plan approval): approving one file must not silently open the whole project tree, so no allow-rule
 *     is recorded server-side even if a client claims remember=true. Auto-allow exists only in bypass
 *     mode; no answer times out to deny. Approval routing is by askId ("xp-" prefix keeps it distinct
 *     from agent/shell asks, but [onVerdict] matches purely by pending-map membership).
 *
 * Every refusal rides [FileContent.error] as a readable reason — never a silent drop.
 */
class FileExportService(
    private val scope: CoroutineScope,
    private val liveWorkdirOf: suspend (convoId: String) -> Path?,
    private val coordinator: ApprovalCoordinator = ApprovalCoordinator(scope),
    // seams for unit tests — production wiring keeps the SessionFilesService defaults
    private val isChanged: (dev.ccpocket.protocol.AgentKind, String, String, String) -> Boolean = SessionFilesService::isChanged,
    private val serveChanged: (dev.ccpocket.protocol.AgentKind, String, String, String) -> FileContent = SessionFilesService::readFile,
    private val gateOf: (String, String) -> SessionFilesService.ExportGate = SessionFilesService::containedForExport,
    private val serveApproved: (String, String, String) -> FileContent = SessionFilesService::serveExport,
    private val verdictTimeoutMs: Long = VERDICT_TIMEOUT_MS,
) {
    private val log = logger("FileExport")

    private val inFlight = ConcurrentHashMap.newKeySet<String>() // one export awaiting approval per convo

    /** Serve [req] through the gate above; emits exactly one [FileContent] (served, or ok=false with why). */
    suspend fun run(req: ExportFile, mode: PermissionMode?, emit: suspend (Frame) -> Unit) {
        fun fail(error: String) = FileContent(req.workdir, req.sessionId, req.path, ok = false, error = error)

        // fast path: in the changed set → ReadFile already serves this; same caps, no prompt, no widening
        if (isChanged(req.agent, req.workdir, req.sessionId, req.path)) {
            emit(serveChanged(req.agent, req.workdir, req.sessionId, req.path))
            return
        }
        // the widening starts here — bind it to the live conversation's OWN workdir, not the client's claim
        val live = liveWorkdirOf(req.convoId)
        if (live == null) {
            emit(fail("the session is no longer open on the computer — reopen it to export files"))
            return
        }
        if (ProjectPaths.canonicalKey(live.toString()) != ProjectPaths.canonicalKey(req.workdir)) {
            emit(fail("exports are scoped to the open session's own project folder"))
            return
        }
        // pre-ask containment: an escaping / missing path is refused OUTRIGHT — the owner is never
        // prompted about a path the gate could not serve anyway
        when (gateOf(req.workdir, req.path)) {
            is SessionFilesService.ExportGate.Allowed -> {}
            SessionFilesService.ExportGate.Outside -> {
                log.info("export refused (outside workdir): ${req.path}")
                emit(fail("that path is outside this session's project folder, so it can't be exported"))
                return
            }
            SessionFilesService.ExportGate.Missing -> {
                emit(fail("that file no longer exists on the computer"))
                return
            }
        }
        // server-side backpressure, like ShellService: a buggy/hostile client must not be able to
        // stack unbounded pending approval cards
        if (!inFlight.add(req.convoId)) {
            emit(fail("an export is already awaiting approval in this session"))
            return
        }
        try {
            val approved =
                if (mode == PermissionMode.BYPASS_PERMISSIONS) {
                    coordinator.recordAuto(ApprovalSource.EXPORT, req.convoId, TOOL, null, "bypass-permissions")
                    true
                } else {
                    askApproval(req, mode, emit)
                }
            if (!approved) {
                emit(fail("the export request was not approved"))
                return
            }
            // §18.1 P1-5: the approval wait can outlive the session — re-verify RIGHT BEFORE the read.
            // An export whose session died while the card was pending never serves.
            if (liveWorkdirOf(req.convoId) == null) {
                emit(fail("the session closed before the export was approved"))
                return
            }
            // serveExport re-runs the containment at READ time: the approval wait is up to 30s, and a
            // path component swapped for a symlink in that window must not turn an approved in-tree
            // path into an out-of-tree read (TOCTOU). The residual sub-ms window between the re-check
            // and the read is out of this gate's threat model — winning it takes local write access,
            // which already reads everything the daemon user can.
            emit(serveApproved(req.workdir, req.sessionId, req.path))
        } finally {
            inFlight.remove(req.convoId)
        }
    }

    private suspend fun askApproval(req: ExportFile, mode: PermissionMode?, emit: suspend (Frame) -> Unit): Boolean {
        val askId = "xp-" + UUID.randomUUID()
        val gate = CompletableDeferred<Boolean>()
        val ask = PermissionAsk(
            req.convoId, askId, TOOL,
            inputPreview = req.path, mode = mode,
            title = "Export file to phone",
            neverRemember = true, // one file ≠ the whole tree: never offer (or honor) "Always allow"
            timeoutSec = (verdictTimeoutMs / 1000).toInt(),
            grantOptions = listOf("once"), // M2: an export stays strictly one-off — no task/session grant
        )
        // [PermissionVerdict.remember] is deliberately ignored: export asks are one-off
        // ([PermissionAsk.neverRemember]), so nothing is recorded even when a client claims remember=true.
        coordinator.submit(ask, ApprovalSource.EXPORT, owner = this, timeoutMs = verdictTimeoutMs, emit = emit) { outcome ->
            gate.complete(outcome is ApprovalOutcome.Answered && outcome.verdict.decision == Decision.ALLOW)
        }
        return gate.await()
    }

    /** Owner-facing snapshot rows for one-off export approvals. */
    fun pendingApprovals(): List<PendingApproval> = coordinator.rowsFor(this)

    private companion object {
        const val TOOL = "ExportFile"
        const val VERDICT_TIMEOUT_MS = 30_000L
    }
}
