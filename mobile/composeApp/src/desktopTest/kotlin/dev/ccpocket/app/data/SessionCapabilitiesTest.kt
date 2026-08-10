package dev.ccpocket.app.data

import dev.ccpocket.protocol.AgentKind
import dev.ccpocket.protocol.CLAUDE_PERMISSION_MODE_AUTO
import dev.ccpocket.protocol.Frame
import dev.ccpocket.protocol.ModelCapabilities
import dev.ccpocket.protocol.ModelServiceTier
import dev.ccpocket.protocol.ModelsList
import dev.ccpocket.protocol.OpenSession
import dev.ccpocket.protocol.PermissionMode
import dev.ccpocket.protocol.SendPrompt
import dev.ccpocket.protocol.SessionLive
import dev.ccpocket.protocol.SwitchServiceTier
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SessionCapabilitiesTest {
    private fun repo(scope: CoroutineScope, sent: MutableList<Frame>) =
        PocketRepository(scope).apply { onSendForTest = { sent += it } }

    @Test
    fun known_model_capabilities_drop_stale_defaults_before_new_session() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val sent = mutableListOf<Frame>()
        val repo = repo(scope, sent)
        try {
            repo.setDefaultModelFor(AgentKind.CODEX, "gpt-5.5")
            repo.setDefaultEffort("ultra")
            repo.setDefaultServiceTier("priority")
            repo.receiveForTest(
                ModelsList(
                    agent = AgentKind.CODEX,
                    models = listOf("gpt-5.5"),
                    modelCapabilities = listOf(
                        ModelCapabilities(
                            model = "gpt-5.5",
                            reasoningEfforts = listOf("low", "medium", "high", "xhigh"),
                        ),
                    ),
                ),
            )

            assertNull(repo.defaultEffort.value)
            assertNull(repo.defaultServiceTier.value)
            repo.openSession("/tmp/project", agent = AgentKind.CODEX)

            val open = sent.filterIsInstance<OpenSession>().single()
            assertEquals("gpt-5.5", open.model)
            assertNull(open.effort)
            assertNull(open.serviceTier)
        } finally {
            repo.setDefaultModelFor(AgentKind.CODEX, null)
            repo.setDefaultEffort(null)
            repo.setDefaultServiceTier(null)
            scope.cancel()
        }
    }

    @Test
    fun old_daemon_model_list_keeps_the_legacy_effort_picker() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val repo = repo(scope, mutableListOf())
        try {
            repo.receiveForTest(ModelsList(agent = AgentKind.CODEX, models = listOf("gpt-5.5")))

            assertEquals(
                listOf("low", "medium", "high", "xhigh", "max"),
                repo.effortOptions(AgentKind.CODEX, "gpt-5.5"),
            )
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun model_switch_clears_effort_and_fast_when_target_does_not_support_them() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val sent = mutableListOf<Frame>()
        val repo = repo(scope, sent)
        try {
            repo.receiveForTest(
                ModelsList(
                    agent = AgentKind.CODEX,
                    models = listOf("gpt-5.6-sol", "gpt-5.5"),
                    modelCapabilities = listOf(
                        ModelCapabilities(
                            model = "gpt-5.6-sol",
                            reasoningEfforts = listOf("ultra"),
                            serviceTiers = listOf(ModelServiceTier("priority")),
                        ),
                        ModelCapabilities(model = "gpt-5.5", reasoningEfforts = listOf("xhigh")),
                    ),
                ),
            )
            repo.receiveForTest(
                SessionLive(
                    "c1",
                    "/tmp/project",
                    "sid",
                    mode = PermissionMode.DEFAULT,
                    model = "gpt-5.6-sol",
                    effort = "ultra",
                    agent = AgentKind.CODEX,
                    serviceTier = "priority",
                ),
            )
            sent.clear()

            repo.switchModel("gpt-5.5")

            assertTrue(sent.filterIsInstance<SendPrompt>().any { it.text == "/model gpt-5.5" })
            assertTrue(sent.filterIsInstance<SendPrompt>().any { it.text == "/effort default" })
            assertEquals(listOf(SwitchServiceTier("c1", null)), sent.filterIsInstance<SwitchServiceTier>())
            assertNull(repo.effort.value)
            assertNull(repo.serviceTier.value)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun session_live_nulls_clear_previous_native_settings() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val repo = repo(scope, mutableListOf())
        try {
            repo.receiveForTest(
                SessionLive(
                    "c1",
                    "/tmp/project",
                    "sid",
                    mode = PermissionMode.DEFAULT,
                    effort = "max",
                    agent = AgentKind.CLAUDE,
                    permissionMode = CLAUDE_PERMISSION_MODE_AUTO,
                    serviceTier = "priority",
                ),
            )
            repo.receiveForTest(
                SessionLive(
                    "c1",
                    "/tmp/project",
                    "sid",
                    mode = PermissionMode.DEFAULT,
                    agent = AgentKind.CLAUDE,
                ),
            )

            assertNull(repo.permissionMode.value)
            assertNull(repo.effort.value)
            assertNull(repo.serviceTier.value)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun session_live_authoritative_title_repairs_a_titleless_open() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val repo = repo(scope, mutableListOf())
        try {
            // Push/deep-link opens know only workdir + session id, so the header starts on its generic fallback.
            repo.openSession("/tmp/project", "codex-session", title = null, agent = AgentKind.CODEX)
            assertNull(repo.chatTitle.value)

            repo.receiveForTest(
                SessionLive(
                    "c1",
                    "/tmp/project",
                    "codex-session",
                    agent = AgentKind.CODEX,
                    title = "codex 会话 cc pocket",
                ),
            )

            assertEquals("codex 会话 cc pocket", repo.chatTitle.value)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun explicit_codex_row_overrides_stale_claude_session_cache() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val sent = mutableListOf<Frame>()
        val repo = repo(scope, sent)
        try {
            // Simulate the bad value persisted by an older build after mis-opening this Codex id.
            repo.receiveForTest(
                SessionLive(
                    "old-convo",
                    "/tmp/project",
                    "codex-session-agent-precedence",
                    agent = AgentKind.CLAUDE,
                ),
            )
            sent.clear()

            repo.openSession(
                "/tmp/project",
                "codex-session-agent-precedence",
                agent = AgentKind.CODEX,
            )

            assertEquals(AgentKind.CODEX, sent.filterIsInstance<OpenSession>().single().agent)
        } finally {
            scope.cancel()
        }
    }
}
