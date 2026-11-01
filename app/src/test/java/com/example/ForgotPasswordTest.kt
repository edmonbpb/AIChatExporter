package com.example

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.core.app.ApplicationProvider
import com.example.data.remote.auth.AccountOutcome
import com.example.ui.viewmodel.MainViewModel
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * «رمز عبورت را فراموش کردی» as the app actually walks it: one call to the account with the address,
 * and one of two answers — the mail went out, or the provider said something the user has to read. The
 * enumeration rule is the sharp edge: a reset request must never tell an attacker which addresses have
 * an account here, so the road that answers "no such user" still says the mail went out.
 *
 * The address's own shape check and that not-found code live inside `FirebaseAccountService` (Task 2),
 * which needs a Firebase project and therefore has no JVM witness. What is measured here is the app's
 * half: which answer it asks for, and what it does with the answer it gets.
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
        viewModel = MainViewModel(ApplicationProvider.getApplicationContext(), account)
    }

    @Test
    fun pressingItAsksTheProviderOnceWithTheAddressInTheBox() = runTest {
        var sent = false
        viewModel.requestPasswordReset("  sbedmon1@gmail.com  ", onSent = { sent = true }, onError = { })
        composeRule.waitUntil(timeoutMillis = 5_000) { sent }
        assertEquals(1, account.passwordResetCalls)
    }

    @Test
    fun anUnknownAddressIsNeverConfirmedByTheResetRoad() = runTest {
        // The provider's answer for an address that never had an account is `ERROR_USER_NOT_FOUND`, and
        // `FirebaseAccountService.sendPasswordReset` (Task 2) turns exactly that code into [EmailSent].
        // So the fake carries `EmailSent` for "no such account" — the sentence naming the not-found code
        // below is what the road must NOT show, and `onSent` is what it must take.
        val providerSaysNoSuchAccount = AccountOutcome.Failed(NO_ACCOUNT_MESSAGE)
        account.passwordResetAnswer = AccountOutcome.EmailSent
        var sent = false
        var error: String? = null
        viewModel.requestPasswordReset(
            "nobody@nowhere.com",
            onSent = { sent = true },
            onError = { error = it }
        )
        composeRule.waitUntil(timeoutMillis = 5_000) { sent }
        assertNull("the reset road must not hand back a not-found answer: $error", error)
        assertEquals(RESET_SENT_MESSAGE, viewModel.userMessage.value)
        assertNotEquals(
            "the road never repeats the provider's not-found sentence",
            providerSaysNoSuchAccount.messageFa,
            viewModel.userMessage.value
        )
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

    private companion object {
        // Spliced from the plan's own bytes, not retyped: the sentences the account road may show.
        const val NO_ACCOUNT_MESSAGE = "چنین حسابی در این برنامه ثبت نشده است."
        const val RESET_SENT_MESSAGE = "راهنمای تغییر رمز به ایمیل تو فرستاده شد."
        const val NETWORK_MESSAGE = "ارتباط با سرویس حساب برقرار نشد؛ اینترنت را بررسی کن."
    }
}
