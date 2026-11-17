package com.example

import com.example.data.network.GitHubUpdateChecker
import com.example.data.repository.BackupManager
import com.example.model.AppIconVariant
import com.example.model.BackupPlaylist
import com.example.model.DjVibe
import com.example.model.EqPreset
import com.example.model.SonoraBackupPayload
import com.example.model.Track
import com.example.player.DjLiveController
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SonoraV2FeaturesTest {

    private fun track(id: String, title: String = "Song $id", artist: String = "Artist $id") = Track(
        videoId = id,
        title = title,
        artist = artist,
        album = "Album $id",
        artworkUrl = "https://example.com/$id.jpg",
        durationText = "3:20",
        durationMs = 200_000L
    )

    @Test
    fun `curateMixQueue deduplicates tracks filters blank ids and caps at 24`() {
        val liked = (1..15).map { track("v$it") } + track("")
        val mostPlayed = (1..10).map { track("v$it") }
        val personalized = (10..35).map { track("v$it") }

        val result = DjLiveController.curateMixQueue(
            vibe = DjVibe.PERSONAL_MIX,
            liked = liked,
            mostPlayed = mostPlayed,
            recent = emptyList(),
            personalized = personalized,
            radio = emptyList(),
            homeFallback = emptyList()
        )

        assertTrue(result.size <= 24)
        assertTrue(result.none { it.videoId.isBlank() })
        assertEquals(result.size, result.map { it.videoId }.distinct().size)
    }

    @Test
    fun `curateMixQueue DISCOVERY prioritizes unseen tracks before known favorites`() {
        val known = (1..4).map { track("known_$it") }
        val unseen = (1..8).map { track("new_$it") }

        val result = DjLiveController.curateMixQueue(
            vibe = DjVibe.DISCOVERY,
            liked = known,
            mostPlayed = known,
            recent = emptyList(),
            personalized = known + unseen,
            radio = emptyList(),
            homeFallback = emptyList()
        )

        val firstEightIds = result.take(8).map { it.videoId }.toSet()
        assertEquals(unseen.map { it.videoId }.toSet(), firstEightIds)
    }

    @Test
    fun `backup encode and decode round trip preserves liked songs and playlists`() {
        val t1 = track("abc123", "Noches de Cristal", "Ibeth")
        val t2 = track("xyz789", "Luz de Luna", "Sonora Band")
        val payload = SonoraBackupPayload(
            version = 1,
            exportedAtMs = 1_760_000_000_000L,
            likedSongs = listOf(t1),
            playlists = listOf(
                BackupPlaylist(
                    name = "Favoritas Nocturnas",
                    description = "Para programar",
                    coverUrl = "https://example.com/cover.jpg",
                    tracks = listOf(t1, t2)
                )
            )
        )

        val encoded = BackupManager.encodeBackupJson(payload)
        val decoded = BackupManager.decodeBackupJson(encoded)

        assertEquals(payload.version, decoded.version)
        assertEquals(payload.exportedAtMs, decoded.exportedAtMs)
        assertEquals(listOf(t1), decoded.likedSongs)
        assertEquals(1, decoded.playlists.size)
        assertEquals("Favoritas Nocturnas", decoded.playlists.first().name)
        assertEquals(listOf(t1, t2), decoded.playlists.first().tracks)
    }

    @Test
    fun `backup decode ignores unknown keys for forward compatibility`() {
        val raw = """
            {
              "version": 2,
              "extraFutureField": "ignored",
              "likedSongs": [
                {"videoId": "t1", "title": "Tema", "artist": "Artista", "unknownTrackProp": 42}
              ],
              "playlists": []
            }
        """.trimIndent()

        val decoded = BackupManager.decodeBackupJson(raw)
        assertEquals(2, decoded.version)
        assertEquals(1, decoded.likedSongs.size)
        assertEquals("t1", decoded.likedSongs.first().videoId)
    }

    @Test
    fun `isRemoteVersionNewer compares semantic and tagged versions accurately`() {
        assertTrue(GitHubUpdateChecker.isRemoteVersionNewer("v2.0.0", "1.0.0"))
        assertTrue(GitHubUpdateChecker.isRemoteVersionNewer("v1.2.0", "1.1"))
        assertTrue(GitHubUpdateChecker.isRemoteVersionNewer("1.0.45", "1.0.44"))
        assertFalse(GitHubUpdateChecker.isRemoteVersionNewer("v1.0", "1.0.0"))
        assertFalse(GitHubUpdateChecker.isRemoteVersionNewer("1.0.43", "1.0.44"))
        assertFalse(GitHubUpdateChecker.isRemoteVersionNewer("draft", "1.0.0"))
    }

    @Test
    fun `all launcher icon variants and equalizer presets are consistently configured`() {
        val aliases = AppIconVariant.entries.map { it.aliasClassName }
        assertEquals(8, aliases.size)
        assertEquals(8, aliases.distinct().size)
        assertTrue(aliases.all { it.startsWith("com.example.Launcher") })

        assertTrue(EqPreset.entries.all { it.bandOffsetsDb.size == 5 })
    }
}
