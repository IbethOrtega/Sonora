package com.example.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.os.Build
import android.widget.RemoteViews
import coil.ImageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
import com.example.MainActivity
import com.example.R
import com.example.model.PersistedQueueState
import com.example.model.Track
import com.example.player.SonoraPlaybackService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import java.io.File

class SonoraMediaWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        val snapshot = readLatestWidgetSnapshot(context)
        renderAll(context, appWidgetManager, appWidgetIds, snapshot)
    }

    companion object {
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        private val json = Json { ignoreUnknownKeys = true }

        @Volatile
        private var lastVideoId: String = ""
        @Volatile
        private var lastTitle: String = ""
        @Volatile
        private var lastArtist: String = ""
        @Volatile
        private var lastArtworkUrl: String = ""
        @Volatile
        private var lastIsPlaying: Boolean = false
        @Volatile
        private var lastIsLiked: Boolean = false
        @Volatile
        private var lastPositionMs: Long = 0L
        @Volatile
        private var lastDurationMs: Long = 0L
        @Volatile
        private var lastRenderedProgressStep: Int = -1
        @Volatile
        private var cachedArtworkUrl: String = ""
        @Volatile
        private var cachedBitmap: Bitmap? = null

        fun updateWidgets(
            context: Context,
            title: String,
            artist: String,
            artworkUrl: String,
            isPlaying: Boolean,
            videoId: String = lastVideoId,
            positionMs: Long = lastPositionMs,
            durationMs: Long = lastDurationMs,
            isLiked: Boolean? = null
        ) {
            if (videoId != lastVideoId) {
                lastVideoId = videoId
                lastRenderedProgressStep = -1
            }
            lastTitle = title
            lastArtist = artist
            lastArtworkUrl = artworkUrl
            lastIsPlaying = isPlaying
            lastPositionMs = positionMs.coerceAtLeast(0L)
            lastDurationMs = durationMs.coerceAtLeast(0L)
            if (isLiked != null) {
                lastIsLiked = isLiked
            }

            val appContext = context.applicationContext
            val mgr = AppWidgetManager.getInstance(appContext)
            val ids = mgr.getAppWidgetIds(ComponentName(appContext, SonoraMediaWidgetProvider::class.java))
            if (ids.isEmpty()) return

            renderAll(
                context = appContext,
                appWidgetManager = mgr,
                appWidgetIds = ids,
                data = currentDisplayData()
            )

            if (artworkUrl.isNotBlank() && artworkUrl != cachedArtworkUrl) {
                scope.launch {
                    val bmp = runCatching {
                        val req = ImageRequest.Builder(appContext)
                            .data(artworkUrl)
                            .allowHardware(false)
                            .size(160)
                            .build()
                        val res = ImageLoader(appContext).execute(req)
                        (res as? SuccessResult)?.drawable?.let { it as? BitmapDrawable }?.bitmap
                    }.getOrNull()
                    if (bmp != null) {
                        cachedArtworkUrl = artworkUrl
                        cachedBitmap = bmp
                        renderAll(
                            context = appContext,
                            appWidgetManager = mgr,
                            appWidgetIds = ids,
                            data = currentDisplayData()
                        )
                    }
                }
            }
        }

        fun updateProgress(context: Context, positionMs: Long, durationMs: Long) {
            val safePos = positionMs.coerceAtLeast(0L)
            val safeDur = durationMs.coerceAtLeast(0L)
            lastPositionMs = safePos
            lastDurationMs = safeDur
            val step = computeProgressPermille(safePos, safeDur)
            if (step == lastRenderedProgressStep) return
            lastRenderedProgressStep = step

            val appContext = context.applicationContext
            val mgr = AppWidgetManager.getInstance(appContext)
            val ids = mgr.getAppWidgetIds(ComponentName(appContext, SonoraMediaWidgetProvider::class.java))
            if (ids.isEmpty()) return

            renderAll(appContext, mgr, ids, currentDisplayData())
        }

        fun updateLikedState(context: Context, videoId: String?, isLiked: Boolean) {
            if (!videoId.isNullOrBlank() && lastVideoId.isNotBlank() && videoId != lastVideoId) {
                return
            }
            lastIsLiked = isLiked
            val appContext = context.applicationContext
            val mgr = AppWidgetManager.getInstance(appContext)
            val ids = mgr.getAppWidgetIds(ComponentName(appContext, SonoraMediaWidgetProvider::class.java))
            if (ids.isEmpty()) return
            val data = if (lastTitle.isNotBlank()) currentDisplayData() else readLatestWidgetSnapshot(appContext).copy(isLiked = isLiked)
            renderAll(appContext, mgr, ids, data)
        }

        fun requestRefreshFromService(context: Context, isPlaying: Boolean) {
            lastIsPlaying = isPlaying
            val data = if (lastTitle.isNotBlank()) {
                currentDisplayData()
            } else {
                readLatestWidgetSnapshot(context).copy(isPlaying = isPlaying)
            }
            val appContext = context.applicationContext
            val mgr = AppWidgetManager.getInstance(appContext)
            val ids = mgr.getAppWidgetIds(ComponentName(appContext, SonoraMediaWidgetProvider::class.java))
            if (ids.isNotEmpty()) {
                renderAll(appContext, mgr, ids, data)
            }
        }

        fun readLatestTrack(context: Context): Track? {
            val file = File(context.applicationContext.filesDir, "queue_state.json")
            val persisted = runCatching {
                if (!file.exists()) return@runCatching null
                json.decodeFromString<PersistedQueueState>(file.readText())
            }.getOrNull() ?: return null
            return persisted.queue.getOrNull(persisted.currentIndex.coerceAtLeast(0))
        }

        private fun currentDisplayData(): WidgetDisplayData = WidgetDisplayData(
            title = lastTitle,
            artist = lastArtist,
            artworkUrl = lastArtworkUrl,
            isPlaying = lastIsPlaying,
            isLiked = lastIsLiked,
            positionMs = lastPositionMs,
            durationMs = lastDurationMs
        )

        private fun computeProgressPermille(positionMs: Long, durationMs: Long): Int {
            if (durationMs <= 0L) return 0
            return ((positionMs.toDouble() / durationMs.toDouble()) * 1000.0)
                .toInt()
                .coerceIn(0, 1000)
        }

        private data class WidgetDisplayData(
            val title: String,
            val artist: String,
            val artworkUrl: String,
            val isPlaying: Boolean,
            val isLiked: Boolean,
            val positionMs: Long,
            val durationMs: Long
        )

        private fun readLatestWidgetSnapshot(context: Context): WidgetDisplayData {
            if (lastTitle.isNotBlank()) {
                return currentDisplayData()
            }
            val file = File(context.filesDir, "queue_state.json")
            val persisted = runCatching {
                if (!file.exists()) return@runCatching null
                json.decodeFromString<PersistedQueueState>(file.readText())
            }.getOrNull()
            val track = persisted?.queue?.getOrNull(persisted.currentIndex.coerceAtLeast(0))
            return if (track != null) {
                lastVideoId = track.videoId
                WidgetDisplayData(
                    title = track.title,
                    artist = track.artist,
                    artworkUrl = track.artworkUrl,
                    isPlaying = false,
                    isLiked = lastIsLiked,
                    positionMs = persisted.positionMs.coerceAtLeast(0L),
                    durationMs = track.durationMs.coerceAtLeast(0L)
                )
            } else {
                WidgetDisplayData(
                    title = "Listo para reproducir",
                    artist = "Toca para abrir Sonora",
                    artworkUrl = "",
                    isPlaying = false,
                    isLiked = false,
                    positionMs = 0L,
                    durationMs = 0L
                )
            }
        }

        private fun renderAll(
            context: Context,
            appWidgetManager: AppWidgetManager,
            appWidgetIds: IntArray,
            data: WidgetDisplayData
        ) {
            val openAppIntent = PendingIntent.getActivity(
                context,
                1001,
                Intent(context, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_NEW_TASK
                    putExtra(MainActivity.EXTRA_OPEN_PLAYER, true)
                },
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val openQueueIntent = PendingIntent.getActivity(
                context,
                1002,
                Intent(context, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_NEW_TASK
                    putExtra(MainActivity.EXTRA_OPEN_PLAYER, true)
                    putExtra(MainActivity.EXTRA_OPEN_PLAYER_QUEUE, true)
                },
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            fun servicePendingIntent(action: String, reqCode: Int): PendingIntent {
                val intent = Intent(context, SonoraPlaybackService::class.java).apply {
                    this.action = action
                }
                return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    PendingIntent.getService(
                        context,
                        reqCode,
                        intent,
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                    )
                } else {
                    PendingIntent.getService(
                        context,
                        reqCode,
                        intent,
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                    )
                }
            }

            val progressPermille = computeProgressPermille(data.positionMs, data.durationMs)
            val views = RemoteViews(context.packageName, R.layout.widget_sonora_player).apply {
                setTextViewText(R.id.widget_title, data.title.ifBlank { "Sonora" })
                setTextViewText(R.id.widget_artist, data.artist.ifBlank { "Sin pista activa" })
                setProgressBar(R.id.widget_progress, 1000, progressPermille, false)
                setImageViewResource(
                    R.id.widget_play_pause,
                    if (data.isPlaying) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play
                )
                setImageViewResource(
                    R.id.widget_like,
                    if (data.isLiked) R.drawable.ic_notif_favorite_filled else R.drawable.ic_notif_favorite_border
                )
                val bmp = if (data.artworkUrl.isNotBlank() && data.artworkUrl == cachedArtworkUrl) cachedBitmap else null
                if (bmp != null) {
                    setImageViewBitmap(R.id.widget_artwork, bmp)
                } else {
                    setImageViewResource(R.id.widget_artwork, R.mipmap.ic_launcher)
                }
                setOnClickPendingIntent(R.id.widget_root, openAppIntent)
                setOnClickPendingIntent(R.id.widget_queue, openQueueIntent)
                setOnClickPendingIntent(
                    R.id.widget_like,
                    servicePendingIntent(SonoraPlaybackService.ACTION_WIDGET_TOGGLE_LIKE, 2004)
                )
                setOnClickPendingIntent(
                    R.id.widget_play_pause,
                    servicePendingIntent(SonoraPlaybackService.ACTION_WIDGET_PLAY_PAUSE, 2001)
                )
                setOnClickPendingIntent(
                    R.id.widget_next,
                    servicePendingIntent(SonoraPlaybackService.ACTION_WIDGET_NEXT, 2002)
                )
                setOnClickPendingIntent(
                    R.id.widget_prev,
                    servicePendingIntent(SonoraPlaybackService.ACTION_WIDGET_PREV, 2003)
                )
            }

            appWidgetIds.forEach { widgetId ->
                appWidgetManager.updateAppWidget(widgetId, views)
            }
        }
    }
}
