package dev.ccpocket.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asSkiaBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SkikoComposeUiTest
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.unit.Density
import dev.ccpocket.app.data.FileUpState
import dev.ccpocket.app.data.PendingFile
import dev.ccpocket.app.data.PocketRepository
import dev.ccpocket.app.data.VoiceState
import dev.ccpocket.app.pairing.PairedDaemon
import dev.ccpocket.app.resources.Res
import dev.ccpocket.app.resources.default_model_section
import dev.ccpocket.app.resources.settings_cat_agent
import dev.ccpocket.app.resources.voice_transcribe_failed
import dev.ccpocket.app.str
import dev.ccpocket.app.theme.PocketTheme
import dev.ccpocket.app.theme.Tok
import dev.ccpocket.protocol.ActiveSession
import dev.ccpocket.protocol.AgentKind
import dev.ccpocket.protocol.DirectoryEntry
import dev.ccpocket.protocol.ModelCapabilities
import dev.ccpocket.protocol.ModelServiceTier
import dev.ccpocket.protocol.ModelsList
import dev.ccpocket.protocol.PermissionMode
import dev.ccpocket.protocol.SessionLive
import dev.ccpocket.protocol.SessionSummary
import dev.ccpocket.protocol.Sessions
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image
import java.io.File
import java.util.Locale
import kotlin.test.Test

/**
 * Opt-in proof renderer for the three related UI refinements (#237/#238/#239).
 *
 * These are not golden tests. They render the real Settings, Chat and Sessions composables with the same
 * repository/protocol shapes their interaction tests use, so design review can inspect the actual state
 * density instead of a parallel mock. The normal test suite skips all file output unless
 * `UI_REFINEMENT_OUT` is present.
 */
@OptIn(ExperimentalTestApi::class)
class UiRefinementShots {

    private val output: File? = System.getenv("UI_REFINEMENT_OUT")?.let(::File)?.apply { mkdirs() }

    private fun account() = PairedDaemon(
        relay = "wss://test.invalid", accountId = "acct-ui-refine", daemonPub = "pub",
        deviceId = "dev", credential = "cred", hostName = "Panda · MacBook Pro",
    )

    private fun live(executing: Boolean) = SessionLive(
        convoId = "refine-convo", workdir = "/Users/panda/code/cc-pocket", sessionId = "refine-live",
        mode = PermissionMode.DEFAULT, executing = executing, model = "gpt-5.6-sol", agent = AgentKind.CODEX,
        contextUsed = 84_000,
    )

    private fun SkikoComposeUiTest.save(name: String) {
        val target = output ?: return
        val skia = onRoot().captureToImage().asSkiaBitmap()
        val png = Image.makeFromBitmap(skia).encodeToData(EncodedImageFormat.PNG)
            ?: error("PNG encode failed for $name")
        File(target, name).writeBytes(png.bytes)
        println("ui refinement frame: $name")
    }

    private fun render(
        name: String,
        width: Int = 402,
        height: Int = 874,
        fontScale: Float = 1f,
        dark: Boolean = true,
        locale: Locale? = null,
        seed: PocketRepository.() -> Unit = {},
        content: @androidx.compose.runtime.Composable (PocketRepository) -> Unit,
        drive: SkikoComposeUiTest.() -> Unit = {},
    ) {
        val previousLocale = Locale.getDefault()
        locale?.let(Locale::setDefault)
        try {
            runDesktopComposeUiTest(width, height) {
                mainClock.autoAdvance = false
                setContent {
                    CompositionLocalProvider(LocalDensity provides Density(1f, fontScale)) {
                        val scope = rememberCoroutineScope()
                        val repo = remember { PocketRepository(scope, account()).apply(seed) }
                        PocketTheme(dark = dark) {
                            Box(Modifier.fillMaxSize().background(Tok.base)) { content(repo) }
                        }
                    }
                }
                waitForIdle()
                drive()
                waitForIdle()
                save(name)
            }
        } finally {
            Locale.setDefault(previousLocale)
        }
    }

    @Test
    fun renderCurrentUiRefinementStates() {
        if (output == null) return

        val settingsSeed: PocketRepository.() -> Unit = {
            setDefaultAgent(AgentKind.CODEX)
            setDefaultModelFor(AgentKind.CODEX, "gpt-5.6-sol")
            setDefaultEffortFor(AgentKind.CODEX, "ultra")
            setDefaultServiceTier("priority")
            receiveForTest(
                ModelsList(
                    agent = AgentKind.CODEX,
                    models = listOf("gpt-5.6-sol", "gpt-5.5", "gpt-5.6-codex-specialized-with-long-context"),
                    modelCapabilities = listOf(
                        ModelCapabilities(
                            model = "gpt-5.6-sol",
                            reasoningEfforts = listOf("low", "medium", "high", "xhigh", "max", "ultra"),
                            serviceTiers = listOf(ModelServiceTier("priority", "Fast")),
                        ),
                    ),
                ),
            )
        }
        render(
            name = "settings-agent-defaults-402.png",
            locale = Locale.SIMPLIFIED_CHINESE,
            seed = settingsSeed,
            content = { SettingsScreen(it, onBack = {}) },
            drive = { onAllNodes(hasText(str(Res.string.settings_cat_agent))).onFirst().performClick() },
        )
        render(
            name = "settings-agent-defaults-en-light-390.png",
            width = 390,
            height = 844,
            dark = false,
            locale = Locale.US,
            seed = settingsSeed,
            content = { SettingsScreen(it, onBack = {}) },
            drive = { onAllNodes(hasText(str(Res.string.settings_cat_agent))).onFirst().performClick() },
        )
        render(
            name = "settings-agent-defaults-320.png",
            width = 320,
            seed = settingsSeed,
            content = { SettingsScreen(it, onBack = {}) },
            drive = {
                onAllNodes(hasText(str(Res.string.settings_cat_agent))).onFirst().performClick()
                waitForIdle()
                onAllNodes(hasText(str(Res.string.default_model_section).uppercase())).onFirst().performScrollTo()
            },
        )
        render(
            name = "settings-agent-defaults-200pct.png",
            width = 390,
            fontScale = 2f,
            seed = settingsSeed,
            content = { SettingsScreen(it, onBack = {}) },
            drive = { onAllNodes(hasText(str(Res.string.settings_cat_agent))).onFirst().performClick() },
        )
        // #237 · S3: the read-only summary under the hardest combination it has to survive — the narrowest
        // supported width with a long custom id, where its label/value pairs stack instead of truncating
        render(
            name = "settings-summary-long-id-280.png",
            width = 280,
            height = 700,
            seed = {
                settingsSeed()
                setDefaultModelFor(AgentKind.CODEX, "gpt-5.6-codex-specialized-with-long-context")
            },
            content = { SettingsScreen(it, onBack = {}) },
            drive = { onAllNodes(hasText(str(Res.string.settings_cat_agent))).onFirst().performClick() },
        )

        // All eight composer states from the master have a real Compose frame: idle, staged, streaming
        // empty/staged, upload, recording, transcribing and failure.
        render(
            name = "chat-idle-en-light-402.png",
            dark = false,
            locale = Locale.US,
            seed = { receiveForTest(live(executing = false)) },
            content = { ChatScreen(it) },
        )
        render(
            name = "chat-staged-en-light-402.png",
            dark = false,
            locale = Locale.US,
            seed = { receiveForTest(live(executing = false)) },
            content = { ChatScreen(it) },
            drive = { onAllNodes(hasSetTextAction()).onFirst().performTextInput("Keep this draft for review.") },
        )
        render(
            name = "chat-streaming-empty-402.png",
            locale = Locale.SIMPLIFIED_CHINESE,
            seed = { receiveForTest(live(executing = true)) },
            content = { ChatScreen(it) },
        )
        render(
            name = "chat-streaming-draft-320.png",
            width = 320,
            seed = { receiveForTest(live(executing = true)) },
            content = { ChatScreen(it) },
            drive = {
                onAllNodes(hasSetTextAction()).onFirst()
                    .performTextInput("Queue this after dictation and keep the current draft for review.")
            },
        )
        render(
            name = "chat-streaming-recording-320.png",
            width = 320,
            seed = {
                receiveForTest(live(executing = true))
                voice.value = VoiceState.Recording(12_400)
            },
            content = { ChatScreen(it) },
        )
        render(
            name = "chat-streaming-transcribing-320.png",
            width = 320,
            locale = Locale.SIMPLIFIED_CHINESE,
            seed = {
                receiveForTest(live(executing = true))
                voice.value = VoiceState.Transcribing
            },
            content = { ChatScreen(it) },
        )
        render(
            name = "chat-voice-failed-320.png",
            width = 320,
            seed = {
                receiveForTest(live(executing = false))
                voice.value = VoiceState.Failed(Res.string.voice_transcribe_failed)
            },
            content = { ChatScreen(it) },
            drive = { onAllNodes(hasSetTextAction()).onFirst().performTextInput("Keep this draft") },
        )
        render(
            name = "chat-upload-only-320.png",
            width = 320,
            seed = {
                receiveForTest(live(executing = false))
                pendingFiles += PendingFile(
                    id = 7L, name = "trace.log", size = 2048, bytes = ByteArray(0),
                    mediaType = "text/plain", state = FileUpState.Uploading,
                )
            },
            content = { ChatScreen(it) },
        )
        // #238 · V3: the two frames the lane's wrapping rule exists for — the narrowest supported width
        // with the full accessory set, and 200% type where nothing may pack inline
        render(
            name = "chat-streaming-draft-280.png",
            width = 280,
            height = 720,
            seed = { receiveForTest(live(executing = true)); directories.add(otherProject()) },
            content = { ChatScreen(it) },
            drive = {
                onAllNodes(hasSetTextAction()).onFirst()
                    .performTextInput("Queue this after dictation and keep the current draft for review.")
            },
        )
        render(
            name = "chat-streaming-upload-200pct.png",
            fontScale = 2f,
            seed = {
                receiveForTest(live(executing = true))
                directories.add(otherProject())
                pendingFiles += PendingFile(
                    id = 8L, name = "trace.log", size = 2048, bytes = ByteArray(0),
                    mediaType = "text/plain", state = FileUpState.Uploading,
                )
            },
            content = { ChatScreen(it) },
        )

        val now = dev.ccpocket.app.epochMillis()
        // #239 · R3: two independent New Results above one Running, with Complete in Recent — the frame
        // the half-filled mark exists to keep separable from the neutral ring beside it
        val sessionsSeed: PocketRepository.() -> Unit = {
                receiveForTest(
                    Sessions(
                        "/Users/panda/code/cc-pocket",
                        listOf(
                            SessionSummary(
                                sessionId = "result-a", title = "Refine composer states",
                                firstPrompt = "Keep voice input available while a draft is staged.",
                                messageCount = 18, cwd = "/Users/panda/code/cc-pocket",
                                lastModified = now - 45_000, gitBranch = "feat/mobile-composer",
                                agent = AgentKind.CODEX,
                            ),
                            SessionSummary(
                                sessionId = "result-b", title = "Audit session state precedence",
                                firstPrompt = "Verify two background results remain independently discoverable.",
                                messageCount = 9, cwd = "/Users/panda/code/cc-pocket",
                                lastModified = now - 120_000, gitBranch = "feat/session-states",
                            ),
                            SessionSummary(
                                sessionId = "running-c", title = "Run the mobile test suite",
                                firstPrompt = "Execute the full regression suite.",
                                messageCount = 4, cwd = "/Users/panda/code/cc-pocket",
                                lastModified = now - 10_000, gitBranch = "main", live = true,
                            ),
                            SessionSummary(
                                sessionId = "complete-d", title = "Document Harmony release",
                                firstPrompt = "Write the release procedure.",
                                messageCount = 12, cwd = "/Users/panda/code/cc-pocket",
                                lastModified = now - 3_600_000, gitBranch = "main",
                            ),
                        ),
                    ),
                )
                unseenSessions.value = setOf("result-a", "result-b")
            }
        render(
            name = "sessions-new-results-402.png",
            locale = Locale.SIMPLIFIED_CHINESE,
            seed = sessionsSeed,
            content = { SessionsScreen(it) },
        )
        render(
            name = "sessions-new-results-en-light-402.png",
            dark = false,
            locale = Locale.US,
            seed = sessionsSeed,
            content = { SessionsScreen(it) },
        )
        render(
            name = "sessions-new-results-200pct.png",
            fontScale = 2f,
            seed = sessionsSeed,
            content = { SessionsScreen(it) },
        )
    }

    /** A second project with a live session of its own — the only thing that gives the switcher a count. */
    private fun otherProject() = DirectoryEntry(
        path = "/Users/panda/code/relay", name = "relay", isDir = true, open = true,
        activeSessions = listOf(ActiveSession(sessionId = "s-relay", title = "Fix relay backoff", executing = true)),
        activeSessionId = "s-relay", activeSessionTitle = "Fix relay backoff",
    )
}
