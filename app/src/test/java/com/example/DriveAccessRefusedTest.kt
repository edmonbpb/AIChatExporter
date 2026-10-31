package com.example

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.backup.BackupManager
import com.example.data.local.AppDatabase
import com.example.data.local.ChatMessageEntity
import com.example.data.local.ChatSessionEntity
import com.example.data.remote.drive.DriveAccessRefusedException
import com.example.data.remote.drive.DriveClient
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

/**
 * B-43, measured on the phone: after the consent is revoked on the Google account page Google keeps
 * minting an access token, so the card still reads "connected" while every Drive call answers `401`
 * and the backup/restore buttons can only ever fail. The refusal has to survive the boundaries it
 * used to die at — Drive's HTTP answer, then [BackupManager]'s outcome — instead of collapsing into
 * the same `null` as "no answer at all".
 *
 * Only Drive itself is replaced: a scripted OkHttp interceptor answers each request, so no socket is
 * opened and no device is touched. [DriveClient], [BackupManager] and a real Room database run the
 * way they do on the phone.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@LooperMode(LooperMode.Mode.PAUSED)
class DriveAccessRefusedTest {

    private lateinit var application: Application
    private lateinit var db: AppDatabase

    @Before
    fun setUp() {
        application = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(application, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun folderLookupRefusedWith401CrossesTheBoundaryWithItsCode() {
        val drive = DriveClient(http(Answer(401, "Unauthorized", REFUSED_BODY)))
        try {
            runBlocking { drive.ensureFolder(TOKEN, FOLDER) }
            fail("a 401 must not look like a missing folder")
        } catch (expected: DriveAccessRefusedException) {
            assertEquals(401, expected.httpCode)
        }
    }

    @Test
    fun folderLookupRefusedWith403CrossesTheBoundaryWithItsCode() {
        val drive = DriveClient(http(Answer(403, "Forbidden", REFUSED_BODY)))
        try {
            runBlocking { drive.ensureFolder(TOKEN, FOLDER) }
            fail("a 403 must not look like a missing folder")
        } catch (expected: DriveAccessRefusedException) {
            assertEquals(403, expected.httpCode)
        }
    }

    @Test
    fun backUpRefusedOnTheFirstCallReportsRevokedAccess() {
        val manager = manager(Answer(401, "Unauthorized", REFUSED_BODY))

        val outcome = runBlocking { manager.backUp(TOKEN) }

        assertFalse(outcome.ok)
        assertTrue(outcome.accessRefused)
        assertEquals(REVOKED_MESSAGE, outcome.message)
    }

    @Test
    fun backUpRefusedOnTheUploadReportsRevokedAccessAndIsNotCountedAsARun() {
        val manager = manager(
            Answer(200, "OK", FOLDER_LIST),
            Answer(401, "Unauthorized", REFUSED_BODY)
        )

        val outcome = runBlocking { manager.backUp(TOKEN) }

        assertFalse(outcome.ok)
        assertTrue(outcome.accessRefused)
        assertEquals(REVOKED_MESSAGE, outcome.message)
        assertNull("a refused backup must not mark the day as backed up", manager.lastBackupDate())
    }

    @Test
    fun restoreRefusedOnTheDownloadReportsRevokedAccessAndLeavesTheLibraryAlone() {
        val session = sessionEntity()
        val message = messageEntity(session.id)
        runBlocking { db.chatDao().saveFullSession(session, listOf(message)) }
        val manager = manager(
            Answer(200, "OK", FOLDER_LIST),
            Answer(200, "OK", BACKUP_LIST),
            Answer(401, "Unauthorized", REFUSED_BODY)
        )

        val outcome = runBlocking { manager.restoreLatest(TOKEN) }

        assertFalse(outcome.ok)
        assertTrue(outcome.accessRefused)
        assertEquals(REVOKED_MESSAGE, outcome.message)
        assertEquals(listOf(session), runBlocking { db.chatDao().getAllSessions().first() })
        assertEquals(listOf(message), runBlocking { db.chatDao().getMessagesForSessionSync(session.id) })
        assertTrue(runBlocking { db.exportDao().getAllExportRecords().first() }.isEmpty())
    }

    @Test
    fun serverErrorStillFailsTheWayItDidBefore() {
        val manager = manager(Answer(500, "Internal Server Error", SERVER_ERROR_BODY))

        val outcome = runBlocking { manager.backUp(TOKEN) }

        assertFalse(outcome.ok)
        assertFalse("a 500 says nothing about the consent", outcome.accessRefused)
        assertNotEquals(REVOKED_MESSAGE, outcome.message)
    }

    private fun manager(vararg answers: Answer): BackupManager =
        BackupManager(application, db, DriveClient(http(*answers)))

    private fun http(vararg answers: Answer): OkHttpClient =
        OkHttpClient.Builder()
            .addInterceptor(ScriptedDriveHttp(answers.toList()))
            .build()

    private fun sessionEntity() = ChatSessionEntity(
        id = "session-1",
        title = "گفتگوی نمونه",
        platformId = "chatgpt",
        modelName = "chatgpt",
        createdAt = 1_700_000_000_000L,
        updatedAt = 1_730_000_000_000L,
        tagsJson = "[]"
    )

    private fun messageEntity(sessionId: String) = ChatMessageEntity(
        id = "message-1",
        sessionId = sessionId,
        role = "USER",
        content = "سلام",
        timestamp = 1_730_000_000_000L,
        modelTag = null,
        direction = "RTL",
        reasoning = null,
        codeSnippetsJson = "[]",
        orderIndex = 0
    )

    private companion object {
        const val TOKEN = "token-still-minted-after-revocation"
        const val FOLDER = "AIChatExporter Backups"

        // The answer Google actually sends once the consent was withdrawn.
        const val REFUSED_BODY =
            """{"error":{"code":401,"message":"Request had invalid authentication credentials.","status":"UNAUTHENTICATED"}}"""
        const val SERVER_ERROR_BODY = """{"error":{"message":"Backend error."}}"""
        const val FOLDER_LIST = """{"files":[{"id":"folder-1","name":"AIChatExporter Backups"}]}"""
        const val BACKUP_LIST =
            """{"files":[{"id":"backup-1","name":"aichat-export.json","size":"4096",""" +
                """"createdTime":"2026-10-31T10:15:00Z"}]}"""

        // The app's own copy, asserted verbatim so what the user reads cannot drift unnoticed.
        const val REVOKED_MESSAGE =
            "دسترسی این برنامه به گوگل درایو در حسابِ تو برداشته شده است؛ با دکمهٔ اتصال دوباره وصلش کن."
    }
}

/** One canned Drive answer: the status Drive sends, and the body it sends with it. */
private data class Answer(val code: Int, val status: String, val body: String)

/**
 * Answers from a script instead of the network. The last answer repeats once the script runs out, so
 * a test names only the responses it cares about and still cannot reach a real socket.
 */
private class ScriptedDriveHttp(private val answers: List<Answer>) : Interceptor {

    private val remaining = ArrayDeque(answers)

    override fun intercept(chain: Interceptor.Chain): Response {
        val answer = if (remaining.size > 1) remaining.removeFirst() else remaining.last()
        return Response.Builder()
            .request(chain.request())
            .protocol(Protocol.HTTP_1_1)
            .code(answer.code)
            .message(answer.status)
            .body(answer.body.toResponseBody(JSON))
            .build()
    }

    private companion object {
        val JSON = "application/json; charset=utf-8".toMediaType()
    }
}
