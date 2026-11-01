package com.kirkisham.motif.ui.home

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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kirkisham.motif.MotifApp
import com.kirkisham.motif.data.SpPlaylist
import com.kirkisham.motif.data.SpotifyRemote
import com.kirkisham.motif.ui.components.Hairline
import com.kirkisham.motif.ui.components.ListRow
import com.kirkisham.motif.ui.components.MiniPlayer
import com.kirkisham.motif.ui.components.PlainLine
import com.kirkisham.motif.ui.components.SectionLabel
import com.kirkisham.motif.ui.components.pluralSongs
import com.kirkisham.motif.ui.motif

/** Home (board Main): pinned playlists, then recent ones, then the rest, mini player at the bottom. */
@Composable
fun HomeScreen(app: MotifApp, onOpenPlaylist: (String) -> Unit, onOpenNowPlaying: () -> Unit) {
    val playlists by app.library.playlists.collectAsStateWithLifecycle()
    val status by app.library.status.collectAsStateWithLifecycle()
    val pins by app.prefs.pins.collectAsStateWithLifecycle(emptyList())
    val lastPlayed by app.prefs.lastPlayed.collectAsStateWithLifecycle(emptyMap())
    val playback by app.remote.playback.collectAsStateWithLifecycle()
    val connection by app.remote.connection.collectAsStateWithLifecycle()
    val notice by app.player.notice.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) { app.library.refreshPlaylists() }

    val ordered = remember(playlists, pins, lastPlayed) { orderForHome(playlists ?: emptyList(), pins, lastPlayed) }

    Box(Modifier.fillMaxSize()) {
        LazyColumn(
            Modifier.fillMaxSize().statusBarsPadding(),
            contentPadding = PaddingValues(start = 24.dp, end = 24.dp, top = 12.dp, bottom = 100.dp),
        ) {
            item {
                Row(Modifier.fillMaxWidth().height(52.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Playlists", style = MaterialTheme.typography.headlineMedium, color = motif.text)
                }
                Spacer(Modifier.height(12.dp))
            }
            connectionLine(connection)?.let { line -> item { PlainLine(line) } }
            notice?.let { line -> item { PlainLine(line) } }
            status?.let { line -> item { PlainLine(line) } }
            if (playlists == null && status == null) item { PlainLine("Loading playlists") }

            if (ordered.pinned.isNotEmpty()) {
                item { SectionLabel("Pinned", Modifier.padding(bottom = 6.dp)) }
                items(ordered.pinned, key = { "p-" + it.id }) { p ->
                    PlaylistRow(p, pinned = true, onClick = { onOpenPlaylist(p.id) }, onPlay = { app.player.playPlaylist(p) })
                }
                item { Spacer(Modifier.height(16.dp)) }
            }
            if (ordered.rest.isNotEmpty()) {
                item { SectionLabel(if (ordered.pinned.isEmpty()) "Playlists" else "Recent", Modifier.padding(bottom = 6.dp)) }
                items(ordered.rest, key = { "r-" + it.id }) { p ->
                    PlaylistRow(p, pinned = false, onClick = { onOpenPlaylist(p.id) }, onPlay = { app.player.playPlaylist(p) })
                }
            }
            item { Spacer(Modifier.height(16.dp)); Hairline() }
        }
        MiniPlayer(
            remote = app.remote,
            playback = playback,
            onToggle = { app.player.togglePlayPause() },
            onNext = { app.player.next() },
            onOpen = onOpenNowPlaying,
            onPlus = null,
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 16.dp),
        )
    }
}

@Composable
private fun PlaylistRow(p: SpPlaylist, pinned: Boolean, onClick: () -> Unit, onPlay: () -> Unit) {
    ListRow(
        title = p.name,
        subtitle = pluralSongs(p.trackCount),
        coverUrl = p.smallCoverUrl,
        onClick = onPlay, // Stage 1: tap plays. Stage 2 moves play into the long-press menu and tap opens the playlist.
        coverSize = 48.dp,
        divider = false,
    ) {
        if (pinned) Icon(Icons.Outlined.PushPin, "Pinned", tint = motif.accent, modifier = Modifier.padding(end = 4.dp).height(16.dp))
    }
}

private fun connectionLine(c: SpotifyRemote.Connection): String? = when (c) {
    SpotifyRemote.Connection.NotInstalled -> "Spotify is not installed on this phone. Motif needs it to play."
    is SpotifyRemote.Connection.Failed -> "Not connected to Spotify. Retrying."
    else -> null
}

class HomeOrder(val pinned: List<SpPlaylist>, val rest: List<SpPlaylist>)

/** Pinned first in pin order; then everything else with the most recently played on top (spec §3.3). */
fun orderForHome(all: List<SpPlaylist>, pins: List<String>, lastPlayed: Map<String, Long>): HomeOrder {
    val byId = all.associateBy { it.id }
    val pinned = pins.mapNotNull { byId[it] }
    val pinSet = pins.toSet()
    val rest = all.filterNot { it.id in pinSet }
        .withIndex()
        .sortedWith(compareByDescending<IndexedValue<SpPlaylist>> { lastPlayed[it.value.id] ?: 0L }.thenBy { it.index })
        .map { it.value }
    return HomeOrder(pinned, rest)
}
