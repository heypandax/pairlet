package dev.ccpocket.daemon.disk

import kotlin.test.Test
import kotlin.test.assertEquals

class ProjectPathsTest {

    @Test
    fun dirKey_keeps_alnum_and_hyphens() {
        // hyphens in path segments are preserved (cc-pocket stays cc-pocket)
        assertEquals(
            "-Users-lidapeng-Desktop-Project-app-cc-pocket",
            ProjectPaths.dirKey("/Users/lidapeng/Desktop/Project/app/cc-pocket"),
        )
    }

    @Test
    fun dirKey_maps_underscore_and_dot_to_hyphen() {
        // regression: cwds with '_' or '.' must match claude's on-disk folder name. Replacing only
        // '/' produced "…ht_binary_ios_makefork2" while claude wrote "…ht-binary-ios-makefork2",
        // so session fetch/open silently returned empty / file-not-found.
        assertEquals(
            "-Users-make-Desktop-Work-Develop2-ht-binary-ios-makefork2",
            ProjectPaths.dirKey("/Users/make/Desktop/Work/Develop2/ht_binary_ios_makefork2"),
        )
        assertEquals("-Users-x-my-app-v2--config", ProjectPaths.dirKey("/Users/x/my.app_v2/.config"))
        assertEquals("-private-tmp-skdbg-IYBb", ProjectPaths.dirKey("/private/tmp/skdbg.IYBb"))
        // runs are not collapsed: a hyphen immediately followed by '_' yields '--'
        assertEquals("-a-r0--2yuiwum-work", ProjectPaths.dirKey("/a/r0-_2yuiwum/work"))
    }
}
