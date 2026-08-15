package dev.ccpocket.app.ui

import dev.ccpocket.app.desktop.desktopModeChoices
import dev.ccpocket.app.desktop.desktopDefaultModeIndex
import dev.ccpocket.app.desktop.DESKTOP_AGENT_CHOICES
import dev.ccpocket.protocol.AgentKind
import dev.ccpocket.protocol.DirectoryEntry
import dev.ccpocket.protocol.PermissionMode
import dev.ccpocket.protocol.ModelsList
import dev.ccpocket.protocol.SessionSummary
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class ZCodeUiLogicTest {

    @Test
    fun identityIsDedicatedAndNamesTheVendor() {
        assertEquals("ZCode", agentName(AgentKind.ZCODE))
        assertEquals("ZC", agentAbbrev(AgentKind.ZCODE))
        assertEquals("ZCode · Z.ai", agentTagline(AgentKind.ZCODE))
        assertNotEquals(agentColor(AgentKind.KIMI), agentColor(AgentKind.ZCODE))
    }

    @Test
    fun desktopNewSessionAndDefaultAgentChoicesExposeZcode() {
        assertEquals(
            listOf(AgentKind.CLAUDE, AgentKind.CODEX, AgentKind.OPENCODE, AgentKind.ZCODE),
            DESKTOP_AGENT_CHOICES,
        )
    }

    @Test
    fun modelRowsUseDaemonTruthWithoutClaudeAliasFormatting() {
        assertEquals("zai/glm-5", modelLabelForAgent(AgentKind.ZCODE, "zai/glm-5"))
        assertEquals(
            listOf("zai/glm-5", "bigmodel/glm-5"),
            modelChoicesFor(
                AgentKind.ZCODE,
                listOf("zai/glm-5", "deepseek-v4-pro", "sonnet", "bigmodel/glm-5"),
                gatewayUrl = null,
            ).map { it.id },
        )
        assertEquals(emptyList(), modelChoicesFor(AgentKind.ZCODE, null, gatewayUrl = null))
    }

    @Test
    fun zcodeNeverInheritsClaudeGatewayState() {
        val claudeGateway = "https://open.bigmodel.cn/api/anthropic"

        assertEquals(claudeGateway, modelPickerGatewayUrl(AgentKind.CLAUDE, claudeGateway))
        assertEquals(null, modelPickerGatewayUrl(AgentKind.ZCODE, claudeGateway))
        assertEquals(null, modelPickerGatewayUrl(AgentKind.KIMI, claudeGateway))

        // Gateway presence must not rewrite or add anything to ZCode's provider/model catalog.
        assertEquals(
            listOf("zai/glm-5"),
            modelChoicesFor(AgentKind.ZCODE, listOf("zai/glm-5"), gatewayUrl = claudeGateway).map { it.pick },
        )
    }

    @Test
    fun zcodeCatalogDistinguishesLoadingEmptyErrorAndReady() {
        assertEquals(ModelCatalogNotice.LOADING, modelCatalogNotice(AgentKind.ZCODE, null, hasSelectableModels = false))
        assertEquals(
            ModelCatalogNotice.EMPTY,
            modelCatalogNotice(
                AgentKind.ZCODE,
                ModelsList(agent = AgentKind.ZCODE),
                hasSelectableModels = false,
            ),
        )
        assertEquals(
            ModelCatalogNotice.ERROR,
            modelCatalogNotice(
                AgentKind.ZCODE,
                ModelsList(agent = AgentKind.ZCODE, error = "config unreadable"),
                hasSelectableModels = false,
            ),
        )
        assertEquals(
            null,
            modelCatalogNotice(
                AgentKind.ZCODE,
                ModelsList(agent = AgentKind.ZCODE, models = listOf("zai/glm-5")),
                hasSelectableModels = true,
            ),
        )
    }

    @Test
    fun desktopPermissionSurfaceMapsTheFourSharedModes() {
        assertEquals(
            listOf(
                PermissionMode.DEFAULT,
                PermissionMode.ACCEPT_EDITS,
                PermissionMode.PLAN,
                PermissionMode.BYPASS_PERMISSIONS,
            ),
            desktopModeChoices(AgentKind.ZCODE).map { it.mode },
        )
    }

    @Test
    fun desktopNewSessionPreservesPersistedZcodeMode() {
        assertEquals(
            PermissionMode.PLAN,
            desktopModeChoices(AgentKind.ZCODE)[
                desktopDefaultModeIndex(AgentKind.ZCODE, PermissionMode.PLAN, null)
            ].mode,
        )
        assertEquals(
            PermissionMode.BYPASS_PERMISSIONS,
            desktopModeChoices(AgentKind.ZCODE)[
                desktopDefaultModeIndex(AgentKind.ZCODE, PermissionMode.BYPASS_PERMISSIONS, null)
            ].mode,
        )
    }

    @Test
    fun zcodeFiltersSessionAndProjectRows() {
        val zcodeSession = SessionSummary("z", "Z", "", 0, "/z", 1, agent = AgentKind.ZCODE)
        val kimiSession = SessionSummary("k", "K", "", 0, "/k", 1, agent = AgentKind.KIMI)
        assertEquals(listOf(zcodeSession), filterSessionsByAgent(listOf(kimiSession, zcodeSession), setOf(AgentKind.ZCODE)))

        val zcodeDir = DirectoryEntry(
            path = "/z", name = "z", isDir = true, hasSessions = true,
            sessionAgents = listOf(AgentKind.ZCODE),
        )
        val kimiDir = DirectoryEntry(
            path = "/k", name = "k", isDir = true, hasSessions = true,
            sessionAgents = listOf(AgentKind.KIMI),
        )
        assertEquals(listOf(zcodeDir), filterDirectoriesByAgent(listOf(kimiDir, zcodeDir), setOf(AgentKind.ZCODE)))
    }
}
