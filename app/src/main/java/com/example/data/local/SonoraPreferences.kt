package com.example.data.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.model.AccentPreset
import com.example.model.AppIconVariant
import com.example.model.AudioOutputRoute
import com.example.model.AudioQuality
import com.example.model.DeviceEqProfile
import com.example.model.EqPreset
import com.example.model.GlassStyle
import com.example.model.PlayerBackdropStyle
import com.example.model.SonoraSettings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.IOException

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "sonora_settings")

class SonoraPreferences(private val context: Context) {

    private object Keys {
        val AUDIO_QUALITY = stringPreferencesKey("audio_quality")
        val AUTOPLAY_RADIO = booleanPreferencesKey("autoplay_radio")
        val SKIP_SILENCE = booleanPreferencesKey("skip_silence")
        val PLAYBACK_SPEED = floatPreferencesKey("playback_speed")
        val PLAYBACK_PITCH = floatPreferencesKey("playback_pitch")
        val AUDIO_ONLY_PREFERENCE = booleanPreferencesKey("audio_only_preference")
        val DOLBY_ATMOS_ENABLED = booleanPreferencesKey("dolby_atmos_enabled")
        val SPATIAL_AUDIO_ENABLED = booleanPreferencesKey("spatial_audio_enabled")
        val AUTOMIX_ENABLED = booleanPreferencesKey("automix_enabled")
        val AUTOMIX_OVERLAP_SECONDS = intPreferencesKey("automix_overlap_seconds")
        val CROSSFADE_SECONDS = intPreferencesKey("crossfade_seconds")
        val SMOOTH_SKIP_FADE = booleanPreferencesKey("smooth_skip_fade")
        val ACCENT_PRESET = stringPreferencesKey("accent_preset")
        val CUSTOM_ACCENT_HEX = longPreferencesKey("custom_accent_hex")
        val APP_ICON_VARIANT = stringPreferencesKey("app_icon_variant")
        val ANIMATED_ARTWORK = booleanPreferencesKey("animated_artwork")
        val ANIMATED_ARTWORK_WIFI_ONLY = booleanPreferencesKey("animated_artwork_wifi_only")
        val PLAYER_BACKDROP_STYLE = stringPreferencesKey("player_backdrop_style")
        val EQ_ENABLED = booleanPreferencesKey("eq_enabled")
        val EQ_PRESET = stringPreferencesKey("eq_preset")
        val BASS_BOOST = intPreferencesKey("bass_boost_strength")
        val DEVICE_EQ_PROFILES_JSON = stringPreferencesKey("device_eq_profiles_json")
        val NORMALIZE_VOLUME = booleanPreferencesKey("normalize_volume")
        val WIFI_ONLY_DOWNLOADS = booleanPreferencesKey("wifi_only_downloads")
        val LYRICS_OFFSET_MS = longPreferencesKey("lyrics_offset_ms")
        val GLASS_STYLE = stringPreferencesKey("glass_style")

        val LISTENBRAINZ_ENABLED = booleanPreferencesKey("listenbrainz_enabled")
        val LISTENBRAINZ_TOKEN = stringPreferencesKey("listenbrainz_token")
        val LISTENBRAINZ_USERNAME = stringPreferencesKey("listenbrainz_username")

        val LASTFM_ENABLED = booleanPreferencesKey("lastfm_enabled")
        val LASTFM_API_KEY = stringPreferencesKey("lastfm_api_key")
        val LASTFM_API_SECRET = stringPreferencesKey("lastfm_api_secret")
        val LASTFM_SESSION_KEY = stringPreferencesKey("lastfm_session_key")
        val LASTFM_USERNAME = stringPreferencesKey("lastfm_username")

        val DISCORD_RPC_ENABLED = booleanPreferencesKey("discord_rpc_enabled")
        val DISCORD_TOKEN = stringPreferencesKey("discord_token")
        val DISCORD_USERNAME = stringPreferencesKey("discord_username")

        val LOCAL_FOLDER_URIS = stringSetPreferencesKey("local_folder_uris")
        val DJ_VOICE_NAME = stringPreferencesKey("dj_voice_name")
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
                playbackSpeed = (prefs[Keys.PLAYBACK_SPEED] ?: 1.0f).coerceIn(0.5f, 2.0f),
                playbackPitch = (prefs[Keys.PLAYBACK_PITCH] ?: 1.0f).coerceIn(0.5f, 2.0f),
                audioOnlyPreference = prefs[Keys.AUDIO_ONLY_PREFERENCE] ?: false,
                dolbyAtmosEnabled = prefs[Keys.DOLBY_ATMOS_ENABLED] ?: false,
                spatialAudioEnabled = prefs[Keys.SPATIAL_AUDIO_ENABLED] ?: false,
                autoMixEnabled = prefs[Keys.AUTOMIX_ENABLED] ?: false,
                autoMixOverlapSeconds = (prefs[Keys.AUTOMIX_OVERLAP_SECONDS] ?: 0).let {
                    if (it <= 0) 0 else it.coerceIn(2, 12)
                },
                crossfadeSeconds = (prefs[Keys.CROSSFADE_SECONDS] ?: 0).let {
                    when {
                        it < 0 -> -1
                        it == 0 -> 0
                        else -> it.coerceIn(1, 12)
                    }
                },
                smoothSkipFadeEnabled = prefs[Keys.SMOOTH_SKIP_FADE] ?: true,
                accentPreset = prefs[Keys.ACCENT_PRESET]
                    ?.let { name -> runCatching { AccentPreset.valueOf(name) }.getOrNull() }
                    ?: AccentPreset.CRIMSON,
                customAccentHex = customHex,
                appIconVariant = prefs[Keys.APP_ICON_VARIANT]
                    ?.let { name -> runCatching { AppIconVariant.valueOf(name) }.getOrNull() }
                    ?: AppIconVariant.EMERALD,
                animatedArtworkEnabled = prefs[Keys.ANIMATED_ARTWORK] ?: true,
                animatedArtworkWifiOnly = prefs[Keys.ANIMATED_ARTWORK_WIFI_ONLY] ?: false,
                playerBackdropStyle = prefs[Keys.PLAYER_BACKDROP_STYLE]
                    ?.let { name -> runCatching { PlayerBackdropStyle.valueOf(name) }.getOrNull() }
                    ?: PlayerBackdropStyle.DEFAULT,
                eqEnabled = prefs[Keys.EQ_ENABLED] ?: false,
                eqPreset = prefs[Keys.EQ_PRESET]
                    ?.let { name -> runCatching { EqPreset.valueOf(name) }.getOrNull() }
                    ?: EqPreset.FLAT,
                bassBoostStrength = (prefs[Keys.BASS_BOOST] ?: 0).coerceIn(0, 1000),
                deviceEqProfilesJson = prefs[Keys.DEVICE_EQ_PROFILES_JSON].orEmpty(),
                normalizeVolume = prefs[Keys.NORMALIZE_VOLUME] ?: false,
                wifiOnlyDownloads = prefs[Keys.WIFI_ONLY_DOWNLOADS] ?: false,
                lyricsOffsetMs = (prefs[Keys.LYRICS_OFFSET_MS] ?: 0L).coerceIn(-10_000L, 10_000L),
                glassStyle = prefs[Keys.GLASS_STYLE]
                    ?.let { name -> runCatching { GlassStyle.valueOf(name) }.getOrNull() }
                    ?: GlassStyle.LIQUID,
                listenBrainzEnabled = prefs[Keys.LISTENBRAINZ_ENABLED] ?: false,
                listenBrainzToken = prefs[Keys.LISTENBRAINZ_TOKEN].orEmpty(),
                listenBrainzUsername = prefs[Keys.LISTENBRAINZ_USERNAME].orEmpty(),
                lastFmEnabled = prefs[Keys.LASTFM_ENABLED] ?: false,
                lastFmApiKey = prefs[Keys.LASTFM_API_KEY].orEmpty(),
                lastFmApiSecret = prefs[Keys.LASTFM_API_SECRET].orEmpty(),
                lastFmSessionKey = prefs[Keys.LASTFM_SESSION_KEY].orEmpty(),
                lastFmUsername = prefs[Keys.LASTFM_USERNAME].orEmpty(),
                discordRpcEnabled = prefs[Keys.DISCORD_RPC_ENABLED] ?: false,
                discordToken = prefs[Keys.DISCORD_TOKEN].orEmpty(),
                discordUsername = prefs[Keys.DISCORD_USERNAME].orEmpty(),
                localFolderUris = prefs[Keys.LOCAL_FOLDER_URIS].orEmpty(),
                djVoiceName = com.example.model.DjVoiceCatalog.normalizeOrDefault(prefs[Keys.DJ_VOICE_NAME])
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

    suspend fun setPlaybackSpeed(speed: Float) {
        context.dataStore.edit { it[Keys.PLAYBACK_SPEED] = speed.coerceIn(0.5f, 2.0f) }
    }

    suspend fun setPlaybackPitch(pitch: Float) {
        context.dataStore.edit { it[Keys.PLAYBACK_PITCH] = pitch.coerceIn(0.5f, 2.0f) }
    }

    suspend fun setAudioOnlyPreference(enabled: Boolean) {
        context.dataStore.edit { it[Keys.AUDIO_ONLY_PREFERENCE] = enabled }
    }

    suspend fun setDolbyAtmosEnabled(enabled: Boolean) {
        context.dataStore.edit { it[Keys.DOLBY_ATMOS_ENABLED] = enabled }
    }

    suspend fun setSpatialAudioEnabled(enabled: Boolean) {
        context.dataStore.edit { it[Keys.SPATIAL_AUDIO_ENABLED] = enabled }
    }

    suspend fun setAutoMixEnabled(enabled: Boolean) {
        context.dataStore.edit { it[Keys.AUTOMIX_ENABLED] = enabled }
    }

    suspend fun setAutoMixOverlapSeconds(seconds: Int) {
        val normalized = if (seconds <= 0) 0 else seconds.coerceIn(2, 12)
        context.dataStore.edit { it[Keys.AUTOMIX_OVERLAP_SECONDS] = normalized }
    }

    suspend fun setCrossfadeSeconds(seconds: Int) {
        val normalized = when {
            seconds < 0 -> -1
            seconds == 0 -> 0
            else -> seconds.coerceIn(1, 12)
        }
        context.dataStore.edit { it[Keys.CROSSFADE_SECONDS] = normalized }
    }

    suspend fun setSmoothSkipFadeEnabled(enabled: Boolean) {
        context.dataStore.edit { it[Keys.SMOOTH_SKIP_FADE] = enabled }
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

    suspend fun setAnimatedArtworkWifiOnly(enabled: Boolean) {
        context.dataStore.edit { it[Keys.ANIMATED_ARTWORK_WIFI_ONLY] = enabled }
    }

    suspend fun setPlayerBackdropStyle(style: PlayerBackdropStyle) {
        context.dataStore.edit { it[Keys.PLAYER_BACKDROP_STYLE] = style.name }
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

    suspend fun saveDeviceEqProfile(route: AudioOutputRoute, profile: DeviceEqProfile) {
        context.dataStore.edit { prefs ->
            val currentMap = parseDeviceEqProfiles(prefs[Keys.DEVICE_EQ_PROFILES_JSON].orEmpty()).toMutableMap()
            currentMap[route.name] = profile
            prefs[Keys.DEVICE_EQ_PROFILES_JSON] = eqJson.encodeToString(currentMap)
        }
    }

    companion object {
        private val eqJson = Json { ignoreUnknownKeys = true; encodeDefaults = true }

        fun parseDeviceEqProfiles(raw: String): Map<String, DeviceEqProfile> {
            if (raw.isBlank()) return emptyMap()
            return runCatching {
                eqJson.decodeFromString<Map<String, DeviceEqProfile>>(raw)
            }.getOrDefault(emptyMap())
        }
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

    suspend fun saveListenBrainzCredentials(token: String, username: String, enabled: Boolean = true) {
        context.dataStore.edit {
            it[Keys.LISTENBRAINZ_TOKEN] = token.trim()
            it[Keys.LISTENBRAINZ_USERNAME] = username.trim()
            it[Keys.LISTENBRAINZ_ENABLED] = enabled && token.isNotBlank()
        }
    }

    suspend fun setListenBrainzEnabled(enabled: Boolean) {
        context.dataStore.edit { it[Keys.LISTENBRAINZ_ENABLED] = enabled }
    }

    suspend fun clearListenBrainz() {
        context.dataStore.edit {
            it.remove(Keys.LISTENBRAINZ_TOKEN)
            it.remove(Keys.LISTENBRAINZ_USERNAME)
            it[Keys.LISTENBRAINZ_ENABLED] = false
        }
    }

    suspend fun saveLastFmCredentials(
        apiKey: String,
        apiSecret: String,
        sessionKey: String,
        username: String,
        enabled: Boolean = true
    ) {
        context.dataStore.edit {
            it[Keys.LASTFM_API_KEY] = apiKey.trim()
            it[Keys.LASTFM_API_SECRET] = apiSecret.trim()
            it[Keys.LASTFM_SESSION_KEY] = sessionKey.trim()
            it[Keys.LASTFM_USERNAME] = username.trim()
            it[Keys.LASTFM_ENABLED] = enabled && sessionKey.isNotBlank()
        }
    }

    suspend fun setLastFmEnabled(enabled: Boolean) {
        context.dataStore.edit { it[Keys.LASTFM_ENABLED] = enabled }
    }

    suspend fun clearLastFm() {
        context.dataStore.edit {
            it.remove(Keys.LASTFM_API_KEY)
            it.remove(Keys.LASTFM_API_SECRET)
            it.remove(Keys.LASTFM_SESSION_KEY)
            it.remove(Keys.LASTFM_USERNAME)
            it[Keys.LASTFM_ENABLED] = false
        }
    }

    suspend fun saveDiscordCredentials(token: String, username: String, enabled: Boolean = true) {
        context.dataStore.edit {
            it[Keys.DISCORD_TOKEN] = token.trim()
            it[Keys.DISCORD_USERNAME] = username.trim()
            it[Keys.DISCORD_RPC_ENABLED] = enabled && token.isNotBlank()
        }
    }

    suspend fun setDiscordRpcEnabled(enabled: Boolean) {
        context.dataStore.edit { it[Keys.DISCORD_RPC_ENABLED] = enabled }
    }

    suspend fun clearDiscord() {
        context.dataStore.edit {
            it.remove(Keys.DISCORD_TOKEN)
            it.remove(Keys.DISCORD_USERNAME)
            it[Keys.DISCORD_RPC_ENABLED] = false
        }
    }

    suspend fun addLocalFolderUri(treeUri: String) {
        context.dataStore.edit { prefs ->
            val current = prefs[Keys.LOCAL_FOLDER_URIS].orEmpty()
            prefs[Keys.LOCAL_FOLDER_URIS] = current + treeUri
        }
    }

    suspend fun removeLocalFolderUri(treeUri: String) {
        context.dataStore.edit { prefs ->
            val current = prefs[Keys.LOCAL_FOLDER_URIS].orEmpty()
            prefs[Keys.LOCAL_FOLDER_URIS] = current - treeUri
        }
    }

    suspend fun setDjVoiceName(voiceName: String) {
        val normalized = com.example.model.DjVoiceCatalog.normalizeOrDefault(voiceName)
        context.dataStore.edit { prefs ->
            prefs[Keys.DJ_VOICE_NAME] = normalized
        }
    }
}
