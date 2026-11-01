# Next wave — shared contract (2026-11-01)

Source of truth for the requirement wording: `contracts/next-wave-plan-2026-11-01.md`.
This file fixes the API seam and the file ownership so the deliveries cannot drift apart.

## Rules for every agent

- Edit only the files this contract assigns to you. Do not touch any other file.
- Do not run Gradle, adb, emulator, or device commands. The controller builds and tests.
- Do not change the Room version, any `@Database(version=…)`, or any entity/DAO.
- Persian text must be copied from this file exactly; never retype a sentence. After writing, a Persian scan is run by the controller.
- Keep the existing comment style and honesty rules. Do not invent UI text that is not in this contract.
- No new dependencies beyond the ones this contract names.

## Fixed decisions from the owner

- «خروج» in the backup section disconnects Google only; the user stays inside the app.
- «همه چت‌ها» means every turn of the current conversation; the chip is «همه پیام‌ها».
- The app lock asks again only on a cold start (process start), not on rotation or return from background.
- Guest mode is retired from the UI and from the auth path; legacy guest records already on disk stay readable.

## Shared text and tags

| Purpose | Exact text | Tag |
|---|---|---|
| Remember me label | `مرا به خاطر بسپار` | `auth_remember_me` |
| Quick unlock button | `ورود سریع با قفل امنیتی` | `auth_quick_unlock_btn` |
| Lock screen root | — | `lock_screen` |
| Unlock with device lock | `باز کردن با قفل دستگاه` | `lock_unlock_btn` |
| Manual sign-in | `ورود دستی به حساب دیگر` | `lock_manual_signin_btn` |
| Remove app lock | `حذف قفل برنامه` | `lock_remove_btn` |
| Disconnect Google button | `قطع اتصال گوگل` | `disconnect_btn` |
| Security card switch | `قفل برنامه با قفل دستگاه` | `settings_toggle_app_lock` |
| All messages chip | `همه پیام‌ها` | `all_messages_chip` |
| Disconnect confirmation | `اتصال برنامه به گوگل درایو قطع می‌شود؛ حساب و مکالمه‌های شما روی همین دستگاه می‌ماند.` | — |
| After disconnecting | `اتصال به گوگل قطع شد؛ حساب و داده‌های شما روی همین دستگاه مانده است.` | — |
| No device credential | `برای روشن‌کردن قفل، اول روی گوشی الگو، پین یا گذرواژه تنظیم کن.` | — |
| Login tab label | `ورود به برنامه` | — |
| Signup tab label | `ساخت حساب کاربری` | — |
| Google button (signup only) | `ساخت حساب کاربری سریع با حساب گوگل` | `google_signin_button` (keep) |
| Divider (signup only) | `یا با ایمیل و رمز عبور` | — |
| Google not configured caption | `این بسته با شناسهٔ OAuth گوگل ساخته نشده است؛ با ایمیل و رمز عبور ادامه بده.` | — |

Removed for good: `auth_name_input`, `auth_confirm_password_input`, `email_demo_notice`, `guest_mode_btn`, `settings_toggle_persian_digits`, and both Settings cards listed in the plan.

---

## File ownership

### Agent A — `app/src/main/java/com/example/ui/viewmodel/MainViewModel.kt` (only this file)

1. **Email auth without a name**
   - Delete `fun continueAsGuest()` entirely.
   - Change the signature to `fun registerWithEmail(email: String, password: String, onSuccess: () -> Unit, onError: (String) -> Unit)`. Keep `delay(900)`, the length check, id, `isGuest = false`, `isGoogleDriveLinked = false`, role `کاربر سازمانی جدید`, `persistUser`, `_userProfile.value`, message `حساب کاربری جدید ایجاد و فعال گردید 🎉`.
   - Add `private fun displayNameFromEmail(email: String): String = email.substringBefore("@").replace(".", " ").capitalizeWords()` and use it in BOTH `loginWithEmail` and `registerWithEmail` (replace the inline expression at line 505).
   - Do not change `loginWithEmail`'s messages or behaviour otherwise.

2. **Google outcome seam (testable on the JVM)**
   - At top level in this file (outside the class):
     ```kotlin
     internal fun applyGoogleSignInOutcome(
         outcome: GoogleSignInOutcome,
         persist: (UserProfile) -> Unit
     ): UserProfile? {
         val profile = (outcome as? GoogleSignInOutcome.SignedIn)?.profile ?: return null
         persist(profile)
         return profile
     }
     ```
   - `loginWithGoogle` keeps its signature and behaviour but uses the seam:
     ```kotlin
     val profile = applyGoogleSignInOutcome(outcome) { persistUser(it) }
     if (profile != null) {
         _userProfile.value = profile
         _userMessage.value = outcome.messageFa
         onSuccess()
     } else {
         onError(outcome.messageFa)
     }
     ```

3. **Remember me**
   - Property: `private val _rememberedEmail = MutableStateFlow(prefs.getString("remembered_email", null)?.takeIf { it.isNotBlank() })` and `val rememberedEmail: StateFlow<String?> = _rememberedEmail.asStateFlow()`.
   - `fun setRememberedEmail(email: String, remember: Boolean)`: remember ⇒ store `email.trim()` in pref `remembered_email` and publish it; not remember ⇒ remove the key and publish `null`. Only the email, never the password.

4. **Disconnect Google (not sign out)**
   ```kotlin
   fun disconnectGoogleAccount() {
       driveConsentPending = false
       driveOperation?.cancel()
       pendingDriveRecoveryIntent = null
       driveSignedOut = true
       _driveLinked.value = false
       _driveBackupStatus.value = "اتصال به گوگل قطع شد؛ حساب و داده‌های شما روی همین دستگاه مانده است."
       prefs.edit().putBoolean("drive_linked", false).apply()
   }
   ```
   - Never call `clearAccountPrefs()`, `resetSession()`, or assign `_userProfile`.
   - In `beginDriveConsent()`, set `driveSignedOut = false` before the guard returns true, so the connect button works again after a disconnect.

5. **App lock state (in-memory session flag only)**
   ```kotlin
   val appLockEnabled = MutableStateFlow(prefs.getBoolean("app_lock_enabled", false))
   val sessionUnlocked = MutableStateFlow(false)   // memory only: cold start always locks
   val lockManualSignIn = MutableStateFlow(false)  // memory only

   fun setAppLockEnabled(enabled: Boolean) {
       appLockEnabled.value = enabled
       prefs.edit().putBoolean("app_lock_enabled", enabled).apply()
       if (enabled) sessionUnlocked.value = true
   }

   fun unlockSession() {
       sessionUnlocked.value = true
       lockManualSignIn.value = false
   }

   fun requestManualSignIn() {
       lockManualSignIn.value = true
   }
   ```
   - `persistUser(...)` ends with `lockManualSignIn.value = false` (any successful sign-in leaves the manual-sign-in pane).
   - `signOut()` must NOT remove `app_lock_enabled` and must not reset `sessionUnlocked`.
   - `sessionUnlocked` and `lockManualSignIn` are never written to prefs and never wrapped in `rememberSaveable` anywhere.

### Agent B — `app/src/main/java/com/example/ui/screens/AuthScreen.kt` and `app/src/main/java/com/example/data/remote/auth/GoogleAuthManager.kt`

1. **AuthScreen**
   - Signature: `fun AuthScreen(viewModel: MainViewModel, onQuickUnlock: (() -> Unit)? = null, modifier: Modifier = Modifier)`.
   - Remove the `name` and `confirmPassword` state, both fields, and the match validation. The password field is the last field: `imeAction = ImeAction.Done`.
   - Initial email: `var email by remember { mutableStateOf(viewModel.rememberedEmail.value ?: "") }`.
   - Tabs: `ورود به برنامه` / `ساخت حساب کاربری`. Submit button uses the same two strings.
   - Google button: rendered only when `selectedTab == 1`, disabled state and click path unchanged, label `ساخت حساب کاربری سریع با حساب گوگل`. The not-configured caption uses the exact text in the table and never mentions a guest.
   - The divider row (`یا با ایمیل و رمز عبور`) exists only in the signup tab. Delete the demo notice (`email_demo_notice`) and the guest button entirely.
   - Remember me: only in the login tab, a `Checkbox` with `Modifier.testTag("auth_remember_me")` plus the label `مرا به خاطر بسپار` in a clickable row. `rememberMe` is local state, default false. On a successful email login call `viewModel.setRememberedEmail(email.trim(), rememberMe)` inside `onSuccess`.
   - Signup submit calls `viewModel.registerWithEmail(email = email.trim(), password = password, …)` (no name argument).
   - Quick unlock: when `onQuickUnlock != null`, render an `OutlinedButton` with tag `auth_quick_unlock_btn` and the exact label, above the tabs, calling the lambda. When it is null the button must not exist.
   - Do not add the quick unlock button inside the form's submit path; it is a separate button.

2. **GoogleAuthManager.kt**
   - `NOT_CONFIGURED_FA` = `ورود با حساب گوگل در این نسخه پیکربندی نشده است. با ایمیل و رمز عبور ادامه بده.`
   - `NO_CREDENTIAL_FA` = `گوگل برای این برنامه ورودِ قابلِ استفاده‌ای پیدا نکرد؛ روی این دستگاه دوباره تلاش کن.`
   - No other change in this file. After the edit no auth-path string may contain `مهمان`.

### Agent C — `app/src/main/java/com/example/ui/screens/SettingsScreen.kt`

1. Delete the whole BiDi typography card (`تنظیمات تایپوگرافی دوزبانه (BiDi Engine)`, lines ~633-668) and the whole progressive-workflow card (`ناوبری مرحله‌ای و پویا (جریان کار)`, lines ~672-718). Keep `usePersianDigits` and `viewModel.progressiveWorkflowEnabled` untouched in the view model.
2. Account card, `SIGN_OUT` branch: replace the current button with an `OutlinedButton` tagged `disconnect_btn`, label `قطع اتصال گوگل`, icon `Icons.Default.CloudOff` (or `LinkOff`), colour `MaterialTheme.colorScheme.error`. It opens a NEW dialog state `showDisconnectDialog` (separate from `showLogoutDialog`):
   - title `قطع اتصال گوگل`
   - text = the disconnect confirmation sentence from the table
   - confirm `قطع اتصال` ⇒ `viewModel.disconnectGoogleAccount()`
   - dismiss `انصراف`
   The `SIGN_IN_WITH_GOOGLE` branch stays as it is.
3. Keep the red `خروج از حساب کاربری` card and its `signOut()` dialog exactly as they are.
4. Add a new security card before the red logout card:
   - header `امنیت` with `Icons.Default.Security`
   - a row with title `قفل برنامه با قفل دستگاه` and description `با هر بار باز شدن کامل برنامه، قفل دستگاه پرسیده می‌شود.`
   - `Switch(checked = appLockEnabled, onCheckedChange = { viewModel.setAppLockEnabled(it) }, modifier = Modifier.testTag("settings_toggle_app_lock"))`
   - when the device has no pattern/PIN/password (`KeyguardManager.isDeviceSecure == false`), disable the switch and show the exact no-credential sentence from the table.
   - collect `viewModel.appLockEnabled` with `collectAsState()`.

### Agent D — `app/src/main/java/com/example/ui/screens/WorkspaceScreen.kt` and NEW `app/src/main/java/com/example/ui/navigation/ExitPolicy.kt`

1. After the three existing `FilterChip`s in the same `Row`, add:
   ```kotlin
   FilterChip(
       selected = searchQuery.isBlank() && !filterCodeOnly && !filterUserOnly && !filterAiOnly,
       onClick = {
           viewModel.searchQuery.value = ""
           viewModel.filterOnlyCode.value = false
           viewModel.filterOnlyUser.value = false
           viewModel.filterOnlyAssistant.value = false
       },
       label = { AdaptiveLabel("همه پیام‌ها") },
       leadingIcon = { Icon(Icons.Default.FilterList, contentDescription = null, modifier = Modifier.size(16.dp)) },
       modifier = Modifier.testTag("all_messages_chip")
   )
   ```
   It must clear the search box and all three filters, so every turn of the current conversation is shown.
2. New file `ExitPolicy.kt`:
   ```kotlin
   package com.example.ui.navigation

   /** A back press counts as the second one only inside the window and after a real first press. */
   fun isExitDoublePress(now: Long, lastPress: Long, windowMs: Long = 2000L): Boolean =
       lastPress > 0L && now >= lastPress && now - lastPress <= windowMs
   ```

### Agent E — `app/src/main/java/com/example/MainActivity.kt`, NEW `app/src/main/java/com/example/ui/screens/LockScreen.kt`, NEW `app/src/main/java/com/example/ui/navigation/AppLockPolicy.kt`, `app/src/main/AndroidManifest.xml`, `gradle/libs.versions.toml`, `app/build.gradle.kts`

1. Dependency and permission:
   - `libs.versions.toml`: add versions `biometric = "1.1.0"` and `fragment = "1.7.1"`; libraries `androidx-biometric = { group = "androidx.biometric", name = "biometric", version.ref = "biometric" }` and `androidx-fragment = { group = "androidx.fragment", name = "fragment", version.ref = "fragment" }`.
   - `app/build.gradle.kts`: `implementation(libs.androidx.biometric)` and `implementation(libs.androidx.fragment)`.
   - Manifest: `<uses-permission android:name="android.permission.USE_BIOMETRIC" />`.
2. New file `AppLockPolicy.kt`:
   ```kotlin
   package com.example.ui.navigation

   fun shouldShowLock(
       appLockEnabled: Boolean,
       deviceCredentialAvailable: Boolean,
       unlockedThisProcess: Boolean
   ): Boolean = appLockEnabled && deviceCredentialAvailable && !unlockedThisProcess
   ```
3. New file `LockScreen.kt`:
   ```kotlin
   @Composable
   fun LockScreen(
       onUnlock: () -> Unit,
       onManualSignIn: () -> Unit,
       onRemoveLock: () -> Unit,
       modifier: Modifier = Modifier
   )
   ```
   Centred column, lock icon, title `برنامه قفل است`, short line `برای دیدن مکالمه‌ها، قفل دستگاه را باز کن.`, then three full-width buttons with the exact labels and tags from the table, in this order: unlock, manual sign-in, remove lock. Root `Modifier.testTag("lock_screen")`.
4. `MainActivity`:
   - extends `androidx.fragment.app.FragmentActivity` (still calls `enableEdgeToEdge()`; still uses `by viewModels()`).
   - In `onCreate`, collect `appLockEnabled`, `sessionUnlocked`, `lockManualSignIn`.
   - `deviceCredentialAvailable` = `getSystemService(KeyguardManager::class.java)?.isDeviceSecure == true`.
   - Root composition, inside the existing theme/providers:
     ```kotlin
     val showLock = shouldShowLock(appLockEnabled, deviceCredentialAvailable, sessionUnlocked)
     if (showLock) {
         LockScreen(
             onUnlock = { presentUnlock { viewModel.unlockSession() } },
             onManualSignIn = { viewModel.requestManualSignIn() },
             onRemoveLock = { presentUnlock { viewModel.setAppLockEnabled(false); viewModel.unlockSession() } }
         )
     } else {
         Crossfade(targetState = userProfile != null || manualSignIn, label = "auth_crossfade") { showAuth ->
             if (showAuth) AuthScreen(
                 viewModel = viewModel,
                 onQuickUnlock = if (appLockEnabled && manualSignIn) {
                     { presentUnlock { viewModel.unlockSession() } }
                 } else null
             ) else MainAppScreen(viewModel = viewModel)
         }
     }
     ```
     (`manualSignIn` is the collected `lockManualSignIn`.)
   - `presentUnlock(onSuccess)` — a private method on the activity:
     ```kotlin
     private fun presentUnlock(onSuccess: () -> Unit) {
         val prompt = BiometricPrompt(this, ContextCompat.getMainExecutor(this),
             object : BiometricPrompt.AuthenticationCallback() {
                 override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                     onSuccess()
                 }
             })
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
     ```
     Do NOT set a negative button: with device credential allowed the builder throws.
   - Exit: replace `finish()` with `finishAndRemoveTask()`; the condition becomes `if (isExitDoublePress(currentTime, lastBackPressTime))`. Keep the toast and the 2000 ms wording.
   - Do not change any other behaviour (intent handling, back navigation, Scaffold).

### Test agents

- **T1 (auth)**: `AuthScreenSignupTest`, `AuthScreenLoginTest`, `RememberedEmailTest`, `AuthPrefillTest`, `GoogleProfileTest`, and UPDATE `GoogleSignInCauseTest` (`NO_CREDENTIAL_FA` no longer contains `مهمان`; assert the new way out — the text contains `دوباره تلاش کن` and still contains exactly one `؛` and no `پیدا نشد`).
- **T2 (settings/drive)**: `SettingsHiddenTogglesTest`, `DriveDisconnectTest`, and UPDATE the three `continueAsGuest()` fixtures in `DriveGuestCardTest` (lines ~120, ~142, ~200) to `seedAppSession(guest = true)` + a fresh `MainViewModel`. Leave `AccountActionTest` untouched.
- **T3 (workspace/exit)**: `AllMessagesChipTest`, `ExitPolicyTest`.
- **T4 (lock)**: `AppLockDecisionTest`, `AppLockPrefsTest`, `LockScreenTest`, `QuickUnlockButtonTest`.

### Test conventions (all test agents)

- JVM tests, `@RunWith(RobolectricTestRunner::class)`, `@Config(sdk = [34])`, `@LooperMode(PAUSED)` when a view model is created.
- Any test that creates `MainViewModel` must clear `aichat_auth_prefs`, `drive_backup`, and `com.google.android.gms.signin` in `@Before`/`@After`, and reset Google's two process caches the way `DriveGuestCardTest` does, or it turns the full suite red while passing alone.
- Do not use the Room database in these tests. For an active conversation use `viewModel.rawInputText.value = <AIChat JSON>` then `viewModel.parseAndLoadTranscript()`; wait with `compose.waitUntil { viewModel.activeSession.value?.messages?.isNotEmpty() == true }`.
- The email sign-in/registration path has an artificial `delay(...)`. Drive it with `shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1000))` (import `org.robolectric.Shadows.shadowOf`), not with wall-clock sleeps.
- Assert exact Persian strings from this contract, not paraphrases.
