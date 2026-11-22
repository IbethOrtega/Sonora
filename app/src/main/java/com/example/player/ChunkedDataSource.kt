package com.example.player

import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import com.example.data.network.StreamResolver
import java.io.IOException

/**
 * Bounded range-chunking DataSource placed between ResolvingDataSource and OkHttpDataSource.
 *
 * Splits requests containing a YouTube `clen` parameter into 512 KB chunks (ANDROID_VR / TVHTML5_SIMPLY)
 * or 1 MiB chunks (other clients) to avoid server throttling/403 disconnects on unbounded range requests.
 * Passes through untouched if `clen` is absent, for HLS manifests, or non-HTTP schemes.
 */
@OptIn(UnstableApi::class)
class ChunkedDataSource(
    private val upstream: DataSource
) : DataSource {

    class Factory(
        private val upstreamFactory: DataSource.Factory
    ) : DataSource.Factory {
        override fun createDataSource(): DataSource =
            ChunkedDataSource(upstreamFactory.createDataSource())
    }

    private var passThroughMode: Boolean = false
    private var activeDataSpec: DataSpec? = null
    private var chunkSizeBytes: Long = StreamResolver.ONE_MIB
    private var currentPosition: Long = 0L
    private var remainingBytes: Long = 0L
    private var remainingInChunk: Long = 0L
    private var chunkOpen: Boolean = false

    override fun addTransferListener(transferListener: TransferListener) {
        upstream.addTransferListener(transferListener)
    }

    override fun open(dataSpec: DataSpec): Long {
        val uri = dataSpec.uri
        val urlStr = uri.toString()
        val scheme = uri.scheme?.lowercase().orEmpty()
        val isHttp = scheme == "http" || scheme == "https"
        val isHls = urlStr.contains(".m3u8", ignoreCase = true)
        val clen = if (isHttp && !isHls) {
            StreamResolver.extractQueryParam(CLEN_REGEX, urlStr)?.toLongOrNull()?.takeIf { it > 0L }
        } else {
            null
        }

        if (clen == null) {
            passThroughMode = true
            activeDataSpec = dataSpec
            chunkOpen = true
            return upstream.open(dataSpec)
        }

        passThroughMode = false
        activeDataSpec = dataSpec
        chunkSizeBytes = if (StreamResolver.isHalfMegabyteRangeClient(urlStr)) {
            StreamResolver.HALF_MIB
        } else {
            StreamResolver.ONE_MIB
        }

        val startPos = dataSpec.position.coerceAtLeast(0L)
        if (startPos >= clen) {
            currentPosition = startPos
            remainingBytes = 0L
            remainingInChunk = 0L
            chunkOpen = false
            return 0L
        }

        val availableFromPos = clen - startPos
        val requestedTotal = if (dataSpec.length != C.LENGTH_UNSET.toLong()) {
            minOf(dataSpec.length.coerceAtLeast(0L), availableFromPos)
        } else {
            availableFromPos
        }

        currentPosition = startPos
        remainingBytes = requestedTotal
        remainingInChunk = 0L
        chunkOpen = false

        if (remainingBytes > 0L) {
            openNextChunk()
        }
        return remainingBytes
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (passThroughMode) {
            return upstream.read(buffer, offset, length)
        }
        if (remainingBytes <= 0L) {
            return C.RESULT_END_OF_INPUT
        }

        while (remainingBytes > 0L) {
            if (!chunkOpen || remainingInChunk <= 0L) {
                closeUpstreamQuietly()
                openNextChunk()
            }
            val toRead = minOf(length.toLong(), remainingInChunk, remainingBytes).toInt()
            if (toRead <= 0) return C.RESULT_END_OF_INPUT

            val read = upstream.read(buffer, offset, toRead)
            if (read == C.RESULT_END_OF_INPUT) {
                closeUpstreamQuietly()
                if (remainingBytes <= 0L) {
                    return C.RESULT_END_OF_INPUT
                }
                // Upstream closed chunk early; if zero bytes were returned for an expected block, fail cleanly
                if (remainingInChunk == minOf(chunkSizeBytes, remainingBytes)) {
                    throw IOException("El servidor cerró el bloque de audio antes de completar la lectura")
                }
                remainingInChunk = 0L
                continue
            }
            if (read > 0) {
                currentPosition += read
                remainingBytes -= read
                remainingInChunk -= read
                return read
            }
        }
        return C.RESULT_END_OF_INPUT
    }

    private fun openNextChunk() {
        val baseSpec = activeDataSpec ?: throw IOException("DataSpec no inicializado")
        val nextChunkLength = minOf(chunkSizeBytes, remainingBytes)
        if (nextChunkLength <= 0L) {
            remainingBytes = 0L
            remainingInChunk = 0L
            chunkOpen = false
            return
        }
        val chunkSpec = baseSpec.buildUpon()
            .setPosition(currentPosition)
            .setLength(nextChunkLength)
            .build()
        val openedLength = upstream.open(chunkSpec)
        chunkOpen = true
        remainingInChunk = if (openedLength != C.LENGTH_UNSET.toLong() && openedLength > 0L) {
            minOf(openedLength, nextChunkLength)
        } else {
            nextChunkLength
        }
    }

    override fun getUri(): Uri? = upstream.uri ?: activeDataSpec?.uri

    override fun getResponseHeaders(): Map<String, List<String>> = upstream.responseHeaders

    override fun close() {
        try {
            if (chunkOpen) {
                upstream.close()
            }
        } finally {
            chunkOpen = false
            passThroughMode = false
            activeDataSpec = null
            remainingBytes = 0L
            remainingInChunk = 0L
        }
    }

    private fun closeUpstreamQuietly() {
        if (!chunkOpen) return
        try {
            upstream.close()
        } catch (_: Throwable) {
        } finally {
            chunkOpen = false
        }
    }

    private companion object {
        val CLEN_REGEX = Regex("[?&]clen=(\\d+)", RegexOption.IGNORE_CASE)
    }
}
