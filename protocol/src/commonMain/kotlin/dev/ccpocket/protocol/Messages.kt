package dev.ccpocket.protocol

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// ===========================================================================
//  phone  ->  daemon   (ToDaemon)
// ===========================================================================

/** List browsable directories: recents + children of an optional [root] (null => recents + defaults). */
@Serializable
@SerialName("pocket/dirs.list")
data class ListDirectories(val root: String? = null) : ToDaemon

/** List resumable sessions for a working directory (reads .jsonl headers; no claude launch). */
@Serializable
@SerialName("pocket/sessions.list")
data class ListSessions(val workdir: String) : ToDaemon

/** Open a session: resume (resumeId != null) or start new (resumeId == null). */
@Serializable
@SerialName("pocket/session.open")
data class OpenSession(
    val workdir: String,
    val resumeId: String? = null,
    val model: String? = null,
    val mode: PermissionMode = PermissionMode.DEFAULT,
    val takeOver: Boolean = false, // true = resume/control even a session live in a terminal (vs observe)
) : ToDaemon

/** Restart the live conversation's claude process under a new cwd. */
@Serializable
@SerialName("pocket/session.switchDir")
data class SwitchDirectory(val convoId: String, val workdir: String) : ToDaemon

/** Send a user turn into a live conversation. */
@Serializable
@SerialName("pocket/prompt")
data class SendPrompt(val convoId: String, val text: String, val images: List<ImageData> = emptyList()) : ToDaemon

/** A base64 image attached to a prompt — downscaled on the phone to fit the relay frame cap. */
@Serializable
data class ImageData(val mediaType: String, val base64: String)

/** Resolve a pending permission prompt. askId == the Anthropic request_id (1:1). */
@Serializable
@SerialName("pocket/verdict")
data class PermissionVerdict(
    val convoId: String,
    val askId: String,
    val decision: Decision,
    val updatedInput: String? = null,
    val message: String? = null,
    val remember: Boolean = false, // ALLOW + remember => add an allow-rule so future matches auto-allow this session
) : ToDaemon

/** Switch the live conversation's permission mode (relaunches claude with --resume + the new mode). */
@Serializable
@SerialName("pocket/mode.switch")
data class SwitchMode(val convoId: String, val mode: PermissionMode) : ToDaemon

/** Drop a session allow-rule (rule == null clears them all) so it prompts again next time. */
@Serializable
@SerialName("pocket/rule.clear")
data class ClearAllowRule(val convoId: String, val rule: String? = null) : ToDaemon

/** Interrupt the current turn. */
@Serializable
@SerialName("pocket/turn.cancel")
data class CancelTurn(val convoId: String) : ToDaemon

/** Tear down a live conversation (clean kill of the process group). */
@Serializable
@SerialName("pocket/session.close")
data class CloseSession(val convoId: String) : ToDaemon

/** One chunk of a voice capture. Chunks of a recording share [captureId]; daemon reassembles by [idx]. */
@Serializable
@SerialName("pocket/audio.chunk")
data class AudioChunk(
    val convoId: String,
    val captureId: String,   // phone-generated, one per recording (fresh per retry)
    val idx: Int,            // 0-based
    val last: Boolean,       // true on the final chunk -> transcription starts once 0..idx are all present
    val mediaType: String,   // "audio/mp4" (AAC m4a) | "audio/wav" (desktop PCM)
    val base64: String,
) : ToDaemon

/** Abandon a capture (user cancelled mid-upload); the daemon drops buffered chunks + any running job. */
@Serializable
@SerialName("pocket/audio.cancel")
data class AudioCancel(val convoId: String, val captureId: String) : ToDaemon

// ===========================================================================
//  daemon  ->  phone   (ToPhone)
// ===========================================================================

@Serializable
@SerialName("pocket/dirs")
data class Directories(val entries: List<DirectoryEntry>, val root: String? = null) : ToPhone

@Serializable
@SerialName("pocket/sessions")
data class Sessions(val workdir: String, val items: List<SessionSummary>) : ToPhone

/**
 * The conversation is live. sessionId is backfilled once claude reports system.init.
 * [mode] is the daemon's ACTUAL permission mode — the phone reconciles its badge from it.
 * Null when observing: the terminal owns that session, the daemon doesn't know its mode.
 */
@Serializable
@SerialName("pocket/session.live")
data class SessionLive(
    val convoId: String,
    val workdir: String,
    val sessionId: String? = null,
    val observing: Boolean = false,
    val mode: PermissionMode? = null,
) : ToPhone

/** A streamed assistant content piece. seq is monotonic per convo for ordering. */
@Serializable
@SerialName("pocket/chunk")
data class AssistantChunk(val convoId: String, val seq: Long, val piece: StreamPiece) : ToPhone

/** A tool invocation surfaced to the UI (original; no Anthropic schema crosses the wire). */
@Serializable
@SerialName("pocket/tool")
data class ToolEvent(
    val convoId: String,
    val seq: Long,
    val phase: ToolPhase,
    val tool: String,
    val inputPreview: String? = null,
    val ok: Boolean? = null,
) : ToPhone

/** A permission prompt the phone must resolve. askId == Anthropic request_id. */
@Serializable
@SerialName("pocket/ask")
data class PermissionAsk(
    val convoId: String,
    val askId: String,
    val tool: String,
    val inputPreview: String,
    val mode: PermissionMode? = null,
    val title: String = "",            // human verb, e.g. "Run command" / "Write file"
    val rule: String? = null,          // the scope "Always allow" would remember, e.g. "git status" / "Edit"
    val danger: Boolean = false,       // destructive tool (rm, force-push…): nudge to "Allow once"
    val dangerNote: String? = null,    // e.g. "delete files"
) : ToPhone

/** Turn finished. finalText is the result text (if any); usage is token accounting (if present). */
@Serializable
@SerialName("pocket/turn.done")
data class TurnDone(
    val convoId: String,
    val finalText: String? = null,
    val usage: TokenUsage? = null,
) : ToPhone

/** An error surfaced to the phone. convoId null = connection-level. */
@Serializable
@SerialName("pocket/error")
data class PocketError(
    val code: String,
    val message: String,
    val convoId: String? = null,
) : ToPhone

@Serializable
enum class ChatRole {
    @SerialName("user") USER,
    @SerialName("assistant") ASSISTANT,
    @SerialName("tool") TOOL,
}

/** One past message in a resumed session's transcript. */
@Serializable
data class HistoryMessage(val role: ChatRole, val text: String, val tool: String? = null)

/** daemon -> phone: the prior transcript of a resumed session, sent once after [SessionLive]. */
@Serializable
@SerialName("pocket/history")
data class ConvoHistory(val convoId: String, val messages: List<HistoryMessage>) : ToPhone

/** One slash command the composer can offer. [name] has no leading "/". */
@Serializable
data class SlashCommand(
    val name: String,
    val description: String = "",
    val argumentHint: String? = null,  // e.g. "<name>" / "[instructions]"; null = takes no arguments
    val source: CommandSource = CommandSource.BUILTIN,
)

/** Where a slash command was discovered — shown as a small tag in the composer menu. */
@Serializable
enum class CommandSource {
    @SerialName("builtin") BUILTIN,
    @SerialName("user") USER,       // ~/.claude/commands/*.md
    @SerialName("project") PROJECT, // <workdir>/.claude/commands/*.md
    @SerialName("skill") SKILL,     // ~/.claude/skills/<name>/ or <workdir>/.claude/skills/<name>/
}

/** daemon -> phone: the slash commands available to this conversation, sent after [SessionLive]. */
@Serializable
@SerialName("pocket/commands")
data class CommandList(val convoId: String, val commands: List<SlashCommand>) : ToPhone

/** daemon -> phone: result of transcribing a voice capture. ok=false carries a user-facing [error]. */
@Serializable
@SerialName("pocket/transcript")
data class Transcript(
    val convoId: String,
    val captureId: String,
    val text: String = "",
    val ok: Boolean = true,
    val error: String? = null, // e.g. "whisper-cli not found — brew install whisper-cpp"
) : ToPhone

// ===========================================================================
//  control plane  <->  relay   (ToRelay; carried in Envelope{to=RELAY} TEXT frames)
//
//  The relay parses ONLY these. App content travels as opaque, end-to-end
//  encrypted BINARY frames the relay forwards without decoding (zero-knowledge).
//  All byte-valued fields below are base64url(no-pad) of raw bytes.
// ===========================================================================

@Serializable
enum class Role {
    @SerialName("daemon") DAEMON,
    @SerialName("device") DEVICE,
}

// ---- daemon login: signed-challenge over its Ed25519 static key ----

/** daemon -> relay: opening claim. accountId MUST equal base32(sha256(ed25519Pub)). */
@Serializable
@SerialName("pocket/daemon.hello")
data class DaemonHello(val accountId: String, val ed25519Pub: String, val protoV: Int = 1) : ToRelay

/** relay -> daemon: a single-use nonce to sign (bound to this socket, short TTL). */
@Serializable
@SerialName("pocket/challenge")
data class Challenge(val nonce: String, val serverTimeMs: Long) : ToRelay

/** daemon -> relay: Ed25519 signature over "ccpocket/daemon-auth/v1"|0x00|accountId|nonce. */
@Serializable
@SerialName("pocket/daemon.auth")
data class DaemonAuth(val sig: String) : ToRelay

// ---- device login: bearer credential issued at pairing ----

/** device -> relay: opening claim. secret is checked against stored sha256(secret). */
@Serializable
@SerialName("pocket/device.hello")
data class DeviceHello(val deviceId: String, val secret: String, val protoV: Int = 1) : ToRelay

// ---- handshake result ----

/** relay -> peer: authenticated and bound to accountId; binary data plane is now live. */
@Serializable
@SerialName("pocket/attached")
data class Attached(val role: Role, val accountId: String) : ToRelay

/** relay -> peer: auth/handshake failed; the relay closes the socket after this. */
@Serializable
@SerialName("pocket/auth.error")
data class AuthError(val code: String, val message: String? = null) : ToRelay

// ---- pairing (only an authenticated daemon may mint) ----

/** daemon -> relay: mint a short-lived, single-use pairing ticket. Carries the daemon's E2E public
 *  key so the relay can serve it to a phone that pairs by short code (the QR path keeps it out-of-band). */
@Serializable
@SerialName("pocket/pair.begin")
data class PairBegin(val e2ePub: String) : ToRelay

/** relay -> daemon: the raw ticket (for the QR) plus a short 6-digit code to type on the phone. */
@Serializable
@SerialName("pocket/pair.ticket")
data class PairTicket(val ticket: String, val expiresInSec: Int, val code: String) : ToRelay

/** relay -> daemon: a device redeemed a ticket. devicePubKey is an advisory hint; the
 *  daemon allow-lists it only after the first ticket-PSK Noise handshake succeeds. */
@Serializable
@SerialName("pocket/device.paired")
data class DevicePaired(val deviceId: String, val devicePubKey: String) : ToRelay

/** daemon -> relay: revoke a device; the relay marks it revoked and force-closes its socket. */
@Serializable
@SerialName("pocket/device.revoke")
data class RevokeDevice(val deviceId: String) : ToRelay

/** relay -> peer: the other end's online/offline transition. */
@Serializable
@SerialName("pocket/peer.presence")
data class PeerPresence(val online: Boolean) : ToRelay

// ---- pairing redeem (REST DTOs over POST /v1/pair/redeem; not Frames) ----

/** device -> relay (HTTP body): redeem a scanned ticket, registering its X25519 static pubkey. */
@Serializable
data class PairRedeem(val ticket: String, val devicePubKey: String)

/** relay -> device (HTTP body): the issued device credential + the account it is bound to. */
@Serializable
data class PairCredential(val deviceId: String, val credential: String, val accountId: String)

/** device -> relay (HTTP body): resolve a short pairing code typed by the user. */
@Serializable
data class PairCodeResolve(val code: String)

/** relay -> device (HTTP body): the pairing payload behind a code (relay is the one being asked). */
@Serializable
data class PairCodePayload(val accountId: String, val daemonPub: String, val ticket: String)
