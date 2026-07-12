package dev.ccpocket.daemon.relay

import dev.ccpocket.protocol.AccessTier
import dev.ccpocket.protocol.PocketJson
import dev.ccpocket.protocol.ShareInvite
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The daemon `share` CLI must emit a folder-share code byte-identical to the app's `ShareInvite.encode()`,
 * or a guest's `decodeShareInvite` can't redeem it. These pin the format contract from the daemon side
 * (the app side is covered by ShareInviteCodecTest): `ccpocket://share#<base64url-no-pad(json)>`, where the
 * base64url matches the app's `B64Url` (documented there as `Base64.getUrlEncoder().withoutPadding()`).
 */
class ShareInviteCodeTest {

    private val invite = ShareInvite(
        relay = "wss://pocket.ark-nexus.cc",
        accountId = "acct-1234",
        daemonPub = "ZGFlbW9uLXB1Yg", // any base64url-ish string
        ticket = "tkt-abc-def",
        folderName = "my-repo",
        tier = AccessTier.COLLABORATE,
        expiresAt = 1_800_000_000_000,
        ttlSec = 120,
        ownerLabel = "Pandas-MacBook-Pro",
    )

    @Test
    fun code_has_the_share_uri_prefix_and_a_canonical_url_safe_no_pad_body() {
        val code = encodeShareInvite(invite)
        assertTrue(code.startsWith("ccpocket://share#"), "must carry the share URI scheme the app decodes")
        val body = code.removePrefix("ccpocket://share#")
        // url-safe alphabet, NO padding — no '+', '/', or '=' that a plain base64 would introduce
        assertTrue(body.matches(Regex("^[A-Za-z0-9_-]+$")), "body must be base64url with no padding, was: $body")
    }

    @Test
    fun body_base64url_decodes_to_exactly_the_apps_pocketjson_of_the_invite() {
        // The app encodes `PocketJson.encodeToString(ShareInvite.serializer(), invite)` then base64url-no-pad.
        // Same PocketJson + same serializer (shared :protocol) + canonical base64url ⇒ identical string.
        val body = encodeShareInvite(invite).removePrefix("ccpocket://share#")
        val json = Base64.getUrlDecoder().decode(body).decodeToString()
        assertEquals(PocketJson.encodeToString(ShareInvite.serializer(), invite), json)
    }

    @Test
    fun guest_decode_contract_round_trips_back_to_the_same_invite() {
        // Mirrors the guest's `decodeShareInvite`: strip prefix → base64url-decode → PocketJson.decode.
        val body = encodeShareInvite(invite).removePrefix("ccpocket://share#")
        val decoded = PocketJson.decodeFromString(
            ShareInvite.serializer(),
            Base64.getUrlDecoder().decode(body).decodeToString(),
        )
        assertEquals(invite, decoded)
    }
}
