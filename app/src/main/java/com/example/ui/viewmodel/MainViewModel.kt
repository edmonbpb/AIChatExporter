package com.example.ui.viewmodel

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.local.AppDatabase
import com.example.data.local.AIDistillationEntity
import com.example.data.local.ChatMessageEntity
import com.example.data.local.ChatSessionEntity
import com.example.data.local.Converters
import com.example.data.local.ExportRecordEntity
import com.example.data.local.ResumeDraftStore
import com.example.data.model.AIDistillation
import com.example.data.model.ChatMessage
import com.example.data.model.ChatSession
import com.example.data.model.DistillationType
import com.example.data.model.ExportFormat
import com.example.data.model.ExportRecord
import com.example.data.model.PlatformPreset
import com.example.data.model.TextDirection
import com.example.data.model.UserProfile
import com.example.data.remote.auth.GoogleAuthManager
import com.example.data.remote.auth.GoogleSignInOutcome
import com.example.data.remote.auth.messageFa
import com.example.data.remote.gemini.GeminiClient
import com.example.domain.bidi.BidiEngine
import com.example.domain.exporter.DocumentExporter
import com.example.domain.exporter.ExportOptions
import com.example.domain.parser.TranscriptIngestionEngine
import com.example.domain.parser.partialImportNotice
import com.example.domain.parser.scrubInventedModel
import com.example.ui.components.AppScreen
import com.example.ui.theme.AppFontFamilyPreset
import com.example.ui.theme.AppThemePreset
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

data class DistillationUiState(
    val isLoading: Boolean = false,
    val selectedType: DistillationType = DistillationType.REPAIR_DEFECTS_ONLY,
    val currentResult: String? = null,
    val errorMessage: String? = null,
    val isRefining: Boolean = false,
    val refinePrompt: String = "",
    val isRetrying: Boolean = false,
    val retryStatusMessage: String? = null,
    val isEditingManually: Boolean = false,
    val savedId: String? = null
)

data class ExportUiState(
    val isExporting: Boolean = false,
    val selectedFormat: ExportFormat = ExportFormat.DOCX,
    val includeMetadata: Boolean = true,
    val includeSummary: Boolean = true,
    val includeCode: Boolean = true,
    val convertPersianDigits: Boolean = true,
    val customTitle: String = "",
    val lastExportedRecord: ExportRecord? = null,
    val errorMessage: String? = null
) {
    /** The one place the screen's switches become document options, so the export and its preview cannot drift. */
    fun toExportOptions(sessionTitle: String): ExportOptions = ExportOptions(
        format = selectedFormat,
        includeMetadataTable = includeMetadata,
        includeExecutiveSummary = includeSummary,
        includeCodeBlocks = includeCode,
        convertToPersianDigits = convertPersianDigits,
        // Decided here, not left null for the exporter to patch: one route to the printed title.
        customTitle = customTitle.takeIf { it.isNotBlank() } ?: sessionTitle
    )
}

private val LEGACY_INVENTED_EMAILS = setOf("user.verified@gmail.com", "user.google@gmail.com")

// A guest who signed in on an earlier build still has the old role string in prefs, and the settings
// card renders it verbatim. Same trick as LEGACY_INVENTED_EMAILS: correct it at the read point, so no
// Room migration and nothing of the user is deleted.
private val LEGACY_FALSE_ROLES = mapOf("دسترسی ۱۰۰٪ آفلاین" to "داده‌ها روی همین دستگاه")

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val db = AppDatabase.getInstance(application)
    private val chatDao = db.chatDao()
    private val distillationDao = db.distillationDao()
    private val exportDao = db.exportDao()
    private val converters = Converters()
    private val prefs = application.getSharedPreferences("aichat_auth_prefs", Context.MODE_PRIVATE)
    private val draftStore = ResumeDraftStore(application)

    // User Authentication State
    private val _userProfile = MutableStateFlow<UserProfile?>(null)
    val userProfile: StateFlow<UserProfile?> = _userProfile.asStateFlow()

    // Navigation & Screen
    private val _currentScreen = MutableStateFlow(AppScreen.IMPORT)
    val currentScreen: StateFlow<AppScreen> = _currentScreen.asStateFlow()

    private val _unlockedScreens = MutableStateFlow<Set<AppScreen>>(
        setOf(AppScreen.IMPORT, AppScreen.LIBRARY, AppScreen.SETTINGS)
    )
    val unlockedScreens: StateFlow<Set<AppScreen>> = _unlockedScreens.asStateFlow()

    val progressiveWorkflowEnabled = MutableStateFlow(true)

    // Ingestion State
    val rawInputText = MutableStateFlow("")
    val selectedImportPlatform = MutableStateFlow(PlatformPreset.CUSTOM)
    val customImportTitle = MutableStateFlow("")
    val importMode = MutableStateFlow(0) // 0 = Shared Link (Default Tab), 1 = Text/File
    val sharedUrlInput = MutableStateFlow("")
    val isExtractingUrl = MutableStateFlow(false)
    val urlExtractionProgress = MutableStateFlow<String?>(null)
    val urlExtractionError = MutableStateFlow<String?>(null)

    // Active Session (Empty by default for real commercial usage)
    private val _activeSession = MutableStateFlow<ChatSession?>(null)
    val activeSession: StateFlow<ChatSession?> = _activeSession.asStateFlow()

    // Workspace Filter & Direction
    val searchQuery = MutableStateFlow("")
    val filterOnlyCode = MutableStateFlow(false)
    val filterOnlyUser = MutableStateFlow(false)
    val filterOnlyAssistant = MutableStateFlow(false)
    val globalDirection = MutableStateFlow(TextDirection.MIXED)
    val usePersianDigits = MutableStateFlow(true)

    // Distillation State
    private val _distillationState = MutableStateFlow(DistillationUiState())
    val distillationState: StateFlow<DistillationUiState> = _distillationState.asStateFlow()

    private val _distillationHistory = MutableStateFlow<List<AIDistillationEntity>>(emptyList())
    val distillationHistory: StateFlow<List<AIDistillationEntity>> = _distillationHistory.asStateFlow()

    // Export State
    private val _exportState = MutableStateFlow(ExportUiState())
    val exportState: StateFlow<ExportUiState> = _exportState.asStateFlow()

    // Status Message / SnackBar
    private val _userMessage = MutableStateFlow<String?>(null)
    val userMessage: StateFlow<String?> = _userMessage.asStateFlow()

    // Database Flows
    val savedSessions: StateFlow<List<ChatSessionEntity>> = chatDao.getAllSessions()
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    val exportHistory: StateFlow<List<ExportRecordEntity>> = exportDao.getAllExportRecords()
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    // Appearance Customization
    val currentThemePreset = MutableStateFlow(AppThemePreset.OBSIDIAN)
    val fontScale = MutableStateFlow(1.0f)
    val fontFamilyPreset = MutableStateFlow(AppFontFamilyPreset.VAZIRMATN)

    init {
        // Check saved login state
        val savedUserId = prefs.getString("user_id", null)
        val savedEmail = prefs.getString("user_email", null)
        if (savedUserId != null && savedEmail in LEGACY_INVENTED_EMAILS) {
            // Earlier builds stored these addresses without ever asking Google. Not a session.
            clearAccountPrefs()
        } else if (savedUserId != null) {
            val storedRole = prefs.getString("user_role", "سازمانی / پژوهشگر") ?: "سازمانی / پژوهشگر"
            _userProfile.value = UserProfile(
                id = savedUserId,
                name = prefs.getString("user_name", "کاربر AIChat") ?: "کاربر AIChat",
                email = savedEmail ?: "user@aichat.app",
                isGuest = prefs.getBoolean("is_guest", false),
                isGoogleDriveLinked = prefs.getBoolean("drive_linked", false),
                role = LEGACY_FALSE_ROLES[storedRole] ?: storedRole
            )
        }

        // Load appearance preferences
        val savedThemeId = prefs.getString("theme_preset", AppThemePreset.OBSIDIAN.id) ?: AppThemePreset.OBSIDIAN.id
        currentThemePreset.value = AppThemePreset.fromId(savedThemeId)
        val savedFontScale = prefs.getFloat("font_scale", 1.0f)
        fontScale.value = savedFontScale
        val savedFontFamilyId = prefs.getString("font_family", AppFontFamilyPreset.VAZIRMATN.id) ?: AppFontFamilyPreset.VAZIRMATN.id
        fontFamilyPreset.value = AppFontFamilyPreset.fromId(savedFontFamilyId)

        // موتور خواندن کلید را از همین فیلد می‌خواند؛ بدون این خط پس از هر اجرای تازه دوباره خالی می‌ماند.
        val savedReaderKey: String? = prefs.getString("reader_api_key", null)?.trim()?.takeIf { it.isNotEmpty() }
        com.example.domain.parser.SharedLinkExtractor.readerApiKey = savedReaderKey

        // The same line for the same reason, one key over: setCustomGeminiApiKey below writes the key into
        // this prefs file, so "stored" in its toast means it is still there after a restart, not "until this
        // run ends". Nothing here ever logs or renders the value.
        val savedGeminiKey: String? = prefs.getString("gemini_api_key", null)?.trim()?.takeIf { it.isNotEmpty() }
        GeminiClient.customApiKey = savedGeminiKey

        // The draft file is the only trace of a conversation the user never pressed «ذخیره» on;
        // signing out deletes it, so nothing here comes back for an account that was left.
        viewModelScope.launch {
            val draft = withContext(Dispatchers.IO) { draftStore.load() }
            if (draft == null || draft.messages.isEmpty()) return@launch

            val stored = chatDao.getSessionById(draft.id)
            // A transcript shared into a dead process is parsed on another dispatcher and may already
            // be loaded by now; that conversation is newer than the draft and must survive.
            if (_activeSession.value != null) return@launch
            if (stored != null) {
                loadSessionFromDatabase(stored)
                return@launch
            }

            _activeSession.value = draft
            _distillationState.value = DistillationUiState()
            _distillationHistory.value = withContext(Dispatchers.IO) {
                distillationDao.getDistillationsForSessionSync(draft.id)
            }
            _exportState.value = _exportState.value.copy(customTitle = draft.title)
            unlockScreen(AppScreen.WORKSPACE)
            _currentScreen.value = AppScreen.WORKSPACE
            _userMessage.value = "ادامهٔ گفتگوی «${draft.title}». این مکالمه هنوز در تاریخچه ذخیره نشده."
        }
    }

    // --- Authentication Actions ---

    val isGoogleSignInConfigured: Boolean get() = GoogleAuthManager.isConfigured

    fun loginWithGoogle(context: Context, onSuccess: () -> Unit, onError: (String) -> Unit) {
        viewModelScope.launch {
            when (val outcome = GoogleAuthManager.signInWithGoogle(context)) {
                is GoogleSignInOutcome.SignedIn -> {
                    persistUser(outcome.profile)
                    _userProfile.value = outcome.profile
                    _userMessage.value = outcome.messageFa
                    onSuccess()
                }

                else -> onError(outcome.messageFa)
            }
        }
    }

    fun loginWithEmail(email: String, password: String, onSuccess: () -> Unit, onError: (String) -> Unit) {
        viewModelScope.launch {
            delay(800)
            if (password.length < 4) {
                onError("طول گذرواژه باید حداقل ۴ کاراکتر باشد.")
                return@launch
            }
            val userName = email.substringBefore("@").replace(".", " ").capitalizeWords()
            val profile = UserProfile(
                id = UUID.randomUUID().toString(),
                name = userName,
                email = email,
                isGuest = false,
                isGoogleDriveLinked = false,
                role = "کاربر سازمان"
            )
            persistUser(profile)
            _userProfile.value = profile
            _userMessage.value = "ورود به حساب با موفقیت انجام شد ✨"
            onSuccess()
        }
    }

    fun registerWithEmail(name: String, email: String, password: String, onSuccess: () -> Unit, onError: (String) -> Unit) {
        viewModelScope.launch {
            delay(900)
            if (password.length < 4) {
                onError("طول گذرواژه باید حداقل ۴ کاراکتر باشد.")
                return@launch
            }
            val profile = UserProfile(
                id = UUID.randomUUID().toString(),
                name = name,
                email = email,
                isGuest = false,
                isGoogleDriveLinked = false,
                role = "کاربر سازمانی جدید"
            )
            persistUser(profile)
            _userProfile.value = profile
            _userMessage.value = "حساب کاربری جدید ایجاد و فعال گردید 🎉"
            onSuccess()
        }
    }

    fun continueAsGuest() {
        val guestProfile = UserProfile(
            id = "guest_${UUID.randomUUID().toString().take(6)}",
            name = "کاربر مهمان (Guest)",
            email = "guest@offline.local",
            isGuest = true,
            isGoogleDriveLinked = false,
            role = "داده‌ها روی همین دستگاه"
        )
        persistUser(guestProfile)
        _userProfile.value = guestProfile
        _userMessage.value = "ورود به حالت مهمان با دسترسی کامل به ابزارها 🚀"
    }

    fun signOut() {
        clearAccountPrefs()
        _userProfile.value = null
        resetSession()
        _userMessage.value = "با موفقیت از حساب کاربری خارج شدید."
    }

    fun logout() = signOut()

    fun setThemePreset(preset: AppThemePreset) {
        currentThemePreset.value = preset
        prefs.edit().putString("theme_preset", preset.id).apply()
    }

    fun setFontScale(scale: Float) {
        fontScale.value = scale
        prefs.edit().putFloat("font_scale", scale).apply()
    }

    fun setFontFamilyPreset(preset: AppFontFamilyPreset) {
        fontFamilyPreset.value = preset
        prefs.edit().putString("font_family", preset.id).apply()
    }

    /**
     * Builds a real JSON archive of every saved conversation on this device and hands the caller a
     * shareable record. Cloud sync is a separate feature and is not claimed here.
     */
    fun createLocalBackupArchive(onReady: (ExportRecord) -> Unit, onError: (String) -> Unit) {
        val sessions = savedSessions.value
        if (sessions.isEmpty()) {
            onError("هیچ مکالمهٔ ذخیره‌شده‌ای برای ساختن آرشیو نیست. در نوار بالا دکمهٔ  ذخیره را بزنید.")
            return
        }

        viewModelScope.launch {
            try {
                val record = withContext(Dispatchers.IO) {
                    val packed = org.json.JSONArray()
                    sessions.forEach { entity ->
                        val messages = chatDao.getMessagesForSessionSync(entity.id)
                        packed.put(org.json.JSONObject().apply {
                            put("id", entity.id)
                            put("title", entity.title)
                            put("platform", entity.platformId)
                            put("model", entity.modelName)
                            put("createdAt", entity.createdAt)
                            put("updatedAt", entity.updatedAt)
                            put("isBookmarked", entity.isBookmarked)
                            put("notes", entity.customNotes)
                            put("messages", org.json.JSONArray().apply {
                                messages.forEach { row ->
                                    put(org.json.JSONObject().apply {
                                        put("role", row.role)
                                        put("content", row.content)
                                        put("timestamp", row.timestamp)
                                        put("modelTag", row.modelTag)
                                        put("direction", row.direction)
                                        put("reasoning", row.reasoning)
                                        put("codeSnippets", org.json.JSONArray(row.codeSnippetsJson))
                                    })
                                }
                            })
                        })
                    }

                    val stamp = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US)
                        .format(java.util.Date())
                    val docsDir = java.io.File(getApplication<Application>().filesDir, "documents")
                    if (!docsDir.exists()) docsDir.mkdirs()
                    val archiveFile = java.io.File(docsDir, "AIChatExporter_backup_$stamp.json")
                    archiveFile.writeText(
                        org.json.JSONObject().apply {
                            put("generator", "AIChatExporter")
                            put("kind", "local-backup")
                            put("exportedAt", System.currentTimeMillis())
                            put("sessionCount", sessions.size)
                            put("sessions", packed)
                        }.toString(2)
                    )

                    ExportRecord(
                        id = UUID.randomUUID().toString(),
                        sessionId = "local-backup",
                        sessionTitle = "آرشیو پشتیبان ${BidiEngine.toPersianDigits(sessions.size.toString())} مکالمه",
                        fileName = archiveFile.name,
                        format = ExportFormat.JSON,
                        filePath = archiveFile.absolutePath,
                        // Was "${archiveFile.length() / 1024} KB", which floored every archive under 1024
                        // bytes to "0 KB". The exporter's formatter answers for it now, so there is one.
                        fileSizeFormatted = DocumentExporter.formatFileSize(archiveFile.length())
                    )
                }
                exportDao.insertExportRecord(
                    ExportRecordEntity(
                        id = record.id,
                        sessionId = record.sessionId,
                        sessionTitle = record.sessionTitle,
                        fileName = record.fileName,
                        format = record.format.extension,
                        filePath = record.filePath,
                        fileSizeFormatted = record.fileSizeFormatted,
                        exportedAt = record.exportedAt
                    )
                )
                _userMessage.value = BidiEngine.toPersianDigits("آرشیو ${record.fileSizeFormatted} با ${sessions.size} مکالمه ساخته شد؛ انتخاب کنید کجا ذخیره شود.")
                onReady(record)
            } catch (e: Exception) {
                onError(e.message ?: "ساختن فایل آرشیو روی این دستگاه ممکن نشد.")
            }
        }
    }

    private fun persistUser(profile: UserProfile) {
        prefs.edit()
            .putString("user_id", profile.id)
            .putString("user_name", profile.name)
            .putString("user_email", profile.email)
            .putBoolean("is_guest", profile.isGuest)
            .putBoolean("drive_linked", profile.isGoogleDriveLinked)
            .putString("user_role", profile.role)
            .apply()
    }

    // Appearance keys (theme_preset, font_scale, font_family) belong to the device, not the session.
    private fun clearAccountPrefs() {
        prefs.edit()
            .remove("user_id")
            .remove("user_name")
            .remove("user_email")
            .remove("is_guest")
            .remove("drive_linked")
            .remove("user_role")
            .apply()
    }

    private fun String.capitalizeWords(): String =
        split(" ").joinToString(" ") { it.replaceFirstChar { char -> if (char.isLowerCase()) char.titlecase() else char.toString() } }

    fun unlockScreen(screen: AppScreen) {
        if (!_unlockedScreens.value.contains(screen)) {
            _unlockedScreens.value = _unlockedScreens.value + screen
        }
    }

    fun navigateTo(screen: AppScreen) {
        unlockScreen(screen)
        _currentScreen.value = screen
    }

    fun clearUserMessage() {
        _userMessage.value = null
    }

    fun showMessage(msg: String) {
        _userMessage.value = msg
    }

    // --- Ingestion & Parsing ---

    fun parseAndLoadTranscript() {
        val raw = rawInputText.value.trim()
        if (raw.isBlank()) {
            _userMessage.value = "لطفاً ابتدا متن مکالمه یا فایل را وارد کنید."
            return
        }

        // Smart Redirect: If user pasted a lone web URL, extract directly from the link!
        if (raw.length < 500 && (raw.startsWith("http://") || raw.startsWith("https://")) && com.example.domain.parser.SharedLinkExtractor.containsUrl(raw)) {
            extractFromSharedLink(raw)
            return
        }

        val suggestedPlatform =
            if (selectedImportPlatform.value != PlatformPreset.CUSTOM) selectedImportPlatform.value else null
        val suggestedTitle = customImportTitle.value.takeIf { it.isNotBlank() }

        viewModelScope.launch(Dispatchers.Default) {
            val session = TranscriptIngestionEngine.parseTranscript(
                rawContent = raw,
                suggestedPlatform = suggestedPlatform,
                suggestedTitle = suggestedTitle
            )

            _activeSession.value = session
            storeDraft(session)
            _exportState.value = _exportState.value.copy(customTitle = session.title)
            unlockScreen(AppScreen.WORKSPACE)
            _currentScreen.value = AppScreen.WORKSPACE
            _userMessage.value = BidiEngine.toPersianDigits("مکالمه با ${session.messages.size} نوبت با موفقیت تحلیل شد! ✨")
        }
    }

    fun extractFromSharedLink(urlToExtract: String? = null) {
        val rawTarget = (urlToExtract ?: sharedUrlInput.value).trim()
        if (rawTarget.isBlank()) {
            val msg = "لطفاً آدرس لینک اشتراک‌گذاری را وارد کنید."
            // One message, one destination. A validation error belongs to the field the user
            // must fix, so it rides `urlExtractionError` - the red card beside the URL box - and
            // not the Snackbar, which is transient and renders on whatever screen happens to be
            // current. Same decision the extraction-failure path below already took.
            urlExtractionError.value = msg
            return
        }

        val targetUrl = com.example.domain.parser.SharedLinkExtractor.normalizeUrl(rawTarget)

        if (!com.example.domain.parser.SharedLinkExtractor.containsUrl(targetUrl)) {
            val msg = "آدرس وارد شده معتبر نیست. لطفاً یک لینک وب معتبر وارد کنید (مانند chatgpt.com/share/... یا claude.ai/share/...)."
            urlExtractionError.value = msg
            return
        }

        sharedUrlInput.value = targetUrl
        urlExtractionError.value = null
        isExtractingUrl.value = true
        urlExtractionProgress.value = "در حال اتصال به سرور و استخراج اطلاعات..."

        viewModelScope.launch {
            val result = com.example.domain.parser.SharedLinkExtractor.extractFromUrl(targetUrl) { progress ->
                urlExtractionProgress.value = progress
            }

            isExtractingUrl.value = false
            urlExtractionProgress.value = null

            result.onSuccess { session ->
                urlExtractionError.value = null
                _activeSession.value = session
                storeDraft(session)
                _exportState.value = _exportState.value.copy(customTitle = session.title)
                unlockScreen(AppScreen.WORKSPACE)
                _currentScreen.value = AppScreen.WORKSPACE
                // One short line only: the Material 3 Snackbar host in MainActivity clips a long Persian
                // sentence, and this branch already landed the user on the screen that renders the full
                // honest warning persistently from partialImportNotice(session). Same predicate as the
                // banner, so the toast and the banner can never disagree about what was read.
                _userMessage.value = if (partialImportNotice(session) == null) {
                    "گفتگوی «${session.title}» با ${BidiEngine.toPersianDigits(session.messages.size.toString())} نوبت با موفقیت استخراج شد! 🔗"
                } else {
                    // Same clause the banner spells out in full, lifted verbatim from `partialImportNotice`
                    // so the toast and the banner can never disagree about what was written.
                    "این گفتگو کامل خوانده نشد ⚠️"
                }
            }.onFailure { err ->
                // Error-only from here on: urlExtractionError feeds the red retry card in ImportScreen,
                // and the same sentence used to be dumped into the Snackbar a second time as well.
                urlExtractionError.value = err.message ?: "خطای ناشناخته در دریافت محتوای لینک."
            }
        }
    }

    fun resetSession() {
        rawInputText.value = ""
        sharedUrlInput.value = ""
        customImportTitle.value = ""
        _activeSession.value = null
        clearDraft()
        _distillationState.value = DistillationUiState()
        _distillationHistory.value = emptyList()
        _unlockedScreens.value = setOf(AppScreen.IMPORT, AppScreen.LIBRARY, AppScreen.SETTINGS)
        _currentScreen.value = AppScreen.IMPORT
    }

    private fun storeDraft(session: ChatSession?) {
        val draft = session ?: return
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                draftStore.save(draft)
            }
        }
    }

    private fun clearDraft() {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                draftStore.clear()
            }
        }
    }

    // --- Workspace Actions ---

    fun toggleDirection() {
        globalDirection.value = when (globalDirection.value) {
            TextDirection.MIXED -> TextDirection.RTL
            TextDirection.RTL -> TextDirection.LTR
            TextDirection.LTR -> TextDirection.MIXED
        }
    }

    fun togglePersianDigits() {
        usePersianDigits.value = !usePersianDigits.value
    }

    fun updateMessageContent(messageId: String, newContent: String) {
        val current = _activeSession.value ?: return
        val updated = current.messages.map { msg ->
            if (msg.id == messageId) {
                val (_, codeBlocks) = BidiEngine.extractCodeBlocks(newContent)
                msg.copy(
                    content = newContent,
                    direction = BidiEngine.detectDirection(newContent),
                    codeSnippets = codeBlocks
                )
            } else {
                msg
            }
        }
        _activeSession.value = current.copy(messages = updated, updatedAt = System.currentTimeMillis())
        storeDraft(_activeSession.value)
    }

    fun deleteMessage(messageId: String) {
        val current = _activeSession.value ?: return
        val updated = current.messages.filterNot { it.id == messageId }
        _activeSession.value = current.copy(messages = updated, updatedAt = System.currentTimeMillis())
        storeDraft(_activeSession.value)
    }

    // --- Database Persistence ---

    fun saveActiveSessionToDatabase() {
        val session = _activeSession.value ?: return
        viewModelScope.launch {
            val sessionEntity = ChatSessionEntity(
                id = session.id,
                title = session.title,
                platformId = session.platform.id,
                modelName = session.modelName,
                createdAt = session.createdAt,
                updatedAt = System.currentTimeMillis(),
                tagsJson = converters.fromStringList(session.tags),
                isBookmarked = session.isBookmarked,
                customNotes = session.customNotes
            )

            val messageEntities = session.messages.mapIndexed { index, msg ->
                ChatMessageEntity(
                    id = msg.id,
                    sessionId = session.id,
                    role = msg.role.name,
                    content = msg.content,
                    timestamp = msg.timestamp,
                    modelTag = msg.modelTag,
                    direction = msg.direction.name,
                    reasoning = msg.reasoning,
                    codeSnippetsJson = converters.fromCodeSnippetList(msg.codeSnippets),
                    orderIndex = index
                )
            }

            chatDao.saveFullSession(sessionEntity, messageEntities)
            _userMessage.value = "مکالمه در پایگاه داده محلی ذخیره شد 💾"
        }
    }

    fun toggleBookmarkActiveSession() {
        val session = _activeSession.value ?: return
        val newBookmark = !session.isBookmarked
        _activeSession.value = session.copy(isBookmarked = newBookmark)
        storeDraft(_activeSession.value)
        viewModelScope.launch {
            chatDao.setBookmark(session.id, newBookmark)
        }
    }

    /**
     * Bookmarks one saved conversation by its id, so a library card can be flagged while it is not
     * the active session (the top-bar toggle above only ever reaches `_activeSession`).
     *
     * Persistence goes through the same DAO update `toggleBookmarkActiveSession` uses, so no schema
     * change is needed: the UPDATE invalidates the Room query behind [savedSessions], that flow
     * re-emits a new list, and LibraryScreen recomposes the card from the fresh `isBookmarked`.
     */
    fun toggleBookmarkSavedSession(sessionId: String) {
        viewModelScope.launch {
            val entity = chatDao.getSessionById(sessionId) ?: return@launch
            val newBookmark = !entity.isBookmarked
            chatDao.setBookmark(sessionId, newBookmark)
            // When this row is also the conversation open in the workspace, keep the in-memory copy
            // in step: AppTopBar reads activeSession.isBookmarked, and a stale value there would let
            // the next top-bar toggle write the old flag straight back over this row.
            val active = _activeSession.value
            if (active != null && active.id == sessionId) {
                val updated = active.copy(isBookmarked = newBookmark)
                _activeSession.value = updated
                storeDraft(updated)
            }
        }
    }

    fun loadSessionFromDatabase(sessionEntity: ChatSessionEntity) {
        viewModelScope.launch {
            val messages = withContext(Dispatchers.IO) {
                chatDao.getMessagesForSessionSync(sessionEntity.id)
            }
            val platform = PlatformPreset.fromString(sessionEntity.platformId)
            val domainMessages = messages.map { msg ->
                ChatMessage(
                    id = msg.id,
                    role = com.example.data.model.MessageRole.valueOf(msg.role),
                    content = msg.content,
                    timestamp = msg.timestamp,
                    // Same reason as the draft path: the old build stamped the invented label on every
                    // assistant turn, so the bubble header needs the scrub, not only the session card.
                    modelTag = scrubInventedModel(platform, msg.modelTag),
                    direction = TextDirection.valueOf(msg.direction),
                    reasoning = msg.reasoning,
                    codeSnippets = converters.toCodeSnippetList(msg.codeSnippetsJson)
                )
            }

            val session = ChatSession(
                id = sessionEntity.id,
                title = sessionEntity.title,
                platform = platform,
                // The library is not migrated (Room is on version = 1 with a destructive fallback, so a
                // migration would wipe the user's whole archive): the invented «GPT-4o» label is scrubbed
                // here on every read instead, exactly like LEGACY_INVENTED_EMAILS at the top of this file.
                modelName = scrubInventedModel(platform, sessionEntity.modelName) ?: platform.displayName,
                createdAt = sessionEntity.createdAt,
                updatedAt = sessionEntity.updatedAt,
                messages = domainMessages,
                tags = converters.toStringList(sessionEntity.tagsJson),
                isBookmarked = sessionEntity.isBookmarked,
                customNotes = sessionEntity.customNotes
            )

            _activeSession.value = session
            storeDraft(session)
            _distillationState.value = DistillationUiState()
            _distillationHistory.value = withContext(Dispatchers.IO) {
                distillationDao.getDistillationsForSessionSync(session.id)
            }
            _exportState.value = _exportState.value.copy(customTitle = session.title)
            unlockScreen(AppScreen.WORKSPACE)
            _currentScreen.value = AppScreen.WORKSPACE
            _userMessage.value = "گفتگوی «${session.title}» بازگذاری شد."
        }
    }

    fun deleteSessionFromDatabase(sessionId: String) {
        viewModelScope.launch {
            chatDao.deleteSession(sessionId)
            if (_activeSession.value?.id == sessionId) {
                resetSession()
            }
            _userMessage.value = "مکالمه از تاریخچه حذف گردید."
        }
    }

    // --- Gemini AI Distillation ---

    fun selectDistillationType(type: DistillationType) {
        _distillationState.value = _distillationState.value.copy(selectedType = type)
    }

    fun runDistillation(type: DistillationType = _distillationState.value.selectedType) {
        val session = _activeSession.value
        if (session == null || session.messages.isEmpty()) {
            _userMessage.value = "ابتدا یک مکالمه معتبر را بارگذاری کنید."
            return
        }

        _distillationState.value = _distillationState.value.copy(
            isLoading = true,
            selectedType = type,
            errorMessage = null,
            isRetrying = false,
            retryStatusMessage = null
        )

        viewModelScope.launch {
            val result = GeminiClient.distillSession(
                session = session,
                type = type,
                onStatusUpdate = { status ->
                    _distillationState.value = _distillationState.value.copy(
                        isRetrying = true,
                        retryStatusMessage = status
                    )
                }
            )
            result.onSuccess { text ->
                val recordId = persistDistillation(session, type, text)
                _distillationState.value = _distillationState.value.copy(
                    isLoading = false,
                    isRetrying = false,
                    retryStatusMessage = null,
                    currentResult = text,
                    errorMessage = null,
                    savedId = recordId
                )
                _userMessage.value = if (recordId != null) {
                    "پالایش هوشمند «${type.titleFa}» آماده شد و در تاریخچه ذخیره گردید! ✨"
                } else {
                    "پالایش هوشمند «${type.titleFa}» آماده شد. برای ماندن در تاریخچه، نخست مکالمه را با دکمهٔ  در نوار بالا ذخیره کنید."
                }
            }.onFailure { err ->
                _distillationState.value = _distillationState.value.copy(
                    isLoading = false,
                    isRetrying = false,
                    retryStatusMessage = null,
                    errorMessage = err.message ?: "خطا در برقراری ارتباط با مدل هوش مصنوعی."
                )
            }
        }
    }

    /**
     * The history table has a foreign key on chat_sessions, so a conversation that was never saved
     * to the library cannot own a row here; it returns null instead of crashing the insert.
     */
    private suspend fun persistDistillation(
        session: ChatSession,
        type: DistillationType,
        text: String
    ): String? = withContext(Dispatchers.IO) {
        if (chatDao.getSessionById(session.id) == null) return@withContext null

        val recordId = UUID.randomUUID().toString()
        distillationDao.insertDistillation(
            AIDistillationEntity(
                id = recordId,
                sessionId = session.id,
                type = type.name,
                title = type.titleFa,
                content = text,
                modelUsed = GeminiClient.lastUsedModel ?: "unknown",
                createdAt = System.currentTimeMillis()
            )
        )
        _distillationHistory.value = distillationDao.getDistillationsForSessionSync(session.id)
        recordId
    }

    /** Keeps the stored row in step with the text the user edits by hand. */
    private fun updateStoredDistillation(text: String) {
        val recordId = _distillationState.value.savedId ?: return
        val session = _activeSession.value ?: return
        val stored = _distillationHistory.value.firstOrNull { it.id == recordId } ?: return
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                distillationDao.insertDistillation(stored.copy(content = text))
                _distillationHistory.value = distillationDao.getDistillationsForSessionSync(session.id)
            }
        }
    }

    fun restoreDistillation(recordId: String) {
        val stored = _distillationHistory.value.firstOrNull { it.id == recordId } ?: return
        _distillationState.value = _distillationState.value.copy(
            currentResult = stored.content,
            savedId = stored.id,
            selectedType = DistillationType.values().firstOrNull { it.name == stored.type }
                ?: _distillationState.value.selectedType,
            errorMessage = null
        )
        _userMessage.value = "نسخهٔ ذخیره‌شدهٔ «${stored.title}» بازیابی شد."
    }

    fun runRefineChat(instruction: String) {
        val current = _distillationState.value.currentResult
            ?: _activeSession.value?.messages?.joinToString("\n\n") { it.content } ?: return

        if (instruction.isBlank()) return

        _distillationState.value = _distillationState.value.copy(
            isRefining = true,
            isRetrying = false,
            retryStatusMessage = null,
            errorMessage = null
        )

        viewModelScope.launch {
            val result = GeminiClient.chatRefine(
                currentContent = current,
                userInstruction = instruction,
                onStatusUpdate = { status ->
                    _distillationState.value = _distillationState.value.copy(
                        isRetrying = true,
                        retryStatusMessage = status
                    )
                }
            )
            result.onSuccess { text ->
                val session = _activeSession.value
                val savedId = _distillationState.value.savedId
                val recordId = when {
                    session == null -> null
                    savedId == null -> persistDistillation(session, DistillationType.CUSTOM_REWRITE, text)
                    else -> {
                        updateStoredDistillation(text)
                        savedId
                    }
                }
                _distillationState.value = _distillationState.value.copy(
                    isRefining = false,
                    isRetrying = false,
                    retryStatusMessage = null,
                    currentResult = text,
                    refinePrompt = "",
                    savedId = recordId ?: savedId,
                    errorMessage = null
                )
                _userMessage.value = "اصلاحات جدید با موفقیت در پیش‌نمایش اعمال شد ✨"
            }.onFailure { err ->
                _distillationState.value = _distillationState.value.copy(
                    isRefining = false,
                    isRetrying = false,
                    retryStatusMessage = null,
                    errorMessage = err.message
                )
            }
        }
    }

    fun updateManualResult(newText: String) {
        _distillationState.value = _distillationState.value.copy(currentResult = newText)
        updateStoredDistillation(newText)
    }

    fun toggleEditManually(editing: Boolean) {
        _distillationState.value = _distillationState.value.copy(isEditingManually = editing)
    }

    // --- Export Operations ---

    fun setExportFormat(format: ExportFormat) {
        _exportState.value = _exportState.value.copy(selectedFormat = format)
    }

    fun updateExportOptions(
        includeMetadata: Boolean? = null,
        includeSummary: Boolean? = null,
        includeCode: Boolean? = null,
        convertPersianDigits: Boolean? = null,
        customTitle: String? = null
    ) {
        _exportState.value = _exportState.value.copy(
            includeMetadata = includeMetadata ?: _exportState.value.includeMetadata,
            includeSummary = includeSummary ?: _exportState.value.includeSummary,
            includeCode = includeCode ?: _exportState.value.includeCode,
            convertPersianDigits = convertPersianDigits ?: _exportState.value.convertPersianDigits,
            customTitle = customTitle ?: _exportState.value.customTitle
        )
    }

    fun performExport() {
        val session = _activeSession.value
        if (session == null || session.messages.isEmpty()) {
            _userMessage.value = "مکالمه‌ای جهت صدور سند موجود نیست."
            return
        }

        _exportState.value = _exportState.value.copy(isExporting = true, errorMessage = null)

        viewModelScope.launch {
            val options = _exportState.value.toExportOptions(session.title)

            val exportResult = withContext(Dispatchers.IO) {
                DocumentExporter.exportDocument(
                    context = getApplication(),
                    session = session,
                    distillationContent = _distillationState.value.currentResult,
                    options = options
                )
            }

            exportResult.onSuccess { record ->
                val entity = ExportRecordEntity(
                    id = record.id,
                    sessionId = record.sessionId,
                    sessionTitle = record.sessionTitle,
                    fileName = record.fileName,
                    format = record.format.extension,
                    filePath = record.filePath,
                    fileSizeFormatted = record.fileSizeFormatted,
                    exportedAt = record.exportedAt
                )
                exportDao.insertExportRecord(entity)

                _exportState.value = _exportState.value.copy(
                    isExporting = false,
                    lastExportedRecord = record
                )
                _userMessage.value = "سند «${record.fileName}» با موفقیت صادر شد! 📄"
            }.onFailure { err ->
                _exportState.value = _exportState.value.copy(
                    isExporting = false,
                    errorMessage = err.message ?: "خطا در تولید سند."
                )
            }
        }
    }

    fun deleteExportRecord(id: String) {
        viewModelScope.launch {
            exportDao.deleteExportRecord(id)
            _userMessage.value = "سند از سوابق حذف شد."
        }
    }

    // --- Settings & Gemini API Key ---

    /**
     * Writes the key the same way setReaderApiKey writes its key below: one putString on the prefs this
     * file already holds, read back at init. No request goes out to check the key, so neither the button nor
     * this toast may claim it was validated. The value is never logged, never rendered and never put in a
     * message.
     */
    fun setCustomGeminiApiKey(key: String) {
        val trimmed = key.trim()
        prefs.edit().putString("gemini_api_key", trimmed).apply()
        GeminiClient.customApiKey = trimmed.ifEmpty { null }
        _userMessage.value = if (trimmed.isNotEmpty()) {
            "کلید Gemini روی همین دستگاه ذخیره شد؛ پالایش از این پس همین کلید را به Google می‌فرستد و در همین صفحه راستی‌آزمایی نمی‌شود."
        } else {
            "کلیدِ واردشده پاک شد؛ اگر کلیدِ دیگری در این نسخه فعال باشد، پالایش باز هم متن را به Google می‌فرستد."
        }
    }

    fun getActiveApiKey(): String = GeminiClient.getEffectiveApiKey()
    fun hasValidApiKey(): Boolean = GeminiClient.hasValidApiKey()

    /** Only what this device stored. getActiveApiKey() would also hand back the key baked into the build. */
    fun getStoredGeminiApiKey(): String = prefs.getString("gemini_api_key", null)?.trim() ?: ""

    /**
     * کلید موتور خواندن، مانند کلید Gemini، روی همین دستگاه ذخیره می‌شود؛ لایۀ رندر آن را از همان
     * فیلدِ اشتراکیِ extractor می‌خواند.
     */
    fun setReaderApiKey(key: String) {
        val trimmed = key.trim()
        prefs.edit().putString("reader_api_key", trimmed).apply()
        com.example.domain.parser.SharedLinkExtractor.readerApiKey = trimmed.ifEmpty { null }
        _userMessage.value = if (trimmed.isEmpty()) {
            "کلید موتور خواندن پاک شد؛ لایۀ رندر بی‌کلید درخواست می‌فرستد و لینک Claude از همان راهِ کپیِ دستی هم وارد می‌شود."
        } else {
            "کلید موتور خواندن ذخیره شد؛ لایۀ رندر از این پس همین کلید را همراهِ درخواست می‌فرستد."
        }
    }

    fun getReaderApiKey(): String = prefs.getString("reader_api_key", null)?.trim() ?: ""
}
