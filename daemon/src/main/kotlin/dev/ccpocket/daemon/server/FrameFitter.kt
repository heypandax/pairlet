package dev.ccpocket.daemon.server

import dev.ccpocket.daemon.disk.ReplayBudget
import dev.ccpocket.daemon.disk.SessionFilesService
import dev.ccpocket.protocol.ConvoHistory
import dev.ccpocket.protocol.ConvoHistoryPage
import dev.ccpocket.protocol.Envelope
import dev.ccpocket.protocol.FileContent
import dev.ccpocket.protocol.FileDiff
import dev.ccpocket.protocol.Frame
import dev.ccpocket.protocol.HistoryMessage
import dev.ccpocket.protocol.PocketJson
import dev.ccpocket.protocol.Sessions
import dev.ccpocket.protocol.ToolEvent

/**
 * The last gate before a frame is sealed toward ONE client: keep the message inside what that client's
 * WebSocket will actually accept ([RequestRouter.ClientCapsHolder.maxFrameBytes]).
 *
 * Every earlier budget ([ReplayBudget], the tool-image thumbnailer, the file caps) assumes the relay's 4 MiB
 * ceiling. Shipped iOS builds are bounded at 1 MiB instead (KTOR-6963: the Darwin engine never applied our
 * `maxFrameSize`, so Apple's `NSURLSessionWebSocketTask` default stood), and a message over it does not fail
 * as one lost frame — the phone's link dies and the app reopens the same session into the same frame, so a
 * screenshot-heavy session became unopenable. Measured on the real encoding, because JSON escaping and the
 * envelope — not the payload sum — are what the transport carries.
 *
 * Shrinking is per frame type, cheapest loss first: a history window sheds row extras (images, sub-agent
 * reports) before rows, and drops rows oldest-first with its cursor metadata re-anchored so the phone pages
 * the rest in on demand; a tool event loses its images; a file body over the cap becomes the same "too
 * large" refusal the daemon already gives for files over its own cap; a diff keeps the whole lines that fit
 * and is marked truncated; a project's session list loses its
 * prompt previews, then its oldest rows. Anything else is sent as it is and
 * reported through [encodeWithin]'s callback, so a new oversized frame type shows up in the daemon log
 * instead of as a silent reconnect loop.
 *
 * Before any of that, a history window that FITS the client's cap but is heavy for a lossy link meets a soft
 * cap ([HISTORY_SOFT_CAP_BYTES]): it ships its newest rows only and leaves the oldest to paging. That step is
 * lossless by construction — see [softTrimHistory].
 */
object FrameFitter {
    /** Bytes the transport adds around the JSON: the Wire type byte, then E2ESession's 8-byte counter and 16-byte GCM tag. */
    const val SEAL_OVERHEAD_BYTES = 1 + 8 + 16

    /** Headroom kept under the cap: Apple documents its limit as a receive-buffer size, not an exact message size. */
    const val SLACK_BYTES = 8 * 1024

    /**
     * The soft cap on an encoded history frame (docs/design/SLOW-LINK-RESILIENCE.md 3.3). Not a transport limit:
     * on the lossy cross-border link between the relay and mainland carriers, loss grows with packet size and a
     * big frame takes disproportionately long (measured: ~73 KB in 2.3 s, 135 KB in 6–16 s, a 1.48 MB delta never
     * arrived), while every frame queued behind it on the in-order link waits — session acks, transcriptions,
     * approvals. An ordinary window (100 rows ≈ 73 KB) never reaches this; one heavy with screenshots or long
     * outputs does.
     */
    const val HISTORY_SOFT_CAP_BYTES = 256 * 1024

    /** The fewest rows a soft-trimmed window keeps. The phone does not yet page up on its own when a window is
     *  too short to fill the screen (SLOW-LINK-RESILIENCE §4 E), so a thinner window could leave it stuck there. */
    const val HISTORY_SOFT_MIN_ROWS = 20

    private const val MAX_PASSES = 8

    /** Does a JSON body of [jsonBytes] bytes fit a client whose cap is [maxFrameBytes] once sealed? */
    fun fits(jsonBytes: Int, maxFrameBytes: Long): Boolean = jsonBytes + SEAL_OVERHEAD_BYTES + SLACK_BYTES <= maxFrameBytes

    /**
     * [env] encoded as the bytes to seal. A history window over [HISTORY_SOFT_CAP_BYTES] first sheds its oldest
     * rows ([softTrimHistory]); [onSoftTrim] receives one content-free line when it does. When the sealed
     * message would still exceed [maxFrameBytes], the body is shrunk (see the class doc) and re-encoded;
     * [onOversize] receives one line per frame that had to be shrunk or that still does not fit. The common
     * case — a frame under both caps — costs one encode, which the callers did anyway.
     *
     * [onSoftTrim] sits BEFORE [onOversize] on purpose: the callers pass the hard-cap report as a trailing
     * lambda, which binds to the last function parameter.
     */
    fun encodeWithin(
        env: Envelope,
        maxFrameBytes: Long,
        onSoftTrim: (String) -> Unit = {},
        onOversize: (String) -> Unit = {},
    ): ByteArray {
        val plain = encode(env)
        val (sending, bytes) = softTrimHistory(env, plain, onSoftTrim) ?: (env to plain)
        if (fits(bytes.size, maxFrameBytes)) return bytes
        val type = sending.body::class.simpleName
        val shrunk = shrink(sending, maxFrameBytes)
        if (shrunk == null) {
            onOversize("$type: ${bytes.size} B is over this client's $maxFrameBytes B frame cap and cannot be shrunk — sent as is")
            return bytes
        }
        val out = encode(sending.copy(body = shrunk))
        onOversize(
            "$type: ${bytes.size} B is over this client's $maxFrameBytes B frame cap → shrunk to ${out.size} B" +
                if (fits(out.size, maxFrameBytes)) "" else " (still over — sent as is)",
        )
        return out
    }

    /**
     * The soft cap (SLOW-LINK-RESILIENCE 3.3) on a [ConvoHistory] (first window or delta) or [ConvoHistoryPage]
     * whose [encoded] form is over [HISTORY_SOFT_CAP_BYTES]. It allows only the one loss that is fully
     * recoverable: whole rows from the OLDEST end, until the frame is back under the cap or [HISTORY_SOFT_MIN_ROWS]
     * remain, anchored as [fitRows] anchors a window that lost rows — `firstSeq` on the first kept row, `hasMore`,
     * and a delta becomes a full window (a continuation missing its oldest rows would leave a hole the phone cannot
     * see) — so the phone pages the dropped rows back in. It never touches what is ON a row: shedding pictures and
     * sub-agent reports ([ReplayBudget.fit]) stays the hard cap's last resort. A window still over the soft cap at
     * the row floor goes on to the hard-cap check like any other frame.
     *
     * Left whole when the first row it would keep carries no cursor (a backend that does not page): there is
     * nothing to anchor on, so nothing could page the dropped rows back. Unlike [fitRows], which must make the
     * frame fit and so drops such leading rows too, this step is optional and simply stands down. Null = nothing
     * trimmed, send [encoded] as it is.
     */
    private fun softTrimHistory(env: Envelope, encoded: ByteArray, onSoftTrim: (String) -> Unit): Pair<Envelope, ByteArray>? {
        if (encoded.size <= HISTORY_SOFT_CAP_BYTES) return null
        val body = env.body
        val rows = when (body) {
            is ConvoHistory -> body.messages
            is ConvoHistoryPage -> body.messages
            else -> return null
        }
        if (rows.size <= HISTORY_SOFT_MIN_ROWS) return null
        fun trimmedFrom(cut: Int): Envelope {
            val kept = rows.drop(cut)
            val anchor = kept.first().seq
            return env.copy(
                body = when (body) {
                    is ConvoHistory -> body.copy(messages = kept, firstSeq = anchor, hasMore = true, delta = false)
                    is ConvoHistoryPage -> body.copy(messages = kept, firstSeq = anchor, hasMore = true)
                    else -> body
                },
            )
        }
        // Dropping the oldest row takes exactly its own encoding plus one separator out of the frame, so the cut
        // is found from per-row sizes rather than by re-encoding the whole window once per dropped row (a heavy
        // window has hundreds of rows) …
        var cut = 0
        var estimate = encoded.size.toLong()
        while (estimate > HISTORY_SOFT_CAP_BYTES && rows.size - cut > HISTORY_SOFT_MIN_ROWS) {
            estimate -= encodedRowBytes(rows[cut]) + 1
            cut++
        }
        // … and the re-anchored metadata (firstSeq, hasMore, delta), which moves the size by a few bytes either
        // way, is settled on the real encoding: the fewest rows dropped that bring the frame under the cap
        var sending = trimmedFrom(cut)
        var out = encode(sending)
        while (out.size > HISTORY_SOFT_CAP_BYTES && rows.size - cut > HISTORY_SOFT_MIN_ROWS) {
            cut++
            sending = trimmedFrom(cut)
            out = encode(sending)
        }
        while (cut > 1) {
            val fewer = trimmedFrom(cut - 1)
            val fewerBytes = encode(fewer)
            if (fewerBytes.size > HISTORY_SOFT_CAP_BYTES) break
            cut--
            sending = fewer
            out = fewerBytes
        }
        if (rows[cut].seq == null) return null
        onSoftTrim(
            "${body::class.simpleName}: ${rows.size} rows / ${encoded.size} B over the $HISTORY_SOFT_CAP_BYTES B history " +
                "soft cap → ${rows.size - cut} rows / ${out.size} B, the oldest $cut left to paging",
        )
        return sending to out
    }

    private fun encodedRowBytes(row: HistoryMessage): Int =
        PocketJson.encodeToString(HistoryMessage.serializer(), row).encodeToByteArray().size

    private fun encode(env: Envelope): ByteArray = PocketJson.encodeToString(Envelope.serializer(), env).encodeToByteArray()

    /** The largest JSON body that fits [maxFrameBytes]. */
    private fun jsonBudget(maxFrameBytes: Long): Long = maxFrameBytes - SEAL_OVERHEAD_BYTES - SLACK_BYTES

    private fun shrink(env: Envelope, maxFrameBytes: Long): Frame? = when (val body = env.body) {
        is ConvoHistory -> fitRows(env, maxFrameBytes, body.messages, body.firstSeq, body.hasMore, body.delta) { rows, first, more, delta ->
            body.copy(messages = rows, firstSeq = first, hasMore = more, delta = delta)
        }
        is ConvoHistoryPage -> fitRows(env, maxFrameBytes, body.messages, body.firstSeq, body.hasMore, delta = false) { rows, first, more, _ ->
            body.copy(messages = rows, firstSeq = first, hasMore = more)
        }
        // a live tool result's pictures are the only thing on it that can weigh a megabyte; the text stays.
        // ToolEvent has no imagesTruncated field, so the loss is only logged — the daemon's own image budget
        // (ImageThumbnail.MAX_TOTAL_BASE64_BYTES, 1 MB) keeps a live RESULT under the legacy 1 MiB cap, so
        // this only ever fires for a client that declared less than that; the reopen replay still carries
        // the pictures, announced via HistoryMessage.imagesTruncated
        is ToolEvent -> body.images.takeIf { it.isNotEmpty() }?.let { body.copy(images = emptyList()) }
        is FileContent -> when {
            // whole-or-nothing base64 (a truncated document is corrupt): the same refusal SessionFilesService
            // gives for a file over its own cap, so the viewer shows a reason instead of the link dropping
            body.base64 != null -> body.copy(
                ok = false,
                error = "file too large to send over this link (${body.totalBytes / 1024} KB — this app accepts frames up to " +
                    "${maxFrameBytes / 1024} KB; a newer app build raises that)",
                base64 = null,
            )
            else -> body.text?.let { text ->
                val room = (jsonBudget(maxFrameBytes) - encode(env.copy(body = body.copy(text = ""))).size).coerceAtLeast(0L)
                // JSON escaping can double a character, so halve the room rather than measure again
                body.copy(text = ReplayBudget.takeUtf8(text, room / 2), truncated = true)
            }
        }
        // a diff already carries `truncated`: keep the whole-line prefix that fits (audit 2026-10-04 D). The
        // daemon's own cap (SessionFilesService.DIFF_CAP_BYTES, encoded bytes) keeps this off the 1 MiB path;
        // it fires for a client that declared a smaller frame cap.
        is FileDiff -> body.diff?.let { diff ->
            val room = (jsonBudget(maxFrameBytes) - encode(env.copy(body = body.copy(diff = "", truncated = true))).size).coerceAtLeast(0L)
            body.copy(diff = SessionFilesService.clipLinesToJsonBytes(diff, room), truncated = true)
        }
        is Sessions -> fitSessions(env, maxFrameBytes, body)
        else -> null
    }

    /**
     * A session list that fits. The rows arrive newest-first and already carry clipped previews
     * ([RequestRouter.SESSION_PROMPT_CLIP]), so this only fires for a project with thousands of sessions or
     * a client with a small cap: the previews go first (the title still names every row), then the oldest
     * rows. The frame has no "truncated" field, so the loss is only logged — a list missing its oldest
     * rows still opens, where the oversized frame dropped the link on every tap.
     */
    private fun fitSessions(env: Envelope, maxFrameBytes: Long, body: Sessions): Frame {
        val target = jsonBudget(maxFrameBytes)
        fun measure(candidate: Sessions): Int = encode(env.copy(body = candidate)).size
        var kept = body.copy(items = body.items.map { it.copy(firstPrompt = "") })
        var encoded = measure(kept)
        var passes = 0
        while (encoded > target && passes++ < MAX_PASSES && kept.items.isNotEmpty()) {
            kept = kept.copy(items = kept.items.take((kept.items.size * target.toDouble() / encoded * 0.9).toInt()))
            encoded = measure(kept)
        }
        return kept
    }

    /**
     * A history window that fits: the rows are re-fitted through [ReplayBudget.fit] with a budget scaled
     * down by the measured overshoot, so extras are shed before rows and the newest rows survive. When no
     * row was lost the frame keeps its cursor metadata — a delta with lighter rows is still a clean delta.
     * When rows were dropped, the window is anchored on the oldest kept row that carries a cursor (a row
     * without one, e.g. a compact summary, cannot be paged back to, so it is dropped rather than shown
     * twice), `hasMore` is set, and a delta becomes a full window: a continuation missing its oldest rows
     * would leave a hole the phone cannot see.
     */
    private fun fitRows(
        env: Envelope,
        maxFrameBytes: Long,
        rows: List<HistoryMessage>,
        firstSeq: Long?,
        hasMore: Boolean,
        delta: Boolean,
        rebuild: (rows: List<HistoryMessage>, firstSeq: Long?, hasMore: Boolean, delta: Boolean) -> Frame,
    ): Frame {
        val target = jsonBudget(maxFrameBytes)
        fun measure(kept: List<HistoryMessage>): Int = encode(env.copy(body = rebuild(kept, firstSeq, hasMore, delta))).size
        var kept = rows
        var encoded = measure(kept)
        var budget = rows.sumOf { ReplayBudget.payloadSize(it) }
        var passes = 0
        while (encoded > target && passes++ < MAX_PASSES && budget > 0) {
            budget = (budget * target.toDouble() / encoded * 0.9).toLong()
            val next = ReplayBudget.fit(rows, budget) // always from the original rows: the shed is monotonic in the budget
            if (next == kept) { budget /= 2; continue } // a budget that changed nothing: cut harder
            kept = next
            encoded = measure(kept)
        }
        // the budget scaling converges in practice; this is the guarantee — whole oldest rows go until it fits
        while (encoded > target && kept.size > 1) {
            kept = kept.drop(1)
            encoded = measure(kept)
        }
        if (kept.size == rows.size) return rebuild(kept, firstSeq, hasMore, delta)
        val anchored = kept.dropWhile { it.seq == null }
        // rows without any cursor (a backend that does not page): keep what fits, there is nothing to anchor
        return if (anchored.isEmpty()) rebuild(kept, null, true, false)
        else rebuild(anchored, anchored.first().seq, true, false)
    }
}
