package com.example

import com.example.ui.navigation.shouldOfferSecurityEntry
import com.example.ui.navigation.shouldShowAuth
import com.example.ui.navigation.shouldShowVerification
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AuthGatePolicyTest {

    // ---- which root screen ----

    @Test
    fun anExitThatAskedForLoginShowsTheLoginFormEvenWithAStoredSession() {
        assertTrue(
            shouldShowAuth(signedIn = true, manualSignInRequested = false, signInRequiredOnStart = true)
        )
    }

    @Test
    fun withoutThatRequestASignedInUserStillGoesStraightIntoTheApp() {
        assertFalse(
            shouldShowAuth(signedIn = true, manualSignInRequested = false, signInRequiredOnStart = false)
        )
    }

    @Test
    fun nobodySignedInSeesTheLoginFormEvenBeforeAnyStart() {
        assertTrue(shouldShowAuth(signedIn = false, manualSignInRequested = false, signInRequiredOnStart = false))
    }

    // ---- the verification wait ----

    @Test
    fun aPendingAddressHoldsTheRootUntilItIsAnswered() {
        assertTrue(shouldShowVerification("a@b.com", lockInWay = false))
    }

    @Test
    fun theLockComesFirstAndTheVerificationWaitWaitsBehindIt() {
        assertFalse(shouldShowVerification("a@b.com", lockInWay = true))
    }

    @Test
    fun withNothingPendingNoWaitScreenExists() {
        assertFalse(shouldShowVerification(null, lockInWay = false))
        assertFalse(shouldShowVerification("   ", lockInWay = false))
    }

    // ---- the security entry offer ----

    @Test
    fun theOfferAppearsOnlyOnTheLoginFormAndOnlyWhenThereIsALockToUse() {
        assertTrue(
            shouldOfferSecurityEntry(
                signInRequiredOnStart = true,
                manualSignInRequested = false,
                appLockEnabled = true,
                deviceCredentialAvailable = true
            )
        )
        assertTrue(
            shouldOfferSecurityEntry(
                signInRequiredOnStart = false,
                manualSignInRequested = true,
                appLockEnabled = true,
                deviceCredentialAvailable = true
            )
        )
    }

    @Test
    fun aPhoneWithNoDeviceLockIsNeverOfferedWhatItCannotAnswer() {
        assertFalse(
            shouldOfferSecurityEntry(
                signInRequiredOnStart = true,
                manualSignInRequested = false,
                appLockEnabled = true,
                deviceCredentialAvailable = false
            )
        )
    }

    @Test
    fun anOwnerWhoNeverTurnedTheSwitchOnIsNotOfferedAQuickEntry() {
        assertFalse(
            shouldOfferSecurityEntry(
                signInRequiredOnStart = true,
                manualSignInRequested = false,
                appLockEnabled = false,
                deviceCredentialAvailable = true
            )
        )
    }

    @Test
    fun aPlainColdStartOfASignedInAppIsNotAnEntryToOffer() {
        assertFalse(
            shouldOfferSecurityEntry(
                signInRequiredOnStart = false,
                manualSignInRequested = false,
                appLockEnabled = true,
                deviceCredentialAvailable = true
            )
        )
    }
}
