package com.example.data.repository

import android.content.Context
import android.net.Uri
import com.example.data.local.LibraryDao
import com.example.data.local.LikedSongEntity
import com.example.data.local.PlaylistEntity
import com.example.data.local.PlaylistTrackEntity
import com.example.data.local.toTrack
import com.example.model.BackupPlaylist
import com.example.model.SonoraBackupPayload
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class BackupManager(
    private val context: Context,
    private val libraryDao: LibraryDao
) {
    suspend fun exportToUri(uri: Uri): Result<Int> = withContext(Dispatchers.IO) {
        runCatching {
            val liked = libraryDao.getAllLikedSongsOnce().map { it.toTrack() }
            val playlists = libraryDao.getAllPlaylistsOnce().map { pl ->
                val tracks = libraryDao.getPlaylistTracksOnce(pl.id).map { it.toTrack() }
                BackupPlaylist(
                    name = pl.name,
                    description = pl.description,
                    coverUrl = pl.coverUrl,
                    tracks = tracks
                )
            }
            val payload = SonoraBackupPayload(
                version = 1,
                exportedAtMs = System.currentTimeMillis(),
                likedSongs = liked,
                playlists = playlists
            )
            val encoded = encodeBackupJson(payload)
            context.contentResolver.openOutputStream(uri)?.use { out ->
                out.write(encoded.toByteArray(Charsets.UTF_8))
                out.flush()
            } ?: throw IllegalStateException("No se pudo abrir el archivo de destino.")

            liked.size + playlists.sumOf { it.tracks.size }
        }
    }

    suspend fun importFromUri(uri: Uri): Result<Pair<Int, Int>> = withContext(Dispatchers.IO) {
        runCatching {
            val raw = context.contentResolver.openInputStream(uri)?.use { input ->
                input.bufferedReader(Charsets.UTF_8).readText()
            } ?: throw IllegalStateException("No se pudo leer el archivo seleccionado.")

            val payload = decodeBackupJson(raw)
            var importedLikes = 0
            payload.likedSongs.forEach { track ->
                if (track.videoId.isNotBlank() && track.title.isNotBlank()) {
                    libraryDao.insertLikedSong(
                        LikedSongEntity(
                            videoId = track.videoId,
                            title = track.title,
                            artist = track.artist,
                            artistId = track.artistId,
                            album = track.album,
                            albumId = track.albumId,
                            artworkUrl = track.artworkUrl,
                            durationText = track.durationText,
                            durationMs = track.durationMs
                        )
                    )
                    importedLikes++
                }
            }

            val existingPlaylistNames = libraryDao.getAllPlaylistsOnce().map { it.name.trim().lowercase() }.toMutableSet()
            var importedPlaylists = 0
            payload.playlists.forEach { pl ->
                if (pl.name.isNotBlank()) {
                    val baseName = pl.name.trim()
                    val finalName = if (existingPlaylistNames.add(baseName.lowercase())) {
                        baseName
                    } else {
                        "$baseName (Restaurada)"
                    }
                    val plId = libraryDao.insertPlaylist(
                        PlaylistEntity(
                            name = finalName,
                            description = pl.description,
                            coverUrl = pl.coverUrl.ifBlank { pl.tracks.firstOrNull()?.artworkUrl.orEmpty() }
                        )
                    )
                    pl.tracks.asReversed().forEach { t ->
                        if (t.videoId.isNotBlank()) {
                            libraryDao.insertPlaylistTrack(
                                PlaylistTrackEntity(
                                    playlistId = plId,
                                    videoId = t.videoId,
                                    title = t.title,
                                    artist = t.artist,
                                    artistId = t.artistId,
                                    album = t.album,
                                    albumId = t.albumId,
                                    artworkUrl = t.artworkUrl,
                                    durationText = t.durationText,
                                    durationMs = t.durationMs
                                )
                            )
                        }
                    }
                    importedPlaylists++
                }
            }
            importedLikes to importedPlaylists
        }
    }

    companion object {
        private val json = Json {
            ignoreUnknownKeys = true
            prettyPrint = true
        }

        fun encodeBackupJson(payload: SonoraBackupPayload): String = json.encodeToString(payload)

        fun decodeBackupJson(raw: String): SonoraBackupPayload =
            json.decodeFromString(SonoraBackupPayload.serializer(), raw)
    }
}
