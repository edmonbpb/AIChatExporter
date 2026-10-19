package com.example

import com.example.ui.components.AppScreen
import com.example.ui.components.stepIsPassed
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The rail's «passed» decision. The last step can only be passed by finishing its own work, because
 * no screen comes after it.
 */
class StepProgressTest {

    @Test
    fun stepsBehindTheCurrentOneArePassed() {
        assertTrue(stepIsPassed(AppScreen.IMPORT, AppScreen.WORKSPACE, currentSessionExported = false))
        assertTrue(stepIsPassed(AppScreen.WORKSPACE, AppScreen.EXPORT, currentSessionExported = false))
        assertFalse(stepIsPassed(AppScreen.EXPORT, AppScreen.WORKSPACE, currentSessionExported = false))
    }

    @Test
    fun theExportStepIsPassedOnlyWhenThisSessionWasExported() {
        assertFalse(stepIsPassed(AppScreen.EXPORT, AppScreen.EXPORT, currentSessionExported = false))
        assertTrue(stepIsPassed(AppScreen.EXPORT, AppScreen.EXPORT, currentSessionExported = true))
        assertTrue(stepIsPassed(AppScreen.EXPORT, AppScreen.LIBRARY, currentSessionExported = true))
    }

    @Test
    fun aScreenOutsideTheWorkflowIsNeverPassed() {
        assertFalse(stepIsPassed(AppScreen.LIBRARY, AppScreen.EXPORT, currentSessionExported = true))
        assertFalse(stepIsPassed(AppScreen.SETTINGS, AppScreen.EXPORT, currentSessionExported = true))
    }
}
