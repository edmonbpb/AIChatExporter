package com.example.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/**
 * The only thing on screen while the app is locked. Every way out goes through the activity's
 * presentUnlock, so a dismissed prompt leaves the user exactly here.
 */
@Composable
fun LockScreen(
    onUnlock: () -> Unit,
    onManualSignIn: () -> Unit,
    onRemoveLock: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .testTag("lock_screen")
            .padding(24.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Icon(
                imageVector = Icons.Default.Lock,
                contentDescription = null,
                modifier = Modifier.size(64.dp),
                tint = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = "برنامه قفل است",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "برای دیدن مکالمه‌ها، قفل دستگاه را باز کن.",
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(32.dp))
            Button(
                onClick = onUnlock,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("lock_unlock_btn")
            ) { Text("باز کردن با قفل دستگاه") }
            Spacer(modifier = Modifier.height(12.dp))
            OutlinedButton(
                onClick = onManualSignIn,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("lock_manual_signin_btn")
            ) { Text("ورود دستی به حساب دیگر") }
            Spacer(modifier = Modifier.height(12.dp))
            TextButton(
                onClick = onRemoveLock,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("lock_remove_btn")
            ) { Text("حذف قفل برنامه") }
        }
    }
}
