package dev.ccpocket.app.telemetry

import dev.ccpocket.app.data.DemoData
import dev.ccpocket.app.data.PocketRepository
import dev.ccpocket.app.desktop.TerminalPanelController
import dev.ccpocket.observability.AnalyticsCatalog
import dev.ccpocket.protocol.AgentKind
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Second-batch feature_used (2026-10-04): usage counts for keep-or-remove candidates. The privacy contract is
 * the point — every value is a fixed enum and nothing a user named or typed rides along — so this pins the
 * vocabulary, the exact parameter keys a trigger may send, and a sample of the real call sites.
 */
class FeatureUsageTelemetryTest {
    private val seen = mutableListOf<Pair<TelEvent, Map<TelKey, Any>>>()

    @BeforeTest fun tap() {
        seen.clear()
        telemetryTap = { e, p -> synchronized(seen) { seen += e to p } }
    }

    @AfterTest fun untap() { telemetryTap = null }

    private fun repo() = PocketRepository(CoroutineScope(Dispatchers.Unconfined))
    private fun used(): List<Map<TelKey, Any>> = synchronized(seen) { seen.filter { it.first == TelEvent.FeatureUsed }.map { it.second } }

    /** The repository's own dimensions plus the feature name — never a path, id, label or text. */
    private val allowedKeys = setOf(TelKey.Feature, TelKey.UsageMode, TelKey.Backend, TelKey.Demo)

    @Test fun featureVocabularyIsShortSnakeCaseAndIngressSafe() {
        val ids = ProductFeature.entries.map { it.name.lowercase() }
        assertEquals(ids.size, ids.toSet().size)
        for (id in ids) {
            assertTrue(Regex("[a-z]+(_[a-z]+)*").matches(id), "not snake_case: $id")
            assertTrue(AnalyticsCatalog.stringValue.matches(id) && id.length <= AnalyticsCatalog.MAX_STRING_LENGTH, id)
        }
        // GA4 reports already key on these; renaming one would silently split its history
        assertEquals(listOf("session_view", "prompt_task", "approval", "file_view", "background_task"), ids.take(5))
    }

    @Test fun repositoryTriggersSendOnlyTheFeatureAndEnumDimensions() {
        val repo = repo()
        repo.openWorkflow("run-secret-id")
        val events = used()
        assertEquals(listOf("workflow_run"), events.map { it[TelKey.Feature] })
        for (p in events) {
            assertTrue(p.keys.all { it in allowedKeys }, "unexpected keys ${p.keys}")
            assertTrue(p.values.none { it.toString().contains("secret") || it.toString().contains("/") || it.toString().contains("Alice") })
        }
    }

    @Test fun everyUseFeatureCallMapsToItsLowercaseName() {
        val repo = repo()
        for (f in ProductFeature.entries) repo.useFeature(f)
        assertEquals(ProductFeature.entries.map { it.name.lowercase() }, used().map { it[TelKey.Feature] })
    }

    /** Opening the embedded shell counts once; re-focusing the dock that is already open in that folder does not. */
    @Test fun embeddedTerminalCountsOpensNotRefocusesAndNeverTheFolder() {
        val panel = TerminalPanelController()
        panel.openEmbedded("/tmp/project-a", "main")
        panel.openEmbedded("/tmp/project-a", "main")
        panel.collapse(); panel.openEmbedded("/tmp/project-a", "main")
        val events = used()
        assertEquals(2, events.size)
        assertTrue(events.all { it[TelKey.Feature] == "embedded_terminal" && it.keys == setOf(TelKey.Feature, TelKey.UsageMode) })
    }

    /** session_opened with resume=0 answers "which backend did a NEW session pick" — an AgentKind value only. */
    @Test fun newSessionOpenCarriesTheChosenBackend() {
        val repo = repo()
        repo.enterDemo()
        repo.finishDemoConnect()
        repo.openSession(DemoData.LIVE_DIR)
        val opened = synchronized(seen) { seen.first { it.first == TelEvent.SessionOpened } }.second
        assertEquals(0, opened[TelKey.Resume])
        assertTrue(opened[TelKey.Backend] in AgentKind.entries.map { it.name.lowercase() }, "backend=${opened[TelKey.Backend]}")
    }
}
