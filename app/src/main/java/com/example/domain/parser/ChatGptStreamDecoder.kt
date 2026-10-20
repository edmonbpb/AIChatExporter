package com.example.domain.parser

import com.example.data.model.MessageRole
import org.json.JSONArray
import org.json.JSONObject
import java.util.regex.Pattern

internal data class ExtractedTurn(val role: MessageRole, val content: String)

/**
 * `modelName` is what the header shows and is never empty (a silent page reads `ChatGPT`, the platform,
 * not a model). `modelSlug` is the STATED model and only that: `null` whenever the page said `auto` or
 * said nothing. `rejectedTurns` counts the turns the decoder looked at and could not carry as text —
 * `0` means nothing was dropped, because this decoder always counts.
 */
internal data class SharedConversationPayload(
    val title: String,
    val modelName: String,
    val turns: List<ExtractedTurn>,
    val modelSlug: String? = null,
    val rejectedTurns: Int = 0
)

/**
 * ChatGPT share pages no longer ship `__NEXT_DATA__` or `self.__next_f.push`. The conversation sits
 * in a React-Router single-flight (turbo-stream) flat array inside `streamController.enqueue("…")`.
 * Index 0 is the root; every object is written with `_<n>` keys meaning «property name is array
 * slot n, value is the next operand»; a negative index encodes `undefined`.
 */
internal object ChatGptStreamDecoder {

    private val ENQUEUE_CALL = Pattern.compile("streamController\\.enqueue\\(")

    private const val MAX_DEPTH = 200
    // Measured: a 256 KB payload holds 3321 slots (~77 bytes per slot), so ~120 000 slots is a ~9 MB
    // chunk — a 200 turn chat. Expanding that on a phone brings OOM instead of a partial result.
    private const val MAX_SLOTS = 120_000
    private const val FALLBACK_TITLE = "گفتگوی ChatGPT"
    private const val FALLBACK_MODEL = "ChatGPT"

    /**
     * Whole page in: every enqueue literal is unescaped and decoded, and the payloads are merged.
     * Returning on the first decodable chunk was a silent loss: a page that splits its conversation
     * (or puts a shorter route payload first) would lose every turn after that chunk.
     */
    fun decode(html: String): SharedConversationPayload? {
        val matcher = ENQUEUE_CALL.matcher(html)
        var consumedThrough = -1
        val payloads = mutableListOf<SharedConversationPayload>()
        while (matcher.find()) {
            if (matcher.start() < consumedThrough) continue
            val literal = findLiteral(html, matcher.end()) ?: continue
            consumedThrough = literal.second
            val flatJson = unescape(literal.first) ?: continue
            decodeFlatArray(flatJson)?.let { payloads += it }
        }
        return merge(payloads)
    }

    /**
     * Chunks are merged turn by turn: the same turn rendered twice is carried once, in the order it
     * first appeared. The title and model come from the chunk that held the most turns, and every
     * measured drop is summed so a refusal in any chunk still reaches the banner.
     */
    private fun merge(payloads: List<SharedConversationPayload>): SharedConversationPayload? {
        if (payloads.isEmpty()) return null
        if (payloads.size == 1) return payloads.first()

        val turns = mutableListOf<ExtractedTurn>()
        val seen = HashSet<Pair<MessageRole, String>>()
        payloads.forEach { payload ->
            payload.turns.forEach { turn ->
                if (seen.add(turn.role to turn.content)) turns += turn
            }
        }
        val best = payloads.maxByOrNull { it.turns.size } ?: payloads.first()
        return SharedConversationPayload(
            title = best.title,
            modelName = best.modelName,
            modelSlug = best.modelSlug,
            turns = turns,
            rejectedTurns = payloads.sumOf { it.rejectedTurns }
        )
    }

    /**
     * Scans the literal instead of matching `("(?:[^"\\]|\\.)*")` with a regex: that loop recurses
     * once per character and overflows the stack on a real 865 KB page. Only a literal closed by
     * `)` counts, so a garbled call cannot swallow the next one.
     */
    private fun findLiteral(html: String, from: Int): Pair<String, Int>? {
        var quote = html.indexOf('"', from)
        while (quote >= 0) {
            val end = closingQuote(html, quote)
            if (end < 0) return null
            val close = closingParen(html, end + 1)
            if (close > 0) return Pair(html.substring(quote, end + 1), close + 1)
            // that quote opened a later call; resync on it rather than on any quote inside its body
            if (end + 1 >= html.length) return null
            quote = end
        }
        return null
    }

    private fun closingParen(html: String, from: Int): Int {
        var i = from
        while (i < html.length && (html[i] == ' ' || html[i] == '\t' || html[i] == '\r' || html[i] == '\n')) i++
        return if (i < html.length && html[i] == ')') i else -1
    }

    private fun closingQuote(html: String, quote: Int): Int {
        var i = quote + 1
        while (i < html.length) {
            when (html[i]) {
                '\\' -> i += 2
                '"' -> return i
                else -> i++
            }
        }
        return -1
    }

    /** Pure and testable: takes the text of one decoded flat array. Only org.json and the models. */
    fun decodeFlatArray(flatJson: String): SharedConversationPayload? {
        // Chunks carrying no conversation (telemetry, `errors`) are never opened at all.
        if (!flatJson.contains("linear_conversation") && !flatJson.contains("mapping")) return null

        val array = try {
            JSONArray(flatJson)
        } catch (_: Exception) {
            return null
        }
        if (array.length() == 0) return null
        if (array.length() > MAX_SLOTS) return null

        val root = resolve(array, array.opt(0), hashSetOf<Int>(), 0) as? JSONObject ?: return null
        val data = findData(root) ?: return null
        val extraction = extractTurns(data)
        val turns = extraction.first
        if (turns.isEmpty()) return null

        val title = text(data.opt("title")) ?: FALLBACK_TITLE
        val slug = text(data.opt("default_model_slug"))
        // `auto` is ChatGPT for «no model was chosen by name» — it is not a model and must not be
        // reported as one. `modelName` keeps the platform name for that case so the header is never
        // blank; `modelSlug` stays null, which is what the bubbles read as «the source never said».
        val stated = slug?.takeIf { !it.equals("auto", ignoreCase = true) }
        val modelName = stated ?: FALLBACK_MODEL
        return SharedConversationPayload(
            title = title,
            modelName = modelName,
            turns = turns,
            modelSlug = stated,
            rejectedTurns = extraction.second
        )
    }

    /** The literal is a JSON string; JSONObject does the unescaping, no manual quote replacement. */
    private fun unescape(literal: String): String? {
        return try {
            JSONObject("{\"v\":$literal}").opt("v") as? String
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Expands one value of the flat array into a real tree. [seen] is the index path, carried as one
     * mutable set: an index is added before descending and removed again on the way out, so siblings
     * still see only their own ancestors and a node inside its own path resolves to null.
     */
    private fun resolve(array: JSONArray, value: Any?, seen: MutableSet<Int>, depth: Int): Any? {
        if (depth > MAX_DEPTH) return null
        if (value == null || value === JSONObject.NULL) return null

        val index = when (value) {
            is Int -> value
            is Long -> if (value in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()) value.toInt() else null
            else -> null
        }
        if (index != null) {
            if (index < 0) return null
            // Past the last slot a number cannot be a reference, it is the value itself — that is how
            // `create_time` reaches the sorting in conversationNodes on the `mapping` path.
            if (index >= array.length()) return value
            if (!seen.add(index)) return null
            val resolved = resolve(array, array.opt(index), seen, depth + 1)
            seen.remove(index)
            return resolved
        }

        if (value is JSONObject) {
            val result = JSONObject()
            val keys = value.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                val name = propertyName(array, key, seen, depth) ?: continue
                val resolved = resolve(array, value.opt(key), seen, depth + 1) ?: continue
                try {
                    result.put(name, resolved)
                } catch (_: Exception) {
                }
            }
            return result
        }

        if (value is JSONArray) {
            val result = JSONArray()
            for (i in 0 until value.length()) {
                val resolved = resolve(array, value.opt(i), seen, depth + 1) ?: continue
                try {
                    result.put(resolved)
                } catch (_: Exception) {
                }
            }
            return result
        }

        return value
    }

    /** `_<n>` keys carry their name in array slot n; plain keys are already names. */
    private fun propertyName(array: JSONArray, key: String, seen: MutableSet<Int>, depth: Int): String? {
        if (key.length < 2 || key[0] != '_') return key.takeIf { it.isNotEmpty() }
        val digits = key.substring(1)
        if (!digits.all { it.isDigit() }) return key.takeIf { it.isNotEmpty() }
        val index = digits.toIntOrNull() ?: return null
        if (index >= array.length()) return null
        val name = resolve(array, array.opt(index), seen, depth + 1)
        return text(name)
    }

    private fun findData(root: JSONObject): JSONObject? {
        val loaderData = root.opt("loaderData") as? JSONObject
        if (loaderData != null) {
            val keys = loaderData.keys()
            while (keys.hasNext()) {
                val route = keys.next()
                if (!route.contains("share.")) continue
                val serverResponse = (loaderData.opt(route) as? JSONObject)?.opt("serverResponse") as? JSONObject
                val data = serverResponse?.opt("data") as? JSONObject
                if (data != null) return data
            }
        }
        // Route names change between builds, so fall back to the first object that holds a conversation.
        return traverse(root) { it.has("linear_conversation") || hasConversationMapping(it) }
    }

    private fun hasConversationMapping(obj: JSONObject): Boolean {
        val mapping = obj.opt("mapping") as? JSONObject ?: return false
        val keys = mapping.keys()
        while (keys.hasNext()) {
            val node = mapping.opt(keys.next()) as? JSONObject ?: continue
            if (node.has("message") || node.has("children")) return true
        }
        return false
    }

    private fun traverse(node: JSONObject, predicate: (JSONObject) -> Boolean): JSONObject? {
        val stack = ArrayDeque<Any>()
        stack.addLast(node)
        while (stack.isNotEmpty()) {
            val current = stack.removeLast()
            if (current is JSONObject) {
                if (predicate(current)) return current
                val keys = current.keys()
                while (keys.hasNext()) {
                    val child = current.opt(keys.next())
                    if (child != null && child !== JSONObject.NULL) stack.addLast(child)
                }
            } else if (current is JSONArray) {
                for (i in 0 until current.length()) {
                    val child = current.opt(i)
                    if (child != null && child !== JSONObject.NULL) stack.addLast(child)
                }
            }
        }
        return null
    }

    private fun conversationNodes(data: JSONObject): List<Any?> {
        val nodes = mutableListOf<Any?>()
        val linear = data.opt("linear_conversation") as? JSONArray
        if (linear != null && linear.length() > 0) {
            for (i in 0 until linear.length()) nodes.add(linear.opt(i))
            return nodes
        }

        val mapping = data.opt("mapping") as? JSONObject ?: return nodes
        val keys = mapping.keys()
        while (keys.hasNext()) {
            (mapping.opt(keys.next()) as? JSONObject)?.let { nodes.add(it) }
        }
        // `mapping` is an id map with no order; `create_time` is what restores the turns.
        return nodes.sortedBy { node ->
            val message = (node as? JSONObject)?.opt("message") as? JSONObject
            (message?.opt("create_time") as? Number)?.toDouble() ?: Double.MAX_VALUE
        }
    }

    /**
     * Turns plus the count of what was dropped. Only TWO content types carry a visible conversation
     * turn: `text` and `multimodal_text`. Everything else a share page stores beside them — `thoughts`,
     * `reasoning_recap`, `model_editable_context`, `code`, tool output — is metadata the reader never
     * sees as a turn, so skipping it is not a loss and must not be counted as one.
     *
     * Measured on a real 798 KB share page (96 nodes): 38 turns read, 24 metadata nodes, and two
     * `text` messages whose body was the empty string. The old rule counted all 26 of those as
     * rejected and made the banner claim a drop on a complete read; this rule counts none of them.
     *
     * Returns `Pair(turns, rejectedTurns)`.
     */
    private fun extractTurns(data: JSONObject): Pair<List<ExtractedTurn>, Int> {
        val turns = mutableListOf<ExtractedTurn>()
        var rejectedTurns = 0
        for (rawNode in conversationNodes(data)) {
            val node = rawNode as? JSONObject ?: continue
            val message = node.opt("message") as? JSONObject ?: node

            val fallback = (message.opt("mapping") as? JSONObject)?.opt("is_fallback")
            if (fallback == true) continue

            val role = text((message.opt("author") as? JSONObject)?.opt("role"))?.lowercase()
            if (role != "user" && role != "assistant") continue

            val content = message.opt("content") as? JSONObject ?: continue
            val type = text(content.opt("content_type"))
            // `code` is a visible turn on a share page: a code-interpreter block the reader sees as
            // its own answer. Dropping it lost real content, not metadata.
            if (type != "text" && type != "multimodal_text" && type != "code") continue

            val body = if (type == "code") codeOf(content) else bodyOf(content)
            if (body.isEmpty()) {
                // An empty text message carries nothing to lose, and a page full of them must not
                // read as a partial import. Only an attachment-only turn is a real loss: there was a
                // visible turn whose pictures this text exporter cannot carry.
                if (type == "multimodal_text" && hasAttachmentPart(content)) rejectedTurns++
                continue
            }

            turns.add(
                ExtractedTurn(
                    role = if (role == "assistant") MessageRole.ASSISTANT else MessageRole.USER,
                    content = body
                )
            )
        }
        return Pair(turns, rejectedTurns)
    }

    /**
     * The text a content object carries. A `text` part is a plain string; a `multimodal_text` part is
     * an object whose readable half is `text` (the rest is an image pointer this exporter cannot carry).
     */
    private fun bodyOf(content: JSONObject): String {
        val parts = content.opt("parts") as? JSONArray
            ?: return text(content.opt("text")).orEmpty()
        val pieces = mutableListOf<String>()
        for (i in 0 until parts.length()) {
            when (val part = parts.opt(i)) {
                is String -> part.takeIf { it.isNotBlank() }?.let { pieces.add(it) }
                is JSONObject -> text(part.opt("text"))?.let { pieces.add(it) }
            }
        }
        return pieces.joinToString("\n").trim()
    }

    /** The source of a `code` content object: `{language, code}` parts, or a plain `text` field. */
    private fun codeOf(content: JSONObject): String {
        val parts = content.opt("parts") as? JSONArray
            ?: return text(content.opt("text")).orEmpty()
        val pieces = mutableListOf<String>()
        for (i in 0 until parts.length()) {
            when (val part = parts.opt(i)) {
                is String -> part.takeIf { it.isNotBlank() }?.let { pieces.add(it) }
                is JSONObject -> text(part.opt("code"))?.let { pieces.add(it) }
            }
        }
        return pieces.joinToString("\n").trim()
    }

    /** True when a part exists that is not text we could have carried: an image, a file, an asset. */
    private fun hasAttachmentPart(content: JSONObject): Boolean {
        val parts = content.opt("parts") as? JSONArray ?: return false
        for (i in 0 until parts.length()) {
            val part = parts.opt(i) as? JSONObject ?: continue
            if (text(part.opt("text")) == null) return true
        }
        return false
    }

    private fun text(value: Any?): String? = (value as? String)?.trim()?.takeIf { it.isNotEmpty() }
}
