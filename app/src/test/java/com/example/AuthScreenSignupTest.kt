package com.example

import android.app.Application
import android.os.Looper
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.util.ReflectionHelpers
import java.time.Duration

/**
 * The signup form the owner asked for: an address and a password, nothing else. The name that once
 * had its own field is now read off the address (`ali.reza@` becomes `Ali Reza`), and the password
 * repeat box is gone with it. The two tests below hold both halves of that: the boxes do not exist
 * even as tags, and a registration driven only through the address still lands a named, non-guest
 * profile - so a field quietly coming back would show up here rather than on the handset.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@LooperMode(LooperMode.Mode.PAUSED)
class AuthScreenSignupTest {
    @get:Rule
    val compose = createComposeRule()

    private lateinit var application: Application
    private lateinit var viewModel: MainViewModel

    @Before
    fun setUp() {
        application = ApplicationProvider.getApplicationContext()
        clearAccountState()
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
    fun theSignUpFormOffersNoNameBoxAndNoPasswordRepeat() {
        openSignUp()

        compose.onNodeWithTag("auth_name_input").assertDoesNotExist()
        compose.onNodeWithTag("auth_confirm_password_input").assertDoesNotExist()
        // The tagged boxes are gone, but an untagged one would be just as wrong: the form may only
        // take two strings, the address and the password.
        compose.onAllNodes(hasSetTextAction()).assertCountEquals(2)
    }

    @Test
    fun signingUpWithOnlyAnAddressBuildsTheNameOutOfThatAddress() {
        openSignUp()

        compose.onNodeWithTag("auth_email_input").performScrollTo().performTextInput(EMAIL)
        compose.onNodeWithTag("auth_password_input").performScrollTo().performTextInput(PASSWORD)
        // Proves this harness really reads what was typed before the empty-password claim is made.
        assertEquals(EMAIL, editableText("auth_email_input"))

        compose.onNodeWithTag("auth_submit_btn").performScrollTo().performClick()
        idlePastTheDelay()

        val profile = viewModel.userProfile.value
        assertNotNull("a registration with only an address must land a profile", profile)
        assertEquals("the name is read off the address, because nobody typed one", "Ali Reza", profile!!.name)
        assertEquals(EMAIL, profile.email)
        assertFalse("a newly created account is never a guest", profile.isGuest)
        assertEquals(ACCOUNT_CREATED, viewModel.userMessage.value)
        compose.onNodeWithTag("auth_name_input").assertDoesNotExist()
        compose.onNodeWithTag("auth_confirm_password_input").assertDoesNotExist()
    }

    private fun openSignUp() {
        // Signup goes through the account seam now, so the fixture hands the seam an answer that
        // really opens a session for the address this test types; the name is still derived from it.
        viewModel = MainViewModel(
            application,
            FakeAccountService().apply {
                registerAnswer = AccountOutcome.SignedIn(EMAIL, "uid-9")
            }
        )
        compose.setContent { MaterialTheme { AuthScreen(viewModel) } }
        compose.onNodeWithTag("tab_signup").performClick()
    }

    /**
     * The submit's work runs on the main dispatcher and this class pauses the looper, so nothing it
     * posted runs until the clock is moved. Advancing it is what lets the registration's coroutine
     * resume and land the fake's answer; the production path itself waits for nothing.
     */
    private fun idlePastTheDelay() {
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1200))
        compose.waitForIdle()
    }

    private fun editableText(tag: String): String =
        compose.onNodeWithTag(tag).fetchSemanticsNode()
            .config.getOrNull(SemanticsProperties.EditableText)?.text.orEmpty()

    /**
     * Google's account cache is parsed once per process and hung off two singletons; a class that
     * reads it without dropping them reads whichever fixture ran first. Same reset as
     * `DriveGuestCardTest`, so a solo run and the full suite agree.
     */
    private fun clearAccountState() {
        application.getSharedPreferences(SIGNIN_PREFS, android.content.Context.MODE_PRIVATE)
            .edit().clear().commit()
        application.getSharedPreferences(DRIVE_BACKUP_PREFS, android.content.Context.MODE_PRIVATE)
            .edit().clear().commit()
        application.getSharedPreferences(AUTH_PREFS, android.content.Context.MODE_PRIVATE)
            .edit().clear().commit()
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

        const val EMAIL = "ali.reza@example.com"
        const val PASSWORD = "Secret-1234"

        /** The success line the view model sets, asserted verbatim so it cannot drift unnoticed. */
        const val ACCOUNT_CREATED = "حساب ساخته شد؛ حالا ایمیل را تأیید کن."
    }
}
