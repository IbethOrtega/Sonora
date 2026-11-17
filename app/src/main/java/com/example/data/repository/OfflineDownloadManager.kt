package com.example.data.repository

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.example.data.local.DownloadedTrackEntity
import com.example.data.local.LibraryDao
import com.example.data.network.StreamResolver
import com.example.model.Track
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream

class OfflineDownloadManager(
    private val context: Context,
    private val okHttpClient: OkHttpClient,
    private val streamResolver: StreamResolver,
    private val libraryDao: LibraryDao
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val offlineDir by lazy {
        File(context.filesDir, "offline_audio").apply { mkdirs() }
    }

    private val _activeDownloads = MutableStateFlow<Map<String, Float>>(emptyMap())
    val activeDownloads: StateFlow<Map<String, Float>> = _activeDownloads.asStateFlow()

    suspend fun getLocalAudioFile(videoId: String): File? = withContext(Dispatchers.IO) {
        val entity = libraryDao.getDownloadedTrack(videoId) ?: return@withContext null
        val file = File(entity.filePath)
        if (file.exists() && file.length() > 1024L) {
            file
        } else {
            libraryDao.deleteDownloadedTrack(videoId)
            null
        }
    }

    suspend fun downloadTrack(
        track: Track,
        wifiOnly: Boolean
    ): Result<DownloadedTrackEntity> = withContext(Dispatchers.IO) {
        if (_activeDownloads.value.containsKey(track.videoId)) {
            return@withContext Result.failure(IllegalStateException("Ya se está descargando esta canción."))
        }
        val existing = getLocalAudioFile(track.videoId)
        if (existing != null) {
            val entity = libraryDao.getDownloadedTrack(track.videoId)!!
            return@withContext Result.success(entity)
        }
        if (wifiOnly && !isOnWifi()) {
            return@withContext Result.failure(IllegalStateException("Solo se permiten descargas por Wi-Fi (revisa Configuración)."))
        }

        _activeDownloads.update { it + (track.videoId to 0.04f) }
        try {
            val stream = streamResolver.resolveAudioStream(track).getOrElse { err ->
                throw IllegalStateException(err.message ?: "No se pudo obtener el audio para descargar.")
            }
            if (stream.mimeType.contains("mpegURL", ignoreCase = true) || stream.url.contains(".m3u8", ignoreCase = true)) {
                throw IllegalStateException("Este tema usa streaming segmentado no compatible con descarga directa.")
            }

            val ext = when {
                stream.mimeType.contains("mp4", ignoreCase = true) -> "m4a"
                stream.mimeType.contains("webm", ignoreCase = true) -> "webm"
                stream.mimeType.contains("mpeg", ignoreCase = true) -> "mp3"
                else -> "m4a"
            }

            val safeId = track.videoId.replace(Regex("[^A-Za-z0-9_-]"), "_")
            val tempFile = File(offlineDir, "$safeId.part")
            val finalFile = File(offlineDir, "$safeId.$ext")

            val req = Request.Builder()
                .url(stream.url)
                .header("User-Agent", stream.userAgent)
                .build()

            okHttpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    throw IllegalStateException("Error al descargar (${resp.code}).")
                }
                val body = resp.body ?: throw IllegalStateException("Respuesta vacía del servidor.")
                val totalBytes = body.contentLength().coerceAtLeast(1L)
                var downloadedBytes = 0L

                body.byteStream().use { input ->
                    FileOutputStream(tempFile).use { output ->
                        val buffer = ByteArray(16 * 1024)
                        var read: Int
                        var lastUiEmit = 0L
                        while (input.read(buffer).also { read = it } != -1) {
                            output.write(buffer, 0, read)
                            downloadedBytes += read
                            val now = System.currentTimeMillis()
                            if (now - lastUiEmit > 120L) {
                                lastUiEmit = now
                                val progress = (downloadedBytes.toFloat() / totalBytes.toFloat()).coerceIn(0.05f, 0.98f)
                                _activeDownloads.update { it + (track.videoId to progress) }
                            }
                        }
                    }
                }
            }

            if (tempFile.length() < 4096L) {
                tempFile.delete()
                throw IllegalStateException("El archivo descargado está incompleto.")
            }

            if (finalFile.exists()) finalFile.delete()
            if (!tempFile.renameTo(finalFile)) {
                tempFile.copyTo(finalFile, overwrite = true)
                tempFile.delete()
            }

            val entity = DownloadedTrackEntity(
                videoId = track.videoId,
                title = track.title,
                artist = track.artist,
                artistId = track.artistId,
                album = track.album,
                albumId = track.albumId,
                artworkUrl = track.artworkUrl,
                durationText = track.durationText,
                durationMs = track.durationMs,
                filePath = finalFile.absolutePath,
                fileSizeBytes = finalFile.length(),
                codecLabel = "${stream.codecLabel} • Offline"
            )
            libraryDao.insertDownloadedTrack(entity)
            Result.success(entity)
        } catch (err: Throwable) {
            Result.failure(err)
        } finally {
            _activeDownloads.update { it - track.videoId }
        }
    }

    fun launchDownload(track: Track, wifiOnly: Boolean, onResult: (Result<DownloadedTrackEntity>) -> Unit = {}) {
        scope.launch {
            val res = downloadTrack(track, wifiOnly)
            onResult(res)
        }
    }

    suspend fun deleteDownload(videoId: String) = withContext(Dispatchers.IO) {
        val existing = libraryDao.getDownloadedTrack(videoId)
        if (existing != null) {
            runCatching { File(existing.filePath).delete() }
        }
        libraryDao.deleteDownloadedTrack(videoId)
    }

    suspend fun clearAllDownloads() = withContext(Dispatchers.IO) {
        runCatching {
            offlineDir.listFiles()?.forEach { it.delete() }
        }
        libraryDao.clearDownloadedTracks()
    }

    private fun isOnWifi(): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return true
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
    }
}
