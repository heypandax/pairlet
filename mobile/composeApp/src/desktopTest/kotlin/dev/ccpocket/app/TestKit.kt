package dev.ccpocket.app

import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasText
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.getString

// Shared semantics-query helpers for the desktop-JVM UI tests — one owner for the matcher convention.

@OptIn(ExperimentalTestApi::class)
internal fun ComposeUiTest.present(text: String, substring: Boolean = false): Boolean =
    onAllNodes(hasText(text, substring = substring)).fetchSemanticsNodes().isNotEmpty()

@OptIn(ExperimentalTestApi::class)
internal fun ComposeUiTest.assertPresent(text: String, substring: Boolean = false) =
    assertTrue(present(text, substring), "expected a node with text: \"$text\"")

/** Resolve a string resource the same way the composables do (issue #181): assertions read the SAME
 *  resource the UI renders, so tests stay green in any JVM locale instead of pinning English literals. */
internal fun str(res: StringResource, vararg args: Any): String =
    runBlocking { if (args.isEmpty()) getString(res) else getString(res, *args) }

/**
 * A REAL daemon identity key for invite fixtures, base64url.
 *
 * Generated rather than written by hand because the collaborator-invite decoder now validates what it is
 * given as an actual P-256 public key: the fingerprint it shows is an identity a human is asked to trust,
 * and a placeholder would be fingerprinted into equally convincing words for a key no handshake could
 * ever complete. Generated once per JVM, so the cost is a single keygen per test run.
 */
internal val TEST_DAEMON_PUB: String by lazy {
    dev.ccpocket.app.util.B64Url.encode(dev.ccpocket.protocol.e2e.E2ECrypto.generateKeyPair().publicRaw)
}
