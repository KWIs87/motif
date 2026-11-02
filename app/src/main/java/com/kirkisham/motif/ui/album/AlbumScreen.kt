package com.kirkisham.motif.ui.album

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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.Shuffle
import androidx.compose.material3.Icon
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kirkisham.motif.MotifApp
import com.kirkisham.motif.ui.MINI_PLAYER_SPACE
import com.kirkisham.motif.data.SpAlbum
import com.kirkisham.motif.data.SpArtist
import com.kirkisham.motif.data.SpTrack
import com.kirkisham.motif.ui.components.AddTarget
import com.kirkisham.motif.ui.components.AddToHost
import com.kirkisham.motif.ui.components.Chip
import com.kirkisham.motif.ui.components.Cover
import com.kirkisham.motif.ui.components.IconButton44
import com.kirkisham.motif.ui.components.PlainLine
import com.kirkisham.motif.ui.components.SectionLabel
import com.kirkisham.motif.ui.components.SongMenuSheet
import com.kirkisham.motif.ui.components.SongRow
import com.kirkisham.motif.ui.components.TopBar
import com.kirkisham.motif.ui.components.formatLength
import com.kirkisham.motif.ui.components.pluralSongs
import com.kirkisham.motif.ui.motif
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/**
 * Album (0.9, asked for by the owner; not on the wireframe canvas): the Playlist screen's look without
 * sort chips or drag. Header with cover, title, artists (tap opens the artist), year, type, count and
 * length, Play and Shuffle; rows are the songs in album order. Tap plays the song inside the album,
 * long press opens the song menu, swipe right or plus adds to a playlist.
 */
@Composable
fun AlbumScreen(
    app: MotifApp,
    albumId: String,
    onBack: () -> Unit,
    onOpenArtist: (SpArtist) -> Unit,
) {
    val playback by app.remote.playback.collectAsStateWithLifecycle()
    val playCounts by app.info.playCounts.collectAsStateWithLifecycle()
    var byPlays by remember { mutableStateOf(false) }
    var album by remember { mutableStateOf<SpAlbum?>(null) }
    var tracks by remember { mutableStateOf<List<SpTrack>?>(null) }
    var failed by remember { mutableStateOf(false) }
    var menuTrack by remember { mutableStateOf<SpTrack?>(null) }
    var addTarget by remember { mutableStateOf<AddTarget?>(null) }
    var newPlaylistFor by remember { mutableStateOf<AddTarget?>(null) }
    var line by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(albumId) {
        coroutineScope {
            val a = async { app.api.album(albumId) }
            val t = async { app.api.albumTracks(albumId) }
            album = a.await()
            tracks = t.await()
        }
        failed = album == null
    }

    // Simplified album tracks carry no album, so the album is attached: rows get the cover and
    // the song menu and add sheet work the same as elsewhere.
    val albumRows = remember(tracks, album) { tracks.orEmpty().filter { it.name.isNotBlank() }.map { it.copy(album = album) } }
    val totalMs = albumRows.sumOf { it.duration_ms }
    // Plays (0.11): most played first, songs without a count at the bottom in album order.
    val rows = remember(albumRows, byPlays, playCounts) {
        if (!byPlays) albumRows else {
            val (known, unknown) = albumRows.partition { (playCounts[it.uri]?.plays ?: -1L) >= 0 }
            known.sortedByDescending { playCounts[it.uri]?.plays ?: -1L } + unknown
        }
    }
    // "Start here": the most played song gets a gold dot once at least half the songs have a count.
    val topUri = remember(albumRows, playCounts) {
        val known = albumRows.mapNotNull { t -> playCounts[t.uri]?.takeIf { it.plays >= 0 }?.let { t.uri to it.plays } }
        if (albumRows.isNotEmpty() && known.size * 2 >= albumRows.size) known.maxByOrNull { it.second }?.first else null
    }
    // Albums are short, so every song's count is asked for once the list is in hand (two at a time).
    LaunchedEffect(albumRows) {
        val missing = albumRows.filter { it.uri.isNotBlank() && app.info.playCounts.value[it.uri] == null }
        coroutineScope { val gate = Semaphore(2); missing.forEach { t -> launch { gate.withPermit { app.info.playCount(t) } } } }
    }
    val a = album
    val subtitle = listOfNotNull(
        a?.year,
        a?.kindLabel,
        if (tracks != null) pluralSongs(rows.size) else a?.total_tracks?.let(::pluralSongs),
        if (tracks != null && totalMs > 0) formatLength(totalMs) else null,
    ).joinToString(" · ")
    val primaryArtist = a?.artists?.firstOrNull { it.id != null }

    Box(Modifier.fillMaxSize()) {
        LazyColumn(
            Modifier.fillMaxSize().statusBarsPadding(),
            contentPadding = PaddingValues(start = 16.dp, end = 24.dp, bottom = MINI_PLAYER_SPACE),
        ) {
            item { TopBar(null, onBack) }
            item {
                Row(Modifier.padding(start = 8.dp, top = 8.dp), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Cover(a?.coverUrl, 96.dp, radius = 6.dp)
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(a?.name ?: "", style = MaterialTheme.typography.titleLarge, color = motif.text, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        if (a != null && a.artistNames.isNotBlank()) {
                            Text(
                                a.artistNames,
                                style = MaterialTheme.typography.bodyMedium,
                                color = if (primaryArtist != null) motif.text else motif.textMuted,
                                maxLines = 1, overflow = TextOverflow.Ellipsis,
                                modifier = if (primaryArtist != null) Modifier.clickable { onOpenArtist(primaryArtist) } else Modifier,
                            )
                        }
                        Text(subtitle, style = MaterialTheme.typography.bodySmall, color = motif.textMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    IconButton44(Icons.Outlined.Shuffle, "Shuffle", { a?.uri?.let { app.player.playContext(it, shuffle = true) } }, tint = motif.textMuted)
                    Box(
                        Modifier.size(48.dp).clip(CircleShape).background(motif.text).clickable { a?.uri?.let { app.player.playContext(it) } },
                        contentAlignment = Alignment.Center,
                    ) { Icon(Icons.Filled.PlayArrow, "Play", tint = motif.ground, modifier = Modifier.size(22.dp)) }
                }
                Spacer(Modifier.height(16.dp))
                Row(Modifier.padding(start = 8.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SectionLabel("Sort", Modifier.padding(end = 4.dp))
                    Chip("Album order", !byPlays, { byPlays = false })
                    Chip("Plays", byPlays, { byPlays = true })
                }
                line?.let { PlainLine(it, Modifier.padding(start = 8.dp)) }
                if (failed) PlainLine("Spotify did not answer.", Modifier.padding(start = 8.dp))
                else if (tracks == null) PlainLine("Loading songs", Modifier.padding(start = 8.dp))
                else if (rows.isEmpty()) PlainLine("Spotify returned no songs for this album.", Modifier.padding(start = 8.dp))
            }
            itemsIndexed(rows, key = { i, t -> "$i-${t.uri}" }) { _, track ->
                val albumIndex = albumRows.indexOf(track).coerceAtLeast(0)
                SongRow(
                    track = track,
                    playing = playback?.uri == track.uri,
                    modifier = Modifier.padding(start = 8.dp),
                    marker = track.uri == topUri,
                    onClick = { a?.uri?.let { app.player.playInContext(it, albumIndex, null, track.uri) } },
                    onSwipeRight = { addTarget = AddTarget(track.uri, track.name) },
                    onPlus = { addTarget = AddTarget(track.uri, track.name) },
                    onPlusLong = { newPlaylistFor = AddTarget(track.uri, track.name) },
                    onLongClick = { menuTrack = track },
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
            onDismiss = { menuTrack = null },
        )
    }
    AddToHost(app, addTarget, newPlaylistFor, onDismiss = { addTarget = null; newPlaylistFor = null }, onDone = { line = it })
}
