package com.kirkisham.motif.ui.playlist

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.DriveFileRenameOutline
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.material.icons.outlined.PlaylistAdd
import androidx.compose.material.icons.outlined.QueueMusic
import androidx.compose.material.icons.outlined.Shuffle
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kirkisham.motif.MotifApp
import com.kirkisham.motif.data.SpTrack
import com.kirkisham.motif.ui.components.AddTarget
import com.kirkisham.motif.ui.components.AddToHost
import com.kirkisham.motif.ui.components.Chip
import com.kirkisham.motif.ui.components.ConfirmSheet
import com.kirkisham.motif.ui.components.Cover
import com.kirkisham.motif.ui.components.Hairline
import com.kirkisham.motif.ui.components.IconButton44
import com.kirkisham.motif.ui.components.MenuItem
import com.kirkisham.motif.ui.components.MenuSheet
import com.kirkisham.motif.ui.components.MiniPlayer
import com.kirkisham.motif.ui.components.NameSheet
import com.kirkisham.motif.ui.components.PlainLine
import com.kirkisham.motif.ui.components.SectionLabel
import com.kirkisham.motif.ui.components.TopBar
import com.kirkisham.motif.ui.components.formatClock
import com.kirkisham.motif.ui.components.formatLength
import com.kirkisham.motif.ui.components.pluralSongs
import com.kirkisham.motif.ui.motif
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

private val SORTS = listOf("Name", "Artist", "Length", "Custom") // BPM chip hidden until Build 2 (spec §3.5)

/**
 * Playlist (board Playlist): header, Play/Shuffle, sort chips, song rows.
 * Long press and drag reorders (writes back to Spotify, chip switches to Custom);
 * long press without moving opens the song menu; swipe right adds to a playlist.
 */
@Composable
fun PlaylistScreen(
    app: MotifApp,
    playlistId: String,
    onBack: () -> Unit,
    onOpenNowPlaying: () -> Unit,
    onPlus: (() -> Unit)?,
    onDeleted: () -> Unit,
) {
    val playlists by app.library.playlists.collectAsStateWithLifecycle()
    val items by app.library.tracksFlow(playlistId).collectAsStateWithLifecycle()
    val sorts by app.prefs.playlistSorts.collectAsStateWithLifecycle(emptyMap())
    val playback by app.remote.playback.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val playlist = playlists?.firstOrNull { it.id == playlistId }
    val sort = sorts[playlistId] ?: "Custom"

    var menuTrack by remember { mutableStateOf<SpTrack?>(null) }
    var addTarget by remember { mutableStateOf<AddTarget?>(null) }
    var newPlaylistFor by remember { mutableStateOf<AddTarget?>(null) }
    var showMore by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    var line by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(playlistId) {
        if (items == null) app.library.refreshTracks(playlistId)
        if (playlist == null) app.library.refreshPlaylists()
    }

    // Display order: Spotify's own order under Custom, otherwise sorted. Each entry keeps its Spotify index.
    val source = items ?: emptyList()
    val displayed = remember(source, sort) {
        val indexed = source.withIndex().toList()
        when (sort) {
            "Name" -> indexed.sortedBy { it.value.track?.name?.lowercase() ?: "" }
            "Artist" -> indexed.sortedWith(compareBy({ it.value.track?.artistNames?.lowercase() ?: "" }, { it.value.track?.name?.lowercase() ?: "" }))
            "Length" -> indexed.sortedBy { it.value.track?.duration_ms ?: 0L }
            else -> indexed
        }
    }
    val totalMs = source.sumOf { it.track?.duration_ms ?: 0L }

    // Drag to reorder: the dragged row follows the finger; neighbours shift as it passes them.
    val density = LocalDensity.current
    val rowPx = with(density) { 60.dp.toPx() }
    var dragIndex by remember { mutableIntStateOf(-1) }       // display index being dragged
    var dragShift by remember { mutableIntStateOf(0) }        // rows moved so far (+ down, - up)
    var dragOffset by remember { mutableFloatStateOf(0f) }    // finger offset in px since start
    var dragMoved by remember { mutableStateOf(false) }

    fun finishDrag() {
        val from = dragIndex
        val shift = dragShift
        val moved = dragMoved
        dragIndex = -1; dragShift = 0; dragOffset = 0f; dragMoved = false
        if (from < 0) return
        if (!moved && shift == 0) {
            menuTrack = displayed.getOrNull(from)?.value?.track
            return
        }
        val toDisplay = (from + shift).coerceIn(0, displayed.lastIndex)
        if (toDisplay == from) return
        val item = displayed[from]
        val reordered = displayed.toMutableList().also { it.removeAt(from); it.add(toDisplay, item) }
        val above = reordered.getOrNull(toDisplay - 1)
        val fromSp = item.index
        val toSp = if (above == null) 0 else {
            val a = above.index
            (if (a < fromSp) a else a - 1) + 1
        }
        scope.launch {
            app.prefs.setPlaylistSort(playlistId, "Custom")
            if (!app.library.move(playlistId, fromSp, toSp)) line = "Spotify did not save the new order."
        }
    }

    Box(Modifier.fillMaxSize()) {
        LazyColumn(
            Modifier.fillMaxSize().statusBarsPadding(),
            state = rememberLazyListState(),
            contentPadding = PaddingValues(start = 16.dp, end = 24.dp, bottom = 100.dp),
            userScrollEnabled = dragIndex < 0,
        ) {
            item {
                TopBar(null, onBack) {
                    IconButton44(Icons.Outlined.MoreHoriz, "More", { showMore = true }, tint = motif.textMuted)
                }
            }
            item {
                Row(Modifier.padding(start = 8.dp, top = 8.dp), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Cover(playlist?.coverUrl, 96.dp, radius = 6.dp)
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(playlist?.name ?: "", style = MaterialTheme.typography.titleLarge, color = motif.text, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(
                            if (items == null) pluralSongs(playlist?.trackCount ?: 0) else "${pluralSongs(source.size)} · ${formatLength(totalMs)}",
                            style = MaterialTheme.typography.bodySmall, color = motif.textMuted,
                        )
                    }
                    IconButton44(Icons.Outlined.Shuffle, "Shuffle", { playlist?.let { app.player.playPlaylist(it, shuffle = true) } }, tint = motif.textMuted)
                    Box(
                        Modifier.size(48.dp).clip(CircleShape).background(motif.text).clickable { playlist?.let { app.player.playPlaylist(it) } },
                        contentAlignment = Alignment.Center,
                    ) { Icon(Icons.Filled.PlayArrow, "Play", tint = motif.ground, modifier = Modifier.size(22.dp)) }
                }
                Spacer(Modifier.height(16.dp))
                Row(Modifier.padding(start = 8.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SectionLabel("Sort", Modifier.padding(end = 4.dp))
                    SORTS.forEach { s -> Chip(s, selected = sort == s, onClick = { scope.launch { app.prefs.setPlaylistSort(playlistId, s) } }) }
                }
                line?.let { PlainLine(it, Modifier.padding(start = 8.dp)) }
                if (items == null) PlainLine("Loading songs", Modifier.padding(start = 8.dp))
                else if (source.isEmpty()) PlainLine("No songs in this playlist yet.", Modifier.padding(start = 8.dp))
            }
            itemsIndexed(displayed, key = { i, it -> "${it.index}-${it.value.track?.uri}-$i" }) { displayIndex, entry ->
                val track = entry.value.track ?: return@itemsIndexed
                val isDragging = dragIndex == displayIndex
                // Rows between the drag origin and its current slot step one row the other way.
                val shiftPx = when {
                    dragIndex < 0 || isDragging -> 0f
                    dragShift > 0 && displayIndex > dragIndex && displayIndex <= dragIndex + dragShift -> -rowPx
                    dragShift < 0 && displayIndex < dragIndex && displayIndex >= dragIndex + dragShift -> rowPx
                    else -> 0f
                }
                SongRow(
                    track = track,
                    playing = playback?.uri == track.uri,
                    modifier = Modifier
                        .padding(start = 8.dp)
                        .zIndex(if (isDragging) 1f else 0f)
                        .graphicsLayer { translationY = if (isDragging) dragOffset else shiftPx }
                        .then(if (isDragging) Modifier.background(motif.surface) else Modifier)
                        .pointerInput(displayIndex, displayed.size) {
                            detectDragGesturesAfterLongPress(
                                onDragStart = { dragIndex = displayIndex; dragShift = 0; dragOffset = 0f; dragMoved = false },
                                onDrag = { change, delta ->
                                    change.consume()
                                    dragOffset += delta.y
                                    if (abs(delta.y) > 0.5f) dragMoved = dragMoved || abs(dragOffset) > rowPx * 0.25f
                                    val newShift = (dragOffset / rowPx).roundToInt().coerceIn(-displayIndex, displayed.lastIndex - displayIndex)
                                    dragShift = newShift
                                },
                                onDragEnd = { finishDrag() },
                                onDragCancel = { finishDrag() },
                            )
                        },
                    onClick = { playlist?.let { app.player.playInContext(it.uri, entry.index, it.id) } },
                    onSwipeRight = { addTarget = AddTarget(track.uri, track.name) },
                    onPlus = { addTarget = AddTarget(track.uri, track.name) },
                )
            }
        }
        MiniPlayer(
            remote = app.remote,
            playback = playback,
            onToggle = { app.player.togglePlayPause() },
            onNext = { app.player.next() },
            onOpen = onOpenNowPlaying,
            onPlus = onPlus,
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 16.dp),
        )
    }

    menuTrack?.let { t ->
        MenuSheet(
            title = t.name,
            subtitle = t.artistNames,
            items = listOf(
                MenuItem("Play next", Icons.Outlined.QueueMusic) { app.player.queue(t.uri) },
                MenuItem("Add to playlist", Icons.Outlined.PlaylistAdd) { addTarget = AddTarget(t.uri, t.name) },
                MenuItem("Remove from this playlist", Icons.Outlined.Delete, Color(0xFFC96E6E)) {
                    scope.launch { if (!app.library.removeTrack(playlistId, t.uri)) line = "Spotify did not remove the song." }
                },
            ),
            onDismiss = { menuTrack = null },
        )
    }
    AddToHost(app, addTarget, newPlaylistFor, onDismiss = { addTarget = null; newPlaylistFor = null }, onDone = { line = it })

    if (showMore && playlist != null) {
        MenuSheet(
            title = playlist.name,
            items = listOf(
                MenuItem("Rename", Icons.Outlined.DriveFileRenameOutline) { renaming = true },
                MenuItem("Delete", Icons.Outlined.Delete, Color(0xFFC96E6E)) { deleting = true },
            ),
            onDismiss = { showMore = false },
        )
    }
    if (renaming && playlist != null) {
        NameSheet("Rename playlist", playlist.name, onSave = { n -> scope.launch { if (!app.library.rename(playlist.id, n)) line = "Spotify did not rename it." } }, onDismiss = { renaming = false })
    }
    if (deleting && playlist != null) {
        ConfirmSheet(
            text = "Remove “${playlist.name}” from your library?",
            confirmLabel = "Delete",
            onConfirm = { scope.launch { if (app.library.unfollow(playlist.id)) { app.prefs.forgetPlaylist(playlist.id); onDeleted() } else line = "Spotify did not delete it." } },
            onDismiss = { deleting = false },
        )
    }
}

/** 60 dp song row: 40 dp cover, title (accent when playing), "Artist · 3:42", plus. */
@Composable
private fun SongRow(
    track: SpTrack,
    playing: Boolean,
    modifier: Modifier,
    onClick: () -> Unit,
    onSwipeRight: () -> Unit,
    onPlus: () -> Unit,
) {
    val density = LocalDensity.current
    val threshold = with(density) { 72.dp.toPx() }
    var dx by remember { mutableFloatStateOf(0f) }
    Column(modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .height(60.dp)
                .clickable(onClick = onClick)
                .pointerInput(track.uri) {
                    detectHorizontalDragGestures(
                        onDragStart = { dx = 0f },
                        onHorizontalDrag = { _, d -> dx += d },
                        onDragEnd = { if (dx > threshold) onSwipeRight() },
                    )
                },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Cover(track.smallCoverUrl, 40.dp, radius = 3.dp)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(track.name, style = MaterialTheme.typography.labelLarge, color = if (playing) motif.accent else motif.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    "${track.artistNames} · ${formatClock(track.duration_ms)}",
                    style = MaterialTheme.typography.bodySmall, color = motif.textMuted, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
            IconButton44(Icons.Outlined.Add, "Add to playlist", onPlus, tint = motif.textMuted, size = 18.dp)
        }
        Hairline()
    }
}
