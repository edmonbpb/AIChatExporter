package com.example

import android.accounts.Account
import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import android.os.Looper
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollTo
import androidx.lifecycle.viewModelScope
import androidx.test.core.app.ApplicationProvider
import com.example.data.remote.auth.AccountOutcome
import com.example.data.remote.auth.AccountService
import com.example.data.remote.drive.DriveAuth
import com.example.ui.screens.SettingsScreen
import com.example.ui.viewmodel.MainViewModel
import com.google.android.gms.auth.GoogleAuthUtil
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.annotation.LooperMode
import org.robolectric.annotation.Resetter
import org.robolectric.util.ReflectionHelpers
import java.time.Duration
import java.util.concurrent.atomic.AtomicInteger

/**
 * B-47 on the JVM: the backup card's disconnect used to last only until the process died, because a
 * new process rebuilt "connected" from Google's own cached `drive.file` grant - a grant this app can
 * never revoke. The fix stores the user's decision (`drive_disconnected` in `aichat_auth_prefs`) and
 * lets it outrank the cached grant at cold start, until a connect press or a new session clears it.
 *
 * The cold-start value alone was not enough and the last test here proves why: opening Settings
 * re-probes the connection, and the probe asked Google - whose cached grant answers happily - so the
 * card flipped back to "connected" in the very next second. Google's blocking token call is therefore
 * shadowed here (the same single class `DriveGuestCardTest` shadows, this time answering with a
 * token) and the last test counts its calls: with the fix a disconnected process never asks at all.
 * The other tests read the card straight off the view model, never render it, and so never reach the
 * call - only the real preference files and Google's real account cache drive those fixtures, the way
 * `DriveReconnectTest` seeds its stale grant.
 */
@RunWith(RobolectricTestRunner::class)
@Config(
    sdk = [34],
    shadows = [DriveDisconnectRestartTest.GoogleTokenShadow::class],
    instrumentedPackages = ["com.google.android.gms.auth.GoogleAuthUtil"]
)
@LooperMode(LooperMode.Mode.PAUSED)
class DriveDisconnectRestartTest {

    @get:Rule
    val compose = createComposeRule()

    private lateinit var application: Application
    private lateinit var authPrefs: SharedPreferences
    private val created = mutableListOf<MainViewModel>()

    /**
     * The only seam between "this process" and "a new process": a freshly built view model. The
     * account seam gets the fake everywhere - without the injection the real service throws inside
     * a launched coroutine on this JVM, and the exception surfaces as an uncaught failure in a
     * later test of the class.
     */
    private fun newViewModel(account: AccountService = FakeAccountService()): MainViewModel =
        MainViewModel(application, account, InMemoryKeyStorage()).also { created += it }

    @Before
    fun setUp() {
        GoogleTokenShadow.reset()
        application = ApplicationProvider.getApplicationContext()
        clearAccountState()
        // The app's own session is on disk before anything starts, so "the account stays" in test 2
        // is measured against a real stored email, not against the absence of one.
        authPrefs = application.getSharedPreferences(AUTH_PREFS, Context.MODE_PRIVATE)
        authPrefs.edit()
            .putString("user_id", APP_SESSION_ID)
            .putString("user_email", SEED_EMAIL)
            .putBoolean("is_guest", false)
            .commit()
        // The handset state the bug was measured on: Google's local cache still holds the drive.file
        // grant this app never revoked.
        seedCachedGoogleGrant()
        assertNotNull("fixture: Google's local cache holds an account", DriveAuth.lastAccount(application))
        assertTrue("fixture must reproduce the cached drive.file grant", DriveAuth.hasConsent(application))
    }

    @After
    fun tearDown() {
        created.forEach { it.viewModelScope.cancel() }
        created.clear()
        if (::application.isInitialized) {
            clearAccountState()
        }
    }

    @Test
    fun aCachedGoogleGrantRebuildsTheCardAsConnectedOnStartup() {
        val viewModel = newViewModel()
        assertTrue("a fresh process with a cached grant starts connected", viewModel.driveLinked.value)
    }

    @Test
    fun disconnectDropsTheCardAndStoresTheDecisionButKeepsTheAccount() {
        val viewModel = newViewModel()
        viewModel.disconnectGoogleAccount()

        assertFalse(viewModel.driveLinked.value)
        assertTrue("the user's decision must outlive this process",
            authPrefs.getBoolean("drive_disconnected", false))
        assertEquals("disconnect is not sign-out: the account stays",
            SEED_EMAIL, authPrefs.getString("user_email", null))
        assertNotNull(viewModel.userProfile.value)
    }

    @Test
    fun theStoredDecisionSurvivesANewProcess() {
        // The defect itself: without the marker this reads Google's cached grant back as "connected".
        newViewModel().disconnectGoogleAccount()
        val restarted = newViewModel()
        assertFalse("a new process must not rebuild connected over the user's disconnect",
            restarted.driveLinked.value)
    }

    @Test
    fun pressingConnectAgainClearsTheMarkerAndTheCardComesBack() {
        val viewModel = newViewModel()
        viewModel.disconnectGoogleAccount()
        // A fresh connect is a fresh ask (under this harness the guard always takes it: no consent is
        // pending), so the stored disconnect must lose its rank here.
        assertTrue("beginDriveConsent must accept the fresh ask", viewModel.beginDriveConsent())
        assertFalse("the connect press removes the marker",
            authPrefs.getBoolean("drive_disconnected", false))

        val restarted = newViewModel()
        assertTrue("with the marker gone, the cached grant speaks again", restarted.driveLinked.value)
    }

    @Test
    fun aNewSessionInheritsNoDisconnectDecision() {
        // Sign-in answers through the seam, and it must answer "signed in" or no new session exists
        // to inherit anything; the card-reset path below is what the test is really measuring.
        val account = FakeAccountService().apply {
            signInAnswer = AccountOutcome.SignedIn(NEW_SESSION_EMAIL, "uid-3")
        }
        val viewModel = newViewModel(account)
        viewModel.disconnectGoogleAccount()

        // The coroutine the login launches runs while the paused main looper is walked forward.
        viewModel.loginWithEmail(
            email = NEW_SESSION_EMAIL,
            password = "pass",
            onSuccess = {},
            onError = { fail("the fixture login must not fail: $it") }
        )
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1_200))

        assertEquals(NEW_SESSION_EMAIL, viewModel.userProfile.value?.email)
        assertFalse("a new session inherits no disconnect from the old one",
            authPrefs.getBoolean("drive_disconnected", false))
    }

    @Test
    fun openingSettingsAfterARestartDoesNotReProbeDriveOverTheStoredDecision() {
        // The hole the phone showed at 18:29 on `R58X30GS9QJ` and the five tests above all missed:
        // none of them ever opened the screen that re-probes. Here the production path is walked in
        // full - the marker a previous process left is on disk, Google's cache still holds the
        // drive.file grant, a fresh view model starts unlinked, and the real SettingsScreen opens,
        // which fires `refreshDriveConnection()`. The shadow below would hand back a token if asked,
        // so without the stored decision gating the probe the card flips back to "connected".
        authPrefs.edit().putBoolean("drive_disconnected", true).commit()
        val viewModel = newViewModel()
        compose.setContent { MaterialTheme { SettingsScreen(viewModel) } }

        // Give a launched probe its full chance to reach Google and back. There is no passing state
        // to wait for here - with the fix nothing happens at all - so patience is plain wall-clock:
        // pump the main looper toward a probe for two seconds. If the guard were missing, the
        // shadowed token call would land on the IO thread and its answer would flip the card back on
        // the main looper, both of which these spins drive. Landing on zero after the full window is
        // the only answer the fix allows.
        val askedGoogle = probeWindowPassed {
            GoogleTokenShadow.requests.get() > 0 || viewModel.driveLinked.value
        }
        assertFalse("the settings screen re-probed Google over the stored decision", askedGoogle)
        assertEquals("a disconnected process must never ask Google again",
            0, GoogleTokenShadow.requests.get())
        assertFalse("the stored decision must survive the screen that re-probes",
            viewModel.driveLinked.value)
        compose.onNodeWithTag("drive_connect_btn").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("drive_backup_now").assertDoesNotExist()
    }

    /**
     * Pumps the main looper for a bounded wall-clock window while [leaked] is false and reports
     * whether it ever turned true. Deliberately not `compose.waitUntil`: that helper exists to prove
     * something *did* happen (see `DriveGuestCardTest.awaitProbe`) and throws when it does not, while
     * this assertion needs the opposite - run out the full window proving nothing ever reached Google.
     */
    private fun probeWindowPassed(leaked: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + 2_000L
        while (System.currentTimeMillis() < deadline) {
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(50))
            compose.waitForIdle()
            if (leaked()) return true
        }
        return leaked()
    }

    /** The real Google Play services cache file, seeded the way `DriveReconnectTest` seeds it. */
    private fun seedCachedGoogleGrant() {
        application.getSharedPreferences(SIGNIN_PREFS, Context.MODE_PRIVATE)
            .edit().clear()
            .putString("defaultGoogleSignInAccount", ACCOUNT_KEY)
            .putString(
                "googleSignInAccount:$ACCOUNT_KEY",
                """{"id":"$ACCOUNT_KEY","email":"$SEED_EMAIL","displayName":"Disconnect Restart Test",""" +
                    """"expirationTime":4102444800,"obfuscatedIdentifier":"$ACCOUNT_KEY",""" +
                    """"grantedScopes":["${DriveAuth.SCOPE}"]}"""
            )
            .commit()
        // Google parses that file once per process and keeps the result, so both singletons holding it
        // must be dropped before anything reads it: otherwise this test walks whichever cache the first
        // class to ask left behind, and the full suite and a solo run disagree.
        resetGoogleAccountCache()
    }

    /**
     * The preference files the card reads: Google's own grant cache, Drive's answer, the app session -
     * and the two process-wide caches Google keeps over them. Cleared in `@Before` and `@After`, the
     * way `DriveGuestCardTest` does, or this class turns the full suite red while passing on its own.
     */
    private fun clearAccountState() {
        application.getSharedPreferences(SIGNIN_PREFS, Context.MODE_PRIVATE).edit().clear().commit()
        application.getSharedPreferences(DRIVE_BACKUP_PREFS, Context.MODE_PRIVATE).edit().clear().commit()
        application.getSharedPreferences(AUTH_PREFS, Context.MODE_PRIVATE).edit().clear().commit()
        resetGoogleAccountCache()
    }

    /**
     * Google's two process-wide caches - the account holder (`zbn`) and the `Storage` it parsed from -
     * reset exactly the way `DriveGuestCardTest.resetGoogleAccountCache()` does. The obfuscated names
     * are spelled out so a library update that drops a field fails the fixture loudly.
     */
    private fun resetGoogleAccountCache() {
        ReflectionHelpers.setStaticField(GOOGLE_ACCOUNT_HOLDER, GOOGLE_ACCOUNT_HOLDER_FIELD, null)
        ReflectionHelpers.setStaticField(GOOGLE_STORAGE, GOOGLE_STORAGE_FIELD, null)
    }

    /**
     * Google's blocking token request, shadowed the way `DriveGuestCardTest.GoogleTokenShadow` is -
     * except this one answers instead of refusing: a fresh process whose probe is not gated would get
     * a token back for the cached `drive.file` grant and the card would flip to "connected". The call
     * count is the load-bearing assertion of the restart test: the fix is not "the answer is wrong",
     * it is "this process never asks the question".
     */
    @Implements(value = GoogleAuthUtil::class, isInAndroidSdk = false)
    class GoogleTokenShadow {
        companion object {
            val requests = AtomicInteger()

            @JvmStatic
            @Resetter
            fun reset() {
                requests.set(0)
            }

            @JvmStatic
            @Implementation
            @Suppress("UNUSED_PARAMETER")
            fun getToken(context: Context, account: Account, scope: String): String {
                requests.incrementAndGet()
                // Google would happily mint for the cached grant - exactly what the phone showed at
                // 18:29 - so the probe must never be allowed to reach this line.
                return "cached-drive-file-token"
            }
        }
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

        const val SEED_EMAIL = "drive-disconnect-restart@example.invalid"
        const val NEW_SESSION_EMAIL = "next-session@example.invalid"
        const val ACCOUNT_KEY = "disconnect-restart-account"
        const val APP_SESSION_ID = "app-session-under-test"
    }
}
