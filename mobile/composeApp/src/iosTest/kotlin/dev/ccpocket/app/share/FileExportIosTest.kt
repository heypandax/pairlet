package dev.ccpocket.app.share

import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSFileManager
import platform.Foundation.NSSelectorFromString
import platform.objc.class_conformsToProtocol
import platform.objc.objc_getProtocol
import platform.objc.object_getClass
import kotlin.test.*

@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
class FileExportIosTest {
    private val pdf = """
        %PDF-1.4
        1 0 obj<</Type/Catalog/Pages 2 0 R>>endobj
        2 0 obj<</Type/Pages/Kids[3 0 R]/Count 1>>endobj
        3 0 obj<</Type/Page/Parent 2 0 R/MediaBox[0 0 72 72]>>endobj
        trailer<</Root 1 0 R>>
        %%EOF
    """.trimIndent().encodeToByteArray()

    // The Preview tap used to cast NSURL to QLPreviewItemProtocol: NSURL does not conform at runtime, so
    // Kotlin/Native threw TypeCastException and the app aborted. The item must carry real conformance.
    // (canPreviewItem itself answers false for every type in a headless test process — not asserted here.)
    @Test fun pdfBecomesAConformingQuickLookItem() {
        val item = assertNotNull(quickLookItem("report.pdf", pdf))
        val path = assertNotNull(item.previewItemURL()?.path)
        try {
            assertEquals("report.pdf", path.substringAfterLast('/'))
            assertTrue(NSFileManager.defaultManager.fileExistsAtPath(path))
            assertTrue(class_conformsToProtocol(object_getClass(item), objc_getProtocol("QLPreviewItem")))
            assertTrue(item.respondsToSelector(NSSelectorFromString("previewItemURL")))
        } finally { NSFileManager.defaultManager.removeItemAtPath(path, null) }
    }
}
