package com.example.data.network

import android.util.Log
import com.example.model.BrowseCard
import com.example.model.ItemKind
import com.example.model.SearchFilter
import com.example.model.SearchResults
import com.example.model.Track
import com.example.ui.components.upgradeArtworkUrl
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import org.schabi.newpipe.extractor.Image
import org.schabi.newpipe.extractor.InfoItem
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.channel.ChannelInfoItem
import org.schabi.newpipe.extractor.playlist.PlaylistInfoItem
import org.schabi.newpipe.extractor.services.youtube.linkHandler.YoutubeSearchQueryHandlerFactory
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import java.net.URI
import java.util.Locale

class NewPipeSearchClient(
    private val httpClient: OkHttpClient
) {
    suspend fun getSearchSuggestions(query: String): List<String> = withContext(Dispatchers.IO) {
        if (query.isBlank()) return@withContext emptyList()
        NewPipeBootstrapper.ensureInitialized(httpClient)
        runCatching {
            ServiceList.YouTube
                .suggestionExtractor
                .suggestionList(query.trim())
                .mapNotNull { it?.trim()?.takeIf(String::isNotBlank) }
                .distinct()
                .take(8)
        }.onFailure { err ->
            Log.w("NewPipeSearchClient", "Suggestions failed: ${err.message}")
        }.getOrDefault(emptyList())
    }

    suspend fun search(query: String, filter: SearchFilter = SearchFilter.ALL): SearchResults =
        withContext(Dispatchers.IO) {
            val trimmed = query.trim()
            if (trimmed.isBlank()) {
                return@withContext SearchResults(sourceLabel = "NewPipe • YouTube Music")
            }
            NewPipeBootstrapper.ensureInitialized(httpClient)

            when (filter) {
                SearchFilter.SONGS -> {
                    val page = executeSingleFilterSearch(trimmed, YoutubeSearchQueryHandlerFactory.MUSIC_SONGS)
                    SearchResults(
                        songs = page.songs.distinctBy { it.videoId },
                        searchSuggestion = page.suggestion,
                        sourceLabel = "NewPipe • YouTube Music"
                    )
                }
                SearchFilter.ALBUMS -> {
                    val page = executeSingleFilterSearch(trimmed, YoutubeSearchQueryHandlerFactory.MUSIC_ALBUMS)
                    SearchResults(
                        albums = page.albums.distinctBy { it.id },
                        searchSuggestion = page.suggestion,
                        sourceLabel = "NewPipe • YouTube Music"
                    )
                }
                SearchFilter.ARTISTS -> {
                    val page = executeSingleFilterSearch(trimmed, YoutubeSearchQueryHandlerFactory.MUSIC_ARTISTS)
                    SearchResults(
                        artists = page.artists.distinctBy { it.id },
                        searchSuggestion = page.suggestion,
                        sourceLabel = "NewPipe • YouTube Music"
                    )
                }
                SearchFilter.PLAYLISTS -> {
                    val page = executeSingleFilterSearch(trimmed, YoutubeSearchQueryHandlerFactory.MUSIC_PLAYLISTS)
                    SearchResults(
                        playlists = page.playlists.distinctBy { it.id },
                        searchSuggestion = page.suggestion,
                        sourceLabel = "NewPipe • YouTube Music"
                    )
                }
                SearchFilter.ALL -> coroutineScope {
                    val songsDeferred = async {
                        runCatching {
                            executeSingleFilterSearch(trimmed, YoutubeSearchQueryHandlerFactory.MUSIC_SONGS)
                        }.getOrNull()
                    }
                    val albumsDeferred = async {
                        runCatching {
                            executeSingleFilterSearch(trimmed, YoutubeSearchQueryHandlerFactory.MUSIC_ALBUMS)
                        }.getOrNull()
                    }
                    val artistsDeferred = async {
                        runCatching {
                            executeSingleFilterSearch(trimmed, YoutubeSearchQueryHandlerFactory.MUSIC_ARTISTS)
                        }.getOrNull()
                    }
                    val playlistsDeferred = async {
                        runCatching {
                            executeSingleFilterSearch(trimmed, YoutubeSearchQueryHandlerFactory.MUSIC_PLAYLISTS)
                        }.getOrNull()
                    }

                    val songsRes = songsDeferred.await()
                    val albumsRes = albumsDeferred.await()
                    val artistsRes = artistsDeferred.await()
                    val playlistsRes = playlistsDeferred.await()

                    if (songsRes == null && albumsRes == null && artistsRes == null && playlistsRes == null) {
                        throw IllegalStateException("No se pudo consultar YouTube Music con NewPipe Extractor.")
                    }

                    SearchResults(
                        songs = songsRes?.songs.orEmpty().distinctBy { it.videoId },
                        albums = albumsRes?.albums.orEmpty().distinctBy { it.id },
                        artists = artistsRes?.artists.orEmpty().distinctBy { it.id },
                        playlists = playlistsRes?.playlists.orEmpty().distinctBy { it.id },
                        searchSuggestion = songsRes?.suggestion
                            ?: albumsRes?.suggestion
                            ?: artistsRes?.suggestion,
                        sourceLabel = "NewPipe • YouTube Music"
                    )
                }
            }
        }

    private data class FilterSearchResult(
        val songs: List<Track> = emptyList(),
        val albums: List<BrowseCard> = emptyList(),
        val artists: List<BrowseCard> = emptyList(),
        val playlists: List<BrowseCard> = emptyList(),
        val suggestion: String? = null
    )

    private fun executeSingleFilterSearch(query: String, contentFilter: String): FilterSearchResult {
        val extractor = ServiceList.YouTube.getSearchExtractor(query, listOf(contentFilter), null)
        extractor.fetchPage()
        val page = extractor.initialPage

        val songs = mutableListOf<Track>()
        val albums = mutableListOf<BrowseCard>()
        val artists = mutableListOf<BrowseCard>()
        val playlists = mutableListOf<BrowseCard>()

        for (item in page.items) {
            when (item) {
                is StreamInfoItem -> mapStreamInfoItem(item)?.let(songs::add)
                is PlaylistInfoItem -> {
                    val kind = if (contentFilter == YoutubeSearchQueryHandlerFactory.MUSIC_ALBUMS) {
                        ItemKind.ALBUM
                    } else {
                        ItemKind.PLAYLIST
                    }
                    mapPlaylistInfoItem(item, kind)?.let { card ->
                        if (kind == ItemKind.ALBUM) albums.add(card) else playlists.add(card)
                    }
                }
                is ChannelInfoItem -> mapChannelInfoItem(item)?.let(artists::add)
            }
        }

        val suggestion = runCatching {
            val raw = extractor.searchSuggestion
            raw.takeIf { it.isNotBlank() && !it.equals(query, ignoreCase = true) }
        }.getOrNull()

        return FilterSearchResult(
            songs = songs,
            albums = albums,
            artists = artists,
            playlists = playlists,
            suggestion = suggestion
        )
    }

    companion object {
        fun mapStreamInfoItem(item: StreamInfoItem): Track? {
            val videoId = extractVideoIdFromUrl(item.url) ?: return null
            val title = item.name?.trim().orEmpty()
            if (title.isBlank()) return null

            val rawUploader = item.uploaderName?.trim().orEmpty()
                .replace(Regex("\\s*-\\s*Topic$", RegexOption.IGNORE_CASE), "")
                .trim()
            val artist = rawUploader.ifBlank { "YouTube Music" }
            val artistId = extractChannelIdFromUrl(item.uploaderUrl)
            val durationSec = item.duration.coerceAtLeast(0L)
            val durationMs = durationSec * 1000L
            val durationText = formatDurationSeconds(durationSec)
            val artwork = selectBestArtworkUrl(item.thumbnails)

            val parsedAlbum = item.shortDescription
                ?.trim()
                ?.takeIf { desc ->
                    desc.isNotBlank() &&
                        !desc.equals(artist, ignoreCase = true) &&
                        !desc.startsWith("http", ignoreCase = true)
                }

            return Track(
                videoId = videoId,
                title = title,
                artist = artist,
                artistId = artistId,
                album = parsedAlbum,
                artworkUrl = artwork,
                durationText = durationText,
                durationMs = durationMs
            )
        }

        fun mapPlaylistInfoItem(item: PlaylistInfoItem, kind: ItemKind): BrowseCard? {
            val browseId = extractPlaylistBrowseId(item.url) ?: return null
            val title = item.name?.trim().orEmpty()
            if (title.isBlank()) return null

            val uploader = item.uploaderName?.trim().orEmpty()
            val countLabel = item.streamCount.takeIf { it > 0 }?.let { "$it canciones" }.orEmpty()
            val subtitle = listOfNotNull(
                if (kind == ItemKind.ALBUM) "Álbum" else "Playlist",
                uploader.takeIf { it.isNotBlank() },
                countLabel.takeIf { it.isNotBlank() }
            ).joinToString(" • ")

            return BrowseCard(
                id = browseId,
                title = title,
                subtitle = subtitle,
                artworkUrl = selectBestArtworkUrl(item.thumbnails),
                kind = kind
            )
        }

        fun mapChannelInfoItem(item: ChannelInfoItem): BrowseCard? {
            val channelId = extractChannelIdFromUrl(item.url) ?: return null
            val name = item.name?.trim().orEmpty()
            if (name.isBlank()) return null

            val subs = item.subscriberCount
            val subtitle = if (subs > 0) {
                "${formatCompactCount(subs)} suscriptores"
            } else {
                "Artista"
            }

            return BrowseCard(
                id = channelId,
                title = name,
                subtitle = subtitle,
                artworkUrl = selectBestArtworkUrl(item.thumbnails),
                kind = ItemKind.ARTIST
            )
        }

        fun extractVideoIdFromUrl(url: String?): String? {
            if (url.isNullOrBlank()) return null
            val trimmed = url.trim()
            if (trimmed.matches(Regex("^[a-zA-Z0-9_-]{11}$"))) return trimmed

            return runCatching {
                val uri = URI(trimmed)
                val host = uri.host.orEmpty().lowercase(Locale.ROOT)
                val path = uri.path.orEmpty()
                if (host.contains("youtu.be")) {
                    return@runCatching path.trim('/').substringBefore('/').takeIf { it.isNotBlank() }
                }
                val query = uri.rawQuery.orEmpty()
                query.split("&")
                    .mapNotNull { param ->
                        val idx = param.indexOf('=')
                        if (idx > 0) param.substring(0, idx) to param.substring(idx + 1) else null
                    }
                    .firstOrNull { it.first == "v" }
                    ?.second
                    ?.takeIf { it.isNotBlank() }
                    ?: when {
                        path.contains("/shorts/") -> path.substringAfter("/shorts/").substringBefore('/')
                        path.contains("/embed/") -> path.substringAfter("/embed/").substringBefore('/')
                        else -> null
                    }?.takeIf { it.isNotBlank() }
            }.getOrNull()
        }

        fun extractPlaylistBrowseId(url: String?): String? {
            if (url.isNullOrBlank()) return null
            val raw = url.trim()
            val listId = runCatching {
                val uri = URI(raw)
                uri.rawQuery.orEmpty()
                    .split("&")
                    .firstOrNull { it.startsWith("list=") }
                    ?.substringAfter("list=")
                    ?.takeIf { it.isNotBlank() }
            }.getOrNull() ?: raw.takeIf { it.startsWith("VL") || it.startsWith("PL") || it.startsWith("OLAK") || it.startsWith("MPREb") }

            return listId?.let { id ->
                if (id.startsWith("VL") || id.startsWith("MPREb")) id else "VL$id"
            }
        }

        fun extractChannelIdFromUrl(url: String?): String? {
            if (url.isNullOrBlank()) return null
            return runCatching {
                val path = URI(url.trim()).path.orEmpty()
                when {
                    path.contains("/channel/") -> path.substringAfter("/channel/").substringBefore('/')
                    else -> null
                }?.takeIf { it.isNotBlank() }
            }.getOrNull()
        }

        fun selectBestArtworkUrl(thumbnails: List<Image>?): String {
            if (thumbnails.isNullOrEmpty()) return ""
            val best = thumbnails
                .filter { it.url.isNotBlank() }
                .maxByOrNull { image ->
                    val area = if (image.width > 0 && image.height > 0) image.width * image.height else 0
                    val levelScore = when (image.estimatedResolutionLevel) {
                        Image.ResolutionLevel.HIGH -> 3_000_000
                        Image.ResolutionLevel.MEDIUM -> 1_000_000
                        Image.ResolutionLevel.LOW -> 100_000
                        else -> 0
                    }
                    area + levelScore
                }?.url.orEmpty()
            return upgradeArtworkUrl(best)
        }

        fun formatDurationSeconds(seconds: Long): String {
            if (seconds <= 0L) return ""
            val mins = seconds / 60L
            val secs = seconds % 60L
            val hours = mins / 60L
            return if (hours > 0L) {
                String.format(Locale.US, "%d:%02d:%02d", hours, mins % 60L, secs)
            } else {
                String.format(Locale.US, "%d:%02d", mins, secs)
            }
        }

        private fun formatCompactCount(count: Long): String = when {
            count >= 1_000_000L -> String.format(Locale.US, "%.1fM", count / 1_000_000.0)
            count >= 1_000L -> String.format(Locale.US, "%.1fK", count / 1_000.0)
            else -> count.toString()
        }
    }
}
