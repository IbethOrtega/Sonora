package com.example.ui.player

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.example.model.CanvasArtwork
import com.example.model.Track

/**
 * Clean-room Apple Music-inspired square artwork/canvas slot with smooth crossfade
 * between tracks and zero pill/equalizer clutter over the cover art.
 */
@Composable
fun MusicVideoLayout(
    track: Track,
    canvasArtwork: CanvasArtwork?,
    animatedArtworkEnabled: Boolean,
    allowMobileData: Boolean,
    isPlaying: Boolean,
    isPlayerActive: Boolean,
    isWideHero: Boolean,
    motionDirection: PlayerMotionDirection,
    reducedMotion: Boolean,
    accent: Color,
    modifier: Modifier = Modifier
) {
    val cornerRadius = 20.dp
    Box(
        modifier = modifier
            .then(
                if (isWideHero) Modifier.widthIn(max = 360.dp).fillMaxWidth(0.84f)
                else Modifier.fillMaxWidth().widthIn(max = 360.dp)
            )
            .aspectRatio(1f),
        contentAlignment = Alignment.Center
    ) {
        PlayerSkipMotion(
            targetState = track,
            direction = motionDirection,
            reducedMotion = reducedMotion,
            modifier = Modifier.fillMaxSize()
        ) { animTrack ->
            CanvasArtworkPlayer(
                track = animTrack,
                canvasArtwork = if (animTrack.videoId == track.videoId) canvasArtwork else null,
                animatedArtworkEnabled = animatedArtworkEnabled,
                allowMobileData = allowMobileData,
                preferPortrait = false,
                isPlaying = isPlaying,
                isPlayerActive = isPlayerActive,
                ambientPrimary = accent,
                showStaticFallback = true,
                modifier = Modifier
                    .fillMaxSize()
                    .clip(RoundedCornerShape(cornerRadius))
                    .border(1.dp, Color.White.copy(alpha = 0.14f), RoundedCornerShape(cornerRadius))
            )
        }
    }
}
