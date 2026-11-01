package com.kirkisham.motif.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kirkisham.motif.MotifApp
import kotlinx.coroutines.launch

/** What the plus button is adding: one track by URI, with a title for the sheet header. */
data class AddTarget(val uri: String, val title: String)

/**
 * The plus button's two sheets (board Menus): short press lists the 3 playlists most recently
 * added to, then all others; long press asks for a new playlist name and adds the track to it.
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
    val recentIds by app.prefs.recentAddedTo.collectAsStateWithLifecycle(emptyList())
    val scope = rememberCoroutineScope()

    if (target != null) {
        val all = playlists.orEmpty()
        val byId = all.associateBy { it.id }
        val recent = recentIds.mapNotNull { byId[it] }.take(3)
        val recentSet = recent.map { it.id }.toSet()
        AddToPlaylistSheet(
            songTitle = target.title,
            recent = recent,
            others = all.filterNot { it.id in recentSet },
            onPick = { p ->
                scope.launch {
                    val ok = app.library.addTrack(p.id, target.uri)
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
                    val ok = app.library.addTrack(p.id, newPlaylistFor.uri)
                    if (ok) app.prefs.markAddedTo(p.id)
                    onDone(if (ok) "Added to $name" else "Created $name, but the song did not add")
                }
            },
            onDismiss = onDismiss,
        )
    }
}
