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
 * Drive looked at the token and said no (a `401`, or a `403` that the body does not explain as a rate
 * limit) — a different fact from "no answer at all", because the consent was revoked on the Google
 * account page and reconnecting is the only fix.
 */
class DriveAccessRefusedException(val httpCode: Int) : IOException("Drive refused the access token ($httpCode)")

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
            throwIfAccessRefused(response.code, body)
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
                val answer = response.body?.string().orEmpty()
                throwIfAccessRefused(response.code, answer)
                response.isSuccessful && answer.contains("\"id\"")
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
            if (response.isSuccessful) {
                response.body?.string()
            } else {
                // The error body is read here and nowhere else: it can be consumed only once, and it is
                // the body that tells a rate limit apart from a withdrawn consent.
                throwIfAccessRefused(response.code, response.body?.string().orEmpty())
                null
            }
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
                    val errorBody = response.body?.string().orEmpty()
                    Log.w("DriveClient", "list ${response.code}: ${errorBody.take(300)}")
                    throwIfAccessRefused(response.code, errorBody)
                    return null
                }
                val bodyText = response.body?.string().orEmpty()
                Log.i("DriveClient", "list ok: ${bodyText.take(200)}")
                // Read like the folder-creation parse below it: a body Drive did not send as JSON is an
                // empty listing, not a JSONException that escapes to the caller's generic failure message.
                val files = runCatching { JSONObject(bodyText).optJSONArray("files") }.getOrNull()
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
        } catch (e: DriveAccessRefusedException) {
            throw e
        } catch (_: IOException) {
            null
        }
    }

    /**
     * The one place that decides which answers mean "the token is refused". Everything else keeps
     * treating a failure as a missing file, because it cannot tell the two apart.
     */
    private fun throwIfAccessRefused(code: Int, errorBody: String) {
        // A `401` is a refusal on its own; a `403` is not, because the same code also answers a rate
        // limit, so the body has to be read before anything is claimed about the user's consent.
        if (code == 401) throw DriveAccessRefusedException(code)
        if (code == 403 && !isRateLimited(errorBody)) throw DriveAccessRefusedException(code)
    }

    /**
     * A bare `403` is several different facts: Google sends it both for a `drive.file` permission
     * problem and for a rate limit, and only the body's `status`/`reason` say which. A user who is
     * being rate limited must not be told they revoked something.
     */
    private fun isRateLimited(errorBody: String): Boolean = runCatching {
        val error = JSONObject(errorBody).optJSONObject("error") ?: return@runCatching false
        val reasons = mutableListOf(error.optString("status"), error.optString("reason"))
        error.optJSONArray("errors")?.let { items ->
            // The reason Google itself sends sits in this array, not beside the top-level status.
            (0 until items.length()).forEach { index ->
                items.optJSONObject(index)?.let { reasons += it.optString("reason") }
            }
        }
        reasons.any { it == RATE_LIMITED || it == USER_RATE_LIMITED }
    }.getOrDefault(false)

    /**
     * A query parameter, not a form field: URLEncoder writes a space as a plus sign, and Drive reads
     * that as a literal plus inside a search expression, so the listing came back empty while the
     * upload had already worked.
     */
    private fun escape(value: String): String =
        URLEncoder.encode(value, "UTF-8").replace("+", "%20")

    private companion object {
        val JSON = "application/json; charset=utf-8".toMediaType()

        // The two bodies Drive sends when the account is simply being answered too often.
        const val RATE_LIMITED = "rateLimitExceeded"
        const val USER_RATE_LIMITED = "userRateLimitExceeded"
    }
}
