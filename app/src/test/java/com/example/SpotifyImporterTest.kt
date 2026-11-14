package com.example

import com.example.data.network.SpotifyImporter
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SpotifyImporterTest {

    private val importer = SpotifyImporter(OkHttpClient())

    @Test
    fun `parsePlainTextTracklist parses numbered and dashed lines`() {
        val raw = """
            1. Blinding Lights - The Weeknd
            2. Tití Me Preguntó — Bad Bunny
            Daft Punk - Get Lucky
        """.trimIndent()

        val parsed = importer.parsePlainTextTracklist("Favoritas", raw)
        assertEquals("Favoritas", parsed.title)
        assertEquals(3, parsed.tracks.size)
        assertEquals("Blinding Lights", parsed.tracks[0].title)
        assertEquals("The Weeknd", parsed.tracks[0].artist)
        assertEquals("Tití Me Preguntó", parsed.tracks[1].title)
        assertEquals("Bad Bunny", parsed.tracks[1].artist)
    }

    @Test
    fun `parsePlainTextTracklist handles empty input safely`() {
        val parsed = importer.parsePlainTextTracklist("", "   \n# comentario\n  ")
        assertEquals("Playlist importada", parsed.title)
        assertTrue(parsed.tracks.isEmpty())
    }
}
