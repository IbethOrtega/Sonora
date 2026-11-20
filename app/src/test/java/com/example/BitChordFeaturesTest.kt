package com.example

import com.example.data.local.LocalMusicRepository
import com.example.data.network.IntegrationsManager
import com.example.data.network.LyricsResolver
import com.example.data.network.YouTubeMusicClient
import com.example.data.repository.MusicRepository
import com.example.model.PersistedQueueState
import com.example.model.Track
import com.example.model.YouTubeAuthSessionPayload
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.security.MessageDigest

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class BitChordFeaturesTest {

    @Test
    fun `parseLrcLyrics parses enhanced word-by-word timestamps and multi-timestamp lines`() {
        val raw = """
            [ti:Style]
            [00:12.50]<00:12.50>Midnight <00:13.10>you <00:13.45>come <00:13.90>and <00:14.20>pick <00:14.60>me <00:15.00>up
            [00:25.00][01:15.00]You got that James Dean daydream look in your eye
        """.trimIndent()

        val parsed = LyricsResolver.parseLrcLyrics(raw)
        assertEquals(3, parsed.size)

        val wordLine = parsed[0]
        assertEquals(12_500L, wordLine.timeMs)
        assertEquals("Midnight you come and pick me up", wordLine.text)
        assertEquals(7, wordLine.words.size)
        assertEquals(12_500L, wordLine.words.first().startMs)
        assertEquals("Midnight ", wordLine.words.first().text)
        assertEquals(15_000L, wordLine.words.last().startMs)

        // Repeated chorus timestamps should produce two sorted lines
        assertEquals(25_000L, parsed[1].timeMs)
        assertEquals(75_000L, parsed[2].timeMs)
        assertEquals(parsed[1].text, parsed[2].text)
    }

    @Test
    fun `decodeBase64Utf8 decodes KuGou base64 LRC payload with whitespace`() {
        val encoded = "WzAwOjEwLjAwXUhvbGEg\nU29ub3JhIPCfjLg="
        val decoded = LyricsResolver.decodeBase64Utf8(encoded)
        assertEquals("[00:10.00]Hola Sonora \uD83C\uDF38", decoded)
    }

    @Test
    fun `signLastFmParams sorts parameter keys alphabetically and appends secret before MD5`() {
        val params = mapOf(
            "track" to "Style",
            "artist" to "Taylor Swift",
            "api_key" to "KEY123",
            "method" to "track.scrobble"
        )
        val secret = "SECRET_XYZ"

        val actual = IntegrationsManager.signLastFmParams(params, secret)
        val expectedRaw = "api_keyKEY123artistTaylor Swiftmethodtrack.scrobbletrackStyleSECRET_XYZ"
        val expectedMd5 = MessageDigest.getInstance("MD5")
            .digest(expectedRaw.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

        assertEquals(expectedMd5, actual)
    }

    @Test
    fun `ListenBrainz and Discord RPC payloads contain expected protocol fields`() {
        val track = Track(
            videoId = "-CmadmM5cOk",
            title = "Style",
            artist = "Taylor Swift",
            album = "1989",
            durationMs = 231_000L
        )

        val nowPlaying = IntegrationsManager.buildListenBrainzPayload(
            track = track,
            isNowPlaying = true,
            listenedAtSec = 1_760_000_000L
        )
        assertEquals("playing_now", nowPlaying.getString("listen_type"))
        val npMeta = nowPlaying.getJSONArray("payload").getJSONObject(0).getJSONObject("track_metadata")
        assertEquals("Taylor Swift", npMeta.getString("artist_name"))
        assertEquals("Style", npMeta.getString("track_name"))
        assertEquals("1989", npMeta.getString("release_name"))
        assertFalse(nowPlaying.getJSONArray("payload").getJSONObject(0).has("listened_at"))

        val scrobble = IntegrationsManager.buildListenBrainzPayload(
            track = track,
            isNowPlaying = false,
            listenedAtSec = 1_760_000_120L
        )
        assertEquals("single", scrobble.getString("listen_type"))
        assertEquals(1_760_000_120L, scrobble.getJSONArray("payload").getJSONObject(0).getLong("listened_at"))

        val discord = IntegrationsManager.buildDiscordPresencePayload(track, nowMs = 1_000_000L)
        assertEquals(3, discord.getInt("op"))
        val activity = discord.getJSONObject("d").getJSONArray("activities").getJSONObject(0)
        assertEquals(2, activity.getInt("type"))
        assertEquals("Sonora", activity.getString("name"))
        assertEquals("Style", activity.getString("details"))
        assertTrue(activity.getString("state").contains("Taylor Swift"))
        assertEquals(1_231_000L, activity.getJSONObject("timestamps").getLong("end"))
    }

    @Test
    fun `LocalMusicRepository detects audio formats generates deterministic ids and formats SAF labels`() {
        assertTrue(LocalMusicRepository.isSupportedAudioFile("song.flac", ""))
        assertTrue(LocalMusicRepository.isSupportedAudioFile("track.unknown", "audio/mpeg"))
        assertFalse(LocalMusicRepository.isSupportedAudioFile("cover.jpg", "image/jpeg"))
        assertFalse(LocalMusicRepository.isSupportedAudioFile("lyrics.lrc", "text/plain"))

        val idA = LocalMusicRepository.buildLocalTrackId("content://com.android.externalstorage.documents/tree/primary%3AMusic/document/1")
        val idB = LocalMusicRepository.buildLocalTrackId("content://com.android.externalstorage.documents/tree/primary%3AMusic/document/1")
        val idC = LocalMusicRepository.buildLocalTrackId("content://com.android.externalstorage.documents/tree/primary%3AMusic/document/2")
        assertTrue(idA.startsWith(LocalMusicRepository.LOCAL_ID_PREFIX))
        assertEquals(idA, idB)
        assertTrue(idA != idC)

        assertEquals(
            "Mi Musica",
            LocalMusicRepository.friendlyFolderLabel("content://com.android.externalstorage.documents/tree/primary%3ADescargas%2FMi%20Musica")
        )
    }

    @Test
    fun `prioritizeAudioOnly ranks studio audio ahead of music videos and live clips`() {
        val videoClip = Track(videoId = "v1", title = "Blinding Lights (Official Music Video)", artist = "The Weeknd")
        val liveClip = Track(videoId = "v2", title = "Blinding Lights (Live at Wembley)", artist = "The Weeknd")
        val studioTrack = Track(videoId = "v3", title = "Blinding Lights", artist = "The Weeknd")

        assertTrue(MusicRepository.isVideoHeavyUpload(videoClip))
        assertTrue(MusicRepository.isVideoHeavyUpload(liveClip))
        assertFalse(MusicRepository.isVideoHeavyUpload(studioTrack))

        val prioritized = MusicRepository.prioritizeAudioOnly(listOf(videoClip, liveClip, studioTrack), audioOnlyEnabled = true)
        assertEquals("v3", prioritized.first().videoId)

        val untouched = MusicRepository.prioritizeAudioOnly(listOf(videoClip, studioTrack), audioOnlyEnabled = false)
        assertEquals("v1", untouched.first().videoId)
    }

    @Test
    fun `PersistedQueueState serializes and deserializes playback queue position faithfully`() {
        val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
        val state = PersistedQueueState(
            queue = listOf(
                Track(
                    videoId = "local_abc",
                    title = "Pista Local",
                    artist = "Artista Local",
                    localUri = "content://local/1",
                    localLrcUri = "content://local/1.lrc",
                    isLocal = true
                ),
                Track(videoId = "yt_99", title = "Style", artist = "Taylor Swift")
            ),
            currentIndex = 1,
            positionMs = 45_200L,
            savedAtMs = 1_760_000_000L
        )
        val decoded = json.decodeFromString<PersistedQueueState>(json.encodeToString(state))
        assertEquals(state, decoded)
        assertTrue(decoded.queue.first().isLocal)
        assertEquals(45_200L, decoded.positionMs)
    }

    @Test
    fun `YouTubeMusicClient extracts ytcfg metadata and builds SAPISIDHASH multi-scheme header`() {
        val html = """
            <script>
            ytcfg.set({
                "LOGGED_IN": true,
                "VISITOR_DATA": "Cgtabcdef123\u003d",
                "DATASYNC_ID": "12345||67890",
                "DELEGATED_SESSION_ID": "10987654321",
                "SESSION_INDEX": "2",
                "INNERTUBE_CLIENT_VERSION": "1.20260101.01.00"
            });
            </script>
        """.trimIndent()
        val cookies = "SAPISID=sap_val; __Secure-1PAPISID=p1_val; __Secure-3PAPISID=p3_val"
        val extracted = YouTubeMusicClient.extractYtCfgFromHtml(html, cookies)
        assertNotNull(extracted)
        assertEquals("Cgtabcdef123=", extracted!!.visitorData)
        assertEquals("12345||67890", extracted.dataSyncId)
        assertEquals("10987654321", extracted.delegatedSessionId)
        assertEquals("2", extracted.authUser)
        assertEquals("1.20260101.01.00", extracted.clientVersion)

        val client = YouTubeMusicClient(OkHttpClient())
        val authHeader = client.buildAuthorizationHeader(cookies, timestampSec = 1_760_000_000L)
        assertNotNull(authHeader)
        assertTrue(authHeader!!.contains("SAPISIDHASH 1760000000_"))
        assertTrue(authHeader.contains("SAPISID1PHASH 1760000000_"))
        assertTrue(authHeader.contains("SAPISID3PHASH 1760000000_"))

        val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
        val roundTrip = json.decodeFromString<YouTubeAuthSessionPayload>(json.encodeToString(extracted))
        assertEquals(extracted, roundTrip)
    }

    @Test
    fun `YouTubeMusicClient extracts continuation tokens and parses paginated playlist tracks`() {
        val client = YouTubeMusicClient(OkHttpClient())

        val itemRenderer = JSONObject().put(
            "musicResponsiveListItemRenderer",
            JSONObject()
                .put("playlistItemData", JSONObject().put("videoId", "vid_next_1"))
                .put(
                    "flexColumns",
                    JSONArray()
                        .put(
                            JSONObject().put(
                                "musicResponsiveListItemFlexColumnRenderer",
                                JSONObject().put(
                                    "text",
                                    JSONObject().put("runs", JSONArray().put(JSONObject().put("text", "Cruel Summer")))
                                )
                            )
                        )
                        .put(
                            JSONObject().put(
                                "musicResponsiveListItemFlexColumnRenderer",
                                JSONObject().put(
                                    "text",
                                    JSONObject().put("runs", JSONArray().put(JSONObject().put("text", "Taylor Swift")))
                                )
                            )
                        )
                )
        )
        val contRenderer = JSONObject().put(
            "continuationItemRenderer",
            JSONObject().put(
                "continuationEndpoint",
                JSONObject().put(
                    "continuationCommand",
                    JSONObject().put("token", "TOKEN_PAGE_3")
                )
            )
        )

        val continuationPayload = JSONObject().put(
            "continuationContents",
            JSONObject().put(
                "musicPlaylistShelfContinuation",
                JSONObject()
                    .put("contents", JSONArray().put(itemRenderer).put(contRenderer))
            )
        )

        val (tracks, cards, nextToken) = client.parseBrowseContinuationItems(continuationPayload)
        assertEquals(1, tracks.size)
        assertEquals("vid_next_1", tracks.first().videoId)
        assertEquals("Cruel Summer", tracks.first().title)
        assertEquals("Taylor Swift", tracks.first().artist)
        assertTrue(cards.isEmpty())
        assertEquals("TOKEN_PAGE_3", nextToken)

        val browseRoot = JSONObject().put(
            "contents",
            JSONObject().put(
                "singleColumnBrowseResultsRenderer",
                JSONObject().put(
                    "tabs",
                    JSONArray().put(
                        JSONObject().put(
                            "tabRenderer",
                            JSONObject().put(
                                "content",
                                JSONObject().put(
                                    "sectionListRenderer",
                                    JSONObject().put(
                                        "continuations",
                                        JSONArray().put(
                                            JSONObject().put(
                                                "nextContinuationData",
                                                JSONObject().put("continuation", "TOKEN_PAGE_2")
                                            )
                                        )
                                    )
                                )
                            )
                        )
                    )
                )
            )
        )
        assertEquals("TOKEN_PAGE_2", client.extractContinuationToken(browseRoot))
    }
}
