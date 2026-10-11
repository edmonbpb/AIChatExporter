package com.example

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.data.local.AIDistillationEntity
import com.example.data.local.AppDatabase
import com.example.data.local.ChatMessageEntity
import com.example.data.local.ChatSessionEntity
import com.example.data.local.Converters
import com.example.data.local.ExportRecordEntity
import com.example.data.model.CodeSnippet
import com.example.data.model.MessageRole
import com.example.data.model.TextDirection
import java.io.DataInputStream
import java.io.FileInputStream
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Proves the library really survives a process death: everything is written through the production
 * DAOs into a file-backed Room database, that instance is CLOSED, and only a freshly built second
 * instance over the same file is allowed to answer the read-back.
 *
 * The database name is this test's own. `aichat_exporter.db` holds the owner's conversations on the
 * device this runs on, and Room sits on `version = 1` with `fallbackToDestructiveMigration()`, so a
 * wrong name here would be an irreversible loss, not a failed assertion.
 */
@RunWith(AndroidJUnit4::class)
class PersistenceRoundTripTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val converters = Converters()

    // A name of our own, so nothing in this file can reach the production database even by accident.
    private val dbName = "${javaClass.simpleName}.db"

    private val createdAt = 1_760_000_000_000L
    private val updatedAt = 1_760_000_900_000L
    private val firstTurnAt = 1_760_000_100_000L
    private val secondTurnAt = 1_760_000_200_000L
    private val thirdTurnAt = 1_760_000_300_000L
    private val fourthTurnAt = 1_760_000_400_000L
    private val exportedAt = 1_760_001_000_000L
    private val distilledAt = 1_760_000_800_000L

    private val sessionId = "w9-round-trip-session"

    /**
     * A Persian sample spelled with escapes, so this source file stays ASCII on disk. Persian is the
     * script the owner's real transcripts are in, and it is the part of a row most likely to be
     * mangled on the way into and out of a column.
     */
    private val nonAsciiSample = "فارسی"
    private val codeSnippets = listOf(
        CodeSnippet(
            id = "w9-snippet-1",
            language = "kotlin",
            code = "fun greet(name: String) {\n    println(name)\n}"
        )
    )

    /** Mirrors `MainViewModel.saveActiveSessionToDatabase` field for field, including the converters. */
    private fun writtenSession() = ChatSessionEntity(
        id = sessionId,
        title = "W9 round trip title $nonAsciiSample",
        platformId = "chatgpt",
        modelName = "gpt-4o-2024-08-06",
        createdAt = createdAt,
        updatedAt = updatedAt,
        tagsJson = converters.fromStringList(listOf("w9", "round trip", nonAsciiSample)),
        isBookmarked = true,
        customNotes = "a note that is not the default empty value"
    )

    private fun writtenMessages() = listOf(
        ChatMessageEntity(
            id = "w9-message-1",
            sessionId = sessionId,
            role = MessageRole.USER.name,
            content = "What does the exporter write?\nAnd on its second line?",
            timestamp = firstTurnAt,
            modelTag = null,
            direction = TextDirection.LTR.name,
            reasoning = null,
            codeSnippetsJson = converters.fromCodeSnippetList(emptyList()),
            orderIndex = 0
        ),
        ChatMessageEntity(
            id = "w9-message-2",
            sessionId = sessionId,
            role = MessageRole.ASSISTANT.name,
            content = "Documents, and the script stays put: $nonAsciiSample",
            timestamp = secondTurnAt,
            modelTag = "gpt-4o-2024-08-06",
            direction = TextDirection.MIXED.name,
            reasoning = "checked the format list before answering",
            codeSnippetsJson = converters.fromCodeSnippetList(codeSnippets),
            orderIndex = 1
        ),
        ChatMessageEntity(
            id = "w9-message-3",
            sessionId = sessionId,
            role = MessageRole.SYSTEM.name,
            content = "",
            timestamp = thirdTurnAt,
            modelTag = null,
            direction = TextDirection.RTL.name,
            reasoning = null,
            codeSnippetsJson = converters.fromCodeSnippetList(emptyList()),
            orderIndex = 2
        ),
        ChatMessageEntity(
            id = "w9-message-4",
            sessionId = sessionId,
            role = MessageRole.ASSISTANT.name,
            content = "Here is the code block.",
            timestamp = fourthTurnAt,
            modelTag = "gpt-4o-2024-08-06",
            direction = TextDirection.MIXED.name,
            reasoning = null,
            codeSnippetsJson = converters.fromCodeSnippetList(codeSnippets),
            orderIndex = 3
        )
    )

    private fun writtenDistillation() = AIDistillationEntity(
        id = "w9-distillation-1",
        sessionId = sessionId,
        type = "EXECUTIVE_SUMMARY",
        title = "Executive Summary $nonAsciiSample",
        content = "Decision one.\nDecision two.",
        modelUsed = "gpt-4o-2024-08-06",
        createdAt = distilledAt
    )

    /**
     * The path here is one this test never writes to disk: what matters is that the exact string the
     * archive row carries — separators, spaces, Persian and all — is the string read back.
     */
    private fun writtenExportRecord() = ExportRecordEntity(
        id = "w9-export-1",
        sessionId = sessionId,
        sessionTitle = "W9 round trip title $nonAsciiSample",
        fileName = "AIChat_W9_round_trip_${nonAsciiSample}_20251010_120000.pdf",
        format = "pdf",
        filePath = "/data/user/0/com.example/files/documents/AIChat_W9_${nonAsciiSample}_20251010_120000.pdf",
        fileSizeFormatted = "41 KB",
        exportedAt = exportedAt
    )

    @Before
    fun startFromAnEmptyFile() {
        assertNotEquals(PRODUCTION_DB_NAME, dbName)
        context.deleteDatabase(dbName)
    }

    @After
    fun removeTheFileThisTestCreated() {
        // Only ever our own name: the owner's library in the same directory is left untouched.
        context.deleteDatabase(dbName)
    }

    @Test
    fun writtenRowsComeBackUnchangedFromAReopenedDatabase() {
        val session = writtenSession()
        val messages = writtenMessages()
        val distillation = writtenDistillation()
        val exportRecord = writtenExportRecord()

        val writer = Room.databaseBuilder(context, AppDatabase::class.java, dbName).build()
        try {
            runBlocking {
                writer.chatDao().saveFullSession(session, messages)
                writer.distillationDao().insertDistillation(distillation)
                writer.exportDao().insertExportRecord(exportRecord)
            }
            // The close is only a witness if this instance really was open first.
            assertTrue("Room had not opened a connection, so closing it proves nothing", writer.isOpen)
        } finally {
            writer.close()
        }
        assertFalse("close() left the connection open; the read-back would not come off the disk", writer.isOpen)

        val dbFile = context.getDatabasePath(dbName)
        assertTrue(
            "no database file was written at ${dbFile.absolutePath}",
            dbFile.exists() && dbFile.length() >= SQLITE_HEADER.length
        )
        val header = ByteArray(SQLITE_HEADER.length)
        DataInputStream(FileInputStream(dbFile)).use { it.readFully(header) }
        assertEquals(
            "the file on disk is not a SQLite database, so nothing here was ever durable",
            SQLITE_HEADER,
            String(header, Charsets.ISO_8859_1)
        )

        val reader = Room.databaseBuilder(context, AppDatabase::class.java, dbName).build()
        assertNotSame(
            "the read came from the instance that wrote it instead of a reopened one",
            writer,
            reader
        )
        try {
            runBlocking {
                assertSessionSurvived(session, reader.chatDao().getSessionById(sessionId))
                assertMessagesSurvived(messages, reader.chatDao().getMessagesForSessionSync(sessionId))
                assertDistillationSurvived(
                    distillation,
                    reader.distillationDao().getDistillationsForSessionSync(sessionId)
                )
                // The archive history screen reads exports through this flow and nothing else.
                val records = reader.exportDao().getAllExportRecords().first()
                assertExportRecordSurvived(exportRecord, records)
            }
        } finally {
            reader.close()
        }
    }

    private fun assertSessionSurvived(
        written: ChatSessionEntity,
        read: ChatSessionEntity?,
    ) {
        assertNotNull("the session row was not there after the re-open", read)
        val actual = read!!
        assertEquals("session id", written.id, actual.id)
        assertEquals("session title", written.title, actual.title)
        assertEquals("platform id", written.platformId, actual.platformId)
        assertEquals("model name", written.modelName, actual.modelName)
        assertEquals("created at", written.createdAt, actual.createdAt)
        assertEquals("updated at", written.updatedAt, actual.updatedAt)
        assertEquals("bookmarked", written.isBookmarked, actual.isBookmarked)
        assertEquals("custom notes", written.customNotes, actual.customNotes)
        // The column holds JSON; the tags the library shows come from decoding it back.
        assertEquals(
            "tags decoded from the stored column",
            converters.toStringList(written.tagsJson),
            converters.toStringList(actual.tagsJson)
        )
        assertEquals("tags were not stored as a JSON array", written.tagsJson, actual.tagsJson)
    }

    private fun assertMessagesSurvived(
        written: List<ChatMessageEntity>,
        read: List<ChatMessageEntity>,
    ) {
        assertEquals(
            "turn count after the re-open (a missing row is a broken insert or an unintended cascade)",
            written.size,
            read.size
        )
        assertEquals(
            "turn order after the re-open (the DAO orders by orderIndex, so the transcript reads in sequence)",
            written.map { it.id },
            read.map { it.id }
        )
        written.forEachIndexed { index, expected ->
            val actual = read[index]
            assertEquals("message ${index + 1} id", expected.id, actual.id)
            assertEquals("message ${index + 1} role", expected.role, actual.role)
            assertEquals("message ${index + 1} content", expected.content, actual.content)
            assertEquals("message ${index + 1} timestamp", expected.timestamp, actual.timestamp)
            assertEquals("message ${index + 1} direction", expected.direction, actual.direction)
            assertEquals("message ${index + 1} order index", expected.orderIndex, actual.orderIndex)
            // A null modelTag must not come back as the empty string, or the header prints a blank name.
            if (expected.modelTag == null) {
                assertNull("message ${index + 1} model tag", actual.modelTag)
            } else {
                assertEquals("message ${index + 1} model tag", expected.modelTag, actual.modelTag)
            }
            if (expected.reasoning == null) {
                assertNull("message ${index + 1} reasoning", actual.reasoning)
            } else {
                assertEquals("message ${index + 1} reasoning", expected.reasoning, actual.reasoning)
            }
            assertEquals(
                "message ${index + 1} code snippets",
                converters.toCodeSnippetList(expected.codeSnippetsJson).map { it.id to it.code },
                converters.toCodeSnippetList(actual.codeSnippetsJson).map { it.id to it.code }
            )
        }
    }

    private fun assertDistillationSurvived(
        written: AIDistillationEntity,
        read: List<AIDistillationEntity>,
    ) {
        assertEquals("distillation history size after the re-open", 1, read.size)
        val actual = read.first()
        assertEquals("distillation id", written.id, actual.id)
        assertEquals("distillation type", written.type, actual.type)
        assertEquals("distillation title", written.title, actual.title)
        assertEquals("distillation content", written.content, actual.content)
        assertEquals("distillation model", written.modelUsed, actual.modelUsed)
        assertEquals("distillation created at", written.createdAt, actual.createdAt)
    }

    private fun assertExportRecordSurvived(
        written: ExportRecordEntity,
        read: List<ExportRecordEntity>,
    ) {
        assertEquals("export history size after the re-open", 1, read.size)
        val actual = read.first()
        assertEquals("export id", written.id, actual.id)
        assertEquals("export session id", written.sessionId, actual.sessionId)
        assertEquals("export session title", written.sessionTitle, actual.sessionTitle)
        assertEquals("export file name", written.fileName, actual.fileName)
        assertEquals("export format", written.format, actual.format)
        assertEquals("export file path", written.filePath, actual.filePath)
        assertEquals("export size string", written.fileSizeFormatted, actual.fileSizeFormatted)
        assertEquals("export timestamp", written.exportedAt, actual.exportedAt)
    }

    private companion object {
        const val PRODUCTION_DB_NAME = "aichat_exporter.db"
        val SQLITE_HEADER = "SQLite format 3\u0000"
    }
}
