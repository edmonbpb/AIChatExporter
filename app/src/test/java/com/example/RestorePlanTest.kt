package com.example

import com.example.data.local.AIDistillationEntity
import com.example.data.local.ChatMessageEntity
import com.example.data.local.ChatSessionEntity
import com.example.data.local.ExportRecordEntity
import com.example.domain.backup.BackupPayload
import com.example.domain.backup.planRestore
import org.junit.Assert.assertEquals
import org.junit.Test

/** The restore may only overwrite what is older than the file; everything else is left alone. */
class RestorePlanTest {

    private fun session(id: String, updatedAt: Long) = ChatSessionEntity(
        id = id, title = id, platformId = "CHATGPT", modelName = "gpt-4o",
        createdAt = 1L, updatedAt = updatedAt, tagsJson = "[]"
    )

    private fun payload(vararg sessions: ChatSessionEntity) = BackupPayload(
        sessions = sessions.toList(),
        messages = emptyList<ChatMessageEntity>(),
        distillations = emptyList<AIDistillationEntity>(),
        exportRecords = emptyList<ExportRecordEntity>()
    )

    @Test
    fun aSessionMissingOnThePhoneIsRestored() {
        val plan = planRestore(payload(session("new", 50))) { null }
        assertEquals(listOf("new"), plan.map { it.id })
    }

    @Test
    fun anOlderLocalSessionIsReplacedByTheFile() {
        val plan = planRestore(payload(session("s", 50))) { 40L }
        assertEquals(listOf("s"), plan.map { it.id })
    }

    @Test
    fun aNewerLocalSessionIsNeverOverwritten() {
        val plan = planRestore(payload(session("s", 50), session("t", 60))) { id ->
            when (id) { "s" -> 70L; else -> 60L }
        }
        assertEquals(emptyList<String>(), plan.map { it.id })
    }

    @Test
    fun aSessionTheFileDoesNotMentionIsNotTouched() {
        val plan = planRestore(payload(session("s", 50))) { id -> if (id == "local-only") 1L else null }
        assertEquals(listOf("s"), plan.map { it.id })
    }
}
