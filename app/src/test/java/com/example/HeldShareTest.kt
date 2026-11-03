package com.example

import android.app.Application
import android.content.Context
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.core.app.ApplicationProvider
import com.example.ui.viewmodel.MainViewModel
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
 * A share that arrives while a gate is up is not dropped and not applied: it is held for the one
 * screen that may take it, and it is taken exactly once.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class HeldShareTest {

    @get:Rule
    val composeRule = createComposeRule()

    private lateinit var application: Application

    @Before
    fun setUp() {
        application = ApplicationProvider.getApplicationContext()
        application.getSharedPreferences("aichat_auth_prefs", Context.MODE_PRIVATE)
            .edit().clear().commit()
    }

    @Test
    fun aHeldShareIsKeptUntilSomethingFlushesIt() {
        val model = MainViewModel(application, FakeAccountService(), InMemoryKeyStorage())
        assertFalse("nothing is held at the start", model.heldShare.value != null)

        model.holdIncomingShare("hello from another app")

        assertTrue(model.heldShare.value != null)
        assertEquals("hello from another app", model.heldShare.value)
        assertTrue("the flush takes it", model.flushHeldShare())
        assertFalse("and it is gone after the flush", model.heldShare.value != null)
    }

    @Test
    fun anEmptyShareIsNeverHeld() {
        val model = MainViewModel(application, FakeAccountService(), InMemoryKeyStorage())
        model.holdIncomingShare("   ")
        assertFalse(model.heldShare.value != null)
        assertFalse(model.flushHeldShare())
    }

    @Test
    fun theSecondFlushFindsNothing() {
        val model = MainViewModel(application, FakeAccountService(), InMemoryKeyStorage())
        model.holdIncomingShare("text")
        assertTrue(model.flushHeldShare())
        assertFalse(model.flushHeldShare())
    }
}
