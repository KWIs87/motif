package com.kirkisham.motif.ui.playlists

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kirkisham.motif.MotifApp
import com.kirkisham.motif.data.SpPlaylist
import com.kirkisham.motif.ui.components.Chip
import com.kirkisham.motif.ui.components.ListRow
import com.kirkisham.motif.ui.components.MiniPlayer
import com.kirkisham.motif.ui.components.PlaylistMenuHost
import com.kirkisham.motif.ui.components.SectionLabel
import com.kirkisham.motif.ui.components.TopBar
import com.kirkisham.motif.ui.components.pluralSongs
import com.kirkisham.motif.ui.motif
import kotlinx.coroutines.launch

private val SORTS = listOf("Recent", "Name", "Songs")

/** All playlists (board AllPlaylists): every playlist, pinned first, sort Recent / Name / Songs. */
@Composable
fun AllPlaylistsScreen(
    app: MotifApp,
    onBack: () -> Unit,
    onOpenPlaylist: (String) -> Unit,
    onOpenNowPlaying: () -> Unit,
    onPlus: (() -> Unit)?,
) {
    val playlists by app.library.playlists.collectAsStateWithLifecycle()
    val pins by app.prefs.pins.collectAsStateWithLifecycle(emptyList())
    val lastPlayed by app.prefs.lastPlayed.collectAsStateWithLifecycle(emptyMap())
    val sort by app.prefs.allSort.collectAsStateWithLifecycle("Recent")
    val playback by app.remote.playback.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var menuTarget by remember { mutableStateOf<SpPlaylist?>(null) }

    val ordered = remember(playlists, pins, lastPlayed, sort) {
        val all = playlists ?: emptyList()
        val pinSet = pins.toSet()
        val byId = all.associateBy { it.id }
        val pinned = pins.mapNotNull { byId[it] }
        val rest = all.filterNot { it.id in pinSet }.withIndex().sortedWith(
            when (sort) {
                "Name" -> compareBy<IndexedValue<SpPlaylist>> { it.value.name.lowercase() }
                "Songs" -> compareByDescending<IndexedValue<SpPlaylist>> { it.value.trackCount }.thenBy { it.index }
                else -> compareByDescending<IndexedValue<SpPlaylist>> { lastPlayed[it.value.id] ?: 0L }.thenBy { it.index }
            },
        ).map { it.value }
        pinned to rest
    }

    Box(Modifier.fillMaxSize()) {
        LazyColumn(
            Modifier.fillMaxSize().statusBarsPadding(),
            contentPadding = PaddingValues(start = 16.dp, end = 24.dp, bottom = 100.dp),
        ) {
            item { TopBar("All playlists", onBack) }
            item {
                Row(Modifier.padding(start = 8.dp, top = 8.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SectionLabel("Sort", Modifier.padding(end = 4.dp))
                    SORTS.forEach { s -> Chip(s, selected = sort == s, onClick = { scope.launch { app.prefs.setAllSort(s) } }) }
                }
            }
            items(ordered.first, key = { "p-" + it.id }) { p ->
                Row(Modifier.padding(start = 8.dp)) { PlaylistRow(p, true, { onOpenPlaylist(p.id) }, { menuTarget = p }) }
            }
            items(ordered.second, key = { "r-" + it.id }) { p ->
                Row(Modifier.padding(start = 8.dp)) { PlaylistRow(p, false, { onOpenPlaylist(p.id) }, { menuTarget = p }) }
            }
            item { Spacer(Modifier.height(8.dp)) }
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
    PlaylistMenuHost(app, menuTarget, onDismiss = { menuTarget = null })
}

@Composable
private fun PlaylistRow(p: SpPlaylist, pinned: Boolean, onClick: () -> Unit, onLongClick: () -> Unit) {
    ListRow(
        title = p.name,
        subtitle = pluralSongs(p.trackCount),
        coverUrl = p.smallCoverUrl,
        onClick = onClick,
        onLongClick = onLongClick,
    ) {
        if (pinned) Icon(Icons.Outlined.PushPin, "Pinned", tint = motif.accent, modifier = Modifier.size(16.dp))
    }
}
