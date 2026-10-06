package dev.ccpocket.app.ui.chat

/**
 * When the chat asks for a page of OLDER history by itself (docs/design/SLOW-LINK-RESILIENCE.md §6).
 *
 * The reader reaching the top of the loaded window was always one trigger. The other is new with lean history:
 * the daemon may now answer an open with a window bounded by bytes — the newest rows only — and such a window
 * can be shorter than the screen. A list that cannot scroll has no "reached the top" to wait for, so it has to
 * page on the facts instead: there is more above, the rows it has are laid out, and they do not fill the view.
 * That is what `ClientCaps.supportsShortHistoryWindow` promises the daemon.
 *
 * [parkedAtTop] is the screen's own reading: the first row is at the very top AND either the reader scrolled
 * up to get there or the list cannot scroll at all. The remaining inputs are what keep the request honest:
 *  - [landed]: the first positioning of this conversation is done — not the instant a transcript lands and the
 *    list sits clamped at index 0 before it is scrolled to the latest message (the self-driving loop of #165);
 *  - [laidOutItems] ≥ [rows]: the list has MEASURED the rows it was just given. Until then "cannot scroll" is a
 *    statement about the previous content — an empty list, right after an open — and would fetch a page for
 *    every window that is perfectly tall once measured.
 */
internal fun wantsOlderHistory(parkedAtTop: Boolean, landed: Boolean, hasMore: Boolean, laidOutItems: Int, rows: Int): Boolean =
    parkedAtTop && landed && hasMore && rows > 0 && laidOutItems >= rows

/**
 * How long the chat waits for a requested page before asking again while it is STILL parked where it wanted
 * one. A page that is merely slow is accepted whenever it lands (the repository keeps the request open past its
 * spinner), so this only matters for one that was lost — and a window too short to scroll has no gesture left
 * to retry with. Long enough that a slow link is not handed the same page twice.
 */
internal const val HISTORY_PAGE_RETRY_MS = 30_000L

/** What the chat list currently says about paging — one value so the collector reacts to any part changing. */
internal data class HistoryPageAsk(
    /** [wantsOlderHistory] holds right now. */
    val wanted: Boolean,
    /** The reader scrolled up to the top (as opposed to: the list is too short to scroll at all). */
    val byReader: Boolean,
    /** Display rows on screen — AFTER folding, which is what the reader sees. */
    val rows: Int,
    /** Bumped by every older page that lands. */
    val pageGen: Int,
)

/** How many pages a window that cannot scroll may fetch on its own before it waits for the reader — when the
 *  daemon bounds its pages by bytes (`DaemonInfo.supportsLeanHistory`). */
internal const val SHORT_WINDOW_AUTO_PAGES = 3

/** The same against a daemon that does not: one page, which is what this screen fetched before lean history.
 *  Its pages are up to a hundred full rows with full-size pictures — not something to pull three of unasked. */
internal const val SHORT_WINDOW_AUTO_PAGES_LEGACY = 1

/**
 * The budget for the pages a too-short window fetches BY ITSELF. A reader at the top asked for what they get;
 * a list that cannot scroll is the chat guessing, and the guess has a failure mode: tool calls fold. A page of
 * forty tool rows can disappear into the "Process · N tools" row already on screen, the window is exactly as
 * short as before, and "page until the screen is full" would walk the entire session in — megabytes nobody
 * asked for, on the links this exists to spare (the loop `ToolProcessCollapseUiTest` guards against).
 *
 * So an automatic page is allowed only while both hold:
 *  - fewer than the cap have been fetched this way for this conversation ([SHORT_WINDOW_AUTO_PAGES], or
 *    [SHORT_WINDOW_AUTO_PAGES_LEGACY] against a daemon whose pages are not byte-bounded), and
 *  - the previous automatic page put something new on screen (more display rows than when it was requested).
 *    When it did not, older history is evidently more of the same fold; the reader can open the fold, which
 *    adds rows and re-arms this, or scroll once there is something to scroll.
 *
 * Asking again for the SAME page (nothing landed since) is always allowed — that is the lost-page retry, not a
 * new page. One instance per conversation on screen; not thread-safe, used from the collector only.
 */
internal class ShortWindowPaging {
    private var pages = 0
    private var askedAtGen = NEVER
    private var rowsAtAsk = 0

    /** May the chat fetch a page on its own now? [pageGen]/[rows] as in [HistoryPageAsk]; [maxPages] is read
     *  per call because the daemon's capability can arrive (or change) while the conversation is on screen. */
    fun mayAsk(pageGen: Int, rows: Int, maxPages: Int = SHORT_WINDOW_AUTO_PAGES): Boolean {
        if (pageGen == askedAtGen) return true // the page asked for has not landed: same request, again
        if (askedAtGen != NEVER && rows <= rowsAtAsk) return false // the last page changed nothing on screen
        if (pages >= maxPages) return false
        pages++
        askedAtGen = pageGen
        rowsAtAsk = rows
        return true
    }

    private companion object { const val NEVER = Int.MIN_VALUE }
}
