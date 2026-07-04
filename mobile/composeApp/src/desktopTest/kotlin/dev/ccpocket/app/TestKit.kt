package dev.ccpocket.app

import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasText
import kotlin.test.assertTrue

// Shared semantics-query helpers for the desktop-JVM UI tests — one owner for the matcher convention.

@OptIn(ExperimentalTestApi::class)
internal fun ComposeUiTest.present(text: String, substring: Boolean = false): Boolean =
    onAllNodes(hasText(text, substring = substring)).fetchSemanticsNodes().isNotEmpty()

@OptIn(ExperimentalTestApi::class)
internal fun ComposeUiTest.assertPresent(text: String, substring: Boolean = false) =
    assertTrue(present(text, substring), "expected a node with text: \"$text\"")
