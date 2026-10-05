package dev.ccpocket.app.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import dev.ccpocket.app.DeepLink
import dev.ccpocket.app.PushRoute
import dev.ccpocket.app.SessionRoute
import dev.ccpocket.app.data.PocketRepository
import dev.ccpocket.app.telemetry.TelEvent
import dev.ccpocket.app.telemetry.Telemetry
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * The app root's launch-time work, held behind the one-time data disclosure (App Review 5.1.2(i) — see
 * docs/APP-STORE-REJECTIONS.md R4: data leaves the device only after the user agreed).
 *
 * The consent screen gates what RENDERS, but these effects sit above it: the app_launch event, the reconnect of
 * an already-paired computer, and the routes the OS hands in — a `ccpocket://` link (pairing redeems over the
 * network on sight) and a tapped push (it connects to open its session). Until consent they do nothing, and a
 * route that arrived meanwhile — the system camera cold-starting the app on a pairing QR — stays parked in its
 * flow and is handled the moment the user agrees, not dropped. Consent never reverts, so for anyone who already
 * accepted every effect runs at first composition exactly as before.
 */
@Composable
internal fun ConsentGatedLaunchEffects(
    repo: PocketRepository,
    links: MutableStateFlow<String?> = DeepLink.pending,
    pushes: MutableStateFlow<SessionRoute?> = PushRoute.pending,
) {
    val consented = repo.privacyConsented.value
    LaunchedEffect(consented) {
        if (!consented) return@LaunchedEffect
        Telemetry.track(TelEvent.AppLaunch)
        if (repo.paired.value != null) repo.startRelay() // already paired -> straight to the list
    }
    // §7: ONE parse for every entry point — a deep link must never redeem on sight.
    val link by links.collectAsState()
    LaunchedEffect(link, consented) {
        if (consented) link?.let { repo.handleIncomingLink(it); links.value = null }
    }
    // a tapped task-complete push deep-links straight into its session (connecting first if needed)
    val push by pushes.collectAsState()
    LaunchedEffect(push, consented) {
        if (consented) push?.let { repo.requestOpenSession(it.workdir, it.sessionId); pushes.value = null }
    }
}
