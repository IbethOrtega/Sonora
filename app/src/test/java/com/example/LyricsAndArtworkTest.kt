package com.example

import com.example.data.network.LyricsResolver
import com.example.ui.components.upgradeArtworkUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LyricsAndArtworkTest {

    @Test
    fun `upgradeArtworkUrl upgrades googleusercontent w-h and s dimensions to 1200x1200`() {
        val whUrl = "https://lh3.googleusercontent.com/abc123=w120-h120-l90-rj"
        assertEquals(
            "https://lh3.googleusercontent.com/abc123=w1200-h1200-l95-rj",
            upgradeArtworkUrl(whUrl)
        )

        val sUrl = "//yt3.ggpht.com/avatar123=s60-c-k-c0x00ffffff-no-rj"
        assertEquals(
            "https://yt3.ggpht.com/avatar123=s1200-l95-rj",
            upgradeArtworkUrl(sUrl)
        )
    }

    @Test
    fun `upgradeArtworkUrl upgrades ytimg default and mqdefault to hqdefault`() {
        assertEquals(
            "https://i.ytimg.com/vi/abc123/hqdefault.jpg",
            upgradeArtworkUrl("https://i.ytimg.com/vi/abc123/default.jpg")
        )
        assertEquals(
            "https://i.ytimg.com/vi/abc123/hqdefault.jpg",
            upgradeArtworkUrl("https://i.ytimg.com/vi/abc123/mqdefault.jpg")
        )
    }

    @Test
    fun `parseLrcLyrics parses multi-tag LRC lines and sorts chronologically`() {
        val raw = """
            [ti:Test Song]
            [ar:Test Artist]
            [00:45.50]Second chorus
            [00:12.34][01:05.00]Repeated line
            [00:04.2]First verse
        """.trimIndent()

        val lines = LyricsResolver.parseLrcLyrics(raw)
        assertEquals(4, lines.size)
        assertEquals(4_200L, lines[0].timeMs)
        assertEquals("First verse", lines[0].text)
        assertEquals(12_340L, lines[1].timeMs)
        assertEquals("Repeated line", lines[1].text)
        assertEquals(45_500L, lines[2].timeMs)
        assertEquals("Second chorus", lines[2].text)
        assertEquals(65_000L, lines[3].timeMs)
        assertEquals("Repeated line", lines[3].text)
    }

    @Test
    fun `distributePlainLyrics spreads plain lines proportionally across track duration`() {
        val plain = """
            Line one
            Line two

            Line three
            Line four
        """.trimIndent()

        val lines = LyricsResolver.distributePlainLyrics(plain, durationMs = 200_000L)
        assertEquals(4, lines.size)
        assertTrue(lines.first().timeMs > 0L)
        for (i in 1 until lines.size) {
            assertTrue("Expected strictly increasing timestamps", lines[i].timeMs > lines[i - 1].timeMs)
        }
        assertTrue(lines.last().timeMs < 200_000L)
    }
}
