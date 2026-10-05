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

    private fun withModel(lists: (AgentKind) -> ModelsList, body: (RepoDesktopModel) -> Unit) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val repo = PocketRepository(scope).apply { onSendForTest = {} }
        try {
            AgentKind.entries.forEach { repo.receiveForTest(lists(it)) }
            body(RepoDesktopModel(repo, scope, store = FakeDesktopStore()))
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun mode_ladders_match_the_name_checked_ladders_for_every_backend() {
        for ((label, lists) in daemons()) {
            withModel(lists) { model ->
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
}
