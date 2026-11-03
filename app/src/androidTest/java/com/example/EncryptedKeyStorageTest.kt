package com.example

import androidx.test.core.app.ApplicationProvider
import com.example.data.secure.EncryptedKeyStorage
import com.example.data.secure.GEMINI_KEY_NAME
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What no JVM test can show: that the bytes on a real handset's disk are not the key. Robolectric
 * hands out a fake keystore, so this assertion belongs on a device, and it is the only witness this
 * task has for the encryption itself.
 */
class EncryptedKeyStorageTest {

    @Test
    fun theSecretIsReadBackAndIsNotOnDiskInClear() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val store = EncryptedKeyStorage(context)
        val secret = "AIzaSy-NotARealKey-UsedOnlyForThisTest"

        store.write(GEMINI_KEY_NAME, secret)

        assertEquals(secret, store.read(GEMINI_KEY_NAME))
        val file = File(context.applicationInfo.dataDir, "shared_prefs/aichat_secret_keys.xml")
        assertTrue("the secrets file must exist: ${file.absolutePath}", file.exists())
        val raw = file.readText()
        assertFalse("the value must not be on disk in clear", raw.contains(secret))
        assertFalse("even the name is sealed", raw.contains(GEMINI_KEY_NAME))

        store.write(GEMINI_KEY_NAME, "")
        assertEquals("", store.read(GEMINI_KEY_NAME))
    }
}
