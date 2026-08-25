package dev.ccpocket.app.ui.session

import dev.ccpocket.protocol.SessionSummary

/**
 * Session-list lineage math for rewind / fork (issue #282, docs/design/REWIND-FORK.md §2 铁律 3).
 *
 * Pure and Compose-free so the load-bearing rule can be asserted directly: **a rewind must not grow the
 * default list.** It cannot, structurally, because a rewind produces exactly one new row (the branch) and
 * removes exactly one from view (the original it replaced), and both facts come from the SAME field —
 * the branch's [SessionSummary.rewindOf] pointer. There is no separate "collapsed" flag that could get
 * out of step with it, and no client-side bookkeeping that a reinstall or a second device would lose.
 *
 * A fork is the opposite by design: it names its parent through [SessionSummary.forkedFrom], which folds
 * nothing away — both sessions stay peers, and the list is meant to grow.
 */

/** The list split into what the default view shows and what the "rewound" group holds. */
data class RewindSplit(val visible: List<SessionSummary>, val rewound: List<SessionSummary>)

/**
 * Fold every session that a peer in [sessions] declares it rewound.
 *
 * Order is preserved on both sides (the daemon already sorts by recency). Scoped to the list it is given:
 * an original whose successor is filtered out of view — a different agent filter, another project — is
 * NOT folded, because from that view's point of view nothing replaced it and hiding it would just lose a
 * session. Self-reference is ignored defensively; a ledger can only produce it through corruption, and
 * the honest answer to "this session replaced itself" is to keep showing it.
 */
fun splitRewound(sessions: List<SessionSummary>): RewindSplit {
    val superseded = sessions.mapNotNullTo(HashSet()) { s -> s.rewindOf?.takeIf { it != s.sessionId } }
    if (superseded.isEmpty()) return RewindSplit(sessions, emptyList())
    val visible = ArrayList<SessionSummary>(sessions.size)
    val rewound = ArrayList<SessionSummary>()
    for (s in sessions) (if (s.sessionId in superseded) rewound else visible) += s
    return RewindSplit(visible, rewound)
}

/** The title of the session [s] was forked from, for a fork child's list caption — null when [s] is not a
 *  fork, or when its parent is not in this list (deleted, or another project). */
fun forkParentTitle(s: SessionSummary, all: List<SessionSummary>): String? =
    s.forkedFrom?.let { parent -> all.firstOrNull { it.sessionId == parent }?.title }

/** The title of the session that REPLACED [s], for a rewound original's caption in the collapsed group.
 *  Null when nothing in this list claims it — which is also exactly when [splitRewound] leaves it visible. */
fun rewoundSuccessorTitle(s: SessionSummary, all: List<SessionSummary>): String? =
    all.firstOrNull { it.rewindOf == s.sessionId && it.sessionId != s.sessionId }?.title
