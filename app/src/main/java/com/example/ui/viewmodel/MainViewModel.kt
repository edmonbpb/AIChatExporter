package com.example.ui.viewmodel

import android.app.Application
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.local.AppDatabase
import com.example.data.backup.BackupManager
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
import com.example.data.remote.auth.ACCOUNT_UNAVAILABLE_FA
import com.example.data.remote.auth.AccountOutcome
import com.example.data.remote.auth.AccountService
import com.example.data.remote.auth.GoogleAuthManager
import com.example.data.remote.auth.accountServiceFor
import com.example.data.remote.auth.confirmedAddressOwnsWait
import com.example.data.remote.drive.DriveAuth
import com.example.data.remote.auth.GoogleSignInOutcome
import com.example.data.remote.auth.messageFa
import com.example.data.remote.gemini.GeminiClient
import com.example.domain.bidi.BidiEngine
import com.example.domain.exporter.DocumentExporter
import com.example.domain.exporter.ExportOptions
import com.example.domain.exporter.OpenXmlGenerator
import com.example.domain.parser.TranscriptIngestionEngine
import com.example.domain.parser.partialImportNotice
import com.example.domain.parser.scrubInventedModel
import com.example.ui.components.AppScreen
import com.example.ui.theme.AppFontFamilyPreset
import com.example.ui.theme.AppThemePreset
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.FileNotFoundException
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
    val pptxSummaryAndCodeOnly: Boolean = false,
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
        pptxSummaryAndCodeOnly = pptxSummaryAndCodeOnly,
        // Decided here, not left null for the exporter to patch: one route to the printed title.
        customTitle = customTitle.takeIf { it.isNotBlank() } ?: sessionTitle
    )
}

private val LEGACY_INVENTED_EMAILS = setOf("user.verified@gmail.com", "user.google@gmail.com")

// A guest who signed in on an earlier build still has the old role string in prefs, and the settings
// card renders it verbatim. Same trick as LEGACY_INVENTED_EMAILS: correct it at the read point, so no
// Room migration and nothing of the user is deleted.
private val LEGACY_FALSE_ROLES = mapOf("دسترسی ۱۰۰٪ آفلاین" to "داده‌ها روی همین دستگاه")

/**
 * Whether the Drive card may say "connected": Drive's own answer wins over Google's token, because a
 * token that still mints is not proof of access — after the consent is revoked on the Google account
 * page Google keeps handing out an access token for a while, and every Drive call answers `401`.
 */
internal fun driveLinkedAfterToken(tokenMinted: Boolean, driveRefused: Boolean): Boolean =
    tokenMinted && !driveRefused

/**
 * Which sentence the Drive card may show when Drive answers `PermissionRequired`. Two facts this app
 * already holds decide it, and no second query to Google is made: the payload of that answer —
 * `PermissionRequired(null)` means this app knows no Google account at all (`DriveAuth.kt:55`),
 * `PermissionRequired(e.intent)` means an account is there and Google handed back the intent to ask it
 * for consent (`DriveAuth.kt:63`) — and whether the session inside this app is a guest one. A guest was
 * never asked for this permission, so "has not been granted" blames them for a choice they never saw,
 * and that holds on a handset where Google's own cache still lists the account they signed out of: that
 * case arrives with a recovery intent and must still read as a guest.
 */
internal fun drivePermissionNotice(
    consentRecoveryPending: Boolean,
    guestSession: Boolean
): String =
    if (consentRecoveryPending && !guestSession) DRIVE_CONSENT_MISSING_FA else DRIVE_SIGN_IN_FIRST_FA

// The consent sentence is the one `DriveReconnectTest` asserts verbatim: same bytes, same meaning.
private const val DRIVE_CONSENT_MISSING_FA =
    "اجازهٔ نوشتن در گوگل درایو داده نشده است؛ از دکمهٔ اتصال استفاده کن."

private const val DRIVE_SIGN_IN_FIRST_FA =
    "اول با حسابِ گوگل وارد شو، بعد برنامه را به درایو وصل کن."

// The account roads' own sentences, in one greppable block beside the Drive ones: every Persian line
// the auth flow may show is written here once, and nowhere else in the file.
private const val SIGN_IN_DONE_FA = "ورود به حساب با موفقیت انجام شد ✨"
private const val SIGN_UP_DONE_FA = "حساب ساخته شد؛ حالا ایمیل را تأیید کن."
private const val VERIFICATION_RESENT_FA = "ایمیل تأیید دوباره فرستاده شد."
private const val VERIFICATION_DONE_FA = "ایمیل تأیید شد؛ حساب باز شد."
private const val VERIFICATION_OTHER_ADDRESS_FA =
    "ایمیلِ دیگری تأیید شده است؛ برای همین نشانی منتظر می‌مانیم."
private const val VERIFICATION_LEFT_FA = "از تأیید ایمیل بیرون آمدی؛ برای ادامه دوباره وارد شو."
private const val PASSWORD_RESET_SENT_FA = "راهنمای تغییر رمز به ایمیل تو فرستاده شد."
private const val ROLE_ORGANISATION = "کاربر سازمان"
private const val ROLE_NEW_MEMBER = "کاربر سازمانی جدید"

/**
 * The value-carrying branch of [GoogleAuthManager.signInWithGoogle] on its own, so a JVM test can see
 * that a real profile reaches the store without a Play Services device. Any other outcome persists
 * nothing and answers null; the caller still reports it with `messageFa`.
 */
internal fun applyGoogleSignInOutcome(
    outcome: GoogleSignInOutcome,
    persist: (UserProfile) -> Unit
): UserProfile? {
    val profile = (outcome as? GoogleSignInOutcome.SignedIn)?.profile ?: return null
    persist(profile)
    return profile
}

class MainViewModel(
    application: Application,
    private val account: AccountService = accountServiceFor(application)
) : AndroidViewModel(application) {

    // `by viewModels()` in `MainActivity` builds this class by asking the JVM for a constructor whose
    // only parameter is the Application, and a Kotlin default value is not such a constructor once the
    // bytecode is written. This second door is what the framework walks on a handset; tests and the
    // root screens walk the first one with an account of their own.
    constructor(application: Application) : this(application, accountServiceFor(application))

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

    // The address the login form reopens with. Email only: no password is stored anywhere here.
    private val _rememberedEmail = MutableStateFlow(
        prefs.getString("remembered_email", null)?.takeIf { it.isNotBlank() }
    )
    val rememberedEmail: StateFlow<String?> = _rememberedEmail.asStateFlow()

    // App lock: the switch belongs to the device and is stored; the two flags below live only in this
    // process, so a cold start always asks for the device lock again. Never persisted, never saveable.
    val appLockEnabled = MutableStateFlow(prefs.getBoolean("app_lock_enabled", false))
    val sessionUnlocked = MutableStateFlow(false)
    val lockManualSignIn = MutableStateFlow(false)

    // The address whose confirmation is still outstanding. Stored, because the wait outlives this
    // process: the mail is opened in another app, and the owner may close this one before tapping the
    // link. An empty value means no wait, so a restored null never parks anyone on a blank screen.
    private val _pendingVerification = MutableStateFlow(
        prefs.getString("pending_verification_email", null)?.takeIf { it.isNotBlank() }
    )
    val pendingVerification: StateFlow<String?> = _pendingVerification.asStateFlow()

    // Set by the deliberate double-back exit and read once by the next cold start: the account may
    // still be on this device, but the owner asked to be out of the app.
    private val _signInRequiredOnStart = MutableStateFlow(prefs.getBoolean("exit_requires_login", false))
    val signInRequiredOnStart: StateFlow<Boolean> = _signInRequiredOnStart.asStateFlow()

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

    /**
     * A transcript or link handed to the app by another app while a gate held the workspace back.
     * Nothing is imported from it here; the workspace screen takes it once, on the first composition
     * it is actually shown on.
     */
    val heldShare = MutableStateFlow<String?>(null)

    fun holdIncomingShare(text: String) {
        val kept = text.trim().takeIf { it.isNotEmpty() } ?: return
        heldShare.value = kept
    }

    /** Takes the held share, if any. True when the caller has work to do with it. */
    fun flushHeldShare(): Boolean = heldShare.value?.let { held ->
        heldShare.value = null
        applyIncomingShare(held)
    } ?: false

    /** The one place that decides what an incoming share does; the gate never calls this directly. */
    private fun applyIncomingShare(text: String): Boolean {
        val isLink = com.example.domain.parser.SharedLinkExtractor.containsUrl(text) && text.length < 500
        if (isLink) {
            val url = com.example.domain.parser.SharedLinkExtractor.extractUrl(text) ?: text
            sharedUrlInput.value = url
            importMode.value = 0
            extractFromSharedLink(url)
        } else {
            rawInputText.value = text
            importMode.value = 1
            parseAndLoadTranscript()
        }
        return true
    }

    // Active Session (Empty by default for real commercial usage)
    private val _activeSession = MutableStateFlow<ChatSession?>(null)
    val activeSession: StateFlow<ChatSession?> = _activeSession.asStateFlow()

    // Workspace Filter & Direction
    val searchQuery = MutableStateFlow("")
    val filterOnlyCode = MutableStateFlow(false)
    val filterOnlyUser = MutableStateFlow(false)
    val filterOnlyAssistant = MutableStateFlow(false)
    val usePersianDigits = MutableStateFlow(true)

    // Distillation State
    private val _distillationState = MutableStateFlow(DistillationUiState())
    val distillationState: StateFlow<DistillationUiState> = _distillationState.asStateFlow()

    private val _distillationHistory = MutableStateFlow<List<AIDistillationEntity>>(emptyList())
    val distillationHistory: StateFlow<List<AIDistillationEntity>> = _distillationHistory.asStateFlow()

    // Drive backup: one file a day in a visible folder, restored by hand and never destructive.
    private val backupManager by lazy { BackupManager(getApplication(), db) }
    private val _driveBackupStatus = MutableStateFlow<String?>(null)
    val driveBackupStatus: StateFlow<String?> = _driveBackupStatus.asStateFlow()
    // Drive's own answer, kept on disk beside the backup date: a revocation outlives this process, and
    // Google's token cache outlives the revocation, so the card cannot be rebuilt from the token alone.
    private var driveRefused = backupManager.driveRefused()
    private val _driveLinked = MutableStateFlow(
        // B-47: a stored disconnect is the user's own decision and it outranks Google's cached grant on
        // this cold start - the cache still holds `drive.file` because disconnecting here never revokes
        // consent on the Google side. Only connect (beginDriveConsent) or a new session (persistUser)
        // takes the marker off and lets the cached grant speak again.
        !prefs.getBoolean("drive_disconnected", false) &&
            driveLinkedAfterToken(
                tokenMinted = DriveAuth.hasConsent(application),
                driveRefused = driveRefused
            )
    )
    val driveLinked: StateFlow<Boolean> = _driveLinked.asStateFlow()
    var pendingDriveRecoveryIntent: Intent? = null
        private set
    private val driveOperationMutex = Mutex()
    private var driveOperation: Job? = null
    private var driveConsentPending = false
    // B-47: the stored user decision also gates the probe itself, not only the cold-start value above.
    // Without this a new process answers the settings screen's refresh with Google's still-cached
    // drive.file grant and the card flips back to "connected". beginDriveConsent() is what lifts it.
    private var driveSignedOut = prefs.getBoolean("drive_disconnected", false)

    fun driveLastBackupDate(): String? = backupManager.lastBackupDate()

    private suspend fun driveTokenOrNull(): String? {
        val outcome = withContext(Dispatchers.IO) { DriveAuth.accessToken(getApplication()) }
        currentCoroutineContext().ensureActive()
        return when (outcome) {
            is DriveAuth.TokenOutcome.Token -> {
                pendingDriveRecoveryIntent = null
                _driveLinked.value = driveLinkedAfterToken(tokenMinted = true, driveRefused = driveRefused)
                outcome.value
            }
            is DriveAuth.TokenOutcome.PermissionRequired -> {
                pendingDriveRecoveryIntent = outcome.recoveryIntent
                _driveLinked.value = false
                // What Google put in the payload is the fact the card's sentence is built from.
                drivePermissionMissing(consentRecoveryPending = outcome.recoveryIntent != null)
                null
            }
            DriveAuth.TokenOutcome.NetworkFailure -> {
                _driveBackupStatus.value = "ارتباط با گوگل درایو برقرار نشد؛ اینترنت را بررسی کن و دوباره تلاش کن."
                null
            }
            DriveAuth.TokenOutcome.Failure -> {
                _driveLinked.value = false
                driveConnectionFailed()
                null
            }
        }
    }

    private fun launchDriveOperation(background: Boolean = false, action: suspend () -> Unit) {
        if (driveSignedOut || driveConsentPending) return
        if (background && driveOperation?.isActive == true) return
        driveOperation?.cancel()
        driveOperation = viewModelScope.launch {
            driveOperationMutex.withLock {
                try {
                    action()
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    currentCoroutineContext().ensureActive()
                    driveConnectionFailed()
                }
            }
        }
    }

    /** Re-checks the connection when the card opens. Silent on success: the card already shows it. */
    fun refreshDriveConnection() {
        launchDriveOperation(background = true) {
            driveTokenOrNull()
        }
    }

    fun beginDriveConsent(): Boolean {
        // Pressing connect again is the user's fresh answer: a disconnect is not a permanent lock-out,
        // so the signed-out flag and the stored disconnect marker are cleared before the guard can
        // refuse the ask - the marker outranked the cached grant only until this very press.
        driveSignedOut = false
        prefs.edit().remove("drive_disconnected").apply()
        if (driveConsentPending) return false
        driveOperation?.cancel()
        driveConsentPending = true
        // A fresh consent is a fresh ask: a refusal recorded against the old grant must not keep the
        // card unlinked after the user connects again, so Drive's answer starts over from nothing here.
        rememberDriveRefused(false)
        _driveLinked.value = false
        _driveBackupStatus.value = null
        return true
    }

    fun onDriveConsentResult(granted: Boolean) {
        if (!driveConsentPending) return
        driveConsentPending = false
        if (!granted) {
            _driveBackupStatus.value = "اتصال به گوگل درایو لغو شد؛ برای تلاش دوباره دکمهٔ اتصال را بزن."
            return
        }
        launchDriveOperation {
            _driveBackupStatus.value = "در حال بررسی اتصال به گوگل درایو…"
            if (driveTokenOrNull() != null) driveConnectionValidated()
        }
    }

    fun onDriveConsentError() {
        if (!driveConsentPending) return
        driveConsentPending = false
        _driveLinked.value = false
        driveConnectionFailed()
    }

    private fun driveConnectionValidated() {
        _driveBackupStatus.value = "اتصال به گوگل درایو برقرار شد؛ حالا می‌توانی پشتیبان بگیری یا بازگردانی کنی."
    }

    private fun driveConnectionFailed() {
        _driveBackupStatus.value = "اتصال به گوگل درایو انجام نشد؛ دوباره تلاش کن."
    }

    private fun drivePermissionMissing(consentRecoveryPending: Boolean) {
        // The two cases arrive through the same `PermissionRequired` answer with different payloads, so
        // the payload is what tells them apart - beside the session this app is itself in. Nothing here
        // asks Google a second time: the read that used to sit on this line ran on the main thread and
        // turned every failure into "no account", which is the lie B-45 is about.
        _driveBackupStatus.value = drivePermissionNotice(
            consentRecoveryPending = consentRecoveryPending,
            guestSession = _userProfile.value?.isGuest == true
        )
    }

    /**
     * Records what Drive answered, in memory and on disk: the refusal has to survive a restart, or the
     * next open of Settings — which re-probes the token — would put the card back to "connected".
     */
    private fun rememberDriveRefused(refused: Boolean) {
        driveRefused = refused
        backupManager.rememberDriveRefused(refused)
    }

    fun backUpToDriveNow() {
        launchDriveOperation {
            _driveBackupStatus.value = "در حال پشتیبان‌گیری…"
            val token = driveTokenOrNull() ?: return@launchDriveOperation
            val outcome = backupManager.backUp(token)
            currentCoroutineContext().ensureActive()
            if (outcome.accessRefused) {
                rememberDriveRefused(true)
                _driveLinked.value = false
            } else if (outcome.ok) {
                // Drive accepted something, so any refusal on file is older than this answer.
                rememberDriveRefused(false)
            }
            _driveBackupStatus.value = outcome.message
        }
    }

    fun restoreFromDrive() {
        launchDriveOperation {
            _driveBackupStatus.value = "در حال بازگردانی…"
            val token = driveTokenOrNull() ?: return@launchDriveOperation
            val outcome = backupManager.restoreLatest(token)
            currentCoroutineContext().ensureActive()
            if (outcome.accessRefused) {
                rememberDriveRefused(true)
                _driveLinked.value = false
            } else if (outcome.ok) {
                rememberDriveRefused(false)
            }
            _driveBackupStatus.value = outcome.message
        }
    }

    /** Called once when the app opens: at most one automatic backup per calendar day. */
    fun autoBackUpIfDue() {
        launchDriveOperation(background = true) {
            if (!backupManager.isDue()) return@launchDriveOperation
            val token = driveTokenOrNull() ?: return@launchDriveOperation
            val outcome = backupManager.backUp(token)
            // The daily automatic run is the first call that meets a revocation, and the only one the
            // user never asked for, so it moves the card without adding a sentence of its own.
            if (outcome.accessRefused) {
                currentCoroutineContext().ensureActive()
                rememberDriveRefused(true)
                _driveLinked.value = false
                _driveBackupStatus.value = outcome.message
            } else if (outcome.ok) {
                rememberDriveRefused(false)
            }
        }
    }

    // Export State
    private val _exportState = MutableStateFlow(ExportUiState())
    val exportState: StateFlow<ExportUiState> = _exportState.asStateFlow()

    /**
     * How many slides the presentation export would write for the session and the switches as they stand,
     * counted by the generator's own chunking before any file is made. Zero when there is nothing to export.
     */
    val pptxSlideEstimate: StateFlow<Int> = combine(
        _activeSession,
        _exportState,
        _distillationState
    ) { session, exportState, distillationState ->
        val current = session
        if (current == null || current.messages.isEmpty()) 0
        else OpenXmlGenerator.estimatePptxSlideCount(
            current,
            distillationState.currentResult,
            exportState.toExportOptions(current.title)
        )
    }.stateIn(viewModelScope, SharingStarted.Lazily, 0)

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
            val outcome = GoogleAuthManager.signInWithGoogle(context)
            val profile = applyGoogleSignInOutcome(outcome) { persistUser(it) }
            if (profile != null) {
                _userProfile.value = profile
                _userMessage.value = outcome.messageFa
                onSuccess()
            } else {
                onError(outcome.messageFa)
            }
        }
    }

    fun loginWithEmail(
        email: String,
        password: String,
        onSuccess: () -> Unit,
        onError: (String) -> Unit
    ) {
        viewModelScope.launch {
            when (val outcome = account.signIn(email, password)) {
                is AccountOutcome.SignedIn -> {
                    openSession(outcome.email, outcome.uid, ROLE_ORGANISATION)
                    _userMessage.value = SIGN_IN_DONE_FA
                    onSuccess()
                }
                is AccountOutcome.NeedsVerification -> {
                    setPendingVerification(outcome.email)
                    onSuccess()
                }
                is AccountOutcome.Failed -> onError(outcome.messageFa)
                AccountOutcome.EmailSent -> onError(ACCOUNT_UNAVAILABLE_FA)
                AccountOutcome.Unavailable -> onError(ACCOUNT_UNAVAILABLE_FA)
            }
        }
    }

    fun registerWithEmail(
        email: String,
        password: String,
        onSuccess: () -> Unit,
        onError: (String) -> Unit
    ) {
        viewModelScope.launch {
            when (val outcome = account.register(email, password)) {
                is AccountOutcome.NeedsVerification -> {
                    // No session is opened here. The owner is on the other side of the mail, and the
                    // workspace stays behind the wait screen until the provider says the address is
                    // theirs.
                    setPendingVerification(outcome.email)
                    onSuccess()
                }
                is AccountOutcome.SignedIn -> {
                    openSession(outcome.email, outcome.uid, ROLE_NEW_MEMBER)
                    _userMessage.value = SIGN_UP_DONE_FA
                    onSuccess()
                }
                is AccountOutcome.Failed -> onError(outcome.messageFa)
                AccountOutcome.EmailSent -> onError(ACCOUNT_UNAVAILABLE_FA)
                AccountOutcome.Unavailable -> onError(ACCOUNT_UNAVAILABLE_FA)
            }
        }
    }

    /**
     * One place builds the profile a confirmed address earns, so the sign-in road, the road that came
     * back from the mail, and the register road that needs no wait cannot drift apart.
     */
    private fun openSession(email: String, uid: String, role: String) {
        val profile = UserProfile(
            id = uid,
            name = displayNameFromEmail(email),
            email = email,
            isGuest = false,
            isGoogleDriveLinked = false,
            role = role
        )
        persistUser(profile)
        _userProfile.value = profile
    }

    fun setPendingVerification(email: String?) {
        val kept = email?.trim()?.takeIf { it.isNotEmpty() }
        _pendingVerification.value = kept
        prefs.edit().apply {
            if (kept == null) remove("pending_verification_email")
            else putString("pending_verification_email", kept)
        }.apply()
    }

    fun resendVerificationEmail(onSent: () -> Unit, onError: (String) -> Unit) {
        viewModelScope.launch {
            when (val outcome = account.sendVerification()) {
                AccountOutcome.EmailSent -> {
                    _userMessage.value = VERIFICATION_RESENT_FA
                    onSent()
                }
                is AccountOutcome.Failed -> onError(outcome.messageFa)
                else -> onError(ACCOUNT_UNAVAILABLE_FA)
            }
        }
    }

    /**
     * Asks the provider what it knows now. A yes opens the session and takes the wait screen away; a
     * no is not an error and says so quietly, because the mail may simply not have been tapped yet.
     */
    fun checkVerificationNow(
        onVerified: () -> Unit,
        onStillPending: () -> Unit,
        onError: (String) -> Unit
    ) {
        val address = _pendingVerification.value ?: return onStillPending()
        viewModelScope.launch {
            if (!account.isEmailVerified()) {
                onStillPending()
                return@launch
            }
            // The confirmation must belong to the address this screen is waiting on, and the session
            // must be written under the id the provider holds — the same id every other road uses.
            val confirmed = account.currentEmail()
            if (!confirmedAddressOwnsWait(address, confirmed)) {
                _userMessage.value = VERIFICATION_OTHER_ADDRESS_FA
                onStillPending()
                return@launch
            }
            openSession(address, uid = account.currentUid() ?: address, role = ROLE_ORGANISATION)
            _userMessage.value = VERIFICATION_DONE_FA
            onVerified()
        }
    }

    /** Back to the form: the half session goes, so the next attempt starts from a clean door. */
    fun leaveVerificationWait() {
        setPendingVerification(null)
        account.signOut()
        _userProfile.value = null
        clearAccountPrefs()
        resetSession()
        _userMessage.value = VERIFICATION_LEFT_FA
    }

    fun requestPasswordReset(
        email: String,
        onSent: () -> Unit,
        onError: (String) -> Unit
    ) {
        viewModelScope.launch {
            when (val outcome = account.sendPasswordReset(email)) {
                AccountOutcome.EmailSent -> {
                    _userMessage.value = PASSWORD_RESET_SENT_FA
                    onSent()
                }
                is AccountOutcome.Failed -> onError(outcome.messageFa)
                else -> onError(ACCOUNT_UNAVAILABLE_FA)
            }
        }
    }

    /**
     * Writes the mark the next cold start reads. `commit()`, not `apply()`: the only caller that
     * matters here is [markHardExit], which runs on the main thread microseconds before
     * `exitProcess(0)`. `apply()` only queues the disk write on the `QueuedWork` thread and the
     * flush it relies on happens at an Activity transition, which the process exit never reaches, so
     * on a loaded device the queued write could be lost and the next start would open straight into
     * the workspace. This one flag has to be on disk before the caller ends the process.
     */
    fun requireSignInOnNextStart() {
        prefs.edit().putBoolean("exit_requires_login", true).commit()
        _signInRequiredOnStart.value = true
    }

    fun clearSignInRequirement() {
        _signInRequiredOnStart.value = false
        // apply() is enough here: this path opens the door and never ends the process, so the queued
        // write still has the next start (or the normal Activity transition) to land.
        prefs.edit().remove("exit_requires_login").apply()
    }

    /**
     * The consequence of the back button's second press: remember that the next start must show the
     * form, then let the caller finish the task. The account itself stays on the device so the
     * security offer at the door has something to unlock.
     */
    fun markHardExit() {
        requireSignInOnNextStart()
    }

    /** Keeps the login form's address, not the password: clearing the box clears the key too. */
    fun setRememberedEmail(email: String, remember: Boolean) {
        if (remember) {
            val kept = email.trim()
            prefs.edit().putString("remembered_email", kept).apply()
            _rememberedEmail.value = kept
        } else {
            prefs.edit().remove("remembered_email").apply()
            _rememberedEmail.value = null
        }
    }

    fun signOut() {
        driveSignedOut = true
        driveConsentPending = false
        driveOperation?.cancel()
        pendingDriveRecoveryIntent = null
        _driveLinked.value = false
        _driveBackupStatus.value = null
        // The app lock is a device setting, not the session's: app_lock_enabled and sessionUnlocked
        // stay as they are here, so signing out does not quietly unlock the next cold start.
        // The provider's own session ends here too: clearing the local prefs alone would leave a
        // Firebase user behind, and the next feature that reads `currentUser` would see a session
        // the screen says is gone. `disconnectGoogleAccount()` stays the exception on purpose.
        account.signOut()
        clearAccountPrefs()
        _userProfile.value = null
        resetSession()
        _userMessage.value = "با موفقیت از حساب کاربری خارج شدید."
    }

    fun logout() = signOut()

    /**
     * The backup card's «قطع اتصال گوگل»: only the Drive link is dropped. The account, its prefs and
     * the conversation stay on this device, so this never calls clearAccountPrefs(), resetSession()
     * or touches _userProfile — signOut() above is the real exit and keeps that job.
     */
    fun disconnectGoogleAccount() {
        driveConsentPending = false
        driveOperation?.cancel()
        pendingDriveRecoveryIntent = null
        driveSignedOut = true
        _driveLinked.value = false
        _driveBackupStatus.value = "اتصال به گوگل قطع شد؛ حساب و داده‌های شما روی همین دستگاه مانده است."
        // The decision is stored beside drive_linked, so a new process reads it too (see _driveLinked)
        // instead of rebuilding "connected" from Google's cache, which this call cannot revoke.
        prefs.edit().putBoolean("drive_linked", false).putBoolean("drive_disconnected", true).apply()
    }

    fun setAppLockEnabled(enabled: Boolean) {
        appLockEnabled.value = enabled
        prefs.edit().putBoolean("app_lock_enabled", enabled).apply()
        // Turning the switch on happens behind the device lock that was just answered, so the session
        // that made the choice is already unlocked.
        if (enabled) sessionUnlocked.value = true
        // Answering the device lock is the owner proving who they are, which is also the answer to the
        // exit gate this same owner set: the form is not shown again on the next start.
        if (enabled) clearSignInRequirement()
    }

    fun unlockSession() {
        sessionUnlocked.value = true
        lockManualSignIn.value = false
        clearSignInRequirement()
    }

    fun requestManualSignIn() {
        lockManualSignIn.value = true
    }

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
        driveSignedOut = false
        // A different session inherits no sentence from the old one: the card falls back to its
        // last-backup line until Drive answers for this session.
        _driveBackupStatus.value = null
        prefs.edit()
            .putString("user_id", profile.id)
            .putString("user_name", profile.name)
            .putString("user_email", profile.email)
            .putBoolean("is_guest", profile.isGuest)
            .putBoolean("drive_linked", profile.isGoogleDriveLinked)
            .putString("user_role", profile.role)
            // A new session inherits no disconnect decision from the old one: only this session's own
            // connect press may write the marker again (disconnectGoogleAccount).
            .remove("drive_disconnected")
            .apply()
        // A session that exists is a session that lifts the door. Every road that writes the account
        // passes through here - the form, the road back from the mail, and Google's - so none of them
        // can open a session and leave the exit mark standing. `unlockSession()` is the other road and
        // keeps its own call: it answers the device lock for an account already on the device and
        // writes nothing.
        setPendingVerification(null)
        clearSignInRequirement()
        // Any successful sign-in is the end of the lock screen's manual-sign-in pane.
        lockManualSignIn.value = false
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

    /**
     * The only rule for a display name now that the signup form asks for no name: the part before the
     * @, dots read as spaces. Signing in and signing up both come through here so they cannot drift.
     */
    private fun displayNameFromEmail(email: String): String =
        email.substringBefore("@").replace(".", " ").capitalizeWords()

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

    /**
     * The one door an account road uses to put a sentence on the Snackbar without clearing the field
     * errors a form is still showing: the screens already hold the message, they only need it published.
     */
    fun reportAuthError(message: String) {
        _userMessage.value = message
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
        pptxSummaryAndCodeOnly: Boolean? = null,
        customTitle: String? = null
    ) {
        _exportState.value = _exportState.value.copy(
            includeMetadata = includeMetadata ?: _exportState.value.includeMetadata,
            includeSummary = includeSummary ?: _exportState.value.includeSummary,
            includeCode = includeCode ?: _exportState.value.includeCode,
            convertPersianDigits = convertPersianDigits ?: _exportState.value.convertPersianDigits,
            pptxSummaryAndCodeOnly = pptxSummaryAndCodeOnly ?: _exportState.value.pptxSummaryAndCodeOnly,
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
                // B-29: the raw text of this exception is an absolute path plus a Java ErrnoException
                // string, and an LTR path printed inside an RTL paragraph is even reordered as it is read.
                // It leaves the screen and goes to logcat, where the stack trace is the useful part.
                Log.w("ExportFailure", "DocumentExporter.exportDocument failed", err)
                _exportState.value = _exportState.value.copy(
                    isExporting = false,
                    errorMessage = exportFailureNotice(err)
                )
            }
        }
    }

    /**
     * What the export card may show when [DocumentExporter.exportDocument] fails: the consequence, in
     * the reader's language. Never the path and never the syscall string the exception carries, neither
     * of which the reader can act on.
     */
    private fun exportFailureNotice(err: Throwable): String = when (err) {
        is FileNotFoundException -> "جایِ ذخیرهٔ سند باز نمی‌شود."
        else -> "ساختن سند روی این دستگاه ممکن نشد."
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
