package com.example.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.model.DistillationType
import com.example.data.remote.gemini.GeminiClient
import com.example.domain.bidi.BidiEngine
import com.example.ui.components.AdaptiveLabel
import com.example.ui.components.AppScreen
import com.example.ui.components.BidiText
import com.example.ui.theme.Cyan40
import com.example.ui.theme.Indigo40
import com.example.ui.viewmodel.MainViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun DistillationScreen(
    viewModel: MainViewModel,
    modifier: Modifier = Modifier
) {
    val session by viewModel.activeSession.collectAsState()
    val state by viewModel.distillationState.collectAsState()
    val usePersianDigits by viewModel.usePersianDigits.collectAsState()
    val history by viewModel.distillationHistory.collectAsState()

    // Same helper shape LibraryScreen.kt:87 uses: one place decides whether a number is shown with
    // Persian digits, so the retry banner, the history count and the clock on its rows all follow the
    // same phone-level preference that the result body already follows in BidiText further down.
    fun shown(value: String) = if (usePersianDigits) BidiEngine.toPersianDigits(value) else value

    // Provenance of the text on screen. The stored record wins, because GeminiClient.lastUsedModel is
    // a single global slot and a restored older version was produced by an earlier run. The literal
    // "unknown" is what a record stores when no engine was noted, so it must not read as a model id.
    val producedByModel: String? =
        (history.firstOrNull { it.id == state.savedId }?.modelUsed ?: GeminiClient.lastUsedModel)
            ?.takeUnless { it.isBlank() || it == "unknown" }
    val isOfflineRun: Boolean = producedByModel == GeminiClient.OFFLINE_MODEL_LABEL
    val engineHeaderFa: String = when {
        isOfflineRun -> "موتور پالایش هوشمند (قواعد محلی؛ به گوگل ارسال نشد)"
        producedByModel != null -> "موتور پالایش هوشمند (پاسخ مدل ابری: $producedByModel)"
        GeminiClient.hasValidApiKey() -> "موتور پالایش هوشمند (مدل ابری، اگر سرور پاسخ دهد)"
        else -> "موتور پالایش هوشمند (قواعد محلی؛ کلید API تنظیم نشده)"
    }

    var refineInput by remember { mutableStateOf("") }
    var copied by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val clipboardManager = LocalClipboardManager.current
    val focusManager = androidx.compose.ui.platform.LocalFocusManager.current
    val scrollState = rememberScrollState()

    // Smoothly scroll down to the preview card as soon as the result is ready
    androidx.compose.runtime.LaunchedEffect(state.currentResult) {
        if (!state.currentResult.isNullOrBlank()) {
            kotlinx.coroutines.delay(200)
            scrollState.animateScrollTo(scrollState.maxValue)
        }
    }

    if (session == null || session!!.messages.isEmpty()) {
        Box(
            modifier = modifier
                .fillMaxSize()
                .padding(24.dp),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = "⚠️ برای پالایش هوشمند، ابتدا یک مکالمه را وارد کنید.",
                    style = MaterialTheme.typography.titleMedium
                )
                Spacer(modifier = Modifier.height(12.dp))
                Button(
                    onClick = { viewModel.navigateTo(AppScreen.IMPORT) },
                    modifier = Modifier.testTag("distill_go_import_btn")
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
            .testTag("distillation_screen")
    ) {
        // Banner Header
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(
                    brush = Brush.linearGradient(
                        listOf(Color(0xFF31104B), Color(0xFF1E1B4B), Color(0xFF0F172A))
                    )
                )
                .border(1.dp, Color(0xFF4C1D95), RoundedCornerShape(16.dp))
                .padding(18.dp)
        ) {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .background(
                                brush = Brush.linearGradient(listOf(Color(0xFFA855F7), Indigo40)),
                                shape = RoundedCornerShape(10.dp)
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Default.AutoAwesome, contentDescription = null, tint = Color.White, modifier = Modifier.size(20.dp))
                    }
                    Spacer(modifier = Modifier.width(10.dp))
                    Column {
                        Text(
                            text = engineHeaderFa,
                            style = MaterialTheme.typography.titleMedium.copy(
                                fontWeight = FontWeight.Bold,
                                color = Color.White
                            ),
                            modifier = Modifier.testTag("distillation_engine_header")
                        )
                        Text(
                            text = "تبدیل و پالایش مکالمه در ۶ قالب استاندارد سازمانی و مستندات حرفه‌ای",
                            style = MaterialTheme.typography.bodySmall.copy(color = Color(0xFFCBD5E1))
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Distillation Mode Cards Selector
        Text(
            text = "انتخاب قالب پالایش هوشمند:",
            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold)
        )
        Spacer(modifier = Modifier.height(8.dp))

        val modes = listOf(
            DistillationType.REPAIR_DEFECTS_ONLY,
            DistillationType.FULL_EDITED,
            DistillationType.EXECUTIVE_SUMMARY,
            DistillationType.TECHNICAL_DOC,
            DistillationType.QA_FLASHCARDS,
            DistillationType.PURE_CODE
        )

        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            modes.forEach { mode ->
                val isSelected = state.selectedType == mode
                Card(
                    modifier = Modifier
                        .width(220.dp)
                        .clickable { viewModel.selectDistillationType(mode) }
                        .testTag("mode_card_${mode.name.lowercase()}"),
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = if (isSelected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant
                    ),
                    border = androidx.compose.foundation.BorderStroke(
                        1.5.dp,
                        if (isSelected) MaterialTheme.colorScheme.primary else Color.Transparent
                    )
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(text = mode.iconEmoji, fontSize = 18.sp)
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = mode.titleFa,
                                style = MaterialTheme.typography.labelMedium.copy(
                                    fontWeight = FontWeight.Bold,
                                    color = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface
                                ),
                                maxLines = 1
                            )
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = mode.descriptionFa,
                            style = MaterialTheme.typography.bodySmall.copy(
                                fontSize = 11.sp,
                                color = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f) else MaterialTheme.colorScheme.onSurfaceVariant
                            ),
                            maxLines = 3,
                            lineHeight = 16.sp
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(14.dp))

        // Run Distillation Action Button
        Button(
            onClick = {
                focusManager.clearFocus()
                viewModel.runDistillation()
            },
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp)
                .testTag("run_distillation_button"),
            shape = RoundedCornerShape(10.dp),
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
            enabled = !state.isLoading
        ) {
            if (state.isLoading) {
                CircularProgressIndicator(
                    color = Color.White,
                    modifier = Modifier.size(20.dp),
                    strokeWidth = 2.dp
                )
                Spacer(modifier = Modifier.width(8.dp))
                AdaptiveLabel(
                    if (GeminiClient.hasValidApiKey()) "در حال پردازش با هوش مصنوعی..."
                    else "در حال ساخت با قواعد محلی..."
                )
            } else {
                Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(6.dp))
                AdaptiveLabel(
                    text = "تولید «${state.selectedType.titleFa}»",
                    fontWeight = FontWeight.Bold
                )
            }
        }

        // Error message if any
        if (state.errorMessage != null && !state.isRetrying) {
            Spacer(modifier = Modifier.height(10.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0x33EF4444), RoundedCornerShape(8.dp))
                    .padding(12.dp)
            ) {
                Text(
                    text = "⚠️ ${state.errorMessage}",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }

        // Smart Automatic Retry Banner (when Google model is busy/overloaded)
        AnimatedVisibility(
            visible = state.isRetrying || state.retryStatusMessage != null,
            enter = fadeIn() + expandVertically(),
            exit = fadeOut() + shrinkVertically()
        ) {
            Column {
                Spacer(modifier = Modifier.height(10.dp))
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("distill_retry_banner"),
                    shape = RoundedCornerShape(10.dp),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFFFEF3C7)),
                    border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFF59E0B))
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp,
                                color = Color(0xFFB45309)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = shown(state.retryStatusMessage ?: "سرورهای گوگل شلوغ است، در حال تلاش مجدد هوشمند هستیم..."),
                                style = MaterialTheme.typography.bodySmall.copy(
                                    fontWeight = FontWeight.Bold,
                                    color = Color(0xFF92400E)
                                )
                            )
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        LinearProgressIndicator(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(4.dp)
                                .clip(RoundedCornerShape(2.dp)),
                            color = Color(0xFFD97706),
                            trackColor = Color(0xFFFDE68A)
                        )
                    }
                }
            }
        }

        // Output Result Display Box
        if (state.currentResult != null) {
            Spacer(modifier = Modifier.height(18.dp))

            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("distillation_result_card"),
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    // Result Header
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(text = "👁️", fontSize = 18.sp)
                            Spacer(modifier = Modifier.width(6.dp))
                            Column {
                                Text(
                                    text = "پیش‌نمایش خروجی سند (${state.selectedType.titleFa})",
                                    style = MaterialTheme.typography.titleSmall.copy(
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                )
                                Text(
                                    text = "${state.selectedType.iconEmoji} پیش‌نمایش زنده و قابل ویرایش سند",
                                    style = MaterialTheme.typography.labelSmall.copy(
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                )
                            }
                        }

                        Row {
                            IconButton(
                                onClick = { viewModel.toggleEditManually(!state.isEditingManually) },
                                modifier = Modifier.size(32.dp)
                            ) {
                                Icon(
                                    imageVector = if (state.isEditingManually) Icons.Default.Visibility else Icons.Default.Edit,
                                    contentDescription = "Edit Text",
                                    tint = if (state.isEditingManually) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(16.dp)
                                )
                            }

                            IconButton(
                                onClick = {
                                    clipboardManager.setText(AnnotatedString(state.currentResult!!))
                                    copied = true
                                    scope.launch {
                                        delay(2000)
                                        copied = false
                                    }
                                },
                                modifier = Modifier.size(32.dp)
                            ) {
                                Icon(
                                    imageVector = if (copied) Icons.Default.Check else Icons.Default.ContentCopy,
                                    contentDescription = "Copy",
                                    tint = if (copied) Color(0xFF10B981) else MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(16.dp)
                                )
                            }

                            IconButton(
                                onClick = { viewModel.runDistillation() },
                                modifier = Modifier.size(32.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Refresh,
                                    contentDescription = "Regenerate",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    // Says out loud which engine produced the text below, so a rule-based answer
                    // never reads as a Gemini reply and a cloud answer shows the id that answered.
                    Text(
                        text = distillationProvenanceFa(producedByModel),
                        style = MaterialTheme.typography.labelSmall.copy(
                            color = if (isOfflineRun) Color(0xFFB45309)
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Start
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 4.dp)
                            .testTag("distillation_provenance")
                    )

                    // Result Body (Formatted Preview or Manual Text Editor)
                    if (state.isEditingManually) {
                        OutlinedTextField(
                            value = state.currentResult ?: "",
                            onValueChange = { viewModel.updateManualResult(it) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 200.dp, max = 360.dp),
                            shape = RoundedCornerShape(8.dp),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = MaterialTheme.colorScheme.primary,
                                unfocusedBorderColor = MaterialTheme.colorScheme.outline
                            )
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = "💡 ویرایش‌های دستی به صورت آنی در پیش‌نمایش و سند خروجی ذخیره می‌شوند.",
                            style = MaterialTheme.typography.labelSmall.copy(color = Color(0xFF10B981))
                        )
                    } else {
                        BidiText(
                            text = state.currentResult!!,
                            color = MaterialTheme.colorScheme.onSurface,
                            style = MaterialTheme.typography.bodyMedium.copy(lineHeight = 24.sp),
                            usePersianDigits = usePersianDigits
                        )
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    // 1-Tap Send to Export Studio
                    Button(
                        onClick = { viewModel.navigateTo(AppScreen.EXPORT) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(44.dp)
                            .testTag("send_to_export_btn"),
                        shape = RoundedCornerShape(8.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary)
                    ) {
                        Icon(Icons.Default.FileDownload, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        AdaptiveLabel("گام ۴: ارسال به استودیو صدور اسناد (PDF / Word) 📤")
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(20.dp))

        // Interactive Gemini AI Refiner & Chat Box
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .testTag("ai_refiner_box"),
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Column(modifier = Modifier.padding(14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.AutoMirrored.Filled.Chat, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "💬 دستیار بازنویسی و اصلاح هوشمند سند (Gemini AI)",
                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold)
                    )
                }

                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "اگر ایرادی در پیش‌نمایش بالا می‌بینید، بنویسید تا هوش مصنوعی بلافاصله آن را اصلاح کند:",
                    style = MaterialTheme.typography.bodySmall.copy(color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.5.sp)
                )

                Spacer(modifier = Modifier.height(8.dp))

                // Suggestion chips
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    val suggestions = listOf(
                        "ایموجی‌های ناخوانا و علائم اضافی را حذف کن",
                        "جهت کلمات انگلیسی و پرانتزها را اصلاح کن",
                        "لحن خودمانی را کاملاً دست‌نخورده نگه دار",
                        "غلط‌های املایی و نیم‌فاصله‌ها را یکدست کن",
                        "خلاصه‌تر و رسمی‌تر کن",
                        "افزودن جدول مقایسه فنی"
                    )
                    suggestions.forEach { prompt ->
                        SuggestionChip(
                            onClick = {
                                focusManager.clearFocus()
                                refineInput = prompt
                                viewModel.runRefineChat(prompt)
                            },
                            label = { Text(prompt, fontSize = 11.sp) }
                        )
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                // Prompt Input + Send Button
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = refineInput,
                        onValueChange = { refineInput = it },
                        placeholder = { Text("مثلاً: در خط ۳ ایموجی نامفهوم را بردار، یا جهت پرانتز انگلیسی را درست کن...") },
                        modifier = Modifier
                            .weight(1f)
                            .testTag("refine_chat_input"),
                        shape = RoundedCornerShape(10.dp),
                        singleLine = false,
                        maxLines = 3,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = MaterialTheme.colorScheme.primary,
                            unfocusedBorderColor = MaterialTheme.colorScheme.outline
                        )
                    )

                    Spacer(modifier = Modifier.width(8.dp))

                    IconButton(
                        onClick = {
                            focusManager.clearFocus()
                            if (refineInput.isNotBlank()) {
                                viewModel.runRefineChat(refineInput)
                                refineInput = ""
                            }
                        },
                        modifier = Modifier
                            .size(48.dp)
                            .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(10.dp))
                            .testTag("send_refine_btn"),
                        enabled = !state.isRefining
                    ) {
                        if (state.isRefining) {
                            CircularProgressIndicator(
                                color = Color.White,
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp
                            )
                        } else {
                            Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send", tint = Color.White, modifier = Modifier.size(20.dp))
                        }
                    }
                }
            }
        }

        // `history` is collected once at the top of this composable, next to the provenance above.
        if (history.isNotEmpty()) {
            Spacer(modifier = Modifier.height(14.dp))
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("distillation_history"),
                shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "🗂 تاریخچه پالایش‌های این مکالمه (${shown(history.size.toString())})",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    history.forEach { record ->
                        TextButton(
                            onClick = { viewModel.restoreDistillation(record.id) },
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.textButtonColors(
                                contentColor = if (record.id == state.savedId)
                                    MaterialTheme.colorScheme.primary
                                else
                                    MaterialTheme.colorScheme.onSurface
                            )
                        ) {
                            Text(
                                text = "${record.title} · " +
                                    shown(
                                        java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US)
                                            .format(java.util.Date(record.createdAt))
                                    ) +
                                    " · ${provenanceShortFa(record.modelUsed)}",
                                style = MaterialTheme.typography.bodySmall,
                                textAlign = TextAlign.Start,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * One honest Persian sentence about the engine that produced the text on screen: a rule-based local
 * answer must not read as a Gemini reply, and a cloud answer shows the id of the model that replied.
 * No model id is invented here; the value comes from the stored record or from the client itself.
 */
private fun distillationProvenanceFa(rawModel: String?): String {
    val model = rawModel?.takeUnless { it.isBlank() || it == "unknown" }
        ?: return "منبع ساخت این متن در این اجرا ثبت نشده است؛ برای دانستن سازنده، پالایش را دوباره اجرا کنید."
    return if (model == GeminiClient.OFFLINE_MODEL_LABEL)
        "این متن را موتور محلیِ همین دستگاه با قواعدِ روی خودِ مکالمه ساخته است؛ چیزی به سرورهای گوگل فرستاده نشد و این پاسخ Gemini نیست."
    else
        "این پاسخ مدل ابری «$model» است؛ همان مدلی که به این درخواست پاسخ داده است."
}

/** The same fact, short enough for one history row. */
private fun provenanceShortFa(rawModel: String?): String {
    val model = rawModel?.takeUnless { it.isBlank() || it == "unknown" } ?: return "منبع ثبت‌نشده"
    return if (model == GeminiClient.OFFLINE_MODEL_LABEL) "موتور محلی (آفلاین)" else model
}
