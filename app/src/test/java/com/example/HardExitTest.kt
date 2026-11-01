package com.example

import android.app.Application
import android.content.Context
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.core.app.ApplicationProvider
import com.example.data.remote.auth.AccountOutcome
import com.example.ui.navigation.hardExitAfterDoublePress
import com.example.ui.viewmodel.MainViewModel
import java.io.File
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
 * The consequence of the back button's second press, read off the app rather than off the account.
 *
 * What this file holds is the decision and its lifetime: only a confirmed second press is an exit,
 * the decision outlives the process (a new [MainViewModel] is the cold start the owner will actually
 * make), it leaves the account on the device so the door still has something to unlock, and both
 * ways back in - the answered device lock and the form itself - take it off again. The two source
 * tests at the bottom hold the one thing a view model cannot show: that the back handler on the
 * Import screen asks for the mark before it closes the app, and that a single press still does
 * nothing but show the hint.
 *
 * What no test here can hold, because the JVM never runs a handset: that the task leaves the
 * recent-tasks list and that the process really ends. `finishAndRemoveTask()` and `exitProcess(0)`
 * are device facts; a test in this file that claimed to see them would be lying.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class HardExitTest {

    // The repo's own harness for a view model whose work runs on `viewModelScope`: the compose rule
    // idles the main looper, so `waitUntil` lets the launch land. A bare `while` loop on this looper
    // would spin forever.
    @get:Rule
    val composeRule = createComposeRule()

    private lateinit var application: Application
    private val account = FakeAccountService()

    @Before
    fun setUp() {
        application = ApplicationProvider.getApplicationContext()
        // The mark lives on disk, so a previous test's mark would be read back by `freshModel()` and
        // this file would prove nothing about the write.
        application.getSharedPreferences(AUTH_PREFS, Context.MODE_PRIVATE).edit().clear().commit()
        account.reset()
    }

    /** A cold start: a view model built the way the app builds one when the process comes back. */
    private fun freshModel(): MainViewModel = MainViewModel(application, account)

    @Test
    fun onlyAConfirmedSecondPressIsTheExitThatMustAskAgain() {
        assertTrue(hardExitAfterDoublePress(exitConfirmed = true))
        assertFalse(hardExitAfterDoublePress(exitConfirmed = false))
    }

    @Test
    fun theMarkIsStillThereWhenTheOwnerStartsTheAppAgain() {
        val beforeTheExit = freshModel()
        assertFalse(
            "fixture: this process starts without the mark",
            beforeTheExit.signInRequiredOnStart.value
        )

        beforeTheExit.markHardExit()
        assertTrue(beforeTheExit.signInRequiredOnStart.value)

        // The process is gone in real life; a new view model over the same prefs is that next start.
        assertTrue(
            "the mark has to be on disk, or the next start walks straight into the workspace",
            freshModel().signInRequiredOnStart.value
        )
    }

    @Test
    fun theMarkLeavesTheSignedInAccountForTheDoorToUnlock() {
        account.signInAnswer = AccountOutcome.SignedIn(OWNER_EMAIL, OWNER_UID)
        val model = freshModel()
        var signedIn = false
        model.loginWithEmail(OWNER_EMAIL, PASSWORD, onSuccess = { signedIn = true }, onError = { })
        composeRule.waitUntil(timeoutMillis = 5_000) { signedIn }
        assertEquals(OWNER_EMAIL, model.userProfile.value?.email)

        model.markHardExit()

        val afterColdStart = freshModel()
        assertTrue("the next start must show the form", afterColdStart.signInRequiredOnStart.value)
        // Without the account the quick-entry offer at the door has nothing to unlock, which is the
        // whole reason the exit marks the door instead of signing the owner out.
        assertEquals(
            "the account stays on the device after a hard exit",
            OWNER_EMAIL,
            afterColdStart.userProfile.value?.email
        )
    }

    @Test
    fun anAnsweredDeviceLockTakesTheMarkOffForGood() {
        val model = freshModel()
        model.markHardExit()
        assertTrue(model.signInRequiredOnStart.value)

        model.unlockSession()

        assertFalse(model.signInRequiredOnStart.value)
        assertFalse(
            "the answer is stored, so the next start must not ask again",
            freshModel().signInRequiredOnStart.value
        )
    }

    @Test
    fun theFormsOwnSignInTakesTheMarkOffAndOpensTheSession() {
        account.signInAnswer = AccountOutcome.SignedIn(OWNER_EMAIL, OWNER_UID)
        val model = freshModel()
        model.markHardExit()

        var signedIn = false
        model.loginWithEmail(OWNER_EMAIL, PASSWORD, onSuccess = { signedIn = true }, onError = { })
        // onSuccess is the last thing the SignedIn branch does, so the mark is already settled when it
        // fires; waiting on the account's call counter would read the flag a step too early.
        composeRule.waitUntil(timeoutMillis = 5_000) { signedIn }

        assertFalse(model.signInRequiredOnStart.value)
        assertEquals(OWNER_EMAIL, model.userProfile.value?.email)
        assertFalse(
            "the answer is stored, so the next start must not ask again",
            freshModel().signInRequiredOnStart.value
        )
    }

    /**
     * The one thing a view model cannot show: which branch of the back handler writes the mark. Read
     * out of the production file, the way `LocalDistillationTest` reads its label, because the JVM
     * cannot press Back on a composed activity. It turns red if the call is dropped or if the process
     * is ended before the task is removed.
     */
    @Test
    fun theSecondPressMarksTheExitThenRemovesTheTaskThenEndsTheProcess() {
        val source = mainActivitySource()
        val elseStart = firstPressStart(source)
        val branchStart = source.indexOf("hardExitAfterDoublePress(")
        val branch = source.substring(branchStart, elseStart)

        val mark = branch.indexOf("viewModel.markHardExit()")
        val remove = branch.indexOf("finishAndRemoveTask()")
        val end = branch.indexOf("exitProcess(0)")
        assertTrue("the confirmed exit must mark the next start: $branch", mark >= 0)
        assertTrue("the confirmed exit must remove the task after the mark: $branch", remove > mark)
        assertTrue("the process must end last, or the row outlives the exit: $branch", end > remove)
    }

    /** A single press on the Import screen still only arms the exit and shows the hint. */
    @Test
    fun theFirstPressOnlyArmsTheExitAndShowsTheHint() {
        val source = mainActivitySource()
        val elseStart = firstPressStart(source)
        val toastEnd = source.indexOf("Toast.LENGTH_SHORT", elseStart)
        assertTrue("the first press must still show the hint", toastEnd > elseStart)
        val firstPress = source.substring(elseStart, toastEnd)

        assertTrue("the first press arms the second one: $firstPress", firstPress.contains("lastBackPressTime = currentTime"))
        assertTrue("the first press shows the hint: $firstPress", firstPress.contains("Toast.makeText"))
        assertFalse("the first press must not mark an exit: $firstPress", firstPress.contains("markHardExit"))
        assertFalse("the first press must not end the app: $firstPress", firstPress.contains("exitProcess"))
    }

    /** Where the first-press branch begins; asserted so the slices above cannot be accidentally empty. */
    private fun firstPressStart(source: String): Int {
        val policy = source.indexOf("hardExitAfterDoublePress(")
        assertTrue("the back handler must ask the exit policy", policy >= 0)
        val elseStart = source.indexOf("} else {", policy)
        assertTrue("the confirmed exit must have a branch of its own", elseStart > policy)
        return elseStart
    }

    /** `MainActivity.kt` found by walking up from the working directory, as the other source test does. */
    private fun mainActivitySource(): String {
        val relative = "src/main/java/com/example/MainActivity.kt"
        var dir: File? = File(".").absoluteFile
        var source: File? = null
        while (dir != null && source == null) {
            val direct = File(dir, relative)
            val nested = File(dir, "app/$relative")
            if (direct.isFile) {
                source = direct
            } else if (nested.isFile) {
                source = nested
            }
            dir = dir.parentFile
        }
        val file: File = source
            ?: error("MainActivity.kt was not found walking up from ${File(".").absolutePath}")
        return file.readText()
    }

    private companion object {
        const val AUTH_PREFS = "aichat_auth_prefs"

        // An address the fake account answers with; not one of the legacy invented ones the view model
        // deliberately drops at startup.
        const val OWNER_EMAIL = "owner.of.this.phone@example.com"
        const val OWNER_UID = "uid-hard-exit"
        const val PASSWORD = "Secret-1234"
    }
}
