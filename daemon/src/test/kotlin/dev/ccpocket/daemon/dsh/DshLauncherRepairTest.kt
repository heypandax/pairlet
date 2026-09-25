package dev.ccpocket.daemon.dsh

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Locks [DshLauncher.looksLikeIncompleteInstall] against the ACTUAL stderr an incomplete dsh install emits,
 * captured by reproducing the crash on a real Windows box (2026-09): a `npm i -g @deepseek-ai/dsh` that
 * dropped the nested `@earendil-works/pi-ai` package makes Node abort while dsh's cordis loader imports the
 * `llm-pi-ai` plugin. The user reported the same chain. This must be recognized (→ one-tap repair) and must
 * NOT be confused with a too-old dsh (→ upgrade) or an ordinary crash.
 */
class DshLauncherRepairTest {
    @Test
    fun recognizes_the_reported_incomplete_install_crash() {
        // as seen in the user's screenshot: the cordis loader failure with the underlying module-not-found
        val reported = """
            error: agent process ended (exit 1) —  [cause]:
            Error: failed to apply loader entry include (cordis:include): failed to import loader entry
            llm-pi-ai (@deepseek-ai/dsh-llm-pi-ai): Cannot find package
            'C:\Users\zhutianyu\AppData\Roaming\npm\node_modules\@deepseek-ai\dsh\node_modules\@earendil-works\pi-ai\dist\index.js'
            imported from ...
        """.trimIndent()
        assertTrue(DshLauncher.looksLikeIncompleteInstall(reported))

        // as reproduced when the whole package dir is gone: Node's own resolver error code
        val reproduced = "Error [ERR_MODULE_NOT_FOUND]: Cannot find package '@earendil-works/pi-ai' " +
            "imported from C:\\...\\dsh-llm-pi-ai\\lib\\index.js"
        assertTrue(DshLauncher.looksLikeIncompleteInstall(reproduced))
    }

    @Test
    fun does_not_mistake_a_too_old_dsh_or_ordinary_crash() {
        assertFalse(DshLauncher.looksLikeIncompleteInstall(null))
        assertFalse(DshLauncher.looksLikeIncompleteInstall(""))
        // too-old dsh: no acp app in the profile — this is an upgrade, not a reinstall
        assertFalse(DshLauncher.looksLikeIncompleteInstall("error: unknown profile 'acp': no app registered"))
        // an ordinary runtime error must not offer a reinstall
        assertFalse(DshLauncher.looksLikeIncompleteInstall("TypeError: cannot read properties of undefined"))
    }
}
