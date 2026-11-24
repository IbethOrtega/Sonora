package com.example

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.ListeningHistoryEntity
import com.example.data.local.SonoraDatabase
import com.example.data.local.YtLikedSongEntity
import com.example.data.repository.MusicRepository
import com.example.data.repository.QueueBuilder
import com.example.model.ListeningHistoryEntry
import com.example.model.QueueMoodFilter
import com.example.model.StatsPeriod
import com.example.model.Track
import com.example.player.ListeningRecorder
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

@RunWith(RobolectricTestRunner::class)
class Stage1PersistenceAndCoherenceTest {

    private lateinit var db: SonoraDatabase

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, SonoraDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `listening recorder commits at 30s ignores pause idle time and seek jumps`() = runBlocking {
        var fakeClock = 1_000_000L
        var committedCount = 0
        var committedListenedMs = 0L
        var updatedListenedMs = 0L

        val recorder = ListeningRecorder(
            nowMs = { fakeClock },
            onCommitPlay = { _, _, listenedMs ->
                committedCount++
                committedListenedMs = listenedMs
                42L
            },
            onUpdateListenedMs = { _, listenedMs ->
                updatedListenedMs = listenedMs
            }
        )

        val track = Track(
            videoId = "vid_rec_1",
            title = "Midnight City",
            artist = "M83",
            durationMs = 240_000L
        )
        recorder.onTrackChanged(track, queueIndex = 0, isPlaying = true)

        // Advance 20 seconds in 500ms ticks -> must NOT commit yet (<30s)
        repeat(40) {
            fakeClock += 500L
            recorder.onSampleTick(track, queueIndex = 0, isPlaying = true, measuredDurationMs = 240_000L)
        }
        assertEquals(0, committedCount)
        assertEquals(20_000L, recorder.currentAccumulatedMs())

        // Pause for 5 minutes -> must NOT accrue listening time
        recorder.onPlayingChanged(false)
        fakeClock += 300_000L
        recorder.onSampleTick(track, queueIndex = 0, isPlaying = false, measuredDurationMs = 240_000L)
        assertEquals(20_000L, recorder.currentAccumulatedMs())

        // Resume and seek -> jump anchor resets cleanly
        recorder.onPlayingChanged(true)
        fakeClock += 45_000L
        recorder.onSeek(isPlaying = true)
        fakeClock += 500L
        recorder.onSampleTick(track, queueIndex = 0, isPlaying = true, measuredDurationMs = 240_000L)
        assertEquals(0, committedCount)

        // Advance remaining 10 seconds cleanly -> reaches 30.5s and commits once
        repeat(20) {
            fakeClock += 500L
            recorder.onSampleTick(track, queueIndex = 0, isPlaying = true, measuredDurationMs = 240_000L)
        }
        assertEquals(1, committedCount)
        assertTrue(committedListenedMs >= 30_000L)

        // Additional playback + flush updates listenedMs on committed row
        repeat(4) {
            fakeClock += 500L
            recorder.onSampleTick(track, queueIndex = 0, isPlaying = true, measuredDurationMs = 240_000L)
        }
        recorder.flush()
        assertTrue(updatedListenedMs > committedListenedMs)
    }

    @Test
    fun `listening recorder commits short track once half duration reached`() = runBlocking {
        var fakeClock = 500_000L
        var committed = 0
        val recorder = ListeningRecorder(
            nowMs = { fakeClock },
            onCommitPlay = { _, _, _ ->
                committed++
                10L
            }
        )
        val shortTrack = Track(videoId = "short_1", title = "Intro", artist = "Band", durationMs = 24_000L)
        recorder.onTrackChanged(shortTrack, 0, isPlaying = true)
        repeat(24) {
            fakeClock += 500L
            recorder.onSampleTick(shortTrack, 0, isPlaying = true, measuredDurationMs = 24_000L)
        }
        assertEquals(1, committed)
    }

    @Test
    fun `history grouping and replay stats compute day labels periods peak hour and albums`() {
        val zone = ZoneOffset.UTC
        val today = LocalDate.of(2026, 11, 24)
        val todayNoon = today.atTime(15, 0).toInstant(zone).toEpochMilli()
        val yesterdayEvening = today.minusDays(1).atTime(21, 15).toInstant(zone).toEpochMilli()
        val twoMonthsAgo = today.minusDays(60).atTime(21, 30).toInstant(zone).toEpochMilli()

        val trackA = Track("vA", "Song A", "Artist One", album = "Album Alpha", durationMs = 180_000L)
        val trackB = Track("vB", "Song B", "Artist Two", album = "Album Beta", durationMs = 240_000L)

        val entries = listOf(
            ListeningHistoryEntry(1L, trackA, todayNoon, 180_000L),
            ListeningHistoryEntry(2L, trackA, yesterdayEvening, 120_000L),
            ListeningHistoryEntry(3L, trackB, twoMonthsAgo, 240_000L)
        )

        val groups = MusicRepository.groupHistoryEntriesByDay(entries, zoneId = zone, today = today)
        assertEquals(3, groups.size)
        assertEquals("Hoy", groups[0].dayLabel)
        assertEquals("Ayer", groups[1].dayLabel)

        val nowMs = today.atTime(23, 59).toInstant(zone).toEpochMilli()
        val weekStats = MusicRepository.computeStatsSummaryForHistory(entries, StatsPeriod.WEEK, zone, nowMs)
        assertEquals(2, weekStats.totalPlays)
        assertEquals(1, weekStats.uniqueTracks)
        assertEquals("Album Alpha", weekStats.topAlbums.first().album)

        val allStats = MusicRepository.computeStatsSummaryForHistory(entries, StatsPeriod.ALL, zone, nowMs)
        assertEquals(3, allStats.totalPlays)
        assertEquals(21, allStats.peakHour)
        assertEquals(2, allStats.topAlbums.size)
    }

    @Test
    fun `queue builder deduplicates audio vs video enforces artist caps and prunes low affinity`() {
        val seed = Track("seed_1", "Blinding Lights", "The Weeknd", durationMs = 200_000L)
        val upNext = listOf(
            // Duplicate of seed in video form -> must be filtered
            Track("dup_seed_mv", "Blinding Lights (Official Video)", "The Weeknd", durationMs = 220_000L),
            // Same song twice: Official Video + studio audio -> studio audio must win
            Track("sa_mv", "Save Your Tears (Official Music Video)", "The Weeknd", durationMs = 215_000L),
            Track("sa_audio", "Save Your Tears", "The Weeknd", durationMs = 215_000L),
            // Unrelated artist appearing > 2 times -> capped at 2
            Track("other_1", "Track 1", "Daft Punk", durationMs = 200_000L),
            Track("other_2", "Track 2", "Daft Punk", durationMs = 200_000L),
            Track("other_3", "Track 3", "Daft Punk", durationMs = 200_000L),
            // Unrelated low affinity (extraSeed with video penalty + duration outlier) -> pruned
            Track("outlier_1", "Podcast Clip (Official Video)", "Random Channel", durationMs = 15_000L)
        )
        val extraSeed = listOf(
            Track("extra_low", "Random Video Clip (Official Video)", "Unknown Band", durationMs = 20_000L)
        )

        val built = QueueBuilder.buildCohesiveQueue(
            QueueBuilder.CohesiveQueueInput(
                seedTrack = seed,
                upNextTracks = upNext,
                extraSeedUpNextTracks = extraSeed,
                likedArtists = setOf("Daft Punk"),
                moodFilter = QueueMoodFilter.BALANCED,
                minAffinityScore = 10
            )
        )

        val builtIds = built.map { it.videoId }
        assertFalse(builtIds.contains("dup_seed_mv"))
        assertTrue(builtIds.contains("sa_audio"))
        assertFalse(builtIds.contains("sa_mv"))
        assertEquals(2, built.count { it.artist == "Daft Punk" })
        assertFalse(builtIds.contains("outlier_1"))
        assertFalse(builtIds.contains("extra_low"))
    }

    @Test
    fun `room v8 listening history is append-only and yt liked songs replaces and clears cleanly`() = runBlocking {
        val dao = db.libraryDao()
        val id1 = dao.insertListeningHistory(
            ListeningHistoryEntity(
                videoId = "v1",
                title = "Track 1",
                artist = "Artist 1",
                playedAtMs = 1_000L,
                listenedMs = 32_000L
            )
        )
        val id2 = dao.insertListeningHistory(
            ListeningHistoryEntity(
                videoId = "v1",
                title = "Track 1",
                artist = "Artist 1",
                playedAtMs = 2_000L,
                listenedMs = 45_000L
            )
        )
        dao.updateListeningHistoryDuration(id2, 95_000L)

        val historyRows = dao.getAllListeningHistoryOnce()
        assertEquals(2, historyRows.size)
        assertTrue(id1 != id2)
        assertEquals(95_000L, historyRows.first { it.id == id2 }.listenedMs)

        val batch1 = (1..35).map { idx ->
            YtLikedSongEntity(
                videoId = "yt_$idx",
                title = "Liked $idx",
                artist = "Artist $idx",
                positionIndex = idx - 1,
                isExplicit = idx % 2 == 0
            )
        }
        dao.replaceYtLikedSongs(batch1)
        val observed = dao.observeYtLikedSongs().first()
        assertEquals(35, observed.size)
        assertTrue(observed[1].isExplicit)

        dao.clearYtLikedSongs()
        assertTrue(dao.getYtLikedSongsOnce().isEmpty())
    }
}
