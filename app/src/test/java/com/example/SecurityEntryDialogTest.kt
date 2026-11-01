package com.example

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.example.ui.navigation.shouldOfferSecurityEntry
import com.example.ui.screens.SecurityEntryDialog
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

/**
 * The popup shown when the app put the owner at the form because they asked to be out of it. Two things
 * are watched: the two buttons are the two decisions the contract names - the device lock, and another
 * account - and the lock the dialog promises is the one the caller read off the phone, never one this
 * screen guessed for itself. The real prompt (`presentUnlock`) belongs to the activity and is not
 * started here, so what is measured is the handover of a press.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@LooperMode(LooperMode.Mode.PAUSED)
class SecurityEntryDialogTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun theDialogOffersTheLockAndAWayPastIt() {
        var unlocked = false
        var choseAccount = false
        compose.setContent {
            MaterialTheme {
                SecurityEntryDialog(
                    lockLabel = FINGER_LABEL,
                    onUnlock = { unlocked = true },
                    onUseAccount = { choseAccount = true }
                )
            }
        }
        compose.onNodeWithTag(DIALOG_TAG).assertExists()
        compose.onNode(hasTestTag(UNLOCK_TAG) and hasText(UNLOCK_LABEL)).assertExists()
        compose.onNode(hasTestTag(MANUAL_TAG) and hasText(MANUAL_LABEL)).assertExists()

        compose.onNodeWithTag(UNLOCK_TAG).performClick()
        assertTrue("the lock button is the device-prompt path", unlocked)
        assertFalse("the lock button must not also hand the owner to the form", choseAccount)

        compose.onNodeWithTag(MANUAL_TAG).performClick()
        assertTrue("the second button is the way to another account", choseAccount)
    }

    @Test
    fun theDialogPromisesTheLockTheCallerNamed() {
        // Composed with the face words: the fingerprint sentence must then be nowhere on the popup. A
        // screen that baked in its own authenticator name would keep the first test green and go red
        // here, which is the only reason the name is a parameter at all.
        compose.setContent {
            MaterialTheme {
                SecurityEntryDialog(lockLabel = FACE_LABEL, onUnlock = { }, onUseAccount = { })
            }
        }
        compose.onNodeWithText(FACE_SENTENCE).assertExists()
        compose.onNodeWithText(FINGER_SENTENCE).assertDoesNotExist()
    }

    @Test
    fun theOfferIsMadeOnlyForADoorThatWasClosedOnPurpose() {
        assertTrue(
            shouldOfferSecurityEntry(
                signInRequiredOnStart = true,
                manualSignInRequested = false,
                appLockEnabled = true,
                deviceCredentialAvailable = true
            )
        )
        assertFalse(
            shouldOfferSecurityEntry(
                signInRequiredOnStart = false,
                manualSignInRequested = false,
                appLockEnabled = true,
                deviceCredentialAvailable = true
            )
        )
    }

    private companion object {
        // The popup's own words, spliced from the plan's bytes, plus the two authenticator names the
        // activity chooses between and the sentence each one builds.
        const val DIALOG_TAG = "security_entry_dialog"
        const val UNLOCK_TAG = "security_entry_unlock_btn"
        const val MANUAL_TAG = "security_entry_manual_btn"
        const val UNLOCK_LABEL = "باز کردن با قفل امنیتی"
        const val MANUAL_LABEL = "ورود با ایمیل و رمز"
        const val FINGER_LABEL = "اثر انگشت"
        const val FACE_LABEL = "چهره یا اثر انگشت"
        val FACE_SENTENCE = "با «$FACE_LABEL» می‌توانی بدون نوشتن ایمیل و رمز وارد شوی."
        val FINGER_SENTENCE = "با «$FINGER_LABEL» می‌توانی بدون نوشتن ایمیل و رمز وارد شوی."
    }
}
