package com.example

import com.example.ui.navigation.isExitDoublePress
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The two-press back rule, read straight off the pure function [isExitDoublePress] that MainActivity
 * calls instead of the inline comparison it used to hold.
 *
 * Only the function is exercised here: whether the second press really removes the app from the
 * recent-tasks list is a device fact (`finishAndRemoveTask`), and this JVM test cannot see it. What it
 * can hold is the arithmetic the toast promises — the first press only arms the exit, the second has
 * two seconds to arrive, and neither an exact window edge nor a clock that stepped backwards may be
 * read as a double press.
 */
class ExitPolicyTest {

    /** A press with nothing behind it is the first one: it may only arm the exit. */
    @Test
    fun theFirstBackPressOnlyArmsTheExit() {
        assertFalse(isExitDoublePress(now = 5_000L, lastPress = 0L))
    }

    /** The ordinary case the toast is written for: two presses a moment apart. */
    @Test
    fun aSecondPressInsideTheWindowIsAnExit() {
        assertTrue(isExitDoublePress(now = 2_500L, lastPress = 1_000L))
    }

    /** The boundary is inclusive: a window of 2000 ms is still open at exactly 2000 ms elapsed. */
    @Test
    fun theExactWindowEdgeStillCounts() {
        assertTrue(isExitDoublePress(now = 3_000L, lastPress = 1_000L, windowMs = 2_000L))
    }

    /** One millisecond later the promise is over, and the late press only arms a new attempt. */
    @Test
    fun oneMillisecondPastTheEdgeDoesNotCount() {
        assertFalse(isExitDoublePress(now = 3_001L, lastPress = 1_000L, windowMs = 2_000L))
    }

    /** `0` is «no press yet» and a negative stamp is not a time at all; neither is a second press. */
    @Test
    fun aNonPositiveLastPressIsNeverASecondPress() {
        assertFalse(isExitDoublePress(now = 5_000L, lastPress = 0L))
        assertFalse(isExitDoublePress(now = 5_000L, lastPress = -1L))
    }

    /**
     * A clock that went the other way (a device time change, an `elapsedRealtime` mix-up) must not
     * subtract into the window: the elapsed time is negative there, and the double press is a lie.
     */
    @Test
    fun aClockThatWentBackwardsIsNotAnExit() {
        assertFalse(isExitDoublePress(now = 2_000L, lastPress = 3_000L))
    }

    /**
     * The window the callers get when they pass nothing is the two seconds the toast names: with the
     * parameter left at its default, 1900 ms and 2000 ms count and 2001 ms does not. A default of 1500
     * or of 5000 would fail here, which is the whole point of the test.
     */
    @Test
    fun theDefaultWindowIsTheTwoSecondsTheToastPromises() {
        assertTrue(isExitDoublePress(now = 2_900L, lastPress = 1_000L))
        assertTrue(isExitDoublePress(now = 3_000L, lastPress = 1_000L))
        assertFalse(isExitDoublePress(now = 3_001L, lastPress = 1_000L))
    }

    /** A window passed in is the one that is honoured, shorter and longer alike. */
    @Test
    fun aCustomWindowIsHonoured() {
        assertTrue(isExitDoublePress(now = 1_500L, lastPress = 1_000L, windowMs = 500L))
        assertFalse(isExitDoublePress(now = 1_501L, lastPress = 1_000L, windowMs = 500L))
        assertTrue(isExitDoublePress(now = 4_000L, lastPress = 1_000L, windowMs = 5_000L))
    }
}
