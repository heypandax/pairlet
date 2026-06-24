package dev.ccpocket.app.desktop

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.ccpocket.app.data.ChatItem
import dev.ccpocket.protocol.AgentKind
import dev.ccpocket.protocol.PermissionAsk
import dev.ccpocket.protocol.PermissionMode

// ── view types (carry the ids/paths the actions need) ───────────────────────────────────────────

enum class DkOs { MAC, LINUX, WIN }

data class DkComputer(
    val accountId: String,
    val name: String,
    val os: DkOs,
    val online: Boolean,
    val meta: String,
)

data class DkProject(
    val path: String,
    val name: String,
    val running: Boolean = false,
    val history: Boolean = false,
)

data class DkSession(
    val sessionId: String,
    val cwd: String,
    val title: String,
    val agent: AgentKind = AgentKind.CLAUDE,
    val running: Boolean = false,
    val pending: Int = 0,
)

/**
 * The desktop shell reads everything through this — so the UI is agnostic to whether it is driven by a live
 * [dev.ccpocket.app.data.PocketRepository] ([RepoDesktopModel]) or by static seed data ([SeedDesktopModel],
 * used by the screenshot generator and UI tests). Getters read snapshot state, so reads recompose normally.
 */
interface DesktopModel {
    // connection + computer switcher
    val connected: Boolean
    val activeComputer: DkComputer?
    val computers: List<DkComputer>
    fun selectComputer(c: DkComputer)
    fun addComputer()

    // ui-local overlay flags
    var switcherOpen: Boolean
    var showNewSession: Boolean
    var showTray: Boolean
    var showPermissionModal: Boolean // seed/demo only; the live model surfaces [ask] inline instead

    // sidebar: projects + the current project's sessions
    val projects: List<DkProject>
    val sessions: List<DkSession>
    val selectedSessionId: String?
    fun openProject(p: DkProject)
    fun selectSession(s: DkSession)
    fun newSession(agent: AgentKind, mode: PermissionMode)

    // main pane: the open chat
    val hasChat: Boolean
    val chatTitle: String
    val chatAgent: AgentKind
    val chatWorkdir: String
    val chatBranch: String?
    val chatModel: String
    val chatMode: PermissionMode
    val messages: List<ChatItem>
    val streaming: Boolean
    var composer: String
    fun send(text: String)

    // permission (live: inline card in the stream; seed: also drives the focused modal)
    val ask: PermissionAsk?
    fun resolve(allow: Boolean, remember: Boolean)
    fun dismissAsk()
}

/** A status dot that gently pulses (scale + alpha + soft glow) — "this is live / working". */
@Composable
fun PulseDot(color: Color, size: Dp = 7.dp) {
    val t by rememberInfiniteTransition().animateFloat(
        initialValue = 1f,
        targetValue = 0.4f,
        animationSpec = infiniteRepeatable(tween(1300), RepeatMode.Reverse),
    )
    Box(
        Modifier
            .size(size)
            .scale(0.6f + 0.4f * t)
            .graphicsLayer { alpha = t }
            .clip(RoundedCornerShape(999.dp))
            .background(color),
    )
}
