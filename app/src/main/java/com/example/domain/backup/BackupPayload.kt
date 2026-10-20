package com.example.domain.backup

import com.example.data.local.AIDistillationEntity
import com.example.data.local.ChatMessageEntity
import com.example.data.local.ChatSessionEntity
import com.example.data.local.ExportRecordEntity
import org.json.JSONArray
import org.json.JSONObject

/**
 * Everything the library holds, in one file: sessions, their messages, the distillation history and
 * the export records. The entities are written field for field so a restore is lossless instead of a
 * best-effort remap, and the marker plus version let a future build refuse a file it cannot read.
 */
internal data class BackupPayload(
    val sessions: List<ChatSessionEntity>,
    val messages: List<ChatMessageEntity>,
    val distillations: List<AIDistillationEntity>,
    val exportRecords: List<ExportRecordEntity>
) {
    val isEmpty: Boolean
        get() = sessions.isEmpty() && messages.isEmpty() && distillations.isEmpty() && exportRecords.isEmpty()

    fun toJson(now: Long = System.currentTimeMillis()): String {
        val root = JSONObject()
        root.put("format", MARKER)
        root.put("version", BackupFormat.VERSION)
        root.put("exportedAt", now)
        root.put("sessions", JSONArray().apply { sessions.forEach { put(it.toJson()) } })
        root.put("messages", JSONArray().apply { messages.forEach { put(it.toJson()) } })
        root.put("distillations", JSONArray().apply { distillations.forEach { put(it.toJson()) } })
        root.put("exportRecords", JSONArray().apply { exportRecords.forEach { put(it.toJson()) } })
        return root.toString()
    }

    companion object {
        const val MARKER = "aichatexporter-backup"

        /** Null means «not a backup this build understands», never «an empty backup». */
        fun fromJson(raw: String): BackupPayload? {
            val root = try {
                JSONObject(raw)
            } catch (_: Exception) {
                return null
            }
            if (root.optString("format") != MARKER) return null
            if (root.optInt("version", -1) > BackupFormat.VERSION) return null

            return BackupPayload(
                sessions = root.optJSONArray("sessions").mapObjects { it.toSession() },
                messages = root.optJSONArray("messages").mapObjects { it.toMessage() },
                distillations = root.optJSONArray("distillations").mapObjects { it.toDistillation() },
                exportRecords = root.optJSONArray("exportRecords").mapObjects { it.toExportRecord() }
            )
        }

    }
}

/** A null array means the key was absent, which is an empty list and not a broken file. */
private inline fun <T> JSONArray?.mapObjects(transform: (JSONObject) -> T?): List<T> =
    if (this == null) emptyList()
    else (0 until length()).mapNotNull { index -> optJSONObject(index)?.let { transform(it) } }

private fun ChatSessionEntity.toJson(): JSONObject = JSONObject().apply {
    put("id", id); put("title", title); put("platformId", platformId); put("modelName", modelName)
    put("createdAt", createdAt); put("updatedAt", updatedAt); put("tagsJson", tagsJson)
    put("isBookmarked", isBookmarked); put("customNotes", customNotes)
}

private fun JSONObject.toSession(): ChatSessionEntity? = runCatching {
    ChatSessionEntity(
        id = getString("id"), title = getString("title"), platformId = getString("platformId"),
        modelName = getString("modelName"), createdAt = getLong("createdAt"), updatedAt = getLong("updatedAt"),
        tagsJson = optString("tagsJson", "[]"), isBookmarked = optBoolean("isBookmarked", false),
        customNotes = optString("customNotes", "")
    )
}.getOrNull()

private fun ChatMessageEntity.toJson(): JSONObject = JSONObject().apply {
    put("id", id); put("sessionId", sessionId); put("role", role); put("content", content)
    put("timestamp", timestamp); put("modelTag", modelTag ?: JSONObject.NULL); put("direction", direction)
    put("reasoning", reasoning ?: JSONObject.NULL); put("codeSnippetsJson", codeSnippetsJson)
    put("orderIndex", orderIndex)
}

private fun JSONObject.toMessage(): ChatMessageEntity? = runCatching {
    ChatMessageEntity(
        id = getString("id"), sessionId = getString("sessionId"), role = getString("role"),
        content = getString("content"), timestamp = getLong("timestamp"),
        modelTag = if (isNull("modelTag")) null else optString("modelTag"),
        direction = optString("direction", "MIXED"),
        reasoning = if (isNull("reasoning")) null else optString("reasoning"),
        codeSnippetsJson = optString("codeSnippetsJson", "[]"), orderIndex = optInt("orderIndex", 0)
    )
}.getOrNull()

private fun AIDistillationEntity.toJson(): JSONObject = JSONObject().apply {
    put("id", id); put("sessionId", sessionId); put("type", type); put("title", title)
    put("content", content); put("modelUsed", modelUsed); put("createdAt", createdAt)
}

private fun JSONObject.toDistillation(): AIDistillationEntity? = runCatching {
    AIDistillationEntity(
        id = getString("id"), sessionId = getString("sessionId"), type = getString("type"),
        title = getString("title"), content = getString("content"),
        modelUsed = optString("modelUsed", ""), createdAt = getLong("createdAt")
    )
}.getOrNull()

private fun ExportRecordEntity.toJson(): JSONObject = JSONObject().apply {
    put("id", id); put("sessionId", sessionId); put("sessionTitle", sessionTitle)
    put("fileName", fileName); put("format", format); put("filePath", filePath)
    put("fileSizeFormatted", fileSizeFormatted); put("exportedAt", exportedAt)
}

private fun JSONObject.toExportRecord(): ExportRecordEntity? = runCatching {
    ExportRecordEntity(
        id = getString("id"), sessionId = getString("sessionId"), sessionTitle = getString("sessionTitle"),
        fileName = getString("fileName"), format = getString("format"), filePath = optString("filePath", ""),
        fileSizeFormatted = optString("fileSizeFormatted", ""), exportedAt = getLong("exportedAt")
    )
}.getOrNull()
