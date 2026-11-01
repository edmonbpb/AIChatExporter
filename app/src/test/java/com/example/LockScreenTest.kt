package com.example

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import com.example.ui.screens.LockScreen
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import java.util.concurrent.atomic.AtomicInteger

/**
 * What the locked screen offers, and that each offer is wired to its own decision. All three ways out
 * go through the activity's device prompt (`presentUnlock`) on the phone, so this screen only has to
 * promise two things: the labels the user reads are the three from the contract, and a press cannot
 * fire a neighbour. The second half is the one a copy-pasted `onClick` breaks in silence - all three
 * buttons would look right and one press would unlock, sign out and remove the lock together.
 *
 * The real `BiometricPrompt` is never started here, and the plan says so: a dismissed prompt leaving
 * the user on this screen is measured on the handset, not under Robolectric.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@LooperMode(LooperMode.Mode.PAUSED)
class LockScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private val unlockPresses = AtomicInteger()
    private val manualSignInPresses = AtomicInteger()
    private val removeLockPresses = AtomicInteger()

    @Test
    fun theLockScreenIsOneRootWithThreeWaysOut() {
        showLockScreen()

        compose.onNodeWithTag("lock_screen").assertIsDisplayed()
        compose.onNode(hasTestTag("lock_unlock_btn") and hasText(UNLOCK_LABEL)).assertIsDisplayed()
        compose.onNode(hasTestTag("lock_manual_signin_btn") and hasText(MANUAL_SIGN_IN_LABEL)).assertIsDisplayed()
        compose.onNode(hasTestTag("lock_remove_btn") and hasText(REMOVE_LOCK_LABEL)).assertIsDisplayed()
    }

    @Test
    fun theUnlockButtonCallsOnlyTheUnlock() {
        showLockScreen()

        compose.onNodeWithTag("lock_unlock_btn").performClick()
        compose.waitForIdle()

        assertEquals("the press belongs to the device-prompt path", 1, unlockPresses.get())
        assertEquals("manual sign-in is a separate choice", 0, manualSignInPresses.get())
        assertEquals("removing the lock is a separate choice", 0, removeLockPresses.get())
    }

    @Test
    fun theManualSignInButtonCallsOnlyManualSignIn() {
        showLockScreen()

        compose.onNodeWithTag("lock_manual_signin_btn").performClick()
        compose.waitForIdle()

        assertEquals("a press here must not open the device prompt", 0, unlockPresses.get())
        assertEquals("the press belongs to the manual path", 1, manualSignInPresses.get())
        assertEquals("removing the lock is a separate choice", 0, removeLockPresses.get())
    }

    @Test
    fun theRemoveLockButtonCallsOnlyRemoveLock() {
        showLockScreen()

        compose.onNodeWithTag("lock_remove_btn").performClick()
        compose.waitForIdle()

        assertEquals("a press here must not open the device prompt", 0, unlockPresses.get())
        assertEquals("manual sign-in is a separate choice", 0, manualSignInPresses.get())
        assertEquals("the press belongs to the remove path", 1, removeLockPresses.get())
    }

    private fun showLockScreen() {
        compose.setContent {
            MaterialTheme {
                LockScreen(
                    onUnlock = { unlockPresses.incrementAndGet() },
                    onManualSignIn = { manualSignInPresses.incrementAndGet() },
                    onRemoveLock = { removeLockPresses.incrementAndGet() }
                )
            }
        }
    }

    private companion object {
        // The screen's own copy, asserted verbatim so what the user reads cannot drift unnoticed.
        const val UNLOCK_LABEL = "باز کردن با قفل دستگاه"
        const val MANUAL_SIGN_IN_LABEL = "ورود دستی به حساب دیگر"
        const val REMOVE_LOCK_LABEL = "حذف قفل برنامه"
    }
}
