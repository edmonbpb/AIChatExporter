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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.components.AdaptiveLabel
import com.example.ui.theme.Cyan40
import com.example.ui.theme.Indigo40
import com.example.ui.viewmodel.MainViewModel

@Composable
fun AuthScreen(
    viewModel: MainViewModel,
    onQuickUnlock: (() -> Unit)? = null,
    onForgotPassword: ((String) -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    var selectedTab by remember { mutableIntStateOf(0) } // 0 = Login, 1 = Sign Up
    var email by remember { mutableStateOf(viewModel.rememberedEmail.value ?: "") }
    var password by remember { mutableStateOf("") }
    var rememberMe by remember { mutableStateOf(false) }
    var passwordVisible by remember { mutableStateOf(false) }
    var isLoading by remember { mutableStateOf(false) }
    var resetRequested by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    val context = androidx.compose.ui.platform.LocalContext.current

    val scrollState = rememberScrollState()

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .testTag("auth_screen")
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(scrollState)
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Spacer(modifier = Modifier.height(20.dp))

            // App Brand Logo & Title
            Box(
                modifier = Modifier
                    .size(72.dp)
                    .clip(RoundedCornerShape(20.dp))
                    .background(
                        brush = Brush.linearGradient(listOf(Indigo40, Cyan40, Color(0xFFA855F7)))
                    )
                    .border(2.dp, Color(0xFF818CF8), RoundedCornerShape(20.dp)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.AutoAwesome,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(38.dp)
                )
            }

            Spacer(modifier = Modifier.height(14.dp))

            Text(
                text = "AIChatExporter",
                style = MaterialTheme.typography.headlineMedium.copy(
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onBackground
                )
            )

            Text(
                text = "سامانه تخصصی استخراج، پالایش و صدور اسناد هوش مصنوعی",
                style = MaterialTheme.typography.bodySmall.copy(
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                ),
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
            )

            Spacer(modifier = Modifier.height(24.dp))

            // Main Auth Card
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("auth_card"),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
            ) {
                Column(modifier = Modifier.padding(20.dp)) {
                    // Quick unlock: only present when the app lock is on and the user chose manual sign-in.
                    if (onQuickUnlock != null) {
                        OutlinedButton(
                            onClick = onQuickUnlock,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(48.dp)
                                .testTag("auth_quick_unlock_btn"),
                            shape = RoundedCornerShape(10.dp),
                            colors = ButtonDefaults.outlinedButtonColors(
                                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                            ),
                            border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Lock,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            AdaptiveLabel(
                                text = "ورود سریع با قفل امنیتی",
                                style = MaterialTheme.typography.labelMedium.copy(
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                            )
                        }
                        Spacer(modifier = Modifier.height(14.dp))
                    }

                    // Sign In / Sign Up Tabs
                    TabRow(
                        selectedTabIndex = selectedTab,
                        containerColor = MaterialTheme.colorScheme.surfaceVariant,
                        contentColor = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.clip(RoundedCornerShape(10.dp))
                    ) {
                        Tab(
                            selected = selectedTab == 0,
                            onClick = {
                                selectedTab = 0
                                errorMessage = null
                            },
                            text = { AdaptiveLabel("ورود به برنامه", itemCount = 2, fontWeight = FontWeight.Bold) },
                            modifier = Modifier.testTag("tab_login")
                        )
                        Tab(
                            selected = selectedTab == 1,
                            onClick = {
                                selectedTab = 1
                                errorMessage = null
                            },
                            text = { AdaptiveLabel("ساخت حساب کاربری", itemCount = 2, fontWeight = FontWeight.Bold) },
                            modifier = Modifier.testTag("tab_signup")
                        )
                    }

                    Spacer(modifier = Modifier.height(18.dp))

                    // Google Sign-In: account creation only, so it lives in the sign-up tab.
                    if (selectedTab == 1) {
                        OutlinedButton(
                            enabled = viewModel.isGoogleSignInConfigured,
                            onClick = {
                                isLoading = true
                                viewModel.loginWithGoogle(
                                    context = context,
                                    onSuccess = { isLoading = false },
                                    onError = {
                                        isLoading = false
                                        errorMessage = it
                                    }
                                )
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(48.dp)
                                .testTag("google_signin_button"),
                            shape = RoundedCornerShape(10.dp),
                            colors = ButtonDefaults.outlinedButtonColors(
                                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                            ),
                            border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(text = "🌐", fontSize = 18.sp)
                                Spacer(modifier = Modifier.width(8.dp))
                                AdaptiveLabel(
                                    text = "ساخت حساب کاربری سریع با حساب گوگل",
                                    style = MaterialTheme.typography.labelMedium.copy(
                                        fontWeight = FontWeight.SemiBold,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                )
                            }
                        }

                        if (!viewModel.isGoogleSignInConfigured) {
                            Text(
                                text = "این بسته با شناسهٔ OAuth گوگل ساخته نشده است؛ با ایمیل و رمز عبور ادامه بده.",
                                style = MaterialTheme.typography.labelSmall.copy(
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                ),
                                modifier = Modifier.padding(top = 6.dp)
                            )
                        }

                        Spacer(modifier = Modifier.height(16.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            HorizontalDivider(modifier = Modifier.weight(1f))
                            Text(
                                text = "یا با ایمیل و رمز عبور",
                                style = MaterialTheme.typography.labelSmall.copy(color = MaterialTheme.colorScheme.onSurfaceVariant),
                                modifier = Modifier.padding(horizontal = 10.dp)
                            )
                            HorizontalDivider(modifier = Modifier.weight(1f))
                        }

                        Spacer(modifier = Modifier.height(10.dp))
                    }

                    // Error Box
                    if (errorMessage != null) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(Color(0x22EF4444), RoundedCornerShape(8.dp))
                                .border(1.dp, Color(0xFFEF4444), RoundedCornerShape(8.dp))
                                .padding(10.dp)
                        ) {
                            Text(
                                text = "⚠️ $errorMessage",
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                        Spacer(modifier = Modifier.height(12.dp))
                    }

                    // Email Field
                    OutlinedTextField(
                        value = email,
                        onValueChange = { email = it },
                        label = { Text("آدرس ایمیل") },
                        placeholder = { Text("example@domain.com") },
                        leadingIcon = {
                            Icon(Icons.Default.Email, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Next),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("auth_email_input"),
                        shape = RoundedCornerShape(10.dp),
                        singleLine = true
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    // Password Field
                    OutlinedTextField(
                        value = password,
                        onValueChange = { password = it },
                        label = { Text("گذرواژه") },
                        leadingIcon = {
                            Icon(Icons.Default.Lock, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        },
                        trailingIcon = {
                            IconButton(onClick = { passwordVisible = !passwordVisible }) {
                                Icon(
                                    imageVector = if (passwordVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                    contentDescription = "نمایش یا پنهان‌کردن گذرواژه"
                                )
                            }
                        },
                        visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("auth_password_input"),
                        shape = RoundedCornerShape(10.dp),
                        singleLine = true
                    )

                    // Remember me: login only, and only the email is ever stored.
                    if (selectedTab == 0) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 6.dp)
                                .clickable { rememberMe = !rememberMe },
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Checkbox(
                                checked = rememberMe,
                                onCheckedChange = { rememberMe = it },
                                modifier = Modifier.testTag("auth_remember_me")
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = "مرا به خاطر بسپار",
                                style = MaterialTheme.typography.bodySmall.copy(color = MaterialTheme.colorScheme.onSurfaceVariant)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(20.dp))

                    // Submit Button
                    Button(
                        onClick = {
                            if (email.isBlank() || password.isBlank()) {
                                errorMessage = "لطفاً ایمیل و گذرواژه را وارد کنید."
                                return@Button
                            }

                            isLoading = true
                            errorMessage = null

                            if (selectedTab == 0) {
                                viewModel.loginWithEmail(
                                    email = email.trim(),
                                    password = password,
                                    onSuccess = {
                                        isLoading = false
                                        viewModel.setRememberedEmail(email.trim(), rememberMe)
                                    },
                                    onError = {
                                        isLoading = false
                                        errorMessage = it
                                    }
                                )
                            } else {
                                viewModel.registerWithEmail(
                                    email = email.trim(),
                                    password = password,
                                    onSuccess = { isLoading = false },
                                    onError = {
                                        isLoading = false
                                        errorMessage = it
                                    }
                                )
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(50.dp)
                            .testTag("auth_submit_btn"),
                        shape = RoundedCornerShape(10.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                        enabled = !isLoading
                    ) {
                        if (isLoading) {
                            CircularProgressIndicator(color = Color.White, modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                        } else {
                            AdaptiveLabel(
                                text = if (selectedTab == 0) "ورود به برنامه" else "ساخت حساب کاربری",
                                style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold, fontSize = 15.sp)
                            )
                        }
                    }

                    // The owner who cannot recall the password still has a way in. Login tab only: the
                    // signup tab creates an account, so a reset link there would be a lie. Nothing about
                    // the address's shape or the password's length is re-checked here - the account road
                    // (Task 2) owns those rules and answers with the one sentence the owner is shown.
                    if (selectedTab == 0 && onForgotPassword != null) {
                        TextButton(
                            onClick = {
                                resetRequested = true
                                onForgotPassword(email)
                            },
                            modifier = Modifier
                                .padding(top = 4.dp)
                                .testTag("auth_forgot_password_btn")
                        ) {
                            // maxLines = 2 because AdaptiveLabel's own default is one line, and one line
                            // cuts the Persian sentence off with an ellipsis.
                            AdaptiveLabel(
                                text = "رمز عبور را فراموش کرده‌ام",
                                maxLines = 2
                            )
                        }

                        if (resetRequested) {
                            Text(
                                text = "راهنمای تغییر رمز به آن نشانی فرستاده شد؛ از ایمیل استفاده کن.",
                                style = MaterialTheme.typography.labelSmall.copy(
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                ),
                                modifier = Modifier
                                    .padding(top = 8.dp)
                                    .testTag("auth_reset_hint")
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            // Offline & Privacy Promise
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Shield,
                    contentDescription = null,
                    tint = Color(0xFF10B981),
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = "حفظ حریم خصوصی: متن گفتگو روی همین دستگاه ذخیره می‌شود. بدونِ کلیدِ Gemini هیچ متنی به جایی فرستاده نمی‌شود؛ با کلیدِ واردشده، متنِ پالایش برای گرفتنِ پاسخ به گوگل می‌رود. بازکردنِ لینکِ اشتراک هم خودش یک درخواستِ بیرونی می‌خواهد — مسیرهایِ بیرونی در تنظیمات نوشته شده‌اند.",
                    style = MaterialTheme.typography.labelSmall.copy(color = Color(0xFF10B981), fontSize = 10.5.sp, textAlign = TextAlign.Center)
                )
            }

            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}
