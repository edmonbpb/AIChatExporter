package com.example

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.data.local.AppDatabase
import com.example.data.local.ChatMessageEntity
import com.example.data.local.ChatSessionEntity
import com.example.domain.backup.BackupPayload
import com.example.domain.backup.planRestore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The restore's write half, on a real Room database in memory: a session the phone lacks is written
 * with its messages, and running the same restore twice does not double the messages. No Drive and
 * no user data are involved — the file is built here.
 */
@RunWith(AndroidJUnit4::class)
class RestoreWriteTest {

    private lateinit var db: AppDatabase

    @Before
    fun openDatabase() {
        db = Room.inMemoryDatabaseBuilder(
            InstrumentationRegistry.getInstrumentation().targetContext,
            AppDatabase::class.java
        ).allowMainThreadQueries().build()
    }

    @After
    fun closeDatabase() {
        db.close()
    }

    @Test
    fun aMissingSessionAndItsMessagesAreWrittenAndNeverDoubled() = runBlocking {
        val session = ChatSessionEntity(
            id = "restore-1", title = "restored", platformId = "CHATGPT", modelName = "gpt-4o",
            createdAt = 1L, updatedAt = 10L, tagsJson = "[]"
        )
        val message = ChatMessageEntity(
            id = "restore-1-m1", sessionId = "restore-1", role = "USER", content = "hello",
            timestamp = 1L, modelTag = null, direction = "MIXED", reasoning = null,
            codeSnippetsJson = "[]", orderIndex = 0
        )
        val payload = BackupPayload(listOf(session), listOf(message), emptyList(), emptyList())

        suspend fun runRestore() {
            val localUpdatedAt = db.chatDao().getAllSessions().first().associate { it.id to it.updatedAt }
            val plan = planRestore(payload) { id -> localUpdatedAt[id] }
            plan.forEach { target ->
                db.chatDao().saveFullSession(target, payload.messages.filter { it.sessionId == target.id })
            }
        }

        runRestore()
        assertEquals("restore-1", db.chatDao().getSessionById("restore-1")?.id)
        assertEquals(1, db.chatDao().getMessagesForSessionSync("restore-1").size)

        // The same file again: the session is now equal, so nothing is written and nothing doubles.
        runRestore()
        assertEquals(1, db.chatDao().getMessagesForSessionSync("restore-1").size)
    }
}
