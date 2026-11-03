package com.example

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import com.example.data.remote.auth.AccountOutcome
import com.example.ui.screens.AuthScreen
import com.example.ui.viewmodel.MainViewModel
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * «رمز عبورت را فراموش کردی» as the app actually walks it: one call to the account with the address,
 * and one of two answers — the mail went out, or the provider said something the user has to read.
 *
 * The enumeration rule is the sharp edge here: a reset request must never tell an attacker which
 * addresses have an account. That rule lives in `resetOutcomeFor` (Task 2) and is witnessed by
 * `ResetOutcomeTest`, not here: with the fake account there is no provider code to mask, so a test in
 * this file that claimed to see the mask would only be comparing a sentence with itself.
 *
 * The address's own shape check lives inside `FirebaseAccountService`, which needs a Firebase project
 * and therefore has no JVM witness. What is measured here is the app's half: which answer it asks for,
 * and what it does with the answer it gets.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ForgotPasswordTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val account = FakeAccountService()
    private lateinit var viewModel: MainViewModel

    @Before
    fun setUp() {
        account.reset()
        account.passwordResetAnswer = AccountOutcome.EmailSent
        viewModel = MainViewModel(ApplicationProvider.getApplicationContext(), account, InMemoryKeyStorage())
    }

    @Test
    fun pressingItAsksTheProviderOnceWithTheAddressInTheBox() = runTest {
        var sent = false
        viewModel.requestPasswordReset("  sbedmon1@gmail.com  ", onSent = { sent = true }, onError = { })
        composeRule.waitUntil(timeoutMillis = 5_000) { sent }
        assertEquals(1, account.passwordResetCalls)
    }

    @Test
    fun aRealFailureIsStillShownAsFailure() = runTest {
        account.passwordResetAnswer = AccountOutcome.Failed(NETWORK_MESSAGE)
        var shown: String? = null
        viewModel.requestPasswordReset("a@b.com", onSent = { }, onError = { shown = it })
        composeRule.waitUntil(timeoutMillis = 5_000) { shown != null }
        assertEquals(NETWORK_MESSAGE, shown)
    }

    /**
     * The way out of the login form itself. The label's words and the address's shape are not what is
     * measured here (Task 2 owns the address rules); what is measured is that the login tab carries the
     * door at all, that pressing it hands the box's address to the caller, that the hint under it is
     * only composed after a press and that its sentence makes no claim the mail went out, and that the
     * signup tab carries no reset link at all - that tab creates an account, so a reset there would be
     * a lie. Break 5d of the plan (rendering the button only when the callback is null) turns the first
     * assertion red; putting back a sentence that claims delivery turns the hint assertion red.
     */
    @Test
    fun theLoginFormCarriesAForgotPasswordWayOut() {
        var askedFor: String? = null
        composeRule.setContent {
            MaterialTheme {
                AuthScreen(viewModel = viewModel, onForgotPassword = { address -> askedFor = address })
            }
        }

        composeRule.onNodeWithTag("auth_forgot_password_btn").performScrollTo().assertExists()
        composeRule.onNodeWithTag("auth_reset_hint").assertDoesNotExist()

        composeRule.onNodeWithTag("auth_email_input").performScrollTo().performTextInput(RESET_ADDRESS)
        composeRule.onNodeWithTag("auth_forgot_password_btn").performScrollTo().performClick()

        assertEquals(RESET_ADDRESS, askedFor)
        // The hint appears after the press, but it must not claim the mail went out: the request can
        // fail, and a failure is what the banner under the form says. The sentence is compared
        // verbatim against the composable's own bytes, so a return to "the guide was sent" is red.
        val hint = composeRule.onNodeWithTag("auth_reset_hint")
        hint.assertExists()
        hint.assertTextEquals(NEUTRAL_HINT)
        val hintText = hint.fetchSemanticsNode()
            .config.getOrNull(SemanticsProperties.Text)?.joinToString("") { it.text }.orEmpty()
        assertFalse("the hint must not claim delivery: $hintText", hintText.contains(SENT_CLAIM))

        composeRule.onNodeWithTag("tab_signup").performScrollTo().performClick()
        composeRule.onNodeWithTag("auth_forgot_password_btn").assertDoesNotExist()
    }

    private companion object {
        // Spliced from the plan's own bytes, not retyped: the sentences the account road may show.
        const val NETWORK_MESSAGE = "ارتباط با سرویس حساب برقرار نشد؛ اینترنت را بررسی کن."

        // An address this class types into the box; never a real account.
        const val RESET_ADDRESS = "forgot.me@example.com"

        // Spliced from the composable's hint line and from the view model's sent-message constant.
        const val NEUTRAL_HINT = "ایمیل را بررسی کن؛ کمی بعد دوباره امتحان کن."
        const val SENT_CLAIM = "فرستاده شد"
    }
}
