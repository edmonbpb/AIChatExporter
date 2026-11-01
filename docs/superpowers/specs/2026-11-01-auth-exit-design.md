# Design: Authentication, Verification, and Hard Exit (2026-11-01)

## Overview
This design implements a professional authentication flow with email verification, a secure password recovery mechanism, a "hard exit" that forces re-authentication, and an improved security entry experience.

## 1. Authentication Subsystem (Firebase Auth)

### 1.1 Email Sign-Up Flow
Current state: Local mock sign-up.
Target state: Integrated Firebase Auth.
- **Flow**: User enters email/password $\rightarrow$ `AuthViewModel.registerWithEmail` calls `FirebaseAuth.createUserWithEmailAndPassword` $\rightarrow$ `FirebaseAuth.sendEmailVerification`.
- **Verification Gate**: After the request, the app navigates to a new `VerificationScreen`.
- **Verification Screen**: Displays the email being verified. Provides a "Resend Code" button. Once the user clicks "I have verified my email" or the app polls for verification status, they are signed in.
- **Success**: Navigates to `MainAppScreen`.

### 1.2 Forgot Password Flow
- **Entry**: "Forgot Password?" link on `AuthScreen`.
- **Flow**: `AuthViewModel.resetPassword(email)` calls `FirebaseAuth.sendPasswordResetEmail`.
- **Feedback**: User receives a system email from Firebase with a secure link to reset their password on a web page.
- **Outcome**: User returns to the app and signs in with the new password.

### 1.3 Google Quick-Sign-Up
- **Flow**: User clicks Google button $\rightarrow$ `GoogleAuthManager` authenticates via Credential Manager $\rightarrow$ `FirebaseAuth.signInWithCredential`.
- **Verification**: Per user requirement, Google accounts are considered pre-verified; no additional email code is sent.

## 2. Hard Exit and Session Hardening

### 2.1 The "Double-Back" Hard Exit
Current state: `finishAndRemoveTask()` is called, but the process may remain cached in the OS task manager.
- **Mechanism**: When the double-press rule triggers the exit:
  1. Set `prefs.putBoolean("hard_exit_triggered", true)`.
  2. Call `finishAndRemoveTask()`.
- **Enforcement**: In `MainActivity.onCreate`:
  1. Check `prefs.getBoolean("hard_exit_triggered", false)`.
  2. If `true`:
     - Clear the flag (`hard_exit_triggered = false`).
     - Force `sessionUnlocked = false`.
     - Clear `userProfile` if the intent is a full logout, or simply force the `AuthScreen` to be the root.
  3. This ensures that if a user returns via the task manager after a deliberate exit, they are met with the login screen.

### 2.2 Recents Behavior
- The app will remain in recents for standard app-switching (Home button).
- The `hard_exit_triggered` flag specifically targets the deliberate double-back exit.

## 3. Security Entry (Quick-Unlock Popup)

### 3.1 Entry Flow
- **Condition**: `appLockEnabled == true` AND the app is starting cold.
- **UI Change**: Instead of immediately showing the `LockScreen`, a `SecurityEntryDialog` is presented.
- **Dialog Content**:
  - Title: "Secure Entry".
  - Option: "Unlock using [Biometric Type]".
  - Option: "Manual Sign-In" (navigates to `AuthScreen`).
- **Action**: Clicking the Biometric option triggers the existing `presentUnlock` (`BiometricPrompt`).
- **Success**: Upon successful biometric auth, `sessionUnlocked = true` and the dialog closes, landing the user in the app.

## 4. Technical Constraints & Testing

### 4.1 Firebase Setup
- Requires `google-services.json` in `app/` directory.
- Dependencies: `firebase-auth`.

### 4.2 Verification Plan
- **TDD**: New tests for `AuthViewModel` to verify Firebase call sequences.
- **Exit Test**: Trigger double-back $\rightarrow$ Open from recents $\rightarrow$ Verify `AuthScreen` is shown.
- **Lock Test**: Enable security $\rightarrow$ Cold start $\rightarrow$ Verify popup appearance $\rightarrow$ Verify biometric success.
