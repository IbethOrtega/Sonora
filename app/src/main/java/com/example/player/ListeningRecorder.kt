package com.example.player

import com.example.model.Track

/**
 * Sample-based listening recorder that measures real continuous playback time (immune to seek jumps)
 * and commits a valid listening session once playback reaches either >= 30s or >= 50% of track duration.
 */
class ListeningRecorder(
    private val nowMs: () -> Long = { System.currentTimeMillis() },
    private val onCommitPlay: suspend (track: Track, playedAtMs: Long, listenedMs: Long) -> Long?,
    private val onUpdateListenedMs: suspend (rowId: Long, listenedMs: Long) -> Unit = { _, _ -> }
) {
    private data class ActiveSession(
        val key: String,
        val track: Track,
        val playedAtMs: Long,
        var accumulatedMs: Long = 0L,
        var lastSampleWallMs: Long = 0L,
        var committedRowId: Long? = null,
        var hasCommitted: Boolean = false,
        var lastPersistedListenedMs: Long = 0L
    )

    private var active: ActiveSession? = null

    suspend fun onTrackChanged(track: Track?, queueIndex: Int, isPlaying: Boolean) {
        flush()
        if (track == null || track.videoId.isBlank()) {
            active = null
            return
        }
        val key = "$queueIndex:${track.videoId}"
        val now = nowMs()
        active = ActiveSession(
            key = key,
            track = track,
            playedAtMs = now,
            lastSampleWallMs = if (isPlaying) now else 0L
        )
    }

    fun onPlayingChanged(isPlaying: Boolean) {
        val current = active ?: return
        current.lastSampleWallMs = if (isPlaying) nowMs() else 0L
    }

    /**
     * Resets the tick anchor so seeking forward/backward does not artificially credit listening time.
     */
    fun onSeek(isPlaying: Boolean) {
        val current = active ?: return
        current.lastSampleWallMs = if (isPlaying) nowMs() else 0L
    }

    /**
     * Advances the active session by the clamped real elapsed wall time since the last sample tick.
     * Commits once validity threshold (>= 30s or >= 50% of duration) is met, and periodically syncs
     * listenedMs for committed rows.
     */
    suspend fun onSampleTick(
        track: Track,
        queueIndex: Int,
        isPlaying: Boolean,
        measuredDurationMs: Long = track.durationMs
    ) {
        val key = "$queueIndex:${track.videoId}"
        if (active?.key != key) {
            onTrackChanged(track, queueIndex, isPlaying)
        }
        val session = active ?: return
        if (!isPlaying) {
            session.lastSampleWallMs = 0L
            return
        }

        val now = nowMs()
        val delta = if (session.lastSampleWallMs > 0L) {
            (now - session.lastSampleWallMs).coerceIn(0L, MAX_SAMPLE_DELTA_MS)
        } else {
            0L
        }
        session.lastSampleWallMs = now
        session.accumulatedMs += delta

        val effectiveDuration = if (measuredDurationMs > 0L) measuredDurationMs else session.track.durationMs
        val isValidPlay = session.accumulatedMs >= MIN_VALID_LISTEN_MS ||
            (effectiveDuration > 0L && session.accumulatedMs >= (effectiveDuration / 2L).coerceAtLeast(1_000L))

        if (isValidPlay && !session.hasCommitted) {
            session.hasCommitted = true
            val rowId = runCatching {
                onCommitPlay(session.track, session.playedAtMs, session.accumulatedMs)
            }.getOrNull()
            session.committedRowId = rowId
            session.lastPersistedListenedMs = session.accumulatedMs
        } else if (session.hasCommitted) {
            val rowId = session.committedRowId
            if (rowId != null && session.accumulatedMs - session.lastPersistedListenedMs >= UPDATE_INTERVAL_MS) {
                session.lastPersistedListenedMs = session.accumulatedMs
                runCatching { onUpdateListenedMs(rowId, session.accumulatedMs) }
            }
        }
    }

    suspend fun flush() {
        val session = active ?: return
        val rowId = session.committedRowId ?: return
        if (session.accumulatedMs > session.lastPersistedListenedMs) {
            session.lastPersistedListenedMs = session.accumulatedMs
            runCatching { onUpdateListenedMs(rowId, session.accumulatedMs) }
        }
    }

    fun currentAccumulatedMs(): Long = active?.accumulatedMs ?: 0L

    companion object {
        const val MIN_VALID_LISTEN_MS = 30_000L
        private const val MAX_SAMPLE_DELTA_MS = 600L
        private const val UPDATE_INTERVAL_MS = 8_000L
    }
}
