package com.example.ui.screens

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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.model.ChatMessage
import com.example.data.model.MessageRole
import com.example.domain.bidi.BidiEngine
import com.example.domain.parser.partialImportNotice
import com.example.ui.components.AdaptiveLabel
import com.example.ui.components.AppScreen
import com.example.ui.components.BidiText
import com.example.ui.components.CodeBlockView
import com.example.ui.components.StatBadge
import com.example.ui.viewmodel.MainViewModel

@Composable
fun WorkspaceScreen(
    viewModel: MainViewModel,
    modifier: Modifier = Modifier
) {
    val session by viewModel.activeSession.collectAsState()
    val searchQuery by viewModel.searchQuery.collectAsState()
    val filterCodeOnly by viewModel.filterOnlyCode.collectAsState()
    val filterUserOnly by viewModel.filterOnlyUser.collectAsState()
    val filterAiOnly by viewModel.filterOnlyAssistant.collectAsState()
    val usePersianDigits by viewModel.usePersianDigits.collectAsState()

    var editingMessage by remember { mutableStateOf<ChatMessage?>(null) }
    var editContentText by remember { mutableStateOf("") }

    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current
    val focusManager = androidx.compose.ui.platform.LocalFocusManager.current

    if (session == null || session!!.messages.isEmpty()) {
        Box(
            modifier = modifier
                .fillMaxSize()
                .padding(24.dp),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = "📭 مکالمه‌ای بارگذاری نشده است.",
                    style = MaterialTheme.typography.titleMedium
                )
                Spacer(modifier = Modifier.height(12.dp))
                Button(
                    onClick = { viewModel.navigateTo(AppScreen.IMPORT) },
                    modifier = Modifier.testTag("go_to_import_btn")
                ) {
                    AdaptiveLabel("ورود به بخش بارگذاری و نمونه‌ها")
                }
            }
        }
        return
    }

    val activeSession = session!!

    // Filter messages based on search and chip filters
    val filteredMessages = remember(
        activeSession.messages,
        searchQuery,
        filterCodeOnly,
        filterUserOnly,
        filterAiOnly
    ) {
        activeSession.messages.filter { msg ->
            val matchQuery = searchQuery.isBlank() || msg.content.contains(searchQuery, ignoreCase = true)
            val matchCode = !filterCodeOnly || msg.codeSnippets.isNotEmpty()
            val matchUser = !filterUserOnly || msg.role == MessageRole.USER
            val matchAi = !filterAiOnly || msg.role == MessageRole.ASSISTANT
            matchQuery && matchCode && matchUser && matchAi
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .testTag("workspace_screen")
    ) {
        // Stats and Metadata Bar
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surface)
                .padding(horizontal = 16.dp, vertical = 10.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                StatBadge(
                    label = "مدل",
                    value = activeSession.modelName,
                    backgroundColor = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                )
                StatBadge(
                    label = "پیام‌ها",
                    value = if (usePersianDigits) BidiEngine.toPersianDigits(activeSession.messages.size.toString()) else activeSession.messages.size.toString()
                )
                StatBadge(
                    label = "کلمات",
                    value = if (usePersianDigits) BidiEngine.toPersianDigits(activeSession.totalWords.toString()) else activeSession.totalWords.toString()
                )
                StatBadge(
                    label = "توکن",
                    value = if (usePersianDigits) BidiEngine.toPersianDigits(activeSession.tokenEstimate.toString()) else activeSession.tokenEstimate.toString()
                )
                StatBadge(
                    label = "کدها",
                    value = if (usePersianDigits) BidiEngine.toPersianDigits(activeSession.codeBlockCount.toString()) else activeSession.codeBlockCount.toString(),
                    backgroundColor = Color(0xFF0F172A),
                    contentColor = Color(0xFF38BDF8)
                )
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Search Bar
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { viewModel.searchQuery.value = it },
                placeholder = { Text("جستجو در پیام‌ها و کدهای مکالمه...") },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Default.Search,
                        contentDescription = "Search",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                },
                trailingIcon = {
                    if (searchQuery.isNotBlank()) {
                        IconButton(onClick = {
                            focusManager.clearFocus()
                            viewModel.searchQuery.value = ""
                        }) {
                            Icon(Icons.Default.Delete, contentDescription = "Clear Search", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(50.dp)
                    .testTag("workspace_search_input"),
                shape = RoundedCornerShape(10.dp),
                singleLine = true
            )

            Spacer(modifier = Modifier.height(8.dp))

            // Filter Chips
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilterChip(
                    selected = filterCodeOnly,
                    onClick = { viewModel.filterOnlyCode.value = !filterCodeOnly },
                    label = { AdaptiveLabel("فقط کدها (${if (usePersianDigits) BidiEngine.toPersianDigits(activeSession.codeBlockCount.toString()) else activeSession.codeBlockCount.toString()})") },
                    leadingIcon = { Icon(Icons.Default.Code, contentDescription = null, modifier = Modifier.size(16.dp)) },
                    modifier = Modifier.testTag("filter_code_chip")
                )
                FilterChip(
                    selected = filterUserOnly,
                    onClick = {
                        viewModel.filterOnlyUser.value = !filterUserOnly
                        if (viewModel.filterOnlyUser.value) viewModel.filterOnlyAssistant.value = false
                    },
                    label = { AdaptiveLabel("پرسش‌های کاربر (${if (usePersianDigits) BidiEngine.toPersianDigits(activeSession.userMessageCount.toString()) else activeSession.userMessageCount.toString()})") },
                    modifier = Modifier.testTag("filter_user_chip")
                )
                FilterChip(
                    selected = filterAiOnly,
                    onClick = {
                        viewModel.filterOnlyAssistant.value = !filterAiOnly
                        if (viewModel.filterOnlyAssistant.value) viewModel.filterOnlyUser.value = false
                    },
                    label = { AdaptiveLabel("پاسخ‌های هوش مصنوعی (${if (usePersianDigits) BidiEngine.toPersianDigits(activeSession.assistantMessageCount.toString()) else activeSession.assistantMessageCount.toString()})") },
                    modifier = Modifier.testTag("filter_ai_chip")
                )
            }
        }

        // A partial read stays on screen for as long as this conversation is open. The Snackbar cannot
        // carry the sentence (Material 3 clips it and the host dismisses it by itself), so the very same
        // pure notice the toast is gated on is rendered here: no maxLines, no timeout, no dismissal.
        val partialNotice = partialImportNotice(activeSession)
        if (partialNotice != null) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
                    .testTag("workspace_partial_import_banner"),
                shape = RoundedCornerShape(10.dp),
                colors = CardDefaults.cardColors(
                    containerColor = Color(0xFF451A03),
                    contentColor = Color(0xFFFDE68A)
                ),
                border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFF59E0B))
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "⚠️",
                        fontSize = 15.sp
                    )
                    BidiText(
                        text = partialNotice,
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontWeight = FontWeight.Bold
                        )
                    )
                }
            }
        }

        // Messages Stream
        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            itemsIndexed(filteredMessages, key = { _, item -> item.id }) { index, msg ->
                MessageTurnCard(
                    message = msg,
                    index = index,
                    usePersianDigits = usePersianDigits,
                    onEdit = {
                        editingMessage = msg
                        editContentText = msg.content
                    },
                    onDelete = { viewModel.deleteMessage(msg.id) },
                    onCopy = {
                        clipboardManager.setText(AnnotatedString(msg.content))
                        viewModel.showMessage("متن نوبت کپی گردید 📋")
                    }
                )
            }
        }

        // Bottom Fast Action Ribbon
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Button(
                onClick = { viewModel.navigateTo(AppScreen.DISTILL) },
                modifier = Modifier
                    .weight(1f)
                    .height(46.dp)
                    .testTag("go_to_distill_btn"),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                shape = RoundedCornerShape(10.dp)
            ) {
                Icon(Icons.Default.AutoAwesome, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(6.dp))
                AdaptiveLabel(
                    "گام ۳: پالایش ✨",
                    fontWeight = FontWeight.Bold,
                    fontSize = 12.5.sp,
                    itemCount = 3
                )
            }

            Button(
                onClick = { viewModel.navigateTo(AppScreen.EXPORT) },
                modifier = Modifier
                    .weight(1f)
                    .height(46.dp)
                    .testTag("go_to_export_btn"),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary),
                shape = RoundedCornerShape(10.dp)
            ) {
                Icon(Icons.Default.FileDownload, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(6.dp))
                AdaptiveLabel(
                    "گام ۴: صدور سند 📄",
                    fontWeight = FontWeight.Bold,
                    fontSize = 12.5.sp,
                    itemCount = 3
                )
            }

            IconButton(
                onClick = { viewModel.saveActiveSessionToDatabase() },
                modifier = Modifier
                    .size(46.dp)
                    .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(10.dp))
                    .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(10.dp))
                    .testTag("workspace_save_btn")
            ) {
                Icon(
                    imageVector = Icons.Default.Save,
                    contentDescription = "Save DB",
                    tint = MaterialTheme.colorScheme.primary
                )
            }
        }
    }

    // Quick Edit Message Dialog
    if (editingMessage != null) {
        AlertDialog(
            onDismissRequest = { editingMessage = null },
            title = { Text("ویرایش نوبت پیام") },
            text = {
                OutlinedTextField(
                    value = editContentText,
                    onValueChange = { editContentText = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(240.dp)
                        .testTag("edit_message_input"),
                    shape = RoundedCornerShape(8.dp)
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.updateMessageContent(editingMessage!!.id, editContentText)
                        editingMessage = null
                    },
                    modifier = Modifier.testTag("save_edit_message_btn")
                ) {
                    AdaptiveLabel("ذخیره تغییرات", itemCount = 2)
                }
            },
            dismissButton = {
                TextButton(onClick = { editingMessage = null }) {
                    AdaptiveLabel("انصراف", itemCount = 2)
                }
            }
        )
    }
}

@Composable
fun MessageTurnCard(
    message: ChatMessage,
    index: Int,
    usePersianDigits: Boolean,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onCopy: () -> Unit,
    modifier: Modifier = Modifier
) {
    val isUser = message.role == MessageRole.USER
    // `null` is the NORMAL case since wave 1: a source that never stated a model leaves modelTag empty.
    // Such a turn gets no model bracket at all — the old «(AI Assistant)» dressed a role up as a model
    // name and was printed on almost every bubble. The platform is named in the stats bar above instead.
    val assistantHeader = message.modelTag?.takeIf { it.isNotBlank() }
        ?.let { "پاسخ مدل ($it)" }
        ?: "پاسخ مدل"
    var reasoningExpanded by remember { mutableStateOf(false) }

    Card(
        modifier = modifier
            .fillMaxWidth()
            .testTag("message_turn_${index}_${message.role.name.lowercase()}"),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isUser) {
                MaterialTheme.colorScheme.surfaceVariant
            } else {
                MaterialTheme.colorScheme.surface
            }
        ),
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            if (isUser) Color(0x3322C55E) else Color(0x336366F1)
        )
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            // Card Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(24.dp)
                            .background(
                                if (isUser) Color(0xFF15803D) else Color(0xFF4F46E5),
                                RoundedCornerShape(6.dp)
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = if (isUser) "👤" else "🤖",
                            fontSize = 12.sp
                        )
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = if (isUser) "پرسش کاربر" else assistantHeader,
                        style = MaterialTheme.typography.titleSmall.copy(
                            fontWeight = FontWeight.Bold,
                            color = if (isUser) Color(0xFF10B981) else Color(0xFF818CF8)
                        )
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "#${if (usePersianDigits) BidiEngine.toPersianDigits((index + 1).toString()) else (index + 1).toString()}",
                        style = MaterialTheme.typography.labelSmall.copy(color = MaterialTheme.colorScheme.onSurfaceVariant)
                    )
                }

                // Turn actions
                Row {
                    IconButton(onClick = onCopy, modifier = Modifier.size(28.dp)) {
                        Icon(Icons.Default.ContentCopy, contentDescription = "Copy", modifier = Modifier.size(15.dp))
                    }
                    IconButton(onClick = onEdit, modifier = Modifier.size(28.dp)) {
                        Icon(Icons.Default.Edit, contentDescription = "Edit", modifier = Modifier.size(15.dp))
                    }
                    IconButton(onClick = onDelete, modifier = Modifier.size(28.dp)) {
                        Icon(Icons.Default.Delete, contentDescription = "Delete", tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(15.dp))
                    }
                }
            }

            // DeepSeek Reasoning Box (<think> tags)
            if (!message.reasoning.isNullOrBlank()) {
                Spacer(modifier = Modifier.height(8.dp))
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color(0xFF1E293B))
                        .clickable { reasoningExpanded = !reasoningExpanded }
                        .padding(10.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Psychology, contentDescription = null, tint = Color(0xFFA855F7), modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "فرایند استدلال مدل (Reasoning Chain)",
                                style = MaterialTheme.typography.labelSmall.copy(color = Color(0xFFA855F7), fontWeight = FontWeight.Bold)
                            )
                        }
                        Icon(
                            imageVector = if (reasoningExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                            contentDescription = null,
                            tint = Color(0xFFA855F7),
                            modifier = Modifier.size(16.dp)
                        )
                    }

                    AnimatedVisibility(visible = reasoningExpanded) {
                        Column(modifier = Modifier.padding(top = 8.dp)) {
                            BidiText(
                                text = message.reasoning,
                                color = Color(0xFFCBD5E1),
                                style = MaterialTheme.typography.bodySmall,
                                usePersianDigits = usePersianDigits
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Main Message Text Content
            BidiText(
                text = message.content,
                color = MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.bodyMedium.copy(lineHeight = 22.sp),
                usePersianDigits = usePersianDigits
            )

            // Extracted Code Blocks
            if (message.codeSnippets.isNotEmpty()) {
                Spacer(modifier = Modifier.height(12.dp))
                message.codeSnippets.forEach { snippet ->
                    CodeBlockView(
                        code = snippet.code,
                        language = snippet.language,
                        modifier = Modifier.padding(vertical = 4.dp),
                        usePersianDigits = usePersianDigits
                    )
                }
            }
        }
    }
}
