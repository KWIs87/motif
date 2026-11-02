package com.kirkisham.motif.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kirkisham.motif.MotifApp
import kotlinx.coroutines.launch

/** What the plus button is adding: one or more track URIs, with a title for the sheet header. */
data class AddTarget(val uris: List<String>, val title: String) {
    constructor(uri: String, title: String) : this(listOf(uri), title)
}

/**
 * The plus button's two sheets (board Menus): short press lists every playlist, the ones most
 * recently added to from Motif first and the rest in Spotify's order, with a filter box on top;
 * long press asks for a new playlist name and adds the track to it.
 */
@Composable
fun AddToHost(
    app: MotifApp,
    target: AddTarget?,
    newPlaylistFor: AddTarget?,
    onDismiss: () -> Unit,
    onDone: (String) -> Unit = {},
) {
    val playlists by app.library.playlists.collectAsStateWithLifecycle()
    val addedAt by app.prefs.addedToAt.collectAsStateWithLifecycle(emptyMap())
    val scope = rememberCoroutineScope()

    if (target != null) {
        val all = playlists.orEmpty()
        // Stable sort: recency descending, never-added playlists keep Spotify's order after them.
        val ordered = all.sortedByDescending { addedAt[it.id] ?: 0L }
        AddToPlaylistSheet(
            songTitle = target.title,
            playlists = ordered,
            onPick = { p ->
                scope.launch {
                    val ok = app.library.addTracks(p.id, target.uris)
                    if (ok) { app.prefs.markAddedTo(p.id); onDone("Added to ${p.name}") } else onDone("Could not add to ${p.name}")
                }
            },
            onDismiss = onDismiss,
        )
    }
    if (newPlaylistFor != null) {
        NameSheet(
            label = "New playlist",
            initial = "",
            onSave = { name ->
                scope.launch {
                    val p = app.library.create(name)
                    if (p == null) { onDone("Could not create the playlist"); return@launch }
                    val ok = app.library.addTracks(p.id, newPlaylistFor.uris)
                    if (ok) app.prefs.markAddedTo(p.id)
                    onDone(if (ok) "Added to $name" else "Created $name, but the songs did not add")
                }
            },
            onDismiss = onDismiss,
        )
    }
}
