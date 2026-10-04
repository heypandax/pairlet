package dev.ccpocket.app.data

import dev.ccpocket.app.pairing.PairedDaemon
import dev.ccpocket.protocol.Frame
import dev.ccpocket.protocol.SendPrompt
import dev.ccpocket.protocol.SessionLive
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * A photo still compressing when the user taps send used to be dropped: only Ready photos rode the
 * prompt, then the whole staging list (Compressing included) was cleared, so the text went out and the
 * picture silently vanished. The send now waits, exactly like it waits for file uploads.
 */
class ImageCompressingSendTest {

    @Test
    fun sendWaitsForACompressingPhotoInsteadOfDroppingIt() {
        val sent = CopyOnWriteArrayList<Frame>()
        val r = PocketRepository(CoroutineScope(Dispatchers.Unconfined)).apply {
            paired.value = PairedDaemon(
                relay = "wss://test", accountId = "acct-test", daemonPub = "pk", deviceId = "dev", credential = "cred",
            )
            onSendForTest = { sent += it }
        }
        r.receiveForTest(SessionLive("c1", "/w", "sid-1", executing = false))
        val photo = byteArrayOf(1, 2, 3)
        r.pendingImages.add(PendingImage(7, photo, ImgState.Compressing)) // what attachImages stages first

        assertFalse(r.sendPrompt("look at this"), "nothing goes out while a photo is still compressing")
        assertTrue(sent.filterIsInstance<SendPrompt>().isEmpty())
        assertEquals(1, r.pendingImages.size, "the photo stays staged")

        r.pendingImages[0] = PendingImage(7, photo, ImgState.Ready) // compression finished
        assertTrue(r.sendPrompt("look at this"))
        assertEquals(1, sent.filterIsInstance<SendPrompt>().single().images.size, "the photo rides the prompt")
    }
}
