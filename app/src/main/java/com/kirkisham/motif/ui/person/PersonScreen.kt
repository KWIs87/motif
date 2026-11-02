package com.kirkisham.motif.ui.person

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kirkisham.motif.MotifApp
import com.kirkisham.motif.data.About
import com.kirkisham.motif.data.CreditedWork
import com.kirkisham.motif.data.SpArtist
import com.kirkisham.motif.data.SpTrack
import com.kirkisham.motif.ui.MINI_PLAYER_SPACE
import com.kirkisham.motif.ui.components.AddTarget
import com.kirkisham.motif.ui.components.AddToHost
import com.kirkisham.motif.ui.components.Hairline
import com.kirkisham.motif.ui.components.PlainLine
import com.kirkisham.motif.ui.components.SectionLabel
import com.kirkisham.motif.ui.components.TopBar
import com.kirkisham.motif.ui.components.TrackRow
import com.kirkisham.motif.ui.motif
import kotlinx.coroutines.launch

/**
 * Person (0.10, item 9; owner-requested, not on the canvas): opened from a credit row. Header with
 * the name; About (Wikipedia through MusicBrainz and Wikidata, else the Discogs profile) with a link
 * out; Credited on (releases or songs they worked on, matched to Spotify where possible: tap plays a
 * matched song or opens a matched album, unmatched rows are text); then, clearly apart,
 * "Recommendations (similar listening, not their work)" from Last.fm when it knows the name.
 */
@Composable
fun PersonScreen(
    app: MotifApp,
    name: String,
    discogsId: Int?,
    geniusId: Int?,
    onBack: () -> Unit,
    onOpenAlbum: (String) -> Unit,
    onOpenArtist: (SpArtist) -> Unit,
) {
    val context = LocalContext.current
    val playback by app.remote.playback.collectAsStateWithLifecycle()
    var about by remember { mutableStateOf<About?>(null) }
    var works by remember { mutableStateOf<List<CreditedWork>?>(null) }
    var similar by remember { mutableStateOf<List<SpTrack>>(emptyList()) }
    var addTarget by remember { mutableStateOf<AddTarget?>(null) }
    var newPlaylistFor by remember { mutableStateOf<AddTarget?>(null) }
    var line by remember { mutableStateOf<String?>(null) }
    var aboutDone by remember { mutableStateOf(false) }

    LaunchedEffect(name, discogsId, geniusId) {
        launch { about = app.info.personAbout(name, discogsId); aboutDone = true }
        launch { works = app.info.personWorks(name, discogsId, geniusId) }
        launch { similar = app.info.personSimilar(name) }
    }

    LazyColumn(
        Modifier.fillMaxSize().statusBarsPadding(),
        contentPadding = PaddingValues(start = 16.dp, end = 24.dp, bottom = MINI_PLAYER_SPACE),
    ) {
        item {
            TopBar(null, onBack)
            Text(name, style = MaterialTheme.typography.headlineMedium, color = motif.text, modifier = Modifier.padding(start = 8.dp, top = 4.dp), maxLines = 2, overflow = TextOverflow.Ellipsis)
            line?.let { PlainLine(it, Modifier.padding(start = 8.dp)) }
        }

        val a = about
        if (a != null) item {
            Column(Modifier.padding(start = 8.dp, top = 24.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                SectionLabel("About")
                Text(a.text, style = MaterialTheme.typography.bodyMedium, color = motif.textMuted, lineHeight = 22.sp, maxLines = 10, overflow = TextOverflow.Ellipsis)
                a.url?.let { url ->
                    Text(
                        if (a.source == "Wikipedia") "Read on Wikipedia" else "Discogs",
                        style = MaterialTheme.typography.bodySmall, color = motif.accent,
                        modifier = Modifier.clickable { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }.padding(vertical = 4.dp),
                    )
                }
            }
        }

        val w = works
        when {
            w == null -> item { PlainLine(if (aboutDone && a == null) "Looking up credits" else "Loading", Modifier.padding(start = 8.dp, top = 16.dp)) }
            w.isEmpty() -> if (a == null) item { PlainLine("Nothing found for this name.", Modifier.padding(start = 8.dp, top = 16.dp)) }
            else -> {
                item { SectionLabel("Credited on", Modifier.padding(start = 8.dp, top = 28.dp, bottom = 8.dp)) }
                items(w, key = { "w-" + it.title.lowercase() + "|" + it.artist.lowercase() + (it.year ?: "") }) { row ->
                    val t = row.track
                    if (t != null) {
                        Column(Modifier.padding(start = 8.dp)) {
                            TrackRow(
                                track = t,
                                playing = playback?.uri == t.uri,
                                onClick = { app.player.playUri(t.uri) },
                                onPlus = { addTarget = AddTarget(t.uri, t.name) },
                                onPlusLong = { newPlaylistFor = AddTarget(t.uri, t.name) },
                            )
                            Hairline()
                        }
                    } else WorkRow(row, onClick = row.albumId?.let { id -> { onOpenAlbum(id) } })
                }
            }
        }

        if (similar.isNotEmpty()) {
            item {
                Column(Modifier.padding(start = 8.dp, top = 36.dp, bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Hairline(Modifier.padding(bottom = 16.dp))
                    SectionLabel("Recommendations")
                    Text("Similar listening, not their work", style = MaterialTheme.typography.bodySmall, color = motif.textDim)
                }
            }
            items(similar, key = { "s-" + it.uri }) { t ->
                Column(Modifier.padding(start = 8.dp)) {
                    TrackRow(
                        track = t,
                        playing = playback?.uri == t.uri,
                        onClick = { app.player.playUri(t.uri) },
                        onLongClick = { t.artists.firstOrNull { it.id != null }?.let(onOpenArtist) },
                        onPlus = { addTarget = AddTarget(t.uri, t.name) },
                        onPlusLong = { newPlaylistFor = AddTarget(t.uri, t.name) },
                    )
                    Hairline()
                }
            }
        }
        item { Spacer(Modifier.height(8.dp)) }
    }

    AddToHost(app, addTarget, newPlaylistFor, onDismiss = { addTarget = null; newPlaylistFor = null }, onDone = { line = it })
}

/** A credited release without a Spotify match (text only) or with an album match (tap opens it). */
@Composable
private fun WorkRow(w: CreditedWork, onClick: (() -> Unit)?) {
    Column(Modifier.padding(start = 8.dp)) {
        Row(
            Modifier.fillMaxWidth().height(52.dp).then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(w.title, style = MaterialTheme.typography.bodyMedium, color = if (onClick != null) motif.text else motif.textMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    listOfNotNull(w.artist.takeIf { it.isNotBlank() }, w.year, w.role).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall, color = motif.textDim, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
            if (onClick != null) Text("Album", style = MaterialTheme.typography.bodySmall, color = motif.textMuted)
        }
        Hairline()
    }
}
