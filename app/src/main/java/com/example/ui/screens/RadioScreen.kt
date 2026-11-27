package com.example.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Radio
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.model.BrowseCard
import com.example.model.MoodCategory
import com.example.model.Track
import com.example.player.DjLiveState
import com.example.ui.components.GlassCard
import com.example.ui.components.SonoraArtwork
import com.example.ui.theme.LocalSonoraAccent
import com.example.ui.theme.LocalSonoraPalette
import com.example.ui.theme.SonoraTextPrimary
import com.example.ui.theme.SonoraTextSecondary

private data class MoodStationPreset(
    val id: String,
    val title: String,
    val subtitle: String,
    val moodQuery: String,
    val accentColor: Color
)

private val DEFAULT_RADIO_MOODS = listOf(
    MoodStationPreset("chill", "Sonora Chill", "Downtempo, lo-fi y calma nocturna", "chill relax", Color(0xFF4F7CAC)),
    MoodStationPreset("energy", "Energía Pura", "Ritmos altos para entrenar o conducir", "workout energy", Color(0xFFFA2D48)),
    MoodStationPreset("focus", "Enfoque Profundo", "Instrumentales y electrónica sutil", "focus ambient", Color(0xFF34A853)),
    MoodStationPreset("latino", "Éxitos Latinos", "Lo más sonado en español ahora", "latin hits", Color(0xFFFF9F0A))
)

@Composable
fun RadioScreen(
    djLiveState: DjLiveState,
    suggestedSongs: List<Track>,
    recentTracks: List<Track>,
    ytPlaylists: List<BrowseCard>,
    moods: List<MoodCategory>,
    bottomContentPadding: Dp,
    onStartDjLive: () -> Unit,
    onOpenVoiceDj: () -> Unit,
    onStopDjLive: () -> Unit,
    onStartTrackRadio: (Track) -> Unit,
    onOpenPlaylist: (BrowseCard) -> Unit,
    onOpenMood: (MoodCategory) -> Unit,
    onStartQuickMix: (String) -> Unit
) {
    val accent = LocalSonoraAccent.current
    val palette = LocalSonoraPalette.current

    val seedTracks = remember(suggestedSongs, recentTracks) {
        (suggestedSongs + recentTracks)
            .distinctBy { it.videoId }
            .take(8)
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .testTag("radio_screen"),
        contentPadding = PaddingValues(top = 16.dp, bottom = bottomContentPadding)
    ) {
        item {
            Column(modifier = Modifier.padding(horizontal = 20.dp)) {
                Text(
                    text = "Radio",
                    style = MaterialTheme.typography.displayLarge,
                    color = SonoraTextPrimary
                )
                Text(
                    text = "Estaciones en vivo, mixes de artistas y radios infinitas",
                    style = MaterialTheme.typography.bodyMedium,
                    color = SonoraTextSecondary
                )
            }
            Spacer(Modifier.height(18.dp))
        }

        // DJ Sony Live Station Card
        item {
            Column(modifier = Modifier.padding(horizontal = 16.dp)) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(24.dp))
                        .background(
                            Brush.linearGradient(
                                listOf(
                                    accent.copy(alpha = 0.42f),
                                    palette.elevated,
                                    palette.surface
                                )
                            )
                        )
                        .clickable {
                            if (djLiveState.isActive) onOpenVoiceDj() else onStartDjLive()
                        }
                        .padding(18.dp)
                        .testTag("radio_dj_station_card")
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(44.dp)
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(accent.copy(alpha = 0.28f)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.AutoAwesome,
                                    contentDescription = null,
                                    tint = Color.White,
                                    modifier = Modifier.size(24.dp)
                                )
                            }
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = if (djLiveState.isActive) "EN VIVO AHORA" else "ESTACIÓN PERSONAL",
                                    style = MaterialTheme.typography.labelSmall.copy(
                                        fontWeight = FontWeight.Bold,
                                        letterSpacing = 0.8.sp
                                    ),
                                    color = Color.White.copy(alpha = 0.78f)
                                )
                                Text(
                                    text = "DJ Sony Radio",
                                    style = MaterialTheme.typography.headlineMedium,
                                    color = Color.White
                                )
                            }
                        }

                        Text(
                            text = if (djLiveState.isActive) {
                                djLiveState.spokenCaption.ifBlank { "Mezclando tu sesión personalizada con comentarios en vivo." }
                            } else {
                                "Locución inteligente y transiciones AutoMix adaptadas a tu biblioteca."
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            color = Color.White.copy(alpha = 0.82f),
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )

                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Button(
                                onClick = { if (djLiveState.isActive) onStopDjLive() else onStartDjLive() },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = if (djLiveState.isActive) Color.White.copy(alpha = 0.22f) else Color.White,
                                    contentColor = if (djLiveState.isActive) Color.White else Color.Black
                                ),
                                shape = RoundedCornerShape(50),
                                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
                            ) {
                                Icon(
                                    imageVector = if (djLiveState.isActive) Icons.Rounded.Stop else Icons.Rounded.PlayArrow,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    text = if (djLiveState.isActive) "Detener" else "Iniciar estación",
                                    style = MaterialTheme.typography.labelLarge
                                )
                            }

                            Surface(
                                onClick = onOpenVoiceDj,
                                shape = RoundedCornerShape(50),
                                color = Color.White.copy(alpha = 0.14f)
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 9.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.Mic,
                                        contentDescription = null,
                                        tint = Color.White,
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Text(
                                        text = "Pedir vibra",
                                        style = MaterialTheme.typography.labelLarge,
                                        color = Color.White
                                    )
                                }
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(24.dp))
        }

        // Personalized artist/track radio stations
        if (seedTracks.isNotEmpty()) {
            item {
                Text(
                    text = "Estaciones para ti",
                    style = MaterialTheme.typography.headlineMedium,
                    color = SonoraTextPrimary,
                    modifier = Modifier.padding(horizontal = 20.dp)
                )
                Spacer(Modifier.height(12.dp))
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(seedTracks, key = { "radio_seed_${it.videoId}" }) { seed ->
                        Column(
                            modifier = Modifier
                                .width(156.dp)
                                .clip(RoundedCornerShape(16.dp))
                                .clickable { onStartTrackRadio(seed) }
                                .padding(4.dp)
                                .testTag("radio_station_${seed.videoId}")
                        ) {
                            SonoraArtwork(
                                url = seed.artworkUrl,
                                contentDescription = seed.title,
                                modifier = Modifier.size(148.dp),
                                cornerRadius = 14.dp
                            )
                            Spacer(Modifier.height(8.dp))
                            Text(
                                text = "Radio de ${seed.artist.ifBlank { seed.title }}",
                                style = MaterialTheme.typography.labelLarge,
                                color = SonoraTextPrimary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = "Basada en ${seed.title}",
                                style = MaterialTheme.typography.bodySmall,
                                color = SonoraTextSecondary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
                Spacer(Modifier.height(24.dp))
            }
        }

        // Mood & genre quick radio mixes
        item {
            Text(
                text = "Estaciones por momento",
                style = MaterialTheme.typography.headlineMedium,
                color = SonoraTextPrimary,
                modifier = Modifier.padding(horizontal = 20.dp)
            )
            Spacer(Modifier.height(10.dp))
            GlassCard(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                shape = RoundedCornerShape(20.dp)
            ) {
                if (moods.isNotEmpty()) {
                    val visibleMoods = moods.take(6)
                    visibleMoods.forEachIndexed { index, mood ->
                        val moodColor = Color(mood.accentHex.toInt() or 0xFF000000.toInt())
                        RadioListRow(
                            title = "Radio ${mood.title}",
                            subtitle = "Mix continuo seleccionado por estado de ánimo",
                            badgeColor = moodColor,
                            onClick = { onOpenMood(mood) },
                            showDivider = index < visibleMoods.lastIndex
                        )
                    }
                } else {
                    DEFAULT_RADIO_MOODS.forEachIndexed { index, preset ->
                        RadioListRow(
                            title = preset.title,
                            subtitle = preset.subtitle,
                            badgeColor = preset.accentColor,
                            onClick = { onStartQuickMix(preset.moodQuery) },
                            showDivider = index < DEFAULT_RADIO_MOODS.lastIndex
                        )
                    }
                }
            }
            Spacer(Modifier.height(22.dp))
        }

        // Playlist radios if user has YouTube Music mixes/playlists
        if (ytPlaylists.isNotEmpty()) {
            item {
                Text(
                    text = "Mixes y emisoras guardadas",
                    style = MaterialTheme.typography.headlineMedium,
                    color = SonoraTextPrimary,
                    modifier = Modifier.padding(horizontal = 20.dp)
                )
                Spacer(Modifier.height(10.dp))
                GlassCard(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                    shape = RoundedCornerShape(20.dp)
                ) {
                    val visible = ytPlaylists.take(6)
                    visible.forEachIndexed { idx, playlist ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onOpenPlaylist(playlist) }
                                .padding(horizontal = 14.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            SonoraArtwork(
                                url = playlist.artworkUrl,
                                contentDescription = playlist.title,
                                modifier = Modifier.size(48.dp),
                                cornerRadius = 10.dp
                            )
                            Spacer(Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = playlist.title,
                                    style = MaterialTheme.typography.titleMedium,
                                    color = SonoraTextPrimary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = playlist.subtitle.ifBlank { "Estación de mezcla continua" },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = SonoraTextSecondary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            Icon(
                                imageVector = Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                                contentDescription = null,
                                tint = Color.White.copy(alpha = 0.40f),
                                modifier = Modifier.size(20.dp)
                            )
                        }
                        if (idx < visible.lastIndex) {
                            HorizontalDivider(
                                modifier = Modifier.padding(start = 74.dp, end = 14.dp),
                                thickness = 0.5.dp,
                                color = Color.White.copy(alpha = 0.08f)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RadioListRow(
    title: String,
    subtitle: String,
    badgeColor: Color,
    onClick: () -> Unit,
    showDivider: Boolean
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(badgeColor.copy(alpha = 0.24f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Rounded.Radio,
                    contentDescription = null,
                    tint = badgeColor,
                    modifier = Modifier.size(20.dp)
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    color = SonoraTextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = SonoraTextSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Icon(
                imageVector = Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                contentDescription = null,
                tint = Color.White.copy(alpha = 0.40f),
                modifier = Modifier.size(20.dp)
            )
        }
        if (showDivider) {
            HorizontalDivider(
                modifier = Modifier.padding(start = 66.dp, end = 14.dp),
                thickness = 0.5.dp,
                color = Color.White.copy(alpha = 0.08f)
            )
        }
    }
}
