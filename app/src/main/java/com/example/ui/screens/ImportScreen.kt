package com.example.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.DriveFolderUpload
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.TabRowDefaults
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.model.PlatformPreset
import com.example.domain.parser.SharedLinkExtractor
import com.example.ui.components.AdaptiveLabel
import com.example.ui.theme.Cyan40
import com.example.ui.theme.Indigo40
import com.example.ui.viewmodel.MainViewModel

@Composable
fun ImportScreen(
    viewModel: MainViewModel,
    modifier: Modifier = Modifier
) {
    val rawText by viewModel.rawInputText.collectAsState()
    val selectedPlatform by viewModel.selectedImportPlatform.collectAsState()
    val customTitle by viewModel.customImportTitle.collectAsState()
    val importMode by viewModel.importMode.collectAsState()
    val sharedUrlInput by viewModel.sharedUrlInput.collectAsState()
    val isExtractingUrl by viewModel.isExtractingUrl.collectAsState()
    val urlProgress by viewModel.urlExtractionProgress.collectAsState()
    val urlExtractionError by viewModel.urlExtractionError.collectAsState()

    val clipboardManager = LocalClipboardManager.current
    val focusManager = androidx.compose.ui.platform.LocalFocusManager.current
    val scrollState = rememberScrollState()
    val context = LocalContext.current

    // Native Android File Picker
    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            try {
                context.contentResolver.openInputStream(uri)?.use { inputStream ->
                    val fileContent = inputStream.bufferedReader().use { it.readText() }
                    viewModel.rawInputText.value = fileContent
                    viewModel.showMessage("فایل با موفقیت بارگذاری شد 📂")
                }
            } catch (e: Exception) {
                viewModel.showMessage("خطا در خواندن فایل: ${e.message}")
            }
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(16.dp)
            .testTag("import_screen")
    ) {
        // Commercial Hero Header Banner
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(
                    brush = Brush.linearGradient(
                        listOf(
                            Color(0xFF1E1B4B),
                            Color(0xFF0F172A),
                            Color(0xFF1E293B)
                        )
                    )
                )
                .border(1.dp, Color(0xFF334155), RoundedCornerShape(16.dp))
                .padding(20.dp)
        ) {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .background(
                                brush = Brush.linearGradient(listOf(Indigo40, Cyan40)),
                                shape = RoundedCornerShape(10.dp)
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.AutoAwesome,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(22.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Column {
                        Text(
                            text = "سامانه استخراج و پردازش مکالمات هوش مصنوعی",
                            style = MaterialTheme.typography.titleMedium.copy(
                                fontWeight = FontWeight.Bold,
                                color = Color.White
                            )
                        )
                        Text(
                            text = "استخراج آنلاین از لینک‌های اشتراک‌گذاری، فایل یا کپی مستقیم متن",
                            style = MaterialTheme.typography.bodySmall.copy(color = Color(0xFF94A3B8))
                        )
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                // Supported Platform Badges
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    PlatformPreset.values().forEach { preset ->
                        val isSelected = selectedPlatform == preset
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(20.dp))
                                .background(if (isSelected) Color(preset.brandColorHex) else Color(0x33334155))
                                .clickable { viewModel.selectedImportPlatform.value = preset }
                                .padding(horizontal = 12.dp, vertical = 6.dp)
                                .testTag("platform_chip_${preset.id}")
                        ) {
                            Text(
                                text = "${preset.iconEmoji} ${preset.displayName}",
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                    color = if (isSelected) Color.White else Color(0xFFCBD5E1),
                                    fontSize = 11.5.sp
                                )
                            )
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Ingestion Mode Tabs (0 = Shared Link, 1 = Manual Text & File)
        TabRow(
            selectedTabIndex = importMode,
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
            contentColor = MaterialTheme.colorScheme.primary,
            indicator = { tabPositions ->
                if (importMode in tabPositions.indices) {
                    TabRowDefaults.SecondaryIndicator(
                        modifier = Modifier.tabIndicatorOffset(tabPositions[importMode]),
                        height = 3.dp,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            },
            modifier = Modifier
                .clip(RoundedCornerShape(12.dp))
                .testTag("import_mode_tabs")
        ) {
            // Tab Index 0: استخراج از لینک (URL)
            Tab(
                selected = importMode == 0,
                onClick = {
                    focusManager.clearFocus()
                    viewModel.importMode.value = 0
                },
                text = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Default.Link,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                            tint = if (importMode == 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        AdaptiveLabel(
                            "استخراج از لینک (URL)",
                            itemCount = 2,
                            fontWeight = if (importMode == 0) FontWeight.Bold else FontWeight.Medium,
                            color = if (importMode == 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                modifier = Modifier.testTag("tab_shared_link")
            )
            // Tab Index 1: ورود متن یا فایل
            Tab(
                selected = importMode == 1,
                onClick = {
                    focusManager.clearFocus()
                    viewModel.importMode.value = 1
                },
                text = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Default.Description,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                            tint = if (importMode == 1) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        AdaptiveLabel(
                            "ورود متن یا فایل",
                            itemCount = 2,
                            fontWeight = if (importMode == 1) FontWeight.Bold else FontWeight.Medium,
                            color = if (importMode == 1) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                modifier = Modifier.testTag("tab_text_file")
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        // TAB 0: SHARED LINK EXTRACTION UI (Active when importMode == 0)
        AnimatedVisibility(visible = importMode == 0) {
            Column {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("shared_link_card"),
                    shape = RoundedCornerShape(14.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
                ) {
                    Column(modifier = Modifier.padding(18.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(36.dp)
                                    .background(Color(0xFF38BDF8).copy(alpha = 0.15f), RoundedCornerShape(8.dp)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Link,
                                    contentDescription = null,
                                    tint = Color(0xFF38BDF8),
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                            Spacer(modifier = Modifier.width(10.dp))
                            Column {
                                Text(
                                    text = "استخراج مستقیم از لینک اشتراک‌گذاری",
                                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold)
                                )
                                Text(
                                    text = "پشتیبانی از لینک‌های ChatGPT، Claude، Perplexity و DeepSeek",
                                    style = MaterialTheme.typography.bodySmall.copy(
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        fontSize = 11.sp
                                    )
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(16.dp))

                        // URL Input Field
                        OutlinedTextField(
                            value = sharedUrlInput,
                            onValueChange = { viewModel.sharedUrlInput.value = it },
                            label = { Text("آدرس لینک اشتراک‌گذاری (URL)") },
                            placeholder = { Text("https://chatgpt.com/share/... یا https://claude.ai/share/...") },
                            leadingIcon = {
                                Icon(Icons.Default.Link, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                            },
                            trailingIcon = {
                                if (sharedUrlInput.isNotBlank()) {
                                    IconButton(onClick = {
                                        focusManager.clearFocus()
                                        viewModel.sharedUrlInput.value = ""
                                    }) {
                                        Icon(Icons.Default.Delete, contentDescription = "پاک کردن", tint = MaterialTheme.colorScheme.error)
                                    }
                                }
                            },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
                            keyboardActions = KeyboardActions(onGo = { viewModel.extractFromSharedLink() }),
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("shared_url_input"),
                            shape = RoundedCornerShape(10.dp)
                        )

                        // Detected Platform Indicator
                        if (sharedUrlInput.isNotBlank()) {
                            val detectedPlatform = SharedLinkExtractor.detectPlatformFromUrl(sharedUrlInput)
                            Spacer(modifier = Modifier.height(8.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = Icons.Default.CheckCircle,
                                    contentDescription = null,
                                    tint = Color(0xFF10B981),
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = "پلتفرم شناسایی‌شده: ${detectedPlatform.displayName} ${detectedPlatform.iconEmoji}",
                                    style = MaterialTheme.typography.labelSmall.copy(
                                        color = Color(0xFF10B981),
                                        fontWeight = FontWeight.Bold
                                    )
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(14.dp))

                        // Quick Actions (Paste from Clipboard & Fetch)
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            OutlinedButton(
                                onClick = {
                                    val clip = clipboardManager.getText()?.text?.trim()
                                    if (!clip.isNullOrBlank()) {
                                        val url = SharedLinkExtractor.extractUrl(clip) ?: clip
                                        viewModel.sharedUrlInput.value = url
                                        viewModel.showMessage("لینک از کلیپ‌بورد درج شد 📋")
                                    } else {
                                        viewModel.showMessage("کلیپ‌بورد خالی است.")
                                    }
                                },
                                modifier = Modifier
                                    .weight(1f)
                                    .height(48.dp)
                                    .testTag("paste_url_button"),
                                shape = RoundedCornerShape(10.dp)
                            ) {
                                Icon(Icons.Default.ContentPaste, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                AdaptiveLabel("جایگذاری از کلیپ‌بورد", fontSize = 12.sp, itemCount = 2)
                            }

                            Button(
                                onClick = {
                                    focusManager.clearFocus()
                                    viewModel.extractFromSharedLink()
                                },
                                enabled = !isExtractingUrl && sharedUrlInput.isNotBlank(),
                                modifier = Modifier
                                    .weight(1.3f)
                                    .height(48.dp)
                                    .testTag("extract_shared_link_button"),
                                shape = RoundedCornerShape(10.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                            ) {
                                if (isExtractingUrl) {
                                    CircularProgressIndicator(
                                        color = Color.White,
                                        modifier = Modifier.size(20.dp),
                                        strokeWidth = 2.dp
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    AdaptiveLabel("در حال دریافت...", fontSize = 13.sp, itemCount = 2)
                                } else {
                                    Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(18.dp))
                                    Spacer(modifier = Modifier.width(6.dp))
                                    AdaptiveLabel("استخراج مکالمه", fontWeight = FontWeight.Bold, fontSize = 13.sp, itemCount = 2)
                                }
                            }
                        }

                        // Live Extraction Progress Card
                        AnimatedVisibility(visible = isExtractingUrl) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = 16.dp)
                                    .background(Color(0xFF0F172A), RoundedCornerShape(10.dp))
                                    .border(1.dp, Color(0xFF334155), RoundedCornerShape(10.dp))
                                    .padding(14.dp)
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        imageVector = Icons.Default.Sync,
                                        contentDescription = null,
                                        tint = Color(0xFF38BDF8),
                                        modifier = Modifier.size(20.dp)
                                    )
                                    Spacer(modifier = Modifier.width(10.dp))
                                    Text(
                                        text = urlProgress ?: "در حال پردازش داده‌های صفحه...",
                                        style = MaterialTheme.typography.bodySmall.copy(
                                            color = Color.White,
                                            fontWeight = FontWeight.SemiBold
                                        )
                                    )
                                }
                            }
                        }

                        // Extraction Error Card with Quick Switch to Text Tab
                        AnimatedVisibility(visible = urlExtractionError != null && !isExtractingUrl) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = 16.dp)
                                    .background(Color(0xFF450A0A).copy(alpha = 0.85f), RoundedCornerShape(10.dp))
                                    .border(1.dp, Color(0xFFEF4444), RoundedCornerShape(10.dp))
                                    .padding(14.dp)
                            ) {
                                Text(
                                    text = "⚠️ خطا در دریافت مستقیم از لینک",
                                    style = MaterialTheme.typography.labelMedium.copy(
                                        color = Color(0xFFFCA5A5),
                                        fontWeight = FontWeight.Bold
                                    )
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = urlExtractionError ?: "",
                                    style = MaterialTheme.typography.bodySmall.copy(
                                        color = Color(0xFFFEE2E2),
                                        fontSize = 11.5.sp,
                                        lineHeight = 18.sp
                                    )
                                )
                                Spacer(modifier = Modifier.height(10.dp))
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Button(
                                        onClick = {
                                            viewModel.importMode.value = 1
                                            viewModel.urlExtractionError.value = null
                                        },
                                        shape = RoundedCornerShape(8.dp),
                                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                                        modifier = Modifier.weight(1.2f)
                                    ) {
                                        AdaptiveLabel("انتقال به تب ورود متن ✍️", fontSize = 11.5.sp, itemCount = 2)
                                    }
                                    OutlinedButton(
                                        onClick = { viewModel.extractFromSharedLink() },
                                        shape = RoundedCornerShape(8.dp),
                                        modifier = Modifier.weight(0.8f)
                                    ) {
                                        AdaptiveLabel("تلاش مجدد 🔄", fontSize = 11.5.sp, itemCount = 2)
                                    }
                                }
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                // Helpful Usage Guide
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(10.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(
                            text = "💡 راهنمای دریافت لینک اشتراک‌گذاری:",
                            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold)
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = "۱. در چت‌جی‌پی‌تی روی دکمه Share Chat (اشتراک‌گذاری) در بالای صفحه بزنید و لینک را کپی کنید.\n" +
                                    "۲. در کلاد (Claude) روی منوی مکالمه و سپس Share Chat بزنید.\n" +
                                    "۳. لینک کپی‌شده را در کادر بالا قرار داده و «استخراج مکالمه» را بزنید.",
                            style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.5.sp, lineHeight = 19.sp)
                        )
                    }
                }
            }
        }

        // TAB 1: MANUAL TEXT & FILE INGESTION UI (Active when importMode == 1)
        AnimatedVisibility(visible = importMode == 1) {
            Column {
                // If user pasted a link in text box, give quick suggestion
                if (SharedLinkExtractor.containsUrl(rawText) && rawText.length < 500) {
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 12.dp),
                        shape = RoundedCornerShape(10.dp),
                        colors = CardDefaults.cardColors(containerColor = Color(0xFF0F172A)),
                        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF38BDF8))
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "🔗 لینک اشتراک‌گذاری تشخیص داده شد!",
                                    style = MaterialTheme.typography.labelMedium.copy(
                                        fontWeight = FontWeight.Bold,
                                        color = Color(0xFF38BDF8)
                                    )
                                )
                                Text(
                                    text = "آیا مایلید محتوای گفتگو مستقیماً از این لینک دانلود و استخراج شود؟",
                                    style = MaterialTheme.typography.bodySmall.copy(color = Color(0xFF94A3B8), fontSize = 11.sp)
                                )
                            }
                            Spacer(modifier = Modifier.width(8.dp))
                            Button(
                                onClick = {
                                    val extracted = SharedLinkExtractor.extractUrl(rawText) ?: rawText
                                    viewModel.sharedUrlInput.value = extracted
                                    viewModel.extractFromSharedLink(extracted)
                                },
                                shape = RoundedCornerShape(8.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0284C7))
                            ) {
                                AdaptiveLabel("استخراج آنی", fontSize = 12.sp)
                            }
                        }
                    }
                }

                // File Picker & Clipboard Actions Row
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = { filePickerLauncher.launch("*/*") },
                        modifier = Modifier
                            .weight(1f)
                            .height(44.dp)
                            .testTag("pick_file_button"),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.DriveFolderUpload,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        AdaptiveLabel("بارگذاری فایل (JSON/TXT/MD)", fontSize = 12.sp, itemCount = 2)
                    }

                    OutlinedButton(
                        onClick = {
                            val clip = clipboardManager.getText()?.text
                            if (!clip.isNullOrBlank()) {
                                viewModel.rawInputText.value = clip
                                viewModel.showMessage("متن از کلیپ‌بورد درج شد 📋")
                            } else {
                                viewModel.showMessage("کلیپ‌بورد خالی است.")
                            }
                        },
                        modifier = Modifier
                            .weight(1f)
                            .height(44.dp)
                            .testTag("paste_clipboard_button"),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.ContentPaste,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        AdaptiveLabel("جایگذاری از کلیپ‌بورد", fontSize = 12.sp, itemCount = 2)
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                // Custom Document Title Field
                OutlinedTextField(
                    value = customTitle,
                    onValueChange = { viewModel.customImportTitle.value = it },
                    label = { Text("عنوان اختصاصی سند / جلسه (اختیاری)") },
                    placeholder = { Text("مثلاً: مستندات فنی معماری سرویس پرداخت") },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("custom_title_input"),
                    shape = RoundedCornerShape(10.dp),
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = MaterialTheme.colorScheme.primary,
                        unfocusedBorderColor = MaterialTheme.colorScheme.outline
                    )
                )

                Spacer(modifier = Modifier.height(12.dp))

                // Raw Transcript Input Box
                Box(modifier = Modifier.fillMaxWidth()) {
                    OutlinedTextField(
                        value = rawText,
                        onValueChange = { viewModel.rawInputText.value = it },
                        label = { Text("متن رونوشت مکالمه یا ساختار JSON") },
                        placeholder = {
                            Text(
                                "متن کپی‌شده از ChatGPT، Claude، DeepSeek یا فایل متنی را در اینجا قرار دهید...\n\nUser: سلام...\nAssistant: درود بر شما...",
                                fontSize = 12.sp,
                                lineHeight = 18.sp
                            )
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 200.dp, max = 340.dp)
                            .testTag("raw_transcript_input"),
                        shape = RoundedCornerShape(12.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = MaterialTheme.colorScheme.primary,
                            unfocusedBorderColor = MaterialTheme.colorScheme.outline
                        )
                    )

                    // Clear button
                    if (rawText.isNotBlank()) {
                        IconButton(
                            onClick = {
                                focusManager.clearFocus()
                                viewModel.rawInputText.value = ""
                            },
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .padding(8.dp)
                                .size(32.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Delete,
                                contentDescription = "پاک‌کردن متن",
                                tint = MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Main Commercial Action Button
                Button(
                    onClick = {
                        focusManager.clearFocus()
                        viewModel.parseAndLoadTranscript()
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp)
                        .testTag("parse_transcript_button"),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary
                    )
                ) {
                    Icon(
                        imageVector = Icons.Default.PlayArrow,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    AdaptiveLabel(
                        text = "تحلیل و ورود به محیط کار (Workspace)",
                        style = MaterialTheme.typography.labelLarge.copy(
                            fontWeight = FontWeight.Bold,
                            fontSize = 15.sp
                        )
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // System Capability Cards
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            CapabilityMiniBadge(
                icon = Icons.Default.Psychology,
                title = "تشخیص خودکار نوبت‌ها",
                subtitle = "تفکیک هوشمند User و AI",
                modifier = Modifier.weight(1f)
            )
            CapabilityMiniBadge(
                icon = Icons.Default.Code,
                title = "ایزوله‌سازی کدهای LTR",
                subtitle = "حفظ سینتکس بدون باگ BiDi",
                modifier = Modifier.weight(1f)
            )
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Enterprise Security Note
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Default.Security,
                contentDescription = null,
                tint = Color(0xFF10B981),
                modifier = Modifier.size(14.dp)
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = "تفکیکِ نوبت‌ها روی همین دستگاه انجام می‌شود؛ مسیرهایِ بیرونی در تنظیمات نوشته شده‌اند",
                style = MaterialTheme.typography.labelSmall.copy(
                    color = Color(0xFF10B981),
                    fontSize = 11.sp,
                    textAlign = TextAlign.Center
                )
            )
        }
    }
}

@Composable
fun CapabilityMiniBadge(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(10.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Row(
            modifier = Modifier.padding(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Column {
                Text(
                    text = title,
                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold)
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                )
            }
        }
    }
}
