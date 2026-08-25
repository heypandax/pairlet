package dev.ccpocket.app.data

import dev.ccpocket.protocol.AgentKind
import dev.ccpocket.protocol.CLAUDE_PERMISSION_MODE_AUTO
import dev.ccpocket.protocol.DaemonInfo
import dev.ccpocket.protocol.FetchModels
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
    fun known_model_capabilities_keep_the_effort_preference_and_let_the_launch_clamp_it() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val sent = mutableListOf<Frame>()
        val repo = repo(scope, sent)
        try {
            repo.setDefaultModelFor(AgentKind.CODEX, "gpt-5.5")
            repo.setDefaultEffortFor(AgentKind.CODEX, "ultra")
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

            // #274: a leaner catalog must NOT erase the persisted effort preference (SecureStore is global);
            // it survives and is clamped at the launch boundary instead. Service tier still reconciles.
            assertEquals("ultra", repo.defaultEffortFor(AgentKind.CODEX), "the effort preference survives")
            assertNull(repo.defaultServiceTier.value)
            repo.openSession("/tmp/project", agent = AgentKind.CODEX)

            val open = sent.filterIsInstance<OpenSession>().single()
            assertEquals("gpt-5.5", open.model)
            assertNull(open.effort, "the unsupported effort is dropped at launch, not persisted away")
            assertNull(open.serviceTier)
        } finally {
            repo.setDefaultModelFor(AgentKind.CODEX, null)
            repo.setDefaultEffortFor(AgentKind.CODEX, null)
            repo.setDefaultServiceTier(null)
            scope.cancel()
        }
    }

    @Test
    fun cliDefaultModelDoesNotBorrowTheFirstAdvertisedModelsCapabilities() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val sent = mutableListOf<Frame>()
        val repo = repo(scope, sent)
        try {
            repo.setDefaultModelFor(AgentKind.CODEX, null)
            repo.setDefaultEffortFor(AgentKind.CODEX, "ultra")
            repo.setDefaultServiceTier("priority")
            repo.receiveForTest(
                ModelsList(
                    agent = AgentKind.CODEX,
                    models = listOf("gpt-first", "gpt-second"),
                    modelCapabilities = listOf(
                        ModelCapabilities(
                            model = "gpt-first",
                            reasoningEfforts = listOf("low"),
                            serviceTiers = emptyList(),
                        ),
                    ),
                ),
            )

            assertNull(repo.modelCapabilities(AgentKind.CODEX, null), "CLI Default has no concrete catalog row")
            assertTrue(repo.serviceTierOptions(AgentKind.CODEX, null).isEmpty())
            assertEquals(
                listOf("low", "medium", "high", "xhigh", "max"),
                repo.effortOptions(AgentKind.CODEX, null),
                "an unknown CLI Default keeps the legacy pass-through picker",
            )
            assertEquals("ultra", repo.defaultEffortFor(AgentKind.CODEX), "unknown capability must pass through")
            assertEquals("priority", repo.defaultServiceTier.value, "unknown capability must pass through")

            repo.openSession("/tmp/codex-cli-default", agent = AgentKind.CODEX)
            val open = sent.filterIsInstance<OpenSession>().single()
            assertNull(open.model)
            assertEquals("ultra", open.effort)
            assertEquals("priority", open.serviceTier)
        } finally {
            repo.setDefaultModelFor(AgentKind.CODEX, null)
            repo.setDefaultEffortFor(AgentKind.CODEX, null)
            repo.setDefaultServiceTier(null)
            scope.cancel()
        }
    }

    @Test
    fun codex_default_effort_isolated_from_claude_and_used_for_new_session() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val sent = mutableListOf<Frame>()
        val repo = repo(scope, sent)
        try {
            repo.setDefaultEffort("high")
            repo.setDefaultEffortFor(AgentKind.CODEX, "ultra")

            assertEquals("high", repo.defaultEffortFor(AgentKind.CLAUDE))
            assertEquals("ultra", repo.defaultEffortFor(AgentKind.CODEX))

            repo.openSession("/tmp/codex-default", agent = AgentKind.CODEX)

            assertEquals("ultra", sent.filterIsInstance<OpenSession>().single().effort)
            assertEquals("high", repo.defaultEffort.value, "Codex settings must not rewrite Claude's legacy preference")
        } finally {
            repo.setDefaultEffort(null)
            repo.setDefaultEffortFor(AgentKind.CODEX, null)
            scope.cancel()
        }
    }

    @Test
    fun everyBackendUsesOnlyItsOwnEffortForANewSession() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val sent = mutableListOf<Frame>()
        val repo = repo(scope, sent)
        val expected = linkedMapOf(
            AgentKind.CLAUDE to "high",
            AgentKind.CODEX to "ultra",
            AgentKind.OPENCODE to "medium",
            AgentKind.KIMI to "low",
        )
        try {
            expected.forEach(repo::setDefaultEffortFor)
            expected.forEach { (agent, effort) ->
                sent.clear()
                repo.openSession("/tmp/${agent.name.lowercase()}-default", agent = agent)
                assertEquals(effort, sent.filterIsInstance<OpenSession>().single().effort, "$agent owns its default")
                repo.backToBrowse()
            }
        } finally {
            expected.keys.forEach { repo.setDefaultEffortFor(it, null) }
            scope.cancel()
        }
    }

    @Test
    fun legacyNonCodexResumeAndTakeoverUseTheirOwnScopedFallback() {
        for (agent in listOf(AgentKind.OPENCODE, AgentKind.KIMI)) {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
            val sent = mutableListOf<Frame>()
            val resumeRepo = repo(scope, sent)
            try {
                resumeRepo.setDefaultEffortFor(AgentKind.CLAUDE, "high")
                resumeRepo.setDefaultEffortFor(agent, if (agent == AgentKind.OPENCODE) "medium" else "low")
                val expected = resumeRepo.defaultEffortFor(agent)
                resumeRepo.receiveForTest(
                    SessionLive(
                        convoId = "${agent.name}-resume",
                        workdir = "/tmp/${agent.name.lowercase()}-resume",
                        sessionId = "${agent.name}-sid",
                        observing = false,
                        executing = false,
                        agent = agent,
                    ),
                )
                resumeRepo.backToBrowse()
                sent.clear()
                resumeRepo.openSession(
                    "/tmp/${agent.name.lowercase()}-resume",
                    resumeId = "${agent.name}-sid",
                    agent = agent,
                )
                assertEquals(expected, sent.filterIsInstance<OpenSession>().single().effort, "$agent resume fallback")

                // Use a fresh repository for the observed-session half. The resume above deliberately
                // leaves an open claim in flight until SessionLive; feeding an unrelated observer into
                // that claim is correctly rejected and would make this a false-positive takeover test.
                val takeoverRepo = repo(scope, sent)
                takeoverRepo.receiveForTest(
                    SessionLive(
                        convoId = "${agent.name}-observe",
                        workdir = "/tmp/${agent.name.lowercase()}-resume",
                        sessionId = "${agent.name}-takeover",
                        observing = true,
                        executing = false,
                        agent = agent,
                    ),
                )
                sent.clear()
                takeoverRepo.takeOver()
                val takeover = sent.filterIsInstance<OpenSession>().single()
                assertEquals("${agent.name}-takeover", takeover.resumeId, "$agent takes over the observed identity")
                assertEquals(agent, takeover.agent, "$agent takeover must resume through its own backend")
                assertEquals(expected, takeover.effort, "$agent takeover fallback")
            } finally {
                resumeRepo.setDefaultEffortFor(AgentKind.CLAUDE, null)
                resumeRepo.setDefaultEffortFor(agent, null)
                scope.cancel()
            }
        }
    }

    @Test
    fun reconcileLeavesEveryBackendsPersistedEffortIntact() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val repo = repo(scope, mutableListOf())
        try {
            repo.setDefaultEffortFor(AgentKind.CLAUDE, "high")
            repo.setDefaultEffortFor(AgentKind.CODEX, "ultra")
            repo.setDefaultEffortFor(AgentKind.OPENCODE, "unsupported")
            repo.setDefaultEffortFor(AgentKind.KIMI, "low")
            repo.setDefaultModelFor(AgentKind.OPENCODE, "provider/model")

            repo.receiveForTest(
                ModelsList(
                    agent = AgentKind.OPENCODE,
                    models = listOf("provider/model"),
                    modelCapabilities = listOf(
                        ModelCapabilities(model = "provider/model", reasoningEfforts = listOf("medium")),
                    ),
                ),
            )

            // #274: even OpenCode's now-unsupported preference is kept (not erased) — the launch boundary
            // drops it where it doesn't fit; every other backend is untouched, as before.
            assertEquals("unsupported", repo.defaultEffortFor(AgentKind.OPENCODE), "the preference survives a leaner catalog")
            assertEquals("high", repo.defaultEffortFor(AgentKind.CLAUDE))
            assertEquals("ultra", repo.defaultEffortFor(AgentKind.CODEX))
            assertEquals("low", repo.defaultEffortFor(AgentKind.KIMI))
        } finally {
            AgentKind.entries.forEach { repo.setDefaultEffortFor(it, null) }
            repo.setDefaultModelFor(AgentKind.OPENCODE, null)
            scope.cancel()
        }
    }

    @Test
    fun claudeBackendWideEffortsKeepThePreferenceButGuardTheResumeLaunch() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val sent = mutableListOf<Frame>()
        val repo = repo(scope, sent)
        try {
            repo.setDefaultEffortFor(AgentKind.CLAUDE, "ultra")
            repo.setDefaultEffortFor(AgentKind.CODEX, "xhigh")
            repo.receiveForTest(
                SessionLive(
                    convoId = "claude-wide-convo",
                    workdir = "/tmp/claude-wide",
                    sessionId = "claude-wide-sid",
                    model = "sonnet",
                    effort = "ultra",
                    agent = AgentKind.CLAUDE,
                ),
            )
            repo.backToBrowse()
            repo.receiveForTest(
                ModelsList(
                    agent = AgentKind.CLAUDE,
                    models = listOf("sonnet"),
                    supportedEfforts = listOf("low", "medium", "high"),
                ),
            )

            // #274: the persisted CLAUDE preference is kept even when this machine's CLI advertises a leaner
            // set; only the launch boundary rejects the stale value.
            assertEquals("ultra", repo.defaultEffortFor(AgentKind.CLAUDE), "the preference survives a leaner CLI")
            assertEquals("xhigh", repo.defaultEffortFor(AgentKind.CODEX), "Claude capability refresh stays isolated")
            sent.clear()
            repo.openSession(
                "/tmp/claude-wide",
                resumeId = "claude-wide-sid",
                agent = AgentKind.CLAUDE,
            )
            assertNull(
                sent.filterIsInstance<OpenSession>().single().effort,
                "a stale per-session value is still rejected at the launch boundary",
            )
        } finally {
            repo.setDefaultEffortFor(AgentKind.CLAUDE, null)
            repo.setDefaultEffortFor(AgentKind.CODEX, null)
            scope.cancel()
        }
    }

    @Test
    fun modelSpecificEffortsTakePriorityOverBackendWideFallback() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val sent = mutableListOf<Frame>()
        val repo = repo(scope, sent)
        try {
            repo.setDefaultModelFor(AgentKind.CLAUDE, "sonnet")
            repo.setDefaultEffortFor(AgentKind.CLAUDE, "high")
            repo.receiveForTest(
                ModelsList(
                    agent = AgentKind.CLAUDE,
                    models = listOf("sonnet"),
                    supportedEfforts = listOf("low", "medium", "high"),
                    modelCapabilities = listOf(ModelCapabilities(model = "sonnet", reasoningEfforts = emptyList())),
                ),
            )

            assertTrue(
                repo.effortOptions(AgentKind.CLAUDE, "sonnet").isEmpty(),
                "a present per-model row is authoritative even when it advertises no effort choices",
            )
            // #274: the preference is kept (not erased by the empty per-model row); the launch drops it.
            assertEquals("high", repo.defaultEffortFor(AgentKind.CLAUDE), "the preference survives the empty per-model row")
            sent.clear()
            repo.openSession("/tmp/claude-no-effort", agent = AgentKind.CLAUDE)
            assertNull(sent.filterIsInstance<OpenSession>().single().effort)
        } finally {
            repo.setDefaultModelFor(AgentKind.CLAUDE, null)
            repo.setDefaultEffortFor(AgentKind.CLAUDE, null)
            scope.cancel()
        }
    }

    @Test
    fun fleetPromotionAdoptsEveryAgentsInMemoryEffort() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val source = PocketRepository(scope)
        val target = PocketRepository(scope)
        try {
            val expected = mapOf(
                AgentKind.CLAUDE to "high",
                AgentKind.CODEX to "ultra",
                AgentKind.OPENCODE to "medium",
                AgentKind.KIMI to "low",
            )
            expected.forEach(source::setDefaultEffortFor)

            target.adoptShellState(source)

            expected.forEach { (agent, effort) ->
                assertEquals(effort, target.defaultEffortFor(agent), "$agent travels with the promoted shell")
            }
        } finally {
            AgentKind.entries.forEach { source.setDefaultEffortFor(it, null) }
            scope.cancel()
        }
    }

    @Test
    fun fleetPromotionReconcilesCopiedDefaultsAgainstTheTargetDaemonCatalog() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val source = PocketRepository(scope)
        val target = PocketRepository(scope)
        try {
            source.setDefaultModelFor(AgentKind.CODEX, "gpt-5.6-sol")
            source.setDefaultEffortFor(AgentKind.CODEX, "ultra")
            source.setDefaultServiceTier("priority")
            target.receiveForTest(
                ModelsList(
                    agent = AgentKind.CODEX,
                    models = listOf("gpt-5.6-sol"),
                    modelCapabilities = listOf(
                        ModelCapabilities(model = "gpt-5.6-sol", reasoningEfforts = listOf("high")),
                    ),
                ),
            )

            target.adoptShellState(source)

            assertEquals("gpt-5.6-sol", target.defaultModelFor(AgentKind.CODEX))
            // #274: the promoted effort preference is kept even against a leaner target catalog (the launch
            // clamps it); only the service tier still reconciles here.
            assertEquals("ultra", target.defaultEffortFor(AgentKind.CODEX), "the copied effort preference survives")
            assertNull(target.defaultServiceTier.value, "the hot target rejects the copied tier immediately")
        } finally {
            source.setDefaultModelFor(AgentKind.CODEX, null)
            source.setDefaultEffortFor(AgentKind.CODEX, null)
            source.setDefaultServiceTier(null)
            scope.cancel()
        }
    }

    @Test
    fun resumed_codex_session_with_saved_nulls_does_not_inherit_new_defaults() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val sent = mutableListOf<Frame>()
        val repo = repo(scope, sent)
        try {
            // SessionLive persists an entry whose nullable model/effort mean this existing session follows
            // the CLI. Presence of that entry must not be collapsed into "no saved value" by Elvis.
            repo.receiveForTest(
                SessionLive(
                    convoId = "saved-null-convo",
                    workdir = "/tmp/saved-null",
                    sessionId = "saved-null-sid",
                    observing = false,
                    executing = false,
                    agent = AgentKind.CODEX,
                ),
            )
            repo.backToBrowse()
            repo.setDefaultModelFor(AgentKind.CODEX, "gpt-5.6-sol")
            repo.setDefaultEffortFor(AgentKind.CODEX, "ultra")
            sent.clear()

            repo.openSession("/tmp/saved-null", resumeId = "saved-null-sid", agent = AgentKind.CODEX)

            val open = sent.filterIsInstance<OpenSession>().single()
            assertNull(open.model)
            assertNull(open.effort)
        } finally {
            repo.setDefaultModelFor(AgentKind.CODEX, null)
            repo.setDefaultEffortFor(AgentKind.CODEX, null)
            scope.cancel()
        }
    }

    @Test
    fun resumed_codex_session_without_a_local_saved_entry_does_not_inherit_new_defaults() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val sent = mutableListOf<Frame>()
        val repo = repo(scope, sent)
        try {
            repo.setDefaultModelFor(AgentKind.CODEX, "gpt-5.6-sol")
            repo.setDefaultEffortFor(AgentKind.CODEX, "ultra")
            sent.clear()

            repo.openSession("/tmp/remote-history", resumeId = "not-on-this-device", agent = AgentKind.CODEX)

            val open = sent.filterIsInstance<OpenSession>().single()
            assertNull(open.model)
            assertNull(open.effort)
        } finally {
            repo.setDefaultModelFor(AgentKind.CODEX, null)
            repo.setDefaultEffortFor(AgentKind.CODEX, null)
            scope.cancel()
        }
    }

    @Test
    fun resumed_claude_session_with_saved_nulls_keeps_the_pre_codex_defaults_fallback() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val sent = mutableListOf<Frame>()
        val repo = repo(scope, sent)
        try {
            repo.receiveForTest(
                SessionLive(
                    convoId = "claude-saved-null-convo",
                    workdir = "/tmp/claude-saved-null",
                    sessionId = "claude-saved-null-sid",
                    observing = false,
                    executing = false,
                    agent = AgentKind.CLAUDE,
                ),
            )
            repo.backToBrowse()
            repo.setDefaultModelFor(AgentKind.CLAUDE, "sonnet")
            repo.setDefaultEffort("high")
            sent.clear()

            repo.openSession(
                "/tmp/claude-saved-null",
                resumeId = "claude-saved-null-sid",
                agent = AgentKind.CLAUDE,
            )

            val open = sent.filterIsInstance<OpenSession>().single()
            assertEquals("sonnet", open.model, "#237 must not change Claude's established resume fallback")
            assertEquals("high", open.effort, "#237 must not change Claude's established resume fallback")
        } finally {
            repo.setDefaultModelFor(AgentKind.CLAUDE, null)
            repo.setDefaultEffort(null)
            scope.cancel()
        }
    }

    @Test
    fun claude_takeover_keeps_the_pre_codex_default_effort_fallback() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val sent = mutableListOf<Frame>()
        val repo = repo(scope, sent)
        try {
            repo.receiveForTest(
                SessionLive(
                    convoId = "claude-observed-convo",
                    workdir = "/tmp/claude-observed",
                    sessionId = "claude-observed-sid",
                    observing = true,
                    executing = false,
                    agent = AgentKind.CLAUDE,
                ),
            )
            repo.setDefaultEffort("high")
            sent.clear()

            repo.takeOver()

            val open = sent.filterIsInstance<OpenSession>().single()
            assertTrue(open.takeOver)
            assertEquals("high", open.effort, "#237 must not change Claude takeover semantics")
        } finally {
            repo.setDefaultEffort(null)
            scope.cancel()
        }
    }

    @Test
    fun codex_takeover_keeps_existing_cli_model_and_effort_instead_of_new_defaults() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val sent = mutableListOf<Frame>()
        val repo = repo(scope, sent)
        try {
            repo.receiveForTest(
                SessionLive(
                    convoId = "observed-convo",
                    workdir = "/tmp/observed",
                    sessionId = "observed-sid",
                    observing = true,
                    executing = false,
                    agent = AgentKind.CODEX,
                ),
            )
            repo.setDefaultModelFor(AgentKind.CODEX, "gpt-5.6-sol")
            repo.setDefaultEffortFor(AgentKind.CODEX, "ultra")
            sent.clear()

            repo.takeOver()

            val open = sent.filterIsInstance<OpenSession>().single()
            assertTrue(open.takeOver)
            assertNull(open.model)
            assertNull(open.effort)
        } finally {
            repo.setDefaultModelFor(AgentKind.CODEX, null)
            repo.setDefaultEffortFor(AgentKind.CODEX, null)
            scope.cancel()
        }
    }

    @Test
    fun agentFrameIsNotDroppedBeforeTheDaemonHasDeclaredItsAgents() {
        // #276/#275: the reverse capability guard must NOT fire during the pre-DaemonInfo reconnect window.
        // daemonSupportedAgents is empty there for the ordinary reason "not told yet", not "unsupported" —
        // dropping then silently killed ZCode reattach on every reconnect. Once the daemon HAS declared a
        // set without zcode, the same frame is correctly dropped. Driven through fetchModels (an agent-
        // carrying frame that funnels the egress guard), so it also pins the C2 widening past OpenSession.
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val sent = mutableListOf<Frame>()
        val repo = repo(scope, sent)
        try {
            // unknown window: no DaemonInfo yet, supported set empty → must pass through
            repo.fetchModels(AgentKind.ZCODE)
            assertEquals(
                1, sent.filterIsInstance<FetchModels>().count { it.agent == AgentKind.ZCODE },
                "an agent-carrying frame must not be dropped before the daemon has declared its agents",
            )

            // now the daemon declares a set WITHOUT zcode → the same frame must be dropped
            sent.clear()
            repo.receiveForTest(DaemonInfo(supportedAgents = listOf("claude", "codex")))
            repo.fetchModels(AgentKind.ZCODE)
            assertTrue(
                sent.filterIsInstance<FetchModels>().none { it.agent == AgentKind.ZCODE },
                "once the daemon is known not to support zcode, the frame is correctly withheld",
            )
            // a supported agent still goes through after the handshake
            repo.fetchModels(AgentKind.CODEX)
            assertEquals(1, sent.filterIsInstance<FetchModels>().count { it.agent == AgentKind.CODEX })
        } finally {
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
    fun switchingDaemonsDropsTheOldCapabilityCacheUntilTheNewPeerReplies() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val sent = mutableListOf<Frame>()
        val repo = repo(scope, sent)
        try {
            repo.receiveForTest(
                ModelsList(
                    agent = AgentKind.CLAUDE,
                    models = listOf("sonnet"),
                    supportedEfforts = listOf("low", "medium", "high"),
                ),
            )
            assertEquals(listOf("low", "medium", "high"), repo.effortOptions(AgentKind.CLAUDE, "sonnet"))

            repo.disconnect()
            assertTrue(repo.effortOptions(AgentKind.CLAUDE, "sonnet").isEmpty(), "the old daemon catalog is gone")
            repo.setDefaultEffortFor(AgentKind.CLAUDE, "max")
            sent.clear()
            repo.openSession("/tmp/new-old-daemon", agent = AgentKind.CLAUDE)

            assertEquals(
                "max",
                sent.filterIsInstance<OpenSession>().single().effort,
                "before the new daemon advertises capabilities, effort is UNKNOWN and passes through",
            )
        } finally {
            repo.setDefaultEffortFor(AgentKind.CLAUDE, null)
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
            // Leave the chat first: re-opening the session we are LOOKING at is refused outright (#235
            // alreadyOpen), and this test's subject is the stale cached params, not that guard.
            repo.backToBrowse()
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
