package com.example.data.local

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.DocumentsContract
import com.example.model.Track
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest

/**
 * Google Play-compliant local music library scanner built strictly on Android's zero-permission
 * Storage Access Framework (ACTION_OPEN_DOCUMENT_TREE).
 * Extracts audio tags, embedded artwork, and matching .lrc sidecar files into Room.
 */
class LocalMusicRepository(
    private val context: Context,
    private val libraryDao: LibraryDao,
    private val preferences: SonoraPreferences
) {
    private val artCacheDir by lazy {
        File(context.filesDir, "local_art").apply { mkdirs() }
    }

    fun observeLocalTracks(): Flow<List<Track>> =
        libraryDao.observeLocalTracks().map { entities -> entities.map { it.toTrack() } }

    suspend fun getLocalTrack(videoId: String): LocalTrackEntity? =
        libraryDao.getLocalTrackById(videoId)

    suspend fun addFolderAndScan(treeUri: Uri): Result<Int> = withContext(Dispatchers.IO) {
        runCatching {
            val resolver = context.contentResolver
            runCatching {
                resolver.takePersistableUriPermission(
                    treeUri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            }
            val treeUriStr = treeUri.toString()
            preferences.addLocalFolderUri(treeUriStr)
            scanTreeInternal(resolver, treeUri, treeUriStr)
        }
    }

    suspend fun rescanFolders(folderUris: Set<String>): Result<Int> = withContext(Dispatchers.IO) {
        runCatching {
            val resolver = context.contentResolver
            var total = 0
            for (uriStr in folderUris) {
                val uri = Uri.parse(uriStr)
                total += scanTreeInternal(resolver, uri, uriStr)
            }
            total
        }
    }

    suspend fun removeFolder(treeUriStr: String) = withContext(Dispatchers.IO) {
        preferences.removeLocalFolderUri(treeUriStr)
        libraryDao.deleteLocalTracksByFolder(treeUriStr)
        runCatching {
            context.contentResolver.releasePersistableUriPermission(
                Uri.parse(treeUriStr),
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        }
    }

    suspend fun clearArtCache() = withContext(Dispatchers.IO) {
        runCatching {
            artCacheDir.listFiles()?.forEach { it.delete() }
        }
    }

    fun computeArtCacheSizeBytes(): Long {
        return artCacheDir.listFiles()?.sumOf { it.length() } ?: 0L
    }

    private suspend fun scanTreeInternal(
        resolver: ContentResolver,
        treeUri: Uri,
        treeUriStr: String
    ): Int {
        val rootDocId = DocumentsContract.getTreeDocumentId(treeUri)
        val audioDocs = mutableListOf<ScannedDoc>()
        val lrcDocsByFolderAndStem = mutableMapOf<String, String>()

        collectDocumentsRecursive(
            resolver = resolver,
            treeUri = treeUri,
            parentDocId = rootDocId,
            depth = 0,
            audioOut = audioDocs,
            lrcMapOut = lrcDocsByFolderAndStem
        )

        val entities = audioDocs.mapNotNull { doc ->
            extractTrackEntity(resolver, treeUriStr, doc, lrcDocsByFolderAndStem)
        }
        libraryDao.deleteLocalTracksByFolder(treeUriStr)
        if (entities.isNotEmpty()) {
            libraryDao.upsertLocalTracks(entities)
        }
        return entities.size
    }

    private data class ScannedDoc(
        val documentId: String,
        val parentDocId: String,
        val displayName: String,
        val mimeType: String,
        val sizeBytes: Long,
        val docUri: Uri
    )

    private fun collectDocumentsRecursive(
        resolver: ContentResolver,
        treeUri: Uri,
        parentDocId: String,
        depth: Int,
        audioOut: MutableList<ScannedDoc>,
        lrcMapOut: MutableMap<String, String>
    ) {
        if (depth > 4) return
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentDocId)
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE
        )

        val subDirs = mutableListOf<String>()
        runCatching {
            resolver.query(childrenUri, projection, null, null, null)?.use { cursor ->
                val idIdx = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                val nameIdx = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                val mimeIdx = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE)
                val sizeIdx = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_SIZE)

                while (cursor.moveToNext()) {
                    val docId = if (idIdx >= 0) cursor.getString(idIdx).orEmpty() else continue
                    val name = if (nameIdx >= 0) cursor.getString(nameIdx).orEmpty() else ""
                    val mime = if (mimeIdx >= 0) cursor.getString(mimeIdx).orEmpty() else ""
                    val size = if (sizeIdx >= 0 && !cursor.isNull(sizeIdx)) cursor.getLong(sizeIdx) else 0L

                    if (mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                        if (!name.startsWith(".")) {
                            subDirs.add(docId)
                        }
                    } else if (name.endsWith(".lrc", ignoreCase = true)) {
                        val docUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId)
                        val stem = name.substringBeforeLast('.').lowercase().trim()
                        lrcMapOut["$parentDocId|$stem"] = docUri.toString()
                    } else if (isSupportedAudioFile(name, mime)) {
                        val docUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId)
                        audioOut.add(
                            ScannedDoc(
                                documentId = docId,
                                parentDocId = parentDocId,
                                displayName = name,
                                mimeType = mime,
                                sizeBytes = size,
                                docUri = docUri
                            )
                        )
                    }
                }
            }
        }

        for (subId in subDirs) {
            collectDocumentsRecursive(resolver, treeUri, subId, depth + 1, audioOut, lrcMapOut)
        }
    }

    private fun extractTrackEntity(
        resolver: ContentResolver,
        folderTreeUri: String,
        doc: ScannedDoc,
        lrcMap: Map<String, String>
    ): LocalTrackEntity? {
        val localId = buildLocalTrackId(doc.docUri.toString())
        val fallbackTitle = doc.displayName.substringBeforeLast('.').ifBlank { "Pista local" }
        val stem = fallbackTitle.lowercase().trim()
        val lrcUri = lrcMap["${doc.parentDocId}|$stem"]

        var title = fallbackTitle
        var artist = "Archivo local"
        var album: String? = null
        var durationMs = 0L
        var bitrateBps = 0
        var sampleRateHz = 0
        var artworkUri = ""

        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(context, doc.docUri)
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)
                ?.takeIf { it.isNotBlank() }?.let { title = it.trim() }
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)
                ?.takeIf { it.isNotBlank() }?.let { artist = it.trim() }
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM)
                ?.takeIf { it.isNotBlank() }?.let { album = it.trim() }
            durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull() ?: 0L
            bitrateBps = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE)
                ?.toIntOrNull() ?: 0
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                sampleRateHz = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_SAMPLERATE)
                    ?.toIntOrNull() ?: 0
            }
            val embeddedArt = retriever.embeddedPicture
            if (embeddedArt != null && embeddedArt.isNotEmpty()) {
                val safeName = localId.removePrefix(LOCAL_ID_PREFIX)
                val artFile = File(artCacheDir, "$safeName.jpg")
                artFile.writeBytes(embeddedArt)
                artworkUri = Uri.fromFile(artFile).toString()
            }
        } catch (_: Throwable) {
            // Keep filename fallback metadata if extractor fails on exotic tags
        } finally {
            runCatching { retriever.release() }
        }

        val resolvedMime = doc.mimeType.ifBlank {
            resolver.getType(doc.docUri) ?: guessMimeFromName(doc.displayName)
        }

        return LocalTrackEntity(
            videoId = localId,
            contentUri = doc.docUri.toString(),
            folderTreeUri = folderTreeUri,
            title = title,
            artist = artist,
            album = album,
            artworkUrl = artworkUri,
            lrcUri = lrcUri,
            durationText = formatDuration(durationMs),
            durationMs = durationMs,
            mimeType = resolvedMime,
            bitrateBps = bitrateBps,
            sampleRateHz = sampleRateHz,
            fileSizeBytes = doc.sizeBytes
        )
    }

    companion object {
        const val LOCAL_ID_PREFIX = "local_"

        private val AUDIO_EXTENSIONS = setOf(
            "mp3", "m4a", "aac", "flac", "opus", "ogg", "wav", "alac", "webm", "wma", "aiff"
        )

        fun isSupportedAudioFile(displayName: String, mimeType: String): Boolean {
            if (mimeType.startsWith("audio/", ignoreCase = true)) return true
            val ext = displayName.substringAfterLast('.', "").lowercase()
            return ext in AUDIO_EXTENSIONS
        }

        fun guessMimeFromName(name: String): String {
            return when (name.substringAfterLast('.', "").lowercase()) {
                "mp3" -> "audio/mpeg"
                "m4a", "aac" -> "audio/mp4"
                "flac" -> "audio/flac"
                "opus", "ogg" -> "audio/ogg"
                "wav" -> "audio/wav"
                "webm" -> "audio/webm"
                else -> "audio/*"
            }
        }

        fun buildLocalTrackId(uriString: String): String {
            val digest = MessageDigest.getInstance("SHA-1")
                .digest(uriString.toByteArray(Charsets.UTF_8))
                .take(10)
                .joinToString("") { "%02x".format(it) }
            return "$LOCAL_ID_PREFIX$digest"
        }

        fun formatDuration(durationMs: Long): String {
            if (durationMs <= 0L) return ""
            val totalSec = durationMs / 1000L
            return "%d:%02d".format(totalSec / 60L, totalSec % 60L)
        }

        fun friendlyFolderLabel(treeUriStr: String): String {
            val decoded = runCatching { Uri.decode(treeUriStr) }.getOrDefault(treeUriStr)
            val afterColon = decoded.substringAfterLast(':', decoded)
            return afterColon.substringAfterLast('/').ifBlank { afterColon.ifBlank { "Carpeta local" } }
        }
    }
}
