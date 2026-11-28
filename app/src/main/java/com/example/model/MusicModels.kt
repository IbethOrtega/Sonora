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
    val durationMs: Long = 0L,
    val localUri: String? = null,
    val localLrcUri: String? = null,
    val isLocal: Boolean = false,
    val isExplicit: Boolean = false
)

enum class PlayerBackdropStyle(val label: String, val description: String) {
    DEFAULT("Dinámico (Malla)", "Degradado de malla animado con reflejo invertido"),
    LEGACY("Clásico", "Orbes de color suaves flotando"),
    SIMPLE("Simple", "Portada difuminada sutil")
}

@Serializable
data class PlaybackOrigin(
    val label: String,
    val browseId: String? = null,
    val playlistId: Long? = null,
    val title: String = "",
    val subtitle: String = "",
    val artworkUrl: String = "",
    val params: String? = null,
    val tabRoute: String? = null
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

data class HomeFilterChip(
    val title: String,
    val browseId: String = "FEmusic_home",
    val params: String? = null,
    val isSelected: Boolean = false,
    val fallbackSearchQuery: String? = null
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
    val codecLabel: String,
    val sourceVideoId: String? = null,
    val headers: Map<String, String> = emptyMap(),
    val profileId: String? = null
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
    AUTO("Automática", "Ajusta la calidad automáticamente según la estabilidad de tu red", 192_000),
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
    EMERALD("Verde Esmeralda", 0xFF1DB954, 0xFF0E3D22),
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
    EMERALD("Esmeralda Sonora", "com.example.LauncherEmerald", 0xFF081C18, 0xFF1CE8B5, 0xFFB8FFF0),
    CRIMSON("Carmesí", "com.example.LauncherCrimson", 0xFF1E070C, 0xFFFA2D48, 0xFFFFA6B4),
    VIOLET("Amatista", "com.example.LauncherViolet", 0xFF150821, 0xFFBF5AF2, 0xFFEAC4FF),
    COBALT("Zafiro", "com.example.LauncherCobalt", 0xFF061124, 0xFF0A84FF, 0xFF9AD0FF),
    AMBER("Ámbar", "com.example.LauncherAmber", 0xFF1C1104, 0xFFFF9F0A, 0xFFFFE1A3),
    ROSE("Rosa Neón", "com.example.LauncherRose", 0xFF1E0611, 0xFFFF375F, 0xFFFFACC0),
    CYAN("Cian", "com.example.LauncherCyan", 0xFF051820, 0xFF32D7E8, 0xFFB2F6FD),
    OBSIDIAN("Obsidiana", "com.example.LauncherObsidian", 0xFF101319, 0xFF7E8B9E, 0xFFF1F5FA)
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

data class DjVoiceOption(
    val id: String,
    val displayName: String,
    val toneDescription: String
)

object DjVoiceCatalog {
    val voices: List<DjVoiceOption> = listOf(
        DjVoiceOption("Aoede", "Aoede", "Cálida • Radio femenina"),
        DjVoiceOption("Leda", "Leda", "Suave • Nocturna"),
        DjVoiceOption("Zephyr", "Zephyr", "Fresca • Juvenil"),
        DjVoiceOption("Kore", "Kore", "Firme • Clara"),
        DjVoiceOption("Puck", "Puck", "Enérgica • Dinámica"),
        DjVoiceOption("Charon", "Charon", "Grave • Profunda"),
        DjVoiceOption("Fenrir", "Fenrir", "Intensa • Directa"),
        DjVoiceOption("Orus", "Orus", "Pausada • Masculina")
    )

    fun normalizeOrDefault(voiceName: String?): String {
        val cleaned = voiceName?.trim().orEmpty()
        return voices.firstOrNull { it.id.equals(cleaned, ignoreCase = true) }?.id ?: "Aoede"
    }
}

enum class GlassStyle(
    val label: String,
    val description: String
) {
    LIQUID("Cristal líquido", "Superficies translúcidas con brillos y ambiente dinámico"),
    OBSIDIAN("Obsidiana OLED", "Negro profundo de máximo contraste")
}

enum class TransitionCurveType(
    val label: String,
    val subtitle: String
) {
    SMOOTH_FADE("Fundido suave", "Curva en S continua estilo estudio"),
    BEAT_CUT("Entrada al pulso", "Cruce rápido sin perder el ritmo"),
    EQ_BASS_SWAP("Filtro DJ (Bass Swap)", "Cambia cuerpo y agudos en el cruce")
}

enum class QueueMoodFilter(
    val label: String,
    val description: String
) {
    BALANCED("Equilibrada", "Mezcla natural de afines y favoritos"),
    MORE_ENERGY("Más energía", "Prioriza temas dinámicos y rítmicos"),
    CHILL("Más chill", "Atmósferas suaves y melódicas"),
    SAME_ARTIST("Mismo artista", "Profundiza en el catálogo del artista"),
    DISCOVER("Descubrir", "Explora voces cercanas que aún no sonaron")
}

enum class AudioOutputRoute(val label: String) {
    SPEAKER("Altavoz"),
    BLUETOOTH("Bluetooth"),
    WIRED("Cable / DAC")
}

@Serializable
data class DeviceEqProfile(
    val eqEnabled: Boolean = false,
    val presetName: String = "FLAT",
    val bassBoostStrength: Int = 0
)

@Serializable
data class PlaylistTransitionSpec(
    val playlistId: Long = 0L,
    val fromVideoId: String,
    val toVideoId: String,
    val fromExitMs: Long = 0L, // 0L = auto near end of track
    val toEntryMs: Long = 0L,  // 0L = start at 0s, >0 = start incoming fragment cleanly here
    val crossfadeMs: Int = 5000,
    val curveType: TransitionCurveType = TransitionCurveType.SMOOTH_FADE
)

@Serializable
data class CustomMixFragment(
    val track: Track,
    val startMs: Long = 0L,
    val endMs: Long = 40_000L,
    val crossfadeMs: Int = 4_000,
    val curveType: TransitionCurveType = TransitionCurveType.SMOOTH_FADE
)

/**
 * Computes a deterministic negative Long key (< -10_000L) for any non-local playlist/album
 * browseId so transition specs persist in Room `playlist_transitions` without colliding with
 * `0L` (ad-hoc mixes) or auto-incremented positive `PlaylistEntity.id` rows.
 */
fun remotePlaylistTransitionKey(browseId: String): Long {
    var hash = 0x811C9DC5L
    for (ch in browseId) {
        hash = (hash xor ch.code.toLong()) * 0x01000193L
        hash = hash and 0x7FFFFFFFL
    }
    return -(hash + 10_000L)
}

fun resolveTrackDurationMs(track: Track, measuredDurationMs: Long = 0L): Long {
    if (measuredDurationMs > 15_000L) return measuredDurationMs
    if (track.durationMs > 15_000L) return track.durationMs
    val parts = track.durationText.trim().split(":").mapNotNull { it.toLongOrNull() }
    val parsed = when (parts.size) {
        2 -> (parts[0] * 60L + parts[1]) * 1000L
        3 -> (parts[0] * 3600L + parts[1] * 60L + parts[2]) * 1000L
        else -> 0L
    }
    return if (parsed > 15_000L) parsed else 210_000L
}

data class SonoraSettings(
    val audioQuality: AudioQuality = AudioQuality.MAX,
    val wifiAudioQuality: AudioQuality = AudioQuality.MAX,
    val mobileAudioQuality: AudioQuality = AudioQuality.HIGH,
    val autoplayRadio: Boolean = true,
    val skipSilence: Boolean = false,
    val playbackSpeed: Float = 1.0f,
    val playbackPitch: Float = 1.0f,
    val audioOnlyPreference: Boolean = false,
    val dolbyAtmosEnabled: Boolean = false,
    val spatialAudioEnabled: Boolean = false,
    val autoMixEnabled: Boolean = false,
    val autoMixOverlapSeconds: Int = 0, // 0 = Automático por canción, 2..12s = Manual
    val crossfadeSeconds: Int = 0, // 0 = Desactivado, 1..12 = Segundos exactos, -1 = Automático
    val smoothSkipFadeEnabled: Boolean = true, // Micro-fundido elegante en play/pausa/salto manual
    val accentPreset: AccentPreset = AccentPreset.CRIMSON,
    val customAccentHex: Long? = null,
    val appIconVariant: AppIconVariant = AppIconVariant.EMERALD,
    val animatedArtworkEnabled: Boolean = true,
    val animatedArtworkMobileData: Boolean = false,
    val autoHidePlayerControls: Boolean = true,
    val spotifyCanvasEnabled: Boolean = false,
    val playerBackdropStyle: PlayerBackdropStyle = PlayerBackdropStyle.DEFAULT,
    val eqEnabled: Boolean = false,
    val eqPreset: EqPreset = EqPreset.FLAT,
    val bassBoostStrength: Int = 0, // 0..1000
    val deviceEqProfilesJson: String = "",
    val normalizeVolume: Boolean = false,
    val wifiOnlyDownloads: Boolean = false,
    val lyricsOffsetMs: Long = 0L,
    val trackLyricsOffsetsJson: String = "",
    val glassStyle: GlassStyle = GlassStyle.LIQUID,
    val liquidGlassEnabled: Boolean = true,
    val listenBrainzEnabled: Boolean = false,
    val listenBrainzToken: String = "",
    val listenBrainzUsername: String = "",
    val lastFmEnabled: Boolean = false,
    val lastFmApiKey: String = "",
    val lastFmApiSecret: String = "",
    val lastFmSessionKey: String = "",
    val lastFmUsername: String = "",
    val discordRpcEnabled: Boolean = false,
    val discordToken: String = "",
    val discordUsername: String = "",
    val localFolderUris: Set<String> = emptySet(),
    val djVoiceName: String = "Aoede",
    val djDeviceVoiceName: String = "",
    val djDeviceSpeechRate: Float = 1.0f,
    val djDevicePitch: Float = 1.03f,
    val autoCheckUpdatesOnLaunch: Boolean = true,
    val wifiOnlyUpdateDownloads: Boolean = true,
    val lastUpdateCheckMs: Long = 0L,
    val skippedUpdateTag: String = ""
) {
    fun lyricsOffsetFor(videoId: String?): Long {
        if (!videoId.isNullOrBlank() && trackLyricsOffsetsJson.isNotBlank()) {
            val map = com.example.data.local.SonoraPreferences.parseTrackLyricsOffsets(trackLyricsOffsetsJson)
            map[videoId]?.let { return it.coerceIn(-2_000L, 2_000L) }
        }
        return lyricsOffsetMs.coerceIn(-2_000L, 2_000L)
    }
}

data class LyricWord(
    val startMs: Long,
    val endMs: Long,
    val text: String
)

data class LyricLine(
    val timeMs: Long,
    val text: String,
    val words: List<LyricWord> = emptyList()
)

data class TrackLyrics(
    val lines: List<LyricLine>,
    val isSynced: Boolean,
    val sourceLabel: String = ""
)

data class StatsForNerdsData(
    val videoId: String = "",
    val sourceLabel: String = "Streaming",
    val codec: String = "--",
    val mimeType: String = "--",
    val bitrateKbps: Int = 0,
    val sampleRateHz: Int = 0,
    val channelCount: Int = 0,
    val bitDepthLabel: String = "--",
    val bufferedMs: Long = 0L,
    val bufferPercent: Int = 0,
    val audioSessionId: Int = 0,
    val playbackSpeed: Float = 1.0f,
    val playbackPitch: Float = 1.0f,
    val streamHost: String = "--"
)

enum class StatsPeriod(val label: String) {
    WEEK("Semana"),
    MONTH("Este mes"),
    YEAR("Este año"),
    ALL("Siempre")
}

data class ArtistPlayStat(
    val artist: String,
    val totalPlays: Int,
    val estimatedMinutes: Long
)

data class TrackPlayStat(
    val track: Track,
    val playCount: Int
)

data class AlbumPlayStat(
    val album: String,
    val albumId: String? = null,
    val artworkUrl: String = "",
    val totalPlays: Int,
    val estimatedMinutes: Long
)

data class MonthlyListeningStat(
    val yearMonthKey: String,
    val label: String,
    val hours: Float,
    val minutes: Long,
    val playCount: Int
)

data class ListeningHistoryEntry(
    val id: Long,
    val track: Track,
    val playedAtMs: Long,
    val listenedMs: Long
)

data class HistoryDayGroup(
    val dayKey: String,
    val dayLabel: String,
    val entries: List<ListeningHistoryEntry>
)

data class YouTubeHistorySection(
    val title: String,
    val tracks: List<Track>
)

data class YouTubeHistoryPage(
    val sections: List<YouTubeHistorySection>,
    val flatTracks: List<Track>,
    val nextContinuationToken: String? = null
)

data class ListeningStatsSummary(
    val totalPlays: Int = 0,
    val uniqueTracks: Int = 0,
    val estimatedMinutes: Long = 0L,
    val topTracks: List<TrackPlayStat> = emptyList(),
    val topArtists: List<ArtistPlayStat> = emptyList(),
    val totalListenedMs: Long = 0L,
    val topAlbums: List<AlbumPlayStat> = emptyList(),
    val peakHour: Int? = null,
    val peakDayOfWeek: Int? = null,
    val currentStreakDays: Int = 0,
    val longestStreakDays: Int = 0,
    val monthlyHours: List<MonthlyListeningStat> = emptyList(),
    val period: StatsPeriod = StatsPeriod.ALL
)

@Serializable
data class PersistedQueueState(
    val queue: List<Track> = emptyList(),
    val currentIndex: Int = 0,
    val positionMs: Long = 0L,
    val savedAtMs: Long = 0L,
    val wasPlaying: Boolean = false,
    val sourcePlaylistId: Long? = null,
    val origin: PlaybackOrigin? = null
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
    val notes: String = "",
    val apkSha256: String? = null,
    val apkSizeBytes: Long = 0L
)

@Serializable
data class YouTubeAuthSessionPayload(
    val cookies: String,
    val visitorData: String = "",
    val dataSyncId: String = "",
    val delegatedSessionId: String = "",
    val authUser: String = "0",
    val clientVersion: String = ""
)

data class ArtistCredit(
    val name: String,
    val browseId: String? = null
)

data class ResolvedTrackMetadata(
    val videoId: String,
    val artistId: String? = null,
    val album: String? = null,
    val albumId: String? = null,
    val artists: List<ArtistCredit> = emptyList()
)

private val ARTIST_META_LABELS = setOf(
    "song", "canción", "cancion", "video", "album", "álbum",
    "playlist", "single", "sencillo", "ep", "live", "en vivo"
)
private val ARTIST_GENERIC_LABELS = setOf(
    "youtube music", "artista desconocido", "various artists", "topic"
)
private val ARTIST_DURATION_REGEX = Regex("^\\d{1,2}:\\d{2}(:\\d{2})?$")
private val ARTIST_SPLIT_REGEX = Regex(
    "\\s*(?:,|&|\\bfeat\\.|\\bfeat\\b|\\bft\\.|\\bft\\b)\\s*",
    RegexOption.IGNORE_CASE
)

fun splitArtistNames(rawArtist: String): List<String> {
    if (rawArtist.isBlank()) return emptyList()
    val primarySegment = rawArtist
        .split("•", "·")
        .map { it.trim() }
        .firstOrNull { seg ->
            val low = seg.lowercase()
            seg.isNotBlank() &&
                low !in ARTIST_META_LABELS &&
                !low.contains("views") &&
                !low.contains("vistas") &&
                !low.contains("plays") &&
                !low.contains("reproducciones") &&
                !ARTIST_DURATION_REGEX.matches(seg)
        } ?: rawArtist

    val cleaned = primarySegment
        .replace(Regex("\\s*-\\s*Topic$", RegexOption.IGNORE_CASE), "")
        .trim()
    if (cleaned.isBlank()) return emptyList()

    val seenLower = HashSet<String>()
    val out = mutableListOf<String>()
    for (part in cleaned.split(ARTIST_SPLIT_REGEX)) {
        val candidate = part
            .replace(Regex("[()\\[\\]]"), "")
            .trim()
        val low = candidate.lowercase()
        if (candidate.isNotBlank() && low !in ARTIST_GENERIC_LABELS && seenLower.add(low)) {
            out.add(candidate)
        }
    }
    return out
}


