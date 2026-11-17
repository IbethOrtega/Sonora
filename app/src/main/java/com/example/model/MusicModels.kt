package com.example.model

import kotlinx.serialization.Serializable

enum class ItemKind {
    SONG,
    ALBUM,
    PLAYLIST,
    ARTIST,
    MOOD
}

@Serializable
data class Track(
    val videoId: String,
    val title: String,
    val artist: String,
    val artistId: String? = null,
    val album: String? = null,
    val albumId: String? = null,
    val artworkUrl: String = "",
    val durationText: String = "",
    val durationMs: Long = 0L
)

data class BrowseCard(
    val id: String,
    val title: String,
    val subtitle: String,
    val artworkUrl: String,
    val kind: ItemKind,
    val params: String? = null,
    val videoId: String? = null
)

data class HomeShelf(
    val title: String,
    val subtitle: String? = null,
    val items: List<BrowseCard> = emptyList(),
    val tracks: List<Track> = emptyList(),
    val isQuickPicks: Boolean = false
)

data class MoodCategory(
    val title: String,
    val browseId: String,
    val params: String?,
    val accentHex: Long
)

data class BrowseDetail(
    val browseId: String,
    val title: String,
    val subtitle: String,
    val description: String = "",
    val artworkUrl: String = "",
    val kind: ItemKind = ItemKind.PLAYLIST,
    val tracks: List<Track> = emptyList(),
    val shelves: List<HomeShelf> = emptyList(),
    val playlistId: String? = null
)

data class SearchResults(
    val songs: List<Track> = emptyList(),
    val albums: List<BrowseCard> = emptyList(),
    val artists: List<BrowseCard> = emptyList(),
    val playlists: List<BrowseCard> = emptyList(),
    val searchSuggestion: String? = null,
    val sourceLabel: String = "NewPipe • YouTube Music"
)

data class ResolvedStream(
    val url: String,
    val mimeType: String,
    val bitrate: Int,
    val userAgent: String,
    val expiresAtMs: Long,
    val codecLabel: String
)

enum class RepeatMode {
    OFF,
    ALL,
    ONE
}

enum class SearchFilter(val label: String, val params: String?) {
    ALL("Todo", null),
    SONGS("Canciones", "EgWKAQIIAWoKEAMQBBAJEAoQBQ%3D%3D"),
    ALBUMS("Álbumes", "EgWKAQIYAWoKEAMQBBAJEAoQBQ%3D%3D"),
    ARTISTS("Artistas", "EgWKAQIgAWoKEAMQBBAJEAoQBQ%3D%3D"),
    PLAYLISTS("Playlists", "EgeKAQQoAEABagoQAxAEEAoQCRAF")
}

data class YouTubeAccountInfo(
    val name: String,
    val handle: String = "",
    val avatarUrl: String = ""
)

data class SpotifyParsedTrack(
    val title: String,
    val artist: String,
    val durationMs: Long = 0L
)

data class SpotifyParsedPlaylist(
    val title: String,
    val subtitle: String = "",
    val coverUrl: String = "",
    val tracks: List<SpotifyParsedTrack>
)

sealed interface SpotifyImportState {
    data object Idle : SpotifyImportState
    data class FetchingSpotify(val message: String = "Leyendo playlist de Spotify...") : SpotifyImportState
    data class MatchingTracks(
        val playlistTitle: String,
        val matchedCount: Int,
        val processedCount: Int,
        val totalCount: Int,
        val currentTrackName: String
    ) : SpotifyImportState
    data class Completed(
        val playlistId: Long,
        val playlistTitle: String,
        val matchedCount: Int,
        val totalCount: Int
    ) : SpotifyImportState
    data class Failed(val message: String) : SpotifyImportState
}

enum class AudioQuality(
    val label: String,
    val description: String,
    val targetBitrateBps: Int
) {
    MAX("Máxima calidad", "Mejor audio OPUS / AAC disponible (160–256 kbps)", 320_000),
    HIGH("Equilibrada", "Buena fidelidad y carga rápida (~128 kbps)", 140_000),
    DATA_SAVER("Ahorro de datos", "Menor consumo de red (~64–96 kbps)", 80_000)
}

enum class AccentPreset(
    val label: String,
    val primaryHex: Long,
    val secondaryHex: Long
) {
    CRIMSON("Carmesí Sonora", 0xFFFA2D48, 0xFF3E1338),
    EMERALD("Verde Spotify", 0xFF1DB954, 0xFF0E3D22),
    VIOLET("Amatista", 0xFFBF5AF2, 0xFF2D1654),
    COBALT("Zafiro", 0xFF0A84FF, 0xFF102A5C),
    AMBER("Ámbar", 0xFFFF9F0A, 0xFF4B280A),
    ROSE("Rosa Neón", 0xFFFF375F, 0xFF481024),
    CYAN("Cian Eléctrico", 0xFF32D7E8, 0xFF0B3744),
    CORAL("Coral Sunset", 0xFFFF6B4A, 0xFF4A1B14),
    GOLD("Oro Clásico", 0xFFFFD60A, 0xFF473A06),
    MINT("Menta Cristal", 0xFF4EF2B2, 0xFF113F30)
}

enum class AppIconVariant(
    val label: String,
    val aliasClassName: String,
    val bgHex: Long,
    val discHex: Long,
    val waveHex: Long
) {
    EMERALD("Verde Spotify", "com.example.LauncherEmerald", 0xFF111318, 0xFF1DB954, 0xFF111318),
    CRIMSON("Carmesí", "com.example.LauncherCrimson", 0xFF140B10, 0xFFFA2D48, 0xFF140B10),
    VIOLET("Amatista", "com.example.LauncherViolet", 0xFF120C1E, 0xFFBF5AF2, 0xFF120C1E),
    COBALT("Zafiro", "com.example.LauncherCobalt", 0xFF0A1324, 0xFF0A84FF, 0xFF0A1324),
    AMBER("Ámbar", "com.example.LauncherAmber", 0xFF181108, 0xFFFF9F0A, 0xFF181108),
    ROSE("Rosa Neón", "com.example.LauncherRose", 0xFF180B12, 0xFFFF375F, 0xFF180B12),
    CYAN("Cian", "com.example.LauncherCyan", 0xFF09151C, 0xFF32D7E8, 0xFF09151C),
    OBSIDIAN("Obsidiana", "com.example.LauncherObsidian", 0xFF050508, 0xFF1D1D28, 0xFFEAEAF2)
}

enum class EqPreset(
    val label: String,
    val bandOffsetsDb: List<Int> // 5 relative dB steps (-6..+6) mapped to device equalizer range
) {
    FLAT("Plano", listOf(0, 0, 0, 0, 0)),
    BASS_BOOST("Bajos profundos", listOf(5, 3, 0, 1, 2)),
    VOCAL("Voces claras", listOf(-2, 0, 4, 3, 1)),
    POP("Pop vibrante", listOf(-1, 2, 4, 2, -1)),
    ROCK("Rock", listOf(4, 2, -1, 3, 4)),
    ELECTRONIC("Electrónica", listOf(5, 3, 0, 2, 4)),
    ACOUSTIC("Acústica", listOf(2, 1, 2, 3, 2)),
    LOUDNESS("Potencia", listOf(4, 2, 0, 2, 4))
}

enum class DjVibe(
    val label: String,
    val subtitle: String
) {
    PERSONAL_MIX("Tu mezcla del día", "Basado en tus favoritas y lo más escuchado"),
    ENERGY("Modo energía", "Ritmos altos para entrenar o animarte"),
    CHILL("Noche y relax", "Temas suaves y atmósferas tranquilas"),
    DISCOVERY("Descubrimiento", "Nuevas joyas cercanas a tu gusto")
}

enum class GlassStyle(
    val label: String,
    val description: String
) {
    LIQUID("Cristal líquido", "Superficies translúcidas con brillos y ambiente dinámico"),
    OBSIDIAN("Obsidiana OLED", "Negro profundo de máximo contraste")
}

data class SonoraSettings(
    val audioQuality: AudioQuality = AudioQuality.MAX,
    val autoplayRadio: Boolean = true,
    val skipSilence: Boolean = false,
    val accentPreset: AccentPreset = AccentPreset.CRIMSON,
    val customAccentHex: Long? = null,
    val appIconVariant: AppIconVariant = AppIconVariant.EMERALD,
    val animatedArtworkEnabled: Boolean = true,
    val eqEnabled: Boolean = false,
    val eqPreset: EqPreset = EqPreset.FLAT,
    val bassBoostStrength: Int = 0, // 0..1000
    val normalizeVolume: Boolean = false,
    val wifiOnlyDownloads: Boolean = false,
    val lyricsOffsetMs: Long = 0L,
    val glassStyle: GlassStyle = GlassStyle.LIQUID
)

data class LyricLine(
    val timeMs: Long,
    val text: String
)

data class TrackLyrics(
    val lines: List<LyricLine>,
    val isSynced: Boolean,
    val sourceLabel: String = ""
)

@Serializable
data class BackupPlaylist(
    val name: String,
    val description: String = "",
    val coverUrl: String = "",
    val tracks: List<Track> = emptyList()
)

@Serializable
data class SonoraBackupPayload(
    val version: Int = 1,
    val exportedAtMs: Long = 0L,
    val likedSongs: List<Track> = emptyList(),
    val playlists: List<BackupPlaylist> = emptyList()
)

data class GitHubReleaseInfo(
    val tagName: String,
    val releaseName: String,
    val htmlUrl: String,
    val apkDownloadUrl: String?,
    val notes: String = ""
)
