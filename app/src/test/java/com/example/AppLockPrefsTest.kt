package com.example

import android.app.Application
import android.content.Context
import androidx.lifecycle.viewModelScope
import androidx.test.core.app.ApplicationProvider
import com.example.ui.viewmodel.MainViewModel
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.util.ReflectionHelpers

/**
 * The two halves of the app lock that live on either side of a process death: `app_lock_enabled` is the
 * only part that may be written down, and `sessionUnlocked` is the part that must never be. The owner's
 * decision was that the lock asks again on every cold start, so those two must not travel together -
 * if the unlocked flag ever reached the preferences file, closing the app would stop locking it.
 *
 * Each test opens a view model the way the app does at launch, so the "next process" here is a second
 * `MainViewModel` over the same Application and the same preferences file, which is exactly what
 * `MainActivity` builds after the process has been killed and started again.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@LooperMode(LooperMode.Mode.PAUSED)
class AppLockPrefsTest {

    private lateinit var application: Application
    private val viewModels = mutableListOf<MainViewModel>()

    @Before
    fun setUp() {
        application = ApplicationProvider.getApplicationContext()
        clearAccountState()
    }

    @After
    fun tearDown() {
        viewModels.forEach { it.viewModelScope.cancel() }
        if (::application.isInitialized) {
            clearAccountState()
        }
    }

    @Test
    fun turningTheSwitchOnIsStoredAndLeavesThisProcessUnlocked() {
        val viewModel = newViewModel()
        assertFalse("fixture: the switch starts off on a fresh device", viewModel.appLockEnabled.value)

        viewModel.setAppLockEnabled(true)

        assertTrue(viewModel.appLockEnabled.value)
        assertTrue("the switch that just threw it is written down", authPrefs().getBoolean("app_lock_enabled", false))
        assertTrue(
            "turning it on happens behind the device lock that was just answered",
            viewModel.sessionUnlocked.value
        )
    }

    @Test
    fun theNextProcessReadsTheSwitchButStartsLocked() {
        newViewModel().setAppLockEnabled(true)

        val coldStart = newViewModel()

        assertTrue("the switch is a device setting, so it survives the process", coldStart.appLockEnabled.value)
        assertFalse(
            "a process that has answered nothing must be locked, whatever the last one did",
            coldStart.sessionUnlocked.value
        )
    }

    @Test
    fun signingOutDoesNotUnlockTheNextColdStart() {
        val signedIn = newViewModel()
        signedIn.setAppLockEnabled(true)

        signedIn.signOut()

        assertTrue(
            "the lock is the device's, not the session's; signing out must not remove it",
            authPrefs().getBoolean("app_lock_enabled", false)
        )
        assertTrue("and the switch on screen stays on", signedIn.appLockEnabled.value)
        assertTrue(
            "signing out is not a lock answer, so the in-memory flag stays as it was",
            signedIn.sessionUnlocked.value
        )
        assertTrue(
            "a cold start after a sign-out is locked again",
            newViewModel().appLockEnabled.value
        )
    }

    @Test
    fun turningTheSwitchOffStoresOffAndTheNextProcessReadsOff() {
        val viewModel = newViewModel()
        viewModel.setAppLockEnabled(true)

        viewModel.setAppLockEnabled(false)

        assertFalse(viewModel.appLockEnabled.value)
        // The key may be removed or written false; the `true` default makes the read fail if neither
        // happened, so this cannot stay green on a forgotten write.
        assertFalse(
            "off must be recorded, not just unread",
            authPrefs().getBoolean("app_lock_enabled", true)
        )
        assertFalse("the next launch must not lock again", newViewModel().appLockEnabled.value)
    }

    private fun newViewModel(): MainViewModel =
        MainViewModel(application).also { viewModels += it }

    private fun authPrefs() = application.getSharedPreferences(AUTH_PREFS, Context.MODE_PRIVATE)

    /**
     * The three preferences files a view model reads at construction, and Google's two process-wide
     * caches, dropped the way `DriveGuestCardTest` drops them: without this the Drive fixture of
     * another class decides what this one sees, and the class passes alone while the suite is red.
     */
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
    }
}
