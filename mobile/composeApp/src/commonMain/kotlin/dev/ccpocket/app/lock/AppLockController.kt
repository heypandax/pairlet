package dev.ccpocket.app.lock

import androidx.compose.runtime.mutableStateOf
import dev.ccpocket.app.epochMillis
import dev.ccpocket.app.secure.SecureStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** How soon cc-pocket re-locks after you leave it (design frame C). Absent/garbage → IMMEDIATELY: the safe
 *  default for a high-privilege app, matching the design's checked default. */
enum class AutoLockDelay {
    IMMEDIATELY,
    AFTER_1_MIN;

    companion object {
        fun from(name: String?): AutoLockDelay = entries.firstOrNull { it.name == name } ?: IMMEDIATELY
    }
}

/** The gate's visual sub-state — the five frames from the design (idle / verifying / failed / passcode
 *  fallback / biometry locked out). Drives copy + which element the terracotta accent lands on. */
enum class GateVisual { IDLE, AUTHENTICATING, FAILED, FALLBACK, LOCKED_OUT }

/** Persistence seam for the two App Lock prefs — the real impl hits [SecureStore] (same store as every other
 *  user pref); unit tests pass an in-memory fake so they never touch the on-disk store. */
interface LockPrefs {
    var enabled: Boolean
    var autoLock: AutoLockDelay
}

/** [LockPrefs] backed by the shared [SecureStore] (String-only, so bool → "1"/"0", enum → .name). */
class SecureStoreLockPrefs : LockPrefs {
    override var enabled: Boolean
        get() = SecureStore.getString(K_ENABLED) == "1"
        set(v) = SecureStore.putString(K_ENABLED, if (v) "1" else "0")
    override var autoLock: AutoLockDelay
        get() = AutoLockDelay.from(SecureStore.getString(K_AUTOLOCK))
        set(v) = SecureStore.putString(K_AUTOLOCK, v.name)

    companion object {
        const val K_ENABLED = "app_lock_enabled"    // "1" = on (absent/other = off; the gate is opt-in)
        const val K_AUTOLOCK = "app_lock_autolock"   // AutoLockDelay.name
    }
}

/**
 * The App Lock gate state machine (issue #109) — a biometric front door for this high-privilege app.
 *
 * Pure and testable: all state is Compose [mutableStateOf] the UI reads directly; the biometric call and the
 * wall-clock are injected ([biometrics], [now]) so the whole lock/unlock/auto-lock/cover logic is exercised in
 * plain unit tests. The platform bits (LAContext / BiometricPrompt / snapshot cover) live behind [Biometrics]
 * and the lifecycle hooks that call the methods here.
 *
 * Lifecycle contract (wired in App()):
 *  - [onWillObscure]  — app is about to be obscured (app-switcher / resign-active): draw the privacy cover.
 *  - [onBackground]   — app fully backgrounded (stop / didEnterBackground): arm the auto-lock timer.
 *  - [onForeground]   — app returned (resume / didBecomeActive): re-lock per policy, else reveal.
 *
 * The lock decision is deliberately keyed on the *background* signal (not resign/pause): presenting the OS
 * biometric sheet resigns-active but never backgrounds the app, so the gate can never re-lock itself into a
 * loop while its own prompt is up.
 */
class AppLockController(
    private val scope: CoroutineScope,
    private val biometrics: Biometrics,
    private val prefs: LockPrefs = SecureStoreLockPrefs(),
    private val now: () -> Long = ::epochMillis,
) {
    // ── persisted config (mirrors the repo's mutableStateOf + setter pref pattern) ──
    val enabled = mutableStateOf(prefs.enabled)
    val autoLock = mutableStateOf(prefs.autoLock)

    // ── runtime gate state (read by the gate composable) ──
    /** True while the gate covers the app and blocks all content. Cold start locks iff the gate is enabled. */
    val locked = mutableStateOf(prefs.enabled)
    val visual = mutableStateOf(GateVisual.IDLE)
    /** Opaque branded privacy cover shown while unlocked-but-obscured, so the app-switcher never leaks content. */
    val covered = mutableStateOf(false)
    /** Settings switch is mid-enable, waiting on the one-time verify (design frame B). */
    val enabling = mutableStateOf(false)

    // stable within a process → cached so the gate's per-frame pulse recomposition doesn't rebuild an LAContext
    val biometryKind: BiometryKind by lazy { biometrics.kind() }
    fun canUseBiometrics(): Boolean = biometrics.canAuthenticate()

    private var failures = 0
    private var authInFlight = false
    private var backgroundedAt: Long? = null

    // ── lifecycle ──────────────────────────────────────────────────────────────
    /** About to be obscured (app-switcher / control-center). Mask content before the OS snapshots it. Skipped
     *  while our own prompt is up (that resign-active is ours, not a real task switch). */
    fun onWillObscure() {
        if (enabled.value && !locked.value && !authInFlight) covered.value = true
    }

    /** Fully backgrounded — remember when, so [onForeground] can apply the auto-lock delay. */
    fun onBackground() {
        if (!enabled.value || authInFlight) return
        backgroundedAt = now()
        if (!locked.value) covered.value = true
    }

    /** Back in the foreground — re-lock if the policy says so, otherwise drop the cover. */
    fun onForeground() {
        if (authInFlight) return
        if (enabled.value && !locked.value && shouldRelock()) lock()
        if (!locked.value) covered.value = false
    }

    private fun shouldRelock(): Boolean {
        val bg = backgroundedAt ?: return false
        return when (autoLock.value) {
            AutoLockDelay.IMMEDIATELY -> true
            AutoLockDelay.AFTER_1_MIN -> now() - bg >= AFTER_1_MIN_MS
        }
    }

    private fun lock() {
        locked.value = true
        visual.value = GateVisual.IDLE
        covered.value = false
        failures = 0
    }

    // ── unlocking (called by the gate) ─────────────────────────────────────────
    /** Biometrics-only unlock — the primary path; yields the retry / lockout states. */
    fun authenticate(reason: String) = runAuth(reason, allowCredential = false)

    /** Explicit device-passcode unlock — the fallback the gate surfaces after failures / lockout. */
    fun useDevicePasscode(reason: String) = runAuth(reason, allowCredential = true)

    private fun runAuth(reason: String, allowCredential: Boolean) {
        if (authInFlight || !locked.value) return
        authInFlight = true
        visual.value = GateVisual.AUTHENTICATING
        scope.launch {
            val r = biometrics.authenticate(reason, allowCredential)
            authInFlight = false
            applyResult(r)
        }
    }

    /** Pure result → gate transition. Package-visible so unit tests can drive the state machine directly. */
    internal fun applyResult(r: AuthResult) {
        when (r) {
            AuthResult.Success -> unlockNow()
            AuthResult.Failed -> {
                failures++
                visual.value = if (failures >= MAX_BIOMETRIC_ATTEMPTS) GateVisual.FALLBACK else GateVisual.FAILED
            }
            AuthResult.LockedOut -> visual.value = GateVisual.LOCKED_OUT
            // biometrics vanished (un-enrolled) — the passcode is the only door left
            AuthResult.Unavailable -> visual.value = GateVisual.FALLBACK
            AuthResult.Canceled -> if (visual.value == GateVisual.AUTHENTICATING) {
                visual.value = if (failures >= MAX_BIOMETRIC_ATTEMPTS) GateVisual.FALLBACK else GateVisual.IDLE
            }
        }
    }

    private fun unlockNow() {
        locked.value = false
        visual.value = GateVisual.IDLE
        covered.value = false
        failures = 0
        backgroundedAt = null
    }

    // ── Settings ───────────────────────────────────────────────────────────────
    /** Turning the switch ON verifies once before it takes effect; a cancel leaves it OFF (design frame B). */
    fun requestEnable(reason: String) {
        if (enabled.value || enabling.value) return
        enabling.value = true
        scope.launch {
            val r = biometrics.authenticate(reason, allowCredential = false)
            enabling.value = false
            if (r == AuthResult.Success) {
                enabled.value = true
                prefs.enabled = true
            }
        }
    }

    fun disable() {
        enabling.value = false
        enabled.value = false
        prefs.enabled = false
        // turning the lock off must not strand the user behind the gate
        locked.value = false
        covered.value = false
        visual.value = GateVisual.IDLE
        failures = 0
    }

    fun setAutoLock(delay: AutoLockDelay) {
        if (delay == autoLock.value) return
        autoLock.value = delay
        prefs.autoLock = delay
    }

    companion object {
        /** Soft biometric failures before the gate promotes the passcode button (design frames 4–5). */
        const val MAX_BIOMETRIC_ATTEMPTS = 2
        const val AFTER_1_MIN_MS = 60_000L
    }
}
