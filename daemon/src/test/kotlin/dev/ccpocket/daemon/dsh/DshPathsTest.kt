package dev.ccpocket.daemon.dsh

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Pins [DshPaths.projectKey] against the upstream `projectKey()` in `dsh-session-persistence-jsonl`
 * (rc.6). Every case below is a direct read of that function's behaviour — if one of these ever fails
 * after a dsh upgrade, the session store layout moved and the scanner's directory-first optimization is
 * looking in the wrong place.
 */
class DshPathsTest {

    @Test
    fun ordinary_posix_path_normalizes_to_the_wrapped_dashed_form() {
        assertEquals("--Users-lidapeng-Desktop-Project--", DshPaths.projectKey("/Users/lidapeng/Desktop/Project"))
    }

    @Test
    fun separator_runs_collapse_and_leading_dashes_are_stripped() {
        // three separators in a row are ONE dash, and the leading one is dropped entirely
        assertEquals("--a-b--", DshPaths.projectKey("///a///b"))
    }

    @Test
    fun trailing_separator_keeps_its_dash() {
        // asymmetric on purpose upstream: leading dashes are stripped, trailing ones are not
        assertEquals("--a-b---", DshPaths.projectKey("/a/b/"))
    }

    /**
     * THE COLLISION. `/a/b` and `/a-b` are different directories that produce the SAME key, which is why
     * the scanner must re-filter on the header's verbatim `cwd` and may never invert the key.
     */
    @Test
    fun distinct_cwds_can_share_one_project_key() {
        assertEquals(DshPaths.projectKey("/a/b"), DshPaths.projectKey("/a-b"))
    }

    @Test
    fun windows_drive_colon_and_backslashes_are_separators_too() {
        assertEquals("--C-work-repo--", DshPaths.projectKey("C:\\work\\repo"))
    }

    @Test
    fun tilde_is_escaped_rather_than_passed_through() {
        // `~` is excluded from the literal set precisely so it can't be confused with an escape marker
        assertEquals("--~007Efoo--", DshPaths.projectKey("~foo"))
    }

    @Test
    fun non_ascii_becomes_uppercase_four_digit_utf16_escapes() {
        // 项目 = U+9879 U+76EE
        assertEquals("--~9879~76EE--", DshPaths.projectKey("项目"))
        assertEquals("--a~0020b--", DshPaths.projectKey("a b")) // space
    }

    @Test
    fun a_path_of_only_separators_falls_back_to_root() {
        assertEquals("--root--", DshPaths.projectKey("/"))
    }

    @Test
    fun the_inner_key_is_truncated_to_251_chars() {
        val key = DshPaths.projectKey("/" + "x".repeat(400))
        assertEquals(251, key.removePrefix("--").removeSuffix("--").length)
        assertTrue(key.startsWith("--") && key.endsWith("--"), key)
    }

    // ---- session id encoding (a DIFFERENT, injective function) ----

    @Test
    fun uuid_shaped_session_ids_encode_to_themselves() {
        val id = "session-2f8c1e94-0b6d-4a51-9c33-7ad0e1b26f45"
        assertEquals(id, DshPaths.encodeSessionId(id))
        assertEquals(id, DshPaths.decodeName(DshPaths.encodeSessionId(id)))
    }

    @Test
    fun session_id_encoding_escapes_separators_instead_of_collapsing_them() {
        // unlike projectKey, `/` does NOT become `-` here — the encoding has to stay reversible
        assertEquals("a~002Fb", DshPaths.encodeSessionId("a/b"))
        assertNotEquals(DshPaths.encodeSessionId("a/b"), DshPaths.encodeSessionId("a-b"))
    }

    @Test
    fun dot_segments_get_their_reserved_encodings() {
        assertEquals("~002E", DshPaths.encodeSessionId("."))
        assertEquals("~002E~002E", DshPaths.encodeSessionId(".."))
    }

    @Test
    fun decode_round_trips_escaped_names_and_leaves_malformed_ones_verbatim() {
        assertEquals("项目", DshPaths.decodeName(DshPaths.encodeSessionId("项目")))
        // a stray `~` that isn't a valid 4-hex escape must not throw or eat characters
        assertEquals("~zz", DshPaths.decodeName("~zz"))
    }

    @Test
    fun sidecars_are_recognized_and_locks_are_never_payloads() {
        assertTrue(DshPaths.isSidecar("session.jsonl.tmp"))
        assertTrue(DshPaths.isSidecar(".dsh-mkdir-1234"))
        assertTrue(DshPaths.isSidecar("session.lock"))
        assertTrue(!DshPaths.isSidecar("session.jsonl.zstd"))
    }
}
