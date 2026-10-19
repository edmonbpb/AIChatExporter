package com.example

import com.example.ui.screens.AccountAction
import com.example.ui.screens.accountAction
import org.junit.Assert.assertEquals
import org.junit.Test

/** The settings card must offer the action the state is missing, not the one it already has. */
class AccountActionTest {

    @Test
    fun aGuestIsOfferedASignIn() {
        assertEquals(AccountAction.SIGN_IN_WITH_GOOGLE, accountAction(isGuest = true))
    }

    @Test
    fun aSignedInUserIsOfferedASignOut() {
        assertEquals(AccountAction.SIGN_OUT, accountAction(isGuest = false))
    }
}
