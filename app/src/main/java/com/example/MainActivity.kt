package com.example

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalTextToolbar
import androidx.compose.ui.platform.TextToolbar
import androidx.compose.ui.platform.TextToolbarStatus
import androidx.compose.ui.unit.LayoutDirection
import com.example.ui.components.AppBottomNavBar
import com.example.ui.components.AppScreen
import com.example.ui.components.AppTopBar
import com.example.ui.screens.AuthScreen
import com.example.ui.screens.DistillationScreen
import com.example.ui.screens.ExportStudioScreen
import com.example.ui.screens.ImportScreen
import com.example.ui.screens.LibraryScreen
import com.example.ui.screens.SettingsScreen
import com.example.ui.screens.WorkspaceScreen
import com.example.ui.theme.AIChatExporterTheme
import com.example.ui.viewmodel.MainViewModel

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

class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()

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

                CompositionLocalProvider(
                    LocalLayoutDirection provides LayoutDirection.Rtl,
                    LocalTextToolbar provides NoOpTextToolbar
                ) {
                    Crossfade(targetState = userProfile != null, label = "auth_crossfade") { isAuthenticated ->
                        if (isAuthenticated) {
                            MainAppScreen(viewModel = viewModel)
                        } else {
                            AuthScreen(viewModel = viewModel)
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
    val globalDirection by viewModel.globalDirection.collectAsState()
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
            if (currentTime - lastBackPressTime < 2000L) {
                (context as? android.app.Activity)?.finish()
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
                currentDirection = globalDirection,
                onToggleDirection = { viewModel.toggleDirection() },
                onSaveSession = { viewModel.saveActiveSessionToDatabase() },
                onToggleBookmark = { viewModel.toggleBookmarkActiveSession() },
                onNewSession = { viewModel.resetSession() }
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
}
