package com.example

import android.content.ClipDescription
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.domain.exporter.FileShareHelper
import com.example.domain.exporter.sensitiveClip
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A copied conversation is private by definition. On 13 and newer the system hides the popup preview
 * for a clip it is told is sensitive, which is the whole ask: the text is still copied, the peek is
 * not shown to whoever is standing next to the phone.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SensitiveClipboardTest {

    @Test
    fun theClipCarriesTheSensitiveFlag() {
        val clip = sensitiveClip("AIChatExporter", "متنِ گفتگو")
        assertTrue(clip.description.extras.getBoolean(ClipDescription.EXTRA_IS_SENSITIVE))
    }

    @Test
    fun theFlagDoesNotChangeWhatIsCopied() {
        val clip = sensitiveClip("label", "the text itself")
        assertEquals(1, clip.itemCount)
        assertEquals("the text itself", clip.getItemAt(0).text.toString())
    }

    @Test
    fun theHelperStillPutsTheFlaggedClipOnTheClipboard() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        FileShareHelper.copyToClipboard(context, "AIChatExporter", "یک گفتگوی خصوصی")
        val manager = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        val clip = manager.primaryClip ?: error("nothing was copied")
        assertTrue(clip.description.extras.getBoolean(ClipDescription.EXTRA_IS_SENSITIVE))
    }
}
