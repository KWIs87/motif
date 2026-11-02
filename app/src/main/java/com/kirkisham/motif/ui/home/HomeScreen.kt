package com.kirkisham.motif.ui.home

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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kirkisham.motif.MotifApp
import com.kirkisham.motif.ui.MINI_PLAYER_SPACE
import com.kirkisham.motif.data.LastEpisode
import com.kirkisham.motif.data.SpPlaylist
import com.kirkisham.motif.data.SpShow
import com.kirkisham.motif.data.SpotifyRemote
import com.kirkisham.motif.ui.components.ArtBox
import com.kirkisham.motif.ui.components.Hairline
import com.kirkisham.motif.ui.components.IconButton44
import com.kirkisham.motif.ui.components.ListRow
import com.kirkisham.motif.ui.components.MenuItem
import com.kirkisham.motif.ui.components.MenuSheet
import com.kirkisham.motif.ui.components.PillSearchBox
import com.kirkisham.motif.ui.components.rankByName
import com.kirkisham.motif.ui.components.PlainLine
import com.kirkisham.motif.ui.components.PlaylistMenuHost
import com.kirkisham.motif.ui.components.SectionLabel
import com.kirkisham.motif.ui.components.pluralSongs
import com.kirkisham.motif.ui.components.rememberArt
import com.kirkisham.motif.ui.motif
import com.spotify.protocol.types.Image

/**
 * Home (board Main): header, search box (0.10, item 4), pinned, recent, All playlists / All podcasts
 * rows, last episode. The ribbon at the bottom is shared (Nav). While the search box has text the
 * playlists show as one ranked, filtered list; the magnifier or the keyboard's search action opens
 * the full Search screen with the text.
 */
@Composable
fun HomeScreen(
    app: MotifApp,
    onOpenPlaylist: (String) -> Unit,
    onOpenAll: () -> Unit,
    onOpenAlarm: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenSearch: (String) -> Unit,
) {
    val playlists by app.library.playlists.collectAsStateWithLifecycle()
    val shows by app.library.shows.collectAsStateWithLifecycle()
    val status by app.library.status.collectAsStateWithLifecycle()
    val pins by app.prefs.pins.collectAsStateWithLifecycle(emptyList())
    val lastPlayed by app.prefs.lastPlayed.collectAsStateWithLifecycle(emptyMap())
    val lastEpisode by app.prefs.lastEpisode.collectAsStateWithLifecycle(null)
    val playback by app.remote.playback.collectAsStateWithLifecycle()
    val connection by app.remote.connection.collectAsStateWithLifecycle()
    val notice by app.player.notice.collectAsStateWithLifecycle()

    var menuTarget by remember { mutableStateOf<SpPlaylist?>(null) }
    var showPodcasts by remember { mutableStateOf(false) }
    var filter by rememberSaveable { mutableStateOf("") }
    val keyboard = LocalSoftwareKeyboardController.current

    LaunchedEffect(Unit) {
        app.library.refreshPlaylists()
        app.library.refreshShows()
    }

    val ordered = remember(playlists, pins, lastPlayed) { orderForHome(playlists ?: emptyList(), pins, lastPlayed) }
    val recent = ordered.rest.take(RECENT_ON_HOME)
    val filtering = filter.isNotBlank()
    // Filtered: pinned, then the rest by recency, as one list ranked by how the name matches (items 3 and 4).
    val filtered = remember(ordered, filter) { if (filtering) rankByName(ordered.pinned + ordered.rest, filter) { it.name } else emptyList() }
    val pinSet = pins.toSet()

    Box(Modifier.fillMaxSize()) {
        LazyColumn(
            Modifier.fillMaxSize().statusBarsPadding(),
            contentPadding = PaddingValues(start = 24.dp, end = 14.dp, top = 12.dp, bottom = MINI_PLAYER_SPACE),
        ) {
            item {
                Row(Modifier.fillMaxWidth().height(52.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Playlists", style = MaterialTheme.typography.headlineMedium, color = motif.text, modifier = Modifier.weight(1f))
                    // Star: Claude sheet, Build 2. Shows nothing yet (spec §3.3).
                    IconButton44(Icons.Outlined.AutoAwesome, "Ask Claude (later)", onClick = {}, tint = motif.accent)
                    IconButton44(Icons.Outlined.Search, "Search", { keyboard?.hide(); onOpenSearch(filter.trim()) })
                    IconButton44(Icons.Outlined.Schedule, "Alarm", onOpenAlarm)
                    IconButton44(Icons.Outlined.Settings, "Settings", onOpenSettings, tint = motif.textMuted)
                }
                PillSearchBox(
                    value = filter,
                    onChange = { filter = it },
                    placeholder = "Find a playlist, song, artist",
                    onSubmit = { keyboard?.hide(); onOpenSearch(filter.trim()) },
                    modifier = Modifier.fillMaxWidth().padding(end = 10.dp),
                )
                Spacer(Modifier.height(16.dp))
            }
            connectionLine(connection)?.let { line -> item { PlainLine(line) } }
            notice?.let { line -> item { PlainLine(line) } }
            status?.let { line -> item { PlainLine(line) } }
            if (playlists == null && status == null) item { PlainLine("Loading playlists") }

            if (filtering) {
                if (filtered.isEmpty() && playlists != null) item { PlainLine("No playlist matches. Tap the magnifier to search Spotify.") }
                items(filtered, key = { "f-" + it.id }) { p ->
                    PlaylistRow(p, pinned = p.id in pinSet, onClick = { onOpenPlaylist(p.id) }, onLongClick = { menuTarget = p })
                }
            }
            if (!filtering && ordered.pinned.isNotEmpty()) {
                item { SectionLabel("Pinned", Modifier.padding(bottom = 6.dp)) }
                items(ordered.pinned, key = { "p-" + it.id }) { p ->
                    PlaylistRow(p, pinned = true, onClick = { onOpenPlaylist(p.id) }, onLongClick = { menuTarget = p })
                }
                item { Spacer(Modifier.height(16.dp)) }
            }
            if (!filtering && recent.isNotEmpty()) {
                item { SectionLabel("Recent", Modifier.padding(bottom = 6.dp)) }
                items(recent, key = { "r-" + it.id }) { p ->
                    PlaylistRow(p, pinned = false, onClick = { onOpenPlaylist(p.id) }, onLongClick = { menuTarget = p })
                }
            }
            if (!filtering) item {
                Spacer(Modifier.height(12.dp))
                Hairline(Modifier.padding(end = 10.dp))
                Spacer(Modifier.height(8.dp))
                NavRow("All playlists", onOpenAll)
                NavRow("All podcasts") { showPodcasts = true }
                Spacer(Modifier.height(8.dp))
            }
            if (!filtering) lastEpisode?.let { ep ->
                item { LastEpisodeCard(app.remote, ep, playback) { app.player.playUri(ep.uri) } }
            }
        }
    }

    PlaylistMenuHost(app, menuTarget, onDismiss = { menuTarget = null })

    if (showPodcasts) {
        PodcastsSheet(shows, onPick = { app.player.playUri(it.uri) }, onDismiss = { showPodcasts = false })
    }
}

private const val RECENT_ON_HOME = 6

@Composable
private fun PlaylistRow(p: SpPlaylist, pinned: Boolean, onClick: () -> Unit, onLongClick: () -> Unit) {
    ListRow(
        title = p.name,
        subtitle = p.itemCount?.let(::pluralSongs),
        coverUrl = p.smallCoverUrl,
        onClick = onClick,
        onLongClick = onLongClick,
        coverSize = 48.dp,
        divider = false,
        modifier = Modifier.padding(end = 10.dp),
    ) {
        if (pinned) Icon(Icons.Outlined.PushPin, "Pinned", tint = motif.accent, modifier = Modifier.size(16.dp))
    }
}

@Composable
private fun NavRow(label: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().height(48.dp).clickable(onClick = onClick).padding(end = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.titleMedium, color = motif.text, modifier = Modifier.weight(1f))
        Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, null, tint = motif.textMuted, modifier = Modifier.size(18.dp))
    }
}

/** "Last episode" card from the board: cover, label, title, show and time left, play glyph. */
@Composable
private fun LastEpisodeCard(remote: SpotifyRemote, ep: LastEpisode, playback: SpotifyRemote.Playback?, onPlay: () -> Unit) {
    val art = rememberArt(remote, ep.imageUrl, Image.Dimension.SMALL)
    val live = playback?.takeIf { it.uri == ep.uri }
    val position = live?.positionNow() ?: ep.positionMs
    val leftMin = ((ep.durationMs - position) / 60_000).coerceAtLeast(0)
    val sub = if (ep.durationMs > 0) "${ep.showName} · $leftMin min left" else ep.showName
    Row(
        Modifier
            .fillMaxWidth()
            .padding(end = 10.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(motif.surface)
            .clickable(onClick = onPlay)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        ArtBox(art, 56.dp)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            SectionLabel("Last episode")
            Text(ep.name, style = MaterialTheme.typography.labelLarge, color = motif.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(sub, style = MaterialTheme.typography.bodySmall, color = motif.textMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Icon(Icons.Filled.PlayArrow, "Resume", tint = motif.text, modifier = Modifier.size(22.dp))
    }
}

/** The user's saved shows. Tapping one plays it (Spotify picks up where that show left off). */
@Composable
private fun PodcastsSheet(shows: List<SpShow>?, onPick: (SpShow) -> Unit, onDismiss: () -> Unit) {
    val items = shows.orEmpty().map { s -> MenuItem(s.name) { onPick(s) } }
    MenuSheet(
        title = "Podcasts",
        subtitle = when {
            shows == null -> "Loading"
            shows.isEmpty() -> "No saved podcasts in your Spotify library."
            else -> null
        },
        items = items,
        onDismiss = onDismiss,
    )
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
