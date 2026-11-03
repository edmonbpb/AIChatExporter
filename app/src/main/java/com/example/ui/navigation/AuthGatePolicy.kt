package com.example.ui.navigation

/**
 * Which root screen the user gets once the lock is not in the way.
 *
 * `signInRequiredOnStart` is the deliberate double-back exit's answer: the account may still be on
 * this device, but the owner asked to be out of the app, so the next start must show the form.
 * Measured on `R58X30GS9QJ` (2026-11-01) without that term an app closed with the back button reopened
 * from the recents list straight into the workspace.
 */
fun shouldShowAuth(
    signedIn: Boolean,
    manualSignInRequested: Boolean,
    signInRequiredOnStart: Boolean = false
): Boolean = signInRequiredOnStart || !signedIn || manualSignInRequested

/**
 * The wait on the address confirmation sits in front of the form and the workspace, but never in
 * front of the device lock: the lock is the owner's own gate and it comes first.
 */
fun shouldShowVerification(pendingVerificationEmail: String?, lockInWay: Boolean): Boolean =
    !lockInWay && !pendingVerificationEmail.isNullOrBlank()

/**
 * Whether to offer the device lock as a way in from the login form. Two different roads put the user
 * on that form — the deliberate exit, and their own "sign in manually" on the lock screen — and both
 * are a place where a quick entry helps. The offer is never made to a phone with nothing to answer it
 * with, and never to an owner who left the security switch off.
 */
fun shouldOfferSecurityEntry(
    signInRequiredOnStart: Boolean,
    manualSignInRequested: Boolean,
    appLockEnabled: Boolean,
    deviceCredentialAvailable: Boolean
): Boolean =
    (signInRequiredOnStart || manualSignInRequested) && appLockEnabled && deviceCredentialAvailable

/**
 * The single fact the back handler needs: the second press inside the window is an exit the owner
 * asked for, and an exit they asked for must put the next start behind the form. Kept apart from
 * [isExitDoublePress] so the timing rule and the consequence of the exit can be tested on their own.
 */
fun hardExitAfterDoublePress(exitConfirmed: Boolean): Boolean = exitConfirmed

/**
 * Whether the workspace is open enough to receive work.
 *
 * The root screen already refuses to *show* the workspace behind these gates, but a share from
 * another app never asks the root: it arrives on the activity's intent and writes straight into the
 * view model. Measured 2026-11-03: with the mail wait on screen, `ACTION_SEND` still ran
 * `parseAndLoadTranscript()`. One predicate, read by both the screen and the road, so they cannot
 * drift apart.
 */
fun workspaceMayOpen(
    signedIn: Boolean,
    manualSignInRequested: Boolean,
    signInRequiredOnStart: Boolean,
    pendingVerificationEmail: String?,
    lockInWay: Boolean
): Boolean =
    !lockInWay &&
        !shouldShowVerification(pendingVerificationEmail, lockInWay) &&
        !shouldShowAuth(signedIn, manualSignInRequested, signInRequiredOnStart)
