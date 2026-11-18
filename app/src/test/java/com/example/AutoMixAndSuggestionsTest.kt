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
        assertEquals(clubPlan.overlapMs, clubPlan.triggerBeforeEndMs)

        val shortOut = track("s1", "Interlude", "Artist", 20_000L)
        val shortPlan = AutoMixEngine.computeTransition(shortOut, clubIn, 20_000L)
        assertTrue(shortPlan.overlapMs <= 2_500L)

        val manualPlan = AutoMixEngine.computeTransition(
            currentTrack = clubOut,
            nextTrack = clubIn,
            measuredDurationMs = 195_000L,
            configuredOverlapSec = 8
        )
        assertEquals(8_000L, manualPlan.overlapMs)
        assertEquals(8_000L, manualPlan.triggerBeforeEndMs)
        assertTrue(manualPlan.transitionStyleLabel.contains("8.0s"))
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

    @Test
    fun `voice dj local parser extracts genre and artist queries and exposes firebase notice`() {
        val result = com.example.data.ai.PlaceholderAiDjIntentRepository.parsePromptLocally(
            "Oye DJ quiero un mix de reggaeton con Bad Bunny y Feid"
        )
        assertEquals(false, result.isFirebaseBacked)
        assertTrue(!result.notice.isNullOrBlank())
        assertTrue(result.searchQueries.isNotEmpty())
        assertTrue(result.searchQueries.any { it.contains("Bad Bunny", ignoreCase = true) || it.contains("reggaeton", ignoreCase = true) })
    }

    @Test
    fun `curateSmartRelatedQueue excludes covers and foreign script clutter and interleaves artist hits`() {
        val seed = track("seed1", "Tití Me Preguntó", "Bad Bunny", 243_000L)
        val artistSong1 = track("a1", "Moscow Mule", "Bad Bunny", 245_000L)
        val artistSong2 = track("a2", "Efecto", "Bad Bunny", 213_000L)
        val coverNoise = track("bad_cov", "Tití Me Preguntó (Cover)", "Random Singer", 240_000L)
        val hindiNoise = track("bad_hi", "नमस्ते बॉलीवुड", "Random Bollywood", 220_000L)
        val radioSameArtist = track("ra1", "Me Porto Bonito", "Bad Bunny, Chencho Corleone", 178_000L)
        val radioRelated = track("rr1", "Provenza", "KAROL G", 210_000L)
        val unrelatedSearch = track("unrel", "Totalmente Random", "Other Artist", 190_000L)

        val curated = MusicRepository.curateSmartRelatedQueue(
            seedTrack = seed,
            upNextTracks = listOf(coverNoise, hindiNoise, radioSameArtist, radioRelated),
            artistSongs = listOf(artistSong1, artistSong2, coverNoise),
            contextSongs = listOf(unrelatedSearch)
        )

        val ids = curated.map { it.videoId }
        assertEquals("seed1", ids.first())
        assertTrue("Expected same-artist tracks included", "a1" in ids && "a2" in ids && "ra1" in ids)
        assertTrue("Expected related radio track included", "rr1" in ids)
        assertTrue("Cover track must be excluded", "bad_cov" !in ids)
        assertTrue("Foreign script track must be excluded", "bad_hi" !in ids)
        assertTrue("Unrelated search result must not leak into smart queue", "unrel" !in ids)
    }

    @Test
    fun `curateSmartRelatedQueue excludes same-title homonym songs and matches vevo channel names`() {
        val seed = track("t_style", "Style", "Taylor Swift", 231_000L)
        val sameTitleOtherArtist1 = track("hom1", "Style", "Foster The People", 240_000L)
        val sameTitleOtherArtist2 = track("hom2", "Style (Official Video)", "Exile", 210_000L)
        val duplicateEdition = track("dup1", "Style (Taylor's Version)", "Taylor Swift", 231_000L)
        val artistSongVevo = track("ts1", "Blank Space", "TaylorSwiftVEVO", 231_000L)
        val artistSong2 = track("ts2", "Cruel Summer", "Taylor Swift", 178_000L)
        val relatedPop = track("rel1", "drivers license", "Olivia Rodrigo", 242_000L)

        assertTrue(MusicRepository.sharesArtistWithSeed(seed, artistSongVevo))

        val curated = MusicRepository.curateSmartRelatedQueue(
            seedTrack = seed,
            upNextTracks = listOf(sameTitleOtherArtist1, relatedPop, duplicateEdition),
            artistSongs = listOf(artistSongVevo, artistSong2),
            contextSongs = listOf(sameTitleOtherArtist1, sameTitleOtherArtist2, duplicateEdition, artistSong2)
        )

        val ids = curated.map { it.videoId }
        assertEquals("t_style", ids.first())
        assertTrue("Homonym song 'Style' by other artist must be excluded", "hom1" !in ids)
        assertTrue("Homonym song 'Style' by other artist 2 must be excluded", "hom2" !in ids)
        assertTrue("Duplicate edition of seed song must be excluded", "dup1" !in ids)
        assertTrue("Expected Taylor Swift catalog hits", "ts1" in ids && "ts2" in ids)
        assertTrue("Expected related pop track", "rel1" in ids)
    }
}
