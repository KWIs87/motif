package com.kirkisham.motif.ui.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.PlaylistAdd
import androidx.compose.material.icons.outlined.QueueMusic
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import com.kirkisham.motif.MotifApp
import com.kirkisham.motif.data.SpArtist
import com.kirkisham.motif.data.SpTrack

/**
 * Long-press menu on a song (spec §3.5): Play next, Add to playlist, Go to artist, and, when the
 * caller passes [onRemove], Remove from this playlist. Shared by Playlist and Search.
 * Go to artist opens the first credited artist; it is hidden for local files without an artist id.
 */
@Composable
fun SongMenuSheet(
    app: MotifApp,
    track: SpTrack,
    onAddTo: (AddTarget) -> Unit,
    onGoToArtist: (SpArtist) -> Unit,
    onDismiss: () -> Unit,
    onRemove: (() -> Unit)? = null,
) {
    val artist = track.artists.firstOrNull { it.id != null }
    val items = buildList {
        add(MenuItem("Play next", Icons.Outlined.QueueMusic) { app.player.queue(track.uri) })
        add(MenuItem("Add to playlist", Icons.Outlined.PlaylistAdd) { onAddTo(AddTarget(track.uri, track.name)) })
        if (artist != null) add(MenuItem("Go to artist", Icons.Outlined.Person) { onGoToArtist(artist) })
        if (onRemove != null) add(MenuItem("Remove from this playlist", Icons.Outlined.Delete, Color(0xFFC96E6E)) { onRemove() })
    }
    MenuSheet(title = track.name, subtitle = track.artistNames, items = items, onDismiss = onDismiss)
}
