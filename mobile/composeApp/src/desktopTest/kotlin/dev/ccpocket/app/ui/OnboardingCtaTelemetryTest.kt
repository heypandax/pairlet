package dev.ccpocket.app.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SkikoComposeUiTest
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.unit.Density
import dev.ccpocket.app.GITHUB_REPO_URL
import dev.ccpocket.app.resources.Res
import dev.ccpocket.app.resources.action_back
import dev.ccpocket.app.resources.fr_cta
import dev.ccpocket.app.resources.fr_enter_code
import dev.ccpocket.app.resources.fr_install_pkg
import dev.ccpocket.app.resources.fr_trust_github
import dev.ccpocket.app.resources.pair_demo
import dev.ccpocket.app.str
import dev.ccpocket.app.telemetry.TelEvent
import dev.ccpocket.app.telemetry.TelKey
import dev.ccpocket.app.telemetry.telemetryTap
import dev.ccpocket.app.theme.PocketTheme
import dev.ccpocket.app.webUrlTap
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The first-run guide's controls, observed through the telemetry seam (issue #342).
 *
 * `onboarding_shown` only ever proved the screen was RENDERED, so a guide people opened and abandoned
 * looked exactly like one they read and acted on. Every control now names itself on one `onboarding_cta`
 * event; what this file pins is that the wiring survives a visual pass — a refactor that drops a `cta(…)`
 * call breaks no layout and no other test, and the loss would only ever show up as a category quietly
 * reading zero forever.
 *
 * Three representative controls are exercised: the primary action (the screen's whole point), the demo exit
 * (the route that leads AWAY from pairing, so it must stay separable from the ones that lead into it), and
 * a trust link (added by this change, and the one most likely to be dropped as decoration). Only the enum
 * event and its fixed categorical params are ever read — never a label, a URL, or anything typed.
 *
 * Links are observed through [webUrlTap], never opened: the trust link's click hands its URL to the
 * platform browser, and before the seam existed every run of this class popped the repository page in the
 * developer's real browser (and, on the desktop actual, still would if the `ccpocket.test` gate were lost).
 */
@OptIn(ExperimentalTestApi::class)
class OnboardingCtaTelemetryTest {

    private val seen = mutableListOf<Pair<TelEvent, Map<TelKey, Any>>>()
    private val opened = mutableListOf<String>()

    @BeforeTest fun tap() {
        seen.clear()
        opened.clear()
        telemetryTap = { e, p -> synchronized(seen) { seen += e to p } }
        webUrlTap = { url -> synchronized(opened) { opened += url } }
    }

    @AfterTest fun untap() {
        telemetryTap = null
        webUrlTap = null
    }

    /** Every `onboarding_cta` target recorded so far, in order. */
    private fun targets(): List<Any?> =
        synchronized(seen) { seen.filter { it.first == TelEvent.OnboardingCta }.map { it.second[TelKey.Target] } }

    /** The guide at the release baseline (iPhone 17, 402 × 874 pt), with every exit wired. */
    private fun guide(assertions: SkikoComposeUiTest.() -> Unit) = runDesktopComposeUiTest(402, 874) {
        setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, 1f)) {
                PocketTheme(dark = true) {
                    Box(Modifier.fillMaxSize()) {
                        OnboardingScreen(onPairNow = {}, onBack = {}, onEnterDemo = {})
                    }
                }
            }
        }
        waitForIdle()
        assertions()
    }

    /**
     * Tap the control labelled [text].
     *
     * [hasClickAction] is not decoration: `hasText` alone also matches the scrollable Column that MERGES the
     * label into its own semantics, and clicking that hits the container, not the control.
     *
     * Only for controls in the guide's scrolling body, which is taller than the 402 × 874 viewport:
     * `performScrollTo` on a node with no scrollable ancestor throws rather than no-opping.
     */
    private fun SkikoComposeUiTest.tapText(text: String) {
        val node = onAllNodes(hasText(text, substring = true) and hasClickAction()).onFirst()
        node.performScrollTo()
        node.performClick()
        waitForIdle()
    }

    @Test
    fun theThreeExitsEachNameThemselves() = guide {
        // the screen's own exposure event still fires, and carries no params (deliberately unchanged)
        assertTrue(
            synchronized(seen) { seen.any { it.first == TelEvent.OnboardingShown && it.second.isEmpty() } },
            "onboarding_shown must survive this change unparameterised, saw $seen",
        )

        tapText(str(Res.string.fr_cta))
        tapText(str(Res.string.fr_enter_code))
        tapText(str(Res.string.pair_demo))
        assertEquals(
            listOf<Any?>("pair_now", "enter_code", "demo"),
            targets(),
            "the primary, the shortcut for an already-installed user, and the no-computer route are three " +
                "different decisions and must never collapse into one",
        )
    }

    /**
     * The trust block's links are the proof behind its claims, so "did anyone check?" is the only signal
     * that says whether the block does its job. They report as targets of their own rather than as some
     * generic "link" — GitHub and the policy answer different doubts.
     */
    @Test
    fun aTrustLinkReportsItsOwnTarget() = guide {
        tapText(str(Res.string.fr_trust_github))
        assertEquals(listOf<Any?>("github"), targets())
        // the proof behind "open source" is the source itself — and it was observed, not opened
        assertEquals(listOf(GITHUB_REPO_URL), synchronized(opened) { opened.toList() })
        assertEquals("true", System.getProperty("ccpocket.test"), "the test runner's browser gate must be armed")
    }

    /**
     * The install method is the one CTA with a qualifier: a copy that came from the piped script and one
     * that came from a package manager are the same tap on the same control, and only [TelKey.Value]
     * distinguishes them. macOS is the default platform, so its package route is `brew`.
     */
    @Test
    fun theInstallMethodSwitchCarriesWhichRouteWasChosen() = guide {
        tapText(str(Res.string.fr_install_pkg))
        val ev = synchronized(seen) { seen.first { it.first == TelEvent.OnboardingCta } }
        assertEquals("install_method", ev.second[TelKey.Target])
        assertEquals("brew", ev.second[TelKey.Value], "macOS's package route is the Homebrew cask")
    }

    /** Closing is an outcome too — the guide's own dismissal must not be the one silent control left. */
    @Test
    fun closingTheGuideIsRecorded() = guide {
        // the shared back target: a chevron that names itself "Back", outside the scroller
        onAllNodes(hasContentDescription(str(Res.string.action_back)) and hasClickAction()).onFirst().performClick()
        waitForIdle()
        assertEquals(listOf<Any?>("close"), targets())
    }
}
