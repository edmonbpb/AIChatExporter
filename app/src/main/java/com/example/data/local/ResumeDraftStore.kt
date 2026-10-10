package com.example.data.local

import android.content.Context
import com.example.data.model.ChatMessage
import com.example.data.model.ChatSession
import com.example.data.model.MessageRole
import com.example.data.model.PlatformPreset
import com.example.data.model.TextDirection
import com.example.domain.parser.scrubInventedModel
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Private, single-slot journal of the conversation currently open in Workspace. It survives a
 * killed process without entering the library table, so nothing here can show up in History.
 */
class ResumeDraftStore(private val context: Context) {

    fun save(session: ChatSession) {
        try {
            val staging = File(context.filesDir, DRAFT_FILE_NAME + ".tmp")
            staging.writeText(draftToJson(session).toString())
            val target = File(context.filesDir, DRAFT_FILE_NAME)
            // renameTo() refuses to overwrite on some filesystems; the old draft is dropped only
            // after the staging file is complete, so a half-written draft can never be loaded.
            if (!staging.renameTo(target)) {
                target.delete()
                staging.renameTo(target)
            }
        } catch (_: Exception) {
        }
    }

    fun load(): ChatSession? {
        return try {
            val file = File(context.filesDir, DRAFT_FILE_NAME)
            if (!file.isFile) return null
            draftFromJson(JSONObject(file.readText()))
        } catch (_: Exception) {
            null
        }
    }

    fun clear() {
        try {
            File(context.filesDir, DRAFT_FILE_NAME).delete()
            File(context.filesDir, DRAFT_FILE_NAME + ".tmp").delete()
        } catch (_: Exception) {
        }
    }

    companion object {
        private const val DRAFT_FILE_NAME = "resume_draft.json"
    }
}

private val draftConverters = Converters()

internal fun draftToJson(session: ChatSession): JSONObject {
    val messages = JSONArray()
    session.messages.forEach { msg ->
        messages.put(
            JSONObject().apply {
                put("id", msg.id)
                put("role", msg.role.name)
                put("content", msg.content)
                put("timestamp", msg.timestamp)
                put("modelTag", msg.modelTag ?: JSONObject.NULL)
                put("direction", msg.direction.name)
                put("reasoning", msg.reasoning ?: JSONObject.NULL)
                put("codeSnippets", JSONArray(draftConverters.fromCodeSnippetList(msg.codeSnippets)))
            }
        )
    }

    return JSONObject().apply {
        put("id", session.id)
        put("title", session.title)
        put("platform", session.platform.id)
        put("modelName", session.modelName)
        // The count of turns the reader refused to carry has to survive a killed process, or the honest
        // warning disappears on the very next restore. Old drafts simply have no such key; the reader
        // below defaults it to 0.
        put("rejectedTurns", session.rejectedTurns)
        put("createdAt", session.createdAt)
        put("updatedAt", session.updatedAt)
        put("messages", messages)
        put("tags", JSONArray(draftConverters.fromStringList(session.tags)))
        put("isBookmarked", session.isBookmarked)
        put("customNotes", session.customNotes)
    }
}

internal fun draftFromJson(obj: JSONObject): ChatSession? {
    return try {
        val id = obj.optStringOrNull("id")?.takeIf { it.isNotBlank() } ?: return null
        val title = obj.optStringOrNull("title") ?: return null
        val messagesJson = obj.optJSONArray("messages") ?: return null

        val platform = PlatformPreset.fromString(obj.optStringOrNull("platform"))
        val messages = mutableListOf<ChatMessage>()
        for (index in 0 until messagesJson.length()) {
            val msg = messagesJson.optJSONObject(index) ?: return null
            messages.add(
                ChatMessage(
                    id = msg.optString("id"),
                    role = MessageRole.valueOf(msg.getString("role")),
                    content = msg.getString("content"),
                    timestamp = msg.optLong("timestamp"),
                    // The turn label was invented per message too, so the bubble header needs the same
                    // scrub the session name gets; a slug the page really stated survives.
                    modelTag = scrubInventedModel(platform, msg.optStringOrNull("modelTag")),
                    direction = TextDirection.valueOf(msg.getString("direction")),
                    reasoning = msg.optStringOrNull("reasoning"),
                    codeSnippets = draftConverters.toCodeSnippetList(msg.optJSONArray("codeSnippets")?.toString())
                )
            )
        }

        ChatSession(
            id = id,
            title = title,
            platform = platform,
            // Reads never migrate. A draft left by an older build can still carry the «GPT-4o» label this
            // app invented for ChatGPT; it is dropped here on the way back into memory and the file on
            // disk is left alone. A label the source really stated survives untouched.
            modelName = scrubInventedModel(platform, obj.getString("modelName")) ?: platform.displayName,
            createdAt = obj.optLong("createdAt"),
            updatedAt = obj.optLong("updatedAt"),
            messages = messages,
            tags = draftConverters.toStringList(obj.optJSONArray("tags")?.toString()),
            isBookmarked = obj.optBoolean("isBookmarked"),
            customNotes = obj.getString("customNotes"),
            // optInt, not getInt: a draft written before this key existed must still load, with 0 = never counted.
            rejectedTurns = obj.optInt("rejectedTurns", 0)
        )
    } catch (_: Exception) {
        null
    }
}

private fun JSONObject.optStringOrNull(key: String): String? = if (isNull(key)) null else getString(key)
