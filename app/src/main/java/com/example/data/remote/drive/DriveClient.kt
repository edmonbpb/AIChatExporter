package com.example.data.remote.drive

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * The four Drive operations a backup needs, and nothing else: find or create the visible folder,
 * upload one JSON file, list the backups, download one. Plain REST over the access token, so no
 * Drive SDK is added to a build that has to keep working offline.
 */
internal class DriveClient(
    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()
) {

    data class DriveFile(val id: String, val name: String, val sizeBytes: Long, val createdTime: String)

    /** The folder's id, creating it on first use. Null means Drive refused every attempt. */
    suspend fun ensureFolder(token: String, folderName: String): String? = withContext(Dispatchers.IO) {
        val query = "name='${escape(folderName)}' and mimeType='application/vnd.google-apps.folder' and trashed=false"
        // Oldest first: a page where an earlier run failed to see the folder can leave more than one
        // behind, and always picking the newest would start a fresh empty folder on every backup.
        listFiles(token, query, orderBy = "createdTime asc")?.firstOrNull()?.id?.let { id ->
            Log.i("DriveClient", "folder $id")
            return@withContext id
        }

        val metadata = JSONObject()
            .put("name", folderName)
            .put("mimeType", "application/vnd.google-apps.folder")
        val request = Request.Builder()
            .url("https://www.googleapis.com/drive/v3/files?fields=id")
            .header("Authorization", "Bearer $token")
            .post(metadata.toString().toRequestBody(JSON))
            .build()
        http.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) return@withContext null
            runCatching { JSONObject(body).optString("id").takeIf { it.isNotBlank() } }.getOrNull()
        }
    }

    /** Uploads one file into the folder. True only when Drive returned an id. */
    suspend fun upload(token: String, folderId: String, name: String, json: String): Boolean =
        withContext(Dispatchers.IO) {
            val metadata = JSONObject().put("name", name).put("parents", listOf(folderId))
            val body = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("metadata", null, metadata.toString().toRequestBody(JSON))
                .addFormDataPart("file", name, json.toByteArray(Charsets.UTF_8).toRequestBody(JSON))
                .build()
            val request = Request.Builder()
                .url("https://www.googleapis.com/upload/drive/v3/files?uploadType=multipart&fields=id")
                .header("Authorization", "Bearer $token")
                .post(body)
                .build()
            http.newCall(request).execute().use { response ->
                response.isSuccessful && response.body?.string().orEmpty().contains("\"id\"")
            }
        }

    /** Newest backup file in the folder, by Drive's own creation time. */
    suspend fun newestBackup(token: String, folderId: String, prefix: String): DriveFile? =
        withContext(Dispatchers.IO) {
            // Deliberately not constrained to one folder: `drive.file` already limits this query to the
            // app's own files, and a file written before the folder lookup settled must still be found.
            val query = "name contains '${escape(prefix)}' and trashed=false"
            listFiles(token, query, orderBy = "createdTime desc", limit = 1)?.firstOrNull()
        }

    /** The file's text, or null when it cannot be read. */
    suspend fun download(token: String, fileId: String): String? = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url("https://www.googleapis.com/drive/v3/files/${escape(fileId)}?alt=media")
            .header("Authorization", "Bearer $token")
            .get()
            .build()
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) null else response.body?.string()
        }
    }

    private fun listFiles(
        token: String,
        query: String,
        orderBy: String = "createdTime",
        limit: Int = 10
    ): List<DriveFile>? {
        val url = "https://www.googleapis.com/drive/v3/files" +
            "?q=${escape(query)}&orderBy=${escape(orderBy)}&pageSize=$limit" +
            "&fields=files(id,name,size,createdTime)"
        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $token")
            .get()
            .build()
        return try {
            http.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.w("DriveClient", "list ${response.code}: ${response.body?.string()?.take(300)}")
                    return null
                }
                val bodyText = response.body?.string().orEmpty()
                Log.i("DriveClient", "list ok: ${bodyText.take(200)}")
                val files = JSONObject(bodyText).optJSONArray("files")
                    ?: return emptyList()
                (0 until files.length()).mapNotNull { index ->
                    files.optJSONObject(index)?.let { file ->
                        val id = file.optString("id")
                        if (id.isBlank()) null else DriveFile(
                            id = id,
                            name = file.optString("name"),
                            sizeBytes = file.optString("size").toLongOrNull() ?: 0L,
                            createdTime = file.optString("createdTime")
                        )
                    }
                }
            }
        } catch (_: IOException) {
            null
        }
    }

    /**
     * A query parameter, not a form field: URLEncoder writes a space as a plus sign, and Drive reads
     * that as a literal plus inside a search expression, so the listing came back empty while the
     * upload had already worked.
     */
    private fun escape(value: String): String =
        URLEncoder.encode(value, "UTF-8").replace("+", "%20")

    private companion object {
        val JSON = "application/json; charset=utf-8".toMediaType()
    }
}
