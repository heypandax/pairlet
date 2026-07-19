package dev.ccpocket.app.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Issue #167 ②, client half: the built-in vendor table stops being the SOURCE of gateway model ids and
 * becomes a lookup for how to DRAW one.
 *
 * The failure mode being inverted here is the one #168 exposed: `kimi-k2` sat in the table for two
 * months after the vendor retired it, because a dead id only fails on the user's own gateway where we
 * get no signal. With the gateway answering for itself, our table being a generation behind costs a
 * vendor monogram — not a missing or dead model.
 */
class GatewayRowsFromTest {

    /** No authoritative answer (no gateway / it didn't reply / older daemon) → today's behaviour intact. */
    @Test
    fun emptyAuthoritativeKeepsTheSeedTable() {
        assertEquals(
            recommendedGatewayPresets(null).map { it.id },
            gatewayRowsFrom(emptyList(), null).map { it.id },
        )
    }

    /** The gateway's list wins outright, in its own order — we don't re-sort what it told us. */
    @Test
    fun authoritativeListReplacesTheTable() {
        val ids = listOf("zzz-model-9", "deepseek-chat")
        assertEquals(ids, gatewayRowsFrom(ids, "https://api.deepseek.com").map { it.id })
    }

    /** An id our table knows keeps its vendor identity (monogram/tint), so nothing looks downgraded. */
    @Test
    fun knownIdKeepsItsVendorIdentity() {
        val known = GATEWAY_MODEL_PRESETS.first()
        val row = gatewayRowsFrom(listOf(known.id), null).single()
        assertEquals(known.vendor, row.vendor)
        assertEquals(known.monogram, row.monogram)
        assertEquals(known.tint, row.tint)
    }

    /** Case differences must not split a known id into a synthesized stranger. */
    @Test
    fun knownIdMatchesCaseInsensitively() {
        val known = GATEWAY_MODEL_PRESETS.first()
        val row = gatewayRowsFrom(listOf(known.id.uppercase()), null).single()
        assertEquals(known.vendor, row.vendor, "casing must not defeat the lookup")
    }

    /** THE point of the change: an id we've never heard of still gets a usable row. */
    @Test
    fun unknownIdStillGetsARow() {
        val row = gatewayRowsFrom(listOf("brandnew-turbo-3"), null).single()
        assertEquals("brandnew-turbo-3", row.id)
        assertEquals("brandnew", row.vendor, "vendor label is the id's first segment, verbatim — never guessed")
        assertEquals("BR", row.monogram)
    }

    /** A synthesized row's colour must be stable, or the picker would flicker between recompositions. */
    @Test
    fun syntheticTintIsDeterministic() {
        val a = gatewayRowsFrom(listOf("some-model"), null).single()
        val b = gatewayRowsFrom(listOf("some-model"), null).single()
        assertEquals(a.tint, b.tint)
    }

    /** Ids that are all punctuation still render something rather than an empty box. */
    @Test
    fun degenerateIdStillRenders() {
        val row = gatewayRowsFrom(listOf("---"), null).single()
        assertTrue(row.monogram.isNotEmpty(), "monogram must never be blank")
    }
}
