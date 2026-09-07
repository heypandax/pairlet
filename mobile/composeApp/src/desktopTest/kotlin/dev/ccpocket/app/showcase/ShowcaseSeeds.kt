package dev.ccpocket.app.showcase

import dev.ccpocket.app.data.DemoData
import dev.ccpocket.app.epochMillis
import dev.ccpocket.protocol.AgentKind
import dev.ccpocket.protocol.ChatRole
import dev.ccpocket.protocol.ClaudeQuota
import dev.ccpocket.protocol.ClaudeQuotaLimit
import dev.ccpocket.protocol.CLAUDE_QUOTA_OK
import dev.ccpocket.protocol.CLAUDE_QUOTA_SEVERITY_NORMAL
import dev.ccpocket.protocol.CLAUDE_QUOTA_KIND_SESSION
import dev.ccpocket.protocol.ConvoHistory
import dev.ccpocket.protocol.Directories
import dev.ccpocket.protocol.DirectoryEntry
import dev.ccpocket.protocol.HistoryMessage
import dev.ccpocket.protocol.ImageData
import dev.ccpocket.protocol.PendingApproval
import dev.ccpocket.protocol.PendingApprovals
import dev.ccpocket.protocol.PermissionAsk
import dev.ccpocket.protocol.PermissionMode
import dev.ccpocket.protocol.SessionLive
import dev.ccpocket.protocol.SessionSummary
import dev.ccpocket.protocol.Sessions
import dev.ccpocket.protocol.ToolEvent
import dev.ccpocket.protocol.ToolPhase
import java.awt.Color
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/**
 * The ONE set of scripted demo fixtures the offscreen renderers in this package seed from.
 *
 * Extracted from [ShowcaseRender.coreFrames]/[ShowcaseRender.entryFrames] verbatim so the acceptance
 * stills, the marketing reels and the App Store frames can never tell three different stories about
 * the same product — a session list that says "Refactor auth module" in one artifact and something
 * else in the next is exactly the drift the offscreen renderers exist to prevent.
 *
 * Everything here is INVENTED demo copy under a fictional `/Users/alex` tree (the same fiction
 * [DemoData] uses). No fixture in this file may ever be seeded from a real transcript, a real path
 * or a real account: these bytes are published to the App Store.
 *
 * Times are expressed RELATIVE to `epochMillis()` on purpose. The UI renders them as "3m ago" /
 * "resets in 2h", so an absolute constant would make every regenerated frame drift with the
 * wall clock; relative offsets re-render identically forever.
 */
internal object ShowcaseSeeds {

    /** The fictional project every frame works inside. */
    const val WORKDIR = DemoData.LIVE_DIR

    /** Conversation + session identity shared by the chat fixtures below. */
    const val CONVO_ID = "core"
    const val SESSION_ID = "core-s1"

    private const val MINUTE = 60_000L

    private fun ago(ms: Long) = epochMillis() - ms
    private fun ahead(ms: Long) = epochMillis() + ms

    /**
     * A real paired binding, so the machine name in a header is a rendered fact rather than a blank.
     * The relay host is `.invalid` (RFC 2606) — this fixture can never point at anything reachable.
     */
    val ACCOUNT = dev.ccpocket.app.pairing.PairedDaemon(
        relay = "wss://showcase.invalid", accountId = "showcase", daemonPub = "pub",
        deviceId = "dev", credential = "cred", hostName = "alex-macbook",
    )

    /** The attached session announce: what the daemon says this conversation IS. */
    fun live(executing: Boolean) = SessionLive(
        convoId = CONVO_ID, workdir = WORKDIR, sessionId = SESSION_ID, mode = PermissionMode.DEFAULT,
        executing = executing, model = "claude-sonnet-4-5", agent = AgentKind.CLAUDE,
    )

    /** One transcript for every Chat frame: a user turn, an agent turn and a real tool result. */
    fun transcript() = ConvoHistory(
        CONVO_ID,
        listOf(
            HistoryMessage(ChatRole.USER, "add a unit test for the stream parser"),
            HistoryMessage(ChatRole.ASSISTANT, "The parser now emits exactly one event when a frame is split across chunks."),
            HistoryMessage(ChatRole.TOOL, "./gradlew :protocol:test", tool = "Bash", ok = true),
            HistoryMessage(ChatRole.ASSISTANT, "I'm checking the remaining call sites that read `TokenStore`."),
        ),
    )

    /** The blocking ask: pinned as Approval required in Chat, and as the loudest row in Sessions. */
    fun approvalAsk() = PermissionAsk(
        convoId = CONVO_ID, askId = "core-ap", tool = "Bash", title = "Upload coverage to Codecov",
        inputPreview = "./gradlew test && bash scripts/upload-coverage.sh",
        grantOptions = listOf("once", "task"), timeoutSec = 600,
    )

    /** …the same ask seen from the session list, where it is the row that needs a human. */
    fun blockedApprovals() = PendingApprovals(
        listOf(PendingApproval(approvalAsk(), workdir = WORKDIR, sessionId = SESSION_ID)),
    )

    /** Three sessions: one live, one on another backend, one long finished. */
    fun sessions() = Sessions(
        WORKDIR,
        listOf(
            SessionSummary(
                sessionId = SESSION_ID, title = "Refactor auth module",
                firstPrompt = "Review the concurrency around the refresh mutex before I open the PR.",
                messageCount = 24, cwd = WORKDIR, lastModified = ago(3 * MINUTE),
                gitBranch = "feat/auth-refactor", live = true,
            ),
            SessionSummary(
                sessionId = "core-s2", title = "Fix flaky socket test",
                firstPrompt = "The reconnect test still fails intermittently on CI.",
                messageCount = 9, cwd = WORKDIR, lastModified = ago(120 * MINUTE),
                gitBranch = "fix/socket-test", agent = AgentKind.CODEX,
            ),
            SessionSummary(
                sessionId = "core-s3", title = "Release notes 1.6",
                firstPrompt = "Summarize the user-visible changes from the last 12 commits.",
                messageCount = 15, cwd = WORKDIR, lastModified = ago(1680 * MINUTE),
                gitBranch = "main",
            ),
        ),
    )

    /**
     * Real [DirectoryEntry] shapes only: one live project with a branch and a live title, then plain
     * rows that claim nothing beyond a name, a path and the daemon's own mtime.
     */
    fun directories() = Directories(
        listOf(
            DirectoryEntry(
                path = WORKDIR, name = "cc-pocket", isDir = true, hasSessions = true, recent = true,
                lastModified = ago(3 * MINUTE), open = true, executing = true,
                activeSessionId = "entry-s1", activeSessionTitle = "Add demo mode for App Review",
                gitBranch = "main",
            ),
            DirectoryEntry(
                path = "/Users/alex/code/cc-pocket-site", name = "cc-pocket-site", isDir = true,
                hasSessions = true, lastModified = ago(90 * MINUTE),
            ),
            DirectoryEntry(
                path = "/Users/alex/code/relay-server", name = "relay-server", isDir = true,
                hasSessions = true, lastModified = ago(600 * MINUTE),
            ),
            DirectoryEntry(
                path = "/Users/alex/Library/Mobile Documents/com~apple~CloudDocs/notes-cli",
                name = "notes-cli", isDir = true, hasSessions = true, lastModified = ago(2400 * MINUTE),
            ),
        ),
    )

    /**
     * A subscription allowance in the shape the live `oauth/usage` endpoint returns.
     *
     * Deliberately ONE window (the rolling 5h session), not the 5h + 7d pair: the strip lays out
     * `brand · segments · <weight> · reset` in whatever width its host gives it, and the iPad's list
     * pane is a fixed 340dp. Two segments there leave ~45dp for the reset caption, which then
     * ellipsizes to a bare `r…` — correct behaviour, and an awful thing to publish to the App Store.
     * A single-window reading is a shape [quotaSections] handles first-class (`listOfNotNull`), and it
     * keeps the number the strip exists for: when the allowance comes back.
     *
     * `resetsAt` is relative for the reason given in the class KDoc.
     */
    fun quota() = ClaudeQuota(
        limits = listOf(
            ClaudeQuotaLimit(
                kind = CLAUDE_QUOTA_KIND_SESSION, group = "session", percent = 34,
                severity = CLAUDE_QUOTA_SEVERITY_NORMAL, resetsAt = ahead(148 * MINUTE), isActive = true,
            ),
        ),
        fetchedAt = ago(MINUTE),
        status = CLAUDE_QUOTA_OK,
    )

    // ── #332 · a tool RESULT that carried a picture ──────────────────────────────────────────────

    /** The START half of the screenshot call — the card the RESULT below then updates in place. */
    fun screenshotToolStart(seq: Long) = ToolEvent(
        convoId = CONVO_ID, seq = seq, phase = ToolPhase.START,
        tool = "mcp__playwright__browser_take_screenshot",
        inputPreview = "http://localhost:5173/pricing", toolUseId = "shot-1",
    )

    /**
     * …and its RESULT, carrying the thumbnail. The bytes are drawn here rather than checked in: a
     * committed binary fixture is one more thing that can quietly stop matching the story it
     * illustrates, and what this frame has to prove is only that a returned picture renders as a
     * picture. [pageCapture] draws a plainly synthetic page, never a real site.
     */
    @OptIn(ExperimentalEncodingApi::class)
    fun screenshotToolResult(seq: Long) = ToolEvent(
        convoId = CONVO_ID, seq = seq, phase = ToolPhase.RESULT,
        tool = "mcp__playwright__browser_take_screenshot",
        inputPreview = "http://localhost:5173/pricing", toolUseId = "shot-1", ok = true,
        images = listOf(ImageData("image/png", Base64.Default.encode(pageCapture()))),
    )

    /**
     * A synthetic "browser screenshot": a light page with a chrome bar, a hero block and a three-card
     * row. At the 92dp thumbnail the tool band renders it at, this reads as a captured web page —
     * which is exactly, and only, what the frame is claiming.
     */
    private fun pageCapture(w: Int = 720, h: Int = 452): ByteArray {
        val img = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
        val g = img.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g.color = Color(0xFA, 0xFA, 0xF7); g.fillRect(0, 0, w, h)             // page
        g.color = Color(0xEC, 0xEC, 0xE7); g.fillRect(0, 0, w, 34)            // browser chrome
        g.color = Color(0xC9, 0xC9, 0xC2)
        listOf(20, 42, 64).forEach { g.fillOval(it - 5, 12, 10, 10) }         // traffic lights
        g.color = Color(0xFF, 0xFF, 0xFF); g.fillRoundRect(92, 8, w - 120, 18, 9, 9)
        g.color = Color(0x22, 0x28, 0x33); g.fillRoundRect(56, 76, 372, 26, 6, 6)  // headline
        g.color = Color(0x8A, 0x90, 0x9B)
        g.fillRoundRect(56, 116, 470, 12, 4, 4); g.fillRoundRect(56, 138, 392, 12, 4, 4)
        g.color = Color(0xE4, 0x7D, 0x55); g.fillRoundRect(56, 172, 132, 34, 8, 8) // CTA
        for (i in 0 until 3) {                                                // card row
            val x = 56 + i * 204
            g.color = Color(0xFF, 0xFF, 0xFF); g.fillRoundRect(x, 250, 180, 150, 12, 12)
            g.color = Color(0xE2, 0xE2, 0xDC); g.drawRoundRect(x, 250, 180, 150, 12, 12)
            g.color = Color(0xD6, 0xE3, 0xE0); g.fillRoundRect(x + 16, 270, 44, 44, 10, 10)
            g.color = Color(0xB4, 0xB9, 0xC1)
            g.fillRoundRect(x + 16, 330, 132, 10, 4, 4); g.fillRoundRect(x + 16, 350, 96, 10, 4, 4)
        }
        g.dispose()
        val out = ByteArrayOutputStream()
        ImageIO.write(img, "png", out)
        return out.toByteArray()
    }
}
