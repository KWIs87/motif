package com.kirkisham.motif.ui.search

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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kirkisham.motif.MotifApp
import com.kirkisham.motif.data.SpArtist
import com.kirkisham.motif.data.SpSearchResult
import com.kirkisham.motif.data.SpTrack
import com.kirkisham.motif.ui.components.AddTarget
import com.kirkisham.motif.ui.components.AddToHost
import com.kirkisham.motif.ui.components.Cover
import com.kirkisham.motif.ui.components.IconButton44
import com.kirkisham.motif.ui.components.ListRow
import com.kirkisham.motif.ui.components.MiniPlayer
import com.kirkisham.motif.ui.components.PlainLine
import com.kirkisham.motif.ui.components.SectionLabel
import com.kirkisham.motif.ui.components.SongMenuSheet
import com.kirkisham.motif.ui.components.TrackRow
import com.kirkisham.motif.ui.components.formatLength
import com.kirkisham.motif.ui.components.pluralSongs
import com.kirkisham.motif.ui.motif
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Search (board Search): a pill search box under the back arrow, then Songs, Artists, Playlists
 * and Podcasts (shows and episodes) from one GET /search. Tap a song to play it, long press for
 * the song menu, tap a show to play its latest episode. Mini player at the bottom.
 */
@Composable
fun SearchScreen(
    app: MotifApp,
    initialQuery: String = "",
    onBack: () -> Unit,
    onOpenPlaylist: (String) -> Unit,
    onOpenArtist: (SpArtist) -> Unit,
    onOpenNowPlaying: () -> Unit,
    onPlus: (() -> Unit)?,
) {
    val playback by app.remote.playback.collectAsStateWithLifecycle()
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var query by rememberSaveable { mutableStateOf(initialQuery) }
    var result by remember { mutableStateOf<SpSearchResult?>(null) }
    var searching by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    var menuTrack by remember { mutableStateOf<SpTrack?>(null) }
    var addTarget by remember { mutableStateOf<AddTarget?>(null) }
    var line by remember { mutableStateOf<String?>(null) }
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current

    LaunchedEffect(query) {
        if (query.isBlank()) { result = null; searching = false; failed = false; return@LaunchedEffect }
        searching = true
        delay(350)
        val r = app.api.search(query.trim())
        failed = r == null
        result = r
        searching = false
    }
    LaunchedEffect(Unit) { if (initialQuery.isBlank()) focus.requestFocus() }

    Box(Modifier.fillMaxSize()) {
        LazyColumn(
            Modifier.fillMaxSize().statusBarsPadding(),
            contentPadding = PaddingValues(start = 12.dp, end = 24.dp, top = 4.dp, bottom = 100.dp),
        ) {
            item {
                Row(Modifier.fillMaxWidth().height(52.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    IconButton44(Icons.AutoMirrored.Outlined.ArrowBack, "Back", onBack)
                    SearchBox(
                        value = query,
                        onChange = { query = it },
                        onSubmit = { keyboard?.hide() },
                        onClear = { query = ""; focus.requestFocus() },
                        modifier = Modifier.weight(1f).focusRequester(focus),
                    )
                }
                Spacer(Modifier.height(16.dp))
            }
            val r = result
            when {
                query.isBlank() -> {}
                r == null && searching -> item { PlainLine("Searching", Modifier.padding(start = 12.dp)) }
                r == null && failed -> item { PlainLine("Spotify did not answer. Try again.", Modifier.padding(start = 12.dp)) }
                r != null && r.isEmpty && !searching -> item { PlainLine("Nothing found for “${query.trim()}”.", Modifier.padding(start = 12.dp)) }
                r != null -> results(
                    r = r,
                    playingUri = playback?.uri,
                    onPlayTrack = { app.player.playUri(it.uri) },
                    onTrackMenu = { menuTrack = it },
                    onPlusTrack = { addTarget = AddTarget(it.uri, it.name) },
                    onOpenArtist = onOpenArtist,
                    onOpenPlaylist = onOpenPlaylist,
                    onPlayShow = { show ->
                        scope.launch {
                            val latest = app.api.showEpisodes(show.id, 1)?.firstOrNull()
                            app.player.playUri(latest?.uri ?: show.uri)
                        }
                    },
                    onPlayEpisode = { app.player.playUri(it.uri) },
                )
            }
            line?.let { item { PlainLine(it, Modifier.padding(start = 12.dp)) } }
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
        SongMenuSheet(app, t, onAddTo = { addTarget = it }, onGoToArtist = onOpenArtist, onDismiss = { menuTrack = null })
    }
    AddToHost(app, addTarget, null, onDismiss = { addTarget = null }, onDone = { line = it })
}

private fun LazyListScope.results(
    r: SpSearchResult,
    playingUri: String?,
    onPlayTrack: (SpTrack) -> Unit,
    onTrackMenu: (SpTrack) -> Unit,
    onPlusTrack: (SpTrack) -> Unit,
    onOpenArtist: (SpArtist) -> Unit,
    onOpenPlaylist: (String) -> Unit,
    onPlayShow: (com.kirkisham.motif.data.SpShow) -> Unit,
    onPlayEpisode: (com.kirkisham.motif.data.SpEpisode) -> Unit,
) {
    val tracks = r.trackList
    val artists = r.artistList.take(3)
    val playlists = r.playlistList.take(5)
    val shows = r.showList.take(3)
    val episodes = r.episodeList.take(5)

    if (tracks.isNotEmpty()) {
        item { SectionLabel("Songs", Modifier.padding(start = 12.dp, bottom = 6.dp)) }
        items(tracks, key = { "t-" + it.uri }) { t ->
            TrackRow(
                track = t,
                playing = playingUri == t.uri,
                onClick = { onPlayTrack(t) },
                onLongClick = { onTrackMenu(t) },
                onPlus = { onPlusTrack(t) },
                modifier = Modifier.padding(start = 12.dp),
            )
        }
        item { Spacer(Modifier.height(20.dp)) }
    }
    if (artists.isNotEmpty()) {
        item { SectionLabel("Artists", Modifier.padding(start = 12.dp, bottom = 6.dp)) }
        items(artists, key = { "a-" + (it.id ?: it.name) }) { a ->
            Row(
                Modifier.fillMaxWidth().height(56.dp).clickable { onOpenArtist(a) }.padding(start = 12.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Cover(a.smallImageUrl, 40.dp, radius = 20.dp)
                Text(a.name, style = MaterialTheme.typography.labelLarge, color = motif.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        item { Spacer(Modifier.height(20.dp)) }
    }
    if (playlists.isNotEmpty()) {
        item { SectionLabel("Playlists", Modifier.padding(start = 12.dp, bottom = 6.dp)) }
        items(playlists, key = { "p-" + it.id }) { p ->
            val by = p.owner?.display_name?.takeIf { it.isNotBlank() }
            ListRow(
                title = p.name,
                subtitle = listOfNotNull(p.itemCount?.takeIf { it > 0 }?.let(::pluralSongs), by).joinToString(" · "),
                coverUrl = p.smallCoverUrl,
                onClick = { onOpenPlaylist(p.id) },
                coverSize = 40.dp,
                divider = false,
                modifier = Modifier.padding(start = 12.dp),
            )
        }
        item { Spacer(Modifier.height(20.dp)) }
    }
    if (shows.isNotEmpty() || episodes.isNotEmpty()) {
        item { SectionLabel("Podcasts", Modifier.padding(start = 12.dp, bottom = 6.dp)) }
        items(shows, key = { "s-" + it.id }) { s ->
            val n = s.total_episodes
            ListRow(
                title = s.name,
                subtitle = if (n != null && n > 0) "Podcast · $n episodes" else "Podcast",
                coverUrl = s.smallCoverUrl,
                onClick = { onPlayShow(s) },
                coverSize = 40.dp,
                divider = false,
                modifier = Modifier.padding(start = 12.dp),
            )
        }
        items(episodes, key = { "e-" + it.id }) { e ->
            val show = e.show?.name
            val len = formatLength(e.duration_ms)
            ListRow(
                title = e.name,
                subtitle = if (show != null) "$show · $len" else "Episode · $len",
                coverUrl = e.smallCoverUrl,
                onClick = { onPlayEpisode(e) },
                coverSize = 40.dp,
                divider = false,
                modifier = Modifier.padding(start = 12.dp),
            )
        }
    }
}

/** 46 dp pill text field in the board's style, with a clear glyph while it has text. */
@Composable
private fun SearchBox(value: String, onChange: (String) -> Unit, onSubmit: () -> Unit, onClear: () -> Unit, modifier: Modifier) {
    val shape = RoundedCornerShape(23.dp)
    Row(
        modifier.height(46.dp).clip(shape).background(motif.surface).border(1.dp, motif.chipLine, shape).padding(start = 18.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.weight(1f)) {
            if (value.isEmpty()) Text("Search songs, artists, podcasts", style = MaterialTheme.typography.bodyLarge, color = motif.textDim, maxLines = 1)
            BasicTextField(
                value = value,
                onValueChange = onChange,
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = motif.text),
                cursorBrush = SolidColor(motif.accent),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { onSubmit() }),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (value.isNotEmpty()) IconButton44(Icons.Outlined.Close, "Clear", onClear, tint = motif.textMuted, size = 18.dp)
        else Spacer(Modifier.height(44.dp))
    }
}
