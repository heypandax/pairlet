package dev.ccpocket.relay.push

import dev.ccpocket.protocol.PocketJson
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Issue #389: turn-end pushes collapse per session (APNs `apns-collapse-id`, FCM `android.notification.tag`);
 *  approvals and handoff offers never collapse, and the approval channel routing is unchanged. */
class PushCollapseTest {
    private val sid = "0f8fad5b-d9cb-469f-a165-70867728950e"

    private fun androidOf(route: NotifyRoute?): JsonObject =
        PocketJson.parseToJsonElement(FcmSender.payload("tok", "t", "b", route))
            .jsonObject["message"]!!.jsonObject["android"]!!.jsonObject

    @Test fun apnsCollapsesTurnEndBySession() {
        assertEquals(sid, ApnsSender.collapseId(NotifyRoute("/w", sid)))
        assertEquals(sid, ApnsSender.collapseId(NotifyRoute("/w", sid, kind = "complete")))
        assertEquals(64, ApnsSender.collapseId(NotifyRoute("/w", "x".repeat(100)))!!.length)
    }

    @Test fun apnsNeverCollapsesApprovalsOrOffers() {
        assertNull(ApnsSender.collapseId(NotifyRoute("/w", sid, kind = "approval")))
        assertNull(ApnsSender.collapseId(NotifyRoute(handoffId = "h1")))
        assertNull(ApnsSender.collapseId(null))
    }

    @Test fun fcmTagsTurnEndBySession() {
        val n = androidOf(NotifyRoute("/w", sid))["notification"]!!.jsonObject
        assertEquals(sid, n["tag"]!!.jsonPrimitive.content)
        assertNull(n["channel_id"])
    }

    @Test fun fcmApprovalKeepsChannelAndTagsUnderItsOwnKey() {
        val n = androidOf(NotifyRoute("/w", sid, kind = "approval"))["notification"]!!.jsonObject
        assertEquals("approvals", n["channel_id"]!!.jsonPrimitive.content)
        assertEquals("approval:$sid", n["tag"]!!.jsonPrimitive.content)
    }

    @Test fun fcmWithoutSessionHasNoAndroidNotification() {
        assertNull(androidOf(NotifyRoute(handoffId = "h1"))["notification"])
        assertNull(androidOf(null)["notification"])
        assertEquals("high", androidOf(null)["priority"]!!.jsonPrimitive.content)
    }
}
