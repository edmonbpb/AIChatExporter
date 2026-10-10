package com.example.domain.bidi

import com.example.data.model.CodeSnippet
import com.example.data.model.TextDirection
import java.util.regex.Pattern

object BidiEngine {

    const val LRM = "\u200E" // Left-to-Right Mark
    const val RLM = "\u200F" // Right-to-Left Mark
    const val ZWNJ = "\u200C" // Zero-Width Non-Joiner (نیم‌فاصله)

    private val PERSIAN_ARABIC_REGEX = Pattern.compile("[\\u0600-\\u06FF\\u0750-\\u077F\\u08A0-\\u08FF\\uFB50-\\uFDFF\\uFE70-\\uFEFF]")
    private val ENGLISH_REGEX = Pattern.compile("[a-zA-Z]")
    private val CODE_FENCE_REGEX = Pattern.compile("```([a-zA-Z0-9_+#.-]*)\\n?([\\s\\S]*?)```")

    /**
     * Determines the dominant direction of a text.
     */
    fun detectDirection(text: String): TextDirection {
        val cleanText = text.replace(CODE_FENCE_REGEX.pattern().toRegex(), "")
        var rtlCount = 0
        var ltrCount = 0

        for (ch in cleanText) {
            val s = ch.toString()
            if (PERSIAN_ARABIC_REGEX.matcher(s).find()) {
                rtlCount++
            } else if (ENGLISH_REGEX.matcher(s).find()) {
                ltrCount++
            }
        }

        return when {
            rtlCount == 0 && ltrCount == 0 -> TextDirection.LTR
            rtlCount > 0 && ltrCount == 0 -> TextDirection.RTL
            ltrCount > 0 && rtlCount == 0 -> TextDirection.LTR
            rtlCount >= ltrCount * 0.4 -> TextDirection.MIXED
            else -> TextDirection.LTR
        }
    }

    /**
     * Extracts all code blocks inside markdown text and returns both the sanitized text and code snippets.
     */
    fun extractCodeBlocks(content: String): Pair<String, List<CodeSnippet>> {
        val snippets = mutableListOf<CodeSnippet>()
        val matcher = CODE_FENCE_REGEX.matcher(content)

        while (matcher.find()) {
            val lang = matcher.group(1)?.trim()?.takeIf { it.isNotBlank() } ?: "plaintext"
            val code = matcher.group(2) ?: ""
            snippets.add(
                CodeSnippet(
                    language = lang,
                    code = code.trimEnd()
                )
            )
        }

        return Pair(content, snippets)
    }

    /**
     * Normalizes Persian half-spaces (ZWNJ) and common typographical errors.
     */
    fun normalizePersianTypography(text: String): String {
        var result = text
        // Replace common disconnected prefixes/suffixes with ZWNJ using unicode boundaries
        result = result.replace(Regex("(^|\\s)می\\s+([\\u0600-\\u06FF])"), "$1می" + ZWNJ + "$2")
        result = result.replace(Regex("(^|\\s)نمی\\s+([\\u0600-\\u06FF])"), "$1نمی" + ZWNJ + "$2")
        result = result.replace(Regex("([\\u0600-\\u06FF])\\s+ها(\\s|[.,!?;:«»)]|$)"), "$1" + ZWNJ + "ها$2")
        result = result.replace(Regex("([\\u0600-\\u06FF])\\s+های(\\s|[.,!?;:«»)]|$)"), "$1" + ZWNJ + "های$2")
        result = result.replace(Regex("([\\u0600-\\u06FF])\\s+هایی(\\s|[.,!?;:«»)]|$)"), "$1" + ZWNJ + "هایی$2")
        result = result.replace(Regex("([\\u0600-\\u06FF])\\s+تر(\\s|[.,!?;:«»)]|$)"), "$1" + ZWNJ + "تر$2")
        result = result.replace(Regex("([\\u0600-\\u06FF])\\s+ترین(\\s|[.,!?;:«»)]|$)"), "$1" + ZWNJ + "ترین$2")
        result = result.replace(Regex("([\\u0600-\\u06FF])\\s+ام(\\s|[.,!?;:«»)]|$)"), "$1" + ZWNJ + "ام$2")
        result = result.replace(Regex("([\\u0600-\\u06FF])\\s+ات(\\s|[.,!?;:«»)]|$)"), "$1" + ZWNJ + "ات$2")
        result = result.replace(Regex("([\\u0600-\\u06FF])\\s+اش(\\s|[.,!?;:«»)]|$)"), "$1" + ZWNJ + "اش$2")
        result = result.replace(Regex("([\\u0600-\\u06FF])\\s+مان(\\s|[.,!?;:«»)]|$)"), "$1" + ZWNJ + "مان$2")
        result = result.replace(Regex("([\\u0600-\\u06FF])\\s+تان(\\s|[.,!?;:«»)]|$)"), "$1" + ZWNJ + "تان$2")
        result = result.replace(Regex("([\\u0600-\\u06FF])\\s+شان(\\s|[.,!?;:«»)]|$)"), "$1" + ZWNJ + "شان$2")

        // Normalize Arabic Kaf/Yeh to Persian
        result = result.replace('ي', 'ی')
        result = result.replace('ك', 'ک')
        result = result.replace('ى', 'ی')

        // Fix excessive consecutive spaces
        result = result.replace(Regex("[ \\t]+"), " ")

        return result
    }

    private fun isPersianLine(line: String): Boolean {
        var persianChars = 0
        for (ch in line) {
            if (PERSIAN_ARABIC_REGEX.matcher(ch.toString()).find()) {
                persianChars++
            }
        }
        return persianChars > 2
    }

    /**
     * Converts standard digits (0-9) to Persian digits (۰-۹).
     */
    fun toPersianDigits(input: String): String {
        val persianDigits = charArrayOf('۰', '۱', '۲', '۳', '۴', '۵', '۶', '۷', '۸', '۹')
        val sb = StringBuilder()
        for (ch in input) {
            if (ch in '0'..'9') {
                sb.append(persianDigits[ch - '0'])
            } else {
                sb.append(ch)
            }
        }
        return sb.toString()
    }

    /**
     * Purely repairs typographical, encoding, and directional defects
     * without altering a single word, tone, or semantic meaning of the original text.
     */
    fun repairDefectsOnly(text: String): String {
        var result = text

        // 1. Clean broken Unicode replacement character and common mojibake sequences
        result = result.replace("\uFFFD", "")
        result = result.replace(Regex("\\[\\?\\]"), "")
        result = result.replace(Regex("â€¢"), "•")
        result = result.replace(Regex("â€œ|â€\u009D"), "\"")
        result = result.replace(Regex("â€™"), "’")
        result = result.replace(Regex("â€“|â€”"), "—")

        // 2. Normalize Arabic letters to standard Persian
        result = result.replace('ي', 'ی')
        result = result.replace('ك', 'ک')
        result = result.replace('ى', 'ی')
        result = result.replace('ھ', 'ه')
        result = result.replace("ۀ", "هٔ")

        // 3. Fix Persian comma and question mark in Persian contexts
        result = result.replace(Regex("([\\u0600-\\u06FF]+)\\s*,"), "$1،")
        result = result.replace(Regex("([\\u0600-\\u06FF]+)\\s*\\?"), "$1؟")
        result = result.replace(Regex("([\\u0600-\\u06FF]+)\\s*;"), "$1؛")

        // 4. Normalize half-spaces (ZWNJ) for verbs and suffixes
        result = result.replace(Regex("(^|\\s)می\\s+([\\u0600-\\u06FF])"), "$1می" + ZWNJ + "$2")
        result = result.replace(Regex("(^|\\s)نمی\\s+([\\u0600-\\u06FF])"), "$1نمی" + ZWNJ + "$2")
        result = result.replace(Regex("([\\u0600-\\u06FF])\\s+ها(\\s|[.,!?;:«»)]|$)"), "$1" + ZWNJ + "ها$2")
        result = result.replace(Regex("([\\u0600-\\u06FF])\\s+های(\\s|[.,!?;:«»)]|$)"), "$1" + ZWNJ + "های$2")
        result = result.replace(Regex("([\\u0600-\\u06FF])\\s+تر(\\s|[.,!?;:«»)]|$)"), "$1" + ZWNJ + "تر$2")
        result = result.replace(Regex("([\\u0600-\\u06FF])\\s+ترین(\\s|[.,!?;:«»)]|$)"), "$1" + ZWNJ + "ترین$2")

        // 5. Fix broken bullet formatting at line beginnings
        val lines = result.lines()
        val processedLines = lines.map { line ->
            var l = line
            // Fix leading flipped bullet points or dashes
            if (l.trimStart().startsWith("- ") || l.trimStart().startsWith("* ") || l.trimStart().startsWith("• ")) {
                val bullet = if (l.trimStart().startsWith("• ")) "• " else if (l.trimStart().startsWith("- ")) "- " else "* "
                val content = l.trimStart().substring(2).trim()
                if (isPersianLine(content)) {
                    "$RLM$bullet$content"
                } else {
                    l
                }
            } else if (isPersianLine(l)) {
                // Directional protection for inline English tokens
                l.replace(Regex("([a-zA-Z0-9_.-]+)"), "$LRM$1$RLM")
            } else {
                l
            }
        }

        return processedLines.joinToString("\n")
    }
}
