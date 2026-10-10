package com.example.domain.parser

import com.example.data.model.ChatMessage
import com.example.data.model.ChatSession
import com.example.data.model.MessageRole
import com.example.data.model.PlatformPreset
import com.example.domain.bidi.BidiEngine
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import java.util.regex.Pattern

object TranscriptIngestionEngine {

    private val REASONING_TAG_REGEX = Pattern.compile("<think>([\\s\\S]*?)</think>", Pattern.CASE_INSENSITIVE)

    fun parseTranscript(
        rawContent: String,
        suggestedPlatform: PlatformPreset? = null,
        suggestedTitle: String? = null
    ): ChatSession {
        val trimmed = rawContent.trim()

        // 1. Try parsing structured JSON first (AIChat schema, OpenWebUI, ChatGPT official export mapping)
        if (trimmed.startsWith("{") || trimmed.startsWith("[")) {
            tryParseJson(trimmed)?.let { return it }
        }

        // 2. Parse text turns (ChatGPT, Claude, Gemini, DeepSeek, Markdown, Raw)
        val detectedPlatform = suggestedPlatform ?: detectPlatform(rawContent)
        val messages = parseTextTurns(trimmed)

        val title = suggestedTitle?.takeIf { it.isNotBlank() }
            ?: generateTitle(messages, rawContent)

        return ChatSession(
            id = UUID.randomUUID().toString(),
            title = title,
            platform = detectedPlatform,
            // A pasted transcript never states a model, so the honest label is the platform itself.
            modelName = detectedPlatform.displayName,
            createdAt = System.currentTimeMillis(),
            updatedAt = System.currentTimeMillis(),
            messages = messages,
            tags = extractTags(messages, detectedPlatform)
        )
    }

    private fun detectPlatform(text: String): PlatformPreset {
        val lower = text.lowercase()
        return when {
            lower.contains("deepseek") || lower.contains("<think>") || lower.contains("deepseek-r1") -> PlatformPreset.DEEPSEEK
            lower.contains("claude") || lower.contains("anthropic") || (lower.contains("human:") && lower.contains("assistant:")) -> PlatformPreset.CLAUDE
            lower.contains("chatgpt") || lower.contains("gpt-4") || lower.contains("gpt-3") || lower.contains("you said:") -> PlatformPreset.CHATGPT
            lower.contains("gemini") || lower.contains("google ai") || lower.contains("gemini-") -> PlatformPreset.GEMINI
            lower.contains("perplexity") || lower.contains("sources:") -> PlatformPreset.PERPLEXITY
            lower.contains("ollama") || lower.contains("openwebui") || lower.contains("llama") -> PlatformPreset.OLLAMA
            else -> PlatformPreset.CUSTOM
        }
    }

    private fun parseTextTurns(text: String): List<ChatMessage> {
        val lines = text.lines()
        val messages = mutableListOf<ChatMessage>()

        val speakerRegex = Regex("^(User|Human|You|You said|Query|Prompt|کاربر|پرسش):?\\s*$", RegexOption.IGNORE_CASE)
        val assistantRegex = Regex("^(Assistant|ChatGPT|ChatGPT said|Claude|Gemini|DeepSeek|Perplexity|Model|Ollama|AI|هوش مصنوعی|پاسخ):?\\s*$", RegexOption.IGNORE_CASE)
        val inlineSpeakerRegex = Regex("^(User|Human|You|You said|Query|Prompt|کاربر|پرسش):\\s*(.+)$", RegexOption.IGNORE_CASE)
        val inlineAssistantRegex = Regex("^(Assistant|ChatGPT|ChatGPT said|Claude|Gemini|DeepSeek|Perplexity|Model|Ollama|AI|هوش مصنوعی|پاسخ):\\s*(.+)$", RegexOption.IGNORE_CASE)
        // متنِ روی همان خط فقط بعد از دونقطه پذیرفته می‌شود؛ بدون دونقطه مثل پیش تنها نشانهٔ خالی شمرده می‌شود
        val markdownHeaderUserRegex = Regex("^#{1,6}\\s*(User|Human|You|You said|Your prompt|Query|Prompt|کاربر|پرسش)(?::\\s*(.*))?\\s*$", RegexOption.IGNORE_CASE)
        val markdownHeaderAiRegex = Regex("^#{1,6}\\s*(Assistant|ChatGPT|ChatGPT said|Claude|Claude said|Claude responded|Claude replied|Gemini|DeepSeek|Perplexity|Model|Ollama|AI|هوش مصنوعی|پاسخ)(?::\\s*(.*))?\\s*$", RegexOption.IGNORE_CASE)
        val boldSpeakerRegex = Regex("^\\*\\*(User|Human|You|You said|Query|Prompt|کاربر|پرسش):?\\*\\*:?\\s*(.*)$", RegexOption.IGNORE_CASE)
        val boldAssistantRegex = Regex("^\\*\\*(Assistant|ChatGPT|ChatGPT said|Claude|Gemini|DeepSeek|Perplexity|Model|Ollama|AI|هوش مصنوعی|پاسخ):?\\*\\*:?\\s*(.*)$", RegexOption.IGNORE_CASE)

        var currentRole: MessageRole? = null
        var currentBuffer = StringBuilder()

        // Lines between ``` fences are code, not turn markers: nothing inside a fenced block may
        // open a new turn. The flag is local to this call, so it never leaks between parses.
        var insideCodeFence = false

        fun flushCurrent() {
            val content = currentBuffer.toString().trim()
            if (content.isNotEmpty() && currentRole != null) {
                messages.add(createMessage(currentRole!!, content))
            }
            currentBuffer = StringBuilder()
        }

        for (line in lines) {
            val trimmedLine = line.trim()
            // A fence marker flips the state and still belongs to the body, exactly as before.
            val fenceMarkerLine = trimmedLine.startsWith("```")
            if (fenceMarkerLine) {
                insideCodeFence = !insideCodeFence
            }
            val markdownUserHeader = markdownHeaderUserRegex.find(trimmedLine)
            val markdownUserText = markdownUserHeader?.groupValues?.getOrNull(2) ?: ""
            val markdownAiHeader = markdownHeaderAiRegex.find(trimmedLine)
            val markdownAiText = markdownAiHeader?.groupValues?.getOrNull(2) ?: ""

            when {
                insideCodeFence || fenceMarkerLine -> {
                    if (currentRole == null) {
                        currentRole = MessageRole.USER
                    }
                    currentBuffer.append(line).append("\n")
                }
                speakerRegex.matches(trimmedLine) || (markdownUserHeader != null && markdownUserText.isBlank()) -> {
                    flushCurrent()
                    currentRole = MessageRole.USER
                }
                assistantRegex.matches(trimmedLine) || (markdownAiHeader != null && markdownAiText.isBlank()) -> {
                    flushCurrent()
                    currentRole = MessageRole.ASSISTANT
                }
                inlineSpeakerRegex.matches(trimmedLine) -> {
                    flushCurrent()
                    currentRole = MessageRole.USER
                    val match = inlineSpeakerRegex.find(trimmedLine)
                    currentBuffer.append(match?.groupValues?.getOrNull(2) ?: "").append("\n")
                }
                inlineAssistantRegex.matches(trimmedLine) -> {
                    flushCurrent()
                    currentRole = MessageRole.ASSISTANT
                    val match = inlineAssistantRegex.find(trimmedLine)
                    currentBuffer.append(match?.groupValues?.getOrNull(2) ?: "").append("\n")
                }
                markdownUserHeader != null -> {
                    flushCurrent()
                    currentRole = MessageRole.USER
                    currentBuffer.append(markdownUserText).append("\n")
                }
                markdownAiHeader != null -> {
                    flushCurrent()
                    currentRole = MessageRole.ASSISTANT
                    currentBuffer.append(markdownAiText).append("\n")
                }
                boldSpeakerRegex.matches(trimmedLine) -> {
                    flushCurrent()
                    currentRole = MessageRole.USER
                    val match = boldSpeakerRegex.find(trimmedLine)
                    currentBuffer.append(match?.groupValues?.getOrNull(2) ?: "").append("\n")
                }
                boldAssistantRegex.matches(trimmedLine) -> {
                    flushCurrent()
                    currentRole = MessageRole.ASSISTANT
                    val match = boldAssistantRegex.find(trimmedLine)
                    currentBuffer.append(match?.groupValues?.getOrNull(2) ?: "").append("\n")
                }
                else -> {
                    if (currentRole == null) {
                        currentRole = MessageRole.USER
                    }
                    currentBuffer.append(line).append("\n")
                }
            }
        }

        flushCurrent()

        if (messages.isEmpty() && text.isNotBlank()) {
            val paragraphs = text.split(Regex("\\n\\s*\\n{2,}"))
            if (paragraphs.size > 1) {
                paragraphs.forEachIndexed { index, para ->
                    val role = if (index % 2 == 0) MessageRole.USER else MessageRole.ASSISTANT
                    messages.add(createMessage(role, para.trim()))
                }
            } else {
                messages.add(createMessage(MessageRole.USER, text.trim()))
            }
        }

        return messages
    }

    /**
     * `modelSlug` is the model the SOURCE named, nothing else. A pasted transcript names no model, so
     * the tag stays `null` and the bubble reads «the source did not say» instead of inventing `GPT-4o`.
     */
    private fun createMessage(role: MessageRole, rawContent: String, modelSlug: String? = null): ChatMessage {
        var cleanContent = rawContent
        var reasoning: String? = null

        val matcher = REASONING_TAG_REGEX.matcher(rawContent)
        if (matcher.find()) {
            reasoning = matcher.group(1)?.trim()
            cleanContent = matcher.replaceAll("").trim()
        }

        val direction = BidiEngine.detectDirection(cleanContent)
        val (_, codeBlocks) = BidiEngine.extractCodeBlocks(cleanContent)

        return ChatMessage(
            id = UUID.randomUUID().toString(),
            role = role,
            content = cleanContent,
            direction = direction,
            modelTag = if (role == MessageRole.ASSISTANT) modelSlug else null,
            reasoning = reasoning,
            codeSnippets = codeBlocks
        )
    }

    private fun tryParseJson(jsonStr: String): ChatSession? {
        try {
            // Case 1: Single Object (AIChat format or Official ChatGPT conversations.json single object)
            if (jsonStr.startsWith("{")) {
                val obj = JSONObject(jsonStr)

                // 1A. AIChat / Standard format
                if (obj.has("messages")) {
                    val title = obj.optString("title", "گفتگوی وارد شده")
                    val platformStr = obj.optString("platform", "custom")
                    // An absent `modelName` used to become the invented «Universal LLM»; the honest
                    // answer for a file that says nothing is the platform name, and nothing else.
                    val statedModel = obj.optString("modelName").takeIf { it.isNotBlank() }
                    val platform = PlatformPreset.fromString(platformStr)
                    val messagesArray = obj.getJSONArray("messages")
                    val messages = mutableListOf<ChatMessage>()

                    for (i in 0 until messagesArray.length()) {
                        val msgObj = messagesArray.getJSONObject(i)
                        val roleStr = msgObj.optString("role", "user").uppercase()
                        val role = when (roleStr) {
                            "ASSISTANT", "MODEL", "AI" -> MessageRole.ASSISTANT
                            "SYSTEM" -> MessageRole.SYSTEM
                            else -> MessageRole.USER
                        }
                        val content = msgObj.optString("content", "")
                        if (content.isNotBlank()) {
                            messages.add(createMessage(role, content, statedModel))
                        }
                    }

                    if (messages.isNotEmpty()) {
                        return ChatSession(
                            id = UUID.randomUUID().toString(),
                            title = title,
                            platform = platform,
                            modelName = statedModel ?: platform.displayName,
                            messages = messages
                        )
                    }
                }

                // 1B. Official ChatGPT Export mapping object
                if (obj.has("mapping")) {
                    val title = obj.optString("title", "مکالمه ChatGPT")
                    // A real `conversations.json` export DOES name the model it used; `auto` and an
                    // absent key both mean «not stated» and must not become a label.
                    val statedSlug = obj.optString("default_model_slug")
                        .takeIf { it.isNotBlank() && !it.equals("auto", ignoreCase = true) }
                    val mappingObj = obj.getJSONObject("mapping")
                    val messages = mutableListOf<ChatMessage>()
                    val keys = mappingObj.keys()

                    while (keys.hasNext()) {
                        val key = keys.next()
                        val node = mappingObj.getJSONObject(key)
                        if (node.has("message") && !node.isNull("message")) {
                            val msg = node.getJSONObject("message")
                            val author = msg.optJSONObject("author")
                            val roleStr = author?.optString("role", "user")?.uppercase() ?: "USER"
                            val role = if (roleStr == "ASSISTANT") MessageRole.ASSISTANT else MessageRole.USER
                            val contentObj = msg.optJSONObject("content")
                            val parts = contentObj?.optJSONArray("parts")

                            if (parts != null && parts.length() > 0) {
                                val textBuilder = StringBuilder()
                                for (p in 0 until parts.length()) {
                                    val partStr = parts.optString(p, "")
                                    if (partStr.isNotBlank()) {
                                        textBuilder.append(partStr).append("\n")
                                    }
                                }
                                val fullText = textBuilder.toString().trim()
                                if (fullText.isNotBlank()) {
                                    messages.add(createMessage(role, fullText, statedSlug))
                                }
                            }
                        }
                    }

                    if (messages.isNotEmpty()) {
                        return ChatSession(
                            id = UUID.randomUUID().toString(),
                            title = title,
                            platform = PlatformPreset.CHATGPT,
                            modelName = statedSlug ?: PlatformPreset.CHATGPT.displayName,
                            messages = messages
                        )
                    }
                }
            }

            // Case 2: Array of messages or Array of ChatGPT sessions
            if (jsonStr.startsWith("[")) {
                val array = JSONArray(jsonStr)
                if (array.length() > 0) {
                    val first = array.getJSONObject(0)
                    if (first.has("mapping")) {
                        // Return the first session from a multi-session export
                        return tryParseJson(first.toString())
                    }

                    val messages = mutableListOf<ChatMessage>()
                    for (i in 0 until array.length()) {
                        val msgObj = array.getJSONObject(i)
                        val roleStr = msgObj.optString("role", "user").uppercase()
                        val role = if (roleStr == "ASSISTANT" || roleStr == "MODEL") MessageRole.ASSISTANT else MessageRole.USER
                        val content = msgObj.optString("content", "")
                        if (content.isNotBlank()) {
                            messages.add(createMessage(role, content))
                        }
                    }
                    if (messages.isNotEmpty()) {
                        return ChatSession(
                            id = UUID.randomUUID().toString(),
                            title = generateTitle(messages, ""),
                            platform = PlatformPreset.CUSTOM,
                            messages = messages
                        )
                    }
                }
            }
        } catch (_: Exception) {}
        return null
    }

    private fun generateTitle(messages: List<ChatMessage>, fallbackText: String): String {
        val firstUserMsg = messages.firstOrNull { it.role == MessageRole.USER }?.content
        if (!firstUserMsg.isNullOrBlank()) {
            val firstLine = firstUserMsg.lines().firstOrNull { it.isNotBlank() } ?: firstUserMsg
            val clean = firstLine.replace(Regex("[#*`_-]"), "").trim()
            return if (clean.length > 45) clean.take(45) + "..." else clean
        }
        val firstLine = fallbackText.lines().firstOrNull { it.isNotBlank() } ?: "مکالمه جدید"
        return if (firstLine.length > 40) firstLine.take(40) + "..." else firstLine
    }

    private fun extractTags(messages: List<ChatMessage>, platform: PlatformPreset): List<String> {
        val tags = mutableListOf<String>()
        tags.add(platform.displayName)

        val fullText = messages.joinToString(" ") { it.content }.lowercase()
        if (fullText.contains("kotlin") || fullText.contains("android") || fullText.contains("compose")) tags.add("Android/Kotlin")
        if (fullText.contains("python") || fullText.contains("django") || fullText.contains("fastapi")) tags.add("Python")
        if (fullText.contains("react") || fullText.contains("typescript") || fullText.contains("javascript")) tags.add("Web/React")
        if (fullText.contains("sql") || fullText.contains("database") || fullText.contains("room")) tags.add("Database")
        if (fullText.contains("architecture") || fullText.contains("معماری") || fullText.contains("طراحی")) tags.add("Architecture")

        return tags.distinct()
    }
}
