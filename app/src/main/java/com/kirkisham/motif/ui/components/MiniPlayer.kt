package com.kirkisham.motif.ui.components

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.kirkisham.motif.data.SpotifyRemote
import com.kirkisham.motif.ui.motif

/**
 * Bottom bar from the boards: 64 dp card, art, title, artist, plus, play/pause.
 * Swipe left anywhere on it to skip (spec §3.3). Hidden when Spotify has reported nothing yet.
 */
@Composable
fun MiniPlayer(
    remote: SpotifyRemote,
    playback: SpotifyRemote.Playback?,
    onToggle: () -> Unit,
    onNext: () -> Unit,
    onOpen: () -> Unit,
    onPlus: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    if (playback == null) return
    val art = rememberArt(remote, playback.imageUri, com.spotify.protocol.types.Image.Dimension.SMALL)
    val density = LocalDensity.current
    val threshold = with(density) { 72.dp.toPx() }
    var drag by remember { mutableStateOf(0f) }
    val shape = RoundedCornerShape(10.dp)
    val p = motif
    Row(
        modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            .height(64.dp)
            .clip(shape)
            .background(p.card)
            .glowBorder(shape)
            .clickable(onClick = onOpen)
            .pointerInput(Unit) {
                detectHorizontalDragGestures(
                    onDragStart = { drag = 0f },
                    onHorizontalDrag = { _, dx -> drag += dx },
                    onDragEnd = { if (drag < -threshold) onNext() },
                )
            }
            .padding(start = 10.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ArtBox(art, 44.dp)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(playback.title, style = MaterialTheme.typography.labelLarge, color = p.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(playback.artist, style = MaterialTheme.typography.bodySmall, color = p.textMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (onPlus != null && !playback.isEpisode) IconButton44(Icons.Outlined.Add, "Add to playlist", onPlus)
        IconButton44(
            if (playback.isPaused) Icons.Filled.PlayArrow else Icons.Filled.Pause,
            if (playback.isPaused) "Play" else "Pause",
            onToggle,
        )
    }
}

/** Border glow from Appearance: a thin border in the glow color whose alpha follows the intensity. */
@Composable
fun Modifier.glowBorder(shape: RoundedCornerShape): Modifier {
    val p = motif
    if (p.glowIntensity <= 0f) return this
    return this.border(1.dp, p.glow.copy(alpha = 0.25f + 0.65f * p.glowIntensity), shape)
}

@Composable
fun ArtBox(bitmap: Bitmap?, size: androidx.compose.ui.unit.Dp, radius: androidx.compose.ui.unit.Dp = 4.dp) {
    Box(Modifier.size(size).clip(RoundedCornerShape(radius)).background(motif.card)) {
        if (bitmap != null) Image(bitmap.asImageBitmap(), null, Modifier.size(size))
    }
}

/** Album art for the given App Remote image URI, re-fetched when the URI changes. */
@Composable
fun rememberArt(remote: SpotifyRemote, imageUri: String?, dimension: com.spotify.protocol.types.Image.Dimension): Bitmap? {
    var art by remember { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(imageUri) {
        art = remote.loadArt(imageUri, dimension)
        if (art == null && imageUri != null) {
            // The connection may still be settling; one more try after a beat.
            kotlinx.coroutines.delay(1500)
            art = remote.loadArt(imageUri, dimension)
        }
    }
    return art
}
