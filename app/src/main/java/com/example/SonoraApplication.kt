package com.example

import android.app.Application
import com.example.data.local.LocalMusicRepository
import com.example.data.local.SonoraDatabase
import com.example.data.local.SonoraPreferences
import com.example.data.ai.AiDjIntentRepository
import com.example.data.ai.DjVoiceSynthesizer
import com.example.data.ai.FirebaseAiDjIntentRepository
import com.example.data.ai.FirebaseAiVoiceSynthesizer
import com.example.data.network.GitHubUpdateChecker
import com.example.data.network.IntegrationsManager
import com.example.data.network.LyricsResolver
import com.example.data.network.NewPipeSearchClient
import com.example.data.network.SpotifyImporter
import com.example.data.network.StreamResolver
import com.example.data.network.YouTubeMusicClient
import com.example.data.repository.BackupManager
import com.example.data.repository.MusicRepository
import com.example.data.repository.OfflineDownloadManager
import com.example.player.DjLiveController
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
        StreamResolver(okHttpClient, applicationContext)
    }

    val spotifyImporter: SpotifyImporter by lazy {
        SpotifyImporter(okHttpClient)
    }

    val lyricsResolver: LyricsResolver by lazy {
        LyricsResolver(okHttpClient, applicationContext)
    }

    val localMusicRepository: LocalMusicRepository by lazy {
        LocalMusicRepository(
            context = applicationContext,
            libraryDao = database.libraryDao(),
            preferences = preferences
        )
    }

    val integrationsManager: IntegrationsManager by lazy {
        IntegrationsManager(okHttpClient)
    }

    val offlineDownloadManager: OfflineDownloadManager by lazy {
        OfflineDownloadManager(
            context = applicationContext,
            okHttpClient = okHttpClient,
            streamResolver = streamResolver,
            libraryDao = database.libraryDao()
        )
    }

    val backupManager: BackupManager by lazy {
        BackupManager(
            context = applicationContext,
            libraryDao = database.libraryDao()
        )
    }

    val gitHubUpdateChecker: GitHubUpdateChecker by lazy {
        GitHubUpdateChecker(okHttpClient)
    }

    val newPipeSearchClient: NewPipeSearchClient by lazy {
        NewPipeSearchClient(okHttpClient)
    }

    val repository: MusicRepository by lazy {
        MusicRepository(
            ytMusicClient = ytMusicClient,
            streamResolver = streamResolver,
            spotifyImporter = spotifyImporter,
            libraryDao = database.libraryDao(),
            lyricsResolver = lyricsResolver,
            newPipeSearchClient = newPipeSearchClient
        )
    }

    val playerController: PlayerController by lazy {
        PlayerController(
            context = applicationContext,
            repository = repository,
            streamResolver = streamResolver,
            preferences = preferences,
            offlineDownloadManager = offlineDownloadManager,
            localMusicRepository = localMusicRepository,
            integrationsManager = integrationsManager
        )
    }

    val aiDjIntentRepository: AiDjIntentRepository by lazy {
        FirebaseAiDjIntentRepository(applicationContext)
    }

    val djVoiceSynthesizer: DjVoiceSynthesizer by lazy {
        FirebaseAiVoiceSynthesizer(applicationContext)
    }

    val djLiveController: DjLiveController by lazy {
        DjLiveController(
            context = applicationContext,
            repository = repository,
            playerController = playerController,
            aiDjIntentRepository = aiDjIntentRepository,
            djVoiceSynthesizer = djVoiceSynthesizer
        )
    }
}
