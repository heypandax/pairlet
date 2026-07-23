package dev.ccpocket.app

/** Whether "launch Dia with the CDP debug port" is available here — true only on desktop macOS with Dia
 *  installed. The composer's Dia button hides itself entirely when this is false (mobile, or no Dia). */
expect fun diaCdpSupported(): Boolean

/**
 * QUIT any running Dia, then relaunch it with `--remote-debugging-port=[port]` and poll until the port
 * answers. Dia (Chromium) forwards a flag-carrying launch to an ALREADY-running instance, so the debug port
 * only opens on a genuinely fresh start — hence the quit-first (the key trap in docs-dia-cdp-launch-需求.md).
 * The owner picked "kill the main Dia and reuse its logged-in profile", so this closes the current Dia
 * (window state is lost) rather than spinning a blank throwaway profile. Blocking work runs off the caller's
 * thread inside the actual. Non-desktop: an unsupported no-op.
 */
expect suspend fun launchDiaCdp(port: Int = 9222): DiaCdpResult

/** [ok] = the CDP port answered; [message] a short human status for the composer feedback. */
data class DiaCdpResult(val ok: Boolean, val message: String)
