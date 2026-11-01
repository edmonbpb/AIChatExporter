package com.example

import android.app.Application
import android.content.Context
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.lifecycle.viewModelScope
import androidx.test.core.app.ApplicationProvider
import com.example.data.remote.auth.AccountOutcome
import com.example.ui.screens.AuthScreen
import com.example.ui.viewmodel.MainViewModel
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.util.ReflectionHelpers

/**
 * Who is allowed on the login tab. Google sign-up, the guest entry and the demo notice were the three
 * ways past the address-and-password form; the owner kept only the form on the login tab and moved
 * Google account creation to the signup tab, under its own label and above the email divider. The
 * tests read the screen, not the source, because a button that merely stopped working would still be
 * a way in for anybody looking at it. The last test presses that same submit button once and then
 * reads the two state values the root screen is built from, because which screen comes next is decided
 * outside this class (Task 5's gate), while "no session" and "the wait is taken up" are decided here.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@LooperMode(LooperMode.Mode.PAUSED)
class AuthScreenLoginTest {
    @get:Rule
    val compose = createComposeRule()

    private lateinit var application: Application
    private val account = FakeAccountService()
    private lateinit var viewModel: MainViewModel

    @Before
    fun setUp() {
        application = ApplicationProvider.getApplicationContext()
        clearAccountState()
        // Login asks the account seam now, so this class is handed its own: the address below is
        // answered with a session whenever a test does press submit, except where the test says
        // otherwise. Nothing here reaches Firebase.
        account.reset()
        account.signInAnswer = AccountOutcome.SignedIn(SIGNED_IN_EMAIL, "uid-1")
        viewModel = MainViewModel(application, account)
        compose.setContent { MaterialTheme { AuthScreen(viewModel) } }
    }

    @After
    fun tearDown() {
        if (::viewModel.isInitialized) {
            viewModel.viewModelScope.cancel()
        }
        if (::application.isInitialized) {
            clearAccountState()
        }
    }

    @Test
    fun theLoginTabSaysWhatItIsAndOffersNeitherGoogleNorAGuest() {
        // Scoped to the tab on purpose: the submit button carries the same two words.
        compose.onNodeWithTag("tab_login").assertTextEquals(LOGIN_TAB)

        compose.onNodeWithTag("google_signin_button").assertDoesNotExist()
        compose.onNodeWithTag("guest_mode_btn").assertDoesNotExist()
        compose.onNodeWithTag("email_demo_notice").assertDoesNotExist()
    }

    @Test
    fun theGoogleButtonAndTheEmailDividerAppearOnlyOnTheSignUpTab() {
        compose.onNodeWithTag("google_signin_button").assertDoesNotExist()
        compose.onNodeWithText(DIVIDER).assertDoesNotExist()

        compose.onNodeWithTag("tab_signup").performClick()

        compose.onNodeWithTag("tab_signup").assertTextEquals(SIGNUP_TAB)
        compose.onNode(hasTestTag("google_signin_button") and hasText(GOOGLE_SIGNUP_LABEL))
            .performScrollTo()
            .assertIsDisplayed()
        compose.onNodeWithTag("google_signin_button").assertIsDisplayed()
        compose.onNodeWithText(DIVIDER).performScrollTo().assertIsDisplayed()

        // Switching back hides it again, so the tab is what owns it rather than first composition.
        compose.onNodeWithTag("tab_login").performScrollTo().performClick()
        compose.onNodeWithTag("google_signin_button").assertDoesNotExist()
        compose.onNodeWithText(DIVIDER).assertDoesNotExist()
    }

    /**
     * The other half of the login road: an address that has not tapped its mail yet. Firebase answers
     * that with `NeedsVerification`, and the view model must take up the wait without opening a
     * session - the workspace is built from `userProfile`, so a profile here is the same as walking in
     * past the mail. The form is driven, not the view model called, so the submit button's own wiring
     * is inside the proof.
     */
    @Test
    fun aLoginWhoseAddressAwaitsItsMailTakesTheWaitAndOpensNoSession() {
        assertNull("fixture: this process starts with no wait on file", viewModel.pendingVerification.value)
        account.signInAnswer = AccountOutcome.NeedsVerification(WAITING_EMAIL)

        compose.onNodeWithTag("auth_email_input").performScrollTo().performTextInput(WAITING_EMAIL)
        compose.onNodeWithTag("auth_password_input").performScrollTo().performTextInput(PASSWORD)
        compose.onNodeWithTag("auth_submit_btn").performScrollTo().performClick()
        compose.waitUntil(timeoutMillis = 5_000) { account.signInCalls == 1 }

        // The wait is taken up under this exact address, and no session is opened beside it.
        assertEquals(
            "the address is the one that must answer the mail",
            WAITING_EMAIL,
            viewModel.pendingVerification.value
        )
        assertNull("a login that needs verification must not reach the workspace", viewModel.userProfile.value)
        assertEquals(1, account.signInCalls)
    }

    /** Google's two process-wide caches, dropped the way `DriveGuestCardTest` drops them. */
    private fun clearAccountState() {
        application.getSharedPreferences(SIGNIN_PREFS, Context.MODE_PRIVATE).edit().clear().commit()
        application.getSharedPreferences(DRIVE_BACKUP_PREFS, Context.MODE_PRIVATE).edit().clear().commit()
        application.getSharedPreferences(AUTH_PREFS, Context.MODE_PRIVATE).edit().clear().commit()
        ReflectionHelpers.setStaticField(GOOGLE_ACCOUNT_HOLDER, GOOGLE_ACCOUNT_HOLDER_FIELD, null)
        ReflectionHelpers.setStaticField(GOOGLE_STORAGE, GOOGLE_STORAGE_FIELD, null)
    }

    private companion object {
        const val SIGNIN_PREFS = "com.google.android.gms.signin"
        const val DRIVE_BACKUP_PREFS = "drive_backup"
        const val AUTH_PREFS = "aichat_auth_prefs"

        val GOOGLE_ACCOUNT_HOLDER: Class<*> =
            com.google.android.gms.auth.api.signin.internal.zbn::class.java

        val GOOGLE_STORAGE: Class<*> =
            com.google.android.gms.auth.api.signin.internal.Storage::class.java

        const val GOOGLE_ACCOUNT_HOLDER_FIELD = "zbd"
        const val GOOGLE_STORAGE_FIELD = "zab"

        // Addresses this class answers with, never a real account: the fake holds both of them.
        const val SIGNED_IN_EMAIL = "signed.in@example.com"
        const val WAITING_EMAIL = "still.waiting@example.com"
        const val PASSWORD = "Secret-1234"

        // The screen's own copy, asserted verbatim so what the user reads cannot drift unnoticed.
        const val LOGIN_TAB = "ورود به برنامه"
        const val SIGNUP_TAB = "ساخت حساب کاربری"
        const val GOOGLE_SIGNUP_LABEL = "ساخت حساب کاربری سریع با حساب گوگل"
        const val DIVIDER = "یا با ایمیل و رمز عبور"
    }
}
