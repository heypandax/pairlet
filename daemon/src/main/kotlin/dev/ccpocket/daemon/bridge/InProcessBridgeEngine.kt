package dev.ccpocket.daemon.bridge

import java.io.File

/**
 * A BUILT-IN bridge adapter that runs INSIDE the daemon (no external process): it holds its own IM
 * long-connection and drives Claude sessions in-process, enforcing the SAME [BridgeGuard] an external
 * bridge's frames pass in DeviceSessions. [BridgeRunners] manages instances 1:1 with a managed runner
 * entry (start/stop/restart/detach), keyed by name.
 *
 * This interface is what removes the "generic infrastructure depends on a concrete IM" layering leak
 * (issue #91 design review): BridgeRunners and BridgeService talk to THIS, and a new IM only needs an
 * implementation plus a factory registered by kind ([BridgeRunners.registerEngine]) — no `feishu`-shaped
 * branch anywhere in the runner or the control plane. The wire kind string (e.g. `RUNNER_KIND_FEISHU`)
 * stays in the protocol as an agreed VALUE, but no daemon code branches on a specific one.
 */
interface InProcessBridgeEngine {
    /** True while the IM link is up and serving. */
    val running: Boolean

    /** The last start/run error surfaced to the owner's bridge card, or null. */
    val lastError: String?

    /** Convo-ids this engine has ever opened — intersected with live registry state for the "active now"
     *  pulse on the management pages, exactly like an external bridge's guard. */
    fun ownedConvoIds(): Set<String>

    /** Connect and begin serving. Returns null on success, else a human-readable error. Idempotent:
     *  a second call while already running is a no-op. */
    fun start(): String?

    /** Drop the IM link so the engine can be re-[start]ed (this is how restart works). Does NOT end
     *  in-flight Claude convos — a restart preserves continuity; [closeOwnedConvos] is the revoke path. */
    fun stop()

    /** Permanent teardown: [stop] plus release of the engine's own resources (coroutine scope, …). */
    fun shutdown()

    /** Force-close the Claude convos this engine opened. Called on REVOKE/detach only, so a bridge's
     *  running turns END with its credential instead of outliving it until idle-reap (issue #91 A1). */
    suspend fun closeOwnedConvos()
}

/** Builds an [InProcessBridgeEngine] for one managed bridge. Registered per IM kind via
 *  [BridgeRunners.registerEngine], so the runner stays kind-agnostic. */
fun interface InProcessEngineFactory {
    fun create(
        name: String,
        spec: BridgeSpec,
        env: Map<String, String>,
        dir: File,
        logLine: (String) -> Unit,
    ): InProcessBridgeEngine
}
