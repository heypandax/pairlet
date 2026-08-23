package dev.ccpocket.protocol

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * [gitStderrHighlight] — which line of a failed git command the phone gets to see (issue #280 真机反馈 5).
 *
 * The bug this pins down: taking `stderr.lineSequence().first()` shows `To https://github.com/…`, the one
 * line of a rejected push that says nothing about the rejection. Every fixture below is real `LC_ALL=C`
 * git output (git ≥ 2.39), so a git wording change is supposed to make these go red.
 */
class GitStderrHighlightTest {

    /** A push refused because the remote moved on — the verdict is git's `! [rejected]` line, line TWO. */
    @Test
    fun rejectedPushPicksTheVerdictLineNotTheToBanner() {
        val stderr = """
            To https://github.com/heypandax/cc-pocket.git
             ! [rejected]        main -> main (fetch first)
            error: failed to push some refs to 'https://github.com/heypandax/cc-pocket.git'
            hint: Updates were rejected because the remote contains work that you do
            hint: not have locally. This is usually caused by another repository pushing
        """.trimIndent()
        assertEquals("! [rejected]        main -> main (fetch first)", gitStderrHighlight(stderr))
    }

    /** Same shape, the other reason: a force-less push over a rewritten history. */
    @Test
    fun nonFastForwardPushPicksTheRejectedLine() {
        val stderr = """
            To git@github.com:heypandax/cc-pocket.git
             ! [rejected]        feature/280-git-panel -> feature/280-git-panel (non-fast-forward)
            error: failed to push some refs to 'git@github.com:heypandax/cc-pocket.git'
        """.trimIndent()
        assertEquals(
            "! [rejected]        feature/280-git-panel -> feature/280-git-panel (non-fast-forward)",
            gitStderrHighlight(stderr),
        )
    }

    /** `pull --ff-only` against a diverged upstream never prints a `!` line — only `fatal:`. */
    @Test
    fun refusedFastForwardPullPicksTheFatalLine() {
        val stderr = """
            hint: Diverging branches can't be fast-forwarded, you need to either:
            hint:
            hint: 	git merge --no-ff
            fatal: Not possible to fast-forward, aborting.
        """.trimIndent()
        assertEquals("fatal: Not possible to fast-forward, aborting.", gitStderrHighlight(stderr))
    }

    /** Nothing but the banner: better to say nothing and let our own sentence stand alone. */
    @Test
    fun aStderrOfNothingButTheToBannerYieldsNoLine() {
        assertEquals("", gitStderrHighlight("To https://github.com/heypandax/cc-pocket.git\n\n"))
        assertEquals("", gitStderrHighlight(""))
    }

    /** A hook denial speaks through `! [remote rejected]`, which is still the verdict line. */
    @Test
    fun remoteRejectedByAHookIsStillRankZero() {
        val stderr = """
            To https://code.hellotalk.cloud/app/ios.git
             ! [remote rejected] main -> main (pre-receive hook declined)
            error: failed to push some refs
        """.trimIndent()
        assertEquals("! [remote rejected] main -> main (pre-receive hook declined)", gitStderrHighlight(stderr))
    }

    /** No marker anywhere: the first line that is not the banner, verbatim. */
    @Test
    fun withoutAnyMarkerTheFirstNonBannerLineWins() {
        val stderr = "To https://example.com/repo.git\nEverything up-to-date, oddly\n"
        assertEquals("Everything up-to-date, oddly", gitStderrHighlight(stderr))
    }

    /** `fatal:` outranks `error:` even when `error:` came first — the fatal line is the cause. */
    @Test
    fun fatalOutranksErrorRegardlessOfOrder() {
        val stderr = "error: could not lock config file\nfatal: unable to access 'origin': timed out\n"
        assertEquals("fatal: unable to access 'origin': timed out", gitStderrHighlight(stderr))
    }
}
