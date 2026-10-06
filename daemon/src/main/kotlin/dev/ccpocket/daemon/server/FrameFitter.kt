package dev.ccpocket.daemon.server

import dev.ccpocket.daemon.disk.ReplayBudget
import dev.ccpocket.daemon.media.ImagePreviews
import dev.ccpocket.protocol.ChatRole
import dev.ccpocket.daemon.disk.SessionFilesService
import dev.ccpocket.protocol.ConvoHistory
import dev.ccpocket.protocol.ConvoHistoryPage
import dev.ccpocket.protocol.Envelope
import dev.ccpocket.protocol.FileContent
import dev.ccpocket.protocol.FileDiff
import dev.ccpocket.protocol.Frame
import dev.ccpocket.protocol.HistoryMessage
import dev.ccpocket.protocol.ImageContent
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
 * lossless by construction — see [trimOldestRows].
 *
 * And before THAT, for a connection that declared it ([Lean]): pictures become tile-sized previews
 * ([ImagePreviews]), and a history frame is bounded by a much smaller byte budget — the first screen, not the
 * last hundred rows. Both are lossless too: the full picture and the older rows are one request away. This is
 * the per-connection stage on purpose. A window is fanned out to every attached client, and only here is it
 * known which of them asked for the lean shape.
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

    /**
     * The lean shape one connection asked for (`ClientCaps.supportsImagePreviews` /
     * `supportsShortHistoryWindow`, docs/design/SLOW-LINK-RESILIENCE.md §6). [OFF] — every connection that
     * declared nothing — leaves this fitter exactly as it was.
     */
    data class Lean(val imagePreviews: Boolean = false, val shortHistoryWindow: Boolean = false) {
        companion object { val OFF = Lean() }
    }

    /**
     * The byte budget of a FIRST history window for a connection that pages on its own: the first screen and a
     * few screens of scroll-back, not the last hundred rows. Measured on the cross-border link this exists for,
     * 73 KB took 2.3 s on a good moment and a 390 KB window never arrived at all; an ordinary row is ~0.7 KB and
     * a picture preview 5–20 KB, so 32 KB is some thirty to forty rows. The rest pages in behind it.
     */
    const val LEAN_FIRST_WINDOW_BYTES = 32 * 1024

    /** A reattach DELTA up to this size goes out whole — it is exactly the rows the client is missing. A
     *  bigger one is a long absence: it becomes a first window ([LEAN_FIRST_WINDOW_BYTES]) the client swaps in. */
    const val LEAN_DELTA_BYTES = 64 * 1024

    /** One older-history page for such a connection: the rows nearest the window, the rest on the next page. */
    const val LEAN_PAGE_BYTES = 64 * 1024

    /** The fewest rows a lean frame keeps whatever they weigh: never an empty first screen, never a page that
     *  makes no progress. The client fills a short window by paging, which is why this can be so low. */
    const val LEAN_MIN_ROWS = 8

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
        lean: Lean = Lean.OFF,
        onSoftTrim: (String) -> Unit = {},
        onOversize: (String) -> Unit = {},
    ): ByteArray {
        // previews first: every budget below should measure the frame as it will actually travel
        val shaped = if (lean.imagePreviews) withPreviews(env) else env
        val plain = encode(shaped)
        val (sending, bytes) = (if (lean.shortHistoryWindow) leanTrimHistory(shaped, plain, onSoftTrim) else null)
            ?: softTrimHistory(shaped, plain, onSoftTrim)
            ?: (shaped to plain)
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
     * [env] with its pictures as previews ([ImagePreviews]) — history rows and a live tool result alike. A
     * prompt attachment gets the larger preview (its bubble shows it bigger); everything else is a tool result.
     * The same envelope comes back when there is nothing to change, which is nearly always.
     */
    private fun withPreviews(env: Envelope): Envelope {
        fun rows(convoId: String, rows: List<HistoryMessage>): List<HistoryMessage>? {
            if (rows.none { it.images.isNotEmpty() }) return null
            var changed = false
            val out = rows.map { row ->
                if (row.images.isEmpty()) return@map row
                val edge = if (row.role == ChatRole.USER) ImagePreviews.USER_EDGE else ImagePreviews.TOOL_EDGE
                val images = ImagePreviews.shape(convoId, row.images, edge)
                if (images === row.images) row else { changed = true; row.copy(images = images) }
            }
            return if (changed) out else null
        }
        return when (val body = env.body) {
            is ConvoHistory -> rows(body.convoId, body.messages)?.let { env.copy(body = body.copy(messages = it)) } ?: env
            is ConvoHistoryPage -> rows(body.convoId, body.messages)?.let { env.copy(body = body.copy(messages = it)) } ?: env
            is ToolEvent -> {
                val images = ImagePreviews.shape(body.convoId, body.images, ImagePreviews.TOOL_EDGE)
                if (images === body.images) env else env.copy(body = body.copy(images = images))
            }
            else -> env
        }
    }

    /**
     * The lean budget (docs/design/SLOW-LINK-RESILIENCE.md §6) for a connection that pages a short window on
     * its own: a first window over [LEAN_FIRST_WINDOW_BYTES], a delta over [LEAN_DELTA_BYTES] (which then
     * becomes a first window) or an older page over [LEAN_PAGE_BYTES] keeps its newest rows only. Same lossless
     * cut as the soft cap — see [trimOldestRows]. Null = nothing trimmed.
     */
    private fun leanTrimHistory(env: Envelope, encoded: ByteArray, onTrim: (String) -> Unit): Pair<Envelope, ByteArray>? {
        val (trigger, target) = when (val body = env.body) {
            is ConvoHistory -> if (body.delta) LEAN_DELTA_BYTES to LEAN_FIRST_WINDOW_BYTES else LEAN_FIRST_WINDOW_BYTES to LEAN_FIRST_WINDOW_BYTES
            is ConvoHistoryPage -> LEAN_PAGE_BYTES to LEAN_PAGE_BYTES
            else -> return null
        }
        if (encoded.size <= trigger) return null
        return trimOldestRows(env, encoded, target, LEAN_MIN_ROWS, skipUnanchored = true, label = "lean history budget", onTrim = onTrim)
    }

    /**
     * The soft cap (SLOW-LINK-RESILIENCE 3.3) on a [ConvoHistory] (first window or delta) or [ConvoHistoryPage]
     * whose [encoded] form is over [HISTORY_SOFT_CAP_BYTES] — for every connection, lean or not. See
     * [trimOldestRows] for the cut. Left whole when the first row it would keep carries no cursor (a backend
     * that does not page): unlike [fitRows], which must make the frame fit and so drops such leading rows too,
     * this step is optional and simply stands down.
     */
    private fun softTrimHistory(env: Envelope, encoded: ByteArray, onSoftTrim: (String) -> Unit): Pair<Envelope, ByteArray>? {
        if (encoded.size <= HISTORY_SOFT_CAP_BYTES) return null
        return trimOldestRows(env, encoded, HISTORY_SOFT_CAP_BYTES.toInt(), HISTORY_SOFT_MIN_ROWS, skipUnanchored = false,
            label = "history soft cap", onTrim = onSoftTrim)
    }

    /**
     * Bring a history frame under [capBytes] by the one loss that is fully recoverable: whole rows from the
     * OLDEST end, until the frame is under the cap or [minRows] remain, anchored as [fitRows] anchors a window
     * that lost rows — `firstSeq` on the first kept row, `hasMore`, and a delta becomes a full window (a
     * continuation missing its oldest rows would leave a hole the phone cannot see) — so the phone pages the
     * dropped rows back in. It never touches what is ON a row: shedding pictures and sub-agent reports
     * ([ReplayBudget.fit]) stays the hard cap's last resort. A frame still over the cap at the row floor goes
     * on to the next check like any other.
     *
     * Two things keep the cut lossless:
     *  - it never falls INSIDE one source line. Paging answers rows strictly before `firstSeq`, so a window that
     *    began on the second row of a line would orphan the first (an assistant line can yield several rows,
     *    all with the same cursor). The cut moves to the older edge of that line — one row group heavier than
     *    the cap asked for, never lighter than the floor.
     *  - the first kept row must carry a cursor, or nothing could page the dropped rows back. With
     *    [skipUnanchored] the cut moves past leading rows that have none (a compact summary — it is on the older
     *    page too, by its line) while that keeps the floor; without it, or when no row has a cursor at all (a
     *    backend that does not page), the frame is left whole.
     *
     * Null = nothing trimmed, send [encoded] as it is.
     */
    private fun trimOldestRows(
        env: Envelope,
        encoded: ByteArray,
        capBytes: Int,
        minRows: Int,
        skipUnanchored: Boolean,
        label: String,
        onTrim: (String) -> Unit,
    ): Pair<Envelope, ByteArray>? {
        if (encoded.size <= capBytes) return null
        val body = env.body
        val rows = when (body) {
            is ConvoHistory -> body.messages
            is ConvoHistoryPage -> body.messages
            else -> return null
        }
        if (rows.size <= minRows) return null
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
        val maxCut = rows.size - minRows
        // Dropping the oldest row takes exactly its own encoding plus one separator out of the frame, so the cut
        // is found from per-row sizes rather than by re-encoding the whole window once per dropped row (a heavy
        // window has hundreds of rows) …
        var cut = 0
        var estimate = encoded.size.toLong()
        while (estimate > capBytes && cut < maxCut) {
            estimate -= encodedRowBytes(rows[cut]) + 1
            cut++
        }
        // … and the re-anchored metadata (firstSeq, hasMore, delta), which moves the size by a few bytes either
        // way, is settled on the real encoding: the fewest rows dropped that bring the frame under the cap
        var out = encode(trimmedFrom(cut))
        while (out.size > capBytes && cut < maxCut) {
            cut++
            out = encode(trimmedFrom(cut))
        }
        while (cut > 1) {
            val fewerBytes = encode(trimmedFrom(cut - 1))
            if (fewerBytes.size > capBytes) break
            cut--
            out = fewerBytes
        }
        // never inside one source line: back to that line's older edge
        fun splitsLine(k: Int) = k in 1 until rows.size && rows[k].seq != null && rows[k].seq == rows[k - 1].seq
        var aligned = cut
        while (splitsLine(aligned)) aligned--
        // a first kept row needs a cursor to page from
        if (rows[aligned].seq == null) {
            if (!skipUnanchored) return null
            var next = aligned
            while (next <= maxCut && rows[next].seq == null) next++
            if (next > maxCut || splitsLine(next)) return null
            aligned = next
        }
        if (aligned == 0) return null
        if (aligned != cut) { cut = aligned; out = encode(trimmedFrom(cut)) }
        val sending = trimmedFrom(cut)
        onTrim(
            "${body::class.simpleName}: ${rows.size} rows / ${encoded.size} B over the $capBytes B $label " +
                "→ ${rows.size - cut} rows / ${out.size} B, the oldest $cut left to paging",
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
        // lean history: the full picture behind a preview. A prompt attachment is served as the transcript
        // holds it (up to ReplayBudget.MAX_IMAGE_BASE64_BYTES), which a connection with a small declared cap
        // cannot take — it keeps its preview and is told the picture is unavailable, rather than losing its link
        is ImageContent -> body.image?.let { body.copy(image = null, error = ImageContent.ERROR_UNAVAILABLE) }
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
