package com.example.ui.player

import android.content.Context
import android.provider.Settings
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp

const val QUEUE_CARRY_FRACTION: Float = 0.34f
private const val SKIP_MOTION_MS = 600

enum class PlayerMotionDirection {
    NEXT,
    PREVIOUS
}

fun isReducedMotionEnabled(context: Context): Boolean {
    return runCatching {
        Settings.Global.getFloat(
            context.contentResolver,
            Settings.Global.ANIMATOR_DURATION_SCALE,
            1f
        ) == 0f
    }.getOrDefault(false)
}

@Stable
class PlayerHaptics(private val haptic: HapticFeedback) {
    fun tick() {
        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
    }

    fun confirm() {
        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
    }
}

@Composable
fun rememberPlayerHaptics(): PlayerHaptics {
    val feedback = LocalHapticFeedback.current
    return remember(feedback) { PlayerHaptics(feedback) }
}

@Stable
class QueueCarryState {
    var dragOffsetPx by mutableFloatStateOf(0f)
    var thresholdPx by mutableFloatStateOf(220f)

    val carryProgress: Float
        get() = ((-dragOffsetPx) / thresholdPx.coerceAtLeast(1f)).coerceIn(0f, 1f)

    fun reset() {
        dragOffsetPx = 0f
    }
}

@Composable
fun rememberQueueCarryState(): QueueCarryState {
    return remember { QueueCarryState() }
}

@Composable
fun ExpandedPlayPauseMotion(
    isPlaying: Boolean,
    reducedMotion: Boolean,
    modifier: Modifier = Modifier,
    content: @Composable (Modifier) -> Unit
) {
    val targetScale = 1.0f
    val targetElevation = if (isPlaying) 24.dp else 10.dp

    val animatedScale by animateFloatAsState(
        targetValue = targetScale,
        animationSpec = if (reducedMotion) {
            tween(durationMillis = 0)
        } else {
            spring(
                dampingRatio = 0.78f,
                stiffness = Spring.StiffnessMediumLow
            )
        },
        label = "expanded_play_pause_scale"
    )
    val animatedElevation by animateDpAsState(
        targetValue = targetElevation,
        animationSpec = tween(durationMillis = if (reducedMotion) 0 else 280),
        label = "expanded_play_pause_elevation"
    )

    content(
        modifier
            .graphicsLayer {
                scaleX = animatedScale
                scaleY = animatedScale
            }
            .shadow(
                elevation = animatedElevation,
                shape = RoundedCornerShape(20.dp),
                ambientColor = Color.Black,
                spotColor = Color.Black
            )
    )
}

@Composable
fun <T> PlayerSkipMotion(
    targetState: T,
    direction: PlayerMotionDirection,
    reducedMotion: Boolean,
    modifier: Modifier = Modifier,
    content: @Composable (T) -> Unit
) {
    AnimatedContent(
        targetState = targetState,
        transitionSpec = {
            if (reducedMotion) {
                fadeIn(tween(120)) togetherWith fadeOut(tween(120))
            } else {
                fadeIn(tween(durationMillis = SKIP_MOTION_MS, easing = FastOutSlowInEasing)) togetherWith
                    fadeOut(tween(durationMillis = 480, easing = FastOutSlowInEasing))
            }
        },
        label = "player_skip_crossfade",
        modifier = modifier
    ) { state ->
        content(state)
    }
}

@Composable
fun <T> SlidingSongInfo(
    targetState: T,
    direction: PlayerMotionDirection,
    reducedMotion: Boolean,
    modifier: Modifier = Modifier,
    content: @Composable (T) -> Unit
) {
    AnimatedContent(
        targetState = targetState,
        transitionSpec = {
            if (reducedMotion) {
                fadeIn(tween(100)) togetherWith fadeOut(tween(100))
            } else {
                val enterSign = if (direction == PlayerMotionDirection.NEXT) 1 else -1
                val exitSign = -enterSign
                (slideInHorizontally(
                    animationSpec = tween(380, easing = FastOutSlowInEasing),
                    initialOffsetX = { (it * 0.28f * enterSign).toInt() }
                ) + fadeIn(tween(260))) togetherWith (slideOutHorizontally(
                    animationSpec = tween(320, easing = FastOutSlowInEasing),
                    targetOffsetX = { (it * 0.28f * exitSign).toInt() }
                ) + fadeOut(tween(200)))
            }
        },
        label = "sliding_song_info",
        modifier = modifier
    ) { state ->
        content(state)
    }
}
