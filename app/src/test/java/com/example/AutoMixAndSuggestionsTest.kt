package com.example

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.SonoraDatabase
import com.example.data.local.SonoraPreferences
import com.example.data.network.LyricsResolver
import com.example.data.network.NewPipeSearchClient
import com.example.data.network.SpotifyImporter
import com.example.data.network.StreamResolver
import com.example.data.network.YouTubeMusicClient
import com.example.data.repository.MusicRepository
import com.example.model.CustomMixFragment
import com.example.model.Track
import com.example.player.AutoMixEngine
import com.example.player.PlayerController
import com.example.player.TrackEnergyProfile
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
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
    fun `voice dj local parser extracts genre and artist queries drops sony and avoids gemini mention`() {
        val result = com.example.data.ai.PlaceholderAiDjIntentRepository.parsePromptLocally(
            "Oye DJ Sony quiero un mix de reggaeton con Bad Bunny y Feid"
        )
        assertEquals(false, result.isFirebaseBacked)
        assertTrue(!result.notice.isNullOrBlank())
        assertFalse(result.notice.orEmpty().contains("Gemini", ignoreCase = true))
        assertFalse(result.notice.orEmpty().contains("Firebase", ignoreCase = true))
        assertTrue(result.searchQueries.isNotEmpty())
        assertTrue(result.searchQueries.none { it.contains("sony", ignoreCase = true) })
        assertTrue(result.searchQueries.any { it.contains("Bad Bunny", ignoreCase = true) || it.contains("reggaeton", ignoreCase = true) })

        assertEquals(
            "algo de salsa romantica",
            com.example.player.DjLiveController.stripWakeWords("Oye DJ Sony algo de salsa romantica")
        )
        assertEquals(
            "rock en español",
            com.example.player.DjLiveController.stripWakeWords("DJ Sony, rock en español")
        )
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

    @Test
    fun `tapping a suggestion from 10-track shelf creates single-item initial queue without adding shelf and clears mix context`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val offlineClient = OkHttpClient.Builder()
            .addInterceptor { chain ->
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(404)
                    .message("Offline")
                    .body("".toResponseBody("text/plain".toMediaType()))
                    .build()
            }
            .build()
        val db = Room.inMemoryDatabaseBuilder(context, SonoraDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val prefs = SonoraPreferences(context)
        prefs.setAutoplayRadio(false)
        val streamResolver = StreamResolver(offlineClient)
        val repo = MusicRepository(
            ytMusicClient = YouTubeMusicClient(offlineClient),
            streamResolver = streamResolver,
            spotifyImporter = SpotifyImporter(offlineClient),
            libraryDao = db.libraryDao(),
            lyricsResolver = LyricsResolver(offlineClient, context),
            newPipeSearchClient = NewPipeSearchClient(offlineClient)
        )
        val controller = PlayerController(
            context = context,
            repository = repo,
            streamResolver = streamResolver,
            preferences = prefs
        )

        try {
            val shelf = (1..10).map { idx ->
                track("shelf_$idx", "Sugerida $idx", "Artista Unico $idx")
            }

            // Activate a custom fragment mix first to ensure playTrackWithSmartQueue clears transition/playlist context
            controller.playCustomMixFragments(
                listOf(
                    CustomMixFragment(track = shelf[0], startMs = 0L, endMs = 30_000L),
                    CustomMixFragment(track = shelf[1], startMs = 10_000L, endMs = 40_000L)
                )
            )
            assertTrue(controller.hasActivePlaylistOrTransitionContext())
            assertEquals(2, controller.state.value.queue.size)

            // Simulate playSuggestedTrackAction on card #4 of the 10-track shelf (ignores shelf list)
            val tapped = shelf[3]
            controller.playTrackWithSmartQueue(tapped)

            assertFalse(controller.hasActivePlaylistOrTransitionContext())
            assertEquals(tapped.videoId, controller.state.value.currentTrack?.videoId)
            assertEquals(listOf(tapped.videoId), controller.state.value.queue.map { it.videoId })

            // Allow async metadataJob to settle with autoplayRadio = false
            delay(100L)
            assertEquals(listOf(tapped.videoId), controller.state.value.queue.map { it.videoId })

            // Tapping another suggestion replaces queue with a fresh single-item queue without altering shelf
            val secondTap = shelf[7]
            controller.playTrackWithSmartQueue(secondTap)
            assertEquals(listOf(secondTap.videoId), controller.state.value.queue.map { it.videoId })
            assertEquals(10, shelf.size)

            // Local track preserves shelf queue via playTrack
            val localTrack = tapped.copy(isLocal = true, localUri = "content://media/external/audio/1")
            controller.playTrack(localTrack, shelf)
            assertEquals(10, controller.state.value.queue.size)
            assertEquals(localTrack.videoId, controller.state.value.currentTrack?.videoId)
        } finally {
            prefs.setAutoplayRadio(true)
            controller.release()
            db.close()
        }
    }

    @Test
    fun `startVoiceDjMix automatically falls back to local parser when AI repository throws`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val searchPayload = """
            {
              "contents": {
                "tabbedSearchResultsRenderer": {
                  "tabs": [
                    {
                      "tabRenderer": {
                        "content": {
                          "sectionListRenderer": {
                            "contents": [
                              {
                                "musicCardShelfRenderer": {
                                  "title": { "runs": [ { "text": "Tití Me Preguntó" } ] },
                                  "subtitle": { "runs": [ { "text": "Canción • Bad Bunny • 4:03" } ] },
                                  "onTap": { "watchEndpoint": { "videoId": "dj_fb_1" } }
                                }
                              }
                            ]
                          }
                        }
                      }
                    }
                  ]
                }
              }
            }
        """.trimIndent()
        val fakeClient = OkHttpClient.Builder()
            .addInterceptor { chain ->
                val url = chain.request().url.toString()
                if (url.contains("youtubei/v1/search")) {
                    Response.Builder()
                        .request(chain.request())
                        .protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("OK")
                        .body(searchPayload.toResponseBody("application/json".toMediaType()))
                        .build()
                } else {
                    Response.Builder()
                        .request(chain.request())
                        .protocol(Protocol.HTTP_1_1)
                        .code(404)
                        .message("Offline")
                        .body("".toResponseBody("text/plain".toMediaType()))
                        .build()
                }
            }
            .build()
        val db = Room.inMemoryDatabaseBuilder(context, SonoraDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val prefs = SonoraPreferences(context)
        val streamResolver = StreamResolver(fakeClient)
        val repo = MusicRepository(
            ytMusicClient = YouTubeMusicClient(fakeClient),
            streamResolver = streamResolver,
            spotifyImporter = SpotifyImporter(fakeClient),
            libraryDao = db.libraryDao(),
            lyricsResolver = LyricsResolver(fakeClient, context),
            newPipeSearchClient = null
        )
        val playerController = PlayerController(
            context = context,
            repository = repo,
            streamResolver = streamResolver,
            preferences = prefs
        )
        val throwingAiRepo = object : com.example.data.ai.AiDjIntentRepository {
            override suspend fun planVoiceMix(spokenPrompt: String): com.example.data.ai.AiDjPlanResult {
                throw IllegalStateException("App Check attestation failed HTTP 403")
            }
        }
        val djController = com.example.player.DjLiveController(
            context = context,
            repository = repo,
            playerController = playerController,
            aiDjIntentRepository = throwingAiRepo,
            djVoiceSynthesizer = com.example.data.ai.NoOpDjVoiceSynthesizer()
        )
        try {
            djController.startVoiceDjMix("Oye DJ Sony pon algo de Bad Bunny")
            val deadline = System.currentTimeMillis() + 5_000L
            while (djController.state.value.isLoading && System.currentTimeMillis() < deadline) {
                org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
                delay(25L)
            }
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            val st = djController.state.value
            assertFalse(st.isLoading)
            assertTrue("Expected DJ Sony to remain active via local fallback", st.isActive)
            assertEquals("DJ Sony sin IA: usando búsqueda directa", st.aiNotice)
            assertTrue(!st.aiTechnicalDetail.isNullOrBlank())
            assertTrue(st.aiError == null)
            assertTrue(playerController.state.value.queue.any { it.videoId == "dj_fb_1" })
        } finally {
            djController.release()
            playerController.release()
            db.close()
        }
    }

    @Test
    fun `sanitizeTextForSpeech strips emojis brackets quotes dashes and feat`() {
        val raw = "🔥 Ahora suena \"Lovers\" (Official Video) [Remastered] — Anna of the North feat. Gus 🎧"
        val cleaned = com.example.data.ai.FirebaseAiVoiceSynthesizer.sanitizeTextForSpeech(raw)
        assertFalse(cleaned.contains("🔥"))
        assertFalse(cleaned.contains("🎧"))
        assertFalse(cleaned.contains("Official Video"))
        assertFalse(cleaned.contains("Remastered"))
        assertFalse(cleaned.contains("\""))
        assertFalse(cleaned.contains("—"))
        assertFalse(cleaned.contains("feat.", ignoreCase = true))
        assertTrue(cleaned.contains("Lovers"))
        assertTrue(cleaned.contains("Anna of the North y Gus"))
    }

    @Test
    fun `SonoraPreferences persists and normalizes djVoiceName`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val prefs = SonoraPreferences(context)
        try {
            prefs.setDjVoiceName("charon")
            assertEquals("Charon", prefs.settingsFlow.first().djVoiceName)

            prefs.setDjVoiceName("UnknownVoice99")
            assertEquals("Aoede", prefs.settingsFlow.first().djVoiceName)

            prefs.setDjVoiceName("Puck")
            assertEquals("Puck", prefs.settingsFlow.first().djVoiceName)
        } finally {
            prefs.setDjVoiceName("Aoede")
        }
    }

    @Test
    fun `FirebaseAiVoiceSynthesizer tries TTS models in priority order caches winner and falls back to Aoede`() = runBlocking {
        val calls = mutableListOf<Pair<String, String>>()
        val synth = com.example.data.ai.FirebaseAiVoiceSynthesizer { modelName, voiceName, _ ->
            calls.add(modelName to voiceName)
            when {
                voiceName == "Charon" -> null // force custom voice failure across candidates
                modelName == "gemini-3.8-flash-lite-tts" && voiceName == "Aoede" ->
                    com.example.data.ai.SynthesizedSpeech(pcmBytes = byteArrayOf(1, 2, 3, 4), sampleRateHz = 24_000)
                else -> null
            }
        }

        val first = synth.synthesizeSpeech("Hola, soy DJ Sony", voiceName = "Charon")
        assertNotNull(first)
        // First 3 attempts tried Charon across TTS_MODEL_CANDIDATES, then Aoede fell back from 3.8-flash-tts -> 3.8-flash-lite-tts
        assertEquals(
            listOf(
                "gemini-3.8-flash-tts" to "Charon",
                "gemini-3.8-flash-lite-tts" to "Charon",
                "gemini-3.1-flash-tts-preview" to "Charon",
                "gemini-3.8-flash-tts" to "Aoede",
                "gemini-3.8-flash-lite-tts" to "Aoede"
            ),
            calls
        )

        calls.clear()
        val second = synth.synthesizeSpeech("Siguiente tema", voiceName = "Aoede")
        assertNotNull(second)
        // Cached working model ("gemini-3.8-flash-lite-tts") should be tried first
        assertEquals(listOf("gemini-3.8-flash-lite-tts" to "Aoede"), calls)

        val emptyResult = synth.synthesizeSpeech("   ", voiceName = "Aoede")
        assertNull(emptyResult)
    }

    @Test
    fun `scoreAlternateCandidate enforces exact artist guard on short titles like Lovers and ranks tiers cleanly`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, SonoraDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val offlineClient = OkHttpClient()
        val repo = MusicRepository(
            ytMusicClient = YouTubeMusicClient(offlineClient),
            streamResolver = StreamResolver(offlineClient),
            spotifyImporter = SpotifyImporter(offlineClient),
            libraryDao = db.libraryDao(),
            lyricsResolver = LyricsResolver(offlineClient, context),
            newPipeSearchClient = null
        )
        try {
            val original = track(
                id = "orig_lovers",
                title = "Lovers",
                artist = "Anna of the North",
                durationMs = 235_000L
            )

            // Wrong-artist homonym must be rejected
            assertNull(
                repo.scoreAlternateCandidate(
                    original,
                    track("wrong_timecop", title = "Lovers", artist = "Timecop1983", durationMs = 235_000L),
                    fromSongsFilter = true
                )
            )
            // Partial artist match ("Anna" != "Anna of the North") must be rejected on short title
            assertNull(
                repo.scoreAlternateCandidate(
                    original,
                    track("wrong_partial", title = "Lovers", artist = "Anna", durationMs = 235_000L),
                    fromSongsFilter = true
                )
            )
            // Cover must be rejected
            assertNull(
                repo.scoreAlternateCandidate(
                    original,
                    track("wrong_cover", title = "Lovers (Cover)", artist = "Anna of the North", durationMs = 235_000L),
                    fromSongsFilter = false
                )
            )

            val ytmSong = repo.scoreAlternateCandidate(
                original,
                track("alt_song", title = "Lovers", artist = "Anna of the North", durationMs = 235_000L),
                fromSongsFilter = true
            )
            val officialAudio = repo.scoreAlternateCandidate(
                original,
                track("alt_audio", title = "Anna of the North - Lovers (Official Audio)", artist = "Anna of the North", durationMs = 237_000L),
                fromSongsFilter = false
            )
            val lyricVideo = repo.scoreAlternateCandidate(
                original,
                track("alt_lyric", title = "Anna of the North - Lovers (Lyrics)", artist = "UniqueVibes", durationMs = 234_000L),
                fromSongsFilter = false
            )
            val officialVideo = repo.scoreAlternateCandidate(
                original,
                track("alt_video", title = "Anna of the North - Lovers (Official Video)", artist = "Anna of the North", durationMs = 243_000L),
                fromSongsFilter = false
            )

            assertNotNull(ytmSong)
            assertNotNull(officialAudio)
            assertNotNull(lyricVideo)
            assertNotNull(officialVideo)

            assertEquals(MusicRepository.AlternatePriorityTier.YTM_SONG, ytmSong!!.tier)
            assertEquals(MusicRepository.AlternatePriorityTier.OFFICIAL_AUDIO, officialAudio!!.tier)
            assertEquals(MusicRepository.AlternatePriorityTier.LYRIC_VIDEO, lyricVideo!!.tier)
            assertEquals(MusicRepository.AlternatePriorityTier.OFFICIAL_VIDEO, officialVideo!!.tier)

            assertTrue(ytmSong.tier.rank > officialAudio.tier.rank)
            assertTrue(officialAudio.tier.rank > lyricVideo.tier.rank)
            assertTrue(lyricVideo.tier.rank > officialVideo.tier.rank)
        } finally {
            db.close()
        }
    }

    @Test
    fun `alternateVideoCache hits within 24h and expires cleanly after 24h`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, SonoraDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val offlineClient = OkHttpClient()
        val streamResolver = StreamResolver(offlineClient)
        val repo = MusicRepository(
            ytMusicClient = YouTubeMusicClient(offlineClient),
            streamResolver = streamResolver,
            spotifyImporter = SpotifyImporter(offlineClient),
            libraryDao = db.libraryDao(),
            lyricsResolver = LyricsResolver(offlineClient, context),
            newPipeSearchClient = null
        )
        try {
            var nowMs = 1_000_000_000L
            repo.alternateClockMs = { nowMs }

            repo.cacheAlternateVideoId("orig_lovers", "alt_lovers")
            assertEquals("alt_lovers", repo.getCachedAlternateVideoId("orig_lovers"))

            // 12 hours later -> still valid
            nowMs += 12L * 60L * 60L * 1000L
            assertEquals("alt_lovers", repo.getCachedAlternateVideoId("orig_lovers"))

            // 24 hours elapsed -> expired and evicted
            nowMs += 12L * 60L * 60L * 1000L
            assertNull(repo.getCachedAlternateVideoId("orig_lovers"))

            // Evict explicitly
            repo.cacheAlternateVideoId("orig_lovers", "alt_lovers_2")
            assertEquals("alt_lovers_2", repo.getCachedAlternateVideoId("orig_lovers"))
            repo.evictAlternateVideoCache("orig_lovers")
            assertNull(repo.getCachedAlternateVideoId("orig_lovers"))
        } finally {
            db.close()
        }
    }

    @Test
    fun `PlaybackDiagnostics records client playability probe outcomes and redacts full URLs`() {
        com.example.data.network.PlaybackDiagnostics.clearForTest()
        val videoId = "lovers_diag_1"
        com.example.data.network.PlaybackDiagnostics.beginResolution(
            videoId = videoId,
            title = "Lovers",
            artist = "Anna of the North"
        )
        com.example.data.network.PlaybackDiagnostics.recordClientResult(
            videoId,
            "ANDROID_VR: playability=LOGIN_REQUIRED (Sign in to confirm your age) https://rr3---sn-abc.googlevideo.com/videoplayback?id=999&sig=SECRET_TOKEN"
        )
        com.example.data.network.PlaybackDiagnostics.recordClientResult(
            videoId,
            "WEB_REMIX: probe=HTTP_403_FORBIDDEN https://rr5---sn-xyz.googlevideo.com/videoplayback?token=LEAKED"
        )
        com.example.data.network.PlaybackDiagnostics.recordAlternateEvent(
            videoId,
            "alt_song_lovers score=195 tier=YTM Song probe=PLAYABLE"
        )

        val report = com.example.data.network.PlaybackDiagnostics.buildReport(videoId)
        assertTrue(report.contains("videoId: lovers_diag_1"))
        assertTrue(report.contains("Título: Lovers"))
        assertTrue(report.contains("Artista: Anna of the North"))
        assertTrue(report.contains("LOGIN_REQUIRED"))
        assertTrue(report.contains("HTTP_403_FORBIDDEN"))
        assertTrue(report.contains("score=195"))
        assertTrue(report.contains("<rr3---sn-abc.googlevideo.com/...>"))
        assertFalse("Full stream URL must never appear in report", report.contains("https://"))
        assertFalse("Query secrets must never appear in report", report.contains("SECRET_TOKEN"))
        assertFalse("Query params must never appear in report", report.contains("videoplayback"))
        com.example.data.network.PlaybackDiagnostics.clearForTest()
    }

    @Test
    fun `findAlternatePlayableStream searches SONGS first when original is a music video like Lovers`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        com.example.data.network.PlaybackDiagnostics.clearForTest()
        val recordedSearchParams = java.util.Collections.synchronizedList(mutableListOf<String>())
        val songSearchPayload = """
            {
              "contents": {
                "tabbedSearchResultsRenderer": {
                  "tabs": [
                    {
                      "tabRenderer": {
                        "content": {
                          "sectionListRenderer": {
                            "contents": [
                              {
                                "musicShelfRenderer": {
                                  "contents": [
                                    {
                                      "musicResponsiveListItemRenderer": {
                                        "playlistItemData": { "videoId": "ytm_lovers_song" },
                                        "flexColumns": [
                                          {
                                            "musicResponsiveListItemFlexColumnRenderer": {
                                              "text": {
                                                "runs": [
                                                  {
                                                    "text": "Lovers",
                                                    "navigationEndpoint": { "watchEndpoint": { "videoId": "ytm_lovers_song" } }
                                                  }
                                                ]
                                              }
                                            }
                                          },
                                          {
                                            "musicResponsiveListItemFlexColumnRenderer": {
                                              "text": {
                                                "runs": [
                                                  {
                                                    "text": "Anna of the North",
                                                    "navigationEndpoint": { "browseEndpoint": { "browseId": "UC_ANNA_01" } }
                                                  },
                                                  { "text": " • " },
                                                  { "text": "3:55" }
                                                ]
                                              }
                                            }
                                          }
                                        ]
                                      }
                                    }
                                  ]
                                }
                              }
                            ]
                          }
                        }
                      }
                    }
                  ]
                }
              }
            }
        """.trimIndent()

        val testClient = OkHttpClient.Builder()
            .addInterceptor { chain ->
                val req = chain.request()
                val url = req.url.toString()
                if (url.contains("youtubei/v1/search")) {
                    val buf = okio.Buffer()
                    req.body?.writeTo(buf)
                    val bodyJson = runCatching { org.json.JSONObject(buf.readUtf8()) }.getOrNull()
                    recordedSearchParams.add(bodyJson?.optString("params").orEmpty())
                    Response.Builder()
                        .request(req)
                        .protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("OK")
                        .body(songSearchPayload.toResponseBody("application/json".toMediaType()))
                        .build()
                } else {
                    Response.Builder()
                        .request(req)
                        .protocol(Protocol.HTTP_1_1)
                        .code(404)
                        .message("Offline")
                        .body("".toResponseBody("text/plain".toMediaType()))
                        .build()
                }
            }
            .build()

        val db = Room.inMemoryDatabaseBuilder(context, SonoraDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val streamResolver = StreamResolver(testClient)
        val repo = MusicRepository(
            ytMusicClient = YouTubeMusicClient(testClient),
            streamResolver = streamResolver,
            spotifyImporter = SpotifyImporter(testClient),
            libraryDao = db.libraryDao(),
            lyricsResolver = LyricsResolver(testClient, context),
            newPipeSearchClient = null
        )
        try {
            val mvOriginal = track(
                id = "mv_lovers_orig",
                title = "Lovers (Official Video)",
                artist = "Anna of the North",
                durationMs = 235_000L
            )
            repo.findAlternatePlayableStream(mvOriginal, allowHls = false)

            assertTrue("Expected at least one search call", recordedSearchParams.isNotEmpty())
            assertEquals(
                "Music video original must trigger SearchFilter.SONGS first",
                com.example.model.SearchFilter.SONGS.params,
                recordedSearchParams.first()
            )
            val diag = com.example.data.network.PlaybackDiagnostics.buildReport("mv_lovers_orig")
            assertTrue(diag.contains("video musical", ignoreCase = true))
            assertTrue(diag.contains("ytm_lovers_song"))
            assertFalse(diag.contains("https://"))
        } finally {
            com.example.data.network.PlaybackDiagnostics.clearForTest()
            db.close()
        }
    }

    @Test
    fun `splitArtistNames splits multiple artists strips metadata bullets and Topic suffix`() {
        assertEquals(
            listOf("Bad Bunny", "Jhayco", "Chencho Corleone"),
            com.example.model.splitArtistNames("Bad Bunny, Jhayco & Chencho Corleone")
        )
        assertEquals(
            listOf("Calvin Harris", "Dua Lipa", "Young Thug"),
            com.example.model.splitArtistNames("Canción • Calvin Harris feat. Dua Lipa ft. Young Thug • 3:44")
        )
        assertEquals(
            listOf("Anna of the North"),
            com.example.model.splitArtistNames("Anna of the North - Topic")
        )
        assertEquals(
            listOf("Ellie Goulding", "Juice WRLD"),
            com.example.model.splitArtistNames("Ellie Goulding, Juice WRLD & ellie goulding")
        )
    }

    @Test
    fun `resolveTrackMetadata extracts multiple artists and album from next response and persists to Room`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val nextPayload = """
            {
              "contents": {
                "singleColumnMusicWatchNextResultsRenderer": {
                  "tabbedRenderer": {
                    "watchNextTabbedResultsRenderer": {
                      "tabs": [
                        {
                          "tabRenderer": {
                            "content": {
                              "musicQueueRenderer": {
                                "content": {
                                  "playlistPanelRenderer": {
                                    "contents": [
                                      {
                                        "playlistPanelVideoRenderer": {
                                          "videoId": "collab_vid_1",
                                          "title": { "runs": [ { "text": "Collab Hit" } ] },
                                          "longBylineText": {
                                            "runs": [
                                              {
                                                "text": "Artist One",
                                                "navigationEndpoint": {
                                                  "browseEndpoint": {
                                                    "browseId": "UC_ARTIST_ONE",
                                                    "browseEndpointContextSupportedConfigs": {
                                                      "browseEndpointContextMusicConfig": { "pageType": "MUSIC_PAGE_TYPE_ARTIST" }
                                                    }
                                                  }
                                                }
                                              },
                                              { "text": " & " },
                                              {
                                                "text": "Artist Two",
                                                "navigationEndpoint": {
                                                  "browseEndpoint": {
                                                    "browseId": "UC_ARTIST_TWO",
                                                    "browseEndpointContextSupportedConfigs": {
                                                      "browseEndpointContextMusicConfig": { "pageType": "MUSIC_PAGE_TYPE_ARTIST" }
                                                    }
                                                  }
                                                }
                                              },
                                              { "text": " • " },
                                              {
                                                "text": "Deluxe LP",
                                                "navigationEndpoint": {
                                                  "browseEndpoint": {
                                                    "browseId": "MPREb_DELUXE_LP",
                                                    "browseEndpointContextSupportedConfigs": {
                                                      "browseEndpointContextMusicConfig": { "pageType": "MUSIC_PAGE_TYPE_ALBUM" }
                                                    }
                                                  }
                                                }
                                              }
                                            ]
                                          },
                                          "lengthText": { "runs": [ { "text": "3:21" } ] }
                                        }
                                      }
                                    ]
                                  }
                                }
                              }
                            }
                          }
                        }
                      ]
                    }
                  }
                }
              }
            }
        """.trimIndent()

        val testClient = OkHttpClient.Builder()
            .addInterceptor { chain ->
                val url = chain.request().url.toString()
                if (url.contains("youtubei/v1/next")) {
                    Response.Builder()
                        .request(chain.request())
                        .protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("OK")
                        .body(nextPayload.toResponseBody("application/json".toMediaType()))
                        .build()
                } else {
                    Response.Builder()
                        .request(chain.request())
                        .protocol(Protocol.HTTP_1_1)
                        .code(404)
                        .message("Offline")
                        .body("".toResponseBody("text/plain".toMediaType()))
                        .build()
                }
            }
            .build()

        val db = Room.inMemoryDatabaseBuilder(context, SonoraDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val dao = db.libraryDao()
        val repo = MusicRepository(
            ytMusicClient = YouTubeMusicClient(testClient),
            streamResolver = StreamResolver(testClient),
            spotifyImporter = SpotifyImporter(testClient),
            libraryDao = dao,
            lyricsResolver = LyricsResolver(testClient, context),
            newPipeSearchClient = null
        )

        try {
            dao.insertLikedSong(
                com.example.data.local.LikedSongEntity(
                    videoId = "collab_vid_1",
                    title = "Collab Hit",
                    artist = "Artist One & Artist Two",
                    artistId = null,
                    album = null,
                    albumId = null,
                    artworkUrl = "",
                    durationText = "3:21",
                    durationMs = 201_000L
                )
            )
            val plId = dao.insertPlaylist(com.example.data.local.PlaylistEntity(name = "Mi Mix"))
            dao.insertPlaylistTrack(
                com.example.data.local.PlaylistTrackEntity(
                    playlistId = plId,
                    videoId = "collab_vid_1",
                    title = "Collab Hit",
                    artist = "Artist One & Artist Two",
                    artistId = null,
                    album = null,
                    albumId = null,
                    artworkUrl = "",
                    durationText = "3:21",
                    durationMs = 201_000L
                )
            )
            dao.insertRecentTrack(
                com.example.data.local.RecentTrackEntity(
                    videoId = "collab_vid_1",
                    title = "Collab Hit",
                    artist = "Artist One & Artist Two",
                    artistId = null,
                    album = null,
                    albumId = null,
                    artworkUrl = "",
                    durationText = "3:21",
                    durationMs = 201_000L
                )
            )

            val resolved = repo.resolveTrackMetadata(
                Track(
                    videoId = "collab_vid_1",
                    title = "Collab Hit",
                    artist = "Artist One & Artist Two",
                    durationMs = 201_000L
                )
            )

            assertEquals("UC_ARTIST_ONE", resolved.artistId)
            assertEquals("Deluxe LP", resolved.album)
            assertEquals("MPREb_DELUXE_LP", resolved.albumId)
            assertEquals(2, resolved.artists.size)
            assertEquals("UC_ARTIST_ONE", resolved.artists[0].browseId)
            assertEquals("UC_ARTIST_TWO", resolved.artists[1].browseId)

            val savedLike = dao.getAllLikedSongsOnce().first { it.videoId == "collab_vid_1" }
            assertEquals("UC_ARTIST_ONE", savedLike.artistId)
            assertEquals("MPREb_DELUXE_LP", savedLike.albumId)
            assertEquals("Deluxe LP", savedLike.album)

            val savedPl = dao.getPlaylistTracksOnce(plId).first { it.videoId == "collab_vid_1" }
            assertEquals("UC_ARTIST_ONE", savedPl.artistId)
            assertEquals("MPREb_DELUXE_LP", savedPl.albumId)
        } finally {
            db.close()
        }
    }

    @Test
    fun `resolveTrackMetadata falls back to SONGS search when next omits artistId and albumId`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val searchPayload = """
            {
              "contents": {
                "tabbedSearchResultsRenderer": {
                  "tabs": [
                    {
                      "tabRenderer": {
                        "content": {
                          "sectionListRenderer": {
                            "contents": [
                              {
                                "musicShelfRenderer": {
                                  "contents": [
                                    {
                                      "musicResponsiveListItemRenderer": {
                                        "playlistItemData": { "videoId": "missing_meta_1" },
                                        "flexColumns": [
                                          {
                                            "musicResponsiveListItemFlexColumnRenderer": {
                                              "text": {
                                                "runs": [
                                                  {
                                                    "text": "Lovers",
                                                    "navigationEndpoint": { "watchEndpoint": { "videoId": "missing_meta_1" } }
                                                  }
                                                ]
                                              }
                                            }
                                          },
                                          {
                                            "musicResponsiveListItemFlexColumnRenderer": {
                                              "text": {
                                                "runs": [
                                                  {
                                                    "text": "Anna of the North",
                                                    "navigationEndpoint": { "browseEndpoint": { "browseId": "UC_ANNA_FALLBACK" } }
                                                  },
                                                  { "text": " • " },
                                                  {
                                                    "text": "Lovers",
                                                    "navigationEndpoint": { "browseEndpoint": { "browseId": "MPREb_LOVERS_ALBUM" } }
                                                  }
                                                ]
                                              }
                                            }
                                          }
                                        ]
                                      }
                                    }
                                  ]
                                }
                              }
                            ]
                          }
                        }
                      }
                    }
                  ]
                }
              }
            }
        """.trimIndent()

        val testClient = OkHttpClient.Builder()
            .addInterceptor { chain ->
                val url = chain.request().url.toString()
                when {
                    url.contains("youtubei/v1/next") -> Response.Builder()
                        .request(chain.request())
                        .protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("OK")
                        .body("{}".toResponseBody("application/json".toMediaType()))
                        .build()
                    url.contains("youtubei/v1/search") -> Response.Builder()
                        .request(chain.request())
                        .protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("OK")
                        .body(searchPayload.toResponseBody("application/json".toMediaType()))
                        .build()
                    else -> Response.Builder()
                        .request(chain.request())
                        .protocol(Protocol.HTTP_1_1)
                        .code(404)
                        .message("Offline")
                        .body("".toResponseBody("text/plain".toMediaType()))
                        .build()
                }
            }
            .build()

        val db = Room.inMemoryDatabaseBuilder(context, SonoraDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val repo = MusicRepository(
            ytMusicClient = YouTubeMusicClient(testClient),
            streamResolver = StreamResolver(testClient),
            spotifyImporter = SpotifyImporter(testClient),
            libraryDao = db.libraryDao(),
            lyricsResolver = LyricsResolver(testClient, context),
            newPipeSearchClient = null
        )

        try {
            val resolved = repo.resolveTrackMetadata(
                Track(
                    videoId = "missing_meta_1",
                    title = "Lovers",
                    artist = "Anna of the North",
                    durationMs = 235_000L
                )
            )
            assertEquals("UC_ANNA_FALLBACK", resolved.artistId)
            assertEquals("Lovers", resolved.album)
            assertEquals("MPREb_LOVERS_ALBUM", resolved.albumId)
        } finally {
            db.close()
        }
    }
}
