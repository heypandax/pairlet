package dev.ccpocket.protocol

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** [ImageData] as every build before lean history encoded and decoded it. */
@Serializable
private data class PrePreviewImageData(val mediaType: String, val base64: String)

/** [ClientCaps] as the builds right before lean history declared it. */
@Serializable
private data class PreLeanClientCaps(
    val supportsAgents: List<String> = emptyList(),
    val supportsApprovalV2: Boolean = false,
    val supportsDiagnostics: Boolean = false,
    val supportsProjectPins: Boolean = false,
    val supportsManagedSessions: Boolean = false,
    val supportsToolOutcomes: Boolean = false,
    val maxFrameBytes: Long = 0,
    val supportsVoiceMemo: Boolean = false,
    val supportsSessionObservationV1: Boolean = false,
)

/**
 * Lean history must be additive in both directions: an image without a ref is byte-for-byte the old shape, a
 * ref an old client meets is ignored, the two capability flags default to "not declared", and the new frame
 * types are simply unknown to an old peer (which drops them like any other unknown type).
 */
class ImagePreviewWireCompatTest {

    private fun bodyJson(frame: Frame): String =
        PocketJson.parseToJsonElement(PocketJson.encodeToString(Envelope("1", 0, body = frame))).jsonObject
            .getValue("body").toString()

    private fun body(json: String): Frame = PocketJson.decodeFromString<Envelope>("""{"id":"1","ts":0,"body":$json}""").body

    @Test
    fun an_image_without_a_ref_is_the_old_shape_on_the_wire() {
        val now = PocketJson.encodeToString(ImageData("image/jpeg", "QUJD"))
        val then = PocketJson.encodeToString(PrePreviewImageData("image/jpeg", "QUJD"))
        assertEquals(then, now) // an uplink attachment and an old daemon's image are indistinguishable
        assertNull(PocketJson.decodeFromString<ImageData>(then).ref)
    }

    @Test
    fun a_preview_roundtrips_and_an_old_client_reads_it_as_a_plain_image() {
        val preview = ImageData("image/jpeg", "QUJD", ref = ImageRefs.of(byteArrayOf(1, 2, 3)))
        val json = PocketJson.encodeToString(preview)
        assertEquals(preview, PocketJson.decodeFromString<ImageData>(json))
        val old = PocketJson.decodeFromString<PrePreviewImageData>(json)
        assertEquals("QUJD", old.base64)
    }

    @Test
    fun an_old_declaration_reads_as_not_lean_and_an_old_daemon_ignores_the_new_flags() {
        val old = PocketJson.encodeToString(PreLeanClientCaps(supportsVoiceMemo = true))
        val decoded = body("""{"t":"pocket/client.caps",${old.removePrefix("{")}""") as ClientCaps
        assertFalse(decoded.supportsImagePreviews)
        assertFalse(decoded.supportsShortHistoryWindow)
        assertTrue(decoded.supportsVoiceMemo)

        val declared = ClientCaps(supportsToolOutcomes = true, supportsImagePreviews = true, supportsShortHistoryWindow = true)
        val json = bodyJson(declared)
        assertEquals(declared, body(json))
        val seenByOld = PocketJson.decodeFromString<PreLeanClientCaps>(json.replace("\"t\":\"pocket/client.caps\",", ""))
        assertTrue(seenByOld.supportsToolOutcomes)
    }

    @Test
    fun an_old_daemons_info_reads_as_not_lean_and_the_flag_roundtrips() {
        assertFalse((body("""{"t":"pocket/daemon.info"}""") as DaemonInfo).supportsLeanHistory)
        val json = bodyJson(DaemonInfo(supportsLeanHistory = true))
        assertTrue("\"supportsLeanHistory\":true" in json, json)
        assertTrue((body(json) as DaemonInfo).supportsLeanHistory)
    }

    @Test
    fun fetch_and_content_roundtrip_under_their_wire_names() {
        val ref = ImageRefs.of("picture".encodeToByteArray())
        val fetch = FetchImage("c1", ref, seq = 42, index = 1, requestId = "r1")
        assertTrue("\"t\":\"pocket/image.fetch\"" in bodyJson(fetch))
        assertEquals(fetch, body(bodyJson(fetch)))
        // the minimal request a client may send: a live picture has no cursor yet
        assertEquals(FetchImage("c1", ref), body("""{"t":"pocket/image.fetch","convoId":"c1","ref":"$ref"}"""))

        val ok = ImageContent("c1", ref, image = ImageData("image/jpeg", "QUJD"), requestId = "r1")
        assertTrue("\"t\":\"pocket/image.content\"" in bodyJson(ok))
        assertEquals(ok, body(bodyJson(ok)))
        val missing = ImageContent("c1", ref, error = ImageContent.ERROR_UNAVAILABLE)
        assertEquals(missing, body(bodyJson(missing)))
        assertNull((body(bodyJson(missing)) as ImageContent).image)
    }

    @Test
    fun a_ref_is_stable_content_addressed_and_shape_checked() {
        val a = ImageRefs.of(byteArrayOf(1, 2, 3))
        assertEquals(a, ImageRefs.of(byteArrayOf(1, 2, 3)))
        assertNotEquals(a, ImageRefs.of(byteArrayOf(1, 2, 4)))
        assertEquals(ImageRefs.LENGTH, a.length)
        assertTrue(ImageRefs.isValid(a))
        // SHA-256("abc") = ba7816bf 8f01cfea 414140de …; base64url of the first bytes pins the encoding itself
        assertEquals("ungWv48Bz-pBQUDeXa4iI7", ImageRefs.of("abc".encodeToByteArray()))
        assertFalse(ImageRefs.isValid(""))
        assertFalse(ImageRefs.isValid(a + "x"))
        assertFalse(ImageRefs.isValid("../../etc/passwd/aaaaa"))
    }
}
