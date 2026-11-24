package com.example.ui.player

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.model.Track
import com.example.ui.components.SonoraArtwork

/**
 * Dual-deck transition indicator and incoming-track deck preview displayed over
 * the player artwork during an active AutoMix or crossfade transition.
 */
@Composable
fun AutoMixCoverOverlay(
    nextTrack: Track?,
    autoMixStatus: String,
    isPlaying: Boolean,
    reducedMotion: Boolean,
    accent: Color,
    modifier: Modifier = Modifier
) {
    val spinTransition = rememberInfiniteTransition(label = "automix_deck_spin")
    val deckRotation by spinTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 10_000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "deck_rotation"
    )
    val effectiveRotation = if (isPlaying && !reducedMotion) deckRotation else 0f

    Row(
        modifier = modifier
            .clip(RoundedCornerShape(18.dp))
            .background(
                Brush.horizontalGradient(
                    listOf(Color(0xD90E1018), Color(0xBD181C2B))
                )
            )
            .border(0.8.dp, accent.copy(alpha = 0.55f), RoundedCornerShape(18.dp))
            .padding(horizontal = 10.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Box(modifier = Modifier.size(38.dp), contentAlignment = Alignment.Center) {
            if (nextTrack != null) {
                Box(
                    modifier = Modifier
                        .size(28.dp)
                        .offset(x = 6.dp)
                        .clip(CircleShape)
                        .border(1.dp, accent.copy(alpha = 0.7f), CircleShape)
                        .graphicsLayer { rotationZ = effectiveRotation * 0.75f }
                ) {
                    SonoraArtwork(
                        url = nextTrack.artworkUrl,
                        contentDescription = nextTrack.title,
                        modifier = Modifier.fillMaxSize(),
                        cornerRadius = 14.dp
                    )
                }
            }
            Surface(
                shape = CircleShape,
                color = accent.copy(alpha = 0.92f),
                border = BorderStroke(1.dp, Color.White.copy(alpha = 0.55f)),
                modifier = Modifier
                    .size(26.dp)
                    .offset(x = if (nextTrack != null) (-6).dp else 0.dp)
                    .graphicsLayer { rotationZ = effectiveRotation }
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.Rounded.AutoAwesome,
                        contentDescription = "AutoMix activo",
                        tint = Color.White,
                        modifier = Modifier.size(14.dp)
                    )
                }
            }
        }

        Column(modifier = Modifier.weight(1f, fill = false)) {
            Text(
                text = autoMixStatus,
                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                color = Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (nextTrack != null) {
                Text(
                    text = "Siguiente: ${nextTrack.title}",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White.copy(alpha = 0.70f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        Spacer(Modifier.width(2.dp))
    }
}
