package dev.ccpocket.app.ui

import dev.ccpocket.app.resources.Res
import dev.ccpocket.app.resources.settings_cat_agent
import dev.ccpocket.app.resources.settings_cat_agent_sub
import dev.ccpocket.app.resources.settings_cat_connections
import dev.ccpocket.app.resources.settings_cat_connections_sub
import dev.ccpocket.app.resources.settings_cat_general
import dev.ccpocket.app.resources.settings_cat_general_sub
import dev.ccpocket.app.resources.settings_cat_security
import dev.ccpocket.app.resources.settings_cat_security_sub
import dev.ccpocket.app.resources.settings_cat_support
import dev.ccpocket.app.resources.settings_cat_support_sub
import org.jetbrains.compose.resources.StringResource

/**
 * The Settings information architecture, as pure data (Supporting Surfaces UI 2.0 · Master v1).
 *
 * Compose-free on purpose: regrouping a long scroll into five pages is exactly the kind of change that
 * silently DROPS a control, and a map a test can walk is the only cheap defence against that. [SettingsDest]
 * enumerates every control the single-scroll Settings could reach; [SettingsDest.category] says where it
 * lives now. A destination with no category is one the landing itself owns.
 */
enum class SettingsCategory { GENERAL, AGENT, CONNECTIONS, SECURITY, SUPPORT }

/**
 * Every control destination Settings owns.
 *
 * Not every entry is a page of its own — several are one row or one segmented control — but each names a
 * capability a user could reach before the regroup and must still reach after it.
 */
enum class SettingsDest {
    // ── landing utilities: destinations, not settings, so they stay one tap away ──
    USAGE,
    SCHEDULES,

    // ── general ──
    APPEARANCE,
    TEXT_SIZE,
    NOTIFICATIONS,
    VOICE_WHISPER,

    // ── agent & session defaults ──
    DEFAULT_MODE,
    DEFAULT_MODEL,
    DEFAULT_EFFORT,
    FAST_MODE,
    CONTEXT_WINDOW,
    PER_MODEL_WINDOWS,
    AGENT_FILTER,

    // ── connections & collaboration ──
    COMPUTERS,
    SHARED_FOLDERS,
    JOIN_FOLDER,
    COLLABORATORS,
    REVIEWS,
    BRIDGES,

    // ── security & approvals ──
    APPROVAL_NO_AUTO_DENY,
    FULL_CONTROL_EXPIRY,
    APP_LOCK,

    // ── support & about ──
    HELP,
    MANUAL,
    TROUBLESHOOTING,
    VERSIONS,
    ABOUT,
    EXIT,
}

/** Which page holds this destination — null for the two the landing keeps for itself. */
val SettingsDest.category: SettingsCategory?
    get() = when (this) {
        SettingsDest.USAGE, SettingsDest.SCHEDULES -> null

        SettingsDest.APPEARANCE, SettingsDest.TEXT_SIZE, SettingsDest.NOTIFICATIONS, SettingsDest.VOICE_WHISPER ->
            SettingsCategory.GENERAL

        SettingsDest.DEFAULT_MODE, SettingsDest.DEFAULT_MODEL, SettingsDest.DEFAULT_EFFORT, SettingsDest.FAST_MODE,
        SettingsDest.CONTEXT_WINDOW, SettingsDest.PER_MODEL_WINDOWS, SettingsDest.AGENT_FILTER ->
            SettingsCategory.AGENT

        SettingsDest.COMPUTERS, SettingsDest.SHARED_FOLDERS, SettingsDest.JOIN_FOLDER, SettingsDest.COLLABORATORS,
        SettingsDest.REVIEWS, SettingsDest.BRIDGES ->
            SettingsCategory.CONNECTIONS

        SettingsDest.APPROVAL_NO_AUTO_DENY, SettingsDest.FULL_CONTROL_EXPIRY, SettingsDest.APP_LOCK ->
            SettingsCategory.SECURITY

        SettingsDest.HELP, SettingsDest.MANUAL, SettingsDest.TROUBLESHOOTING, SettingsDest.VERSIONS,
        SettingsDest.ABOUT, SettingsDest.EXIT ->
            SettingsCategory.SUPPORT
    }

/** What [category] holds, in declaration order — the order its page renders them in. */
fun destinationsOf(category: SettingsCategory): List<SettingsDest> =
    SettingsDest.entries.filter { it.category == category }

fun settingsCategoryTitleRes(category: SettingsCategory): StringResource = when (category) {
    SettingsCategory.GENERAL -> Res.string.settings_cat_general
    SettingsCategory.AGENT -> Res.string.settings_cat_agent
    SettingsCategory.CONNECTIONS -> Res.string.settings_cat_connections
    SettingsCategory.SECURITY -> Res.string.settings_cat_security
    SettingsCategory.SUPPORT -> Res.string.settings_cat_support
}

/** A stable descriptive subtitle. Deliberately NOT a live value: a landing summary that had to be recomputed
 *  from five sources would be the first thing to drift out of truth (design brief §6). */
fun settingsCategorySubRes(category: SettingsCategory): StringResource = when (category) {
    SettingsCategory.GENERAL -> Res.string.settings_cat_general_sub
    SettingsCategory.AGENT -> Res.string.settings_cat_agent_sub
    SettingsCategory.CONNECTIONS -> Res.string.settings_cat_connections_sub
    SettingsCategory.SECURITY -> Res.string.settings_cat_security_sub
    SettingsCategory.SUPPORT -> Res.string.settings_cat_support_sub
}
