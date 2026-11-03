package com.example

import com.example.ui.navigation.workspaceMayOpen
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The share road has to answer to the same three gates the root screen answers to. A workspace the
 * user cannot see is not a place to write an imported conversation into.
 */
class ShareGatePolicyTest {

    private fun open(
        signedIn: Boolean = true,
        manualSignIn: Boolean = false,
        signInRequired: Boolean = false,
        pending: String? = null,
        lockInWay: Boolean = false
    ) = workspaceMayOpen(
        signedIn = signedIn,
        manualSignInRequested = manualSignIn,
        signInRequiredOnStart = signInRequired,
        pendingVerificationEmail = pending,
        lockInWay = lockInWay
    )

    @Test
    fun anOpenWorkspaceAcceptsTheShare() {
        assertTrue(open())
    }

    @Test
    fun theMailWaitHoldsTheShareBackEvenWithASessionOnTheDevice() {
        assertFalse(open(pending = "a@b.com"))
    }

    @Test
    fun theDeviceLockHoldsTheShareBack() {
        assertFalse(open(lockInWay = true))
    }

    @Test
    fun nobodySignedInSeesNoShareContent() {
        assertFalse(open(signedIn = false))
    }

    @Test
    fun theDeliberateExitHoldsTheShareUntilTheFormIsAnswered() {
        assertFalse(open(signedIn = true, signInRequired = true))
    }

    @Test
    fun aManualSignInRequestOnTheLockScreenHoldsTheShareToo() {
        assertFalse(open(manualSignIn = true))
    }
}
