package com.example.ui.player

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.model.LyricLine
import com.example.model.TrackLyrics
import kotlinx.coroutines.flow.StateFlow

@Immutable
data class LyricClockSnapshot(
    val effectivePositionMs: Long = 0L,
    val activeIndex: Int = -1,
    val activeLineProgress: Float = 0f,
    val activeWordIndex: Int = -1,
    val activeWordProgress: Float = 0f
)

/**
 * Clean-room timing evaluator for line-synced and word-synced lyrics.
 * Isolated from outer player UI so 250ms position emissions only invalidate
 * composables that explicitly observe the clock.
 */
fun computeLyricClockSnapshot(
    lyrics: TrackLyrics?,
    positionMs: Long,
    durationMs: Long,
    offsetMs: Long
): LyricClockSnapshot {
    val lines = lyrics?.lines.orEmpty()
    val effectivePos = (positionMs + offsetMs).coerceAtLeast(0L)
    if (lines.isEmpty()) {
        return LyricClockSnapshot(effectivePositionMs = effectivePos)
    }

    var activeIdx = -1
    for (i in lines.indices) {
        if (lines[i].timeMs <= effectivePos + 160L) {
            activeIdx = i
        } else {
            break
        }
    }
    if (activeIdx !in lines.indices) {
        return LyricClockSnapshot(effectivePositionMs = effectivePos)
    }

    val activeLine = lines[activeIdx]
    val nextStart = lines.getOrNull(activeIdx + 1)?.timeMs
        ?: durationMs.takeIf { it > activeLine.timeMs }
        ?: (activeLine.timeMs + 4_500L)
    val lineSpan = (nextStart - activeLine.timeMs).coerceAtLeast(500L)
    val lineProgress = ((effectivePos - activeLine.timeMs).toFloat() / lineSpan.toFloat()).coerceIn(0f, 1f)

    var wordIdx = -1
    var wordProgress = 0f
    val words = activeLine.words
    if (words.isNotEmpty()) {
        for (wIdx in words.indices) {
            val w = words[wIdx]
            if (effectivePos + 60L >= w.startMs) {
                wordIdx = wIdx
                val wordSpan = (w.endMs - w.startMs).coerceAtLeast(120L)
                wordProgress = ((effectivePos - w.startMs).toFloat() / wordSpan.toFloat()).coerceIn(0f, 1f)
            } else {
                break
            }
        }
    }

    return LyricClockSnapshot(
        effectivePositionMs = effectivePos,
        activeIndex = activeIdx,
        activeLineProgress = lineProgress,
        activeWordIndex = wordIdx,
        activeWordProgress = wordProgress
    )
}

@Composable
fun rememberLyricClockState(
    lyrics: TrackLyrics?,
    positionMsFlow: StateFlow<Long>,
    durationMs: Long,
    offsetMs: Long
): State<LyricClockSnapshot> {
    val positionMs by positionMsFlow.collectAsStateWithLifecycle()
    return remember(lyrics, durationMs, offsetMs) {
        derivedStateOf {
            computeLyricClockSnapshot(
                lyrics = lyrics,
                positionMs = positionMs,
                durationMs = durationMs,
                offsetMs = offsetMs
            )
        }
    }
}
