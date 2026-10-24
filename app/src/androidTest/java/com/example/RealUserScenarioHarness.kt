package com.example

import android.content.Intent
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import java.io.File

object RealUserScenarioHarness {
    const val PINNED_SERIAL = "emulator-5554"
    const val PACKAGE = "com.aistudio.aichatexporter.kxrtva"
    const val REF_LINK = "https://chatgpt.com/share/6adc95e5-031c-83ea-958d-3a70bfe98720"

    fun device(): UiDevice = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())

    fun dumpUi(tag: String): File {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val dir = ctx.cacheDir ?: ctx.filesDir
        dir.mkdirs()
        val out = File(dir, "$tag.xml")
        try {
            device().dumpWindowHierarchy(out)
        } catch (_: Exception) {
            try {
                device().executeShellCommand("uiautomator dump ${out.absolutePath}")
            } catch (_: Exception) {
                // ignore
            }
        }
        return out
    }

    fun waitForText(text: String, timeoutMs: Long = 8000): Boolean =
        device().wait(Until.hasObject(By.text(text)), timeoutMs)

    fun preflight(): Boolean {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        try {
            ctx.packageManager.getPackageInfo(PACKAGE, 0)
        } catch (_: Exception) {
            return false
        }
        val cacheWritable = try { ctx.cacheDir.canWrite() } catch (_: Exception) { false }
        val downloadWritable = try {
            val dl = android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS)
            dl.exists() || dl.mkdirs() || dl.canWrite()
        } catch (_: Exception) { false }
        return cacheWritable || downloadWritable
    }

    fun launchApp() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val pm = ctx.packageManager
        val intent = pm.getLaunchIntentForPackage(PACKAGE)
        if (intent != null) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            ctx.startActivity(intent)
        } else {
            device().pressHome()
            device().executeShellCommand("am start -n $PACKAGE/.MainActivity")
        }
    }

    fun isGuestMode(): Boolean =
        device().wait(Until.hasObject(By.text("مهمان")), 1500)

    fun importLink(link: String) {
        // stub for R-S3: paste link and tap Extract
    }
}
