package com.kirkisham.motif.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.kirkisham.motif.ui.motif

/** 11 sp uppercase muted section label (spec §2). */
@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text.uppercase(),
        style = MaterialTheme.typography.labelSmall,
        color = motif.textMuted,
        modifier = modifier,
    )
}

@Composable
fun Hairline(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().height(1.dp).background(motif.line))
}

/** 44 dp touch target with a thin-stroke icon. */
@Composable
fun IconButton44(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tint: Color = motif.text,
    size: Dp = 22.dp,
    enabled: Boolean = true,
) {
    Box(
        modifier
            .size(44.dp)
            .clip(CircleShape)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription, tint = if (enabled) tint else tint.copy(alpha = 0.35f), modifier = Modifier.size(size))
    }
}

/** Back arrow, centered title, optional actions on the right (boards: All playlists, Playlist, Settings). */
@Composable
fun TopBar(
    title: String?,
    onBack: (() -> Unit)?,
    modifier: Modifier = Modifier,
    actions: @Composable RowScopeActions.() -> Unit = {},
) {
    Row(modifier.fillMaxWidth().height(52.dp), verticalAlignment = Alignment.CenterVertically) {
        if (onBack != null) IconButton44(Icons.AutoMirrored.Outlined.ArrowBack, "Back", onBack) else Spacer(Modifier.width(44.dp))
        Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
            if (title != null) Text(title, style = MaterialTheme.typography.titleMedium, color = motif.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Row(verticalAlignment = Alignment.CenterVertically) { RowScopeActions.actions() }
    }
}

object RowScopeActions

/** Pill chip: filled with text color when selected, hairline border when not (boards: sort chips). */
@Composable
fun Chip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
    enabled: Boolean = true,
    height: Dp = 30.dp,
) {
    val shape = RoundedCornerShape(50)
    val bg = if (selected) motif.text else Color.Transparent
    val fg = if (selected) motif.ground else motif.textMuted.copy(alpha = if (enabled) 1f else 0.5f)
    Box(
        modifier
            .height(height)
            .clip(shape)
            .background(bg)
            .border(1.dp, if (selected) motif.text else motif.chipLine, shape)
            .then(
                if (onLongClick != null) Modifier.combinedClickableCompat(enabled, onClick, onLongClick)
                else Modifier.clickable(enabled = enabled, onClick = onClick),
            )
            .padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            fontSize = 12.sp * motif.textScale,
            fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
            color = fg,
            maxLines = 1,
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
fun Modifier.combinedClickableCompat(enabled: Boolean, onClick: () -> Unit, onLongClick: () -> Unit): Modifier =
    this.combinedClickable(enabled = enabled, onLongClick = onLongClick, onClick = onClick)

/** Square cover with the board's placeholder tone when there is no image. */
@Composable
fun Cover(url: String?, size: Dp, modifier: Modifier = Modifier, radius: Dp = 4.dp) {
    val shape = RoundedCornerShape(radius)
    Box(modifier.size(size).clip(shape).background(motif.card)) {
        if (!url.isNullOrBlank()) {
            AsyncImage(model = url, contentDescription = null, modifier = Modifier.size(size))
        }
    }
}

/** 60 dp list row: cover, title + subtitle, trailing slot. */
@Composable
fun ListRow(
    title: String,
    subtitle: String?,
    coverUrl: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
    coverSize: Dp = 44.dp,
    titleColor: Color = motif.text,
    divider: Boolean = true,
    trailing: @Composable () -> Unit = {},
) {
    Column(modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .height(60.dp)
                .then(
                    if (onLongClick != null) Modifier.combinedClickableCompat(true, onClick, onLongClick)
                    else Modifier.clickable(onClick = onClick),
                ),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Cover(coverUrl, coverSize)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium, color = titleColor, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = motif.textMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            trailing()
        }
        if (divider) Hairline()
    }
}

/** 56 dp song row for result lists: 40 dp cover, title (accent when playing), "Artist · 3:42", plus. */
@Composable
fun TrackRow(
    track: com.kirkisham.motif.data.SpTrack,
    playing: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
    onPlus: (() -> Unit)? = null,
    plusTint: Color = motif.textMuted,
) {
    Row(
        modifier
            .fillMaxWidth()
            .height(56.dp)
            .then(
                if (onLongClick != null) Modifier.combinedClickableCompat(true, onClick, onLongClick)
                else Modifier.clickable(onClick = onClick),
            ),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Cover(track.smallCoverUrl, 40.dp, radius = 3.dp)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(track.name, style = MaterialTheme.typography.labelLarge, color = if (playing) motif.accent else motif.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                if (track.duration_ms > 0) "${track.artistNames} · ${formatClock(track.duration_ms)}" else track.artistNames,
                style = MaterialTheme.typography.bodySmall, color = motif.textMuted, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
        if (onPlus != null) IconButton44(Icons.Outlined.Add, "Add to playlist", onPlus, tint = plusTint, size = 18.dp)
    }
}

/** One plain line of status text, no dialog (CLAUDE.md: no pop-ups). */
@Composable
fun PlainLine(text: String, modifier: Modifier = Modifier) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = motif.textMuted, modifier = modifier.padding(vertical = 8.dp))
}

fun formatClock(ms: Long): String {
    val total = (ms / 1000).coerceAtLeast(0)
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

/** "13 hr 40 min" style total length for headers. */
fun formatLength(ms: Long): String {
    val mins = ms / 60_000
    val h = mins / 60
    val m = mins % 60
    return when {
        h > 0 -> "$h hr $m min"
        else -> "$m min"
    }
}

fun pluralSongs(n: Int) = if (n == 1) "1 song" else "$n songs"

/** Shown in place of a song list when Spotify withholds a playlist's contents (February 2026 Dev Mode rule). */
const val NOT_SHARED_LINE = "Spotify only shares the contents of playlists you own or collaborate on"

