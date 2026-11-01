package com.example.ui.screens

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag

/**
 * The door the owner asked for: an app they closed on purpose now wants a login, and if they turned
 * the security switch on they should be offered that lock here instead of being made to type.
 * The second button is not a decline of security — it is the way to another account.
 */
@Composable
fun SecurityEntryDialog(
    lockLabel: String,
    onUnlock: () -> Unit,
    onUseAccount: () -> Unit,
    modifier: Modifier = Modifier
) {
    AlertDialog(
        onDismissRequest = onUseAccount,
        modifier = modifier.testTag("security_entry_dialog"),
        title = { Text("ورود سریع") },
        text = { Text("با «$lockLabel» می‌توانی بدون نوشتن ایمیل و رمز وارد شوی.") },
        confirmButton = {
            Button(
                onClick = onUnlock,
                modifier = Modifier.testTag("security_entry_unlock_btn")
            ) { Text("باز کردن با قفل امنیتی") }
        },
        dismissButton = {
            TextButton(
                onClick = onUseAccount,
                modifier = Modifier.testTag("security_entry_manual_btn")
            ) { Text("ورود با ایمیل و رمز") }
        }
    )
}
