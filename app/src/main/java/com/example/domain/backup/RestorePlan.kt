package com.example.domain.backup

import com.example.data.local.ChatSessionEntity

/**
 * Which sessions a restore is allowed to write. The rule is the one that protects the user's newest
 * work: a session the backup carries is written only when the phone has no copy of it or the phone's
 * copy is older. A session the file never mentions is not even looked at.
 *
 * Pure, so the decision can be driven on the JVM without a database or a Drive account.
 */
internal fun planRestore(
    payload: BackupPayload,
    localUpdatedAt: (String) -> Long?
): List<ChatSessionEntity> = payload.sessions.filter { session ->
    BackupFormat.backupWins(localUpdatedAt(session.id), session.updatedAt)
}
