package com.example.data.repository

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.example.data.local.DownloadedTrackEntity
import com.example.data.local.LibraryDao
import com.example.data.network.InnerTubeXResolver
import com.example.data.network.StreamResolver
import com.example.model.ResolvedStream
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
import java.io.FileInputStream
import java.io.FileOutputStream

enum class AudioContainer(val extension: String, val mimeType: String) {
    MP4("m4a", "audio/mp4"),
    WEBM("webm", "audio/webm")
}

class OfflineDownloadManager(
    private val context: Context,
    private val okHttpClient: OkHttpClient,
    private val streamResolver: StreamResolver,
    private val libraryDao: LibraryDao,
    private val alternateStreamFinder: (suspend (Track) -> ResolvedStream?)? = null
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
        val expectedSize = entity.fileSizeBytes
        val validSize = expectedSize > 0L && file.exists() && file.isFile && file.length() == expectedSize
        val validContainer = if (validSize) detectAudioContainer(file) != null else false
        if (validSize && validContainer) {
            file
        } else {
            runCatching { if (file.exists()) file.delete() }
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
        val safeId = track.videoId.replace(Regex("[^A-Za-z0-9_-]"), "_")
        val tempFile = File(offlineDir, "$safeId.part")
        runCatching { if (tempFile.exists()) tempFile.delete() }

        try {
            var currentStream = resolveDownloadableStream(track, excludeCurrent = false)
            var totalSizeBytes = probeTotalSize(currentStream)
            var retriedAfterRefusal = false

            if (totalSizeBytes == SIZE_PROBE_REFUSED) {
                InnerTubeXResolver.onRefused(currentStream.url)
                streamResolver.invalidate(track.videoId)
                retriedAfterRefusal = true
                currentStream = resolveDownloadableStream(track, excludeCurrent = true)
                totalSizeBytes = probeTotalSize(currentStream)
                if (totalSizeBytes <= 0L) {
                    throw IllegalStateException("El servidor rechazó la descarga del audio.")
                }
            } else if (totalSizeBytes <= 0L) {
                throw IllegalStateException("No se pudo determinar el tamaño total del audio para descargar.")
            }

            var downloadedBytes = 0L
            var lastUiEmit = 0L
            var chunkSize = chunkSizeForStream(currentStream)

            while (downloadedBytes < totalSizeBytes) {
                val endInclusive = minOf(downloadedBytes + chunkSize - 1L, totalSizeBytes - 1L)
                val headers = buildHeaders(currentStream)
                val reqBuilder = Request.Builder()
                    .url(currentStream.url)
                    .header("Range", "bytes=$downloadedBytes-$endInclusive")
                headers.forEach { (k, v) ->
                    if (v.isNotBlank()) reqBuilder.header(k, v)
                }

                var shouldRetryFromRefusal = false
                okHttpClient.newCall(reqBuilder.get().build()).execute().use { resp ->
                    if (resp.code == 403 || resp.code == 404 || resp.code == 410) {
                        if (retriedAfterRefusal) {
                            throw IllegalStateException("El servidor rechazó la descarga (HTTP ${resp.code}).")
                        }
                        shouldRetryFromRefusal = true
                        return@use
                    }
                    if (!resp.isSuccessful && resp.code != 206) {
                        throw IllegalStateException("Error al descargar (HTTP ${resp.code}).")
                    }
                    val body = resp.body ?: throw IllegalStateException("Respuesta vacía del servidor.")
                    val append = downloadedBytes > 0L
                    FileOutputStream(tempFile, append).use { output ->
                        val buffer = ByteArray(16 * 1024)
                        val input = body.byteStream()
                        var bytesReadThisChunk = 0L
                        while (true) {
                            val n = input.read(buffer)
                            if (n <= 0) break
                            val remainingAllowed = totalSizeBytes - downloadedBytes
                            val toWrite = minOf(n.toLong(), remainingAllowed).toInt()
                            if (toWrite <= 0) break
                            output.write(buffer, 0, toWrite)
                            downloadedBytes += toWrite
                            bytesReadThisChunk += toWrite
                            val now = System.currentTimeMillis()
                            if (now - lastUiEmit > 120L) {
                                lastUiEmit = now
                                val progress = (downloadedBytes.toFloat() / totalSizeBytes.toFloat())
                                    .coerceIn(0.05f, 0.98f)
                                _activeDownloads.update { it + (track.videoId to progress) }
                            }
                        }
                        if (bytesReadThisChunk == 0L) {
                            throw IllegalStateException("La conexión se detuvo antes de recibir datos.")
                        }
                    }
                }

                if (shouldRetryFromRefusal) {
                    retriedAfterRefusal = true
                    InnerTubeXResolver.onRefused(currentStream.url)
                    streamResolver.invalidate(track.videoId)
                    val newStream = resolveDownloadableStream(track, excludeCurrent = true)
                    val newTotal = probeTotalSize(newStream)
                    if (newTotal <= 0L) {
                        throw IllegalStateException("No se pudo verificar el nuevo stream de descarga.")
                    }
                    if (newTotal != totalSizeBytes) {
                        throw IllegalStateException("La fuente alternativa tiene un tamaño distinto; vuelve a intentar la descarga.")
                    }
                    currentStream = newStream
                    chunkSize = chunkSizeForStream(newStream)
                }
            }

            if (!tempFile.exists() || tempFile.length() != totalSizeBytes || tempFile.length() < 4096L) {
                throw IllegalStateException("El archivo descargado está incompleto (${tempFile.length()}/$totalSizeBytes bytes).")
            }

            val container = detectAudioContainer(tempFile)
                ?: throw IllegalStateException("El archivo descargado no contiene un contenedor MP4 o WebM válido.")

            val finalFile = File(offlineDir, "$safeId.${container.extension}")
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
                codecLabel = "${currentStream.codecLabel} • Offline"
            )
            libraryDao.insertDownloadedTrack(entity)
            Result.success(entity)
        } catch (err: Throwable) {
            runCatching { if (tempFile.exists()) tempFile.delete() }
            Result.failure(err)
        } finally {
            runCatching { if (tempFile.exists()) tempFile.delete() }
            _activeDownloads.update { it - track.videoId }
        }
    }

    private suspend fun resolveDownloadableStream(
        track: Track,
        excludeCurrent: Boolean
    ): ResolvedStream {
        if (excludeCurrent) {
            streamResolver.invalidate(track.videoId)
        }
        val resolved = streamResolver.resolveAudioStream(track, allowHls = false, forDownload = true).getOrNull()
        if (resolved != null && !isHlsStream(resolved)) {
            return resolved
        }

        // If primary returned HLS or failed, invalidate and try another source before failing
        if (resolved != null && isHlsStream(resolved)) {
            streamResolver.invalidate(track.videoId)
            val secondTry = streamResolver.resolveAudioStream(track, allowHls = false, forDownload = true).getOrNull()
            if (secondTry != null && !isHlsStream(secondTry)) {
                return secondTry
            }
        }

        val alternate = alternateStreamFinder?.invoke(track)
        if (alternate != null && !isHlsStream(alternate)) {
            return alternate
        }

        throw IllegalStateException(
            "Este tema usa streaming segmentado (HLS) sin fuente descargable directa."
        )
    }

    /**
     * Determines total byte length using URL `clen` parameter or a `Range: bytes=0-0` probe.
     * Returns [SIZE_PROBE_REFUSED] when the server responds with HTTP 403/404/410.
     */
    private fun probeTotalSize(stream: ResolvedStream): Long {
        val urlClen = StreamResolver.extractQueryParam(CLEN_REGEX, stream.url)
            ?.toLongOrNull()
            ?.takeIf { it > 0L }
        if (urlClen != null) {
            return urlClen
        }

        val headers = buildHeaders(stream)
        val reqBuilder = Request.Builder()
            .url(stream.url)
            .header("Range", "bytes=0-0")
        headers.forEach { (k, v) ->
            if (v.isNotBlank()) reqBuilder.header(k, v)
        }

        return okHttpClient.newCall(reqBuilder.get().build()).execute().use { resp ->
            when {
                resp.code == 403 || resp.code == 404 || resp.code == 410 -> SIZE_PROBE_REFUSED
                !resp.isSuccessful && resp.code != 206 -> -1L
                else -> {
                    val contentRange = resp.header("Content-Range").orEmpty()
                    val fromRange = CONTENT_RANGE_TOTAL_REGEX.find(contentRange)
                        ?.groupValues
                        ?.getOrNull(1)
                        ?.toLongOrNull()
                        ?.takeIf { it > 0L }
                    if (fromRange != null) {
                        fromRange
                    } else if (resp.code == 200) {
                        resp.body?.contentLength()?.takeIf { it > 0L } ?: -1L
                    } else {
                        resp.header("Content-Length")?.toLongOrNull()?.takeIf { it > 1L } ?: -1L
                    }
                }
            }
        }
    }

    private fun buildHeaders(stream: ResolvedStream): Map<String, String> {
        return linkedMapOf<String, String>().apply {
            putAll(streamResolver.getHeadersForUrl(stream.url))
            putAll(stream.headers)
            if (keys.none { it.equals("User-Agent", ignoreCase = true) }) {
                put("User-Agent", stream.userAgent)
            }
        }
    }

    private fun chunkSizeForStream(stream: ResolvedStream): Long {
        val cParam = StreamResolver.extractQueryParam(CLIENT_PARAM_REGEX, stream.url)?.uppercase()
        val isAndroidVr = cParam == "ANDROID_VR" ||
            stream.profileId?.contains("ANDROID_VR", ignoreCase = true) == true
        return if (isAndroidVr) ANDROID_VR_CHUNK_BYTES else DEFAULT_CHUNK_BYTES
    }

    private fun isHlsStream(stream: ResolvedStream): Boolean =
        stream.mimeType.contains("mpegURL", ignoreCase = true) ||
            stream.url.contains(".m3u8", ignoreCase = true)

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

    companion object {
        private const val DEFAULT_CHUNK_BYTES = 2L * 1024L * 1024L
        private const val ANDROID_VR_CHUNK_BYTES = 512L * 1024L
        private const val SIZE_PROBE_REFUSED = -403L

        private val CLEN_REGEX = Regex("[?&]clen=(\\d+)", RegexOption.IGNORE_CASE)
        private val CLIENT_PARAM_REGEX = Regex("[?&]c=([^&#]+)", RegexOption.IGNORE_CASE)
        private val CONTENT_RANGE_TOTAL_REGEX = Regex("bytes\\s+\\d+-\\d+/(\\d+)", RegexOption.IGNORE_CASE)

        fun detectAudioContainer(file: File): AudioContainer? {
            if (!file.exists() || !file.isFile || file.length() < 8L) return null
            val header = ByteArray(12)
            val read = runCatching {
                FileInputStream(file).use { it.read(header) }
            }.getOrDefault(-1)
            if (read < 4) return null
            return detectContainerFromHeader(header, read)
        }

        internal fun detectContainerFromHeader(header: ByteArray, length: Int = header.size): AudioContainer? {
            if (length >= 4 &&
                header[0] == 0x1A.toByte() &&
                header[1] == 0x45.toByte() &&
                header[2] == 0xDF.toByte() &&
                header[3] == 0xA3.toByte()
            ) {
                return AudioContainer.WEBM
            }
            if (length >= 8 &&
                header[4] == 'f'.code.toByte() &&
                header[5] == 't'.code.toByte() &&
                header[6] == 'y'.code.toByte() &&
                header[7] == 'p'.code.toByte()
            ) {
                return AudioContainer.MP4
            }
            return null
        }
    }
}
