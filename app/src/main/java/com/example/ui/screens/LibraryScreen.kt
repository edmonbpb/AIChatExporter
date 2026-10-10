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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Launch
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.CloudSync
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DriveFolderUpload
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.local.ChatSessionEntity
import com.example.data.local.ExportRecordEntity
import com.example.data.model.ExportFormat
import com.example.data.model.ExportRecord
import com.example.data.model.PlatformPreset
import com.example.domain.bidi.BidiEngine
import com.example.domain.exporter.FileShareHelper
import com.example.domain.parser.scrubInventedModel
import com.example.ui.components.AdaptiveLabel
import com.example.ui.theme.Cyan40
import com.example.ui.theme.Indigo40
import com.example.ui.viewmodel.MainViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun LibraryScreen(
    viewModel: MainViewModel,
    modifier: Modifier = Modifier
) {
    val savedSessions by viewModel.savedSessions.collectAsState()
    val exportHistory by viewModel.exportHistory.collectAsState()
    val usePersianDigits by viewModel.usePersianDigits.collectAsState()
    val context = LocalContext.current

    var selectedTab by remember { mutableIntStateOf(0) }
    // Same helper shape DocumentExporter.kt:357 uses: one place decides whether a number is shown
    // with Persian digits, so the tab counts cannot drift away from the dates on the cards below.
    fun shown(value: String) = if (usePersianDigits) BidiEngine.toPersianDigits(value) else value
    val tabs = listOf("گفتگوها (${shown(savedSessions.size.toString())})", "فایل‌های صادرشده (${shown(exportHistory.size.toString())})", "پشتیبان محلی")

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp)
            .testTag("library_screen")
    ) {
        // Banner Header
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(
                    brush = Brush.linearGradient(
                        listOf(Color(0xFF1E293B), Color(0xFF0F172A), Color(0xFF111827))
                    )
                )
                .border(1.dp, Color(0xFF334155), RoundedCornerShape(16.dp))
                .padding(16.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .background(
                            brush = Brush.linearGradient(listOf(Indigo40, Cyan40)),
                            shape = RoundedCornerShape(10.dp)
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Default.Folder, contentDescription = null, tint = Color.White, modifier = Modifier.size(20.dp))
                }
                Spacer(modifier = Modifier.width(10.dp))
                Column {
                    Text(
                        text = "مرکز اسناد و تاریخچهٔ این دستگاه",
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                    )
                    Text(
                        text = "این فهرست از حافظهٔ همین دستگاه خوانده می‌شود",
                        style = MaterialTheme.typography.bodySmall.copy(color = Color(0xFF94A3B8))
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(14.dp))

        // Tabs
        TabRow(
            selectedTabIndex = selectedTab,
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
            contentColor = MaterialTheme.colorScheme.primary,
            modifier = Modifier.clip(RoundedCornerShape(10.dp))
        ) {
            tabs.forEachIndexed { index, title ->
                Tab(
                    selected = selectedTab == index,
                    onClick = { selectedTab = index },
                    text = { Text(title, fontSize = 12.sp, fontWeight = FontWeight.SemiBold) },
                    modifier = Modifier.testTag("library_tab_$index")
                )
            }
        }

        Spacer(modifier = Modifier.height(14.dp))

        when (selectedTab) {
            0 -> {
                // Saved Sessions Tab
                if (savedSessions.isEmpty()) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(
                            text = "هنوز مکالمه‌ای در دیتابیس ذخیره نشده است.\nدر محیط کار روی آیکون 💾 کلیک کنید.",
                            style = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurfaceVariant)
                        )
                    }
                } else {
                    LazyColumn(
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        items(savedSessions, key = { it.id }) { sessionEntity ->
                            SavedSessionCard(
                                entity = sessionEntity,
                                usePersianDigits = usePersianDigits,
                                onOpen = { viewModel.loadSessionFromDatabase(sessionEntity) },
                                onToggleBookmark = { viewModel.toggleBookmarkSavedSession(sessionEntity.id) },
                                onDelete = { viewModel.deleteSessionFromDatabase(sessionEntity.id) }
                            )
                        }
                    }
                }
            }

            1 -> {
                // Exported Files Tab
                if (exportHistory.isEmpty()) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(
                            text = "سندی در سوابق یافت نشد.\nاز بخش صدور اسناد خروجی Word یا PDF تولید کنید.",
                            style = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurfaceVariant)
                        )
                    }
                } else {
                    LazyColumn(
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        items(exportHistory, key = { it.id }) { recordEntity ->
                            val record = ExportRecord(
                                id = recordEntity.id,
                                sessionId = recordEntity.sessionId,
                                sessionTitle = recordEntity.sessionTitle,
                                fileName = recordEntity.fileName,
                                format = ExportFormat.values().firstOrNull { it.extension == recordEntity.format } ?: ExportFormat.DOCX,
                                filePath = recordEntity.filePath,
                                fileSizeFormatted = recordEntity.fileSizeFormatted,
                                exportedAt = recordEntity.exportedAt
                            )
                            ExportRecordCard(
                                record = record,
                                usePersianDigits = usePersianDigits,
                                onOpen = { FileShareHelper.openFile(context, record) },
                                onShare = { FileShareHelper.shareFile(context, record) },
                                onDelete = { viewModel.deleteExportRecord(record.id) }
                            )
                        }
                    }
                }
            }

            2 -> {
                // Local backup archive (no cloud sync in this build)
                CloudSyncView(viewModel = viewModel)
            }
        }
    }
}

@Composable
fun SavedSessionCard(
    entity: ChatSessionEntity,
    usePersianDigits: Boolean,
    onOpen: () -> Unit,
    onToggleBookmark: () -> Unit,
    onDelete: () -> Unit
) {
    val platform = PlatformPreset.fromString(entity.platformId)
    val dateStr = SimpleDateFormat("yyyy/MM/dd HH:mm", Locale.getDefault()).format(Date(entity.updatedAt))

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onOpen() }
            .testTag("saved_session_card_${entity.id}"),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(text = platform.iconEmoji, fontSize = 16.sp)
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = entity.title,
                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "مدل: ${scrubInventedModel(platform, entity.modelName) ?: platform.displayName} | تاریخ: ${if (usePersianDigits) BidiEngine.toPersianDigits(dateStr) else dateStr}",
                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                )
            }

            Row {
                // Reflects the row stored in Room, not a local flag: entity comes from viewModel.savedSessions,
                // which is the Room query itself, so the icon cannot drift away from what is on disk.
                IconButton(
                    onClick = onToggleBookmark,
                    modifier = Modifier
                        .size(32.dp)
                        .testTag("saved_session_bookmark_${entity.id}")
                ) {
                    Icon(
                        imageVector = if (entity.isBookmarked) Icons.Default.Bookmark else Icons.Default.BookmarkBorder,
                        contentDescription = "Bookmark",
                        tint = if (entity.isBookmarked) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp)
                    )
                }
                IconButton(onClick = onDelete, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.Default.Delete, contentDescription = "Delete", tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(18.dp))
                }
            }
        }
    }
}

@Composable
fun ExportRecordCard(
    record: ExportRecord,
    usePersianDigits: Boolean,
    onOpen: () -> Unit,
    onShare: () -> Unit,
    onDelete: () -> Unit
) {
    // Same idiom as SavedSessionCard's date line: one SimpleDateFormat at the card's own timestamp,
    // converted only at render so the digits switch cannot drift between the two cards.
    val dateStr = SimpleDateFormat("yyyy/MM/dd HH:mm", Locale.getDefault()).format(Date(record.exportedAt))

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("export_record_card_${record.id}"),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(text = record.format.iconEmoji, fontSize = 16.sp)
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = record.fileName,
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontWeight = FontWeight.SemiBold,
                            fontFamily = FontFamily.Monospace
                        ),
                        maxLines = 1,
                        // Ellipsis instead of the default Clip: a mid-glyph cut made two names that
                        // differ only at the end read as identical. MiddleEllipsis would be the better
                        // cue (keeps the extension), but it needs ui-text 1.10+; this app resolves 1.7.0.
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "${record.format.displayName} • ${if (usePersianDigits) BidiEngine.toPersianDigits(record.fileSizeFormatted) else record.fileSizeFormatted}",
                    style = MaterialTheme.typography.labelSmall.copy(color = MaterialTheme.colorScheme.onSurfaceVariant)
                )
                Text(
                    text = "تاریخ: ${if (usePersianDigits) BidiEngine.toPersianDigits(dateStr) else dateStr}",
                    style = MaterialTheme.typography.labelSmall.copy(color = MaterialTheme.colorScheme.onSurfaceVariant)
                )
            }

            Row {
                IconButton(onClick = onOpen, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.AutoMirrored.Filled.Launch, contentDescription = "Open", modifier = Modifier.size(16.dp))
                }
                IconButton(onClick = onShare, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.Default.Share, contentDescription = "Share", modifier = Modifier.size(16.dp))
                }
                IconButton(onClick = onDelete, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.Default.Delete, contentDescription = "Delete", tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(16.dp))
                }
            }
        }
    }
}

@Composable
fun CloudSyncView(viewModel: MainViewModel) {
    val context = LocalContext.current
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.CloudSync, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(24.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "پشتیبان‌گیری محلی (آرشیو JSON)",
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                )
            }

            Spacer(modifier = Modifier.height(10.dp))
            Text(
                text = "مکالماتِ این فهرست روی همین دستگاه نگهداری می‌شوند. دکمهٔ زیر از همهٔ مکالماتِ ذخیره‌شده یک فایل آرشیو JSON می‌سازد و خودِ برنامه آن را به هیچ سروری نمی‌فرستد؛ تنها با انتخاب خودِ شما همان لحظه می‌توانید آن را در حافظهٔ گوشی، تلگرام یا هر جای دیگر بگذارید. همگام‌سازی خودکار با Google Drive هنوز در این برنامه پیاده نشده است.",
                style = MaterialTheme.typography.bodySmall.copy(lineHeight = 20.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            )

            Spacer(modifier = Modifier.height(16.dp))

            Button(
                onClick = {
                    viewModel.createLocalBackupArchive(
                        onReady = { record -> FileShareHelper.shareFile(context, record) },
                        onError = { reason -> viewModel.showMessage(reason) }
                    )
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(46.dp)
                    .testTag("cloud_backup_btn"),
                shape = RoundedCornerShape(10.dp),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
            ) {
                Icon(Icons.Default.DriveFolderUpload, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(6.dp))
                AdaptiveLabel("تولید و دانلود آرشیو پشتیبان کلی (Backup ZIP/JSON)")
            }
        }
    }
}
