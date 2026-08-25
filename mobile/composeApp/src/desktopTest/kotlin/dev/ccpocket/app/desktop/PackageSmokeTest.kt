package dev.ccpocket.app.desktop

import kotlin.test.Test

class PackageSmokeTest {
    @Test
    fun sourceRuntimeContractPassesWithoutNetwork() {
        runPackageSmoke()
    }
}
