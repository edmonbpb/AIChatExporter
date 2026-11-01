package com.example

import com.example.data.remote.auth.ACCOUNT_UNAVAILABLE_FA
import com.example.data.remote.auth.AccountOutcome
import com.example.data.remote.auth.UnconfiguredAccountService
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
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
    }
}
