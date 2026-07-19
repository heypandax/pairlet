package dev.ccpocket.app.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import dev.ccpocket.app.data.PocketRepository
import dev.ccpocket.app.pairing.PairedDaemon
import dev.ccpocket.app.present
import dev.ccpocket.app.resources.Res
import dev.ccpocket.app.resources.ctx_edit_for_model
import dev.ccpocket.app.resources.ctx_own_value
import dev.ccpocket.app.resources.ctx_save_for_model
import dev.ccpocket.app.resources.ctx_set_for_model
import dev.ccpocket.app.resources.ctx_using_catchall
import dev.ccpocket.app.resources.per_model_delete
import dev.ccpocket.app.resources.per_model_empty_title
import dev.ccpocket.app.resources.per_model_overrides
import dev.ccpocket.app.secure.SecureStore
import dev.ccpocket.app.theme.PocketTheme
import dev.ccpocket.protocol.DEFAULT_CONTEXT_WINDOW
import dev.ccpocket.protocol.SessionLive
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.getString
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Issue #171: the per-model table needs a WRITE ENTRY POINT.
 *
 * #169 rebuilt the storage (per-model map, four-tier resolution, catch-all demotion) with tests — but shipped
 * with `setContextWindowOverrideFor()` having ZERO production callers. The table could never be non-empty, so
 * resolution always fell through to the catch-all and user-visible behaviour was identical to before the
 * change. The defect was not a wrong value; it was an unreachable feature.
 *
 * These pin the reachability itself. [saveWritesAPerModelEntry] is the one that would have caught #169's gap:
 * it drives the real UI and asserts the table actually gained a row. Detach the write surface again and it
 * fails, instead of the feature quietly reverting to a no-op.
 *
 * Every text assertion resolves through [s] rather than hard-coding English — the suite runs under whatever
 * locale the JVM has (zh here), and the point of these cases is the wiring, not the wording.
 */
@OptIn(ExperimentalTestApi::class)
class PerModelWindowUiTest {

    private fun s(res: StringResource, vararg args: Any): String =
        runBlocking { if (args.isEmpty()) getString(res) else getString(res, *args) }

    /** Same hazard as ContextWindowOverrideTest: the desktop SecureStore is a real file shared by the JVM,
     *  and both override keys are read at repo CONSTRUCTION. Clear up front so cases stay order-independent. */
    @BeforeTest
    fun clearPersistedOverrides() {
        SecureStore.remove(PocketRepository.K_CONTEXT_WINDOW_OVERRIDE)
        SecureStore.remove(PocketRepository.K_CONTEXT_WINDOW_OVERRIDES)
    }

    private fun repo(model: String? = "deepseek-chat", declared: Long? = DEFAULT_CONTEXT_WINDOW) =
        PocketRepository(CoroutineScope(Dispatchers.Unconfined)).apply {
            paired.value = PairedDaemon(
                relay = "wss://test", accountId = "acct-test", daemonPub = "pk", deviceId = "dev", credential = "cred",
            )
            convoId.value = "c1"
            receiveForTest(SessionLive("c1", "/w", "sid-1", executing = false, model = model, contextWindow = declared))
        }

    // ── Session Info · the write surface ────────────────────────────────────

    /** THE regression guard for #169's gap: drive the real row and assert the table gained an entry. */
    @Test
    fun saveWritesAPerModelEntry() = runComposeUiTest {
        val r = repo()
        assertNull(r.contextWindowOverrides["deepseek-chat"], "precondition: no entry yet")
        setContent { PocketTheme { Box(Modifier.width(380.dp)) { PerModelWindowRow(r) } } }
        waitForIdle()

        onNode(hasText(s(Res.string.ctx_set_for_model))).performClick()   // inherit state → opens the editor
        waitForIdle()
        onNode(hasSetTextAction()).performTextInput("262144")
        waitForIdle()
        onNode(hasText(s(Res.string.ctx_save_for_model))).performClick()
        waitForIdle()

        assertEquals(262_144L, r.contextWindowOverrides["deepseek-chat"], "save must write the per-model row")
    }

    /** A model with no entry of its own says so, and shows the catch-all it is currently borrowing. */
    @Test
    fun inheritingModelNamesTheCatchAll() = runComposeUiTest {
        val r = repo().apply { setContextWindowOverride(200_000) }
        setContent { PocketTheme { Box(Modifier.width(380.dp)) { PerModelWindowRow(r) } } }
        waitForIdle()
        assertTrue(present(s(Res.string.ctx_set_for_model)), "inherit state offers to SET, not edit")
        assertTrue(present(s(Res.string.ctx_using_catchall, "200,000")), "must show the catch-all it borrows")
    }

    /** Once it owns a value the affordance flips to EDIT and prints that value, not the catch-all. */
    @Test
    fun ownValueFlipsToEdit() = runComposeUiTest {
        val r = repo().apply {
            setContextWindowOverride(200_000)
            setContextWindowOverrideFor("deepseek-chat", 262_144)
        }
        setContent { PocketTheme { Box(Modifier.width(380.dp)) { PerModelWindowRow(r) } } }
        waitForIdle()
        assertTrue(present(s(Res.string.ctx_edit_for_model)), "own state offers to EDIT")
        assertTrue(present(s(Res.string.ctx_own_value, "262,144")), "must print its own value")
        assertTrue(!present("200,000", substring = true), "must not print the catch-all it overrides")
    }

    /** Clearing the field deletes the entry rather than pinning a zero denominator. */
    @Test
    fun blankSaveClearsTheEntry() = runComposeUiTest {
        val r = repo().apply { setContextWindowOverrideFor("deepseek-chat", 262_144) }
        setContent { PocketTheme { Box(Modifier.width(380.dp)) { PerModelWindowRow(r) } } }
        waitForIdle()
        onNode(hasText(s(Res.string.ctx_edit_for_model))).performClick()
        waitForIdle()
        onNode(hasSetTextAction()).performTextClearance()   // arrives pre-filled with 262144
        waitForIdle()
        onNode(hasText(s(Res.string.ctx_save_for_model))).performClick()
        waitForIdle()
        assertNull(r.contextWindowOverrides["deepseek-chat"], "blank must clear, not pin 0")
    }

    /** No model id in scope → nothing to key an entry by, so the row must not offer to write one. */
    @Test
    fun hiddenWithoutAModel() = runComposeUiTest {
        val r = repo(model = null)
        setContent { PocketTheme { Box(Modifier.width(380.dp)) { PerModelWindowRow(r) } } }
        waitForIdle()
        assertTrue(!present(s(Res.string.ctx_set_for_model)), "no model → no write surface")
    }

    // ── Settings · the audit list ───────────────────────────────────────────

    /** Empty is the common case and must read as "nothing here yet", not as a broken list. */
    @Test
    fun emptyTableExplainsItself() = runComposeUiTest {
        val r = repo()
        setContent { PocketTheme { Box(Modifier.width(380.dp)) { PerModelWindows(r) } } }
        waitForIdle()
        assertTrue(present(s(Res.string.per_model_empty_title)), "empty state states where entries come from")
    }

    /** Entries are listed with their exact numbers, and delete actually removes them. */
    @Test
    fun deleteRemovesTheEntry() = runComposeUiTest {
        val r = repo().apply {
            setContextWindowOverrideFor("deepseek-chat", 65_536)
            setContextWindowOverrideFor("qwen-2.5-max", 131_072)
        }
        setContent { PocketTheme { Box(Modifier.width(380.dp)) { PerModelWindows(r) } } }
        waitForIdle()
        assertTrue(present("65,536"), "exact typed number, not an abbreviation")
        assertTrue(present("131,072"))

        onAllNodes(hasContentDescription(s(Res.string.per_model_delete))).onFirst().performClick()
        waitForIdle()
        assertEquals(1, r.contextWindowOverrides.size, "delete must remove exactly one row")
    }

    /** The live model's row is flagged: its override is not hypothetical, it is in force right now. */
    @Test
    fun liveModelRowIsFlagged() = runComposeUiTest {
        val r = repo().apply {
            setContextWindowOverrideFor("deepseek-chat", 65_536)   // the running model
            setContextWindowOverrideFor("qwen-2.5-max", 131_072)   // not running
        }
        setContent { PocketTheme { Box(Modifier.width(380.dp)) { PerModelWindows(r) } } }
        waitForIdle()
        assertEquals(
            1, onAllNodes(hasText(s(Res.string.per_model_overrides))).fetchSemanticsNodes().size,
            "only the model actually running carries the flag",
        )
    }
}
