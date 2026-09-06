package dev.ccpocket.app.ui

import dev.ccpocket.protocol.AgentKind
import dev.ccpocket.protocol.ModelsList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Issue #333 — dsh's model picker, after #255's deliberate scope-out was lifted.
 *
 * The old branch returned an empty list on purpose ("dsh picks its own model"), which is now wrong in a
 * way that fails QUIETLY: the daemon really does answer with rows, the picker really does apply them, and
 * a leftover `emptyList()` would just look like a backend that reported nothing.
 */
class DshModelCatalogTest {

    private val daemonRows = listOf("deepseek-v4-flash", "deepseek-v4-pro")

    @Test
    fun dsh_rows_come_from_the_daemon_verbatim() {
        val choices = modelChoicesFor(AgentKind.DSH, daemonRows, gatewayUrl = null)
        assertEquals(daemonRows, choices.map { it.pick })
        // the id the user SEES is the id that goes on the wire — no provider prefix invented here
        assertEquals(daemonRows, choices.map { it.id })
        assertTrue(choices.all { it.ctx.isEmpty() }, "no context pill: dsh windows come off the live wire")
    }

    /** No static fallback: an invented catalogue would offer models the user's providers cannot route. */
    @Test
    fun dsh_has_no_static_fallback_when_the_daemon_has_not_answered() {
        assertTrue(modelChoicesFor(AgentKind.DSH, null, gatewayUrl = null).isEmpty())
        assertTrue(modelChoicesFor(AgentKind.DSH, emptyList(), gatewayUrl = null).isEmpty())
    }

    /**
     * …which is exactly why the picker must SAY which of the three states it is in. Before #333 the notice
     * was ZCode-only, so a dsh picker whose fetch had failed rendered an empty column with no reason.
     */
    @Test
    fun the_picker_notice_covers_dsh_the_same_way_it_covers_zcode() {
        assertEquals(
            ModelCatalogNotice.LOADING,
            modelCatalogNotice(AgentKind.DSH, result = null, hasSelectableModels = false),
        )
        assertEquals(
            ModelCatalogNotice.ERROR,
            modelCatalogNotice(
                AgentKind.DSH,
                ModelsList(agent = AgentKind.DSH, error = "could not start DeepSeek Harness"),
                hasSelectableModels = false,
            ),
        )
        assertEquals(
            ModelCatalogNotice.EMPTY,
            modelCatalogNotice(AgentKind.DSH, ModelsList(agent = AgentKind.DSH), hasSelectableModels = false),
        )
        assertNull(
            modelCatalogNotice(
                AgentKind.DSH,
                ModelsList(agent = AgentKind.DSH, models = daemonRows),
                hasSelectableModels = true,
            ),
            "rows present = nothing to explain",
        )
    }

    /**
     * The arbitrary-id field is hidden for dsh, and structurally so: dsh routes a model as a
     * `{provider, model}` pair joined out of its own catalogue, so an id outside the rows has no provider
     * and the daemon leaves the session on the model it had. A field that accepts anything and does
     * nothing is worse than no field.
     */
    @Test
    fun the_custom_model_id_field_is_offered_only_where_an_arbitrary_id_can_route() {
        assertTrue(!supportsCustomModelId(AgentKind.DSH))
        for (agent in AgentKind.entries - AgentKind.DSH) {
            assertTrue(supportsCustomModelId(agent), "$agent still takes a hand-written id")
        }
    }
}
