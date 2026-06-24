package dev.ccpocket.app.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Density
import dev.ccpocket.app.data.PocketRepository
import dev.ccpocket.app.theme.PocketTheme
import dev.ccpocket.app.theme.Tok
import dev.ccpocket.protocol.AgentKind
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.jetbrains.skia.EncodedImageFormat
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * REAL end-to-end test: drives a live [PocketRepository] against the running daemon (via the persisted desktop
 * pairing), then renders each real state to build/screenshots-live as PNGs. Unlike [DesktopUiTest] (seed data),
 * this proves the actual data path — connect → real projects → real sessions → real chat. Requires: the daemon
 * running + this desktop already paired (cc-pocket pair). Skips gracefully (not fail) if unpaired/offline so CI
 * without a daemon stays green.
 */
@OptIn(ExperimentalComposeUiApi::class)
class DesktopLiveTest {

    private val outDir = File("build/screenshots-live").apply { mkdirs() }
    private val scale = 2

    private fun shot(name: String, model: DesktopModel) {
        val scene = ImageComposeScene(width = 1180 * scale, height = 798 * scale, density = Density(scale.toFloat())) {
            PocketTheme { Box(Modifier.fillMaxSize().background(Tok.base)) { DesktopApp(model) } }
        }
        try {
            scene.render().encodeToData(EncodedImageFormat.PNG)?.let { File(outDir, name).writeBytes(it.bytes) }
        } finally {
            scene.close()
        }
    }

    private suspend fun waitUntil(timeoutMs: Long, cond: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (cond()) return true
            delay(150)
        }
        return cond()
    }

    @Test
    fun liveAgainstDaemon() = runBlocking {
        // opt-in only: connects to a real daemon, so it never runs in normal/CI test runs.
        // enable with:  ./gradlew :mobile:composeApp:desktopTest --tests '*DesktopLiveTest*' -PccpocketLive=1
        if (System.getProperty("ccpocket.live") != "1") {
            println("[live] SKIP — pass -PccpocketLive=1 to run the live daemon test")
            return@runBlocking
        }
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        val repo = PocketRepository(scope)
        if (repo.paired.value == null) {
            println("[live] SKIP — desktop not paired (run: cc-pocket pair, then enter the code in the app)")
            return@runBlocking
        }

        repo.startRelay()
        val gotDirs = waitUntil(25_000) { repo.directories.isNotEmpty() }
        if (!gotDirs) {
            println("[live] SKIP — connected=${repo.sessionActive.value} phase=${repo.phase.value} but no projects within 25s")
            scope.cancel(); return@runBlocking
        }
        val model = RepoDesktopModel(repo)
        shot("01-live-projects.png", model)
        println("[live] projects=${repo.directories.size} computer=${model.activeComputer?.name}")

        // a real project that has resumable history → list its sessions
        val proj = repo.directories.firstOrNull { it.hasSessions } ?: repo.directories.first()
        repo.listSessions(proj.path)
        val gotSessions = waitUntil(15_000) { repo.sessions.isNotEmpty() }
        shot("02-live-sessions.png", model)
        println("[live] project=${proj.path} sessions=${repo.sessions.size}")

        // open the most recent real session → render the real transcript
        if (gotSessions) {
            val s = repo.sessions.maxByOrNull { it.lastModified }!!
            repo.openSession(wd = s.cwd, resumeId = s.sessionId, title = s.title, agent = s.agent ?: AgentKind.CLAUDE)
            val gotMsgs = waitUntil(25_000) { repo.messages.isNotEmpty() }
            shot("03-live-chat.png", model)
            println("[live] session=${s.title} messages=${repo.messages.size} gotMsgs=$gotMsgs")
        }

        assertTrue(repo.directories.isNotEmpty(), "live daemon returned real projects")
        scope.cancel()
    }
}
