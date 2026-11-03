package com.example.data.secure

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Where a secret of the user's own lives. Two names use this today, `gemini_api_key` and
 * `reader_api_key`; both used to sit in clear in `aichat_auth_prefs.xml`, the file that also carries
 * the session.
 */
interface KeyStorage {
    /** The value for [name], or the empty string. Never logs what it read. */
    fun read(name: String): String

    /** Stores [value] under [name]; an empty value removes the entry. */
    fun write(name: String, value: String)

    /** Whether the bytes on this device's disk are ciphertext. The screen must not claim more. */
    val isEncrypted: Boolean
}

private const val SECRETS_FILE = "aichat_secret_keys"
private const val LEGACY_FILE = "aichat_auth_prefs"

/** The names this app treats as secrets. */
const val GEMINI_KEY_NAME = "gemini_api_key"
const val READER_KEY_NAME = "reader_api_key"

/**
 * The value that wins: the one kept in the secrets store, or the old clear one when nothing was ever
 * stored since. Kept apart from [shouldEraseLegacy] so both halves of the move have a JVM witness.
 */
internal fun valueAfterMigration(stored: String, legacy: String): String =
    stored.ifBlank { legacy }

/**
 * The clear copy may be dropped only once the value is safe somewhere else. Erasing the one copy
 * there is would throw away a key the owner paid for.
 */
internal fun shouldEraseLegacy(legacy: String, stored: String): Boolean =
    legacy.isNotBlank() && stored.isNotBlank()

class EncryptedKeyStorage(context: Context) : KeyStorage {
    override val isEncrypted = true

    private val prefs: SharedPreferences by lazy {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            SECRETS_FILE,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    override fun read(name: String): String = prefs.getString(name, null)?.trim().orEmpty()

    override fun write(name: String, value: String) {
        prefs.edit().apply {
            if (value.isBlank()) remove(name) else putString(name, value)
        }.apply()
    }
}

/**
 * The shape the secrets had before this file existed: two strings in the session's own preferences.
 * Reads of an old install still find a value here, and the value moves on the first write.
 */
class PlainKeyStorage(context: Context) : KeyStorage {
    override val isEncrypted = false

    private val prefs = context.getSharedPreferences(LEGACY_FILE, Context.MODE_PRIVATE)

    override fun read(name: String): String = prefs.getString(name, null)?.trim().orEmpty()

    override fun write(name: String, value: String) {
        prefs.edit().apply {
            if (value.isBlank()) remove(name) else putString(name, value)
        }.apply()
    }
}

/**
 * Chosen at the view model's door. A device whose keystore refuses to answer is not a reason to
 * write a secret in clear: the caller still gets a store, and `isEncrypted` is what the screen and
 * the register have to tell the truth about.
 */
fun keyStorageFor(context: Context): KeyStorage = try {
    EncryptedKeyStorage(context).also { it.read(GEMINI_KEY_NAME) }
} catch (_: Exception) {
    PlainKeyStorage(context)
}

/**
 * Reads through [secrets], moving a value found only in the clear file. Returns the value the caller
 * should use now. One place, so neither of the two settings screens can forget the move.
 */
internal fun readWithMigration(secrets: KeyStorage, clear: KeyStorage, name: String): String {
    val stored = secrets.read(name)
    val legacy = clear.read(name)
    val value = valueAfterMigration(stored, legacy)
    if (shouldEraseLegacy(legacy, value)) {
        if (stored.isBlank()) secrets.write(name, value)
        clear.write(name, "")
    }
    return value
}
