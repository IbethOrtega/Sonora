package com.example.ui.player

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AspectRatio
import androidx.compose.material.icons.rounded.MovieFilter
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.model.CanvasArtwork
import com.example.model.Track

/**
 * Clean-room layout container for the hero artwork, animated Motion Canvas preview,
 * aspect toggle when a live canvas video is loaded, and AutoMix dual-deck transition badge.
 */
@Composable
fun MusicVideoLayout(
    track: Track,
    nextTrack: Track?,
    canvasArtwork: CanvasArtwork?,
    animatedArtworkEnabled: Boolean,
    animatedArtworkWifiOnly: Boolean,
    isPlaying: Boolean,
    isPlayerActive: Boolean,
    isWideHero: Boolean,
    motionDirection: PlayerMotionDirection,
    reducedMotion: Boolean,
    autoMixStatus: String?,
    showAutoMixBadge: Boolean,
    accent: Color,
    modifier: Modifier = Modifier
) {
    var cinemaExpanded by remember(track.videoId) { mutableStateOf(false) }
    val hasCanvasVideo = animatedArtworkEnabled && canvasArtwork != null
    val cornerRadius by animateDpAsState(
        targetValue = if (cinemaExpanded && !isWideHero) 12.dp else 20.dp,
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label = "artwork_corner_radius"
    )
    val aspect = if (cinemaExpanded && hasCanvasVideo && !isWideHero) 1.15f else 1f

    Box(
        modifier = modifier
            .then(
                if (isWideHero) Modifier.widthIn(max = 360.dp).fillMaxWidth(0.84f)
                else Modifier.fillMaxWidth().widthIn(max = if (cinemaExpanded && hasCanvasVideo) 420.dp else 360.dp)
            )
            .aspectRatio(aspect),
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
                wifiOnly = animatedArtworkWifiOnly,
                isPlaying = isPlaying,
                isPlayerActive = isPlayerActive,
                ambientPrimary = accent,
                modifier = Modifier
                    .fillMaxSize()
                    .clip(RoundedCornerShape(cornerRadius))
                    .border(1.dp, Color.White.copy(alpha = 0.14f), RoundedCornerShape(cornerRadius))
            )
        }

        if (hasCanvasVideo) {
            Surface(
                shape = RoundedCornerShape(50),
                color = Color(0x990D0F16),
                border = BorderStroke(0.6.dp, Color.White.copy(alpha = 0.24f)),
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(10.dp)
                    .clip(RoundedCornerShape(50))
                    .clickable { cinemaExpanded = !cinemaExpanded }
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 9.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Icon(
                        imageVector = if (cinemaExpanded) Icons.Rounded.AspectRatio else Icons.Rounded.MovieFilter,
                        contentDescription = "Formato de Canvas",
                        tint = Color.White.copy(alpha = 0.92f),
                        modifier = Modifier.size(13.dp)
                    )
                    Text(
                        text = if (cinemaExpanded) "Cine" else "Canvas",
                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                        color = Color.White.copy(alpha = 0.92f)
                    )
                }
            }
        }

        if (showAutoMixBadge && !autoMixStatus.isNullOrBlank()) {
            AutoMixCoverOverlay(
                nextTrack = nextTrack,
                autoMixStatus = autoMixStatus,
                isPlaying = isPlaying,
                reducedMotion = reducedMotion,
                accent = accent,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(horizontal = 12.dp, vertical = 10.dp)
            )
        }
    }
}
