package com.example.data.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.model.AccentPreset
import com.example.model.AppIconVariant
import com.example.model.AudioQuality
import com.example.model.EqPreset
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
        val CUSTOM_ACCENT_HEX = longPreferencesKey("custom_accent_hex")
        val APP_ICON_VARIANT = stringPreferencesKey("app_icon_variant")
        val ANIMATED_ARTWORK = booleanPreferencesKey("animated_artwork")
        val EQ_ENABLED = booleanPreferencesKey("eq_enabled")
        val EQ_PRESET = stringPreferencesKey("eq_preset")
        val BASS_BOOST = intPreferencesKey("bass_boost_strength")
        val NORMALIZE_VOLUME = booleanPreferencesKey("normalize_volume")
        val WIFI_ONLY_DOWNLOADS = booleanPreferencesKey("wifi_only_downloads")
        val LYRICS_OFFSET_MS = longPreferencesKey("lyrics_offset_ms")
        val GLASS_STYLE = stringPreferencesKey("glass_style")
    }

    val settingsFlow: Flow<SonoraSettings> = context.dataStore.data
        .catch { err ->
            if (err is IOException) emit(emptyPreferences()) else throw err
        }
        .map { prefs ->
            val customHex = prefs[Keys.CUSTOM_ACCENT_HEX]?.takeIf { it != 0L }
            SonoraSettings(
                audioQuality = prefs[Keys.AUDIO_QUALITY]
                    ?.let { name -> runCatching { AudioQuality.valueOf(name) }.getOrNull() }
                    ?: AudioQuality.MAX,
                autoplayRadio = prefs[Keys.AUTOPLAY_RADIO] ?: true,
                skipSilence = prefs[Keys.SKIP_SILENCE] ?: false,
                accentPreset = prefs[Keys.ACCENT_PRESET]
                    ?.let { name -> runCatching { AccentPreset.valueOf(name) }.getOrNull() }
                    ?: AccentPreset.CRIMSON,
                customAccentHex = customHex,
                appIconVariant = prefs[Keys.APP_ICON_VARIANT]
                    ?.let { name -> runCatching { AppIconVariant.valueOf(name) }.getOrNull() }
                    ?: AppIconVariant.EMERALD,
                animatedArtworkEnabled = prefs[Keys.ANIMATED_ARTWORK] ?: true,
                eqEnabled = prefs[Keys.EQ_ENABLED] ?: false,
                eqPreset = prefs[Keys.EQ_PRESET]
                    ?.let { name -> runCatching { EqPreset.valueOf(name) }.getOrNull() }
                    ?: EqPreset.FLAT,
                bassBoostStrength = (prefs[Keys.BASS_BOOST] ?: 0).coerceIn(0, 1000),
                normalizeVolume = prefs[Keys.NORMALIZE_VOLUME] ?: false,
                wifiOnlyDownloads = prefs[Keys.WIFI_ONLY_DOWNLOADS] ?: false,
                lyricsOffsetMs = (prefs[Keys.LYRICS_OFFSET_MS] ?: 0L).coerceIn(-10_000L, 10_000L),
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
        context.dataStore.edit {
            it[Keys.ACCENT_PRESET] = preset.name
            it.remove(Keys.CUSTOM_ACCENT_HEX)
        }
    }

    suspend fun setCustomAccentHex(colorHex: Long) {
        context.dataStore.edit {
            it[Keys.CUSTOM_ACCENT_HEX] = colorHex
        }
    }

    suspend fun setAppIconVariant(variant: AppIconVariant) {
        context.dataStore.edit { it[Keys.APP_ICON_VARIANT] = variant.name }
    }

    suspend fun setAnimatedArtworkEnabled(enabled: Boolean) {
        context.dataStore.edit { it[Keys.ANIMATED_ARTWORK] = enabled }
    }

    suspend fun setEqEnabled(enabled: Boolean) {
        context.dataStore.edit { it[Keys.EQ_ENABLED] = enabled }
    }

    suspend fun setEqPreset(preset: EqPreset) {
        context.dataStore.edit { it[Keys.EQ_PRESET] = preset.name }
    }

    suspend fun setBassBoostStrength(strength: Int) {
        context.dataStore.edit { it[Keys.BASS_BOOST] = strength.coerceIn(0, 1000) }
    }

    suspend fun setNormalizeVolume(enabled: Boolean) {
        context.dataStore.edit { it[Keys.NORMALIZE_VOLUME] = enabled }
    }

    suspend fun setWifiOnlyDownloads(enabled: Boolean) {
        context.dataStore.edit { it[Keys.WIFI_ONLY_DOWNLOADS] = enabled }
    }

    suspend fun setLyricsOffsetMs(offsetMs: Long) {
        context.dataStore.edit { it[Keys.LYRICS_OFFSET_MS] = offsetMs.coerceIn(-10_000L, 10_000L) }
    }

    suspend fun setGlassStyle(style: GlassStyle) {
        context.dataStore.edit { it[Keys.GLASS_STYLE] = style.name }
    }
}
