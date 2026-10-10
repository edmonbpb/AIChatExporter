package com.example.data.model

import java.util.UUID

enum class MessageRole {
    USER,
    ASSISTANT,
    SYSTEM
}

enum class TextDirection {
    RTL,
    LTR,
    MIXED
}

data class CodeSnippet(
    val id: String = UUID.randomUUID().toString(),
    val language: String = "plaintext",
    val code: String,
    val lineCount: Int = code.lines().size
)

data class ChatMessage(
    val id: String = UUID.randomUUID().toString(),
    val role: MessageRole,
    val content: String,
    val timestamp: Long = System.currentTimeMillis(),
    val modelTag: String? = null,
    val direction: TextDirection = TextDirection.MIXED,
    val reasoning: String? = null, // e.g. for DeepSeek-R1 / thinking models
    val codeSnippets: List<CodeSnippet> = emptyList()
)
