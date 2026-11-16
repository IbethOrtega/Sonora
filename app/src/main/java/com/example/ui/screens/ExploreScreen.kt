package com.example.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.model.BrowseCard
import com.example.model.MoodCategory
import com.example.model.Track
import com.example.player.PlaybackUiState
import com.example.ui.components.BrowseCardItem
import com.example.ui.components.ErrorStateGlass
import com.example.ui.components.GlassCard
import com.example.ui.components.TrackRowItem
import com.example.ui.theme.LocalSonoraAccent
import com.example.ui.theme.SonoraTextPrimary
import com.example.ui.theme.SonoraTextSecondary
import com.example.ui.viewmodel.ExploreUiData
import com.example.ui.viewmodel.UiState

@Composable
fun ExploreScreen(
    exploreState: UiState<ExploreUiData>,
    playbackState: PlaybackUiState,
    bottomContentPadding: Dp,
    onRetry: () -> Unit,
    onPlayTrack: (Track, List<Track>) -> Unit,
    onOpenCard: (BrowseCard) -> Unit,
    onOpenMood: (MoodCategory) -> Unit,
    onTrackMore: (Track) -> Unit
) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .testTag("explore_screen"),
        contentPadding = PaddingValues(top = 16.dp, bottom = bottomContentPadding)
    ) {
        item {
            Column(modifier = Modifier.padding(horizontal = 20.dp)) {
                Text(
                    text = "Explorar",
                    style = MaterialTheme.typography.displayLarge,
                    color = SonoraTextPrimary
                )
                Text(
                    text = "Éxitos globales, novedades y estados de ánimo",
                    style = MaterialTheme.typography.bodyMedium,
                    color = SonoraTextSecondary
                )
            }
            Spacer(Modifier.height(18.dp))
        }

        when (exploreState) {
            is UiState.Loading -> {
                item {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 72.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator(color = LocalSonoraAccent.current)
                    }
                }
            }

            is UiState.Error -> {
                item {
                    ErrorStateGlass(message = exploreState.message, onRetry = onRetry)
                }
            }

            is UiState.Success -> {
                val data = exploreState.data

                if (data.moods.isNotEmpty()) {
                    item {
                        Text(
                            text = "Géneros y momentos",
                            style = MaterialTheme.typography.headlineMedium,
                            color = SonoraTextPrimary,
                            modifier = Modifier.padding(horizontal = 20.dp)
                        )
                        Spacer(Modifier.height(12.dp))
                        val rows = data.moods.take(10).chunked(2)
                        Column(
                            modifier = Modifier.padding(horizontal = 16.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            rows.forEach { pair ->
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    pair.forEach { mood ->
                                        MoodGlassPill(
                                            mood = mood,
                                            onClick = { onOpenMood(mood) },
                                            modifier = Modifier.weight(1f)
                                        )
                                    }
                                    if (pair.size == 1) Spacer(Modifier.weight(1f))
                                }
                            }
                        }
                        Spacer(Modifier.height(22.dp))
                    }
                }

                items(data.shelves, key = { "explore_${it.title}" }) { shelf ->
                    Column(modifier = Modifier.padding(vertical = 8.dp)) {
                        Text(
                            text = shelf.title,
                            style = MaterialTheme.typography.headlineMedium,
                            color = SonoraTextPrimary,
                            modifier = Modifier.padding(horizontal = 20.dp)
                        )
                        Spacer(Modifier.height(10.dp))
                        if (shelf.tracks.isNotEmpty()) {
                            GlassCard(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp),
                                shape = RoundedCornerShape(20.dp)
                            ) {
                                Column(modifier = Modifier.padding(6.dp)) {
                                    shelf.tracks.take(10).forEachIndexed { idx, track ->
                                        TrackRowItem(
                                            track = track,
                                            isPlayingCurrent = playbackState.currentTrack?.videoId == track.videoId,
                                            isActuallyPlaying = playbackState.isPlaying,
                                            indexLabel = "%02d".format(idx + 1),
                                            onClick = { onPlayTrack(track, shelf.tracks) },
                                            onMoreClick = { onTrackMore(track) }
                                        )
                                    }
                                }
                            }
                        }
                        if (shelf.items.isNotEmpty()) {
                            LazyRow(
                                contentPadding = PaddingValues(horizontal = 16.dp),
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                items(shelf.items, key = { "exp_card_${shelf.title}_${it.id}" }) { card ->
                                    BrowseCardItem(card = card, onClick = { onOpenCard(card) })
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun MoodGlassPill(
    mood: MoodCategory,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val accent = Color(mood.accentHex.toInt() or 0xFF000000.toInt())
    Row(
        modifier = modifier
            .height(58.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(
                Brush.horizontalGradient(
                    colors = listOf(
                        accent.copy(alpha = 0.28f),
                        Color(0xFF1A1A26).copy(alpha = 0.78f)
                    )
                )
            )
            .border(0.8.dp, Color.White.copy(alpha = 0.16f), RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp)
            .testTag("mood_pill_${mood.title}"),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .width(4.dp)
                .height(26.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(accent)
        )
        Spacer(Modifier.width(12.dp))
        Text(
            text = mood.title,
            style = MaterialTheme.typography.titleMedium,
            color = SonoraTextPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}
