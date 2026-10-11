package com.example.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Launch
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Divider
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.model.ExportFormat
import com.example.domain.bidi.BidiEngine
import com.example.domain.exporter.DocumentExporter
import com.example.domain.exporter.FileShareHelper
import com.example.ui.components.AdaptiveLabel
import com.example.ui.components.AppScreen
import com.example.ui.components.BidiText
import com.example.ui.theme.Cyan40
import com.example.ui.theme.Indigo40
import com.example.ui.viewmodel.MainViewModel

@Composable
fun ExportStudioScreen(
    viewModel: MainViewModel,
    modifier: Modifier = Modifier
) {
    val session by viewModel.activeSession.collectAsState()
    val exportState by viewModel.exportState.collectAsState()
    val distillationState by viewModel.distillationState.collectAsState()
    // The on-screen digit preference, not the document option `exportState.convertPersianDigits`;
    // this is the flag LibraryScreen's `ExportRecordCard` and WorkspaceScreen honour for the same values.
    val usePersianDigits by viewModel.usePersianDigits.collectAsState()
    val context = LocalContext.current
    val scrollState = rememberScrollState()

    var showLivePreview by remember { mutableStateOf(false) }

    if (session == null || session!!.messages.isEmpty()) {
        Box(
            modifier = modifier
                .fillMaxSize()
                .padding(24.dp),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = "📄 برای صدور سند، ابتدا یک مکالمه را بارگذاری کنید.",
                    style = MaterialTheme.typography.titleMedium
                )
                Spacer(modifier = Modifier.height(12.dp))
                Button(
                    onClick = { viewModel.navigateTo(AppScreen.IMPORT) },
                    modifier = Modifier.testTag("export_go_import_btn")
                ) {
                    AdaptiveLabel("ورود به بخش استخراج و نمونه‌ها")
                }
            }
        }
        return
    }

    val activeSession = session!!

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(16.dp)
            .testTag("export_studio_screen")
    ) {
        // Banner Header
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(
                    brush = Brush.linearGradient(
                        listOf(Color(0xFF064E3B), Color(0xFF0F172A), Color(0xFF1E293B))
                    )
                )
                .border(1.dp, Color(0xFF059669), RoundedCornerShape(16.dp))
                .padding(18.dp)
        ) {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .background(
                                brush = Brush.linearGradient(listOf(Color(0xFF10B981), Cyan40)),
                                shape = RoundedCornerShape(10.dp)
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Default.FileDownload, contentDescription = null, tint = Color.White, modifier = Modifier.size(20.dp))
                    }
                    Spacer(modifier = Modifier.width(10.dp))
                    Column {
                        Text(
                            text = "استودیو صدور اسناد چندگانه (Export Studio)",
                            style = MaterialTheme.typography.titleMedium.copy(
                                fontWeight = FontWeight.Bold,
                                color = Color.White
                            )
                        )
                        Text(
                            text = "تولید Word (.docx)، PDF، اسلایدهای ارائه (.pptx)، Markdown و JSON",
                            style = MaterialTheme.typography.bodySmall.copy(color = Color(0xFFCBD5E1))
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Format Selector Cards
        Text(
            text = "فرمت خروجی سند را انتخاب کنید:",
            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold)
        )
        Spacer(modifier = Modifier.height(8.dp))

        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FormatOptionCard(
                    format = ExportFormat.DOCX,
                    isSelected = exportState.selectedFormat == ExportFormat.DOCX,
                    onClick = { viewModel.setExportFormat(ExportFormat.DOCX) },
                    modifier = Modifier.weight(1f)
                )
                FormatOptionCard(
                    format = ExportFormat.PDF,
                    isSelected = exportState.selectedFormat == ExportFormat.PDF,
                    onClick = { viewModel.setExportFormat(ExportFormat.PDF) },
                    modifier = Modifier.weight(1f)
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FormatOptionCard(
                    format = ExportFormat.PPTX,
                    isSelected = exportState.selectedFormat == ExportFormat.PPTX,
                    onClick = { viewModel.setExportFormat(ExportFormat.PPTX) },
                    modifier = Modifier.weight(1f)
                )
                FormatOptionCard(
                    format = ExportFormat.MARKDOWN,
                    isSelected = exportState.selectedFormat == ExportFormat.MARKDOWN,
                    onClick = { viewModel.setExportFormat(ExportFormat.MARKDOWN) },
                    modifier = Modifier.weight(1f)
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FormatOptionCard(
                    format = ExportFormat.TXT,
                    isSelected = exportState.selectedFormat == ExportFormat.TXT,
                    onClick = { viewModel.setExportFormat(ExportFormat.TXT) },
                    modifier = Modifier.weight(1f)
                )
                FormatOptionCard(
                    format = ExportFormat.JSON,
                    isSelected = exportState.selectedFormat == ExportFormat.JSON,
                    onClick = { viewModel.setExportFormat(ExportFormat.JSON) },
                    modifier = Modifier.weight(1f)
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FormatOptionCard(
                    format = ExportFormat.HTML,
                    isSelected = exportState.selectedFormat == ExportFormat.HTML,
                    onClick = { viewModel.setExportFormat(ExportFormat.HTML) },
                    modifier = Modifier.weight(1f)
                )
            }
        }

        Spacer(modifier = Modifier.height(18.dp))

        // Options Card
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Column(modifier = Modifier.padding(14.dp)) {
                Text(
                    text = "⚙️ تنظیمات سفارشی‌سازی سند",
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold)
                )
                Spacer(modifier = Modifier.height(10.dp))

                OutlinedTextField(
                    value = exportState.customTitle,
                    onValueChange = { viewModel.updateExportOptions(customTitle = it) },
                    label = { Text("عنوان رسمی سند") },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("export_title_input"),
                    shape = RoundedCornerShape(8.dp),
                    singleLine = true
                )

                Spacer(modifier = Modifier.height(10.dp))

                OptionToggleRow(
                    label = "درج جدول مشخصات فنی (مدل، تاریخ، توکن‌ها)",
                    checked = exportState.includeMetadata,
                    onCheckedChange = { viewModel.updateExportOptions(includeMetadata = it) },
                    testTag = "toggle_metadata"
                )

                OptionToggleRow(
                    label = "درج بخش خلاصه و پالایش هوشمند (در صورت وجود)",
                    checked = exportState.includeSummary,
                    onCheckedChange = { viewModel.updateExportOptions(includeSummary = it) },
                    testTag = "toggle_summary"
                )

                OptionToggleRow(
                    label = "حفظ و استایل‌دهی کدهای برنامه‌نویسی",
                    checked = exportState.includeCode,
                    onCheckedChange = { viewModel.updateExportOptions(includeCode = it) },
                    testTag = "toggle_code"
                )

                OptionToggleRow(
                    label = "تبدیل اعداد به ارقام فارسی (۰-۹)",
                    checked = exportState.convertPersianDigits,
                    onCheckedChange = { viewModel.updateExportOptions(convertPersianDigits = it) },
                    testTag = "toggle_persian_digits"
                )
            }
        }

        Spacer(modifier = Modifier.height(14.dp))

        // Live Document Preview Toggle
        OutlinedButton(
            onClick = { showLivePreview = !showLivePreview },
            modifier = Modifier
                .fillMaxWidth()
                .height(44.dp)
                .testTag("toggle_preview_btn"),
            shape = RoundedCornerShape(10.dp)
        ) {
            Icon(Icons.Default.Visibility, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(modifier = Modifier.width(6.dp))
            AdaptiveLabel(if (showLivePreview) "مخفی‌سازی پیش‌نمایش زنده" else "مشاهده پیش‌نمایش زنده ساختار سند")
        }

        if (showLivePreview) {
            Spacer(modifier = Modifier.height(10.dp))

            // The preview is the head of the file this export would really write, built from the
            // same options the export button hands the exporter. `remember` is keyed on that option
            // set, so it is rebuilt when the format or the title changes and not on every
            // recomposition, and it is only built at all while the preview is open.
            val previewOptions = exportState.toExportOptions(activeSession.title)
            val previewLines = remember(previewOptions, distillationState.currentResult, activeSession) {
                DocumentExporter.previewLines(
                    session = activeSession,
                    distillationContent = distillationState.currentResult,
                    options = previewOptions,
                    maxLines = LIVE_PREVIEW_LINES
                )
            }

            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("live_doc_preview_card"),
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    // Document header, the same header the exported file carries.
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Indigo40, RoundedCornerShape(8.dp))
                            .padding(14.dp)
                    ) {
                        Column {
                            Text(
                                text = exportState.customTitle.takeIf { it.isNotBlank() } ?: activeSession.title,
                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold, color = Color.White)
                            )
                            Text(
                                text = "مدل: ${activeSession.modelName} • پلتفرم: ${activeSession.platform.displayName}",
                                style = MaterialTheme.typography.bodySmall.copy(color = Color(0xFFA5B4FC))
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    if (previewLines == null) {
                        // A .docx and a .pptx are ZIP packages whose text is assembled inside
                        // `OpenXmlGenerator`, and a PDF is drawn on a canvas, so those three have no
                        // text form to read without writing the file to disk first. Rather than show
                        // a pretend preview, the card shows the exact name this export will write.
                        Text(
                            text = FILE_NAME_LABEL + DocumentExporter.exportFileName(
                                session = activeSession,
                                options = previewOptions,
                                timestamp = DocumentExporter.exportTimestamp()
                            ),
                            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace)
                        )
                    } else {
                        Column(modifier = Modifier.testTag("live_doc_preview_lines")) {
                            previewLines.forEach { line ->
                                Text(
                                    text = line,
                                    style = MaterialTheme.typography.bodySmall.copy(
                                        fontFamily = FontFamily.Monospace,
                                        fontSize = 10.sp
                                    ),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Main Export Trigger Button
        Button(
            onClick = { viewModel.performExport() },
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp)
                .testTag("execute_export_btn"),
            shape = RoundedCornerShape(12.dp),
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
            enabled = !exportState.isExporting
        ) {
            if (exportState.isExporting) {
                CircularProgressIndicator(
                    color = Color.White,
                    modifier = Modifier.size(20.dp),
                    strokeWidth = 2.dp
                )
                Spacer(modifier = Modifier.width(8.dp))
                AdaptiveLabel("در حال تولید و صدور سند...")
            } else {
                Icon(Icons.Default.FileDownload, contentDescription = null, modifier = Modifier.size(20.dp))
                Spacer(modifier = Modifier.width(8.dp))
                AdaptiveLabel(
                    text = "صدور فوری سند (${exportState.selectedFormat.displayName})",
                    style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold, fontSize = 15.sp)
                )
            }
        }

        // Export Failure Card (B-21). `exportState.errorMessage` was written by MainViewModel and
        // cleared at the start of the next attempt, but nothing read it. It sits directly under the
        // button that failed, above the success card, so the reason is read where the tap happened.
        // It needs no dismiss action of its own: the next attempt clears it at MainViewModel.kt:963.
        if (exportState.errorMessage != null) {
            Spacer(modifier = Modifier.height(16.dp))

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF450A0A).copy(alpha = 0.85f), RoundedCornerShape(10.dp))
                    .border(1.dp, Color(0xFFEF4444), RoundedCornerShape(10.dp))
                    .padding(14.dp)
                    .testTag("export_error_card")
            ) {
                Text(
                    text = "⚠️ خطا در تولید سند",
                    style = MaterialTheme.typography.labelMedium.copy(
                        color = Color(0xFFFCA5A5),
                        fontWeight = FontWeight.Bold
                    )
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = exportState.errorMessage ?: "",
                    style = MaterialTheme.typography.bodySmall.copy(
                        color = Color(0xFFFEE2E2),
                        fontSize = 11.5.sp,
                        lineHeight = 18.sp
                    )
                )
            }
        }

        // Export Success Card with Open & Share Actions
        if (exportState.lastExportedRecord != null) {
            val record = exportState.lastExportedRecord!!
            Spacer(modifier = Modifier.height(18.dp))

            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("export_success_card"),
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                border = androidx.compose.foundation.BorderStroke(1.5.dp, Color(0xFF10B981))
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.CheckCircle, contentDescription = null, tint = Color(0xFF10B981), modifier = Modifier.size(22.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "سند با موفقیت آماده شد!",
                            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold, color = Color(0xFF10B981))
                        )
                    }

                    Spacer(modifier = Modifier.height(8.dp))
                    Text(text = "📁 نام فایل: ${record.fileName}", style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace))
                    Text(text = "📦 حجم: ${if (usePersianDigits) BidiEngine.toPersianDigits(record.fileSizeFormatted) else record.fileSizeFormatted} | فرمت: ${record.format.displayName}", style = MaterialTheme.typography.bodySmall)

                    val downloadNote = record.downloadNote
                    if (downloadNote != null) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = downloadNote,
                            style = MaterialTheme.typography.bodySmall.copy(color = MaterialTheme.colorScheme.onSurfaceVariant),
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("download_note")
                        )
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = { FileShareHelper.openFile(context, record) },
                            modifier = Modifier
                                .weight(1f)
                                .height(44.dp)
                                .testTag("open_exported_file_btn"),
                            shape = RoundedCornerShape(8.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                        ) {
                            Icon(Icons.AutoMirrored.Filled.Launch, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            AdaptiveLabel("باز کردن", itemCount = 2)
                        }

                        Button(
                            onClick = { FileShareHelper.shareFile(context, record) },
                            modifier = Modifier
                                .weight(1f)
                                .height(44.dp)
                                .testTag("share_exported_file_btn"),
                            shape = RoundedCornerShape(8.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary)
                        ) {
                            Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            AdaptiveLabel("اشتراک‌گذاری", itemCount = 2)
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun FormatOptionCard(
    format: ExportFormat,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier
            .clickable { onClick() }
            .testTag("format_card_${format.extension}"),
        shape = RoundedCornerShape(10.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant
        ),
        border = androidx.compose.foundation.BorderStroke(
            1.5.dp,
            if (isSelected) MaterialTheme.colorScheme.primary else Color.Transparent
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(text = format.iconEmoji, fontSize = 20.sp)
            Spacer(modifier = Modifier.width(8.dp))
            Column {
                Text(
                    text = format.displayName,
                    style = MaterialTheme.typography.labelMedium.copy(
                        fontWeight = FontWeight.Bold,
                        color = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface
                    )
                )
                Text(
                    text = if (format.isDocument) "سند سازمانی با استایل RTL" else "فرمت متنی سبک",
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontSize = 10.sp,
                        color = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f) else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                )
            }
        }
    }
}

@Composable
fun OptionToggleRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    testTag: String
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp)
        )
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            modifier = Modifier.testTag(testTag),
            colors = SwitchDefaults.colors(
                checkedThumbColor = MaterialTheme.colorScheme.primary,
                checkedTrackColor = MaterialTheme.colorScheme.primaryContainer
            )
        )
    }
}

/** A handful of lines is a preview; the whole document is not, and this card is not a file reader. */
private const val LIVE_PREVIEW_LINES = 12

/** The words the export-success card already uses to name a file, reused so both cards say it once. */
private const val FILE_NAME_LABEL = "📁 نام فایل: "
