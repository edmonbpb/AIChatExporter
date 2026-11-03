package com.example

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import com.example.data.remote.auth.AccountOutcome
import com.example.ui.screens.VerificationScreen
import com.example.ui.viewmodel.MainViewModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

/**
 * The mail wait as its owner reads it. Three things are watched: the address being waited on is the one
 * this screen was handed, the three ways out carry the tags Task 6 wires and Task 7 walks, and every
 * press asks exactly one question of the account. The answers themselves come from [FakeAccountService],
 * so what is proved here is the screen's half - that a press reaches the view model, and that an answer
 * which is not yet a yes leaves the owner on the same wait with one line saying what is still missing.
 *
 * Which screen shows when is `AuthGatePolicy`'s rule and `AuthGatePolicyTest` watches it. Nothing here
 * reads a nullable address: the wait screen is given the one it displays.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@LooperMode(LooperMode.Mode.PAUSED)
class VerificationScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private lateinit var application: Application
    private val account = FakeAccountService()
    private lateinit var viewModel: MainViewModel

    @Before
    fun setUp() {
        application = ApplicationProvider.getApplicationContext()
        account.reset()
        viewModel = MainViewModel(application, account).also {
            it.setPendingVerification(WAITING_EMAIL)
        }
    }

    private fun show() {
        compose.setContent {
            MaterialTheme { VerificationScreen(viewModel = viewModel, email = WAITING_EMAIL) }
        }
    }

    @Test
    fun theScreenNamesTheAddressBeingWaitedOn() {
        show()
        // The address on screen is the parameter this screen was handed, so a wait shown for a
        // different owner cannot pass, and a copy of the sentence baked into the screen goes red here.
        compose.onNodeWithText(WAITING_EMAIL).assertIsDisplayed()
    }

    @Test
    fun theWaitScreenRootAndTheThreeWaysOutCarryTheirTags() {
        show()
        // Task 6 shows and hides this screen by this tag, Task 7 presses the three buttons by theirs, so
        // a rename here must be caught here and not on the handset.
        compose.onNodeWithTag(ROOT_TAG).assertExists()
        compose.onNode(hasTestTag(CHECK_TAG) and hasText(CHECK_LABEL)).performScrollTo().assertIsDisplayed()
        compose.onNode(hasTestTag(RESEND_TAG) and hasText(RESEND_LABEL)).performScrollTo().assertIsDisplayed()
        compose.onNode(hasTestTag(BACK_TAG) and hasText(BACK_LABEL)).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun checkButtonAsksTheProviderOnce() {
        account.verified = true
        account.emailToReport = WAITING_EMAIL
        show()
        compose.onNodeWithTag(CHECK_TAG).performScrollTo().performClick()
        compose.waitUntil(timeoutMillis = 5_000) { viewModel.pendingVerification.value == null }
        assertEquals("one press asks the account once", 1, account.reloadCalls)
        assertNull(viewModel.pendingVerification.value)
        compose.onNodeWithTag(NOTE_TAG).assertDoesNotExist()
    }

    @Test
    fun aCheckThatComesBackEmptyLeavesTheWaitOnScreenWithANote() {
        account.verified = false
        show()
        compose.onNodeWithTag(CHECK_TAG).performScrollTo().performClick()
        // An unopened link is not a failure. The screen has to say so where the owner can read it, and
        // keep the wait exactly where it was.
        compose.waitUntil(timeoutMillis = 5_000) {
            compose.onAllNodesWithTag(NOTE_TAG).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText(STILL_PENDING).assertExists()
        assertEquals("an unanswered check still asks the account once", 1, account.reloadCalls)
        assertEquals("the wait outlives a no", WAITING_EMAIL, viewModel.pendingVerification.value)
    }

    @Test
    fun resendButtonSendsAgainWithoutTouchingTheWait() {
        account.sendVerificationAnswer = AccountOutcome.EmailSent
        show()
        compose.onNodeWithTag(RESEND_TAG).performScrollTo().performClick()
        compose.waitUntil(timeoutMillis = 5_000) { account.sendVerificationCalls == 1 }
        assertEquals("a resend keeps the owner on the same wait", WAITING_EMAIL, viewModel.pendingVerification.value)
        assertNull("a resend opens no session", viewModel.userProfile.value)
    }

    @Test
    fun backButtonEndsTheWaitAndReturnsToTheForm() {
        show()
        compose.onNodeWithTag(BACK_TAG).performScrollTo().performClick()
        compose.waitUntil(timeoutMillis = 5_000) { viewModel.pendingVerification.value == null }
        assertEquals("leaving the wait signs the half session out", 1, account.signOutCalls)
        assertNull(viewModel.pendingVerification.value)
    }

    private companion object {
        // The screen's own labels and its tags, spliced from the plan's bytes rather than retyped:
        // Task 6 wires these tags and Task 7 reads these labels, so neither may drift unnoticed here.
        const val WAITING_EMAIL = "wait@user.com"
        const val ROOT_TAG = "verification_screen"
        const val CHECK_TAG = "verification_check_btn"
        const val RESEND_TAG = "verification_resend_btn"
        const val BACK_TAG = "verification_back_btn"
        const val NOTE_TAG = "verification_note"
        const val CHECK_LABEL = "بررسی وضعیت تأیید"
        const val RESEND_LABEL = "فرستادن دوبارهٔ ایمیل"
        const val BACK_LABEL = "بازگشت به صفحهٔ ورود"
        const val STILL_PENDING = "لینک هنوز باز نشده است. بعد از زدنش دوباره بررسی کن."
    }
}
