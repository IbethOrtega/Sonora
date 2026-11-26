package com.example

import com.example.data.local.ListeningHistoryEntity
import com.example.data.repository.HomeFeedCurator
import com.example.data.repository.MusicRepository
import com.example.data.repository.QueueBuilder
import com.example.data.repository.TasteProfileBuilder
import com.example.model.CanvasArtwork
import com.example.model.CanvasSource
import com.example.model.HomeShelf
import com.example.model.ListeningHistoryEntry
import com.example.model.StatsPeriod
import com.example.model.Track
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

class SonoraComprehensiveFeaturesTest {

    private fun track(
        id: String,
        title: String = "Track $id",
        artist: String = "Artist $id",
        album: String? = "Album $id",
        durationMs: Long = 210_000L
    ) = Track(
        videoId = id,
        title = title,
        artist = artist,
        album = album,
        artworkUrl = "https://example.com/$id.jpg",
        durationText = "3:30",
        durationMs = durationMs
    )

    @Test
    fun `CanvasArtwork splitNormalizedArtists and matches normalize accents punctuation separators and conditional album`() {
        val artists = CanvasArtwork.splitNormalizedArtists(
            "Rosalía, Bad Bunny & Jhayco feat. Feid ft. Karol G x Rauw Alejandro with Tainy"
        )
        assertEquals(
            listOf("rosalia", "bad bunny", "jhayco", "feid", "karol g", "rauw alejandro", "tainy"),
            artists
        )

        val canvas = CanvasArtwork(
            videoUrl = "https://cdn.example.com/motion_square.mp4",
            portraitUrl = "https://cdn.example.com/motion_tall.mp4",
            matchedTitle = "Cancion Bonita (Remastered 2024)",
            matchedArtist = "Carlos Vives, Ricky Martin",
            album = "Cumbiana II",
            source = CanvasSource.APPLE
        )

        // Matching normalized title/artist with accents, punctuation, brackets, and "&"
        assertTrue(
            canvas.matches(
                trackTitle = "Canción Bonita!",
                trackArtist = "Carlos Vives & Ricky Martin",
                trackAlbum = "Cumbiana II (Deluxe)"
            )
        )

        // Conditional album: passes when either side omits the album
        assertTrue(
            canvas.matches(
                trackTitle = "Canción Bonita",
                trackArtist = "Carlos Vives feat. Ricky Martin",
                trackAlbum = null
            )
        )
        val noAlbumCanvas = canvas.copy(album = null)
        assertTrue(
            noAlbumCanvas.matches(
                trackTitle = "Canción Bonita",
                trackArtist = "Carlos Vives",
                trackAlbum = "Otro Álbum"
            )
        )

        // Conditional album: rejects when BOTH know the album and they differ
        assertFalse(
            canvas.matches(
                trackTitle = "Canción Bonita",
                trackArtist = "Carlos Vives, Ricky Martin",
                trackAlbum = "Vives"
            )
        )

        // Rejects wrong artist or wrong title or blank streams
        assertFalse(canvas.matches("Canción Bonita", "Shakira", "Cumbiana II"))
        assertFalse(canvas.matches("Otro Tema", "Carlos Vives, Ricky Martin", "Cumbiana II"))
        assertFalse(
            canvas.copy(videoUrl = "", portraitUrl = null, fallbackUrl = null)
                .matches("Canción Bonita", "Carlos Vives, Ricky Martin", "Cumbiana II")
        )
    }

    @Test
    fun `TasteProfileBuilder applies 30-day half-life decay and isolates 1-hour recent playback exclusion`() {
        val nowMs = 1_760_000_000_000L
        val dayMs = 86_400_000L

        assertEquals(1.0, TasteProfileBuilder.decayWeight(nowMs, nowMs), 0.0001)
        assertEquals(0.5, TasteProfileBuilder.decayWeight(nowMs - 30L * dayMs, nowMs), 0.0001)
        assertEquals(0.25, TasteProfileBuilder.decayWeight(nowMs - 60L * dayMs, nowMs), 0.0001)

        val history = listOf(
            ListeningHistoryEntity(
                videoId = "recent_30m",
                title = "Lovers",
                artist = "Anna of the North & Gus Dapperton",
                album = "Lovers",
                durationMs = 200_000L,
                playedAtMs = nowMs - 30L * 60_000L,
                listenedMs = 200_000L
            ),
            ListeningHistoryEntity(
                videoId = "older_3h",
                title = "Dream Girl",
                artist = "Anna of the North",
                album = "Dream Girl",
                durationMs = 200_000L,
                playedAtMs = nowMs - 3L * 3_600_000L,
                listenedMs = 200_000L
            ),
            ListeningHistoryEntity(
                videoId = "stale_60d",
                title = "Old Track",
                artist = "Stale Band",
                album = "Archive",
                durationMs = 200_000L,
                playedAtMs = nowMs - 60L * dayMs,
                listenedMs = 200_000L
            )
        )

        val profile = TasteProfileBuilder.build(
            historyRows = history,
            likedTracks = emptyList(),
            nowMs = nowMs
        )

        assertTrue("Expected 30-min track in recent1HourVideoIds", "recent_30m" in profile.recent1HourVideoIds)
        assertFalse("Expected 3-hour track excluded from recent1HourVideoIds", "older_3h" in profile.recent1HourVideoIds)
        assertTrue(
            "Expected 1-hour dedupe key for Lovers",
            QueueBuilder.normalizeDedupeKey("Lovers", "Anna of the North & Gus Dapperton") in profile.recent1HourDedupeKeys
        )
        assertTrue(profile.weightForArtist("Anna of the North") > profile.weightForArtist("Stale Band"))
        assertTrue(
            "Collaborative + session tracks should populate related artists",
            profile.topRelatedArtistsForSeed(track("s", "Dream Girl", "Anna of the North")).isNotEmpty()
        )
    }

    @Test
    fun `QueueBuilder curates Lovers by Anna of the North rejecting homonyms 1h repeats and 3 consecutive same artist`() {
        val nowMs = 1_760_000_000_000L
        val seed = track("seed_lovers", "Lovers", "Anna of the North", album = "Lovers", durationMs = 235_000L)

        val recentPlay = ListeningHistoryEntity(
            videoId = "recent_1h_track",
            title = "Oslo",
            artist = "Anna of the North",
            durationMs = 210_000L,
            playedAtMs = nowMs - 15L * 60_000L,
            listenedMs = 210_000L
        )
        val profile = TasteProfileBuilder.build(
            historyRows = listOf(recentPlay),
            likedTracks = listOf(track("liked_1", "Someone", "Anna of the North")),
            nowMs = nowMs
        )

        val queue = QueueBuilder.buildCohesiveQueue(
            QueueBuilder.CohesiveQueueInput(
                seedTrack = seed,
                upNextTracks = listOf(
                    track("homonym_timecop", "Lovers", "Timecop1983"),
                    track("homonym_variant", "Lovers (Official Video)", "Exile"),
                    track("trap_title_word", "Lovers Rock Cover", "Tribute Band"),
                    track("recent_1h_track", "Oslo", "Anna of the North"),
                    track("rel_1", "Midnight City", "M83"),
                    track("rel_2", "Oblivion", "Grimes"),
                    track("rel_3", "Genesis", "Grimes"),
                    track("rel_4", "Nightcall", "Kavinsky"),
                    track("rel_5", "Gooey", "Glass Animals"),
                    track("rel_6", "Breathe Deeper", "Tame Impala")
                ),
                artistSongs = listOf(
                    track("anna_1", "Someone", "Anna of the North"),
                    track("anna_2", "Dream Girl", "Anna of the North"),
                    track("anna_3", "Playing Games", "Anna of the North"),
                    track("anna_4", "Leaning On Myself", "Anna of the North"),
                    track("anna_5", "Thank Me Later", "Anna of the North")
                ),
                relatedArtistTracks = listOf(
                    track("taste_1", "Teenage Blue", "Dreamgirl"),
                    track("taste_2", "Chinatown", "Bleachers")
                ),
                tasteProfile = profile,
                limit = 12
            )
        )

        val ids = queue.map { it.videoId }
        assertFalse("Homonym 'Lovers' by Timecop1983 must be excluded", "homonym_timecop" in ids)
        assertFalse("Homonym video 'Lovers' by Exile must be excluded", "homonym_variant" in ids)
        assertFalse("Cover track must be excluded", "trap_title_word" in ids)
        assertFalse("Track played within last 1 hour must be excluded", "recent_1h_track" in ids)
        assertTrue("Expected non-empty cohesive queue", queue.size >= 6)

        // Verify never more than 2 consecutive tracks by the same primary artist (including seed at index 0)
        val fullSequence = listOf(seed) + queue
        for (i in 0..(fullSequence.size - 3)) {
            val a0 = QueueBuilder.splitArtistTokens(fullSequence[i].artist).firstOrNull()
            val a1 = QueueBuilder.splitArtistTokens(fullSequence[i + 1].artist).firstOrNull()
            val a2 = QueueBuilder.splitArtistTokens(fullSequence[i + 2].artist).firstOrNull()
            assertFalse(
                "Found 3 consecutive songs by $a0 at index $i",
                !a0.isNullOrBlank() && a0 == a1 && a1 == a2
            )
        }
    }

    @Test
    fun `HomeFeedCurator provides deterministic daily rotation 24h exclusion and cross-shelf deduplication`() {
        val zone = ZoneId.of("UTC")
        val dayOneNoon = ZonedDateTime.of(2026, 11, 20, 12, 0, 0, 0, zone).toInstant().toEpochMilli()
        val dayTwoNoon = ZonedDateTime.of(2026, 11, 21, 12, 0, 0, 0, zone).toInstant().toEpochMilli()

        val seedDay1A = HomeFeedCurator.dailyRotationSeed(dayOneNoon, pullToRefreshNonce = 0)
        val seedDay1B = HomeFeedCurator.dailyRotationSeed(dayOneNoon + 3_600_000L, pullToRefreshNonce = 0)
        val seedDay1Refresh = HomeFeedCurator.dailyRotationSeed(dayOneNoon, pullToRefreshNonce = 1)
        val seedDay2 = HomeFeedCurator.dailyRotationSeed(dayTwoNoon, pullToRefreshNonce = 0)

        assertEquals(seedDay1A, seedDay1B)
        assertNotEquals(seedDay1A, seedDay1Refresh)
        assertNotEquals(seedDay1A, seedDay2)

        val items = (1..8).map { "item_$it" }
        assertEquals(
            HomeFeedCurator.rotateDeterministically(items, seedDay1A),
            HomeFeedCurator.rotateDeterministically(items, seedDay1B)
        )
        assertNotEquals(
            HomeFeedCurator.rotateDeterministically(items, seedDay1A),
            HomeFeedCurator.rotateDeterministically(items, seedDay1Refresh)
        )

        val historyRows = listOf(
            ListeningHistoryEntity(
                videoId = "played_3h",
                title = "Recent Song",
                artist = "Artist A",
                playedAtMs = dayOneNoon - 3L * 3_600_000L
            ),
            ListeningHistoryEntity(
                videoId = "played_48h",
                title = "Older Song",
                artist = "Artist B",
                playedAtMs = dayOneNoon - 48L * 3_600_000L
            )
        )
        val (excludedIds, excludedKeys) = HomeFeedCurator.buildPlayedLast24hExclusion(
            historyRows = historyRows,
            nowMs = dayOneNoon
        )
        assertTrue("played_3h" in excludedIds)
        assertFalse("played_48h" in excludedIds)

        val shelves = listOf(
            HomeShelf(
                title = "Volver a escuchar",
                tracks = listOf(
                    track("played_3h", "Recent Song", "Artist A"),
                    track("dup_track", "Shared Track", "Artist C")
                )
            ),
            HomeShelf(
                title = "Descubrimiento diario",
                tracks = listOf(
                    track("played_3h", "Recent Song", "Artist A"),
                    track("dup_track", "Shared Track", "Artist C"),
                    track("fresh_disc", "Fresh Discovery", "Artist D")
                )
            )
        )
        val curatedShelves = HomeFeedCurator.deduplicateHomeShelves(
            shelves = shelves,
            excluded24hIds = excludedIds,
            excluded24hDedupeKeys = excludedKeys
        )

        assertEquals(2, curatedShelves.size)
        assertEquals(
            listOf("played_3h", "dup_track"),
            curatedShelves[0].tracks.map { it.videoId }
        )
        assertEquals(
            listOf("fresh_disc"),
            curatedShelves[1].tracks.map { it.videoId }
        )
    }

    @Test
    fun `MusicRepository groupHistoryEntriesByDay and computeStatsSummaryForHistory compute Spanish labels streaks and monthlyHours`() {
        val zone = ZoneId.of("UTC")
        val today = LocalDate.of(2026, 11, 26)
        val nowMs = ZonedDateTime.of(2026, 11, 26, 20, 0, 0, 0, zone).toInstant().toEpochMilli()

        fun entryAt(id: Long, date: LocalDate, hour: Int, videoId: String, artist: String, minutes: Long): ListeningHistoryEntry {
            val ms = ZonedDateTime.of(date.year, date.monthValue, date.dayOfMonth, hour, 0, 0, 0, zone)
                .toInstant()
                .toEpochMilli()
            return ListeningHistoryEntry(
                id = id,
                track = track(videoId, "Song $videoId", artist, durationMs = minutes * 60_000L),
                playedAtMs = ms,
                listenedMs = minutes * 60_000L
            )
        }

        // Historical 4-day streak (Nov 10..13) + active 2-day streak (Nov 25 & Nov 26)
        val entries = listOf(
            entryAt(1L, today, 20, "v_today_1", "Anna of the North", 60L),
            entryAt(2L, today, 20, "v_today_2", "Anna of the North", 30L),
            entryAt(3L, today.minusDays(1), 15, "v_yest", "M83", 30L),
            entryAt(4L, LocalDate.of(2026, 11, 13), 20, "v_h4", "M83", 40L),
            entryAt(5L, LocalDate.of(2026, 11, 12), 18, "v_h3", "Grimes", 40L),
            entryAt(6L, LocalDate.of(2026, 11, 11), 14, "v_h2", "Grimes", 40L),
            entryAt(7L, LocalDate.of(2026, 11, 10), 10, "v_h1", "Anna of the North", 60L)
        )

        val groups = MusicRepository.groupHistoryEntriesByDay(entries, zoneId = zone, today = today)
        assertEquals("Hoy", groups[0].dayLabel)
        assertEquals("Ayer", groups[1].dayLabel)
        assertEquals("2026-11-13", groups[2].dayKey)
        assertTrue("Expected Spanish month 'nov' in formatted date label: ${groups[2].dayLabel}", groups[2].dayLabel.contains("nov", ignoreCase = true))

        val summary = MusicRepository.computeStatsSummaryForHistory(
            entries = entries,
            period = StatsPeriod.ALL,
            zoneId = zone,
            nowMs = nowMs
        )
        assertEquals(7, summary.totalPlays)
        assertEquals(7, summary.uniqueTracks)
        assertEquals(300L, summary.estimatedMinutes)
        assertEquals(2, summary.currentStreakDays)
        assertEquals(4, summary.longestStreakDays)
        assertEquals(20, summary.peakHour)
        assertEquals(6, summary.monthlyHours.size)
        val novStat = summary.monthlyHours.last()
        assertEquals("2026-11", novStat.yearMonthKey)
        assertEquals(300L, novStat.minutes)
        assertEquals(5.0f, novStat.hours, 0.01f)
    }
}
