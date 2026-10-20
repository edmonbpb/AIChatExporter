package com.example

import com.example.data.model.MessageRole
import com.example.domain.parser.ChatGptStreamDecoder
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatGptStreamDecoderTest {

    private fun readResource(name: String): String =
        requireNotNull(javaClass.getResourceAsStream(name)) { "fixture $name missing from the test classpath" }
            .bufferedReader(Charsets.UTF_8).use { it.readText() }

    private val flatJson = readResource("/chatgpt_stream_flat.json")
    private val pageHtml = readResource("/chatgpt_stream_page.html")

    // Two `parts` of one message are joined with a real newline, and blank parts are dropped.
    private val expectedTurns = listOf(
        MessageRole.USER to "سلام، فایل‌های لازم پروژه را بده",
        MessageRole.ASSISTANT to "بودجهٔ پروژه\nدو بخش دارد",
        MessageRole.USER to "دوباره دقیق‌تر بگو",
        MessageRole.ASSISTANT to "متن نهایی"
    )

    // Node n2 is its own parent and its own child; n1.children, n3.children and mapping reference n2 again.
    private val cyclicFlat = """
        [{"_1":2,"_69":42},"loaderData",{"_3":4},"share.f1a2e3",{"_5":6},"serverResponse",{"_7":8},"data",
        {"_9":10,"_11":12,"_13":14,"_37":68},"title","یک متن مشترک","default_model_slug","auto","linear_conversation",
        [15,45,57],{"_16":17,"_18":19,"_41":42,"_43":44},"id","n1","message",{"_16":17,"_20":21,"_26":27,
        "_28":29,"_35":36,"_37":38},"author",{"_22":23,"_24":25},"role","user","metadata",{},"create_time",
        1.0,"content",{"_30":31,"_32":33},"content_type","text","parts",[34],"متن کاربر","status","finished_successfully",
        "mapping",{"_39":40},"is_fallback",false,"parent",null,"children",[45],{"_16":46,"_18":47,"_41":45,
        "_43":56},"n2",{"_16":46,"_20":48,"_26":51,"_28":52,"_35":36,"_37":55},{"_22":49,"_24":50},"assistant",
        {},2.0,{"_30":31,"_32":53},[54],"متن مشترک",{"_39":40},[45],{"_16":58,"_18":59,"_41":15,"_43":67},
        "n3",{"_16":58,"_20":60,"_26":62,"_28":63,"_35":36,"_37":66},{"_22":23,"_24":61},{},3.0,{"_30":31,
        "_32":64},[65],"متن سوم",{"_39":40},[15,45],{"_17":15,"_46":45,"_58":57},"errors"]
    """

    private val twoTurnFlat = """
        [{"_1":2,"_57":42},"loaderData",{"_3":4},"share.f1a2e3",{"_5":6},"serverResponse",{"_7":8},"data",
        {"_9":10,"_11":12,"_13":14},"title","دو نوبت","default_model_slug","auto","linear_conversation",
        [15,45],{"_16":17,"_18":19,"_41":42,"_43":44},"id","n1","message",{"_16":17,"_20":21,"_26":27,"_28":29,
        "_35":36,"_37":38},"author",{"_22":23,"_24":25},"role","user","metadata",{},"create_time",1.0,"content",
        {"_30":31,"_32":33},"content_type","text","parts",[34],"سلام دوباره","status","finished_successfully",
        "mapping",{"_39":40},"is_fallback",false,"parent",null,"children",[],{"_16":46,"_18":47,"_41":42,
        "_43":56},"n2",{"_16":46,"_20":48,"_26":51,"_28":52,"_35":36,"_37":55},{"_22":49,"_24":50},"assistant",
        {},2.0,{"_30":31,"_32":53},[54],"پاسخ کوتاه",{"_39":40},[],"errors"]
    """

    private val errorsOnlyFlat = """
        [{"_1":2,"_7":8},"errors",{"_3":4,"_5":6},"message","خطای بارگذاری","code",500.0,"version",1.0]
    """

    /*
     * Fixtures for counting what the reader drops. These are written as plain inline JSON objects
     * instead of the `_<n>` reference encoding: `resolve()` passes an already-materialised object
     * through untouched, so the shape stays readable while every gate below is still the decoder's
     * own. `create_time` is a DOUBLE on purpose — an integer would be read as an array reference.
     */

    // m2 is a `code` part and m4 an emptied text body: both are real content, both must be counted.
    private val droppedContentFlat = """
        [{"title":"سه نوبت و دو رد","default_model_slug":"auto","mapping":{
          "m1":{"message":{"author":{"role":"user","metadata":{}},"create_time":1.0,
                 "content":{"content_type":"text","parts":["پرسش اول"],"status":"finished_successfully"},
                 "mapping":{"is_fallback":false}}},
          "m2":{"message":{"author":{"role":"assistant","metadata":{}},"create_time":2.0,
                 "content":{"content_type":"code","parts":[{"language":"bash","code":"ls -la"}]},
                 "mapping":{"is_fallback":false}}},
          "m3":{"message":{"author":{"role":"assistant","metadata":{}},"create_time":3.0,
                 "content":{"content_type":"text","parts":["پاسخ دوم"],"status":"finished_successfully"},
                 "mapping":{"is_fallback":false}}},
          "m4":{"message":{"author":{"role":"user","metadata":{}},"create_time":4.0,
                 "content":{"content_type":"text","parts":["","   "]},"mapping":{"is_fallback":false}}},
          "m5":{"message":{"author":{"role":"assistant","metadata":{}},"create_time":5.0,
                 "content":{"content_type":"text","parts":["پاسخ سوم"],"status":"finished_successfully"},
                 "mapping":{"is_fallback":false}}}
        }}]
    """

    // One code-interpreter answer: a turn the reader sees, so it must be carried, not skipped.
    private val codeOnlyFlat = """
        [{"title":"a code answer","default_model_slug":"auto","mapping":{
          "c1":{"message":{"author":{"role":"assistant","metadata":{}},"create_time":1.0,
                 "content":{"content_type":"code","parts":[{"language":"python","code":"print(1)"}]},
                 "mapping":{"is_fallback":false}}}
        }}]
    """

    // One text turn and one picture-only turn: the second is a visible turn this text exporter
    // cannot carry, so it is the one kind of loss that must still be counted.
    private val attachmentOnlyFlat = """
        [{"title":"text and attachment","default_model_slug":"auto","mapping":{
          "a1":{"message":{"author":{"role":"user","metadata":{}},"create_time":1.0,
                 "content":{"content_type":"text","parts":["first question"],"status":"finished_successfully"},
                 "mapping":{"is_fallback":false}}},
          "a2":{"message":{"author":{"role":"assistant","metadata":{}},"create_time":2.0,
                 "content":{"content_type":"multimodal_text","parts":[{"content_type":"image_asset_pointer","asset_pointer":"file-service://x"}]},
                 "mapping":{"is_fallback":false}}}
        }}]
    """

    // Three nodes carry a NON-text content type and an empty body each, and every one of them is
    // already refused by a gate above: a `system` row, a `tool` row, a cancelled fallback answer.
    private val gatedDropsFlat = """
        [{"title":"دو نوبت و سه دروازه","default_model_slug":"auto","mapping":{
          "g1":{"message":{"author":{"role":"system","metadata":{}},"create_time":1.0,
                "content":{"content_type":"system_message","parts":["تنظیمات سیستم"]},
                "mapping":{"is_fallback":false}}},
          "g2":{"message":{"author":{"role":"user","metadata":{}},"create_time":2.0,
                "content":{"content_type":"text","parts":["پرسش مانده"],"status":"finished_successfully"},
                "mapping":{"is_fallback":false}}},
          "g3":{"message":{"author":{"role":"tool","metadata":{}},"create_time":3.0,
                "content":{"content_type":"tool_call_output","parts":["خروجی ابزار"]},
                "mapping":{"is_fallback":false}}},
          "g4":{"message":{"author":{"role":"assistant","metadata":{}},"create_time":4.0,
                "content":{"content_type":"code","parts":[{"language":"bash","code":"ls -la"}]},
                "mapping":{"is_fallback":true}}},
          "g5":{"message":{"author":{"role":"assistant","metadata":{}},"create_time":5.0,
                "content":{"content_type":"text","parts":["پاسخ مانده"],"status":"finished_successfully"},
                "mapping":{"is_fallback":false}}}
        }}]
    """

    private val noSlugFlat = """
        [{"title":"بدون اسلاگ","mapping":{
          "s1":{"message":{"author":{"role":"user","metadata":{}},"create_time":1.0,
                "content":{"content_type":"text","parts":["پرسش"],"status":"finished_successfully"},
                "mapping":{"is_fallback":false}}},
          "s2":{"message":{"author":{"role":"assistant","metadata":{}},"create_time":2.0,
                "content":{"content_type":"text","parts":["پاسخ"],"status":"finished_successfully"},
                "mapping":{"is_fallback":false}}}
        }}]
    """

    private val autoCapsFlat = noSlugFlat.replace(
        """{"title":"بدون اسلاگ",""",
        """{"title":"اسلاگ AUTO","default_model_slug":"AUTO","""
    )

    private val realSlugFlat = noSlugFlat.replace(
        """{"title":"بدون اسلاگ",""",
        """{"title":"اسلاگ واقعی","default_model_slug":"gpt-4o-2024-08-06","""
    )

    private val twoTurns = listOf(
        MessageRole.USER to "سلام دوباره",
        MessageRole.ASSISTANT to "پاسخ کوتاه"
    )

    @Test
    fun flatArrayFixtureYieldsTheFourExpectedTurns() {
        val payload = requireNotNull(ChatGptStreamDecoder.decodeFlatArray(flatJson)) { "flat fixture decoded to null" }

        assertEquals("فایل‌های لازم پروژه", payload.title)
        assertEquals("ChatGPT", payload.modelName)
        assertEquals(4, payload.turns.size)
        assertEquals(expectedTurns, payload.turns.map { it.role to it.content })
    }

    @Test
    fun htmlPageFixtureDecodesToTheSameConversationAsTheFlatArray() {
        val fromHtml = requireNotNull(ChatGptStreamDecoder.decode(pageHtml)) { "enqueue literal did not decode" }
        val fromFlat = requireNotNull(ChatGptStreamDecoder.decodeFlatArray(flatJson)) { "flat fixture decoded to null" }

        assertEquals("فایل‌های لازم پروژه", fromHtml.title)
        assertEquals("ChatGPT", fromHtml.modelName)
        assertEquals(4, fromHtml.turns.size)
        assertEquals(fromFlat.turns.map { it.role to it.content }, fromHtml.turns.map { it.role to it.content })
        assertEquals(expectedTurns, fromHtml.turns.map { it.role to it.content })
    }

    @Test
    fun emptyMalformedAndEnqueueFreeInputReturnNullWithoutThrowing() {
        assertNull(ChatGptStreamDecoder.decodeFlatArray("[]"))
        assertNull(ChatGptStreamDecoder.decodeFlatArray("{bad json"))
        assertNull(ChatGptStreamDecoder.decode("<html>بدون enqueue</html>"))
    }

    @Test
    fun negativeReferenceResolvesToNullInsteadOfCrashing() {
        // A negative index is this stream's encoding of `undefined`; here it is a property name,
        // a property value and an array element all at once.
        val negativeRefs = """[{"_3": -5, "_1": -5}, "عنوان", [4, -5], -5, {"_2": -5}]"""

        assertNull(ChatGptStreamDecoder.decodeFlatArray(negativeRefs))
    }

    @Test
    fun fallbackAndNonTextTurnsNeverReachTheResult() {
        val payload = requireNotNull(ChatGptStreamDecoder.decodeFlatArray(flatJson)) { "flat fixture decoded to null" }
        val contents = payload.turns.map { it.content }

        assertFalse(contents.any { it.contains("لغو") })
        assertFalse(contents.any { it.contains("تفکر پنهان") })
        assertFalse(contents.any { it.contains("ls -la") })
        assertFalse(contents.any { it.contains("خروجی ابزار") })
        assertFalse(payload.turns.any { it.role == MessageRole.SYSTEM })
        assertEquals(4, contents.size)
    }

    @Test
    fun selfReferencingNodeAndRepeatedReferencesYieldThatTurnOnce() {
        val payload = requireNotNull(ChatGptStreamDecoder.decodeFlatArray(cyclicFlat)) { "cyclic flat array decoded to null" }

        // 3 turns: linear_conversation lists n1, n2, n3 once each; n2's self-cycle and its 3 other refs add none.
        assertEquals(3, payload.turns.size)
        assertEquals(
            listOf(
                MessageRole.USER to "متن کاربر",
                MessageRole.ASSISTANT to "متن مشترک",
                MessageRole.USER to "متن سوم"
            ),
            payload.turns.map { it.role to it.content }
        )
        assertEquals(1, payload.turns.count { it.content == "متن مشترک" })
        assertEquals("یک متن مشترک", payload.title)
        assertEquals("ChatGPT", payload.modelName)
    }

    @Test
    fun contentlessFirstEnqueueChunkDoesNotHideTheConversationInTheSecondOne() {
        val html = "<html><head><title>اشتراک</title></head><body>" +
            "<script>streamController.enqueue(${JSONObject.quote(errorsOnlyFlat)});</script>" +
            "<script>streamController.enqueue(${JSONObject.quote(twoTurnFlat)})</script>" +
            "</body></html>"

        val payload = requireNotNull(ChatGptStreamDecoder.decode(html)) { "the second enqueue chunk was skipped" }

        assertEquals("دو نوبت", payload.title)
        assertEquals("ChatGPT", payload.modelName)
        assertEquals(2, payload.turns.size)
        assertEquals(twoTurns, payload.turns.map { it.role to it.content })
    }

    @Test
    fun variableEnqueueArgumentIsSkippedAndAnUnclosedLiteralIsNotSwallowed() {
        val variableArgumentPage = "<html><body><script>" +
            "const chunk = decoder.decode(value); streamController.enqueue(chunk);</script>" +
            "<script>streamController.enqueue(${JSONObject.quote(twoTurnFlat)})</script>" +
            "</body></html>"

        val payload = requireNotNull(ChatGptStreamDecoder.decode(variableArgumentPage)) {
            "the literal after enqueue(chunk) was not found"
        }
        assertEquals(2, payload.turns.size)
        assertEquals(twoTurns, payload.turns.map { it.role to it.content })

        // The literal is never closed by `)`, so no conversation may be claimed for this page.
        val unclosedLiteralPage = "<html><body><script>streamController.enqueue(${JSONObject.quote(twoTurnFlat)} ;" +
            "</script><p>باقی سند</p></body></html>"

        assertNull(ChatGptStreamDecoder.decode(unclosedLiteralPage))
    }

    // ── What the reader drops, and what it is allowed to say about the model ────────────────────

    @Test
    fun metadataAndEmptyMessagesAreNotCountedAsDroppedTurns() {
        val payload = requireNotNull(ChatGptStreamDecoder.decodeFlatArray(droppedContentFlat)) { "drop fixture decoded to null" }

        // m2 is a code answer, which IS a visible turn and is now carried; m4 is a text message whose
        // body is empty, so there was nothing to carry and nothing was lost.
        assertEquals(4, payload.turns.size)
        assertEquals(0, payload.rejectedTurns)

        // The measured page shape: nine nodes in, four turns out, nothing dropped.
        val fromFixture = requireNotNull(ChatGptStreamDecoder.decodeFlatArray(flatJson)) { "flat fixture decoded to null" }
        assertEquals(4, fromFixture.turns.size)
        assertEquals(0, fromFixture.rejectedTurns)
    }

    @Test
    fun everyChunkOfThePageIsReadNotOnlyTheFirst() {
        // A page may deliver the conversation in more than one chunk; stopping at the first decodable
        // one silently dropped every turn that came after it.
        val page = "<html><body><script>" +
            "streamController.enqueue(${JSONObject.quote(twoTurnFlat)})" +
            "</script><script>streamController.enqueue(${JSONObject.quote(codeOnlyFlat)})</script></body></html>"

        val payload = requireNotNull(ChatGptStreamDecoder.decode(page)) { "merged payload is null" }
        assertEquals(3, payload.turns.size)
        assertTrue(payload.turns.any { it.content == "print(1)" })
    }

    @Test
    fun theSameTurnDeliveredTwiceIsCarriedOnce() {
        val page = "<html><body><script>" +
            "streamController.enqueue(${JSONObject.quote(twoTurnFlat)})" +
            "</script><script>streamController.enqueue(${JSONObject.quote(twoTurnFlat)})</script></body></html>"

        val payload = requireNotNull(ChatGptStreamDecoder.decode(page)) { "merged payload is null" }
        assertEquals(2, payload.turns.size)
    }

    @Test
    fun anAttachmentOnlyTurnIsCountedAsDropped() {
        // A visible turn whose only content is a picture: there is a turn, and this text exporter
        // cannot carry it, so the count must say one turn was missed.
        val payload = requireNotNull(ChatGptStreamDecoder.decodeFlatArray(attachmentOnlyFlat)) { "attachment fixture decoded to null" }

        assertEquals(1, payload.turns.size)
        assertEquals(1, payload.rejectedTurns)
    }

    @Test
    fun cancelledToolAndSystemTurnsAreNotCounted() {
        val payload = requireNotNull(ChatGptStreamDecoder.decodeFlatArray(gatedDropsFlat)) { "gate fixture decoded to null" }

        // Three of the five nodes carry a non-text content type or an empty body, and all three are
        // already refused by a gate ABOVE those sites — a lost tool call is not a lost answer.
        assertEquals(2, payload.turns.size)
        assertEquals(0, payload.rejectedTurns)

        // Same shape on the real fixture: the `system` row, the `tool` row and the cancelled fallback
        // answer must not lift the number past the genuine content losses (here: none).
        val fromFixture = requireNotNull(ChatGptStreamDecoder.decodeFlatArray(flatJson)) { "flat fixture decoded to null" }
        assertEquals(0, fromFixture.rejectedTurns)
    }

    @Test
    fun autoModelSlugIsReportedAsUnstated() {
        val auto = requireNotNull(ChatGptStreamDecoder.decodeFlatArray(flatJson)) { "flat fixture decoded to null" }
        assertNull(auto.modelSlug)
        assertEquals("ChatGPT", auto.modelName)

        val absent = requireNotNull(ChatGptStreamDecoder.decodeFlatArray(noSlugFlat)) { "no-slug fixture decoded to null" }
        assertNull(absent.modelSlug)
        assertEquals("ChatGPT", absent.modelName)

        // `AUTO` in any case is still «no model chosen by name», not a model.
        val caps = requireNotNull(ChatGptStreamDecoder.decodeFlatArray(autoCapsFlat)) { "AUTO fixture decoded to null" }
        assertNull(caps.modelSlug)
        assertEquals("ChatGPT", caps.modelName)

        // A slug the page really stated survives as itself, in both fields.
        val stated = requireNotNull(ChatGptStreamDecoder.decodeFlatArray(realSlugFlat)) { "real-slug fixture decoded to null" }
        assertEquals("gpt-4o-2024-08-06", stated.modelSlug)
        assertEquals("gpt-4o-2024-08-06", stated.modelName)
    }
}
