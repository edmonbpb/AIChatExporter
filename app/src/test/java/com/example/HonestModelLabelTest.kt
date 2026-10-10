package com.example

import com.example.data.model.ChatMessage
import com.example.data.model.ChatSession
import com.example.data.model.MessageRole
import com.example.data.model.PlatformPreset
import com.example.domain.bidi.BidiEngine
import com.example.domain.parser.ChatGptStreamDecoder
import com.example.domain.parser.SharedLinkExtractor
import com.example.domain.parser.TranscriptIngestionEngine
import com.example.domain.parser.partialImportNotice
import com.example.domain.parser.scrubInventedModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Nothing in this file may invent a model, and nothing in it may call a partial read complete. Each
 * test holds one of the two rules the old code broke: the bubble said `GPT-4o` for a page whose stated
 * slug was `auto`, and an import that silently dropped every non-text turn was announced as a success.
 * The last two tests hold the rule added after that fix: a number the reader never measured may not be
 * printed either, so the notice speaks a drop count only when one was actually counted.
 */
class HonestModelLabelTest {

    private fun flatJson(): String =
        requireNotNull(javaClass.getResourceAsStream("/chatgpt_stream_flat.json")) { "fixture missing from the test classpath" }
            .bufferedReader(Charsets.UTF_8).use { it.readText() }

    private fun turn(role: MessageRole, content: String): ChatMessage =
        ChatMessage(id = content, role = role, content = content)

    /** A session of [kept] alternating turns that measured [dropped] refusals on the way in. */
    private fun session(kept: Int, dropped: Int): ChatSession = ChatSession(
        id = "honest-label-test",
        title = "آزمون",
        messages = List(kept) { index ->
            turn(if (index % 2 == 0) MessageRole.USER else MessageRole.ASSISTANT, "turn-$index")
        },
        rejectedTurns = dropped
    )

    @Test
    fun pastedTranscriptTurnsCarryNoInventedModelTag() {
        val pasted = TranscriptIngestionEngine.parseTranscript(
            "User: سلام\nAssistant: پاسخ کوتاه",
            PlatformPreset.CHATGPT
        )

        assertTrue(pasted.messages.isNotEmpty())
        // The source never named a model, so no row may claim one.
        assertTrue(pasted.messages.all { it.modelTag == null })
        assertEquals("ChatGPT", pasted.modelName)
        assertNotEquals("GPT-4o", pasted.modelName)

        // A JSON file that carries no `modelName` is answered with the platform, not with a made-up model.
        val json = TranscriptIngestionEngine.parseTranscript(
            """{"platform":"custom","messages":[{"role":"user","content":"الف"},{"role":"assistant","content":"ب"}]}"""
        )
        assertEquals(PlatformPreset.CUSTOM.displayName, json.modelName)
        assertNotEquals("Universal LLM", json.modelName)
        assertTrue(json.messages.all { it.modelTag == null })

        // An official export whose `default_model_slug` is `auto` stays unlabelled as well.
        val exported = TranscriptIngestionEngine.parseTranscript(
            "{\"title\":\"t\",\"default_model_slug\":\"auto\",\"mapping\":{" +
                "\"a\":{\"message\":{\"author\":{\"role\":\"user\"}," +
                "\"content\":{\"content_type\":\"text\",\"parts\":[\"الف\"]}}}," +
                "\"b\":{\"message\":{\"author\":{\"role\":\"assistant\"}," +
                "\"content\":{\"content_type\":\"text\",\"parts\":[\"ب\"]}}}}}"
        )
        assertEquals(PlatformPreset.CHATGPT.displayName, exported.modelName)
        assertTrue(exported.messages.all { it.modelTag == null })
    }

    @Test
    fun legacyInventedGptLabelIsScrubbedButRealSlugSurvives() {
        // The label the app wrote itself, on the platform it wrote it for, is not data.
        assertNull(scrubInventedModel(PlatformPreset.CHATGPT, "GPT-4o"))
        assertNull(scrubInventedModel(PlatformPreset.CHATGPT, null))

        // A model the source really named — or the user typed — is data and must pass untouched.
        assertEquals("gpt-4o-2024-08-06", scrubInventedModel(PlatformPreset.CHATGPT, "gpt-4o-2024-08-06"))
        assertEquals("ChatGPT", scrubInventedModel(PlatformPreset.CHATGPT, "ChatGPT"))
        assertEquals("DeepSeek-R1", scrubInventedModel(PlatformPreset.DEEPSEEK, "DeepSeek-R1"))
        assertEquals("GPT-4o", scrubInventedModel(PlatformPreset.CUSTOM, "GPT-4o"))
    }

    @Test
    fun droppedTurnsNeverMakeAFullReadClaimComplete() {
        // A long read is still partial when the reader measured refusals inside it.
        assertNotNull(partialImportNotice(session(kept = 12, dropped = 3)))

        // End to end through the production factory: the session the app actually builds is what the
        // banner reads, so breaking the wiring in `sessionFromTurns` turns this red (it did not when
        // the test assembled its own ChatSession).
        val payload = requireNotNull(ChatGptStreamDecoder.decodeFlatArray(flatJson())) { "flat fixture decoded to null" }
        val imported = requireNotNull(SharedLinkExtractor.sessionFromTurns(payload, "https://chatgpt.com/share/test")) {
            "the share-page factory returned null"
        }

        assertEquals(2, imported.rejectedTurns)
        assertEquals(4, imported.messages.size)
        assertNotNull(partialImportNotice(imported))
    }

    @Test
    fun threeTurnsWithoutDropsIsNotPartial() {
        assertNull(partialImportNotice(session(kept = 3, dropped = 0)))
        assertNull(partialImportNotice(session(kept = 40, dropped = 0)))

        // One turn short of the floor is still a fragment, and the short read has to be said out loud.
        assertNotNull(partialImportNotice(session(kept = 2, dropped = 0)))
    }

    /**
     * Case (a) of the notice: the decoder MEASURED refusals, so that number is the one spoken next to
     * the words for «a turn was refused», and the count that arrived is spoken too. Deliberate break
     * that reddens this: print `keptText` where `droppedText` is printed (the two numbers swap) and the
     * third assertion goes red while the sentence still reads as a fluent Persian claim.
     */
    @Test
    fun measuredDropCountIsTheNumberSpokenWithTheRefusal() {
        val notice = requireNotNull(partialImportNotice(session(kept = 12, dropped = 3))) {
            "a read with a measured drop must not be called complete"
        }

        assertTrue(notice.contains("کامل خوانده نشد"))
        assertTrue(notice.contains("رد شد"))
        assertTrue(
            "the measured drop count 3 was not spoken beside «نوبت رد شد»: $notice",
            notice.contains(BidiEngine.toPersianDigits("3") + " نوبت رد شد")
        )
        assertTrue(notice.contains(BidiEngine.toPersianDigits("12")))

        // Two numbers and nothing else: the drop count and the count that arrived.
        assertEquals(3, notice.count { it.isDigit() })
    }

    /**
     * Case (b) of the notice: `rejectedTurns == 0` means «never counted» (`ChatSession.kt:20-23`), and a
     * session that went through the library comes back with 0 whatever was dropped there, because
     * `Entities.kt` has no column for it. So the short read is said out loud and no drop figure is
     * printed. Deliberate break that reddens this: make case (b) print a zero drop count again — one
     * number becomes two, `رد شد` comes back, and a Persian zero appears where none was measured.
     */
    @Test
    fun uncountedShortReadNamesNoDropFigure() {
        val notice = requireNotNull(partialImportNotice(session(kept = 2, dropped = 0))) {
            "a two-turn read is a fragment and must be said"
        }

        assertTrue(notice.contains("کامل خوانده نشد"))
        assertFalse("a drop figure was invented for a read that never counted one: $notice",
            notice.contains("رد شد"))
        assertFalse("the unmeasured zero was printed as a drop count: $notice",
            notice.contains(BidiEngine.toPersianDigits("0")))
        assertTrue(notice.contains(BidiEngine.toPersianDigits("2")))

        // Exactly one number — the turns that arrived — and no latin digit anywhere in the sentence.
        assertEquals(1, notice.count { it.isDigit() })
        assertTrue(notice.none { it in '0'..'9' })
        assertTrue(notice.endsWith("."))
    }

    @Test
    fun noticeUsesPersianDigits() {
        val notice = requireNotNull(partialImportNotice(session(kept = 4, dropped = 2))) { "notice was null for a partial read" }

        // Both numbers are spoken, and both in the digits the rest of the UI uses.
        assertTrue(notice.contains("۲"))
        assertTrue(notice.contains("۴"))
        assertTrue("a latin digit leaked into the banner: $notice", notice.none { it in '0'..'9' })
        assertTrue(notice.all { !it.isDigit() || it in '۰'..'۹' })

        // One complete Persian sentence, built from the words the app already uses for a partial read.
        assertTrue(notice.contains("کامل خوانده نشد"))
        assertTrue(notice.contains("رد شد"))
        assertTrue(notice.contains("نوبت"))
        assertTrue(notice.endsWith("."))
    }
}
