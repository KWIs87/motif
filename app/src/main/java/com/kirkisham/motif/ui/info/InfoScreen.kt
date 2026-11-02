package com.kirkisham.motif.ui.info

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kirkisham.motif.BuildConfig
import com.kirkisham.motif.MotifApp
import com.kirkisham.motif.data.ArtistFacts
import com.kirkisham.motif.data.Concert
import com.kirkisham.motif.data.Credit
import com.kirkisham.motif.data.SpArtist
import com.kirkisham.motif.data.SpTrack
import com.kirkisham.motif.data.Where
import com.kirkisham.motif.ui.components.AddTarget
import com.kirkisham.motif.ui.components.AddToHost
import com.kirkisham.motif.ui.components.Cover
import com.kirkisham.motif.ui.components.Hairline
import com.kirkisham.motif.ui.components.IconButton44
import com.kirkisham.motif.ui.components.PlainLine
import com.kirkisham.motif.ui.components.SectionLabel
import com.kirkisham.motif.ui.components.TrackRow
import com.kirkisham.motif.ui.motif
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/** What the info screen was opened for: a track (art tap on Now playing) or an artist (Go to artist). */
enum class InfoKind { TRACK, ARTIST }

/**
 * Song and artist info (board SongInfo): header, Credits (Genius or Discogs) with Last.fm tags,
 * Songs like this (Last.fm matched to Spotify), Artist (MusicBrainz, Wikidata, Wikipedia),
 * Near you (Ticketmaster, needs a key and coarse location). Every section hides until it has
 * data; lookups run in parallel and fail silently. "Lately" is parked.
 */
@Composable
fun InfoScreen(
    app: MotifApp,
    kind: InfoKind,
    id: String,
    onBack: () -> Unit,
    onSearch: (String) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val playback by app.remote.playback.collectAsStateWithLifecycle()
    val contextUri by app.remote.contextUri.collectAsStateWithLifecycle()
    val playlists by app.library.playlists.collectAsStateWithLifecycle()
    val locationAsked by app.prefs.locationAsked.collectAsStateWithLifecycle(true)

    var track by remember { mutableStateOf<SpTrack?>(null) }
    var artist by remember { mutableStateOf<SpArtist?>(null) }
    var failed by remember { mutableStateOf(false) }
    var credits by remember { mutableStateOf<List<Credit>>(emptyList()) }
    var tags by remember { mutableStateOf<List<String>>(emptyList()) }
    var similar by remember { mutableStateOf<List<SpTrack>>(emptyList()) }
    var facts by remember { mutableStateOf<ArtistFacts?>(null) }
    var concerts by remember { mutableStateOf<List<Concert>?>(null) }
    var hasLocation by remember { mutableStateOf(Where.hasPermission(context)) }
    var addTarget by remember { mutableStateOf<AddTarget?>(null) }
    var line by remember { mutableStateOf<String?>(null) }

    val askLocation = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        hasLocation = granted
        scope.launch { app.prefs.setLocationAsked() }
    }

    // Step 1: the Spotify objects. Step 2: the outside lookups, each on its own.
    LaunchedEffect(kind, id) {
        when (kind) {
            InfoKind.TRACK -> {
                val t = app.api.track(id)
                track = t
                failed = t == null
                t?.artists?.firstOrNull { it.id != null }?.id?.let { artist = app.api.artist(it) ?: t.artists.first() }
                if (t != null && artist == null) artist = t.artists.firstOrNull()
            }
            InfoKind.ARTIST -> {
                val a = app.api.artist(id)
                artist = a
                failed = a == null
            }
        }
    }
    LaunchedEffect(track?.uri) {
        val t = track ?: return@LaunchedEffect
        launch { credits = app.info.credits(t) }
        launch { tags = app.info.tags(t) }
        launch { similar = app.info.similar(t) }
    }
    LaunchedEffect(artist?.id, artist?.name) {
        val a = artist ?: return@LaunchedEffect
        launch { facts = app.info.facts(a) }
        if (kind == InfoKind.ARTIST) launch { similar = app.info.similarForArtist(a) }
    }
    LaunchedEffect(artist?.name, hasLocation) {
        val a = artist ?: return@LaunchedEffect
        if (!hasLocation || BuildConfig.TICKETMASTER_API_KEY.isBlank()) return@LaunchedEffect
        val loc = Where.coarse(context) ?: return@LaunchedEffect
        concerts = app.info.concerts(a.name, loc.first, loc.second)
    }

    // Plus on "Songs like this": straight into the playlist that is playing, else the add sheet.
    val playingPlaylist = contextUri?.takeIf { it.startsWith("spotify:playlist:") }?.let { uri ->
        playlists?.firstOrNull { it.uri == uri }
    }
    fun addSimilar(t: SpTrack) {
        val p = playingPlaylist
        if (p == null) { addTarget = AddTarget(t.uri, t.name); return }
        scope.launch {
            val ok = app.library.addTrack(p.id, t.uri)
            line = if (ok) "Added to ${p.name}" else "Could not add to ${p.name}"
            if (ok) app.prefs.markAddedTo(p.id)
        }
    }

    val title = track?.name ?: artist?.name ?: ""
    val subtitle = when {
        track != null -> listOfNotNull(track?.artistNames?.takeIf { it.isNotBlank() }, track?.album?.year).joinToString(" · ")
        artist != null -> artist?.genres?.take(2)?.joinToString(", ").orEmpty()
        else -> ""
    }
    val art = track?.smallCoverUrl ?: artist?.smallImageUrl
    val showLocationLine = kind != InfoKind.TRACK || track != null
    val needLocationLine = BuildConfig.TICKETMASTER_API_KEY.isNotBlank() && !hasLocation && !locationAsked && artist != null && showLocationLine

    LazyColumn(
        Modifier.fillMaxSize().statusBarsPadding(),
        contentPadding = PaddingValues(start = 16.dp, end = 24.dp, bottom = 40.dp),
    ) {
        item {
            Row(Modifier.fillMaxWidth().height(60.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                IconButton44(Icons.AutoMirrored.Outlined.ArrowBack, "Back", onBack)
                Cover(art, 48.dp, radius = if (kind == InfoKind.ARTIST) 24.dp else 4.dp)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(title, style = MaterialTheme.typography.titleMedium, color = motif.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (subtitle.isNotBlank()) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = motif.textMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            Spacer(Modifier.height(12.dp))
            if (failed) PlainLine("Spotify did not answer.", Modifier.padding(start = 8.dp))
            line?.let { PlainLine(it, Modifier.padding(start = 8.dp)) }
        }

        if (credits.isNotEmpty() || tags.isNotEmpty()) {
            item { SectionLabel("Credits", Modifier.padding(start = 8.dp, top = 16.dp, bottom = 8.dp)) }
            items(credits, key = { "c-" + it.name + it.role }) { c ->
                Column(Modifier.padding(start = 8.dp)) {
                    Row(
                        Modifier.fillMaxWidth().height(48.dp).clickable { onSearch(c.name) },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(c.name, style = MaterialTheme.typography.bodyMedium, color = motif.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(c.role, style = MaterialTheme.typography.bodySmall, color = motif.textMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, null, tint = motif.textMuted, modifier = Modifier.size(16.dp))
                    }
                    Hairline()
                }
            }
            if (tags.isNotEmpty()) item { TagChips(tags) }
        }

        if (similar.isNotEmpty()) {
            item { SectionLabel("Songs like this", Modifier.padding(start = 8.dp, top = 28.dp, bottom = 8.dp)) }
            items(similar, key = { "s-" + it.uri }) { t ->
                Column(Modifier.padding(start = 8.dp)) {
                    TrackRow(
                        track = t,
                        playing = playback?.uri == t.uri,
                        onClick = { app.player.playUri(t.uri) },
                        onPlus = { addSimilar(t) },
                        plusTint = motif.text,
                    )
                    Hairline()
                }
            }
            playingPlaylist?.let { p ->
                item { Text("Plus adds to ${p.name}", style = MaterialTheme.typography.bodySmall, color = motif.textDim, modifier = Modifier.padding(start = 8.dp, top = 8.dp)) }
            }
        }

        val f = facts
        val a = artist
        if (f != null && a != null) artistSection(a, f, onOpenUrl = { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(it))) })

        if (needLocationLine) {
            item {
                Text(
                    "Allow location to see concerts near you",
                    style = MaterialTheme.typography.bodyMedium, color = motif.accent,
                    modifier = Modifier.padding(start = 8.dp, top = 28.dp).clickable { askLocation.launch(android.Manifest.permission.ACCESS_COARSE_LOCATION) },
                )
            }
        }
        val c = concerts
        if (!c.isNullOrEmpty()) {
            item { SectionLabel("Near you", Modifier.padding(start = 8.dp, top = 28.dp, bottom = 8.dp)) }
            items(c, key = { "n-" + it.venue + it.date }) { ev ->
                Column(Modifier.padding(start = 8.dp)) {
                    Row(
                        Modifier.fillMaxWidth().height(56.dp).clickable(enabled = ev.url != null) { ev.url?.let { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(it))) } },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(listOf(ev.venue, ev.city).filter { it.isNotBlank() }.joinToString(", "), style = MaterialTheme.typography.bodyMedium, color = motif.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(prettyDate(ev.date), style = MaterialTheme.typography.bodySmall, color = motif.textMuted)
                        }
                        if (ev.url != null) Text("Tickets", style = MaterialTheme.typography.bodySmall, color = motif.accent)
                    }
                    Hairline()
                }
            }
        }
    }

    AddToHost(app, addTarget, null, onDismiss = { addTarget = null }, onDone = { line = it })
}

private fun LazyListScope.artistSection(a: SpArtist, f: ArtistFacts, onOpenUrl: (String) -> Unit) {
    item {
        Column(Modifier.padding(start = 8.dp, top = 28.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SectionLabel("Artist")
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Cover(a.smallImageUrl, 72.dp, radius = 36.dp)
                val cells = listOfNotNull(
                    f.origin?.let { "From" to it },
                    f.active?.let { "Active" to it },
                    f.members?.let { "Members" to it.toString() },
                    f.genres.firstOrNull()?.let { "Genre" to f.genres.take(2).joinToString(", ") },
                )
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    cells.chunked(2).forEach { pair ->
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                            pair.forEach { (label, value) ->
                                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                    Text(label, fontSize = 11.sp * motif.textScale, color = motif.textMuted)
                                    Text(value, style = MaterialTheme.typography.bodySmall, color = motif.text, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                }
                            }
                            if (pair.size == 1) Spacer(Modifier.weight(1f))
                        }
                    }
                }
            }
            f.bio?.let { bio ->
                Text(bio, style = MaterialTheme.typography.bodyMedium, color = motif.textMuted, lineHeight = 22.sp, maxLines = 8, overflow = TextOverflow.Ellipsis)
                f.wikipediaUrl?.let { url ->
                    Text("Read on Wikipedia", style = MaterialTheme.typography.bodySmall, color = motif.accent, modifier = Modifier.clickable { onOpenUrl(url) }.padding(vertical = 4.dp))
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TagChips(tags: List<String>) {
    FlowRow(Modifier.padding(start = 8.dp, top = 14.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        tags.forEach { t ->
            Box(
                Modifier.height(28.dp).clip(RoundedCornerShape(14.dp)).border(1.dp, motif.chipLine, RoundedCornerShape(14.dp)).padding(horizontal = 12.dp),
                contentAlignment = Alignment.Center,
            ) { Text(t.replaceFirstChar { it.titlecase(Locale.getDefault()) }, style = MaterialTheme.typography.bodySmall, color = motif.textMuted) }
        }
    }
}

private fun prettyDate(iso: String): String = runCatching {
    LocalDate.parse(iso).format(DateTimeFormatter.ofPattern("EEE, MMM d, yyyy", Locale.US))
}.getOrDefault(iso)
