package com.example

import com.example.data.local.draftFromJson
import com.example.data.local.draftToJson
import com.example.data.model.ChatMessage
import com.example.data.model.ChatSession
import com.example.data.model.CodeSnippet
import com.example.data.model.MessageRole
import com.example.data.model.PlatformPreset
import com.example.data.model.TextDirection
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ResumeDraftStoreTest {

    private val createdAt = 1_760_000_000_000L
    private val updatedAt = 1_760_000_900_000L

    private fun draftSession() = ChatSession(
        id = "session-42",
        title = "معماری کش در اندروید",
        platform = PlatformPreset.DEEPSEEK,
        modelName = "DeepSeek-R1",
        createdAt = createdAt,
        updatedAt = updatedAt,
        messages = listOf(
            ChatMessage(
                id = "msg-1",
                role = MessageRole.USER,
                content = "چطور لایه کش طراحی کنم؟",
                timestamp = createdAt,
                modelTag = null,
                direction = TextDirection.RTL,
                reasoning = null,
                codeSnippets = emptyList()
            ),
            ChatMessage(
                id = "msg-2",
                role = MessageRole.ASSISTANT,
                content = "کش را در دو سطح حافظه و دیسک نگه دارید.",
                timestamp = updatedAt,
                modelTag = "DeepSeek-R1",
                direction = TextDirection.MIXED,
                reasoning = "بررسی گزینه‌ها و مقایسه هزینه دسترسی",
                codeSnippets = listOf(
                    CodeSnippet(
                        id = "snippet-1",
                        language = "kotlin",
                        code = "val cache = LruCache<String, ByteArray>(64)"
                    )
                )
            )
        ),
        tags = listOf("معماری"),
        isBookmarked = true,
        customNotes = "برای بازبینی هفتگی"
    )

    @Test
    fun draftRoundTripsEverySessionField() {
        val session = draftSession()
        val restored = requireNotNull(draftFromJson(draftToJson(session))) { "draft did not come back" }

        assertEquals(session.id, restored.id)
        assertEquals(session.title, restored.title)
        assertEquals(PlatformPreset.DEEPSEEK, restored.platform)
        assertEquals(session.modelName, restored.modelName)
        assertEquals(session.createdAt, restored.createdAt)
        assertEquals(session.updatedAt, restored.updatedAt)
        assertEquals(session.tags, restored.tags)
        assertEquals(session.isBookmarked, restored.isBookmarked)
        assertEquals(session.customNotes, restored.customNotes)
        assertEquals(2, restored.messages.size)

        val first = restored.messages[0]
        assertEquals("msg-1", first.id)
        assertEquals(MessageRole.USER, first.role)
        assertEquals("چطور لایه کش طراحی کنم؟", first.content)
        assertEquals(createdAt, first.timestamp)
        assertNull(first.modelTag)
        assertEquals(TextDirection.RTL, first.direction)
        assertNull(first.reasoning)
        assertTrue(first.codeSnippets.isEmpty())

        val second = restored.messages[1]
        assertEquals("msg-2", second.id)
        assertEquals(MessageRole.ASSISTANT, second.role)
        assertEquals("کش را در دو سطح حافظه و دیسک نگه دارید.", second.content)
        assertEquals(updatedAt, second.timestamp)
        assertEquals("DeepSeek-R1", second.modelTag)
        assertEquals(TextDirection.MIXED, second.direction)
        assertEquals("بررسی گزینه‌ها و مقایسه هزینه دسترسی", second.reasoning)
        assertEquals(1, second.codeSnippets.size)
        assertEquals("snippet-1", second.codeSnippets[0].id)
        assertEquals("kotlin", second.codeSnippets[0].language)
        assertEquals("val cache = LruCache<String, ByteArray>(64)", second.codeSnippets[0].code)
        assertEquals(1, second.codeSnippets[0].lineCount)

        assertEquals(session, restored)
    }

    @Test
    fun malformedDraftJsonReturnsNullInsteadOfCrashing() {
        assertNull(draftFromJson(JSONObject("{}")))
        assertNull(draftFromJson(JSONObject("""{"id":"s1","title":"بدون پیام"}""")))
        assertNull(draftFromJson(JSONObject("""{"id":"s1","title":"t","messages":"خراب"}""")))
        assertNull(draftFromJson(JSONObject("""{"id":"s1","title":"t","messages":["متن، نه شیء"]}""")))
        assertNull(
            draftFromJson(
                JSONObject(
                    """{"id":"s1","title":"t","modelName":"m","customNotes":"",""" +
                        """"messages":[{"id":"m1","role":"TOOL","content":"x","direction":"RTL"}]}"""
                )
            )
        )
    }

    @Test
    fun emptyMessageContentSurvivesTheRoundTrip() {
        val session = ChatSession(
            id = "session-empty",
            title = "پیش‌نویس بی‌محتوا",
            platform = PlatformPreset.CUSTOM,
            modelName = "Universal LLM",
            createdAt = createdAt,
            updatedAt = createdAt,
            messages = listOf(
                ChatMessage(
                    id = "msg-empty",
                    role = MessageRole.ASSISTANT,
                    content = "",
                    timestamp = createdAt,
                    direction = TextDirection.LTR
                )
            )
        )

        val restored = requireNotNull(draftFromJson(draftToJson(session))) { "draft did not come back" }

        assertEquals("", restored.messages[0].content)
        assertEquals(session.messages[0], restored.messages[0])
        assertEquals(session, restored)
    }

    /** Same file the writer just produced, minus one key — i.e. exactly what an older build left on disk. */
    private fun JSONObjectWithoutKey(source: JSONObject, excluded: String): JSONObject {
        val copy = JSONObject()
        val keys = source.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            if (key != excluded) copy.put(key, source.get(key))
        }
        return copy
    }

    /**
     * A stored count is NOT carried back. Drafts written by older builds counted metadata nodes as
     * dropped turns, and nothing in the file says which rule wrote the number, so a resumed session
     * says nothing about drops instead of repeating a figure it cannot vouch for.
     */
    @Test
    fun aStoredDropCountIsNotCarriedIntoTheResumedSession() {
        val session = draftSession().copy(rejectedTurns = 3)

        val json = draftToJson(session)
        assertFalse("the draft must not carry a count it cannot vouch for", json.has("rejectedTurns"))

        val restored = requireNotNull(draftFromJson(json)) { "draft did not come back" }
        assertEquals(0, restored.rejectedTurns)
    }

    /**
     * A draft written before this key existed has no `rejectedTurns` at all and must still open, with 0
     * read as «never counted». This file is built from a real serialized draft with the key stripped, so
     * nothing here can be satisfied by the writer adding it back.
     *
     * Deliberate break: change the read to `obj.getInt("rejectedTurns")` — the JSONException is swallowed by
     * `draftFromJson`'s catch, it returns null, and only this test turns red. That is what proves the
     * `optInt` default is the thing carrying the old file.
     */
    @Test
    fun oldDraftWithoutCountsStillLoads() {
        val legacyDraft = JSONObjectWithoutKey(draftToJson(draftSession()), "rejectedTurns")
        assertFalse("the fixture must really lack the key", legacyDraft.has("rejectedTurns"))

        val restored = requireNotNull(draftFromJson(legacyDraft)) { "an old draft without the count must still load" }
        assertEquals(0, restored.rejectedTurns)
        assertEquals(draftSession(), restored)
    }

    /**
     * Reading the draft is where the invented ChatGPT label dies — never a Room migration, never a
     * rewritten file (`AppDatabase` is on version = 1 with a destructive fallback).
     *
     * Deliberate break: hand `obj.getString("modelName")` straight to `ChatSession` without
     * `scrubInventedModel`, and the first assertion goes red («GPT-4o» is not «ChatGPT») while the
     * stated-slug half stays green, proving the map is one entry wide.
     */
    @Test
    fun draftRestoreScrubbsTheInventedGptLabel() {
        val invented = draftSession().copy(platform = PlatformPreset.CHATGPT, modelName = "GPT-4o")
        val restored = requireNotNull(draftFromJson(draftToJson(invented))) { "draft did not come back" }
        assertEquals("ChatGPT", restored.modelName)

        // A slug a page really stated is a fact, and the same read must leave it alone.
        val stated = draftSession().copy(platform = PlatformPreset.CHATGPT, modelName = "gpt-4o-2024-08-06")
        val restoredStated = requireNotNull(draftFromJson(draftToJson(stated))) { "draft did not come back" }
        assertEquals("gpt-4o-2024-08-06", restoredStated.modelName)
    }

    /**
     * Measured on the emulator (2026-10-10): a restored Claude draft written by an older build still
     * showed «Claude 3.5 Sonnet» in the bubble header, because the read scrub only reached the session
     * name and only knew ChatGPT. The same invented label must die on every turn, and for Claude too.
     *
     * Deliberate break: pass `msg.optStringOrNull("modelTag")` through without `scrubInventedModel`
     * and the second assertion goes red; drop the CLAUDE entry from the map and the first two go red.
     */
    @Test
    fun draftRestoreScrubbsTheInventedLabelOnEveryTurn() {
        val legacy = draftSession().copy(
            platform = PlatformPreset.CLAUDE,
            modelName = "Claude 3.5 Sonnet",
            messages = draftSession().messages.map { it.copy(modelTag = "Claude 3.5 Sonnet") }
        )
        val restored = requireNotNull(draftFromJson(draftToJson(legacy))) { "draft did not come back" }
        assertEquals("Claude", restored.modelName)
        assertTrue("no turn may keep the invented label", restored.messages.all { it.modelTag == null })

        // A label the source really stated has to survive the same read, turn by turn.
        val stated = legacy.copy(
            modelName = "claude-3-7-sonnet",
            messages = legacy.messages.map { it.copy(modelTag = "claude-3-7-sonnet") }
        )
        val restoredStated = requireNotNull(draftFromJson(draftToJson(stated))) { "draft did not come back" }
        assertEquals(
            listOf("claude-3-7-sonnet"),
            restoredStated.messages.mapNotNull { it.modelTag }.distinct()
        )
    }
}
