package com.example

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.data.secure.PlainKeyStorage
import com.example.data.secure.shouldEraseLegacy
import com.example.data.secure.valueAfterMigration
import com.example.ui.viewmodel.MainViewModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The keys the owner types in are the app's only secrets of its own. They sat in clear in
 * `aichat_auth_prefs.xml` (MainViewModel.kt:1557, :1578), where a rooted handset, a `run-as` shell on
 * a debug build, or any forensic image reads them without asking the app. This file holds the rule
 * that moves them, and the rule that an old, already-typed key is not lost by the move.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class StoredKeyTest {

    private lateinit var application: Application

    @Before
    fun setUp() {
        application = ApplicationProvider.getApplicationContext()
        application.getSharedPreferences("aichat_auth_prefs", Context.MODE_PRIVATE)
            .edit().clear().commit()
        application.getSharedPreferences("aichat_secret_keys", Context.MODE_PRIVATE)
            .edit().clear().commit()
    }

    @Test
    fun theStoredCopyWinsOverTheOldClearCopy() {
        assertEquals("new", valueAfterMigration(stored = "new", legacy = "old"))
        assertEquals("old", valueAfterMigration(stored = "", legacy = "old"))
        assertEquals("", valueAfterMigration(stored = "", legacy = ""))
    }

    @Test
    fun theClearCopyIsDroppedOnlyWhenSomethingElseHoldsTheValue() {
        assertTrue(shouldEraseLegacy(legacy = "old", stored = "new"))
        assertTrue(shouldEraseLegacy(legacy = "old", stored = "old"))
        assertFalse("never erase the only copy there is", shouldEraseLegacy(legacy = "old", stored = ""))
    }

    @Test
    fun anOldKeyTypedBeforeThisBuildSurvivesTheMove() {
        val clear = PlainKeyStorage(application)
        clear.write("gemini_api_key", "typed-long-before")
        val secrets = InMemoryKeyStorage()

        // The same two rules the view model runs, in the order it runs them.
        val value = valueAfterMigration(secrets.read("gemini_api_key"), clear.read("gemini_api_key"))
        if (shouldEraseLegacy(clear.read("gemini_api_key"), value)) {
            secrets.write("gemini_api_key", value)
            clear.write("gemini_api_key", "")
        }

        assertEquals("typed-long-before", secrets.read("gemini_api_key"))
        assertEquals("", clear.read("gemini_api_key"))
    }

    @Test
    fun writingThroughTheModelLeavesNothingInClearPrefs() {
        val memory = InMemoryKeyStorage()
        val model = MainViewModel(application, FakeAccountService(), memory)

        model.setCustomGeminiApiKey("  secret-value  ")

        assertEquals("secret-value", memory.read("gemini_api_key"))
        val clearPrefs = application.getSharedPreferences("aichat_auth_prefs", Context.MODE_PRIVATE)
        assertFalse("the clear file must not keep the key", clearPrefs.contains("gemini_api_key"))
        assertFalse(
            "no clear file may hold the value either",
            clearPrefs.all.values.any { entry -> entry.toString().contains("secret-value") }
        )
    }

    @Test
    fun theModelStillReadsAnOldClearKeyThroughTheSameDoor() {
        PlainKeyStorage(application).write("reader_api_key", "from-the-old-build")

        val model = MainViewModel(application, FakeAccountService(), InMemoryKeyStorage())

        assertEquals("from-the-old-build", model.getReaderApiKey())
    }

    @Test
    fun clearingTheKeyClearsItEverywhere() {
        val memory = InMemoryKeyStorage()
        memory.write("reader_api_key", "to-be-removed")
        val model = MainViewModel(application, FakeAccountService(), memory)

        model.setReaderApiKey("")

        assertEquals("", memory.read("reader_api_key"))
    }
}
