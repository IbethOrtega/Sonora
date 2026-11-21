package com.example

import com.example.data.repository.MusicRepository
import com.example.model.Track
import com.example.player.AutoMixEngine
import com.example.player.TrackEnergyProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
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

    @Test
    fun `gemini tts helpers parse sample rate strip wav header and smooth pcm edges`() {
        assertEquals(
            24_000,
            com.example.data.ai.FirebaseAiVoiceSynthesizer.parseSampleRateFromMime("audio/pcm")
        )
        assertEquals(
            24_000,
            com.example.data.ai.FirebaseAiVoiceSynthesizer.parseSampleRateFromMime("audio/L16;codec=pcm;rate=24000")
        )
        assertEquals(
            16_000,
            com.example.data.ai.FirebaseAiVoiceSynthesizer.parseSampleRateFromMime("audio/pcm;rate=16000")
        )

        val wavHeader = "RIFF".toByteArray() + ByteArray(40)
        val payload = byteArrayOf(10, 20, 30, 40)
        val stripped = com.example.data.ai.FirebaseAiVoiceSynthesizer.stripWavHeaderIfPresent(wavHeader + payload)
        assertEquals(4, stripped.size)
        assertEquals(10.toByte(), stripped[0])

        val rawPcm = ByteArray(4_000) { i -> if (i % 2 == 0) 0x00 else 0x40 }
        val smoothed = com.example.player.DjLiveController.applyEdgeFades(rawPcm, 24_000)
        assertEquals(rawPcm.size, smoothed.size)
        // First sample should be attenuated to 0 to eliminate click
        assertEquals(0.toByte(), smoothed[0])
        assertEquals(0.toByte(), smoothed[1])
    }

    @Test
    fun `curateSmartRelatedQueue rotates fresh suggestions and remembers previouslyShownIds`() {
        val seed = track("stardance", "Star Dance", "Selena Gomez", 215_000L)
        val sameArtistCatalog = (1..8).map { idx ->
            track("sg_$idx", "Selena Track $idx", "Selena Gomez", 200_000L + idx * 1000L)
        }
        val relatedCatalog = (1..8).map { idx ->
            track("rel_$idx", "Pop Hit $idx", "Similar Artist $idx", 210_000L + idx * 1000L)
        }

        val firstPlay = MusicRepository.curateSmartRelatedQueue(
            seedTrack = seed,
            upNextTracks = relatedCatalog,
            artistSongs = sameArtistCatalog,
            limit = 10,
            previouslyShownIds = emptySet(),
            rotationSeed = 1L
        )
        val firstUpcomingIds = firstPlay.drop(1).map { it.videoId }
        val shownIds = firstUpcomingIds.take(5).toSet()

        val secondPlay = MusicRepository.curateSmartRelatedQueue(
            seedTrack = seed,
            upNextTracks = relatedCatalog,
            artistSongs = sameArtistCatalog,
            limit = 10,
            previouslyShownIds = shownIds,
            rotationSeed = 2L
        )
        val secondUpcoming = secondPlay.drop(1).map { it.videoId }

        assertEquals("stardance", secondPlay.first().videoId)
        assertNotEquals(firstUpcomingIds, secondUpcoming)
        // Unseen tracks should appear before previously shown tracks
        val unseenFirstHalf = secondUpcoming.take(5).count { it !in shownIds }
        assertTrue("Expected unseen tracks prioritized at top of second queue", unseenFirstHalf >= 4)

        val sameArtistMood = MusicRepository.curateSmartRelatedQueue(
            seedTrack = seed,
            upNextTracks = relatedCatalog,
            artistSongs = sameArtistCatalog,
            limit = 8,
            moodFilter = com.example.model.QueueMoodFilter.SAME_ARTIST
        )
        val sameArtistCount = sameArtistMood.drop(1).count { MusicRepository.sharesArtistWithSeed(seed, it) }
        assertTrue("SAME_ARTIST mood should heavily prioritize Selena Gomez tracks", sameArtistCount >= 5)
    }

    @Test
    fun `computeTransition honors crossfadeSeconds custom fragment specs and curve gains`() {
        val outTrack = track("a", "Intro Song", "Artist A", 200_000L)
        val inTrack = track("b", "Next Drop (Remix)", "Artist B", 210_000L)

        val manualCrossfade = AutoMixEngine.computeTransition(
            currentTrack = outTrack,
            nextTrack = inTrack,
            measuredDurationMs = 200_000L,
            configuredOverlapSec = 0,
            crossfadeSeconds = 5
        )
        assertEquals(5_000L, manualCrossfade.overlapMs)
        assertTrue(manualCrossfade.transitionStyleLabel.contains("Fundido"))

        val autoCrossfade = AutoMixEngine.computeTransition(
            currentTrack = outTrack,
            nextTrack = inTrack,
            measuredDurationMs = 200_000L,
            configuredOverlapSec = 0,
            crossfadeSeconds = -1
        )
        assertTrue(autoCrossfade.overlapMs in 3_000L..6_000L)
        assertTrue(autoCrossfade.transitionStyleLabel.contains("Fundido auto"))

        val fragmentSpec = com.example.model.PlaylistTransitionSpec(
            playlistId = 10L,
            fromVideoId = "a",
            toVideoId = "b",
            fromExitMs = 140_000L,
            toEntryMs = 42_000L,
            crossfadeMs = 8_000,
            curveType = com.example.model.TransitionCurveType.BEAT_CUT
        )
        val fragmentPlan = AutoMixEngine.computeTransition(
            currentTrack = outTrack,
            nextTrack = inTrack,
            measuredDurationMs = 200_000L,
            customSpec = fragmentSpec
        )
        assertEquals(8_000L, fragmentPlan.overlapMs)
        assertEquals(132_000L, fragmentPlan.triggerAtPositionMs)
        assertEquals(42_000L, fragmentPlan.incomingStartPositionMs)
        assertEquals(com.example.model.TransitionCurveType.BEAT_CUT, fragmentPlan.curveType)

        com.example.model.TransitionCurveType.entries.forEach { curve ->
            val start = AutoMixEngine.curveGains(0f, curve)
            val mid = AutoMixEngine.curveGains(0.5f, curve)
            val end = AutoMixEngine.curveGains(1f, curve)
            assertEquals(1f, start.first, 0.01f)
            assertEquals(0f, start.second, 0.01f)
            assertTrue(mid.first in 0f..1f && mid.second in 0f..1f)
            assertEquals(0f, end.first, 0.01f)
            assertEquals(1f, end.second, 0.01f)
        }
    }

    @Test
    fun `normalizePersistedQueueWindow centers window around high current index`() {
        val hugeQueue = (0 until 250).map { idx -> track("id_$idx", "Song $idx") }
        val (windowed, newIndex) = com.example.player.PlayerController.normalizePersistedQueueWindow(
            queue = hugeQueue,
            currentIndex = 180,
            maxSize = 120
        )
        assertEquals(120, windowed.size)
        assertEquals("id_180", windowed[newIndex].videoId)
        assertTrue(newIndex in 40..70)
    }

    @Test
    fun `curateSmartRelatedQueue strictly pushes lastGenerationIds to the very end below fresh and olderSeen`() {
        val seed = track("seed_rep", "Pepas", "Farruko", 287_000L)
        val sameArtist = (1..6).map { idx ->
            track("fa_$idx", "Farruko Hit $idx", "Farruko", 200_000L + idx * 1000L)
        }
        val related = (1..6).map { idx ->
            track("rel_$idx", "Urbano Hit $idx", "Rauw Alejandro", 205_000L + idx * 1000L)
        }

        // First generation showed fa_1, fa_2, rel_1; olderSeen showed fa_3; fresh are fa_4..6 & rel_2..6
        val lastGen = setOf("fa_1", "fa_2", "rel_1")
        val allShown = lastGen + "fa_3"

        val replayQueue = MusicRepository.curateSmartRelatedQueue(
            seedTrack = seed,
            upNextTracks = related,
            artistSongs = sameArtist,
            limit = 10,
            previouslyShownIds = allShown,
            rotationSeed = 99L,
            lastGenerationIds = lastGen
        )
        val upcomingIds = replayQueue.drop(1).map { it.videoId }
        val topSix = upcomingIds.take(6)
        assertTrue(
            "Top 6 tracks on immediate re-play must exclude lastGenerationIds, got $topSix",
            topSix.none { it in lastGen }
        )
    }

    @Test
    fun `YouTubeMusicClient parseHomeChips parses chipCloudRenderer and preserves selection`() {
        val ytm = com.example.data.network.YouTubeMusicClient(okhttp3.OkHttpClient())
        val chipCloud = org.json.JSONObject().put(
            "chips",
            org.json.JSONArray()
                .put(
                    org.json.JSONObject().put(
                        "chipCloudChipRenderer",
                        org.json.JSONObject()
                            .put("text", org.json.JSONObject().put("runs", org.json.JSONArray().put(org.json.JSONObject().put("text", "Energía"))))
                            .put("isSelected", true)
                            .put(
                                "navigationEndpoint",
                                org.json.JSONObject().put(
                                    "browseEndpoint",
                                    org.json.JSONObject()
                                        .put("browseId", "FEmusic_home")
                                        .put("params", "chip_energy_123")
                                )
                            )
                    )
                )
                .put(
                    org.json.JSONObject().put(
                        "chipCloudChipRenderer",
                        org.json.JSONObject()
                            .put("text", org.json.JSONObject().put("runs", org.json.JSONArray().put(org.json.JSONObject().put("text", "Relajación"))))
                            .put("isSelected", false)
                            .put(
                                "navigationEndpoint",
                                org.json.JSONObject().put(
                                    "browseEndpoint",
                                    org.json.JSONObject()
                                        .put("browseId", "FEmusic_home")
                                        .put("params", "chip_chill_456")
                                )
                            )
                    )
                )
        )
        val responseJson = org.json.JSONObject().put(
            "contents",
            org.json.JSONObject().put(
                "singleColumnBrowseResultsRenderer",
                org.json.JSONObject().put(
                    "tabs",
                    org.json.JSONArray().put(
                        org.json.JSONObject().put(
                            "tabRenderer",
                            org.json.JSONObject().put(
                                "content",
                                org.json.JSONObject().put(
                                    "sectionListRenderer",
                                    org.json.JSONObject().put(
                                        "header",
                                        org.json.JSONObject().put("chipCloudRenderer", chipCloud)
                                    )
                                )
                            )
                        )
                    )
                )
            )
        )

        val chips = ytm.parseHomeChips(responseJson, activeParams = "chip_energy_123")
        assertTrue(chips.isNotEmpty())
        assertEquals("Todo", chips.first().title)
        assertEquals(false, chips.first().isSelected)
        val energy = chips.first { it.title == "Energía" }
        assertEquals("chip_energy_123", energy.params)
        assertTrue(energy.isSelected)
    }

    @Test
    fun `curateVoiceMixPool keeps primary requested tracks instead of stripping seed title`() {
        val requested = listOf(
            track("v1", "Dakiti", "Bad Bunny, Jhayco"),
            track("v2", "La Canción", "J Balvin, Bad Bunny")
        )
        val upNext = listOf(
            track("v3", "Yonaguni", "Bad Bunny")
        )
        val pool = com.example.player.DjLiveController.curateVoiceMixPool(
            gathered = requested,
            upNextTracks = upNext,
            limit = 24
        )
        val ids = pool.map { it.videoId }
        assertTrue("Requested song v1 must stay in Voice DJ pool", "v1" in ids)
        assertTrue("Requested song v2 must stay in Voice DJ pool", "v2" in ids)
        assertTrue("Related song v3 must stay in Voice DJ pool", "v3" in ids)
    }

    @Test
    fun `remotePlaylistTransitionKey produces stable negative non-colliding IDs`() {
        val key1 = com.example.model.remotePlaylistTransitionKey("VLPL_YTM_PLAYLIST_123")
        val key1Again = com.example.model.remotePlaylistTransitionKey("VLPL_YTM_PLAYLIST_123")
        val key2 = com.example.model.remotePlaylistTransitionKey("MPREb_ALBUM_XYZ")

        assertEquals(key1, key1Again)
        assertNotEquals(key1, key2)
        assertTrue("Remote playlist transition key must be < -10000", key1 < -10_000L)
        assertTrue("Remote album transition key must be < -10000", key2 < -10_000L)
    }

    @Test
    fun `resolveTrackDurationMs parses durationText and clamps custom exit against short tracks`() {
        val parsedFromText = track("dur_txt", "Custom", "Artist", durationMs = 0L)
            .copy(durationText = "4:15")
        assertEquals(255_000L, com.example.model.resolveTrackDurationMs(parsedFromText))

        val measuredOverride = com.example.model.resolveTrackDurationMs(parsedFromText, measuredDurationMs = 198_000L)
        assertEquals(198_000L, measuredOverride)

        val unknownDur = track("dur_unk", "Unknown", "Artist", durationMs = 0L).copy(durationText = "")
        assertEquals(210_000L, com.example.model.resolveTrackDurationMs(unknownDur))

        // If customSpec fromExitMs (190s) exceeds measured track duration (160s), triggerAtPositionMs stays reachable
        val spec = com.example.model.PlaylistTransitionSpec(
            playlistId = 0L,
            fromVideoId = "out",
            toVideoId = "in",
            fromExitMs = 190_000L,
            toEntryMs = 38_000L,
            crossfadeMs = 6_000,
            curveType = com.example.model.TransitionCurveType.EQ_BASS_SWAP
        )
        val plan = AutoMixEngine.computeTransition(
            currentTrack = track("out", durationMs = 160_000L),
            nextTrack = track("in", durationMs = 200_000L),
            measuredDurationMs = 160_000L,
            customSpec = spec
        )
        assertTrue(plan.triggerAtPositionMs != null && plan.triggerAtPositionMs!! < 160_000L)
        assertEquals(38_000L, plan.incomingStartPositionMs)
    }
}
