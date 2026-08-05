package dev.ccpocket.daemon.review

import dev.ccpocket.protocol.ArtifactKind
import dev.ccpocket.protocol.ArtifactRef

/**
 * The `--artifact` token grammar (REVIEW-REQUEST.md §4.2), parsed DAEMON-SIDE so the CLI, a Skill and
 * any future UI all mean the same thing by the same string:
 *
 * ```text
 * mr:<https url>                          a merge/pull request
 * document:<https url>                    a design doc / spec page
 * commits:<repo>#<base>..<head>           a commit range, when there is no MR yet
 * ```
 *
 * A trailing ` | <title>` on any form attaches a human label:
 * `mr:https://…/42 | relay ACK fence`.
 *
 * Deliberately strict. An unrecognised prefix is an ERROR, never a best-effort guess: guessing would
 * let a typo become a `document` artifact pointing at a merge request, and the reviewer would review
 * the wrong thing without either side noticing.
 */
object ArtifactSyntax {

    /** Parse one token. Left = the human-readable reason it was refused. */
    fun parse(raw: String): Result<ArtifactRef> {
        val (body, title) = splitTitle(raw.trim())
        if (body.isEmpty()) return fail("empty --artifact")
        val ref = when {
            body.startsWith(MR_PREFIX) -> ArtifactRef(
                kind = ArtifactKind.MERGE_REQUEST,
                url = body.removePrefix(MR_PREFIX).trim(),
                title = title,
            )
            body.startsWith(DOC_PREFIX) -> ArtifactRef(
                kind = ArtifactKind.DOCUMENT_URL,
                url = body.removePrefix(DOC_PREFIX).trim(),
                title = title,
            )
            body.startsWith(COMMITS_PREFIX) -> {
                val rest = body.removePrefix(COMMITS_PREFIX).trim()
                val repo = rest.substringBefore('#', "").trim()
                val range = rest.substringAfter('#', "").trim()
                val base = range.substringBefore("..", "").trim()
                val head = range.substringAfter("..", "").trim()
                if (repo.isEmpty() || base.isEmpty() || head.isEmpty()) {
                    return fail("commits artifacts look like commits:<repo>#<base>..<head>")
                }
                ArtifactRef(kind = ArtifactKind.COMMIT_RANGE, repo = repo, base = base, head = head, title = title)
            }
            else -> return fail(
                "unrecognised --artifact \"${body.substringBefore(':')}…\" — use mr:<url>, document:<url> or commits:<repo>#<base>..<head>",
            )
        }
        // one validator for both ends: what the CLI accepts is exactly what the store will keep
        ReviewLimits.artifact(ref)?.let { return fail(it) }
        return Result.success(ref)
    }

    /** Render an artifact back into its `--artifact` token — what `review show` prints so a reader can
     *  copy it straight into a follow-up send. */
    fun render(a: ArtifactRef): String {
        val body = when (a.kind) {
            ArtifactKind.MERGE_REQUEST -> "$MR_PREFIX${a.url}"
            ArtifactKind.DOCUMENT_URL -> "$DOC_PREFIX${a.url}"
            ArtifactKind.COMMIT_RANGE -> "$COMMITS_PREFIX${a.repo}#${a.base}..${a.head}"
            ArtifactKind.UNKNOWN -> "(unsupported artifact)"
        }
        return body + (a.title?.let { " | $it" } ?: "")
    }

    /** `<body> | <title>` — split on the FIRST separator. The body is a URL or a repo#range, neither of
     *  which may contain an unescaped pipe; a human-written title very well may, so everything after the
     *  first separator is the title. */
    private fun splitTitle(raw: String): Pair<String, String?> {
        val i = raw.indexOf(TITLE_SEP)
        if (i < 0) return raw to null
        val title = raw.substring(i + TITLE_SEP.length).trim().takeIf { it.isNotEmpty() }
        return raw.substring(0, i).trim() to title
    }

    private fun fail(message: String): Result<ArtifactRef> = Result.failure(IllegalArgumentException(message))

    private const val MR_PREFIX = "mr:"
    private const val DOC_PREFIX = "document:"
    private const val COMMITS_PREFIX = "commits:"
    private const val TITLE_SEP = " | "
}
