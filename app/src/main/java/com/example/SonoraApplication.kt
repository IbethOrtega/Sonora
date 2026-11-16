package com.example

import android.app.Application
import com.example.data.local.SonoraDatabase
import com.example.data.local.SonoraPreferences
import com.example.data.network.SpotifyImporter
import com.example.data.network.StreamResolver
import com.example.data.network.YouTubeMusicClient
import com.example.data.repository.MusicRepository
import com.example.player.PlayerController
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

class SonoraApplication : Application() {
    val okHttpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()
    }

    val preferences: SonoraPreferences by lazy {
        SonoraPreferences(applicationContext)
    }

    val database: SonoraDatabase by lazy {
        SonoraDatabase.getInstance(this)
    }

    val ytMusicClient: YouTubeMusicClient by lazy {
        YouTubeMusicClient(okHttpClient)
    }

    val streamResolver: StreamResolver by lazy {
        StreamResolver(okHttpClient)
    }

    val spotifyImporter: SpotifyImporter by lazy {
        SpotifyImporter(okHttpClient)
    }

    val repository: MusicRepository by lazy {
        MusicRepository(
            ytMusicClient = ytMusicClient,
            streamResolver = streamResolver,
            spotifyImporter = spotifyImporter,
            libraryDao = database.libraryDao()
        )
    }

    val playerController: PlayerController by lazy {
        PlayerController(
            context = applicationContext,
            repository = repository,
            streamResolver = streamResolver,
            preferences = preferences
        )
    }
}
