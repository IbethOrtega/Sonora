package com.example.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.model.Track
import com.example.player.PlaybackUiState
import com.example.ui.components.GlassCard
import com.example.ui.components.TrackRowItem
import com.example.ui.components.sonoraLiquidGlassSurface
import com.example.ui.theme.LocalSonoraAccent
import com.example.ui.theme.SonoraTextPrimary
import com.example.ui.theme.SonoraTextSecondary
import com.example.ui.viewmodel.SonoraViewModel

@Composable
fun YtLikedSongsScreen(
    viewModel: SonoraViewModel,
    playbackState: PlaybackUiState,
    bottomContentPadding: Dp,
    onBack: () -> Unit,
    onPlayQueue: (List<Track>, Int) -> Unit,
    onTrackMore: (Track) -> Unit
) {
    val accent = LocalSonoraAccent.current
    val songs by viewModel.ytLikedSongs.collectAsState()
    val isSyncing by viewModel.isSyncingYt.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .testTag("yt_liked_songs_screen")
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
                    .testTag("yt_liked_back_button")
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
                    text = stringResource(R.string.yt_liked_title),
                    style = MaterialTheme.typography.titleLarge,
                    color = SonoraTextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = if (isSyncing) {
                        "${songs.size} canciones • Sincronizando..."
                    } else {
                        "${songs.size} canciones"
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = SonoraTextSecondary
                )
            }
            IconButton(
                onClick = { viewModel.syncYouTubeLibrary() },
                enabled = !isSyncing,
                modifier = Modifier
                    .minimumInteractiveComponentSize()
                    .testTag("yt_liked_sync_button")
            ) {
                if (isSyncing) {
                    CircularProgressIndicator(
                        color = accent,
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp
                    )
                } else {
                    Icon(
                        imageVector = Icons.Rounded.Sync,
                        contentDescription = "Sincronizar Me gusta",
                        tint = SonoraTextPrimary
                    )
                }
            }
        }

        if (songs.isEmpty()) {
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
                            imageVector = Icons.Rounded.Favorite,
                            contentDescription = null,
                            tint = accent,
                            modifier = Modifier.size(44.dp)
                        )
                        Spacer(Modifier.height(12.dp))
                        Text(
                            text = stringResource(R.string.yt_liked_empty),
                            style = MaterialTheme.typography.bodyLarge,
                            color = SonoraTextSecondary,
                            textAlign = TextAlign.Center
                        )
                    }
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = bottomContentPadding)
            ) {
                item {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 6.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Button(
                            onClick = { onPlayQueue(songs, 0) },
                            colors = ButtonDefaults.buttonColors(containerColor = accent),
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier
                                .weight(1f)
                                .height(48.dp)
                                .testTag("yt_liked_play_all_button")
                        ) {
                            Icon(Icons.Rounded.PlayArrow, contentDescription = null)
                            Spacer(Modifier.width(6.dp))
                            Text(stringResource(R.string.play_all))
                        }
                        OutlinedButton(
                            onClick = { onPlayQueue(songs.shuffled(), 0) },
                            border = BorderStroke(1.dp, Color.White.copy(alpha = 0.24f)),
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier
                                .weight(1f)
                                .height(48.dp)
                                .testTag("yt_liked_shuffle_button")
                        ) {
                            Icon(Icons.Rounded.Shuffle, contentDescription = null, tint = SonoraTextPrimary)
                            Spacer(Modifier.width(6.dp))
                            Text(stringResource(R.string.shuffle), color = SonoraTextPrimary)
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                }

                itemsIndexed(
                    items = songs,
                    key = { idx, track -> "yt_liked_${track.videoId}_$idx" }
                ) { idx, track ->
                    TrackRowItem(
                        track = track,
                        indexLabel = "${idx + 1}",
                        isPlayingCurrent = playbackState.currentTrack?.videoId == track.videoId,
                        isActuallyPlaying = playbackState.isPlaying,
                        showDivider = idx < songs.lastIndex,
                        onClick = { onPlayQueue(songs, idx) },
                        onMoreClick = { onTrackMore(track) },
                        modifier = Modifier.padding(horizontal = 6.dp)
                    )
                }
            }
        }
    }
}
