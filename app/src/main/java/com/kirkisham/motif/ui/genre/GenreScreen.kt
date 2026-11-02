package com.kirkisham.motif.ui.genre

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kirkisham.motif.MotifApp
import com.kirkisham.motif.data.SpArtist
import com.kirkisham.motif.data.SpTrack
import com.kirkisham.motif.ui.MINI_PLAYER_SPACE
import com.kirkisham.motif.ui.components.AddTarget
import com.kirkisham.motif.ui.components.AddToHost
import com.kirkisham.motif.ui.components.Cover
import com.kirkisham.motif.ui.components.Hairline
import com.kirkisham.motif.ui.components.PlainLine
import com.kirkisham.motif.ui.components.SectionLabel
import com.kirkisham.motif.ui.components.SongMenuSheet
import com.kirkisham.motif.ui.components.TopBar
import com.kirkisham.motif.ui.components.TrackRow
import com.kirkisham.motif.ui.motif
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * Genre (0.10, item 10; owner-requested, not on the canvas): opened by tapping a tag chip. Title is
 * the tag; "Top artists" is a horizontal row of Last.fm's top artists for the tag matched to Spotify
 * (tap opens the artist); "Top tracks" lists Last.fm's top tracks matched to Spotify (tap plays,
 * plus adds, long press on the plus makes a new playlist). Rows appear as each batch matches.
 */
@Composable
fun GenreScreen(
    app: MotifApp,
    tag: String,
    onBack: () -> Unit,
    onOpenArtist: (SpArtist) -> Unit,
    onOpenAlbum: (String) -> Unit,
) {
    val playback by app.remote.playback.collectAsStateWithLifecycle()
    var artists by remember { mutableStateOf<List<SpArtist>>(emptyList()) }
    var tracks by remember { mutableStateOf<List<SpTrack>>(emptyList()) }
    var artistsDone by remember { mutableStateOf(false) }
    var tracksDone by remember { mutableStateOf(false) }
    var menuTrack by remember { mutableStateOf<SpTrack?>(null) }
    var addTarget by remember { mutableStateOf<AddTarget?>(null) }
    var newPlaylistFor by remember { mutableStateOf<AddTarget?>(null) }
    var line by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(tag) {
        launch { artists = app.info.genreArtists(tag) { artists = it }; artistsDone = true }
        launch { tracks = app.info.genreTracks(tag) { tracks = it }; tracksDone = true }
    }

    LazyColumn(
        Modifier.fillMaxSize().statusBarsPadding(),
        contentPadding = PaddingValues(start = 16.dp, end = 24.dp, bottom = MINI_PLAYER_SPACE),
    ) {
        item {
            TopBar(null, onBack)
            Text(
                tag.replaceFirstChar { it.titlecase(Locale.getDefault()) },
                style = MaterialTheme.typography.headlineMedium, color = motif.text,
                modifier = Modifier.padding(start = 8.dp, top = 4.dp), maxLines = 2, overflow = TextOverflow.Ellipsis,
            )
            line?.let { PlainLine(it, Modifier.padding(start = 8.dp)) }
        }

        if (artists.isNotEmpty()) item {
            Column(Modifier.padding(top = 24.dp)) {
                SectionLabel("Top artists", Modifier.padding(start = 8.dp, bottom = 12.dp))
                LazyRow(contentPadding = PaddingValues(start = 8.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    items(artists, key = { it.id ?: it.name }) { a ->
                        Column(
                            Modifier.width(84.dp).clickable { onOpenArtist(a) },
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Cover(a.smallImageUrl, 72.dp, radius = 36.dp)
                            Text(a.name, style = MaterialTheme.typography.bodySmall, color = motif.text, maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
                        }
                    }
                }
            }
        } else if (!artistsDone) item { PlainLine("Finding artists", Modifier.padding(start = 8.dp, top = 24.dp)) }

        if (tracks.isNotEmpty()) {
            item { SectionLabel("Top tracks", Modifier.padding(start = 8.dp, top = 28.dp, bottom = 8.dp)) }
            items(tracks, key = { "t-" + it.uri }) { t ->
                Column(Modifier.padding(start = 8.dp)) {
                    TrackRow(
                        track = t,
                        playing = playback?.uri == t.uri,
                        onClick = { app.player.playUri(t.uri) },
                        onLongClick = { menuTrack = t },
                        onPlus = { addTarget = AddTarget(t.uri, t.name) },
                        onPlusLong = { newPlaylistFor = AddTarget(t.uri, t.name) },
                    )
                    Hairline()
                }
            }
        } else if (!tracksDone) item { PlainLine("Finding songs", Modifier.padding(start = 8.dp, top = 24.dp)) }

        if (artistsDone && tracksDone && artists.isEmpty() && tracks.isEmpty()) {
            item { PlainLine("Last.fm has nothing for this tag that Spotify could match.", Modifier.padding(start = 8.dp, top = 24.dp)) }
        }
        item { Spacer(Modifier.height(8.dp)) }
    }

    menuTrack?.let { t ->
        SongMenuSheet(app, t, onAddTo = { addTarget = it }, onGoToArtist = onOpenArtist, onGoToAlbum = onOpenAlbum, onDismiss = { menuTrack = null })
    }
    AddToHost(app, addTarget, newPlaylistFor, onDismiss = { addTarget = null; newPlaylistFor = null }, onDone = { line = it })
}
