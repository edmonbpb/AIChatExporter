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
