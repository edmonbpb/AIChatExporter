package com.example

import android.app.Application
import android.content.Context
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.lifecycle.viewModelScope
import androidx.test.core.app.ApplicationProvider
import com.example.ui.screens.WorkspaceScreen
import com.example.ui.viewmodel.MainViewModel
import kotlinx.coroutines.cancel
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.util.ReflectionHelpers

/**
 * «همه پیام‌ها» on the workspace: the chip has to hand back the whole conversation.
 *
 * The screen a user reaches that chip from is always a narrowed one — a search word, a role chip, or
 * both — so the fixture is built exactly that way: three turns of a real parse, then a search that
 * matches two of them, then the assistant filter that leaves one. Clicking the chip has to clear the
 * search and all three role filters and bring the two hidden turns back on screen; anything less
 * leaves the user looking at a filtered list with no visible reason for it.
 *
 * The conversation is built through `parseAndLoadTranscript`, the path a pasted file takes, so the
 * screen is fed the same [com.example.data.model.ChatSession] production builds — Room is not used.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@LooperMode(LooperMode.Mode.PAUSED)
class AllMessagesChipTest {
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
            compose.runOnIdle { viewModel.viewModelScope.cancel() }
        }
        if (::application.isInitialized) {
            clearAccountState()
        }
    }

    @Test
    fun theAllMessagesChipClearsTheSearchAndEveryRoleFilter() {
        viewModel = MainViewModel(application)
        viewModel.rawInputText.value = threeTurnConversation()
        viewModel.parseAndLoadTranscript()
        // Parsing runs on Dispatchers.Default; the turns are the precondition, not something to sleep on.
        compose.waitUntil(timeoutMillis = 5_000) {
            viewModel.activeSession.value?.messages?.size == 3
        }

        compose.setContent { MaterialTheme { WorkspaceScreen(viewModel) } }

        // Narrowed the way a user narrows it: the search word alone. The first turn still matches, so
        // the list begins with the user's card, and the third turn is not in the list at all.
        compose.runOnIdle { viewModel.searchQuery.value = SEARCH_WORD }
        assertEquals(SEARCH_WORD, viewModel.searchQuery.value)
        compose.onNodeWithTag("message_turn_0_user").assertIsDisplayed()
        assertTurnIsNotInTheList(THIRD_TURN)

        // One role chip on top of that leaves exactly one turn: the assistant's. The user's matching
        // turn is gone from the list's first position, which is what a hidden turn looks like here.
        compose.runOnIdle { viewModel.filterOnlyAssistant.value = true }
        assertTrue("fixture: the assistant filter is on", viewModel.filterOnlyAssistant.value)
        assertFalse("fixture: only one role filter is on", viewModel.filterOnlyUser.value)
        compose.onNodeWithTag("message_turn_0_assistant").assertIsDisplayed()
        compose.onNodeWithTag("message_turn_0_user").assertDoesNotExist()
        compose.onNodeWithText(FIRST_TURN).assertDoesNotExist()
        assertTurnIsNotInTheList(FIRST_TURN)
        assertTurnIsNotInTheList(THIRD_TURN)

        // The chip sits fourth in a horizontally scrolling row, so it may start off-screen.
        compose.onNodeWithTag("all_messages_chip").performScrollTo().performClick()

        // The state the chip is responsible for, read first so a broken chip fails with a named value.
        assertEquals("the search box is emptied", "", viewModel.searchQuery.value)
        assertFalse("the code chip is off", viewModel.filterOnlyCode.value)
        assertFalse("the user chip is off", viewModel.filterOnlyUser.value)
        assertFalse("the assistant chip is off", viewModel.filterOnlyAssistant.value)
        assertEquals("the chip filters, it never deletes", 3, viewModel.activeSession.value?.messages?.size)

        // And every turn of the conversation is back on the screen, walked to one by one so the
        // assertion is about the list's content, not about how many cards the viewport happens to
        // hold. The same walk is what came back empty above, so a walk that could not find anything at
        // all would fail here first instead of making those absences look proven.
        val messageList = compose.onNode(hasScrollToIndexAction())
        messageList.performScrollToNode(hasText(FIRST_TURN))
        compose.onNodeWithText(FIRST_TURN).assertIsDisplayed()
        compose.onNodeWithTag("message_turn_0_user").assertIsDisplayed()
        messageList.performScrollToNode(hasText(SECOND_TURN))
        compose.onNodeWithText(SECOND_TURN).assertIsDisplayed()
        messageList.performScrollToNode(hasText(THIRD_TURN))
        compose.onNodeWithText(THIRD_TURN).assertIsDisplayed()
    }

    /**
     * Says «this turn is not in the message list» by walking the list to its end and expecting the
     * finder to come back empty: `performScrollToNode` throws `AssertionError` («No node found that
     * matches») exactly then. Merely checking that a card is not composed would say nothing, because a
     * lazy list leaves cards out of the tree all the time - this asks the list itself.
     */
    private fun assertTurnIsNotInTheList(text: String) {
        assertThrows(
            "a filtered-out turn is still reachable in the message list",
            AssertionError::class.java
        ) {
            compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText(text))
        }
    }

    /**
     * The AIChat object [MainViewModel.parseAndLoadTranscript] reads: three turns, no digits and no
     * Persian word that [com.example.domain.bidi.BidiEngine.normalizePersianTypography] would glue to
     * its neighbour, so the text the cards render is byte-for-byte the text asserted here.
     */
    private fun threeTurnConversation(): String = JSONObject().apply {
        put("title", "گزارش فروش")
        put("platform", "custom")
        put("messages", JSONArray().apply {
            put(JSONObject().put("role", "user").put("content", FIRST_TURN))
            put(JSONObject().put("role", "assistant").put("content", SECOND_TURN))
            put(JSONObject().put("role", "user").put("content", THIRD_TURN))
        })
    }.toString()

    /**
     * The three preference files a [MainViewModel] reads, plus the two process-wide caches Google keeps
     * over them — the same fixture `DriveGuestCardTest` clears, for the same reason: Robolectric runs
     * every test class in one JVM, and a cache left by another class would answer for this one.
     */
    private fun clearAccountState() {
        application.getSharedPreferences(SIGNIN_PREFS, Context.MODE_PRIVATE).edit().clear().commit()
        application.getSharedPreferences(DRIVE_BACKUP_PREFS, Context.MODE_PRIVATE).edit().clear().commit()
        application.getSharedPreferences(AUTH_PREFS, Context.MODE_PRIVATE).edit().clear().commit()
        resetGoogleAccountCache()
    }

    private fun resetGoogleAccountCache() {
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

        // The search word is in the first two turns and not in the third, so the role chip is the only
        // thing that can take the list down to one card.
        const val SEARCH_WORD = "گزارش"

        const val FIRST_TURN = "گزارش فروش آماده شد"
        const val SECOND_TURN = "گزارش فروش را کوتاه کردم"
        const val THIRD_TURN = "برنامه فردا چیست"
    }
}
