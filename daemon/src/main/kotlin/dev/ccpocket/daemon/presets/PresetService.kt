package dev.ccpocket.daemon.presets

import dev.ccpocket.daemon.util.logger
import dev.ccpocket.protocol.AuthBlockReason
import dev.ccpocket.protocol.AuthBlocker
import dev.ccpocket.protocol.DeletePreset
import dev.ccpocket.protocol.Frame
import dev.ccpocket.protocol.PresetsState
import dev.ccpocket.protocol.SavePreset

/**
 * Drives preset CRUD + activation over pocket/presets.* — the API-key counterpart of the OAuth
 * account switch in AuthService, sharing its switch semantics and the registry's guard suppliers:
 *
 * - A switch (activate / deactivate / delete-of-active) is REFUSED while any conversation is
 *   mid-task (turn in flight / background jobs): its running agent process holds the OLD env for
 *   its next turn, and killing work under the user is not ours to decide. The refusal carries
 *   [PresetsState.blockers] so the client renders the same actionable card as an account switch.
 * - Merely-open idle conversations are closed automatically instead: their processes hold the old
 *   env too, but they resume from disk like any cold session — the next launch injects the new env.
 * - [force] = the user saw the blocker list and chose "stop all & switch".
 *
 * Every entry point answers with one [PresetsState]. Log discipline: names/counts only — never a
 * token, base URL, or model value (values can be secrets-adjacent; the token IS a secret).
 */
class PresetService(
    private val store: PresetStore,
    private val busyConversations: suspend () -> List<AuthBlocker>,
    private val closeIdleConversations: suspend () -> Int,
    private val closeBusyConversations: suspend () -> Int = { 0 },
) {
    private val log = logger("Presets")

    suspend fun sendState(emit: suspend (Frame) -> Unit) = emit(state())

    suspend fun save(req: SavePreset, emit: suspend (Frame) -> Unit) {
        val refusal = store.save(req)
        if (refusal != null) log.info("preset save refused (${refusal.second ?: "general"})")
        else log.info("preset ${if (req.id == null) "created" else "updated"}")
        emit(state(error = refusal?.first, fieldError = refusal?.second))
    }

    suspend fun delete(req: DeletePreset, emit: suspend (Frame) -> Unit) {
        // deleting the ACTIVE preset changes what new sessions run on → full switch semantics first
        if (store.activeId == req.id && !switchGate(req.force, "preset delete", emit)) return
        val ok = store.delete(req.id)
        log.info(if (ok) "preset deleted" else "preset delete: unknown id")
        emit(state(error = if (ok) null else "Preset not found — it may have been deleted."))
    }

    suspend fun activate(id: String?, force: Boolean, emit: suspend (Frame) -> Unit) {
        if (id == store.activeId) { emit(state()); return } // no-op switch must not close anything
        if (!switchGate(force, "preset switch", emit)) return
        val error = store.activate(id)
        if (error == null) log.info(if (id == null) "preset deactivated" else "preset activated")
        emit(state(error = error))
    }

    /** The shared mid-task guard + idle auto-close (mirrors AuthService.login). True = proceed;
     *  false = refused, a blockers state was already emitted. */
    private suspend fun switchGate(force: Boolean, why: String, emit: suspend (Frame) -> Unit): Boolean {
        if (force) closeBusyConversations().takeIf { it > 0 }?.let { log.info("force-closed $it busy conversation(s) for $why") }
        val blockers = busyConversations()
        if (blockers.isNotEmpty()) {
            emit(state(error = "Sessions on this computer are still working: ${describe(blockers)} — stop them (or wait) first", blockers = blockers))
            return false
        }
        closeIdleConversations().takeIf { it > 0 }?.let { log.info("closed $it idle conversation(s) for $why") }
        return true
    }

    private fun state(error: String? = null, fieldError: String? = null, blockers: List<AuthBlocker> = emptyList()) =
        PresetsState(presets = store.summaries(), activeId = store.activeId, error = error, fieldError = fieldError, blockers = blockers)

    private fun describe(blockers: List<AuthBlocker>): String = blockers.joinToString("; ") { b ->
        val name = b.cwd.substringAfterLast('/').substringAfterLast('\\').ifBlank { b.cwd }
        when (b.reason) {
            AuthBlockReason.EXECUTING -> "$name is mid-turn"
            AuthBlockReason.BACKGROUND_JOBS ->
                "$name has ${b.jobLabels.size.coerceAtLeast(1)} background task${if (b.jobLabels.size == 1) "" else "s"} running"
            AuthBlockReason.UNKNOWN -> "$name is still working" // decode fallback — this daemon never emits it
        }
    }
}
