package dev.ccpocket.app.push

/**
 * A push token from the platform. [platform] tags which relay-side sender handles it:
 * "apns"/"apns_sandbox" (iOS, by build env) or "fcm" (Android). Future domestic-vendor channels
 * ("xiaomi"/"huawei"/…) reuse the same tag mechanism.
 */
data class PushToken(val platform: String, val token: String)

/**
 * The OS's answer to "may this app post notifications". Only ever read, never assumed: a token that
 * arrives says nothing about whether the user later revoked permission in Settings, and a permission
 * that exists says nothing about whether a token was ever obtained.
 */
enum class PushAuthorization {
    /** Never asked — a prompt is still available (and is the only thing that can move this). */
    NOT_DETERMINED,
    /** The user said no (or revoked later). Retrying registration cannot help; only Settings can. */
    DENIED,
    AUTHORIZED,
    /** iOS quiet delivery — deliverable, so it counts as allowed. */
    PROVISIONAL,
    /** iOS App Clip style, time-boxed. Allowed while it lasts. */
    EPHEMERAL,
    /** The platform cannot tell us (desktop, or a read that failed). Never treated as DENIED. */
    UNKNOWN,
}

/** Why the platform could not hand over a token. Split by what the client should DO about it. */
enum class PushRegistrationFailure {
    /** Transient: APNs/FCM unreachable. Worth retrying on a budget. */
    NETWORK,
    /** The user refused (or revoked). Stop retrying; offer a route to system Settings instead. */
    DENIED,
    /** No push stack on this build/device (no Play Services, desktop). Stop — it will not appear. */
    UNSUPPORTED,
    UNKNOWN,
}

/**
 * Registers the device for remote push and surfaces the resulting token. Mirrors the [dev.ccpocket.app
 * .telemetry.Telemetry] seam — the platform actual hides APNs/FCM behind this single API so business
 * code never imports them. Desktop is a no-op (not a push target).
 *
 * The split between [start] and [requestToken] is deliberate: wiring the sink must be free of side
 * effects, because the app now wires it on every attach, while ASKING the OS is the step that may show
 * a permission dialog and must stay under the coordinator's control (see [PushRegistrar]). Before this
 * split `start` did both, so the only way to observe a token was to risk prompting for it.
 */
expect object PushController {
    /** Wire the token sink (replays a token that already arrived). Does NOT itself request registration
     *  any more — call [requestToken] for that. Idempotent; the last caller owns the sink. */
    fun start(onToken: (PushToken) -> Unit)

    /** Ask the platform for the current token. [prompt] = true may show the system permission dialog when
     *  authorization is NOT_DETERMINED; false registers silently only if already authorized. Idempotent.
     *  [onFailed] receives THIS ask's refusal — possibly synchronously, from inside this call. Where the
     *  platform reports failures without saying which ask they answer (iOS), the latest ask's sink gets it. */
    fun requestToken(prompt: Boolean, onFailed: (PushRegistrationFailure) -> Unit)

    /** Async read of the OS authorization state; UNKNOWN where the platform cannot tell (desktop). */
    fun readAuthorization(cb: (PushAuthorization) -> Unit)

    /** Open this app's OS notification settings; no-op on desktop. */
    fun openNotificationSettings()
}
