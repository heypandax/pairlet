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
 */
object ReplayBudget {
    const val MAX_FRAME_TEXT_BYTES = 1_500_000L

    /**
     * Trim [msgs] (already count-capped, in chronological order) so the summed UTF-8 size of their
     * [HistoryMessage.text] stays within [maxBytes]. Keeps the newest messages whole; the single
     * message that straddles the limit is truncated on a UTF-8-safe boundary and anything older is
     * dropped. Chronological order is preserved.
     */
    fun fit(msgs: List<HistoryMessage>, maxBytes: Long = MAX_FRAME_TEXT_BYTES): List<HistoryMessage> {
        var remaining = maxBytes
        val kept = ArrayDeque<HistoryMessage>()
        for (m in msgs.asReversed()) {
            val size = utf8Size(m.text)
            when {
                size <= remaining -> { kept.addFirst(m); remaining -= size }
                remaining <= 0L -> {} // budget already spent — drop this (older) row
                else -> { kept.addFirst(m.copy(text = takeUtf8(m.text, remaining))); remaining = 0L }
            }
        }
        return kept
    }

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
