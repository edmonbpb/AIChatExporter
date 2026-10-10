package com.example.data.model

/**
 * A platform is a KNOWN SOURCE, never a model. The model column this enum used to carry was deleted on
 * purpose: the app answered «GPT-4o» for a ChatGPT share page whose stated slug was `auto`, i.e. a page
 * that never named a model at all. Nothing here may invent one — the honest label of a source that
 * stayed silent is `displayName`, and a real model reaches the app only as a slug read from the page.
 */
enum class PlatformPreset(
    val id: String,
    val displayName: String,
    val iconEmoji: String,
    val brandColorHex: Long
) {
    CHATGPT("chatgpt", "ChatGPT", "🟢", 0xFF10A37F),
    CLAUDE("claude", "Claude", "🟠", 0xFFD97706),
    GEMINI("gemini", "Google Gemini", "🔵", 0xFF2563EB),
    DEEPSEEK("deepseek", "DeepSeek", "🟣", 0xFF7C3AED),
    PERPLEXITY("perplexity", "Perplexity AI", "🌐", 0xFF0D9488),
    OLLAMA("ollama", "Ollama / Local", "🦙", 0xFF475569),
    CUSTOM("custom", "سفارشی / عمومی", "⚡", 0xFF6366F1);

    companion object {
        fun fromString(name: String?): PlatformPreset {
            return values().firstOrNull { it.id.equals(name, ignoreCase = true) || it.displayName.equals(name, ignoreCase = true) }
                ?: CUSTOM
        }
    }
}
