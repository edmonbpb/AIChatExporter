package com.example.domain.backup

import java.text.SimpleDateFormat
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Date
import java.util.Locale

/**
 * The rules a backup has to follow before a single byte leaves the phone: what the file is called,
 * which calendar day it belongs to, and whether a local row or the backup wins.
 *
 * Pure on purpose: the merge decision is the one that can destroy a newer conversation, so it is a
 * function a JVM test can drive directly.
 */
internal object BackupFormat {

    const val PREFIX = "aichatexporter-backup-"
    const val EXTENSION = ".json"
    const val FOLDER_NAME = "AIChatExporter"
    const val VERSION = 1

    private val FILE_STAMP = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US)
    private val DAY = DateTimeFormatter.ofPattern("yyyy-MM-dd", Locale.US)

    /** `aichatexporter-backup-20261020-083015.json` — one file per run, sortable by name. */
    fun fileName(now: Long = System.currentTimeMillis()): String =
        PREFIX + FILE_STAMP.format(Date(now)) + EXTENSION

    /** The calendar day in the user's own zone, because "once a day" means his day, not UTC's. */
    fun localDate(now: Long, zone: ZoneId): String =
        DAY.format(Instant.ofEpochMilli(now).atZone(zone))

    /**
     * The automatic run happens on the first app open of a new calendar day. A missing date (a fresh
     * install, or a cleared preference) counts as never backed up.
     */
    fun shouldAutoBackUp(lastBackupDate: String?, today: String): Boolean =
        lastBackupDate.isNullOrBlank() || lastBackupDate != today

    /**
     * A restore never deletes and never guesses: the backup's session replaces the local one only when
     * it is strictly newer. A local session the backup has never seen is left alone.
     */
    fun backupWins(localUpdatedAt: Long?, backupUpdatedAt: Long): Boolean =
        localUpdatedAt == null || backupUpdatedAt > localUpdatedAt

    /** A file only becomes a candidate when it carries this app's backup name. */
    fun isBackupFile(name: String?): Boolean =
        name != null && name.startsWith(PREFIX) && name.endsWith(EXTENSION)
}
