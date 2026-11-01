package com.example.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.ui.viewmodel.MainViewModel

/**
 * The half-finished sign-up's home. Everything the owner has to do out there — open the mail, tap the
 * link — happens outside this process, so the only honest affordances are "ask whether it is done",
 * "send it again" and "I give up for now".
 */
@Composable
fun VerificationScreen(viewModel: MainViewModel, email: String, modifier: Modifier = Modifier) {
    var note by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }

    Box(
        modifier = modifier
            .fillMaxSize()
            .testTag("verification_screen"),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = "ایمیلت را تأیید کن",
                style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(10.dp))
            Text(
                text = "یک لینک تأیید به نشانی",
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center
            )
            Text(
                text = email,
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(vertical = 6.dp)
            )
            Text(
                text = "فرستادیم. بازش کن و روی لینک بزن، بعد برگرد و وضعیت را بررسی کن.",
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center
            )

            note?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall.copy(
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    ),
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .padding(top = 12.dp)
                        .testTag("verification_note")
                )
            }

            Spacer(modifier = Modifier.height(22.dp))

            Button(
                enabled = !busy,
                onClick = {
                    busy = true
                    viewModel.checkVerificationNow(
                        onVerified = { busy = false },
                        onStillPending = {
                            busy = false
                            note = "لینک هنوز باز نشده است. بعد از زدنش دوباره بررسی کن."
                        },
                        onError = {
                            busy = false
                            note = it
                        }
                    )
                },
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .testTag("verification_check_btn")
            ) {
                Text("بررسی وضعیت تأیید")
            }

            Spacer(modifier = Modifier.height(10.dp))

            OutlinedButton(
                enabled = !busy,
                onClick = {
                    busy = true
                    viewModel.resendVerificationEmail(
                        onSent = {
                            busy = false
                            note = "یک ایمیل دیگر فرستادیم."
                        },
                        onError = {
                            busy = false
                            note = it
                        }
                    )
                },
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .testTag("verification_resend_btn")
            ) {
                Text("فرستادن دوبارهٔ ایمیل")
            }

            Spacer(modifier = Modifier.height(6.dp))

            TextButton(
                onClick = { viewModel.leaveVerificationWait() },
                modifier = Modifier.testTag("verification_back_btn")
            ) {
                Text("بازگشت به صفحهٔ ورود")
            }

            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = "تا ایمیل تأیید نشود، حساب در این برنامه باز نمی‌شود.",
                style = MaterialTheme.typography.labelSmall.copy(
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                ),
                textAlign = TextAlign.Center
            )
        }
    }
}
