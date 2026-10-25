package com.example.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.domain.bidi.BidiEngine
import com.example.ui.theme.CodeBgDark
import com.example.ui.theme.CodeComment
import com.example.ui.theme.CodeFunction
import com.example.ui.theme.CodeHeaderDark
import com.example.ui.theme.CodeKeyword
import com.example.ui.theme.CodeString
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun CodeBlockView(
    code: String,
    language: String = "plaintext",
    modifier: Modifier = Modifier,
    // Required rather than defaulted: a panel that never hears the preference would quietly keep
    // counting lines in Latin digits on a Persian screen.
    usePersianDigits: Boolean
) {
    val clipboardManager = LocalClipboardManager.current
    var copied by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val scrollState = rememberScrollState()
    val lines = remember(code) { code.lines() }

    // Same local helper shape the library tabs guard uses (fun shown in LibraryScreen.kt), so this
    // caption follows the display preference the message bubbles above it already follow.
    fun shown(value: String) = if (usePersianDigits) BidiEngine.toPersianDigits(value) else value

    // Enforce LTR layout for programming code
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        Column(
            modifier = modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .background(CodeBgDark)
                .testTag("code_block_view")
        ) {
            // Header Bar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(CodeHeaderDark)
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Terminal,
                        contentDescription = null,
                        tint = Color(0xFF38BDF8),
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = language.uppercase(),
                        color = Color(0xFFE2E8F0),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        fontFamily = FontFamily.Monospace
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "(${shown(lines.size.toString())} خط)",
                        color = Color(0xFF64748B),
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace
                    )
                }

                IconButton(
                    onClick = {
                        clipboardManager.setText(AnnotatedString(code))
                        copied = true
                        scope.launch {
                            delay(2000)
                            copied = false
                        }
                    },
                    modifier = Modifier
                        .size(32.dp)
                        .testTag("copy_code_button")
                ) {
                    Icon(
                        imageVector = if (copied) Icons.Default.Check else Icons.Default.ContentCopy,
                        contentDescription = "رونوشت کد",
                        tint = if (copied) Color(0xFF10B981) else Color(0xFF94A3B8),
                        modifier = Modifier.size(16.dp)
                    )
                }
            }

            // Code Content with Line Numbers
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 10.dp)
            ) {
                // Line Numbers
                Column(
                    modifier = Modifier
                        .padding(start = 12.dp, end = 12.dp),
                    horizontalAlignment = Alignment.End
                ) {
                    lines.indices.forEach { index ->
                        Text(
                            text = "${index + 1}",
                            color = Color(0xFF475569),
                            fontFamily = FontFamily.Monospace,
                            fontSize = 12.sp,
                            lineHeight = 20.sp
                        )
                    }
                }

                // Code Text Body with horizontal scroll
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .horizontalScroll(scrollState)
                        .padding(end = 12.dp)
                ) {
                    val annotatedCode = remember(code, language) {
                        highlightCodeSyntax(code)
                    }
                    Text(
                        text = annotatedCode,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                        lineHeight = 20.sp
                    )
                }
            }
        }
    }
}

private fun highlightCodeSyntax(code: String): AnnotatedString {
    return buildAnnotatedString {
        val keywords = setOf(
            "fun", "val", "var", "class", "interface", "data", "object", "enum", "sealed",
            "import", "package", "return", "if", "else", "when", "for", "while", "do",
            "try", "catch", "finally", "throw", "override", "suspend", "private", "public",
            "protected", "internal", "true", "false", "null", "const", "def", "async", "await",
            "select", "from", "where", "insert", "delete", "update", "table", "export", "default"
        )

        val words = code.split(Regex("(?<=\\b)|(?=\\b)|(?<=[^a-zA-Z0-9_])|(?=[^a-zA-Z0-9_])"))
        for (w in words) {
            when {
                keywords.contains(w) -> {
                    pushStyle(SpanStyle(color = CodeKeyword, fontWeight = FontWeight.Bold))
                    append(w)
                    pop()
                }
                w.startsWith("\"") || w.endsWith("\"") || w.startsWith("'") -> {
                    pushStyle(SpanStyle(color = CodeString))
                    append(w)
                    pop()
                }
                w.startsWith("//") || w.startsWith("#") -> {
                    pushStyle(SpanStyle(color = CodeComment))
                    append(w)
                    pop()
                }
                w.length > 2 && w.first().isUpperCase() -> {
                    pushStyle(SpanStyle(color = CodeFunction, fontWeight = FontWeight.SemiBold))
                    append(w)
                    pop()
                }
                else -> {
                    pushStyle(SpanStyle(color = Color(0xFFE2E8F0)))
                    append(w)
                    pop()
                }
            }
        }
    }
}
