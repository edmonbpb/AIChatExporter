package com.example.ui.navigation

/**
 * The lock is shown only on a cold start: the owner decided that rotation and returning from the
 * background must not ask again. Without a pattern/PIN/password on the device there is nothing to
 * ask, so the gate stays open instead of bricking the app.
 */
fun shouldShowLock(
    appLockEnabled: Boolean,
    deviceCredentialAvailable: Boolean,
    unlockedThisProcess: Boolean
): Boolean = appLockEnabled && deviceCredentialAvailable && !unlockedThisProcess

/**
 * Which root screen the user gets once the lock is not in the way. Measured on `R58X30GS9QJ`
 * (2026-11-01): writing this as `signedIn || manualSignInRequested` put a signed-in user back on the
 * login form after every successful login, and no JVM test caught it because nothing there composes
 * the root of `MainActivity`.
 */
fun shouldShowAuth(signedIn: Boolean, manualSignInRequested: Boolean): Boolean =
    !signedIn || manualSignInRequested
