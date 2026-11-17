package com.example

import com.example.data.repository.MusicRepository
import com.example.model.Track
import com.example.player.AutoMixEngine
import com.example.player.TrackEnergyProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoMixAndSuggestionsTest {

    private fun track(
        id: String,
        title: String = "Track $id",
        artist: String = "Artist $id",
        durationMs: Long = 210_000L
    ) = Track(
        videoId = id,
        title = title,
        artist = artist,
        album = "Album $id",
        artworkUrl = "https://example.com/$id.jpg",
        durationText = "3:30",
        durationMs = durationMs
    )

    @Test
    fun `equalPowerGains maintains constant energy across transition`() {
        val mid = AutoMixEngine.equalPowerGains(0.5f)
        val energy = mid.first * mid.first + mid.second * mid.second
        assertEquals(1.0f, energy, 0.01f)

        val start = AutoMixEngine.equalPowerGains(0f)
        assertEquals(1.0f, start.first, 0.001f)
        assertEquals(0.0f, start.second, 0.001f)

        val end = AutoMixEngine.equalPowerGains(1f)
        assertEquals(0.0f, end.first, 0.001f)
        assertEquals(1.0f, end.second, 0.001f)
    }

    @Test
    fun `computeTransition adapts overlap based on song tempo and duration`() {
        val clubOut = track("c1", "Neon Club (Remix)", "DJ Nova", 195_000L)
        val clubIn = track("c2", "House Pulse", "DJ Nova", 205_000L)
        val clubPlan = AutoMixEngine.computeTransition(clubOut, clubIn, 195_000L)

        assertEquals(TrackEnergyProfile.HIGH_ENERGY_CLUB, AutoMixEngine.classifyEnergyProfile(clubOut))
        assertTrue(clubPlan.overlapMs in 3_200L..8_500L)
        assertTrue(clubPlan.compatibilityScore >= 0.9f)
        assertTrue(clubPlan.triggerBeforeEndMs > clubPlan.overlapMs)

        val shortOut = track("s1", "Interlude", "Artist", 20_000L)
        val shortPlan = AutoMixEngine.computeTransition(shortOut, clubIn, 20_000L)
        assertTrue(shortPlan.overlapMs <= 2_500L)
    }

    @Test
    fun `curateSuggestedSongs prioritizes unseen tracks deduplicates and rotates on refresh`() {
        val history = listOf(track("h1"), track("h2"))
        val personalized = (1..8).map { track("p$it") } + track("h1")
        val expanded = (1..4).map { track("e$it") } + track("")
        val home = (1..4).map { track("m$it") }

        val setGen0 = MusicRepository.curateSuggestedSongs(
            personalizedTracks = personalized,
            expandedTracks = expanded,
            homeTracks = home,
            userHistoryTracks = history,
            refreshSeed = 0,
            limit = 12
        )
        val setGen1 = MusicRepository.curateSuggestedSongs(
            personalizedTracks = personalized,
            expandedTracks = expanded,
            homeTracks = home,
            userHistoryTracks = history,
            refreshSeed = 1,
            limit = 12
        )

        assertEquals(12, setGen0.size)
        assertTrue(setGen0.none { it.videoId.isBlank() })
        assertEquals(setGen0.size, setGen0.map { it.videoId }.distinct().size)
        assertNotEquals(setGen0.first().videoId, setGen1.first().videoId)
    }
}
