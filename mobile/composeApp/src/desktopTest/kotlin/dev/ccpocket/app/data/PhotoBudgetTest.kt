package dev.ccpocket.app.data

import dev.ccpocket.protocol.WIRE_MAX_FRAME_BYTES
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * "Picking 3+ photos, only the first two arrive": the per-frame photo budget was sized for the relay's old
 * 256 KiB cap, so two compressed photos used it up and the rest were marked Rejected and left out of the send.
 */
class PhotoBudgetTest {
    @Test
    fun the_attach_limit_of_photos_at_the_compression_target_all_fit_one_frame() {
        val scope = CoroutineScope(Dispatchers.Unconfined)
        try {
            val repo = PocketRepository(scope)
            repeat(PocketRepository.MAX_IMAGES) { i ->
                repo.pendingImages.add(PendingImage(i.toLong(), ByteArray(PocketRepository.IMG_MAX_BYTES), ImgState.Ready))
            }
            repo.removePendingImage(-1) // no-op removal: re-runs the budget pass
            assertEquals(List(PocketRepository.MAX_IMAGES) { ImgState.Ready }, repo.pendingImages.map { it.state })
        } finally { scope.cancel() }
    }

    @Test
    fun the_budget_stays_well_inside_the_wire_frame_cap() {
        // prompt text + JSON envelope + E2E framing ride the same frame
        assertTrue(PocketRepository.IMAGE_BUDGET_B64 * 2 < WIRE_MAX_FRAME_BYTES)
    }
}
