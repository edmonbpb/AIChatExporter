package com.example.data.model

import java.util.UUID

enum class ExportFormat(
    val extension: String,
    val mimeType: String,
    val displayName: String,
    val iconEmoji: String,
    val isDocument: Boolean = true
) {
    DOCX("docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document", "Microsoft Word (.docx)", "📄", true),
    PDF("pdf", "application/pdf", "Adobe PDF (.pdf)", "📕", true),
    PPTX("pptx", "application/vnd.openxmlformats-officedocument.presentationml.presentation", "PowerPoint Slides (.pptx)", "📊", true),
    MARKDOWN("md", "text/markdown", "Markdown (.md)", "📝", false),
    HTML("html", "text/html", "سند وب (.html)", "🌐", true),
    TXT("txt", "text/plain", "Plain Text (.txt)", "📑", false),
    JSON("json", "application/json", "Structured JSON (.json)", "📦", false)
}

data class ExportRecord(
    val id: String = UUID.randomUUID().toString(),
    val sessionId: String,
    val sessionTitle: String,
    val fileName: String,
    val format: ExportFormat,
    val filePath: String,
    val fileSizeFormatted: String,
    val exportedAt: Long = System.currentTimeMillis(),
    val downloadNote: String? = null
)
