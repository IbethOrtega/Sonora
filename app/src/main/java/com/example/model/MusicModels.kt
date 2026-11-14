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
    val playlists: List<BrowseCard> = emptyList()
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

