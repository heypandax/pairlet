package dev.ccpocket.observability

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.*

class NativeStackFrameTest {
    @Test fun sourceLocationKeepsOnlyBasenameAndLine() {
        val frame = safeNativeFrame("4 cc-pocket 0x1234 kfun:dev.ccpocket.app.Repository#read(){} + 48 (/Users/PRIVATE_NAME/project/Repository.kt:123:7)")!!
        assertEquals("dev.ccpocket.app.Repository.read", frame.symbol)
        assertEquals("Repository.kt", frame.file)
        assertEquals(123, frame.line)
        val json = Json.encodeToString(frame)
        for (privateValue in listOf("PRIVATE_NAME", "/Users/", "0x1234", "48", "project/")) {
            assertFalse(json.contains(privateValue), privateValue)
        }
    }

    @Test fun sourceInfoWithoutColumnAndWithoutSymbolsDegradesSafely() {
        val frame = safeNativeFrame("4 cc-pocket 0x1234 kfun:dev.ccpocket.app.Repository#read(){} + 48 (Repository.kt:123)")!!
        assertEquals(123, frame.line)
        val unknown = safeNativeFrame("4 cc-pocket 0x1234 kfun:dev.ccpocket.app.Repository#read(){} + 48 ")!!
        assertNull(unknown.file)
        assertNull(unknown.line)
    }

    @Test fun unrelatedFramesAndMultilineExceptionTextAreRejected() {
        assertNull(safeNativeFrame("0 libc 0x1234 pthread_start + 48"))
        assertNull(safeNativeFrame("0 app 0x1234 kfun:kotlin.IllegalStateException#<init>(){} + 48"))
        assertNull(safeNativeFrame("IllegalStateException: dev.ccpocket.app.PRIVATE_MESSAGE"))
        assertNull(safeNativeFrame("kfun:dev.ccpocket.app.Repository#read\nPRIVATE_MESSAGE"))
    }

    @Test fun invalidSourceFieldsAreNotForwarded() {
        val frame = safeNativeFrame("0 app 0x1234 kfun:dev.ccpocket.app.Repository#read(){} + 48 (/private/token=SECRET.kt:0)")!!
        assertNull(frame.file)
        assertNull(frame.line)
        val overflow = safeNativeFrame("0 app 0x1234 kfun:dev.ccpocket.app.Repository#read(){} + 48 (Repository.kt:999999999999)")!!
        assertNull(overflow.line)
    }
}
