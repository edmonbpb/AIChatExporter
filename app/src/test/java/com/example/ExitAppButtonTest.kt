package com.example

import android.app.Application
import android.content.Context
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.example.data.remote.auth.AccountOutcome
import com.example.ui.components.AppScreen
import com.example.ui.components.AppTopBar
import com.example.ui.viewmodel.MainViewModel
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

/**
 * The exit the owner asked to see at the top of the screen, in two halves.
 *
 * The first test reads the bar itself: the button exists under its own tag and a tap reaches the
 * exit callback it was handed - the bar is told what an exit means by its caller, so a bar that
 * drew the icon and called something else (the new-session action, or the account sign-out) would
 * be the same defect as no button at all.
 *
 * The second test reads the account sign-out that still lives in Settings: `logout()` must end the
 * provider's session, not only the local prefs. A session that survives in the account service while
 * the profile is null is the hole a reviewer flagged - the next feature that asks the provider who
 * is signed in would get an answer the screen has already denied.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@LooperMode(LooperMode.Mode.PAUSED)
class ExitAppButtonTest {

    @get:Rule
    val composeRule = createComposeRule()

    private lateinit var application: Application
    private val account = FakeAccountService()
    private lateinit var viewModel: MainViewModel

    @Before
    fun setUp() {
        application = ApplicationProvider.getApplicationContext()
        // The account prefs outlive a test's process view, so a session left by a previous test
        // would be read back here and this class would prove nothing about the exit.
        application.getSharedPreferences(AUTH_PREFS, Context.MODE_PRIVATE)
            .edit().clear().commit()
        account.reset()
        viewModel = MainViewModel(application, account)
    }

    @Test
    fun theBarCarriesAnExitThatCallsOnlyTheExit() {
        var exitCalls = 0
        var newSessionCalls = 0
        composeRule.setContent {
            MaterialTheme {
                AppTopBar(
                    currentScreen = AppScreen.IMPORT,
                    activeSession = null,
                    onSaveSession = { },
                    onToggleBookmark = { },
                    onNewSession = { newSessionCalls += 1 },
                    onExitApp = { exitCalls += 1 }
                )
            }
        }

        composeRule.onNodeWithTag("exit_app_button").performClick()

        assertEquals("one tap is one exit, no more", 1, exitCalls)
        assertEquals("the exit must not be wired to the new-session action", 0, newSessionCalls)
    }

    @Test
    fun anExitEndsTheProviderSessionAsWellAsTheLocalOne() = runTest {
        account.signInAnswer = AccountOutcome.SignedIn(OWNER_EMAIL, UID)
        viewModel.loginWithEmail(OWNER_EMAIL, PASSWORD, onSuccess = { }, onError = { })
        composeRule.waitUntil(timeoutMillis = 5_000) { viewModel.userProfile.value != null }
        assertNotNull(
            "fixture: the exit below needs a session to clear",
            viewModel.userProfile.value
        )

        viewModel.logout()

        assertNull(
            "the workspace is built from the profile, so a profile here is still a signed-in user",
            viewModel.userProfile.value
        )
        assertEquals(
            "the provider's own session ends too, not only the local prefs",
            1,
            account.signOutCalls
        )
    }

    private companion object {
        const val AUTH_PREFS = "aichat_auth_prefs"
        const val OWNER_EMAIL = "exit.owner@example.com"
        const val UID = "uid-exit"
        const val PASSWORD = "Secret-1234"
    }
}
