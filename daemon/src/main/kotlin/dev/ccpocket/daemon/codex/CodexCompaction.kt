package dev.ccpocket.daemon.codex

// Shared by live completion and rollout replay so reconnect can merge the same card. Codex remote
// compaction may persist an empty message plus encrypted replacement history, not a readable summary.
// Show a completion record; never expose replacement_history as if it were a conversation message.
internal const val CODEX_COMPACTION_NOTICE = "Context compacted."
