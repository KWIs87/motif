package com.kirkisham.motif.ui.playlist

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.DriveFileRenameOutline
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.material.icons.outlined.Checklist
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
import com.kirkisham.motif.ui.MINI_PLAYER_SPACE
import com.kirkisham.motif.data.SpArtist
import com.kirkisham.motif.data.SpPlaylist
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
import com.kirkisham.motif.ui.components.NameSheet
import com.kirkisham.motif.ui.components.NOT_SHARED_LINE
import com.kirkisham.motif.ui.components.PlainLine
import com.kirkisham.motif.ui.components.deletePlaylistWithUndo
import com.kirkisham.motif.ui.components.SectionLabel
import com.kirkisham.motif.ui.components.SongMenuSheet
import com.kirkisham.motif.ui.components.SongRow
import com.kirkisham.motif.ui.components.TopBar
import com.kirkisham.motif.ui.components.formatLength
import com.kirkisham.motif.ui.components.pluralSongs
import com.kirkisham.motif.ui.motif
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlin.math.abs
import kotlin.math.roundToInt

private val SORTS = listOf("Recent", "Name", "Artist", "Length", "Plays", "Custom") // BPM chip hidden until Build 2 (spec §3.5)
/** Natural direction per sort: Recent newest first, Plays most first, the rest A to Z / shortest first; Custom ▲ is the playlist's own order (0.11). */
private fun naturalAscending(sort: String): Boolean? = when (sort) { "Recent", "Plays" -> false; else -> true }

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
    onDeleted: () -> Unit,
    onMassEdit: () -> Unit,
    onOpenArtist: (SpArtist) -> Unit,
    onOpenAlbum: (String) -> Unit,
) {
    val playlists by app.library.playlists.collectAsStateWithLifecycle()
    val items by app.library.tracksFlow(playlistId).collectAsStateWithLifecycle()
    val sorts by app.prefs.playlistSorts.collectAsStateWithLifecycle(emptyMap())
    val reversedIds by app.prefs.playlistSortReversed.collectAsStateWithLifecycle(emptySet())
    val playback by app.remote.playback.collectAsStateWithLifecycle()
    val playCounts by app.info.playCounts.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    // Playlists outside the library (opened from Search) are fetched once and kept for the session.
    var foreign by remember { mutableStateOf<SpPlaylist?>(null) }
    val playlist = playlists?.firstOrNull { it.id == playlistId } ?: foreign
    val sort = sorts[playlistId] ?: "Custom"
    val reversed = playlistId in reversedIds
    val ascending = naturalAscending(sort)?.let { it xor reversed }

    var menuTrack by remember { mutableStateOf<SpTrack?>(null) }
    var addTarget by remember { mutableStateOf<AddTarget?>(null) }
    var newPlaylistFor by remember { mutableStateOf<AddTarget?>(null) }
    var showMore by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    var line by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(playlistId) {
        if (playlist == null) {
            app.library.refreshPlaylists()
            if (app.library.playlistById(playlistId) == null) foreign = app.library.playlistOrFetch(playlistId)
        }
        if (items == null) app.library.refreshTracks(playlistId)
    }
    // Spotify (February 2026) only shares the contents of playlists the user owns or collaborates on.
    val notShared = playlist != null && !playlist.hasItems && items.isNullOrEmpty()

    // Display order: Spotify's own order under Custom, otherwise sorted. Each entry keeps its Spotify index.
    val source = items ?: emptyList()
    val counts = if (sort == "Plays") playCounts else emptyMap()
    val displayed = remember(source, sort, reversed, counts) {
        // Keep Spotify's index on every row (reorder/remove need it), but hide rows Spotify no longer returns
        // (removed catalog tracks, local files): they would render as a blank "0:00" line.
        val indexed = source.withIndex().filter { !it.value.item?.name.isNullOrBlank() }
        val natural = when (sort) {
            "Recent" -> indexed.sortedByDescending { it.value.added_at ?: "" }
            "Name" -> indexed.sortedBy { it.value.item?.name?.lowercase() ?: "" }
            "Artist" -> indexed.sortedWith(compareBy({ it.value.item?.artistNames?.lowercase() ?: "" }, { it.value.item?.name?.lowercase() ?: "" }))
            "Length" -> indexed.sortedBy { it.value.item?.duration_ms ?: 0L }
            "Plays" -> {
                // Known counts most-played first; songs without a count (still loading, or unknown to Last.fm) stay at the bottom in either direction.
                val (known, unknown) = indexed.partition { (counts[it.value.item?.uri]?.plays ?: -1L) >= 0 }
                val sorted = known.sortedByDescending { counts[it.value.item?.uri]?.plays ?: -1L }
                return@remember (if (reversed) sorted.asReversed() else sorted) + unknown
            }
            else -> indexed
        }
        if (reversed) natural.asReversed() else natural
    }
    // Sorting by Plays asks Last.fm for every song still without a count in the background, two at a
    // time so the rows on screen (which fetch on their own) are not starved.
    LaunchedEffect(sort, items) {
        if (sort != "Plays") return@LaunchedEffect
        val missing = source.mapNotNull { it.item }.filter { it.uri.isNotBlank() && app.info.playCounts.value[it.uri] == null }
        coroutineScope {
            val gate = Semaphore(2)
            missing.forEach { t -> launch { gate.withPermit { app.info.playCount(t) } } }
        }
    }
    val totalMs = source.sumOf { it.item?.duration_ms ?: 0L }

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
            menuTrack = displayed.getOrNull(from)?.value?.item
            return
        }
        val toDisplay = (from + shift).coerceIn(0, displayed.lastIndex)
        if (toDisplay == from) return
        val item = displayed[from]
        val reordered = displayed.toMutableList().also { it.removeAt(from); it.add(toDisplay, item) }
        // The song lands right after its neighbour in Spotify's order: the row above it when the list reads
        // top-down, the row below it when the list is reversed (0.11: Custom ▾ reorders the true order).
        val anchor = if (reversed) reordered.getOrNull(toDisplay + 1) else reordered.getOrNull(toDisplay - 1)
        val fromSp = item.index
        val toSp = if (anchor == null) 0 else {
            val a = anchor.index
            (if (a < fromSp) a else a - 1) + 1
        }
        scope.launch {
            // A drop while sorted switches to Custom (natural direction); under Custom the direction is kept.
            if (sort != "Custom") app.prefs.setPlaylistSort(playlistId, "Custom")
            if (!app.library.move(playlistId, fromSp, toSp)) line = "Spotify did not save the new order."
        }
    }

    Box(Modifier.fillMaxSize()) {
        LazyColumn(
            Modifier.fillMaxSize().statusBarsPadding(),
            state = rememberLazyListState(),
            contentPadding = PaddingValues(start = 16.dp, end = 24.dp, bottom = MINI_PLAYER_SPACE),
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
                            when {
                                notShared -> playlist?.owner?.display_name?.takeIf { it.isNotBlank() }?.let { "by $it" } ?: ""
                                items == null -> playlist?.itemCount?.let(::pluralSongs) ?: ""
                                else -> "${pluralSongs(source.size)} · ${formatLength(totalMs)}"
                            },
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
                // Six chips since 0.11 (Plays): the row scrolls sideways when the screen is too narrow for them all.
                Row(Modifier.padding(start = 8.dp, bottom = 12.dp).horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SectionLabel("Sort", Modifier.padding(end = 4.dp))
                    SORTS.forEach { s -> Chip(s, selected = sort == s, ascending = ascending, onClick = { scope.launch { app.prefs.setPlaylistSort(playlistId, s) } }) }
                }
                line?.let { PlainLine(it, Modifier.padding(start = 8.dp)) }
                if (notShared) PlainLine(NOT_SHARED_LINE, Modifier.padding(start = 8.dp))
                else if (items == null) PlainLine("Loading songs", Modifier.padding(start = 8.dp))
                else if (source.isEmpty()) PlainLine("No songs in this playlist yet.", Modifier.padding(start = 8.dp))
            }
            itemsIndexed(displayed, key = { i, it -> "${it.index}-${it.value.item?.uri}-$i" }) { displayIndex, entry ->
                val track = entry.value.item ?: return@itemsIndexed
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
                    onClick = { playlist?.let { app.player.playInContext(it.uri, entry.index, it.id, entry.value.item?.uri) } },
                    onSwipeRight = { addTarget = AddTarget(track.uri, track.name) },
                    onPlus = { addTarget = AddTarget(track.uri, track.name) },
                    onPlusLong = { newPlaylistFor = AddTarget(track.uri, track.name) },
                    showInPlaylist = false,
                )
            }
        }
    }

    menuTrack?.let { t ->
        SongMenuSheet(
            app = app,
            track = t,
            onAddTo = { addTarget = it },
            onGoToArtist = onOpenArtist,
            onGoToAlbum = onOpenAlbum,
            onRemove = {
                // Undo (0.10): the row goes at once; Spotify hears about it when the pill is gone.
                val hidden = app.library.hideTracks(playlistId, listOf(t.uri))
                if (hidden == null) scope.launch { if (!app.library.removeTrack(playlistId, t.uri)) line = "Spotify did not remove the song." }
                else app.undo.post(
                    commit = { if (!app.library.removeTracksRemote(hidden)) line = "Spotify did not remove the song." },
                    undo = { app.library.restoreTracks(hidden) },
                )
            },
            onDismiss = { menuTrack = null },
        )
    }
    AddToHost(app, addTarget, newPlaylistFor, onDismiss = { addTarget = null; newPlaylistFor = null }, onDone = { line = it })

    if (showMore && playlist != null) {
        MenuSheet(
            title = playlist.name,
            items = listOf(
                MenuItem("Mass edit", Icons.Outlined.Checklist) { onMassEdit() },
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
            onConfirm = { deletePlaylistWithUndo(app, playlist); onDeleted() },
            onDismiss = { deleting = false },
        )
    }
}
