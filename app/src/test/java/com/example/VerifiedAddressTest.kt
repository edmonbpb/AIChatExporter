package com.example

import android.app.Application
import android.content.Context
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.core.app.ApplicationProvider
import com.example.data.remote.auth.AccountOutcome
import com.example.data.remote.auth.confirmedAddressOwnsWait
import com.example.ui.viewmodel.MainViewModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Two facts the mail-wait check must hold at once: the address that was confirmed is the address the
 * wait is about, and the id the session is written under is the provider's, not a stand-in. Measured
 * 2026-11-03: `checkVerificationNow` wrote `uid = address` (MainViewModel.kt:677) while every other
 * road wrote the provider's uid, so one person could end up with two identities.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class VerifiedAddressTest {

    @get:Rule
    val composeRule = createComposeRule()

    private lateinit var application: Application
    private val account = FakeAccountService()

    @Before
    fun setUp() {
        application = ApplicationProvider.getApplicationContext()
        application.getSharedPreferences("aichat_auth_prefs", Context.MODE_PRIVATE)
            .edit().clear().commit()
        account.reset()
    }

    @Test
    fun anAddressIsOnlyTheSameAddressIgnoringCaseAndSurroundingSpace() {
        assertTrue(confirmedAddressOwnsWait("Owner@Example.com ", " owner@example.com"))
        assertFalse(confirmedAddressOwnsWait("owner@example.com", "someone.else@example.com"))
        assertFalse(confirmedAddressOwnsWait(null, "owner@example.com"))
        assertFalse(confirmedAddressOwnsWait("owner@example.com", null))
    }

    @Test
    fun theWaitOpensOnlyWhenTheConfirmedAddressIsThePendingOne() {
        account.verified = true
        account.uidToReport = "uid-from-provider"
        account.emailToReport = "owner@example.com"

        val model = MainViewModel(application, account)
        model.setPendingVerification("owner@example.com")
        var opened = false
        var refused = false
        model.checkVerificationNow(
            onVerified = { opened = true },
            onStillPending = { refused = true },
            onError = { refused = true }
        )
        composeRule.waitUntil(timeoutMillis = 5_000) { opened || refused }

        assertTrue("the pending address was the confirmed one", opened)
        assertEquals("uid-from-provider", model.userProfile.value?.id)
    }

    @Test
    fun aConfirmedAddressThatIsNotThePendingOneOpensNothing() {
        account.verified = true
        account.uidToReport = "uid-from-provider"
        account.emailToReport = "someone.else@example.com"

        val model = MainViewModel(application, account)
        model.setPendingVerification("owner@example.com")
        var opened = false
        var settled = false
        model.checkVerificationNow(
            onVerified = { opened = true },
            onStillPending = { settled = true },
            onError = { settled = true }
        )
        composeRule.waitUntil(timeoutMillis = 5_000) { settled || opened }

        assertFalse("another address's confirmation must not open this wait", opened)
        assertEquals("the wait keeps its own address", "owner@example.com",
            model.pendingVerification.value)
    }

    @Test
    fun anUnconfirmedAddressKeepsTheWaitUp() {
        account.verified = false
        val model = MainViewModel(application, account)
        model.setPendingVerification("owner@example.com")
        var opened = false
        var settled = false
        model.checkVerificationNow(
            onVerified = { opened = true },
            onStillPending = { settled = true },
            onError = { settled = true }
        )
        composeRule.waitUntil(timeoutMillis = 5_000) { settled || opened }

        assertFalse("an unconfirmed address must not open the session", opened)
        assertEquals("owner@example.com", model.pendingVerification.value)
    }
}
