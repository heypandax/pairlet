package dev.ccpocket.daemon.relay

import dev.ccpocket.protocol.PocketJson
import dev.ccpocket.protocol.ShareInvite
import java.util.Base64

/**
 * The owner-side folder-share invite CODEC (issue #115), daemon copy for the `share` CLI (issue #91's
 * `pair --headless` sibling). It produces the EXACT string the app's `ShareInvite.encode()` produces, so
 * a `cc-pocket-daemon share` mint and an in-app "Create invite" are byte-for-byte identical — a guest's
 * `decodeShareInvite` redeems either one.
 *
 * Wire form: `ccpocket://share#<base64url-no-pad(json)>` — the same prefix + the same [PocketJson] over the
 * same [ShareInvite.serializer] the app uses, base64url-no-pad. Parity rests on one documented fact: the
 * app's hand-rolled `B64Url` is (by its own class doc) exactly `Base64.getUrlEncoder().withoutPadding()`, so
 * encoding the identical JSON bytes here yields the identical string. Covered by ShareInviteCodeTest.
 */
const val SHARE_URI_PREFIX = "ccpocket://share#"

/** Encode an [invite] into the scannable / pasteable code — identical to the app's `ShareInvite.encode()`. */
fun encodeShareInvite(invite: ShareInvite): String =
    SHARE_URI_PREFIX + Base64.getUrlEncoder().withoutPadding()
        .encodeToString(PocketJson.encodeToString(ShareInvite.serializer(), invite).encodeToByteArray())
