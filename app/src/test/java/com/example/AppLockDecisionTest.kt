package com.example

import com.example.ui.navigation.shouldShowLock
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The app lock gate, read off the pure function rather than the screen. `shouldShowLock` is the only
 * place that decides whether `LockScreen` replaces the app, and the owner's decision behind it is that
 * the lock asks again only on a cold start (plan, «قفلِ برنامه فقط در شروعِ سرد»). Three facts feed the
 * function - the stored switch, whether the handset has a pattern/PIN/password at all, and whether this
 * process already answered the device lock - so all eight combinations are walked here, each test named
 * by the fact it holds rather than by a row number, so a swapped condition fails with a readable name.
 *
 * What this cannot cover, and knowingly: nothing here proves `MainActivity` calls the function, and the
 * real `BiometricPrompt` (pattern, PIN, password, fingerprint, face, cancel) is never started. That
 * matrix belongs to the controller on the phone; a JVM screen would only prove the fake answered.
 */
class AppLockDecisionTest {

    @Test
    fun aColdProcessWithTheSwitchOnAndADeviceLockShowsTheLock() {
        assertTrue(
            shouldShowLock(
                appLockEnabled = true,
                deviceCredentialAvailable = true,
                unlockedThisProcess = false
            )
        )
    }

    @Test
    fun aProcessThatAlreadyAnsweredTheDeviceLockIsNotAskedAgain() {
        assertFalse(
            shouldShowLock(
                appLockEnabled = true,
                deviceCredentialAvailable = true,
                unlockedThisProcess = true
            )
        )
    }

    @Test
    fun aDeviceWithoutAPatternPinOrPasswordStaysOpenEvenWithTheSwitchOn() {
        assertFalse(
            shouldShowLock(
                appLockEnabled = true,
                deviceCredentialAvailable = false,
                unlockedThisProcess = false
            )
        )
    }

    @Test
    fun aDeviceWithoutADeviceLockAndAnAnsweredSessionStaysOpen() {
        assertFalse(
            shouldShowLock(
                appLockEnabled = true,
                deviceCredentialAvailable = false,
                unlockedThisProcess = true
            )
        )
    }

    @Test
    fun withTheSwitchOffAFreshProcessIsNeverLocked() {
        assertFalse(
            shouldShowLock(
                appLockEnabled = false,
                deviceCredentialAvailable = true,
                unlockedThisProcess = false
            )
        )
    }

    @Test
    fun withTheSwitchOffAnAnsweredSessionIsNeverLocked() {
        assertFalse(
            shouldShowLock(
                appLockEnabled = false,
                deviceCredentialAvailable = true,
                unlockedThisProcess = true
            )
        )
    }

    @Test
    fun withTheSwitchOffAndNoDeviceLockTheGateStaysOpen() {
        assertFalse(
            shouldShowLock(
                appLockEnabled = false,
                deviceCredentialAvailable = false,
                unlockedThisProcess = false
            )
        )
    }

    @Test
    fun withTheSwitchOffNoDeviceLockAndAnAnsweredSessionTheGateStaysOpen() {
        assertFalse(
            shouldShowLock(
                appLockEnabled = false,
                deviceCredentialAvailable = false,
                unlockedThisProcess = true
            )
        )
    }
}
