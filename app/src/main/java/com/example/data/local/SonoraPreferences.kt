package com.example.data.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.model.AccentPreset
import com.example.model.AudioQuality
import com.example.model.GlassStyle
import com.example.model.SonoraSettings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import java.io.IOException

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "sonora_settings")

class SonoraPreferences(private val context: Context) {

    private object Keys {
        val AUDIO_QUALITY = stringPreferencesKey("audio_quality")
        val AUTOPLAY_RADIO = booleanPreferencesKey("autoplay_radio")
        val SKIP_SILENCE = booleanPreferencesKey("skip_silence")
        val ACCENT_PRESET = stringPreferencesKey("accent_preset")
        val GLASS_STYLE = stringPreferencesKey("glass_style")
    }

    val settingsFlow: Flow<SonoraSettings> = context.dataStore.data
        .catch { err ->
            if (err is IOException) emit(emptyPreferences()) else throw err
        }
        .map { prefs ->
            SonoraSettings(
                audioQuality = prefs[Keys.AUDIO_QUALITY]
                    ?.let { name -> runCatching { AudioQuality.valueOf(name) }.getOrNull() }
                    ?: AudioQuality.MAX,
                autoplayRadio = prefs[Keys.AUTOPLAY_RADIO] ?: true,
                skipSilence = prefs[Keys.SKIP_SILENCE] ?: false,
                accentPreset = prefs[Keys.ACCENT_PRESET]
                    ?.let { name -> runCatching { AccentPreset.valueOf(name) }.getOrNull() }
                    ?: AccentPreset.CRIMSON,
                glassStyle = prefs[Keys.GLASS_STYLE]
                    ?.let { name -> runCatching { GlassStyle.valueOf(name) }.getOrNull() }
                    ?: GlassStyle.LIQUID
            )
        }

    suspend fun setAudioQuality(quality: AudioQuality) {
        context.dataStore.edit { it[Keys.AUDIO_QUALITY] = quality.name }
    }

    suspend fun setAutoplayRadio(enabled: Boolean) {
        context.dataStore.edit { it[Keys.AUTOPLAY_RADIO] = enabled }
    }

    suspend fun setSkipSilence(enabled: Boolean) {
        context.dataStore.edit { it[Keys.SKIP_SILENCE] = enabled }
    }

    suspend fun setAccentPreset(preset: AccentPreset) {
        context.dataStore.edit { it[Keys.ACCENT_PRESET] = preset.name }
    }

    suspend fun setGlassStyle(style: GlassStyle) {
        context.dataStore.edit { it[Keys.GLASS_STYLE] = style.name }
    }
}
