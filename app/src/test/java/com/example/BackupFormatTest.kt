package com.example

import com.example.data.local.AIDistillationEntity
import com.example.data.local.ChatMessageEntity
import com.example.data.local.ChatSessionEntity
import com.example.data.local.ExportRecordEntity
import com.example.domain.backup.BackupFormat
import com.example.domain.backup.BackupPayload
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId

class BackupFormatTest {

    @Test
    fun theFileNameCarriesTheMomentItWasWritten() {
        val name = BackupFormat.fileName(0L)
        assertTrue(name.startsWith(BackupFormat.PREFIX))
        assertTrue(name.endsWith(BackupFormat.EXTENSION))
        assertTrue(BackupFormat.isBackupFile(name))
        assertFalse(BackupFormat.isBackupFile("holiday-photo.json"))
    }

    @Test
    fun theDailyRuleUsesTheLocalCalendarDay() {
        val zone = ZoneId.of("Asia/Tehran")
        val today = BackupFormat.localDate(1_760_000_000_000L, zone)
        assertTrue(BackupFormat.shouldAutoBackUp(null, today))
        assertTrue(BackupFormat.shouldAutoBackUp("2026-10-19", today))
        assertFalse(BackupFormat.shouldAutoBackUp(today, today))
    }

    @Test
    fun theBackupOnlyReplacesAnOlderLocalSession() {
        assertTrue(BackupFormat.backupWins(localUpdatedAt = null, backupUpdatedAt = 1))
        assertTrue(BackupFormat.backupWins(localUpdatedAt = 10, backupUpdatedAt = 11))
        assertFalse(BackupFormat.backupWins(localUpdatedAt = 11, backupUpdatedAt = 11))
        assertFalse(BackupFormat.backupWins(localUpdatedAt = 12, backupUpdatedAt = 11))
    }

    @Test
    fun aPayloadSurvivesTheRoundTrip() {
        val session = ChatSessionEntity(
            id = "s1", title = "a chat", platformId = "CHATGPT", modelName = "gpt-4o",
            createdAt = 1L, updatedAt = 2L, tagsJson = "[\"x\"]", isBookmarked = true, customNotes = "note"
        )
        val message = ChatMessageEntity(
            id = "m1", sessionId = "s1", role = "USER", content = "hello", timestamp = 3L,
            modelTag = null, direction = "RTL", reasoning = null, codeSnippetsJson = "[]", orderIndex = 0
        )
        val distillation = AIDistillationEntity(
            id = "d1", sessionId = "s1", type = "SUMMARY", title = "t", content = "c",
            modelUsed = "m", createdAt = 4L
        )
        val record = ExportRecordEntity(
            id = "e1", sessionId = "s1", sessionTitle = "a chat", fileName = "a.pdf",
            format = "PDF", filePath = "/x/a.pdf", fileSizeFormatted = "1 KB", exportedAt = 5L
        )

        val json = BackupPayload(listOf(session), listOf(message), listOf(distillation), listOf(record)).toJson(9L)
        val back = requireNotNull(BackupPayload.fromJson(json)) { "the payload did not decode" }

        assertEquals(listOf(session), back.sessions)
        assertEquals(listOf(message), back.messages)
        assertEquals(listOf(distillation), back.distillations)
        assertEquals(listOf(record), back.exportRecords)
    }

    @Test
    fun aFileThatIsNotABackupIsRefused() {
        assertNull(BackupPayload.fromJson("{\"format\":\"something-else\",\"version\":1}"))
        assertNull(BackupPayload.fromJson("not json at all"))
    }
}
