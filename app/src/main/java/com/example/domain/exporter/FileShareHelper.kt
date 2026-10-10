package com.example.domain.exporter

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.core.content.FileProvider
import com.example.data.model.ExportRecord
import java.io.File

object FileShareHelper {

    fun shareFile(context: Context, record: ExportRecord) {
        try {
            val file = File(record.filePath)
            if (!file.exists()) {
                Toast.makeText(context, "فایل یافت نشد!", Toast.LENGTH_SHORT).show()
                return
            }

            val uri: Uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                file
            )

            val intent = Intent(Intent.ACTION_SEND).apply {
                type = record.format.mimeType
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, record.sessionTitle)
                putExtra(Intent.EXTRA_TEXT, "سند صادر شده از AIChatExporter: ${record.sessionTitle}")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }

            context.startActivity(Intent.createChooser(intent, "اشتراک‌گذاری سند (${record.format.displayName})"))
        } catch (e: Exception) {
            Toast.makeText(context, "خطا در اشتراک‌گذاری: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    fun openFile(context: Context, record: ExportRecord) {
        try {
            val file = File(record.filePath)
            if (!file.exists()) {
                Toast.makeText(context, "فایل وجود ندارد!", Toast.LENGTH_SHORT).show()
                return
            }

            val uri: Uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                file
            )

            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, record.format.mimeType)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }

            context.startActivity(Intent.createChooser(intent, "مشاهده سند"))
        } catch (e: Exception) {
            Toast.makeText(context, "برنامه‌ای برای باز کردن این فرمت یافت نشد.", Toast.LENGTH_LONG).show()
        }
    }

    fun copyToClipboard(context: Context, label: String, text: String) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText(label, text)
        clipboard.setPrimaryClip(clip)
        Toast.makeText(context, "متن در حافظه کپی شد ✨", Toast.LENGTH_SHORT).show()
    }
}
