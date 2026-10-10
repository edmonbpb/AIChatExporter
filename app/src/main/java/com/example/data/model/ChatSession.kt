package com.example.data.model

import java.util.UUID

data class ChatSession(
    val id: String = UUID.randomUUID().toString(),
    val title: String,
    val platform: PlatformPreset = PlatformPreset.CUSTOM,
    // The platform's own name, never a model invented for it: a source that did not state a model is
    // labelled with what it IS (`ChatGPT`), not with what it might have used (`GPT-4o`).
    val modelName: String = platform.displayName,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val messages: List<ChatMessage> = emptyList(),
    val tags: List<String> = emptyList(),
    val isBookmarked: Boolean = false,
    val customNotes: String = "",
    /**
     * Turns the reader saw on the page and refused to carry (a non-text part, an emptied body). `0`
     * means «never counted» — the text and file importers do not measure it — and is NOT a claim that
     * the read was complete; only the share-page decoder fills this with a measured number.
     */
    val rejectedTurns: Int = 0
) {
    val totalWords: Int
        get() = messages.sumOf { it.content.split("\\s+".toRegex()).count { word -> word.isNotBlank() } }

    val tokenEstimate: Int
        get() = (totalWords * 1.35).toInt()

    val userMessageCount: Int
        get() = messages.count { it.role == MessageRole.USER }

    val assistantMessageCount: Int
        get() = messages.count { it.role == MessageRole.ASSISTANT }

    val codeBlockCount: Int
        get() = messages.sumOf { it.codeSnippets.size }
}
