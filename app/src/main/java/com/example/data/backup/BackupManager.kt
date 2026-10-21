package com.example.data.backup

import android.content.Context
import com.example.data.local.AppDatabase
import com.example.data.remote.drive.DriveClient
import com.example.domain.backup.BackupFormat
import com.example.domain.backup.BackupPayload
import com.example.domain.backup.planRestore
import com.example.domain.exporter.DocumentExporter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.time.ZoneId

/**
 * One backup, one file, one folder. The manager owns the sequence — read the library, write it as one
 * JSON, put it in Drive — and the restore rule that must never cost the user a conversation: a file
 * replaces a local session only when it is strictly newer, and a session the file has never seen is
 * left untouched.
 */
internal class BackupManager(
    context: Context,
    private val db: AppDatabase,
    private val drive: DriveClient = DriveClient()
) {
    data class Outcome(val ok: Boolean, val message: String)

    private val prefs = context.getSharedPreferences("drive_backup", Context.MODE_PRIVATE)

    fun lastBackupDate(): String? = prefs.getString(KEY_LAST_DATE, null)

    /** The automatic run happens on the first open of a local calendar day. */
    fun isDue(now: Long = System.currentTimeMillis()): Boolean =
        BackupFormat.shouldAutoBackUp(lastBackupDate(), BackupFormat.localDate(now, ZoneId.systemDefault()))

    fun rememberRun(now: Long = System.currentTimeMillis()) {
        prefs.edit()
            .putString(KEY_LAST_DATE, BackupFormat.localDate(now, ZoneId.systemDefault()))
            .apply()
    }

    suspend fun backUp(token: String): Outcome = withContext(Dispatchers.IO) {
        val folderId = drive.ensureFolder(token, BackupFormat.FOLDER_NAME)
            ?: return@withContext Outcome(false, "پوشهٔ درایو ساخته نشد. اینترنت و اجازهٔ دسترسی را بررسی کن.")

        val sessions = db.chatDao().getAllSessions().first()
        val messages = sessions.flatMap { db.chatDao().getMessagesForSessionSync(it.id) }
        val distillations = sessions.flatMap { db.distillationDao().getDistillationsForSessionSync(it.id) }
        val records = db.exportDao().getAllExportRecords().first()
        val json = BackupPayload(sessions, messages, distillations, records).toJson()
        val name = BackupFormat.fileName()

        if (!drive.upload(token, folderId, name, json)) {
            return@withContext Outcome(false, "پشتیبان‌گیری انجام نشد؛ هیچ فایلی در درایو ساخته نشد.")
        }
        rememberRun()
        val size = DocumentExporter.formatFileSize(json.toByteArray(Charsets.UTF_8).size.toLong())
        Outcome(true, "پشتیبان در پوشهٔ ${BackupFormat.FOLDER_NAME} ذخیره شد: $name ($size).")
    }

    suspend fun restoreLatest(token: String): Outcome = withContext(Dispatchers.IO) {
        val folderId = drive.ensureFolder(token, BackupFormat.FOLDER_NAME)
            ?: return@withContext Outcome(false, "پوشهٔ درایو ساخته نشد. اینترنت و اجازهٔ دسترسی را بررسی کن.")

        val file = drive.newestBackup(token, folderId, BackupFormat.PREFIX)
            ?: return@withContext Outcome(false, "هیچ پشتیبانی در پوشهٔ درایو پیدا نشد.")
        val raw = drive.download(token, file.id)
            ?: return@withContext Outcome(false, "فایل پشتیبان دریافت نشد؛ چیزی روی گوشی تغییر نکرد.")
        val payload = BackupPayload.fromJson(raw)
            ?: return@withContext Outcome(false, "فایل پشتیبان قابل خواندن نبود؛ چیزی روی گوشی تغییر نکرد.")

        val localUpdatedAt = db.chatDao().getAllSessions().first().associate { it.id to it.updatedAt }
        val toWrite = planRestore(payload) { id -> localUpdatedAt[id] }
        toWrite.forEach { session ->
            db.chatDao().saveFullSession(session, payload.messages.filter { it.sessionId == session.id })
            payload.distillations.filter { it.sessionId == session.id }
                .forEach { db.distillationDao().insertDistillation(it) }
        }
        val restored = toWrite.size
        // Export records are history, not conversation: a row that already exists stays as it is.
        payload.exportRecords.forEach { record -> runCatching { db.exportDao().insertExportRecord(record) } }

        if (restored == 0) {
            Outcome(false, "فایلِ پشتیبان از گفتگوهای گوشی تو تازه‌تر نبود؛ چیزی تغییر نکرد.")
        } else {
            Outcome(true, "از پشتیبانِ ${file.name}، $restored گفتگو بازگردانی شد.")
        }
    }

    private companion object {
        const val KEY_LAST_DATE = "last_backup_date"
    }
}
