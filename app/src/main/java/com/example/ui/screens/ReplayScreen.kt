package com.example.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Album
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.CalendarMonth
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.model.StatsPeriod
import com.example.model.Track
import com.example.player.PlaybackUiState
import com.example.ui.components.GlassCard
import com.example.ui.components.SonoraArtwork
import com.example.ui.components.TrackRowItem
import com.example.ui.components.sonoraLiquidGlassSurface
import com.example.ui.theme.LocalSonoraAccent
import com.example.ui.theme.LocalSonoraAccentSecondary
import com.example.ui.theme.SonoraTextPrimary
import com.example.ui.theme.SonoraTextSecondary
import com.example.ui.theme.SonoraTextTertiary
import com.example.ui.viewmodel.SonoraViewModel

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ReplayScreen(
    viewModel: SonoraViewModel,
    playbackState: PlaybackUiState,
    bottomContentPadding: Dp,
    onBack: () -> Unit,
    onPlayQueue: (List<Track>, Int) -> Unit,
    onTrackMore: (Track) -> Unit
) {
    val accent = LocalSonoraAccent.current
    val accentSecondary = LocalSonoraAccentSecondary.current
    val period by viewModel.statsPeriod.collectAsState()
    val stats by viewModel.replayStats.collectAsState()

    val hours = stats.estimatedMinutes / 60L
    val mins = stats.estimatedMinutes % 60L
    val totalTimeLabel = if (hours > 0) "${hours}h ${mins}m" else "${mins} min"
    val peakHourLabel = stats.peakHour?.let { String.format("%02d:00", it) } ?: "--"
    val peakDayLabel = when (stats.peakDayOfWeek) {
        1 -> "Lunes"
        2 -> "Martes"
        3 -> "Miércoles"
        4 -> "Jueves"
        5 -> "Viernes"
        6 -> "Sábado"
        7 -> "Domingo"
        else -> "--"
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .testTag("replay_screen")
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(
                onClick = onBack,
                modifier = Modifier
                    .sonoraLiquidGlassSurface(
                        shape = CircleShape,
                        blurRadius = 14.dp,
                        refractionHeight = 6.dp,
                        refractionAmount = 10.dp,
                        surfaceAlpha = 0.46f
                    )
                    .minimumInteractiveComponentSize()
                    .testTag("replay_back_button")
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                    contentDescription = "Volver",
                    tint = SonoraTextPrimary
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.replay_title),
                    style = MaterialTheme.typography.titleLarge,
                    color = SonoraTextPrimary
                )
                Text(
                    text = "Tu actividad y tendencias musicales",
                    style = MaterialTheme.typography.bodyMedium,
                    color = SonoraTextSecondary
                )
            }
        }

        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(StatsPeriod.entries) { item ->
                val selected = item == period
                FilterChip(
                    selected = selected,
                    onClick = { viewModel.setStatsPeriod(item) },
                    label = { Text(item.label) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = accent.copy(alpha = 0.25f),
                        selectedLabelColor = SonoraTextPrimary,
                        containerColor = Color.White.copy(alpha = 0.06f),
                        labelColor = SonoraTextSecondary
                    ),
                    border = FilterChipDefaults.filterChipBorder(
                        enabled = true,
                        selected = selected,
                        borderColor = Color.White.copy(alpha = 0.14f),
                        selectedBorderColor = accent.copy(alpha = 0.65f)
                    ),
                    modifier = Modifier.testTag("replay_period_${item.name.lowercase()}")
                )
            }
        }

        Spacer(Modifier.height(10.dp))

        if (stats.totalPlays == 0) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 28.dp, vertical = 32.dp),
                contentAlignment = Alignment.Center
            ) {
                GlassCard(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(22.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.AutoAwesome,
                            contentDescription = null,
                            tint = accent,
                            modifier = Modifier.size(44.dp)
                        )
                        Spacer(Modifier.height(12.dp))
                        Text(
                            text = stringResource(R.string.replay_empty),
                            style = MaterialTheme.typography.bodyLarge,
                            color = SonoraTextSecondary,
                            textAlign = TextAlign.Center
                        )
                    }
                }
            }
        } else {
            val topTracksQueue = remember(stats.topTracks) { stats.topTracks.map { it.track } }

            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = bottomContentPadding)
            ) {
                item {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp)
                            .clip(RoundedCornerShape(22.dp))
                            .background(
                                Brush.linearGradient(
                                    colors = listOf(
                                        accent.copy(alpha = 0.34f),
                                        accentSecondary.copy(alpha = 0.24f),
                                        Color(0xFF151520)
                                    )
                                )
                            )
                            .padding(20.dp)
                    ) {
                        Column {
                            Text(
                                text = period.label.uppercase(),
                                style = MaterialTheme.typography.labelSmall,
                                color = Color.White.copy(alpha = 0.72f)
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(
                                text = totalTimeLabel,
                                style = MaterialTheme.typography.displayLarge,
                                color = Color.White,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "${stats.totalPlays} reproducciones • ${stats.uniqueTracks} temas distintos",
                                style = MaterialTheme.typography.bodyMedium,
                                color = Color.White.copy(alpha = 0.85f)
                            )
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                }

                item {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        GlassCard(
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(18.dp)
                        ) {
                            Row(
                                modifier = Modifier.padding(14.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.Schedule,
                                    contentDescription = null,
                                    tint = accent,
                                    modifier = Modifier.size(22.dp)
                                )
                                Spacer(Modifier.width(10.dp))
                                Column {
                                    Text(
                                        text = "Hora pico",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = SonoraTextSecondary
                                    )
                                    Text(
                                        text = peakHourLabel,
                                        style = MaterialTheme.typography.titleMedium,
                                        color = SonoraTextPrimary,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                }
                            }
                        }

                        GlassCard(
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(18.dp)
                        ) {
                            Row(
                                modifier = Modifier.padding(14.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.CalendarMonth,
                                    contentDescription = null,
                                    tint = accentSecondary,
                                    modifier = Modifier.size(22.dp)
                                )
                                Spacer(Modifier.width(10.dp))
                                Column {
                                    Text(
                                        text = "Día más activo",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = SonoraTextSecondary
                                    )
                                    Text(
                                        text = peakDayLabel,
                                        style = MaterialTheme.typography.titleMedium,
                                        color = SonoraTextPrimary,
                                        fontWeight = FontWeight.SemiBold,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                        }
                    }
                    Spacer(Modifier.height(14.dp))
                }

                if (stats.topArtists.isNotEmpty()) {
                    item {
                        Text(
                            text = "Top artistas",
                            style = MaterialTheme.typography.headlineMedium,
                            color = SonoraTextPrimary,
                            modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp)
                        )
                        GlassCard(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp),
                            shape = RoundedCornerShape(18.dp)
                        ) {
                            Column(modifier = Modifier.padding(14.dp)) {
                                stats.topArtists.forEachIndexed { idx, artistStat ->
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(vertical = 7.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = "${idx + 1}",
                                            style = MaterialTheme.typography.labelLarge,
                                            color = accent,
                                            fontWeight = FontWeight.Bold,
                                            modifier = Modifier.width(26.dp)
                                        )
                                        Text(
                                            text = artistStat.artist,
                                            style = MaterialTheme.typography.titleMedium,
                                            color = SonoraTextPrimary,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            modifier = Modifier.weight(1f)
                                        )
                                        Text(
                                            text = "${artistStat.totalPlays}× • ${artistStat.estimatedMinutes} min",
                                            style = MaterialTheme.typography.labelMedium,
                                            color = SonoraTextSecondary
                                        )
                                    }
                                    if (idx < stats.topArtists.lastIndex) {
                                        HorizontalDivider(color = Color.White.copy(alpha = 0.08f))
                                    }
                                }
                            }
                        }
                        Spacer(Modifier.height(14.dp))
                    }
                }

                if (stats.topAlbums.isNotEmpty()) {
                    item {
                        Text(
                            text = "Top álbumes",
                            style = MaterialTheme.typography.headlineMedium,
                            color = SonoraTextPrimary,
                            modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp)
                        )
                        GlassCard(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp),
                            shape = RoundedCornerShape(18.dp)
                        ) {
                            Column(modifier = Modifier.padding(14.dp)) {
                                stats.topAlbums.forEachIndexed { idx, albumStat ->
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(vertical = 7.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        if (albumStat.artworkUrl.isNotBlank()) {
                                            SonoraArtwork(
                                                url = albumStat.artworkUrl,
                                                contentDescription = albumStat.album,
                                                modifier = Modifier.size(42.dp),
                                                cornerRadius = 8.dp
                                            )
                                        } else {
                                            Box(
                                                modifier = Modifier
                                                    .size(42.dp)
                                                    .clip(RoundedCornerShape(8.dp))
                                                    .background(Color.White.copy(alpha = 0.08f)),
                                                contentAlignment = Alignment.Center
                                            ) {
                                                Icon(
                                                    imageVector = Icons.Rounded.Album,
                                                    contentDescription = null,
                                                    tint = SonoraTextSecondary
                                                )
                                            }
                                        }
                                        Spacer(Modifier.width(12.dp))
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                text = albumStat.album,
                                                style = MaterialTheme.typography.titleMedium,
                                                color = SonoraTextPrimary,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                            Text(
                                                text = "${albumStat.totalPlays} reproducciones • ${albumStat.estimatedMinutes} min",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = SonoraTextSecondary
                                            )
                                        }
                                    }
                                    if (idx < stats.topAlbums.lastIndex) {
                                        HorizontalDivider(color = Color.White.copy(alpha = 0.08f))
                                    }
                                }
                            }
                        }
                        Spacer(Modifier.height(14.dp))
                    }
                }

                if (stats.topTracks.isNotEmpty()) {
                    item {
                        Text(
                            text = "Top canciones",
                            style = MaterialTheme.typography.headlineMedium,
                            color = SonoraTextPrimary,
                            modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp)
                        )
                    }
                    items(
                        count = stats.topTracks.size,
                        key = { idx -> "replay_track_${stats.topTracks[idx].track.videoId}_$idx" }
                    ) { idx ->
                        val stat = stats.topTracks[idx]
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(modifier = Modifier.weight(1f)) {
                                TrackRowItem(
                                    track = stat.track,
                                    indexLabel = "${idx + 1}",
                                    isPlayingCurrent = playbackState.currentTrack?.videoId == stat.track.videoId,
                                    isActuallyPlaying = playbackState.isPlaying,
                                    showDivider = idx < stats.topTracks.lastIndex,
                                    onClick = { onPlayQueue(topTracksQueue, idx) },
                                    onMoreClick = { onTrackMore(stat.track) }
                                )
                            }
                            Text(
                                text = "${stat.playCount}×",
                                style = MaterialTheme.typography.labelMedium,
                                color = accent,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.padding(end = 14.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}
