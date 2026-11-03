package com.example

import android.app.Application
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.core.app.ApplicationProvider
import com.example.data.remote.auth.AccountOutcome
import com.example.data.remote.auth.VerifiedSession
import com.example.ui.viewmodel.MainViewModel
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The signup and sign-in roads read off the app, not off the account: a new address waits on its mail
 * instead of opening a session, the wait is the owner's own half-finished signup (it survives a new
 * process because they asked for that mail), and an unconfigured build says so rather than letting
 * anyone in. Every answer below comes from [FakeAccountService], so the thing under watch is what the
 * view model does with an answer, never the answer itself.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RealSignupVerificationTest {

    @get:Rule
    val composeRule = createComposeRule()

    private lateinit var application: Application
    private val account = FakeAccountService()
    private lateinit var viewModel: MainViewModel

    @Before
    fun setUp() {
        application = ApplicationProvider.getApplicationContext()
        // The wait lives on disk, so a previous test's wait would be read back here and a test would
        // prove nothing about the write. Same clean slate as `AppLockPrefsTest`.
        application.getSharedPreferences(AUTH_PREFS, android.content.Context.MODE_PRIVATE)
            .edit().clear().commit()
        account.reset()
        account.registerAnswer = AccountOutcome.NeedsVerification("new@user.com")
        viewModel = MainViewModel(application, account, InMemoryKeyStorage())
    }

    @Test
    fun aSignupThatNeedsVerificationDoesNotOpenTheSession() = runTest {
        viewModel.registerWithEmail("new@user.com", "secret12", onSuccess = { }, onError = { })
        composeRule.waitUntil(timeoutMillis = 5_000) { account.registerCalls == 1 }
        assertEquals("new@user.com", viewModel.pendingVerification.value)
        assertNull(viewModel.userProfile.value)
    }

    @Test
    fun theWaitSurvivesANewProcessBecauseItIsTheOwnersOwnHalfFinishedSignup() {
        assertNull("fixture: this process starts with no wait on file", viewModel.pendingVerification.value)
        viewModel.setPendingVerification("new@user.com")
        val restarted = MainViewModel(application, account, InMemoryKeyStorage())
        assertEquals("new@user.com", restarted.pendingVerification.value)
    }

    @Test
    fun comingBackAfterTappingTheLinkOpensTheSession() = runTest {
        viewModel.setPendingVerification("new@user.com")
        // The wait check opens the session from one provider snapshot, not from the three separate
        // questions, so the fake must report the pending address through that snapshot.
        account.verifiedSessionToReport = VerifiedSession("uid-1", "new@user.com")
        account.signedInAs = AccountOutcome.SignedIn("new@user.com", "uid-1")
        var verified = false
        viewModel.checkVerificationNow(
            onVerified = { verified = true },
            onStillPending = { },
            onError = { }
        )
        composeRule.waitUntil(timeoutMillis = 5_000) { verified }
        assertNull(viewModel.pendingVerification.value)
        assertEquals("new@user.com", viewModel.userProfile.value?.email)
    }

    @Test
    fun anUnansweredLinkLeavesTheWaitOnScreenAndSaysNothingWrong() = runTest {
        viewModel.setPendingVerification("new@user.com")
        account.verified = false
        var stillPending = false
        viewModel.checkVerificationNow(
            onVerified = { },
            onStillPending = { stillPending = true },
            onError = { throw AssertionError("the wait is not an error: $it") }
        )
        composeRule.waitUntil(timeoutMillis = 5_000) { stillPending }
        assertEquals("new@user.com", viewModel.pendingVerification.value)
        assertNull(viewModel.userProfile.value)
    }

    @Test
    fun leavingTheWaitDropsTheAddressAndSignsTheHalfSessionOut() = runTest {
        viewModel.setPendingVerification("new@user.com")
        viewModel.leaveVerificationWait()
        assertNull(viewModel.pendingVerification.value)
        assertEquals(1, account.signOutCalls)
    }

    @Test
    fun anUnconfiguredBuildSaysSoInsteadOfQuietlyLettingAnyoneIn() = runTest {
        account.registerAnswer = AccountOutcome.Unavailable
        var shown: String? = null
        viewModel.registerWithEmail("new@user.com", "secret12", onSuccess = {
            throw AssertionError("an unconfigured build must not report success")
        }, onError = { shown = it })
        composeRule.waitUntil(timeoutMillis = 5_000) { shown != null }
        assertTrue(shown!!.isNotBlank())
        assertNotEquals(AccountOutcome.Unavailable.toString(), shown)
    }

    private companion object {
        const val AUTH_PREFS = "aichat_auth_prefs"
    }
}
