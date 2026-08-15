package dev.ccpocket.daemon.disk

import dev.ccpocket.protocol.HistoryMessage

/**
 * Frame-safety budget for a replayed transcript (issue #81).
 *
 * A `ConvoHistory` is delivered as one E2E-sealed binary WebSocket frame; the relay drops any frame
 * over `RelayServer.MAX_FRAME` = 4 MiB with `FrameTooBigException`, killing the connection. The old
 * guard truncated *each* message to 2000 chars, which clipped a single long reply (the reported bug:
 * a 2343-char answer replayed as exactly its first 2000 chars) while doing nothing to bound the sum
 * of 100 such messages. This bounds the **total** UTF-8 size instead: recent messages replay whole,
 * and only a genuinely huge session sheds its oldest rows.
 *
 * Budget math (why [MAX_FRAME_TEXT_BYTES] is well under 4 MiB):
 * sealed frame bytes = JSON(`Envelope{ConvoHistory}`) + 25 B (E2E counter + AEAD tag, 1 B Wire type —
 * the data path is a *binary* frame, so there is no base64 expansion). On top of the raw text the
 * JSON adds per-message structure (~60 B) and string escaping — ~1.1x for prose, up to ~2x for
 * newline/quote-dense code. At the 1.5 MB text budget that is ≤3 MB of JSON worst-case, leaving
 * >1 MB of headroom under the 4 MiB cap. 1.5 MB of text is ~500k CJK or ~1.5M ASCII chars across up
 * to `maxMessages` rows — orders of magnitude past any real single reply, so real replies never clip.
 *
 * The budget counts a row's WHOLE frame payload, not just [HistoryMessage.text] (issue #33): a replayed
 * sub-agent TOOL row also carries [HistoryMessage.output] (issue #77, per-row cap `SUBAGENT_OUTPUT_MAX`
 * = 4000 chars) and an answered question row carries [HistoryMessage.answers] — 100 rows × 4000 CJK
 * output alone is ~1.2 MB that would otherwise ride on top of the 1.5 MB text budget and erode the
 * frame headroom. Since issue #254 a USER row can also carry [HistoryMessage.images] as inline base64
 * (~100 KB apiece, and a computer-side paste can be far larger) — by far the heaviest thing on a row,
 * so it is both COUNTED here and pre-capped per message by [capImages]. See [payloadSize].
 */
object ReplayBudget {
    const val MAX_FRAME_TEXT_BYTES = 1_500_000L

    /** Per-image base64 ceiling for a replayed prompt attachment (issue #254). One phone-uplinked JPEG
     *  is ~100 KB of base64; a computer-side paste of a retina screenshot can be many times that, and a
     *  single such image would eat a third of the whole frame budget — shedding it (and saying so via
     *  [HistoryMessage.imagesTruncated]) beats letting it evict the conversation around it. */
    const val MAX_IMAGE_BASE64_BYTES = 600_000L

    /** Per-message tile ceiling (issue #254): the app's own attach tray and both renderers are built
     *  around a handful of tiles, so replaying more costs frame budget nobody sees. */
    const val MAX_IMAGES_PER_MESSAGE = 4

    /**
     * Trim [msgs] (already count-capped, in chronological order) so the summed [payloadSize] stays
     * within [maxBytes]. Keeps the newest messages whole. A message that does NOT fit first sheds its
     * heavy optional payloads (sub-agent output / answers / images) and is measured again — only if it
     * is still over does it become the oldest kept row, clipped on a UTF-8-safe boundary, with
     * everything older dropped. That re-measure is what keeps an attachment-heavy turn from evicting
     * the conversation around it. Chronological order is preserved, and the row COUNT never changes
     * for rows that fit — the delta path in [ReplaySlicer.slice] reads `size == input size` as
     * "nothing was trimmed".
     *
     * Order of sacrifice, cheapest first: images → sub-agent report / answers → text. Images are also
     * capped per-message up front ([capImages]).
     */
    fun fit(msgs: List<HistoryMessage>, maxBytes: Long = MAX_FRAME_TEXT_BYTES): List<HistoryMessage> {
        var remaining = maxBytes
        val kept = ArrayDeque<HistoryMessage>()
        for (raw in msgs.asReversed()) {
            val m = capImages(raw)
            val size = payloadSize(m)
            when {
                size <= remaining -> { kept.addFirst(m); remaining -= size }
                remaining <= 0L -> {} // budget already spent — drop this (older) row
                else -> {
                    // Over budget: shed the expandable extras (sub-agent report / answers / images) and
                    // MEASURE AGAIN before giving up on the rest of the history. Images made that
                    // re-measure load-bearing: [MAX_IMAGES_PER_MESSAGE] images at [MAX_IMAGE_BASE64_BYTES]
                    // is 2.4 MB — well past the whole 1.5 MB budget — so a perfectly ordinary
                    // 3-screenshot turn straddles on its PICTURES while its words fit easily. Zeroing
                    // `remaining` there would evict every older row for attachments the phone can't see
                    // anyway, leaving a reconnect with a one-row transcript.
                    // Image loss is ANNOUNCED (unlike output/answers) so the phone says so in place.
                    val stripped = m.copy(
                        output = null,
                        answers = null,
                        images = emptyList(),
                        imagesTruncated = m.imagesTruncated || m.images.isNotEmpty(),
                    )
                    val strippedSize = payloadSize(stripped)
                    if (strippedSize <= remaining) {
                        // the row's TEXT fits — keep it whole and let older rows spend what is left
                        kept.addFirst(stripped)
                        remaining -= strippedSize
                    } else {
                        // genuinely too big even stripped: this is the oldest row we keep, clipped
                        kept.addFirst(stripped.copy(text = takeUtf8(stripped.text, remaining)))
                        remaining = 0L
                    }
                }
            }
        }
        return kept
    }

    /**
     * Drop the attachments no replay frame should carry (issue #254): any single image whose base64
     * exceeds [MAX_IMAGE_BASE64_BYTES], and everything past the first [MAX_IMAGES_PER_MESSAGE] that
     * survive that. Flags [HistoryMessage.imagesTruncated] when anything was dropped. Returns [m]
     * untouched when there is nothing to do — the overwhelmingly common (image-less) row.
     */
    fun capImages(m: HistoryMessage): HistoryMessage {
        if (m.images.isEmpty()) return m
        val kept = m.images.filter { it.base64.length <= MAX_IMAGE_BASE64_BYTES }.take(MAX_IMAGES_PER_MESSAGE)
        return if (kept.size == m.images.size) m else m.copy(images = kept, imagesTruncated = true)
    }

    /**
     * Total UTF-8 bytes a row contributes to the `ConvoHistory` frame body: [HistoryMessage.text] plus
     * the sub-agent report [HistoryMessage.output] (issue #77) plus replayed [HistoryMessage.answers]
     * plus the base64 of every attached image (issue #254 — inline base64 dwarfs everything else on a
     * row that has any, so leaving it out of the count would blow the 4 MiB frame cap outright).
     */
    fun payloadSize(m: HistoryMessage): Long =
        utf8Size(m.text) +
            (m.output?.let(::utf8Size) ?: 0L) +
            (m.answers?.sumOf { utf8Size(it.question) + utf8Size(it.answer) } ?: 0L) +
            // base64 is ASCII-only, so length == UTF-8 bytes — and not scanning a 600 KB string per row matters
            m.images.sumOf { it.base64.length.toLong() }

    /** UTF-8 byte length of [s] without allocating the encoded array. */
    fun utf8Size(s: String): Long {
        var n = 0L
        var i = 0
        while (i < s.length) {
            val c = s[i].code
            if (c in 0xD800..0xDBFF && i + 1 < s.length && s[i + 1].code in 0xDC00..0xDFFF) {
                n += 4; i += 2 // surrogate pair = one code point = 4 UTF-8 bytes
            } else {
                n += if (c < 0x80) 1 else if (c < 0x800) 2 else 3
                i += 1
            }
        }
        return n
    }

    /** Longest prefix of [s] whose UTF-8 size is <= [maxBytes], never splitting a surrogate pair. */
    private fun takeUtf8(s: String, maxBytes: Long): String {
        var used = 0L
        var i = 0
        while (i < s.length) {
            val c = s[i].code
            val pair = c in 0xD800..0xDBFF && i + 1 < s.length && s[i + 1].code in 0xDC00..0xDFFF
            val w = if (pair) 4L else if (c < 0x80) 1L else if (c < 0x800) 2L else 3L
            if (used + w > maxBytes) break
            used += w
            i += if (pair) 2 else 1
        }
        return s.substring(0, i)
    }
}
