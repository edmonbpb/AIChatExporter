package com.example.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
interface ChatDao {

    @Query("SELECT * FROM chat_sessions ORDER BY updatedAt DESC")
    fun getAllSessions(): Flow<List<ChatSessionEntity>>

    @Query("SELECT * FROM chat_sessions WHERE id = :sessionId")
    suspend fun getSessionById(sessionId: String): ChatSessionEntity?

    @Query("SELECT * FROM chat_messages WHERE sessionId = :sessionId ORDER BY orderIndex ASC")
    fun getMessagesForSession(sessionId: String): Flow<List<ChatMessageEntity>>

    @Query("SELECT * FROM chat_messages WHERE sessionId = :sessionId ORDER BY orderIndex ASC")
    suspend fun getMessagesForSessionSync(sessionId: String): List<ChatMessageEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSession(session: ChatSessionEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMessages(messages: List<ChatMessageEntity>)

    @Query("UPDATE chat_sessions SET isBookmarked = :isBookmarked WHERE id = :sessionId")
    suspend fun setBookmark(sessionId: String, isBookmarked: Boolean)

    @Query("UPDATE chat_sessions SET title = :newTitle, updatedAt = :updatedAt WHERE id = :sessionId")
    suspend fun updateTitle(sessionId: String, newTitle: String, updatedAt: Long = System.currentTimeMillis())

    @Query("DELETE FROM chat_sessions WHERE id = :sessionId")
    suspend fun deleteSession(sessionId: String)

    @Query("DELETE FROM chat_messages WHERE sessionId = :sessionId")
    suspend fun deleteMessagesForSession(sessionId: String)

    @Transaction
    suspend fun saveFullSession(session: ChatSessionEntity, messages: List<ChatMessageEntity>) {
        insertSession(session)
        deleteMessagesForSession(session.id)
        insertMessages(messages)
    }
}

@Dao
interface DistillationDao {

    @Query("SELECT * FROM ai_distillations WHERE sessionId = :sessionId ORDER BY createdAt DESC")
    fun getDistillationsForSession(sessionId: String): Flow<List<AIDistillationEntity>>

    @Query("SELECT * FROM ai_distillations WHERE sessionId = :sessionId ORDER BY createdAt DESC")
    suspend fun getDistillationsForSessionSync(sessionId: String): List<AIDistillationEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertDistillation(distillation: AIDistillationEntity)

    @Query("DELETE FROM ai_distillations WHERE id = :id")
    suspend fun deleteDistillation(id: String)
}

@Dao
interface ExportDao {

    @Query("SELECT * FROM export_records ORDER BY exportedAt DESC")
    fun getAllExportRecords(): Flow<List<ExportRecordEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertExportRecord(record: ExportRecordEntity)

    @Query("DELETE FROM export_records WHERE id = :id")
    suspend fun deleteExportRecord(id: String)
}
