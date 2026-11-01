package com.example

import com.example.data.remote.auth.ACCOUNT_UNAVAILABLE_FA
import com.example.data.remote.auth.AccountOutcome
import com.example.data.remote.auth.UnconfiguredAccountService
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AccountServiceContractTest {

    @Test
    fun theUnconfiguredServiceNeverPretendsAnAccountWasCreated() = runTest {
        assertTrue(
            UnconfiguredAccountService.register("a@b.com", "secret12") is AccountOutcome.Unavailable
        )
        assertTrue(
            UnconfiguredAccountService.signIn("a@b.com", "secret12") is AccountOutcome.Unavailable
        )
        assertTrue(UnconfiguredAccountService.sendVerification() is AccountOutcome.Unavailable)
        assertTrue(
            UnconfiguredAccountService.sendPasswordReset("a@b.com") is AccountOutcome.Unavailable
        )
    }

    @Test
    fun theUnconfiguredServiceReportsNoVerifiedEmailAndNeedsNoDevice() = runTest {
        assertEquals(false, UnconfiguredAccountService.isEmailVerified())
        UnconfiguredAccountService.signOut()
    }

    @Test
    fun theUnavailableSentencePointsAtTheBuildNotAtTheUser() {
        assertTrue(ACCOUNT_UNAVAILABLE_FA.contains("بسته"))
        // An unconfigured build tells us nothing about the person in front of it, so the sentence must
        // not name the things they typed either: an address, or a password. Neither can be blamed for
        // a build that carries no account service at all.
        assertFalse("the sentence must not name an address: $ACCOUNT_UNAVAILABLE_FA", ACCOUNT_UNAVAILABLE_FA.contains("@"))
        assertFalse("the sentence must not name a password: $ACCOUNT_UNAVAILABLE_FA", ACCOUNT_UNAVAILABLE_FA.contains(PASSWORD_WORD))
    }

    private companion object {
        /**
         * The app's own word for a password, sliced out of the provider's wrong-password sentence in
         * `FirebaseAccountService.authErrorFa`, so this test asks about the word the app really shows
         * instead of one invented here.
         */
        const val PASSWORD_WORD = "رمز"
    }
}
