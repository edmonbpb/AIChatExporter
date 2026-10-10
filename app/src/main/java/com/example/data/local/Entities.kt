package com.example.data.local

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "chat_sessions")
data class ChatSessionEntity(
    @PrimaryKey val id: String,
    val title: String,
    val platformId: String,
    val modelName: String,
    val createdAt: Long,
    val updatedAt: Long,
    val tagsJson: String,
    val isBookmarked: Boolean = false,
    val customNotes: String = ""
)

@Entity(
    tableName = "chat_messages",
    foreignKeys = [
        ForeignKey(
            entity = ChatSessionEntity::class,
            parentColumns = ["id"],
            childColumns = ["sessionId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("sessionId")]
)
data class ChatMessageEntity(
    @PrimaryKey val id: String,
    val sessionId: String,
    val role: String,
    val content: String,
    val timestamp: Long,
    val modelTag: String?,
    val direction: String,
    val reasoning: String?,
    val codeSnippetsJson: String,
    val orderIndex: Int
)

@Entity(
    tableName = "ai_distillations",
    foreignKeys = [
        ForeignKey(
            entity = ChatSessionEntity::class,
            parentColumns = ["id"],
            childColumns = ["sessionId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("sessionId")]
)
data class AIDistillationEntity(
    @PrimaryKey val id: String,
    val sessionId: String,
    val type: String,
    val title: String,
    val content: String,
    val modelUsed: String,
    val createdAt: Long
)

@Entity(tableName = "export_records")
data class ExportRecordEntity(
    @PrimaryKey val id: String,
    val sessionId: String,
    val sessionTitle: String,
    val fileName: String,
    val format: String,
    val filePath: String,
    val fileSizeFormatted: String,
    val exportedAt: Long
)
