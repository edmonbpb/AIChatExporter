package com.example

import com.example.ui.components.LabelMode
import com.example.ui.components.labelMaxLines
import com.example.ui.components.labelMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * Gate for the button-label guard (U-4). `labelMode` is the only place that decides whether a
 * label is drawn whole, ellipsized or dropped for an icon, and every threshold in it is a number
 * measured on emulator-5554 (1080x1920, 480dpi) during P-3:
 *
 *  - a healthy bottom-bar label was 104px to 133px wide for 8 to 10 characters = 35dp to 44dp,
 *  - the broken one was 31px wide and 432px tall (ten characters collapsed to ten one-letter
 *    lines) = 10dp, and the failure signature of the audit is a label under 60px = 20dp wide.
 *
 * So: 5dp per character of advance, 24dp eaten by icon plus padding, 20dp as the icon-only
 * ceiling and 4dp of slack before a fit counts as full.
 */
class AdaptiveLabelModeTest {

    // ---- the three modes, each at a width that is unambiguously on its own side of a line ----

    @Test
    fun wideSlotWithRoomToSpareRendersTheWholeLabel() {
        // 1 slot of 100dp leaves 76dp for text; a 6-character label needs 30dp + 4dp slack.
        assertEquals(LabelMode.FULL, labelMode(availableDp = 100, itemCount = 1, labelChars = 6))
    }

    @Test
    fun tightSlotThatCannotHoldTheLabelEllipsizesInsteadOfBreaking() {
        // 6 slots over 360dp leave 36dp of text; the 10-character label needs 54dp.
        assertEquals(LabelMode.ELLIPSIS, labelMode(availableDp = 360, itemCount = 6, labelChars = 10))
    }

    @Test
    fun slotInsideTheMeasuredFailureWidthGoesIconOnly() {
        // 5 slots over 100dp leave only 1dp of text, well under the 20dp an ellipsis needs.
        assertEquals(LabelMode.ICON_ONLY, labelMode(availableDp = 100, itemCount = 5, labelChars = 8))
    }

    // ---- the exact boundary numbers ----

    @Test
    fun textWidthOfExactlyTheIconOnlyCeilingIsIconOnly() {
        // 44dp slot - 24dp chrome = 20dp, and the ceiling test is inclusive (<= 20dp).
        assertEquals(LabelMode.ICON_ONLY, labelMode(availableDp = 44, itemCount = 1, labelChars = 1))
    }

    @Test
    fun oneDpAboveTheIconOnlyCeilingIsNoLongerIconOnly() {
        // 45dp - 24dp = 21dp: above the ceiling, and enough for a 1-character label (5dp + 4dp).
        assertEquals(LabelMode.FULL, labelMode(availableDp = 45, itemCount = 1, labelChars = 1))
    }

    @Test
    fun labelClearingItsEstimateByExactlyTheSlackCountsAsFull() {
        // 4 characters need 20dp; 48dp - 24dp = 24dp is 20dp + the 4dp slack, so it is FULL.
        assertEquals(LabelMode.FULL, labelMode(availableDp = 48, itemCount = 1, labelChars = 4))
    }

    @Test
    fun oneDpLessThanTheSlackIsEllipsisAndNotFull() {
        // 47dp - 24dp = 23dp, one short of the 24dp a full fit demands.
        assertEquals(LabelMode.ELLIPSIS, labelMode(availableDp = 47, itemCount = 1, labelChars = 4))
    }

    @Test
    fun fourSlotsKeepTheirLabelsWholeAsMeasured() {
        // The audit's own case: four items across 360dp leave 66dp, and a 10-character label wants 54dp.
        assertEquals(LabelMode.FULL, labelMode(availableDp = 360, itemCount = 4, labelChars = 10))
    }

    // ---- a long label in a narrow width must never be trusted to fit ----

    @Test
    fun longLabelInNarrowWidthNeverComesBackFullAtAnyItemCount() {
        // A 40-character label needs 200dp of text plus the 4dp slack, so any slot narrower than
        // 228dp (chrome included) must answer ELLIPSIS or ICON_ONLY, never a promise it cannot keep.
        val longLabel = 40
        for (availableDp in listOf(44, 60, 120, 180, 220, 360)) {
            for (itemCount in 2..6) {
                assertNotEquals(
                    "width ${availableDp}dp over $itemCount slots must not promise a full 40-char label",
                    LabelMode.FULL,
                    labelMode(availableDp, itemCount, longLabel)
                )
            }
        }
        // Even a whole phone screen as one slot is not enough for a 40-character button label.
        assertNotEquals(LabelMode.FULL, labelMode(availableDp = 220, itemCount = 1, labelChars = longLabel))
    }

    @Test
    fun unknownGeometryFallsBackToFullSoTheGuardOnlyClampsLines() {
        // No measured width and no slot count: keep the caller's copy whole, one line, ellipsis ready.
        assertEquals(LabelMode.FULL, labelMode(availableDp = 0, itemCount = 1, labelChars = 12))
        assertEquals(LabelMode.FULL, labelMode(availableDp = -1, itemCount = 3, labelChars = 12))
        assertEquals(LabelMode.FULL, labelMode(availableDp = 360, itemCount = 0, labelChars = 12))
        assertEquals(LabelMode.FULL, labelMode(availableDp = 360, itemCount = 3, labelChars = 0))
    }

    // ---- the line budget the guard hands to the Text call ----

    @Test
    fun tightAndIconOnlyModesNeverAllowASecondLine() {
        assertEquals(1, labelMaxLines(LabelMode.ELLIPSIS, preferredMaxLines = 3))
        assertEquals(1, labelMaxLines(LabelMode.ICON_ONLY, preferredMaxLines = 3))
    }

    @Test
    fun fullModeKeepsThePreferredLineCountButNeverZero() {
        assertEquals(2, labelMaxLines(LabelMode.FULL, preferredMaxLines = 2))
        assertEquals(1, labelMaxLines(LabelMode.FULL, preferredMaxLines = 1))
        assertEquals(1, labelMaxLines(LabelMode.FULL, preferredMaxLines = 0))
    }
}
