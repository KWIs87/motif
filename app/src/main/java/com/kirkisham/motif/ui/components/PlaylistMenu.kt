package com.kirkisham.motif.ui.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.DriveFileRenameOutline
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.outlined.Shuffle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kirkisham.motif.MotifApp
import com.kirkisham.motif.data.SpPlaylist
import com.kirkisham.motif.ui.motif
import kotlinx.coroutines.launch

/**
 * Long-press menu on a playlist (spec §3.3/3.4): Pin/Unpin, Play, Shuffle, Rename, Delete (confirms).
 * Hosts its own Rename and Delete sheets. Render with target = null to show nothing.
 */
@Composable
fun PlaylistMenuHost(app: MotifApp, target: SpPlaylist?, onDismiss: () -> Unit, onDeleted: (SpPlaylist) -> Unit = {}) {
    val pins by app.prefs.pins.collectAsStateWithLifecycle(emptyList())
    val scope = rememberCoroutineScope()
    var renaming by remember { mutableStateOf<SpPlaylist?>(null) }
    var deleting by remember { mutableStateOf<SpPlaylist?>(null) }

    if (target != null) {
        val pinned = target.id in pins
        MenuSheet(
            title = target.name,
            items = listOf(
                MenuItem(if (pinned) "Unpin" else "Pin to top", Icons.Outlined.PushPin, motif.accent) { scope.launch { app.prefs.togglePin(target.id) } },
                MenuItem("Play", Icons.Outlined.PlayArrow) { app.player.playPlaylist(target) },
                MenuItem("Shuffle", Icons.Outlined.Shuffle) { app.player.playPlaylist(target, shuffle = true) },
                MenuItem("Rename", Icons.Outlined.DriveFileRenameOutline) { renaming = target },
                MenuItem("Delete", Icons.Outlined.Delete, Color(0xFFC96E6E)) { deleting = target },
            ),
            onDismiss = onDismiss,
        )
    }
    renaming?.let { p ->
        NameSheet("Rename playlist", p.name, onSave = { name -> scope.launch { app.library.rename(p.id, name) } }, onDismiss = { renaming = null })
    }
    deleting?.let { p ->
        ConfirmSheet(
            text = "Remove “${p.name}” from your library?",
            confirmLabel = "Delete",
            onConfirm = {
                scope.launch {
                    if (app.library.unfollow(p.id)) { app.prefs.forgetPlaylist(p.id); onDeleted(p) }
                }
            },
            onDismiss = { deleting = null },
        )
    }
}
