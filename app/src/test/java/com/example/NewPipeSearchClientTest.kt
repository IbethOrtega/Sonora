package com.example

import com.example.data.network.NewPipeSearchClient
import com.example.model.ItemKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.schabi.newpipe.extractor.Image
import org.schabi.newpipe.extractor.channel.ChannelInfoItem
import org.schabi.newpipe.extractor.playlist.PlaylistInfoItem
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import org.schabi.newpipe.extractor.stream.StreamType

class NewPipeSearchClientTest {

    @Test
    fun `extractVideoIdFromUrl parses watch short and music URLs`() {
        assertEquals(
            "dQw4w9WgXcQ",
            NewPipeSearchClient.extractVideoIdFromUrl("https://music.youtube.com/watch?v=dQw4w9WgXcQ&list=RDAMVM")
        )
        assertEquals(
            "abc123XYZ09",
            NewPipeSearchClient.extractVideoIdFromUrl("https://youtu.be/abc123XYZ09?si=123")
        )
        assertEquals(
            "xyz987ABC12",
            NewPipeSearchClient.extractVideoIdFromUrl("xyz987ABC12")
        )
        assertNull(NewPipeSearchClient.extractVideoIdFromUrl("https://music.youtube.com/browse"))
    }

    @Test
    fun `extractPlaylistBrowseId normalizes list parameter with VL prefix`() {
        assertEquals(
            "VLOLAK5uy_k123456789",
            NewPipeSearchClient.extractPlaylistBrowseId("https://music.youtube.com/playlist?list=OLAK5uy_k123456789")
        )
        assertEquals(
            "VLPL99999",
            NewPipeSearchClient.extractPlaylistBrowseId("https://www.youtube.com/playlist?list=PL99999")
        )
        assertEquals(
            "MPREb_album123",
            NewPipeSearchClient.extractPlaylistBrowseId("MPREb_album123")
        )
    }

    @Test
    fun `selectBestArtworkUrl picks highest quality thumbnail and upgrades dimensions`() {
        val images = listOf(
            Image("https://lh3.googleusercontent.com/cover=w60-h60-l90-rj", 60, 60, Image.ResolutionLevel.LOW),
            Image("https://lh3.googleusercontent.com/cover=w544-h544-l90-rj", 544, 544, Image.ResolutionLevel.HIGH)
        )
        assertEquals(
            "https://lh3.googleusercontent.com/cover=w1200-h1200-l95-rj",
            NewPipeSearchClient.selectBestArtworkUrl(images)
        )
    }

    @Test
    fun `mapStreamInfoItem extracts title artist duration and artwork cleanly`() {
        val item = StreamInfoItem(
            0,
            "https://music.youtube.com/watch?v=track001122",
            "Midnight City",
            StreamType.AUDIO_STREAM
        ).apply {
            uploaderName = "M83 - Topic"
            uploaderUrl = "https://www.youtube.com/channel/UC_M83_OFFICIAL"
            duration = 243L
            shortDescription = "Hurry Up, We're Dreaming"
            thumbnails = listOf(
                Image("https://lh3.googleusercontent.com/m83=w226-h226-l90-rj", 226, 226, Image.ResolutionLevel.MEDIUM)
            )
        }

        val mapped = NewPipeSearchClient.mapStreamInfoItem(item)
        assertNotNull(mapped)
        assertEquals("track001122", mapped!!.videoId)
        assertEquals("Midnight City", mapped.title)
        assertEquals("M83", mapped.artist)
        assertEquals("UC_M83_OFFICIAL", mapped.artistId)
        assertEquals("Hurry Up, We're Dreaming", mapped.album)
        assertEquals("4:03", mapped.durationText)
        assertEquals(243_000L, mapped.durationMs)
        assertEquals("https://lh3.googleusercontent.com/m83=w1200-h1200-l95-rj", mapped.artworkUrl)
    }

    @Test
    fun `mapPlaylistInfoItem and mapChannelInfoItem build BrowseCards`() {
        val albumItem = PlaylistInfoItem(
            0,
            "https://music.youtube.com/playlist?list=OLAK5uy_xyz",
            "After Hours"
        ).apply {
            uploaderName = "The Weeknd"
            streamCount = 14L
            thumbnails = listOf(
                Image("https://lh3.googleusercontent.com/ah=w544-h544-l90-rj", 544, 544, Image.ResolutionLevel.HIGH)
            )
        }
        val albumCard = NewPipeSearchClient.mapPlaylistInfoItem(albumItem, ItemKind.ALBUM)
        assertNotNull(albumCard)
        assertEquals("VLOLAK5uy_xyz", albumCard!!.id)
        assertEquals("After Hours", albumCard.title)
        assertEquals(ItemKind.ALBUM, albumCard.kind)

        val artistItem = ChannelInfoItem(
            0,
            "https://www.youtube.com/channel/UC0WP5P-ufpRfjbNrmOWwLBQ",
            "The Weeknd"
        ).apply {
            subscriberCount = 35_000_000L
        }
        val artistCard = NewPipeSearchClient.mapChannelInfoItem(artistItem)
        assertNotNull(artistCard)
        assertEquals("UC0WP5P-ufpRfjbNrmOWwLBQ", artistCard!!.id)
        assertEquals(ItemKind.ARTIST, artistCard.kind)
        assertEquals("35.0M suscriptores", artistCard.subtitle)
    }
}
