package com.example

import android.app.KeyguardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalTextToolbar
import androidx.compose.ui.platform.TextToolbar
import androidx.compose.ui.platform.TextToolbarStatus
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import com.example.ui.components.AppBottomNavBar
import com.example.ui.components.AppScreen
import com.example.ui.components.AppTopBar
import com.example.ui.navigation.hardExitAfterDoublePress
import com.example.ui.navigation.isExitDoublePress
import com.example.ui.navigation.shouldOfferSecurityEntry
import com.example.ui.navigation.shouldShowAuth
import com.example.ui.navigation.shouldShowLock
import com.example.ui.navigation.shouldShowVerification
import com.example.ui.screens.AuthScreen
import com.example.ui.screens.DistillationScreen
import com.example.ui.screens.ExportStudioScreen
import com.example.ui.screens.ImportScreen
import com.example.ui.screens.LibraryScreen
import com.example.ui.screens.LockScreen
import com.example.ui.screens.SecurityEntryDialog
import com.example.ui.screens.SettingsScreen
import com.example.ui.screens.VerificationScreen
import com.example.ui.screens.WorkspaceScreen
import com.example.ui.theme.AIChatExporterTheme
import com.example.ui.viewmodel.MainViewModel
import kotlin.system.exitProcess

private val NoOpTextToolbar = object : TextToolbar {
    override val status: TextToolbarStatus = TextToolbarStatus.Hidden
    override fun hide() {}
    override fun showMenu(
        rect: Rect,
        onCopyRequested: (() -> Unit)?,
        onPasteRequested: (() -> Unit)?,
        onCutRequested: (() -> Unit)?,
        onSelectAllRequested: (() -> Unit)?
    ) {}
}

class MainActivity : FragmentActivity() {

    private val viewModel: MainViewModel by viewModels()

    @OptIn(ExperimentalComposeUiApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        handleIncomingIntent(intent)

        setContent {
            val themePreset by viewModel.currentThemePreset.collectAsState()
            val fontScale by viewModel.fontScale.collectAsState()
            val fontFamilyPreset by viewModel.fontFamilyPreset.collectAsState()

            AIChatExporterTheme(
                themePreset = themePreset,
                fontScale = fontScale,
                fontFamilyPreset = fontFamilyPreset
            ) {
                val userProfile by viewModel.userProfile.collectAsState()
                val appLockEnabled by viewModel.appLockEnabled.collectAsState()
                val sessionUnlocked by viewModel.sessionUnlocked.collectAsState()
                val manualSignIn by viewModel.lockManualSignIn.collectAsState()
                // Nothing to ask when the phone has no pattern/PIN/password; the lock then stays
                // open instead of leaving the user on a screen with no working way forward.
                val deviceCredentialAvailable =
                    getSystemService(KeyguardManager::class.java)?.isDeviceSecure == true

                CompositionLocalProvider(
                    LocalLayoutDirection provides LayoutDirection.Rtl,
                    LocalTextToolbar provides NoOpTextToolbar
                ) {
                    // One flag, set at the top of the view tree: the accessibility delegate looks for it
                    // by walking up the semantics chain from the node it is filling in, so every testTag
                    // below (export_error_card, toggle_pptx_scope, pptx_slide_estimate, ...) also reaches
                    // a UI Automator or Espresso dump as a resource name. Nothing on screen changes.
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .semantics { testTagsAsResourceId = true }
                    ) {
                        // Three rules, one order, all decided outside a composable (AuthGatePolicy):
                        // the owner's own device lock first, then the mail that has not been answered,
                        // then the ordinary form-or-workspace choice.
                        val signInRequired by viewModel.signInRequiredOnStart.collectAsState()
                        val pendingVerificationEmail by viewModel.pendingVerification.collectAsState()

                        // The manual-sign-in request is honoured only for the locked-out user who asked
                        // for it: without this term the lock screen would sit forever in front of the
                        // auth form it is supposed to reveal.
                        val lockInWay = shouldShowLock(
                            appLockEnabled,
                            deviceCredentialAvailable,
                            sessionUnlocked
                        ) && !manualSignIn

                        // A local read of the pending address, so the null test below smart-casts it and
                        // the wait screen is handed a non-null address without an assertion operator.
                        val waitingFor = pendingVerificationEmail

                        // Neither button closes this popup by itself, so the choice is remembered here:
                        // once the owner has answered it, it does not come back on the next recomposition.
                        var securityOfferDismissed by remember { mutableStateOf(false) }
                        val showSecurityOffer = !securityOfferDismissed && shouldOfferSecurityEntry(
                            signInRequiredOnStart = signInRequired,
                            manualSignInRequested = manualSignIn,
                            appLockEnabled = appLockEnabled,
                            deviceCredentialAvailable = deviceCredentialAvailable
                        )

                        when {
                            lockInWay -> LockScreen(
                                onUnlock = { presentUnlock { viewModel.unlockSession() } },
                                onManualSignIn = { viewModel.requestManualSignIn() },
                                onRemoveLock = {
                                    presentUnlock {
                                        viewModel.setAppLockEnabled(false)
                                        viewModel.unlockSession()
                                    }
                                }
                            )

                            waitingFor != null && shouldShowVerification(waitingFor, lockInWay) ->
                                VerificationScreen(viewModel = viewModel, email = waitingFor)

                            else -> {
                                Crossfade(
                                    targetState = shouldShowAuth(
                                        signedIn = userProfile != null,
                                        manualSignInRequested = manualSignIn,
                                        signInRequiredOnStart = signInRequired
                                    ),
                                    label = "auth_crossfade"
                                ) { showAuth ->
                                    if (showAuth) {
                                        AuthScreen(
                                            viewModel = viewModel,
                                            onQuickUnlock = if (appLockEnabled && manualSignIn) {
                                                { presentUnlock { viewModel.unlockSession() } }
                                            } else null,
                                            onForgotPassword = { address ->
                                                viewModel.requestPasswordReset(
                                                    email = address,
                                                    // The view model already shows the "guide sent"
                                                    // sentence; this callback only closes the loop.
                                                    onSent = { },
                                                    onError = { message ->
                                                        viewModel.reportAuthError(message)
                                                    }
                                                )
                                            }
                                        )
                                        if (showSecurityOffer) {
                                            SecurityEntryDialog(
                                                lockLabel = securityLockLabel(),
                                                onUnlock = {
                                                    securityOfferDismissed = true
                                                    presentUnlock { viewModel.unlockSession() }
                                                },
                                                // A tap outside or a back press is the same answer as the
                                                // second button: carry on with the form.
                                                onUseAccount = { securityOfferDismissed = true }
                                            )
                                        }
                                    } else {
                                        MainAppScreen(viewModel = viewModel)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    override fun onWindowStartingActionMode(
        callback: android.view.ActionMode.Callback?,
        type: Int
    ): android.view.ActionMode? {
        if (type == android.view.ActionMode.TYPE_FLOATING) {
            return object : android.view.ActionMode() {
                override fun setTitle(title: CharSequence?) {}
                override fun setTitle(resId: Int) {}
                override fun setSubtitle(subtitle: CharSequence?) {}
                override fun setSubtitle(resId: Int) {}
                override fun setCustomView(view: android.view.View?) {}
                override fun invalidate() {}
                override fun finish() {}
                override fun getMenu(): android.view.Menu = android.widget.PopupMenu(this@MainActivity, window.decorView).menu
                override fun getTitle(): CharSequence? = null
                override fun getSubtitle(): CharSequence? = null
                override fun getCustomView(): android.view.View? = null
                override fun getMenuInflater(): android.view.MenuInflater = menuInflater
                override fun getType(): Int = android.view.ActionMode.TYPE_FLOATING
            }
        }
        return super.onWindowStartingActionMode(callback, type)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIncomingIntent(intent)
    }

    /**
     * Asks for the device credential - pattern, PIN, password, fingerprint or face in one prompt.
     * No negative button may be set: the builder rejects it whenever device credential is allowed.
     */
    private fun presentUnlock(onSuccess: () -> Unit) {
        val prompt = BiometricPrompt(
            this,
            ContextCompat.getMainExecutor(this),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    onSuccess()
                }
            }
        )
        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle("باز کردن برنامه")
            .setSubtitle("قفل دستگاه را باز کن")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            info.setAllowedAuthenticators(
                BiometricManager.Authenticators.BIOMETRIC_WEAK or
                    BiometricManager.Authenticators.DEVICE_CREDENTIAL
            )
        } else {
            @Suppress("DEPRECATION")
            info.setDeviceCredentialAllowed(true)
        }
        prompt.authenticate(info.build())
    }

    /**
     * Which authenticator the phone will actually ask for, in words the owner recognises. Biometric
     * capability is asked, never assumed; a phone that only has a pattern says pattern.
     */
    private fun securityLockLabel(): String {
        val biometrics = BiometricManager.from(this).canAuthenticate(
            BiometricManager.Authenticators.BIOMETRIC_STRONG or
                BiometricManager.Authenticators.BIOMETRIC_WEAK
        )
        return when {
            biometrics == BiometricManager.BIOMETRIC_SUCCESS ->
                if (packageManager.hasSystemFeature(PackageManager.FEATURE_FACE)) "چهره یا اثر انگشت"
                else "اثر انگشت"
            else -> "قفل دستگاه"
        }
    }

    private fun handleIncomingIntent(intent: Intent?) {
        if (intent?.action == Intent.ACTION_SEND && intent.type == "text/plain") {
            val sharedText = intent.getStringExtra(Intent.EXTRA_TEXT)?.trim()
            if (!sharedText.isNullOrBlank()) {
                if (com.example.domain.parser.SharedLinkExtractor.containsUrl(sharedText) && sharedText.length < 500) {
                    val url = com.example.domain.parser.SharedLinkExtractor.extractUrl(sharedText) ?: sharedText
                    viewModel.sharedUrlInput.value = url
                    viewModel.importMode.value = 0
                    viewModel.extractFromSharedLink(url)
                } else {
                    viewModel.rawInputText.value = sharedText
                    viewModel.importMode.value = 1
                    viewModel.parseAndLoadTranscript()
                }
            }
        }
    }
}

@Composable
fun MainAppScreen(viewModel: MainViewModel) {
    val currentScreen by viewModel.currentScreen.collectAsState()
    val activeSession by viewModel.activeSession.collectAsState()
    val exportState by viewModel.exportState.collectAsState()
    // Keyed to the session, not to a global flag: a new conversation starts un-exported.
    val currentSessionExported = activeSession?.let { session ->
        exportState.lastExportedRecord?.sessionId == session.id
    } ?: false
    var showNewSessionDialog by remember { mutableStateOf(false) }
    // One automatic backup per local calendar day, on the first open of that day.
    LaunchedEffect(Unit) { viewModel.autoBackUpIfDue() }
    val userMessage by viewModel.userMessage.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val focusManager = androidx.compose.ui.platform.LocalFocusManager.current

    // Safely clear text selection and focus whenever navigating between screens
    LaunchedEffect(currentScreen) {
        focusManager.clearFocus(force = true)
    }

    // Handle user snackbar messages
    LaunchedEffect(userMessage) {
        userMessage?.let { msg ->
            snackbarHostState.showSnackbar(msg)
            viewModel.clearUserMessage()
        }
    }

    var lastBackPressTime by remember { mutableStateOf(0L) }
    val context = androidx.compose.ui.platform.LocalContext.current

    // The tab the user was on before the current one. LIBRARY and SETTINGS are plain side tabs and
    // register no BackHandler of their own, so Back from them pops here instead of jumping into the
    // workspace tab (which the old branch also unlocked as a side effect of navigateTo).
    // This lives in the composable, not in the ViewModel: remember() keeps it across every
    // recomposition and tab tap, and it falls back to the root IMPORT tab when the Activity is
    // recreated. That reset is safe because IMPORT is in the seeded unlockedScreens set, so a lost
    // history can never open a workflow stage the user has not reached.
    var screenBeforeCurrent by remember { mutableStateOf(AppScreen.IMPORT) }
    var lastSeenScreen by remember { mutableStateOf(currentScreen) }
    LaunchedEffect(currentScreen) {
        if (currentScreen != lastSeenScreen) {
            screenBeforeCurrent = lastSeenScreen
            lastSeenScreen = currentScreen
        }
    }

    // Back button handling: pop to previous or Import screen, double-tap to exit on root Import screen
    BackHandler(enabled = true) {
        focusManager.clearFocus(force = true)
        if (currentScreen == AppScreen.LIBRARY || currentScreen == AppScreen.SETTINGS) {
            // Side tab: return to the tab the user actually came from. That screen is always
            // already unlocked - every path that shows a tab either calls navigateTo (which
            // unlocks) or calls unlockScreen just before setting the screen - so this cannot grow
            // unlockedScreens.
            viewModel.navigateTo(screenBeforeCurrent)
        } else if (currentScreen != AppScreen.IMPORT) {
            if (currentScreen == AppScreen.WORKSPACE) {
                viewModel.navigateTo(AppScreen.IMPORT)
            } else {
                viewModel.navigateTo(AppScreen.WORKSPACE)
            }
        } else {
            val currentTime = System.currentTimeMillis()
            if (hardExitAfterDoublePress(isExitDoublePress(currentTime, lastBackPressTime))) {
                // The second press is the exit the owner asked for, so the next start has to ask again;
                // markHardExit writes that decision to disk and leaves the account where it is.
                viewModel.markHardExit()
                (context as? android.app.Activity)?.finishAndRemoveTask()
                // The task is gone, but Android may keep the process warm and some handsets keep
                // listing it in the task manager. Ending the process is what makes that list honest
                // about an app the owner asked to leave. A single Home press never comes through here.
                exitProcess(0)
            } else {
                lastBackPressTime = currentTime
                android.widget.Toast.makeText(
                    context,
                    "برای خروج از برنامه، دوباره دکمه بازگشت را بزنید",
                    android.widget.Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    Scaffold(
        topBar = {
            AppTopBar(
                currentScreen = currentScreen,
                activeSession = activeSession,
                onSaveSession = { viewModel.saveActiveSessionToDatabase() },
                onToggleBookmark = { viewModel.toggleBookmarkActiveSession() },
                onNewSession = { showNewSessionDialog = true }
            )
        },
        bottomBar = {
            val unlockedScreens by viewModel.unlockedScreens.collectAsState()
            val progressiveEnabled by viewModel.progressiveWorkflowEnabled.collectAsState()
            val usePersianDigits by viewModel.usePersianDigits.collectAsState()
            AppBottomNavBar(
                currentScreen = currentScreen,
                unlockedScreens = unlockedScreens,
                hasActiveSession = activeSession != null,
                progressiveEnabled = progressiveEnabled,
                currentSessionExported = currentSessionExported,
                usePersianDigits = usePersianDigits,
                onScreenSelected = {
                    focusManager.clearFocus(force = true)
                    viewModel.navigateTo(it)
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        modifier = Modifier.fillMaxSize()
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            Crossfade(targetState = currentScreen, label = "screen_crossfade") { screen ->
                when (screen) {
                    AppScreen.IMPORT -> ImportScreen(viewModel = viewModel)
                    AppScreen.WORKSPACE -> WorkspaceScreen(viewModel = viewModel)
                    AppScreen.DISTILL -> DistillationScreen(viewModel = viewModel)
                    AppScreen.EXPORT -> ExportStudioScreen(viewModel = viewModel)
                    AppScreen.LIBRARY -> LibraryScreen(viewModel = viewModel)
                    AppScreen.SETTINGS -> SettingsScreen(viewModel = viewModel)
                }
            }
        }
    }

    if (showNewSessionDialog) {
        val hasUnsavedWork = activeSession?.messages?.isNotEmpty() == true
        AlertDialog(
            onDismissRequest = { showNewSessionDialog = false },
            title = { Text("نشستِ تازه") },
            text = {
                Column {
                    Text("این کار نشستِ فعلی را می‌بندد. مطمئنی؟")
                    if (hasUnsavedWork) {
                        Spacer(modifier = Modifier.height(6.dp))
                        Text("نشستِ فعلی ذخیره نشده است.")
                    }
                    Spacer(modifier = Modifier.height(10.dp))
                    TextButton(
                        onClick = {
                            showNewSessionDialog = false
                            viewModel.navigateTo(AppScreen.LIBRARY)
                        }
                    ) { Text("برگشت به کارهای قبلی") }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showNewSessionDialog = false
                        viewModel.resetSession()
                    }
                ) { Text("ببند و شروع کن") }
            },
            dismissButton = {
                Row {
                    if (hasUnsavedWork) {
                        TextButton(
                            onClick = {
                                showNewSessionDialog = false
                                viewModel.saveActiveSessionToDatabase()
                                viewModel.resetSession()
                            }
                        ) { Text("ذخیره کن و ببند") }
                    }
                    TextButton(onClick = { showNewSessionDialog = false }) { Text("انصراف") }
                }
            }
        )
    }
}
