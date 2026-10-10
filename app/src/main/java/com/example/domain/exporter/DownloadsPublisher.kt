package com.example.domain.exporter

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.io.IOException

/**
 * Publishes the export copy into the public Download directory through MediaStore, the only path
 * that needs no storage permission. The app requires Android 10+ (API 29), so no pre-Q branch is
 * handled here.
 */
internal object DownloadsPublisher {

    sealed class Outcome {
        data class Published(val displayName: String) : Outcome()
        data class Failed(val reason: String) : Outcome()
    }

    fun publish(
        context: Context,
        source: File,
        displayName: String,
        mimeType: String
    ): Outcome {
        val resolver = context.contentResolver
        val uri: Uri? = try {
            resolver.insert(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
                    put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
                    put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                }
            )
        } catch (e: Exception) {
            return Outcome.Failed(e.message ?: "رونوشت در Download ذخیره نشد.")
        }
        if (uri == null) return Outcome.Failed("رونوشت در Download ذخیره نشد.")

        try {
            val target = resolver.openOutputStream(uri)
                ?: throw IOException("رونوشت در Download ذخیره نشد.")
            target.use { output ->
                source.inputStream().use { input ->
                    val buffer = ByteArray(BUFFER_SIZE)
                    var read = input.read(buffer)
                    while (read != -1) {
                        output.write(buffer, 0, read)
                        read = input.read(buffer)
                    }
                }
            }
        } catch (e: Exception) {
            // A half-copied row would stay visible as a broken file, so it is removed instead.
            runCatching { resolver.delete(uri, null, null) }
            return Outcome.Failed(e.message ?: "رونوشت در Download ذخیره نشد.")
        } finally {
            // IS_PENDING must be cleared on the failure path too, otherwise the row stays
            // pending forever and the file never appears in Download.
            runCatching {
                resolver.update(
                    uri,
                    ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) },
                    null,
                    null
                )
            }
        }

        return Outcome.Published(displayName)
    }

    private const val BUFFER_SIZE = 8 * 1024
}
