package com.example.player

import android.content.Context
import android.os.Looper
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.Timeline
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
import com.example.model.PersistedQueueState
import com.example.model.Track
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.SettableFuture
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File
import java.lang.reflect.Proxy
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class PlayerControllerSyncTest {

    private val testJson = Json { encodeDefaults = true }

    private lateinit var context: Context
    private lateinit var db: SonoraDatabase
    private lateinit var prefs: SonoraPreferences
    private lateinit var controller: PlayerController

    private class FakePlayerController {
        val items = mutableListOf<MediaItem>()
        var currentIndex: Int = 0
        var currentPositionMs: Long = 0L
        var durationMs: Long = 200_000L
        var isPlayingFlag: Boolean = false
        var playbackStateVal: Int = Player.STATE_READY
        var shuffleEnabled: Boolean = false
        var repeatModeVal: Int = Player.REPEAT_MODE_OFF
        val listeners = mutableListOf<Player.Listener>()

        var setMediaItemsCallCount: Int = 0
        var seekToNextCount: Int = 0
        var seekToPrevCount: Int = 0
        val seekToCalls = mutableListOf<Pair<Int, Long>>()

        val player: Player = Proxy.newProxyInstance(
            Player::class.java.classLoader,
            arrayOf(Player::class.java)
        ) { _, method, args ->
            when (method.name) {
                "addListener" -> {
                    (args?.getOrNull(0) as? Player.Listener)?.let { listeners.add(it) }
                    Unit
                }
                "removeListener" -> {
                    (args?.getOrNull(0) as? Player.Listener)?.let { listeners.remove(it) }
                    Unit
                }
                "getMediaItemCount" -> items.size
                "getMediaItemAt" -> {
                    val idx = args!![0] as Int
                    items[idx]
                }
                "getCurrentMediaItem" -> items.getOrNull(currentIndex)
                "getCurrentMediaItemIndex" -> if (items.isEmpty()) 0 else currentIndex
                "isPlaying" -> isPlayingFlag
                "getPlaybackState" -> playbackStateVal
                "getCurrentPosition" -> currentPositionMs
                "getDuration" -> durationMs
                "getShuffleModeEnabled" -> shuffleEnabled
                "setShuffleModeEnabled" -> {
                    shuffleEnabled = args!![0] as Boolean
                    listeners.toList().forEach { it.onShuffleModeEnabledChanged(shuffleEnabled) }
                    Unit
                }
                "getRepeatMode" -> repeatModeVal
                "setRepeatMode" -> {
                    repeatModeVal = args!![0] as Int
                    listeners.toList().forEach { it.onRepeatModeChanged(repeatModeVal) }
                    Unit
                }
                "hasNextMediaItem" -> items.isNotEmpty() && (currentIndex < items.lastIndex || (repeatModeVal == Player.REPEAT_MODE_ALL && items.size > 1))
                "hasPreviousMediaItem" -> items.isNotEmpty() && (currentIndex > 0 || (repeatModeVal == Player.REPEAT_MODE_ALL && items.size > 1))
                "seekTo" -> {
                    if (args != null && args.size == 1) {
                        currentPositionMs = (args[0] as Long).coerceAtLeast(0L)
                    } else if (args != null && args.size == 2) {
                        val idx = args[0] as Int
                        val pos = (args[1] as Long).coerceAtLeast(0L)
                        seekToCalls.add(idx to pos)
                        if (idx in items.indices) {
                            currentIndex = idx
                        }
                        currentPositionMs = pos
                    }
                    Unit
                }
                "seekToNextMediaItem", "seekToNext" -> {
                    seekToNextCount++
                    if (currentIndex < items.lastIndex) {
                        currentIndex++
                        currentPositionMs = 0L
                    } else if (repeatModeVal == Player.REPEAT_MODE_ALL && items.size > 1) {
                        currentIndex = 0
                        currentPositionMs = 0L
                    }
                    Unit
                }
                "seekToPreviousMediaItem", "seekToPrevious" -> {
                    seekToPrevCount++
                    if (currentIndex > 0) {
                        currentIndex--
                        currentPositionMs = 0L
                    } else if (repeatModeVal == Player.REPEAT_MODE_ALL && items.size > 1) {
                        currentIndex = items.lastIndex
                        currentPositionMs = 0L
                    }
                    Unit
                }
                "setMediaItems" -> {
                    setMediaItemsCallCount++
                    val newItems = (args?.getOrNull(0) as? List<*>)?.filterIsInstance<MediaItem>().orEmpty()
                    items.clear()
                    items.addAll(newItems)
                    if (args != null && args.size >= 3) {
                        currentIndex = (args[1] as Int).coerceIn(0, (items.size - 1).coerceAtLeast(0))
                        currentPositionMs = (args[2] as Long).coerceAtLeast(0L)
                    }
                    Unit
                }
                "addMediaItem" -> {
                    if (args?.size == 1) {
                        (args[0] as? MediaItem)?.let { items.add(it) }
                    } else if (args?.size == 2) {
                        val idx = (args[0] as Int).coerceIn(0, items.size)
                        (args[1] as? MediaItem)?.let { items.add(idx, it) }
                    }
                    Unit
                }
                "addMediaItems" -> {
                    val list = (args?.lastOrNull() as? List<*>)?.filterIsInstance<MediaItem>().orEmpty()
                    if (args?.size == 1) {
                        items.addAll(list)
                    } else if (args?.size == 2) {
                        val idx = (args[0] as Int).coerceIn(0, items.size)
                        items.addAll(idx, list)
                    }
                    Unit
                }
                "removeMediaItem" -> {
                    val idx = args!![0] as Int
                    if (idx in items.indices) items.removeAt(idx)
                    Unit
                }
                "removeMediaItems" -> {
                    val from = (args!![0] as Int).coerceIn(0, items.size)
                    val to = (args[1] as Int).coerceIn(from, items.size)
                    if (from < to) items.subList(from, to).clear()
                    Unit
                }
                "replaceMediaItem" -> {
                    val idx = args!![0] as Int
                    val item = args[1] as MediaItem
                    if (idx in items.indices) items[idx] = item
                    Unit
                }
                "prepare" -> Unit
                "play" -> {
                    isPlayingFlag = true
                    Unit
                }
                "pause" -> {
                    isPlayingFlag = false
                    Unit
                }
                "stop" -> {
                    isPlayingFlag = false
                    playbackStateVal = Player.STATE_IDLE
                    Unit
                }
                else -> when (method.returnType) {
                    java.lang.Boolean.TYPE -> false
                    java.lang.Integer.TYPE -> 0
                    java.lang.Long.TYPE -> 0L
                    java.lang.Float.TYPE -> 0f
                    java.lang.Double.TYPE -> 0.0
                    else -> null
                }
            }
        } as Player

        fun setPlaylist(tracks: List<Track>, activeIndex: Int, posMs: Long = 0L, playing: Boolean = true) {
            items.clear()
            tracks.forEach { t ->
                items.add(
                    MediaItem.Builder()
                        .setMediaId(t.videoId)
                        .setMediaMetadata(
                            MediaMetadata.Builder()
                                .setTitle(t.title)
                                .setArtist(t.artist)
                                .setAlbumTitle(t.album)
                                .build()
                        )
                        .build()
                )
            }
            currentIndex = activeIndex.coerceIn(0, (items.size - 1).coerceAtLeast(0))
            currentPositionMs = posMs
            durationMs = tracks.getOrNull(currentIndex)?.durationMs ?: 200_000L
            isPlayingFlag = playing
        }
    }

    private fun track(id: String, title: String = "Title $id", artist: String = "Artist $id", dur: Long = 200_000L) =
        Track(
            videoId = id,
            title = title,
            artist = artist,
            album = "Album $id",
            artworkUrl = "https://example.com/$id.jpg",
            durationText = "3:20",
            durationMs = dur
        )

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        File(context.filesDir, "queue_state.json").delete()
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
        db = Room.inMemoryDatabaseBuilder(context, SonoraDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        prefs = SonoraPreferences(context)
        runBlocking { prefs.setAutoplayRadio(false) }
        val streamResolver = StreamResolver(offlineClient)
        val repo = MusicRepository(
            ytMusicClient = YouTubeMusicClient(offlineClient),
            streamResolver = streamResolver,
            spotifyImporter = SpotifyImporter(offlineClient),
            libraryDao = db.libraryDao(),
            lyricsResolver = LyricsResolver(offlineClient, context),
            newPipeSearchClient = NewPipeSearchClient(offlineClient)
        )
        controller = PlayerController(
            context = context,
            repository = repo,
            streamResolver = streamResolver,
            preferences = prefs
        )
    }

    @After
    fun tearDown() {
        runBlocking { prefs.setAutoplayRadio(true) }
        controller.release()
        File(context.filesDir, "queue_state.json").delete()
        db.close()
    }

    @Test
    fun `connect resyncs currentTrack and currentIndex when controller advanced inside queue`() {
        val queue = (0..5).map { track("q$it") }
        controller.seedStateForTest(queue = queue, currentIndex = 0, positionMs = 10_000L, isPlaying = true)

        val fake = FakePlayerController()
        fake.setPlaylist(queue, activeIndex = 4, posMs = 52_000L, playing = true)

        val future = SettableFuture.create<Player>()
        controller.controllerFutureFactoryForTest = { future }
        controller.connect()

        // While future is pending, UI resync gate is active so stale track is not flashed
        assertTrue(controller.state.value.isResyncingController)

        future.set(fake.player)
        shadowOf(Looper.getMainLooper()).idle()

        val state = controller.state.value
        assertFalse(state.isResyncingController)
        assertEquals("q4", state.currentTrack?.videoId)
        assertEquals(4, state.currentIndex)
        assertEquals(52_000L, state.positionMs)
        assertEquals(200_000L, state.durationMs)
        assertTrue(state.isPlaying)
        assertEquals(queue.map { it.videoId }, state.queue.map { it.videoId })
    }

    @Test
    fun `connect reconstructs queue when controller item is outside stale app queue`() {
        val staleQueue = listOf(track("old0"), track("old1"))
        controller.seedStateForTest(queue = staleQueue, currentIndex = 0, positionMs = 5_000L)

        val newQueue = listOf(track("ext0"), track("ext1"), track("ext2"), track("ext3"))
        val fake = FakePlayerController()
        fake.setPlaylist(newQueue, activeIndex = 2, posMs = 31_000L, playing = true)

        controller.controllerFutureFactoryForTest = { Futures.immediateFuture(fake.player) }
        controller.connect()
        shadowOf(Looper.getMainLooper()).idle()

        val state = controller.state.value
        assertFalse(state.isResyncingController)
        assertEquals(listOf("ext0", "ext1", "ext2", "ext3"), state.queue.map { it.videoId })
        assertEquals("ext2", state.currentTrack?.videoId)
        assertEquals("Title ext2", state.currentTrack?.title)
        assertEquals(2, state.currentIndex)
        assertEquals(31_000L, state.positionMs)
    }

    @Test
    fun `syncFromController resolves duplicate videoId closest to controller currentMediaItemIndex`() {
        val duplicateQueue = listOf(
            track("dup", "Song A"),
            track("mid", "Song B"),
            track("dup", "Song A"),
            track("end", "Song C")
        )
        controller.seedStateForTest(queue = duplicateQueue, currentIndex = 0)

        val fake = FakePlayerController()
        fake.setPlaylist(duplicateQueue, activeIndex = 2, posMs = 15_000L, playing = true)
        controller.controllerFutureFactoryForTest = { Futures.immediateFuture(fake.player) }
        controller.connect()

        assertEquals("dup", controller.state.value.currentTrack?.videoId)
        assertEquals(2, controller.state.value.currentIndex)
    }

    @Test
    fun `connect falls back to persisted queue when controller has single item outside app queue and empty timeline`() {
        val persistedTracks = listOf(track("p0"), track("p1"), track("p2"))
        val payload = PersistedQueueState(queue = persistedTracks, currentIndex = 1, positionMs = 40_000L)
        File(context.filesDir, "queue_state.json").writeText(testJson.encodeToString(payload))

        controller.seedStateForTest(queue = listOf(track("stale")), currentIndex = 0)

        val fake = FakePlayerController()
        // Simulate currentMediaItem present without timeline items
        fake.items.add(
            MediaItem.Builder()
                .setMediaId("p2")
                .setMediaMetadata(MediaMetadata.Builder().setTitle("Title p2").build())
                .build()
        )
        fake.currentIndex = 0
        // Override count to 0 only? Even simpler: keep 1 item not matching stale queue, OR test persisted via single currentMediaItem:
        // When mediaItemCount == 1 it reconstructs from controller; when item is in persisted queue with mediaItemCount==0:
        val emptyCountPlayer = Proxy.newProxyInstance(
            Player::class.java.classLoader,
            arrayOf(Player::class.java)
        ) { _, method, _ ->
            when (method.name) {
                "addListener", "removeListener" -> Unit
                "getMediaItemCount" -> 0
                "getCurrentMediaItem" -> MediaItem.Builder().setMediaId("p2").build()
                "getCurrentMediaItemIndex" -> 2
                "isPlaying" -> true
                "getPlaybackState" -> Player.STATE_READY
                "getCurrentPosition" -> 18_000L
                "getDuration" -> 200_000L
                "getShuffleModeEnabled" -> false
                "getRepeatMode" -> Player.REPEAT_MODE_OFF
                else -> when (method.returnType) {
                    java.lang.Boolean.TYPE -> false
                    java.lang.Integer.TYPE -> 0
                    java.lang.Long.TYPE -> 0L
                    else -> null
                }
            }
        } as Player

        controller.controllerFutureFactoryForTest = { Futures.immediateFuture(emptyCountPlayer) }
        controller.connect()

        val state = controller.state.value
        assertEquals(listOf("p0", "p1", "p2"), state.queue.map { it.videoId })
        assertEquals("p2", state.currentTrack?.videoId)
        assertEquals(2, state.currentIndex)
    }

    @Test
    fun `skipPrevious on desynced state keeps queue intact without rebuild and moves from track 5 to track 4`() {
        val queue = (0..9).map { track("t$it") }
        // App thinks track 0 is active
        controller.seedStateForTest(queue = queue, currentIndex = 0, positionMs = 0L, isPlaying = true)

        val fake = FakePlayerController()
        fake.setPlaylist(queue, activeIndex = 5, posMs = 1_200L, playing = true)

        controller.controllerFutureFactoryForTest = { Futures.immediateFuture(fake.player) }
        controller.connect()
        // Re-desynchronize app state explicitly to verify skipPrevious itself resyncs first
        controller.seedStateForTest(queue = queue, currentIndex = 0, positionMs = 0L, isPlaying = true)
        fake.setMediaItemsCallCount = 0

        controller.skipPrevious()

        val state = controller.state.value
        assertEquals(0, fake.setMediaItemsCallCount)
        assertEquals(queue.map { it.videoId }, state.queue.map { it.videoId })
        assertEquals(10, state.queue.size)
        assertEquals(10, fake.items.size)
        assertEquals(4, fake.currentIndex)
        assertEquals(4, state.currentIndex)
        assertEquals("t4", state.currentTrack?.videoId)
    }

    @Test
    fun `skipNext on desynced state keeps queue intact without rebuild and moves from track 5 to track 6`() {
        val queue = (0..9).map { track("t$it") }
        controller.seedStateForTest(queue = queue, currentIndex = 0, positionMs = 0L, isPlaying = true)

        val fake = FakePlayerController()
        fake.setPlaylist(queue, activeIndex = 5, posMs = 25_000L, playing = true)

        controller.controllerFutureFactoryForTest = { Futures.immediateFuture(fake.player) }
        controller.connect()
        // Re-desynchronize app state explicitly to verify skipNext itself resyncs first
        controller.seedStateForTest(queue = queue, currentIndex = 0, positionMs = 0L, isPlaying = true)
        fake.setMediaItemsCallCount = 0

        controller.skipNext()

        val state = controller.state.value
        assertEquals(0, fake.setMediaItemsCallCount)
        assertEquals(queue.map { it.videoId }, state.queue.map { it.videoId })
        assertEquals(10, state.queue.size)
        assertEquals(10, fake.items.size)
        assertEquals(6, fake.currentIndex)
        assertEquals(6, state.currentIndex)
        assertEquals("t6", state.currentTrack?.videoId)
    }

    @Test
    fun `skipNext with active mix context seeks to toEntryMs without rebuilding playlist`() {
        val t0 = track("m0")
        val t1 = track("m1")
        val t2 = track("m2")
        controller.playCustomMixFragments(
            listOf(
                CustomMixFragment(track = t0, startMs = 0L, endMs = 40_000L),
                CustomMixFragment(track = t1, startMs = 22_000L, endMs = 60_000L),
                CustomMixFragment(track = t2, startMs = 35_000L, endMs = 80_000L)
            )
        )
        // Cancel initial resolution job and wire fake player matching the 3-track mix queue
        controller.release()
        val fake = FakePlayerController()
        fake.setPlaylist(listOf(t0, t1, t2), activeIndex = 0, posMs = 15_000L, playing = true)
        controller.controllerFutureFactoryForTest = { Futures.immediateFuture(fake.player) }
        controller.connect()
        fake.setMediaItemsCallCount = 0

        controller.skipNext()

        assertEquals(0, fake.setMediaItemsCallCount)
        assertEquals(1, fake.currentIndex)
        assertEquals(1 to 22_000L, fake.seekToCalls.last())
        assertEquals("m1", controller.state.value.currentTrack?.videoId)
        assertEquals(1, controller.state.value.currentIndex)
    }

    @Test
    fun `listener callbacks resync when controller advances via crossfade discontinuity`() {
        val queue = (0..3).map { track("c$it") }
        val fake = FakePlayerController()
        fake.setPlaylist(queue, activeIndex = 0, posMs = 1_000L, playing = true)
        controller.controllerFutureFactoryForTest = { Futures.immediateFuture(fake.player) }
        controller.connect()
        assertEquals("c0", controller.state.value.currentTrack?.videoId)

        // Simulate AutoMix crossfade handoff advancing controller silently to track 2
        fake.currentIndex = 2
        fake.currentPositionMs = 18_000L
        val oldPos = Player.PositionInfo(null, 0, fake.items[0], null, 0, 190_000L, 190_000L, -1, -1)
        val newPos = Player.PositionInfo(null, 2, fake.items[2], null, 2, 18_000L, 18_000L, -1, -1)
        fake.listeners.toList().forEach {
            it.onPositionDiscontinuity(oldPos, newPos, Player.DISCONTINUITY_REASON_SEEK)
        }

        assertEquals("c2", controller.state.value.currentTrack?.videoId)
        assertEquals(2, controller.state.value.currentIndex)
        assertEquals(18_000L, controller.state.value.positionMs)

        // Also verify onTimelineChanged resyncs if track drifts again
        fake.currentIndex = 3
        fake.listeners.toList().forEach {
            it.onTimelineChanged(Timeline.EMPTY, Player.TIMELINE_CHANGE_REASON_SOURCE_UPDATE)
        }
        assertEquals("c3", controller.state.value.currentTrack?.videoId)
        assertEquals(3, controller.state.value.currentIndex)
    }

    @Test
    fun `onMediaItemTransition resolves track by mediaId even when controller index does not match queue index`() {
        val queue = (0..3).map { track("m$it") }
        val fake = FakePlayerController()
        fake.setPlaylist(queue, activeIndex = 0, posMs = 0L, playing = true)
        controller.controllerFutureFactoryForTest = { Futures.immediateFuture(fake.player) }
        controller.connect()

        // Suppose controller index reports 0 while passed mediaItem has mediaId "m3"
        fake.currentIndex = 0
        val targetItem = MediaItem.Builder().setMediaId("m3").build()
        fake.listeners.toList().forEach {
            it.onMediaItemTransition(targetItem, Player.MEDIA_ITEM_TRANSITION_REASON_SEEK)
        }

        assertEquals("m3", controller.state.value.currentTrack?.videoId)
        assertEquals(3, controller.state.value.currentIndex)
    }

    @Test
    fun `startProgressLoop detects controller mediaId drift after 1 second and resyncs`() = runBlocking {
        val queue = (0..3).map { track("p$it") }
        val fake = FakePlayerController()
        fake.setPlaylist(queue, activeIndex = 0, posMs = 5_000L, playing = true)
        controller.controllerFutureFactoryForTest = { Futures.immediateFuture(fake.player) }
        controller.connect()
        assertEquals("p0", controller.state.value.currentTrack?.videoId)

        // Mutate fake player active item silently without firing any Player.Listener
        fake.currentIndex = 2
        fake.currentPositionMs = 14_000L

        // Allow progress loop on Main looper to run past 1_000ms threshold
        val deadline = System.currentTimeMillis() + 3_000L
        while (controller.state.value.currentTrack?.videoId != "p2" && System.currentTimeMillis() < deadline) {
            delay(150L)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(150))
        }

        assertEquals("p2", controller.state.value.currentTrack?.videoId)
        assertEquals(2, controller.state.value.currentIndex)
    }
}
