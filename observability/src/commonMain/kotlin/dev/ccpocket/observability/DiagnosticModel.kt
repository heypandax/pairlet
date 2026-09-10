package dev.ccpocket.observability

import kotlinx.serialization.Serializable

/** Stable, bounded classification. Never pass business text, paths, tokens or arbitrary attributes. */
@Serializable
enum class ErrorPath(val id: String) {
    STARTUP("EP-01"), ASYNC_WORKER("EP-02"), CONNECTION("EP-03"), PAIRING("EP-04"),
    HANDSHAKE("EP-05"), PROTOCOL("EP-06"), OUTBOX("EP-07"), RELAY("EP-08"),
    SESSION_LIST("EP-09"), SESSION_OPEN("EP-10"), HISTORY_READ("EP-11"), HISTORY_PAGE("EP-12"),
    PAYLOAD_SEND("EP-13"), HISTORY_APPLY("EP-14"), CONTENT("EP-15"), AGENT_START("EP-16"),
    PROMPT("EP-17"), AGENT_PROTOCOL("EP-18"), TURN("EP-19"), APPROVAL("EP-20"),
    FILE_READ("EP-21"), FILE_UPLOAD("EP-22"), STORAGE("EP-23"), BACKGROUND("EP-24"),
    PEER_DELIVERY("EP-25"), GIT("EP-26"), UPDATE("EP-27"), PUSH("EP-28"),
    LIFECYCLE("EP-29"), DIAGNOSTICS("EP-30"),
}

@Serializable enum class Component { ANDROID, IOS, DESKTOP, DAEMON, RELAY }
@Serializable enum class Environment { DEVELOPMENT, STAGING, PRODUCTION }
@Serializable enum class Stage {
    LAYOUT, START, CONFIGURE, CONNECT, ATTACH, HANDSHAKE, DECRYPT, DECODE, QUEUE, WRITE, RECEIVE,
    SCAN, READ, PARSE, ENCODE, APPLY, COMPLETE, SPAWN, INITIALIZE, ACK, EXECUTE, EXIT,
    REQUEST, WAIT, VERDICT, COMMIT, DISPATCH, RECONCILE, DOWNLOAD, VERIFY, EXTRACT, RESTART,
}
@Serializable enum class Outcome { SUCCESS, FAILURE, TIMEOUT, CANCELLED, RECOVERED, UNKNOWN }
@Serializable enum class ResultQuality { COMPLETE, PARTIAL, FALLBACK, UNKNOWN }
@Serializable enum class AgentBackendLabel { CLAUDE, CODEX, OPENCODE, KIMI, ZCODE, DSH, UNKNOWN }
@Serializable enum class Transport { RELAY, DIRECT, LOCAL, UNKNOWN }
@Serializable enum class DiagnosticKind { LOG, ERROR, RESULT, RECOVERY }
@Serializable enum class ErrorCode {
    UNEXPECTED, UNAVAILABLE, REJECTED, EXPIRED, NOT_FOUND, PERMISSION_DENIED, TIMEOUT,
    DECODE_FAILED, UNSUPPORTED, READ_FAILED, WRITE_FAILED, SEND_FAILED, QUEUE_CLOSED,
    INCOMPLETE, PARTIAL_RESULT, FALLBACK_USED, SIZE_LIMIT, RATE_LIMITED, SUPERSEDED,
    CONNECTION_CLOSED, SPAWN_FAILED, PROCESS_EXITED, IO_FAILED, APPLY_FAILED,
    COMMIT_FAILED, CANCELLED, RECOVERED, OK, SMOKE_TEST,
}

/** Numeric technical facts only. Negative/huge/unbounded values are normalized at the collection seam. */
@Serializable
data class SafeMetrics(
    val totalCount: Long? = null,
    val failedCount: Long? = null,
    val returnedCount: Long? = null,
    val byteCount: Long? = null,
    val queueSize: Long? = null,
    val exitCode: Int? = null,
    val resultQuality: ResultQuality? = null,
    val transport: Transport? = null,
    val backend: AgentBackendLabel? = null,
) {
    internal fun bounded() = copy(
        totalCount = totalCount?.coerceIn(0, MAX_VALUE), failedCount = failedCount?.coerceIn(0, MAX_VALUE),
        returnedCount = returnedCount?.coerceIn(0, MAX_VALUE), byteCount = byteCount?.coerceIn(0, MAX_VALUE),
        queueSize = queueSize?.coerceIn(0, MAX_VALUE), exitCode = exitCode?.coerceIn(-65_536, 65_536),
    )
    private companion object { const val MAX_VALUE = 1_000_000_000_000L }
}

@Serializable data class SafeStackFrame(val symbol: String, val file: String? = null, val line: Int? = null)
@Serializable data class SafeException(val type: String, val frames: List<SafeStackFrame>)
@Serializable data class DiagnosticStep(val stage: Stage, val elapsedMs: Long, val code: ErrorCode)

@ConsistentCopyVisibility
@Serializable
data class DiagnosticRecord internal constructor(
    val schemaVersion: Int = 1,
    val eventId: String,
    val traceId: String?,
    val path: ErrorPath,
    val component: Component,
    val environment: Environment,
    val release: String,
    val kind: DiagnosticKind,
    val stage: Stage,
    val code: ErrorCode,
    val outcome: Outcome?,
    val occurredAtMs: Long,
    val elapsedMs: Long?,
    val attempt: Int,
    val metrics: SafeMetrics,
    val exception: SafeException?,
    val steps: List<DiagnosticStep>,
    val suppressedCount: Long,
    val spanId: String? = null,
    val parentSpanId: String? = null,
    val connectionId: String? = null,
    val peerConnectionId: String? = null,
)

internal expect class DiagnosticLock() { fun <T> withLock(block: () -> T): T }
internal expect fun diagnosticId(): String
internal expect fun diagnosticEpochMs(): Long
internal expect fun safeException(error: Throwable): SafeException

internal object SafeSymbols {
    private val identifier = Regex("[A-Za-z0-9_.$<>-]{1,160}")
    private val filename = Regex("[A-Za-z0-9_.-]{1,100}\\.(kt|java|swift|m|mm)")
    fun type(value: String?): String = value?.takeIf { identifier.matches(it) } ?: "Exception"
    fun frame(symbol: String, file: String?, line: Int?): SafeStackFrame? {
        if (!symbol.startsWith("dev.ccpocket.") || !identifier.matches(symbol)) return null
        return SafeStackFrame(symbol, file?.takeIf { filename.matches(it) }, line?.takeIf { it in 1..1_000_000 })
    }
    fun release(value: String): String = value.takeIf { Regex("[A-Za-z0-9][A-Za-z0-9_.+@-]{0,95}").matches(it) } ?: "unknown"
}
