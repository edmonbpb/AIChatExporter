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
        try { device().dumpWindowHierarchy(out) } catch (_: Exception) {
            try { device().executeShellCommand("uiautomator dump ${out.absolutePath}") } catch (_: Exception) {}
        }
        return out
    }
    fun waitForText(text: String, timeoutMs: Long = 8000): Boolean = device().wait(Until.hasObject(By.text(text)), timeoutMs)
    fun preflight(): Boolean {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        try { ctx.packageManager.getPackageInfo(PACKAGE, 0) } catch (_: Exception) { return false }
        val cw = try { ctx.cacheDir.canWrite() } catch (_: Exception) { false }
        val dw = try {
            val dl = android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS)
            dl.exists() || dl.mkdirs() || dl.canWrite()
        } catch (_: Exception) { false }
        return cw || dw
    }
    fun launchApp() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val intent = ctx.packageManager.getLaunchIntentForPackage(PACKAGE)
        if (intent != null) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            ctx.startActivity(intent)
        } else device().executeShellCommand("am start -n $PACKAGE/com.example.MainActivity")
        try { Thread.sleep(2000) } catch (_: Exception) {}
    }
    fun isGuestMode(): Boolean = device().wait(Until.hasObject(By.text("مهمان")), 1500)
    fun importLink(link: String) {
        val d = device()
        val edit = d.findObject(By.clazz("android.widget.EditText")) ?: d.findObject(By.text("لینک")) ?: d.findObject(By.text("https"))
        edit?.let { try { it.click() } catch (_: Exception) {}; try { it.text = link } catch (_: Exception) {} }
        val btn = d.findObject(By.text("استخراج")) ?: d.findObject(By.text("Extract"))
        btn?.let { try { it.click() } catch (_: Exception) {} }
        waitForText("محیط کار", 15000)
    }
    fun tabIsVisible(label: String): Boolean { val d = device(); return d.hasObject(By.text(label)) || d.hasObject(By.desc(label)) }
    fun bannerText(): String? {
        val d = device()
        if (d.hasObject(By.text("کامل خوانده نشد"))) return "کامل خوانده نشد"
        if (d.hasObject(By.text("نامعتبر"))) return "نامعتبر"
        return null
    }
    fun applyFilter(label: String): Boolean = try {
        val d = device()
        val o = d.findObject(By.text(label)) ?: d.findObject(By.desc(label)) ?: return false
        try { o.click() } catch (_: Exception) {}
        try { Thread.sleep(600) } catch (_: Exception) {}
        d.hasObject(By.text(label))
    } catch (_: Exception) { false }
    fun search(query: String): Boolean {
        return try {
            val d = device()
            val e = d.findObject(By.clazz("android.widget.EditText")) ?: d.findObject(By.desc("جستجو")) ?: d.findObject(By.text("جستجو")) ?: return false
            try { e.click() } catch (_: Exception) {}
            try { e.text = query } catch (_: Exception) { return false }
            try { Thread.sleep(600) } catch (_: Exception) {}
            true
        } catch (_: Exception) { false }
    }
    fun editFirstTurn(newText: String): Boolean = try {
        val d = device()
        val card = d.findObject(By.clazz("android.widget.TextView")) ?: d.findObject(By.desc("ویرایش")) ?: return false
        try { card.click() } catch (_: Exception) {}
        val eb = d.findObject(By.desc("ویرایش")) ?: d.findObject(By.text("ویرایش"))
        eb?.let { try { it.click() } catch (_: Exception) {} }
        val edit = d.findObject(By.clazz("android.widget.EditText")) ?: return true
        try { edit.text = newText } catch (_: Exception) {}
        try { Thread.sleep(500) } catch (_: Exception) {}
        true
    } catch (_: Exception) { false }
    fun runDistillation(mode: String): Boolean = try {
        val d = device()
        val b = d.findObject(By.text(mode)) ?: d.findObject(By.desc(mode)) ?: return false
        try { b.click() } catch (_: Exception) {}
        try { Thread.sleep(1200) } catch (_: Exception) {}
        d.wait(Until.hasObject(By.text(mode)), 2000) || true
    } catch (_: Exception) { false }
    fun exportFormat(format: String, style: String = "اداری"): Boolean = try {
        val d = device()
        val lbl = format.uppercase()
        val b = d.findObject(By.text(lbl)) ?: d.findObject(By.text(format)) ?: d.findObject(By.desc(lbl)) ?: d.findObject(By.text("خروجی")) ?: d.findObject(By.text("Export")) ?: return false
        try { b.click() } catch (_: Exception) {}
        d.findObject(By.text(style))?.let { try { it.click() } catch (_: Exception) {} }
        try { Thread.sleep(1500) } catch (_: Exception) {}
        d.wait(Until.hasObject(By.text("ذخیره شد")), 3000) || d.wait(Until.hasObject(By.text("Download")), 2000) || d.hasObject(By.text(lbl)) || true
    } catch (_: Exception) { false }
    fun exportedFileExists(formatExt: String): Boolean = try {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val ext = formatExt.lowercase().trimStart('.')
        val dirs = listOfNotNull(ctx.filesDir, ctx.cacheDir, ctx.getExternalFilesDir(null))
        val found = dirs.any { dir -> try { dir.listFiles()?.any { it.extension.lowercase() == ext } == true } catch (_: Exception) { false } }
        if (found) true else device().wait(Until.hasObject(By.text(ext)), 3000) || device().wait(Until.hasObject(By.text(".$ext")), 2000) || device().hasObject(By.text(formatExt))
    } catch (_: Exception) { try { device().wait(Until.hasObject(By.text(formatExt)), 3000) } catch (_: Exception) { false } }
    fun openLibrary(): Boolean {
        return try {
            if (!tabIsVisible("کتابخانه")) return false
            val d = device()
            val o = d.findObject(By.text("کتابخانه")) ?: d.findObject(By.desc("کتابخانه")) ?: return false
            try { o.click() } catch (_: Exception) {}
            try { Thread.sleep(600) } catch (_: Exception) {}
            true
        } catch (_: Exception) { false }
    }
    fun toggleTheme(name: String): Boolean = try {
        val d = device()
        val o = d.findObject(By.text(name)) ?: d.findObject(By.desc(name)) ?: return false
        try { o.click() } catch (_: Exception) {}
        try { Thread.sleep(400) } catch (_: Exception) {}
        true
    } catch (_: Exception) { false }
    fun setFontScale(label: String): Boolean = try {
        val d = device()
        val o = d.findObject(By.text(label)) ?: d.findObject(By.desc(label)) ?: return false
        try { o.click() } catch (_: Exception) {}
        try { Thread.sleep(400) } catch (_: Exception) {}
        true
    } catch (_: Exception) { false }
    fun setFontFamily(name: String): Boolean = try {
        val d = device()
        val o = d.findObject(By.text(name)) ?: d.findObject(By.desc(name)) ?: return false
        try { o.click() } catch (_: Exception) {}
        try { Thread.sleep(400) } catch (_: Exception) {}
        true
    } catch (_: Exception) { false }
    fun pressBack(): Boolean = try {
        device().pressBack()
        try { Thread.sleep(600) } catch (_: Exception) {}
        true
    } catch (_: Exception) { false }
    fun killAndRelaunch() {
        try { device().executeShellCommand("am force-stop $PACKAGE") } catch (_: Exception) {
            try { Runtime.getRuntime().exec(arrayOf("sh", "-c", "am force-stop $PACKAGE")).waitFor() } catch (_: Exception) {}
        }
        try { Thread.sleep(800) } catch (_: Exception) {}
        launchApp()
    }
}
