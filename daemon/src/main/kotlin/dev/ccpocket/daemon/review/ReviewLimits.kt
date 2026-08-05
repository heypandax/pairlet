package dev.ccpocket.daemon.review

import dev.ccpocket.protocol.ArtifactKind
import dev.ccpocket.protocol.ArtifactRef
import dev.ccpocket.protocol.PocketJson
import dev.ccpocket.protocol.ReviewBrief
import dev.ccpocket.protocol.ReviewRequest
import dev.ccpocket.protocol.ReviewResult
import dev.ccpocket.protocol.ReviewStatus
import dev.ccpocket.protocol.ReviewVerdict

/**
 * The bounds every ReviewRequest must satisfy BEFORE it is persisted (REVIEW-REQUEST.md §11.2: a
 * colleague's brief, artifact refs and result are UNTRUSTED INPUT, not instructions).
 *
 * Applied on BOTH ends and for the same reason twice over:
 *  - the SENDER daemon validates at create, so it never mints a row its own peer would have to refuse;
 *  - the RECIPIENT daemon re-validates every row it mirrors, because "the other end already checked"
 *    is exactly the assumption a malicious or newer peer breaks.
 *
 * REJECT, never truncate. Silently shortening a URL, repo identity or SHA would turn a bad reference
 * into a plausible-looking DIFFERENT one — the reviewer would then look at the wrong thing and report
 * on it with confidence. Free text is bounded by the same rule for one simpler reason: a caller that
 * hands us 10 MB of "background" gets told so, rather than discovering later that half the brief is
 * missing.
 */
object ReviewLimits {
    const val MAX_ID = 128
    const val MAX_LABEL = 120
    const val MAX_TITLE = 200

    /** A prose field (request / background / summary / decline reason / finding detail). */
    const val MAX_TEXT = 8_000

    /** One bullet in any of the brief's / result's list fields. */
    const val MAX_LIST_ITEM = 1_000
    const val MAX_LIST = 32

    const val MAX_ARTIFACTS = 8
    const val MAX_URL = 2_000
    const val MAX_REPO = 400

    /** A git ref or SHA. Generous for long branch names, still bounded. */
    const val MAX_REF = 300

    const val MAX_FINDINGS = 100

    /** The whole encoded request/result, as a last line of defence against a pathological shape that
     *  slips through every per-field bound (many fields each just under their cap). */
    const val MAX_ENCODED_BYTES = 128 * 1024

    /** Only these URL schemes are ever recorded. The daemon NEVER opens one (§11.2) — this exists so a
     *  `file:`/`javascript:` reference can't be handed to a reviewer's agent as if it were a web link. */
    private val ALLOWED_SCHEMES = listOf("https://", "http://")

    /** A machine-readable refusal, or null when [value] is acceptable. */
    fun text(value: String?, max: Int, field: String): String? = when {
        value == null -> null
        value.length > max -> "$field is too long (${value.length} > $max)"
        value.any { it.isISOControl() && it != '\n' && it != '\r' && it != '\t' } ->
            "$field contains unsafe control characters"
        else -> null
    }

    /** Fields rendered on one terminal line must not carry line breaks, tabs or ANSI controls. */
    fun singleLine(value: String?, max: Int, field: String): String? {
        text(value, max, field)?.let { return it }
        return if (value?.any(Char::isISOControl) == true) "$field must be a single line" else null
    }

    /**
     * An OPAQUE identifier: `[A-Za-z0-9_-]+`, bounded by [MAX_ID].
     *
     * Strictly narrower than [singleLine] and deliberately so. A request id is minted by the PEER's
     * daemon ([ReviewRegistry.randomRequestId] shape, but a peer's build decides), and this daemon then
     * puts it in a suggested shell command, in prose a reviewer's agent reads, and in a local storage
     * key. `singleLine` happily admits `rr_1; rm -rf ~` — every character of which is printable and on
     * one line. The grammar, checked BEFORE the row is stored or ACKed, is what makes the id boring
     * everywhere it is later used; quoting at each render site is then defence in depth, not the
     * defence.
     *
     * base64url (`-`/`_`) is inside the grammar, so every id this build mints already satisfies it.
     */
    fun opaqueId(value: String, field: String): String? = when {
        value.isEmpty() -> "$field is empty"
        value.length > MAX_ID -> "$field is too long (${value.length} > $MAX_ID)"
        // the value is NOT echoed: this message reaches logs, and the offending text is peer-supplied
        !value.all { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it == '_' || it == '-' } ->
            "$field must be an opaque token (letters, digits, _ or -)"
        else -> null
    }

    fun list(values: List<String>, field: String): String? = when {
        values.size > MAX_LIST -> "$field has too many entries (${values.size} > $MAX_LIST)"
        values.any { it.length > MAX_LIST_ITEM } -> "$field has an entry longer than $MAX_LIST_ITEM characters"
        values.any { entry -> entry.any { it.isISOControl() && it != '\t' } } -> "$field contains an unsafe control character"
        else -> null
    }

    /** Validate one artifact reference. The REQUIRED fields depend on the kind, and an UNKNOWN kind —
     *  a value only a newer peer knows — is refused outright rather than stored unusable (§10). */
    fun artifact(a: ArtifactRef): String? {
        singleLine(a.url, MAX_URL, "artifact url")?.let { return it }
        singleLine(a.repo, MAX_REPO, "artifact repo")?.let { return it }
        singleLine(a.base, MAX_REF, "artifact base")?.let { return it }
        singleLine(a.head, MAX_REF, "artifact head")?.let { return it }
        singleLine(a.title, MAX_TITLE, "artifact title")?.let { return it }
        a.url?.let { u ->
            // the value is deliberately NOT echoed: this message reaches logs, where peer-supplied
            // content must not land (§11.4)
            if (ALLOWED_SCHEMES.none { u.startsWith(it, ignoreCase = true) }) return "artifact url must be http(s)"
        }
        return when (a.kind) {
            ArtifactKind.MERGE_REQUEST, ArtifactKind.DOCUMENT_URL ->
                if (a.url.isNullOrBlank()) "a ${a.kind.name.lowercase()} artifact needs a url" else null
            ArtifactKind.COMMIT_RANGE -> when {
                a.repo.isNullOrBlank() -> "a commit_range artifact needs a repo"
                a.base.isNullOrBlank() || a.head.isNullOrBlank() -> "a commit_range artifact needs base and head"
                else -> null
            }
            // fail closed: this build cannot describe it to a reviewer, so it must not become a row
            ArtifactKind.UNKNOWN -> "unsupported artifact kind — update the daemon on both machines"
        }
    }

    fun brief(b: ReviewBrief): String? {
        if (b.request.isBlank()) return "a review request needs a request line — what should they do?"
        text(b.request, MAX_TEXT, "request")?.let { return it }
        text(b.background, MAX_TEXT, "background")?.let { return it }
        list(b.completedWork, "completedWork")?.let { return it }
        list(b.focusAreas, "focusAreas")?.let { return it }
        list(b.knownRisks, "knownRisks")?.let { return it }
        list(b.verification, "verification")?.let { return it }
        list(b.constraints, "constraints")?.let { return it }
        list(b.definitionOfDone, "definitionOfDone")?.let { return it }
        return null
    }

    fun result(r: ReviewResult): String? {
        if (r.verdict == ReviewVerdict.UNKNOWN) return "a review result needs a known verdict"
        if (r.summary.isBlank()) return "a review result needs a summary"
        text(r.summary, MAX_TEXT, "summary")?.let { return it }
        if (r.findings.size > MAX_FINDINGS) return "too many findings (${r.findings.size} > $MAX_FINDINGS)"
        for (f in r.findings) {
            if (f.title.isBlank()) return "a finding needs a title"
            singleLine(f.title, MAX_TITLE, "finding title")?.let { return it }
            singleLine(f.severity, MAX_LABEL, "finding severity")?.let { return it }
            text(f.detail, MAX_TEXT, "finding detail")?.let { return it }
            singleLine(f.file, MAX_URL, "finding file")?.let { return it }
            f.artifactIndex?.let { if (it < 0) return "finding artifactIndex must not be negative" }
            f.line?.let { if (it < 1) return "finding line must be positive" }
        }
        list(r.verification, "result verification")?.let { return it }
        list(r.openQuestions, "openQuestions")?.let { return it }
        list(r.recommendedNextSteps, "recommendedNextSteps")?.let { return it }
        val encoded = PocketJson.encodeToString(ReviewResult.serializer(), r).toByteArray(Charsets.UTF_8).size
        if (encoded > MAX_ENCODED_BYTES) return "encoded result is too large ($encoded > $MAX_ENCODED_BYTES bytes)"
        return null
    }

    /**
     * Whole-row validation — what the RECIPIENT runs on everything the peer sends before it touches
     * disk, and what the sender runs on its own freshly built row. Deliberately checks the identifier
     * fields too: they key the local mirror, so an unbounded one is a storage-key problem, not a
     * cosmetic one.
     */
    fun request(r: ReviewRequest): String? {
        if (r.id.isBlank()) return "the request has no id"
        if (r.recipientDeviceId.isBlank()) return "the request has no recipientDeviceId"
        if (r.revision < 1) return "the request revision must be positive"
        // the id is a PEER-minted handle that ends up in a shell command, in a reviewer's prompt and in
        // a storage key: it must be opaque, not merely printable (see [opaqueId])
        opaqueId(r.id, "id")?.let { return it }
        singleLine(r.senderDeviceId, MAX_ID, "senderDeviceId")?.let { return it }
        singleLine(r.recipientDeviceId, MAX_ID, "recipientDeviceId")?.let { return it }
        singleLine(r.senderLabel, MAX_LABEL, "senderLabel")?.let { return it }
        singleLine(r.recipientLabel, MAX_LABEL, "recipientLabel")?.let { return it }
        singleLine(r.title, MAX_TITLE, "title")?.let { return it }
        text(r.declineReason, MAX_TEXT, "declineReason")?.let { return it }
        if (r.artifacts.isEmpty()) return "the request has no artifacts"
        if (r.artifacts.size > MAX_ARTIFACTS) return "too many artifacts (${r.artifacts.size} > $MAX_ARTIFACTS)"
        r.artifacts.forEach { a -> artifact(a)?.let { return it } }
        brief(r.brief)?.let { return it }
        r.result?.let { res -> result(res)?.let { return it } }
        val returned = r.result
        val resultState = r.status == ReviewStatus.RESPONDED || r.status == ReviewStatus.CLOSED
        if (resultState && returned == null) return "a ${r.status.name.lowercase()} request needs a result"
        if (!resultState && returned != null) return "a ${r.status.name.lowercase()} request must not carry a result"
        if (resultState && (returned!!.respondedByDeviceId.isBlank() || returned.respondedAt <= 0)) {
            return "a returned result is missing its daemon-stamped responder"
        }
        if (r.status != ReviewStatus.DECLINED && r.declineReason != null) {
            return "only a declined request may carry a decline reason"
        }
        r.result?.findings?.firstOrNull { finding -> finding.artifactIndex?.let { it >= r.artifacts.size } == true }?.let {
            return "finding artifactIndex ${it.artifactIndex} is outside the artifact list"
        }
        if (r.status == ReviewStatus.UNKNOWN) return "unknown request status — update the daemon on both machines"
        val encoded = PocketJson.encodeToString(ReviewRequest.serializer(), r).toByteArray(Charsets.UTF_8).size
        if (encoded > MAX_ENCODED_BYTES) return "encoded request is too large ($encoded > $MAX_ENCODED_BYTES bytes)"
        return null
    }
}
