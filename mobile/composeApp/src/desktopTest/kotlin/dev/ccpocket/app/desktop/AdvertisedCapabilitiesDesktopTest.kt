package dev.ccpocket.app.desktop

import dev.ccpocket.app.data.PocketRepository
import dev.ccpocket.app.data.currentDaemonModels
import dev.ccpocket.app.data.oldDaemonModels
import dev.ccpocket.protocol.AgentKind
import dev.ccpocket.protocol.CLAUDE_PERMISSION_MODE_AUTO
import dev.ccpocket.protocol.ModelsList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Desktop half of [dev.ccpocket.app.data.AdvertisedCapabilitiesTest]: the Popovers / SettingsModal gates
 * read the per-backend advertisement through [DesktopModel], and for all six backends — against today's
 * daemon and an older one — must offer exactly what the old name checks offered.
 */
class AdvertisedCapabilitiesDesktopTest {
    private fun daemons(): List<Pair<String, (AgentKind) -> ModelsList>> =
        listOf("current daemon" to ::currentDaemonModels, "old daemon" to ::oldDaemonModels)

    private fun withModel(lists: (AgentKind) -> ModelsList, body: (RepoDesktopModel, PocketRepository) -> Unit) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val repo = PocketRepository(scope).apply { onSendForTest = {} }
        try {
            AgentKind.entries.forEach { repo.receiveForTest(lists(it)) }
            body(RepoDesktopModel(repo, scope, store = FakeDesktopStore()), repo)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun mode_ladders_match_the_name_checked_ladders_for_every_backend() {
        for ((label, lists) in daemons()) {
            withModel(lists) { model, _ ->
                for (agent in AgentKind.entries) {
                    // pre-move Popovers.kt / SettingsModal.kt:
                    // `agent == CLAUDE && model.permissionModeAvailable(AUTO)` (which read Claude's list)
                    val legacy = desktopModeChoices(
                        agent,
                        agent == AgentKind.CLAUDE && model.permissionModeAvailable(CLAUDE_PERMISSION_MODE_AUTO),
                    )
                    val now = desktopModeChoices(agent, model.permissionModeAvailable(CLAUDE_PERMISSION_MODE_AUTO, agent))
                    assertEquals(legacy, now, "$agent / $label")
                    assertEquals(
                        agent == AgentKind.CLAUDE && label == "current daemon",
                        now.any { it.nativeMode == CLAUDE_PERMISSION_MODE_AUTO },
                        "$agent / $label: only Claude on an advertising daemon offers Auto",
                    )
                }
            }
        }
    }

    @Test
    fun default_fast_switch_shows_exactly_where_the_codex_name_check_showed_it() {
        val savedModels = mutableMapOf<AgentKind, String?>()
        for ((label, lists) in daemons()) {
            withModel(lists) { model, repo ->
                AgentKind.entries.forEach { savedModels[it] = repo.defaultModelFor(it) }
                try {
                    for (agent in AgentKind.entries) {
                        // the default model is the one the daemon listed, so a per-model row can match
                        repo.setDefaultModelFor(agent, currentDaemonModels(agent).models.first())
                        val tiers = model.serviceTierOptionsFor(agent, repo.defaultModelFor(agent))
                        // pre-move Settings.kt / SettingsModal.kt: `agent == CODEX && tiers.any { priority }`
                        val legacy = agent == AgentKind.CODEX && tiers.any { it.id == "priority" }
                        assertEquals(legacy, dev.ccpocket.app.ui.fastModeAvailable(repo, agent), "mobile $agent / $label")
                        assertEquals(legacy, dev.ccpocket.app.data.advertisesFastTier(tiers), "desktop $agent / $label")
                        assertEquals(
                            agent == AgentKind.CODEX && label == "current daemon",
                            legacy,
                            "$agent / $label: only Codex on an advertising daemon shows Fast",
                        )
                    }
                } finally {
                    savedModels.forEach { (a, m) -> repo.setDefaultModelFor(a, m) }
                }
            }
        }
    }
}
