package com.example.data.local

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Stores optional unofficial Spotify `sp_dc` session cookie in Android Keystore-backed
 * [EncryptedSharedPreferences], excluded from cloud backups and never logged.
 */
object SpotifyCanvasCredentialsStore {
    const val PREFS_NAME = "sonora_spotify_secure_prefs"
    private const val KEY_SP_DC = "spotify_sp_dc"

    @Volatile
    private var cachedPrefs: SharedPreferences? = null

    @Suppress("DEPRECATION")
    private fun prefs(context: Context): SharedPreferences? {
        cachedPrefs?.let { return it }
        val appContext = context.applicationContext
        return runCatching {
            val masterKey = MasterKey.Builder(appContext)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            EncryptedSharedPreferences.create(
                appContext,
                PREFS_NAME,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        }.getOrNull()?.also { created ->
            cachedPrefs = created
        }
    }

    fun getSpDc(context: Context): String {
        return runCatching {
            prefs(context)?.getString(KEY_SP_DC, "").orEmpty().trim()
        }.getOrDefault("")
    }

    fun hasSpDc(context: Context): Boolean = getSpDc(context).isNotBlank()

    fun saveSpDc(context: Context, rawCookie: String): Boolean {
        val cleaned = sanitizeSpDcInput(rawCookie)
        if (cleaned.isBlank()) return false
        return runCatching {
            prefs(context)?.edit()?.putString(KEY_SP_DC, cleaned)?.apply()
            true
        }.getOrDefault(false)
    }

    fun clearSpDc(context: Context) {
        runCatching {
            prefs(context)?.edit()?.remove(KEY_SP_DC)?.apply()
        }
    }

    internal fun sanitizeSpDcInput(raw: String): String {
        val trimmed = raw.trim()
        if (trimmed.isBlank()) return ""
        val match = Regex("""(?:^|[;\s])sp_dc\s*=\s*([^;\s]+)""", RegexOption.IGNORE_CASE)
            .find(trimmed)
        return (match?.groupValues?.getOrNull(1) ?: trimmed)
            .trim()
            .removePrefix("\"")
            .removeSuffix("\"")
    }
}
