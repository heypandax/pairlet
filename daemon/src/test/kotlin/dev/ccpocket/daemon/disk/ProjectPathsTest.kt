package dev.ccpocket.daemon.disk

import kotlin.test.Test
import kotlin.test.assertEquals

class ProjectPathsTest {

    @Test
    fun dirKey_replaces_slash_keeps_dots_and_underscores() {
        assertEquals(
            "-Users-lidapeng-Desktop-Project-app-cc-pocket",
            ProjectPaths.dirKey("/Users/lidapeng/Desktop/Project/app/cc-pocket"),
        )
        assertEquals("-Users-x-my.app_v2-.config", ProjectPaths.dirKey("/Users/x/my.app_v2/.config"))
    }
}
