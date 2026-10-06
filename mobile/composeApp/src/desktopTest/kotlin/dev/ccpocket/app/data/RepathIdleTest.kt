package dev.ccpocket.app.data

import dev.ccpocket.protocol.AgentKind
import dev.ccpocket.protocol.PermissionAsk
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.UnconfinedTestDispatcher

/** #404 / TRANSPORT-AUTO-REPATH-V1 4.3: one case per blocking clause of [PocketRepository.repathIdle]. */
class RepathIdleTest {
    private lateinit var scope: CoroutineScope

    @BeforeTest fun setUp() { scope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(TestCoroutineScheduler())) }
    @AfterTest fun tearDown() = scope.cancel()

    private fun pane(r: PocketRepository) = SidePane(7, "sid-7", "/Users/dev/api", "Pane", AgentKind.CLAUDE)
        .also { r.sidePanes.panes += it }

    private fun busy(name: String, arrange: PocketRepository.() -> Unit) {
        val r = PocketRepository(scope)
        assertTrue(r.repathIdle(), "fresh repository is idle (baseline for '$name')")
        r.arrange()
        assertFalse(r.repathIdle(), "$name must block the planned switch")
    }

    @Test fun mainChatStreaming() = busy("main streaming") { streaming.value = true }
    // extended thinking is a per-session SETTING (#345), not activity: a session that has it on is idle
    // between turns like any other, or the planned switch would never happen for it
    @Test fun thinkingSettingAloneDoesNotBlock() {
        val r = PocketRepository(scope)
        r.thinking.value = true
        assertTrue(r.repathIdle(), "the thinking setting is not activity")
    }
    @Test fun sidePaneStreaming() = busy("side pane streaming") { pane(this).streaming.value = true }
    @Test fun queuedTurn() = busy("queued turn") { turnQueued.value = true }
    @Test fun stalledSend() = busy("stalled send") { sendStalled.value = true }
    @Test fun unsentPromptMain() = busy("unsent prompt") { transcript.messages += ChatItem.User("hi", pending = true) }
    @Test fun unsentPromptPane() = busy("unsent pane prompt") {
        pane(this).messages += ChatItem.User("hi", pending = true)
    }
    @Test fun pendingAskMain() = busy("pending ask") {
        pendingAsk.value = PermissionAsk(convoId = "c", askId = "a", tool = "Bash", inputPreview = "ls", title = "Run")
    }
    @Test fun pendingAskPane() = busy("pane ask") {
        pane(this).pendingAsk.value = PermissionAsk(convoId = "c", askId = "a", tool = "Bash", inputPreview = "ls", title = "Run")
    }
    @Test fun openingSession() = busy("opening") { opening.value = true }
    @Test fun switchingMode() = busy("mode switch") { switching.value = true }
    @Test fun switchingSession() = busy("session switch") { switchingSession.value = true }
    @Test fun newTaskStarting() = busy("new task") { newTaskStarting.value = true }
    @Test fun paneOpening() = busy("pane opening") { pane(this) } // a fresh pane is opening until its SessionLive
    @Test fun imageCompressing() = busy("compressing") { pendingImages += PendingImage(1, ByteArray(0), ImgState.Compressing) }
    @Test fun fileUploading() = busy("upload") {
        pendingFiles += PendingFile(id = 5L, name = "a.log", size = 1, bytes = ByteArray(0), mediaType = "text/plain",
            state = FileUpState.Uploading)
    }
    @Test fun voiceRecording() = busy("recording") { voice.value = VoiceState.Recording(0) }
    @Test fun voiceTranscribing() = busy("transcribing") { voice.value = VoiceState.Transcribing }
    @Test fun voiceUploading() = busy("voice upload") { voiceUploading.value = true }
    @Test fun historyPaging() = busy("history paging") { historyLoadingOlder.value = true }
    @Test fun fileViewLoading() = busy("file view") { viewedFilePath.value = "/x/a.kt" }
    @Test fun exportWaiting() = busy("export") {
        viewedFilePath.value = "/x/a.kt"; viewedFile.value = null; exportWaiting.value = true
    }

    @Test fun settledStateIsIdle() {
        val r = PocketRepository(scope)
        r.transcript.messages += ChatItem.User("done", delivered = true)
        r.pendingImages += PendingImage(1, ByteArray(0), ImgState.Ready)
        r.voice.value = VoiceState.Idle
        assertTrue(r.repathIdle())
    }
}
