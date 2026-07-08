package dev.ccpocket.app.ui

import dev.ccpocket.protocol.UsageDay
import kotlin.test.Test
import kotlin.test.assertEquals

/** Pure helpers behind the 30d usage heatmap — the weekday placement and quartile shading the grid keys off. */
class UsageHeatmapTest {

    @Test
    fun weekdayMon0_parses_iso_date_with_monday_zero() {
        // ground truth: 2026-07-06 Mon, 2026-07-09 Thu, 2026-07-12 Sun
        assertEquals(0, weekdayMon0(UsageDay("Mon", 0, date = "2026-07-06")))
        assertEquals(3, weekdayMon0(UsageDay("Thu", 0, date = "2026-07-09")))
        assertEquals(6, weekdayMon0(UsageDay("Sun", 0, date = "2026-07-12")))
    }

    @Test
    fun weekdayMon0_falls_back_to_english_label_when_date_absent() {
        assertEquals(0, weekdayMon0(UsageDay("Mon", 0)))
        assertEquals(4, weekdayMon0(UsageDay("Fri", 0)))
        assertEquals(6, weekdayMon0(UsageDay("Sun", 0)))
        // an unparseable label degrades to column 0, never -1 (which would corrupt the grid offset)
        assertEquals(0, weekdayMon0(UsageDay("???", 0)))
    }

    @Test
    fun quartile_buckets_by_fraction_of_window_max() {
        assertEquals(1, quartile(10, 100))  // 10% → faintest
        assertEquals(1, quartile(25, 100))  // 25% boundary stays in bucket 1
        assertEquals(2, quartile(26, 100))
        assertEquals(2, quartile(50, 100))
        assertEquals(3, quartile(75, 100))
        assertEquals(4, quartile(76, 100))
        assertEquals(4, quartile(100, 100)) // the peak day → full
    }

    @Test
    fun peakLabel_formats_iso_date_and_falls_back_to_label() {
        assertEquals("07-05", peakLabel(UsageDay("Sun", 100, date = "2026-07-05")))
        assertEquals("Wed", peakLabel(UsageDay("Wed", 100)))
    }
}
