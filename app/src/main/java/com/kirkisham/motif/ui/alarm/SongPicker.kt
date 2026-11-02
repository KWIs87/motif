package com.kirkisham.motif.ui.alarm

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kirkisham.motif.MotifApp
import com.kirkisham.motif.alarm.AlarmSong
import com.kirkisham.motif.data.SpPlaylist
import com.kirkisham.motif.data.SpPlaylistItem
import com.kirkisham.motif.data.SpTrack
import com.kirkisham.motif.ui.components.Cover
import com.kirkisham.motif.ui.components.Hairline
import com.kirkisham.motif.ui.components.IconButton44
import com.kirkisham.motif.ui.components.MotifTextField
import com.kirkisham.motif.ui.components.PillButton
import com.kirkisham.motif.ui.components.NOT_SHARED_LINE
import com.kirkisham.motif.ui.components.PlainLine
import com.kirkisham.motif.ui.components.SectionLabel
import com.kirkisham.motif.ui.components.formatClock
import com.kirkisham.motif.ui.components.pluralSongs
import com.kirkisham.motif.ui.motif
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * The alarm's plus button: search Spotify or browse any playlist, tap songs to add them.
 * Stays open so several songs can be added; Done closes it. (Spec §4: the full Search screen is Build 2.)
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SongPickerSheet(app: MotifApp, chosen: List<AlarmSong>, onAdd: (AlarmSong) -> Unit, onDismiss: () -> Unit) {
    val playlists by app.library.playlists.collectAsStateWithLifecycle()
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<SpTrack>?>(null) }
    var browsing by remember { mutableStateOf<SpPlaylist?>(null) }
    val noItems = remember { MutableStateFlow<List<SpPlaylistItem>?>(null) }
    val browseItems by (browsing?.let { app.library.tracksFlow(it.id) } ?: noItems).collectAsStateWithLifecycle()
    val chosenUris = chosen.map { it.uri }.toSet()

    LaunchedEffect(query) {
        if (query.isBlank()) { results = null; return@LaunchedEffect }
        delay(350)
        results = app.api.searchTracks(query)
    }
    LaunchedEffect(browsing?.id) { browsing?.let { app.library.refreshTracks(it.id) } }
    LaunchedEffect(Unit) { if (playlists == null) app.library.refreshPlaylists() }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = motif.card, contentColor = motif.text, dragHandle = null,
        shape = RoundedCornerShape(topStart = 14.dp, topEnd = 14.dp),
    ) {
        Column(Modifier.fillMaxWidth().fillMaxHeight(0.92f).padding(horizontal = 16.dp).navigationBarsPadding()) {
            Row(Modifier.fillMaxWidth().height(56.dp), verticalAlignment = Alignment.CenterVertically) {
                if (browsing != null) IconButton44(Icons.AutoMirrored.Outlined.ArrowBack, "Back", { browsing = null }) else Spacer(Modifier.size(44.dp))
                Text(browsing?.name ?: "Add songs", style = MaterialTheme.typography.titleMedium, color = motif.text, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                PillButton("Done", onClick = onDismiss)
            }
            if (browsing == null) {
                MotifTextField(query, { query = it }, placeholder = "Search songs", modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp))
            }
            LazyColumn(Modifier.fillMaxWidth().weight(1f).padding(horizontal = 4.dp)) {
                val b = browsing
                if (b != null) {
                    val tracks = browseItems?.mapNotNull { it.item } ?: emptyList()
                    if (browseItems == null) item { PlainLine("Loading songs") }
                    else if (tracks.isEmpty() && !b.hasItems) item { PlainLine(NOT_SHARED_LINE) }
                    items(tracks, key = { it.uri + it.id }) { t -> TrackPick(t, t.uri in chosenUris) { onAdd(t.toAlarmSong()) } }
                } else if (query.isNotBlank()) {
                    val r = results
                    if (r == null) item { PlainLine("Searching") }
                    else if (r.isEmpty()) item { PlainLine("No songs found.") }
                    else items(r, key = { it.uri }) { t -> TrackPick(t, t.uri in chosenUris) { onAdd(t.toAlarmSong()) } }
                } else {
                    item { SectionLabel("Or browse a playlist", Modifier.padding(top = 8.dp, bottom = 6.dp)) }
                    items(playlists.orEmpty(), key = { it.id }) { p ->
                        Row(
                            Modifier.fillMaxWidth().height(56.dp).clickable { browsing = p },
                            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp),
                        ) {
                            Cover(p.smallCoverUrl, 36.dp, radius = 3.dp)
                            Column(Modifier.weight(1f)) {
                                Text(p.name, style = MaterialTheme.typography.titleMedium, color = motif.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                p.itemCount?.let { Text(pluralSongs(it), style = MaterialTheme.typography.bodySmall, color = motif.textMuted) }
                            }
                        }
                        Hairline()
                    }
                }
                item { Spacer(Modifier.height(24.dp)) }
            }
        }
    }
}

@Composable
private fun TrackPick(t: SpTrack, added: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().height(56.dp).clickable(enabled = !added, onClick = onClick),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Cover(t.smallCoverUrl, 36.dp, radius = 3.dp)
        Column(Modifier.weight(1f)) {
            Text(t.name, style = MaterialTheme.typography.titleMedium, color = if (added) motif.accent else motif.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("${t.artistNames} · ${formatClock(t.duration_ms)}", style = MaterialTheme.typography.bodySmall, color = motif.textMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (added) Icon(Icons.Outlined.Check, "Added", tint = motif.accent, modifier = Modifier.size(18.dp))
    }
    Hairline()
}

fun SpTrack.toAlarmSong() = AlarmSong(uri = uri, title = name, artist = artistNames, durationMs = duration_ms, imageUrl = smallCoverUrl)
