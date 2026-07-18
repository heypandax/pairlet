package dev.ccpocket.daemon.conversation

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * WHO launches clean-room (no MCP, `--setting-sources ""`). The flag's CONTENT is pinned by
 * ClaudeLauncherCleanRoomTest; this pins the DERIVATION — the security-relevant half, because the
 * failure mode of getting it wrong is silent: the owner's personal permissions.allow rules (a bare
 * "Edit" was live on the machine this shipped from) auto-approve tools inside the CLI, no ask ever
 * reaches the daemon, and a stranger in an IM chat edits files without the phone hearing about it.
 */
class ConversationCleanRoomTest {

    @Test
    fun a_bridge_origin_conversation_is_clean_room() {
        // issue #91 follow-up: the bot's sessions must not inherit the owner's "don't ask again" choices
        assertTrue(Conversation.launchesCleanRoom(pathScope = null, origin = "feishu-bot"))
    }

    @Test
    fun a_guest_share_conversation_is_clean_room() {
        assertTrue(Conversation.launchesCleanRoom(pathScope = listOf("/shared/root"), origin = "guest"))
    }

    @Test
    fun owner_and_scheduler_conversations_keep_the_owner_environment() {
        // interactive devices and the scheduler open with neither — the owner's own MCP servers,
        // skills and allow rules are theirs to keep
        assertFalse(Conversation.launchesCleanRoom(pathScope = null, origin = null))
    }
}
