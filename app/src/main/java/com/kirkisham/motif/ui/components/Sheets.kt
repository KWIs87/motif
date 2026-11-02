package com.kirkisham.motif.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.kirkisham.motif.data.SpPlaylist
import com.kirkisham.motif.ui.motif

/** One menu entry in the long-press sheets (board Menus). */
data class MenuItem(
    val label: String,
    val icon: ImageVector? = null,
    val tint: Color? = null,
    val onClick: () -> Unit,
)

/** Bottom sheet styled like the board's menu card: title row, hairline, 48 dp entries. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MenuSheet(title: String, items: List<MenuItem>, onDismiss: () -> Unit, subtitle: String? = null) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = motif.card,
        contentColor = motif.text,
        dragHandle = null,
        shape = RoundedCornerShape(topStart = 14.dp, topEnd = 14.dp),
    ) {
        Column(Modifier.fillMaxWidth().padding(vertical = 8.dp).navigationBarsPadding()) {
            Column(Modifier.padding(horizontal = 20.dp, vertical = 10.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium, color = motif.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = motif.textMuted)
            }
            Hairline(Modifier.padding(horizontal = 20.dp))
            items.forEach { item ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                        .clickable { item.onClick(); onDismiss() }
                        .padding(horizontal = 20.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    if (item.icon != null) Icon(item.icon, null, tint = item.tint ?: motif.textMuted, modifier = Modifier.size(18.dp))
                    else Spacer(Modifier.width(18.dp))
                    Text(item.label, style = MaterialTheme.typography.bodyLarge, color = item.tint ?: motif.text)
                }
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

/**
 * "Add 'song' to" sheet (board Menus, short press plus): a filter box, then every playlist, the
 * ones most recently added to from Motif first. Colors are set explicitly on every element so the
 * sheet's own window cannot fall back to Material defaults; the title clears the status bar.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddToPlaylistSheet(
    songTitle: String,
    playlists: List<SpPlaylist>,
    onPick: (SpPlaylist) -> Unit,
    onDismiss: () -> Unit,
) {
    var filter by remember { mutableStateOf("") }
    val shown = remember(playlists, filter) {
        val q = filter.trim()
        if (q.isEmpty()) playlists else playlists.filter { it.name.contains(q, ignoreCase = true) }
    }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = motif.card,
        contentColor = motif.text,
        dragHandle = null,
        shape = RoundedCornerShape(topStart = 14.dp, topEnd = 14.dp),
    ) {
        CompositionLocalProvider(LocalContentColor provides motif.text) {
            androidx.compose.foundation.lazy.LazyColumn(Modifier.fillMaxWidth().statusBarsPadding().padding(vertical = 8.dp).navigationBarsPadding()) {
                item {
                    Text(
                        "Add \u201c$songTitle\u201d to",
                        style = MaterialTheme.typography.bodyMedium, color = motif.textMuted,
                        modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 14.dp, bottom = 10.dp), maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                    PillSearchBox(
                        value = filter,
                        onChange = { filter = it },
                        placeholder = "Find a playlist",
                        imeAction = ImeAction.Done,
                        modifier = Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, bottom = 10.dp),
                    )
                    Hairline(Modifier.padding(horizontal = 20.dp))
                }
                if (shown.isEmpty()) item {
                    Text(
                        if (playlists.isEmpty()) "No playlists yet" else "No playlist matches",
                        style = MaterialTheme.typography.bodySmall, color = motif.textMuted,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp),
                    )
                }
                items(shown.size, key = { shown[it].id }) { i -> PlaylistPickRow(shown[i]) { onPick(shown[i]); onDismiss() } }
                item { Spacer(Modifier.height(8.dp)) }
            }
        }
    }
}

@Composable
private fun PlaylistPickRow(p: SpPlaylist, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().height(56.dp).clickable(onClick = onClick).padding(horizontal = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Cover(p.smallCoverUrl, 36.dp, radius = 3.dp)
        Text(p.name, style = MaterialTheme.typography.titleMedium, color = motif.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** Name field + Save (board Menus, long press plus; also used for Rename). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NameSheet(label: String, initial: String, onSave: (String) -> Unit, onDismiss: () -> Unit, saveLabel: String = "Save") {
    var name by remember { mutableStateOf(initial) }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = motif.card,
        contentColor = motif.text,
        dragHandle = null,
        shape = RoundedCornerShape(topStart = 14.dp, topEnd = 14.dp),
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 16.dp).navigationBarsPadding(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(label, style = MaterialTheme.typography.bodyMedium, color = motif.textMuted)
            MotifTextField(name, { name = it }, placeholder = "Name", onDone = { if (name.isNotBlank()) { onSave(name.trim()); onDismiss() } })
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                PillButton(saveLabel, enabled = name.isNotBlank()) { onSave(name.trim()); onDismiss() }
            }
        }
    }
}

/** Plain confirm as a sheet rather than a dialog: one line and two choices. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConfirmSheet(text: String, confirmLabel: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = motif.card,
        contentColor = motif.text,
        dragHandle = null,
        shape = RoundedCornerShape(topStart = 14.dp, topEnd = 14.dp),
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 16.dp).navigationBarsPadding(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(text, style = MaterialTheme.typography.bodyLarge, color = motif.text)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                PillButton("Cancel", filled = false) { onDismiss() }
                PillButton(confirmLabel) { onConfirm(); onDismiss() }
            }
        }
    }
}

/** 44 dp pill: filled (text color on ground) or outlined. */
@Composable
fun PillButton(label: String, modifier: Modifier = Modifier, filled: Boolean = true, enabled: Boolean = true, accent: Boolean = false, onClick: () -> Unit) {
    val shape = RoundedCornerShape(50)
    val bg = when { !filled -> Color.Transparent; accent -> motif.accent; else -> motif.text }
    val fg = when { !filled && accent -> motif.accent; !filled -> motif.textMuted; else -> motif.ground }
    Box(
        modifier
            .height(44.dp)
            .clip(shape)
            .background(if (enabled) bg else bg.copy(alpha = 0.4f))
            .then(if (!filled) Modifier.border(1.dp, if (accent) motif.accent else motif.chipLine, shape) else Modifier)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Medium, color = fg)
    }
}

/** Single-line text field in the board's style: 44 dp, 1 px border, dark fill. */
@Composable
fun MotifTextField(
    value: String,
    onChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String? = null,
    onDone: (() -> Unit)? = null,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        modifier = modifier.fillMaxWidth(),
        singleLine = true,
        placeholder = placeholder?.let { { Text(it, color = motif.textDim) } },
        shape = RoundedCornerShape(10.dp),
        textStyle = MaterialTheme.typography.bodyLarge,
        keyboardOptions = keyboardOptions.copy(imeAction = if (onDone != null) ImeAction.Done else keyboardOptions.imeAction),
        keyboardActions = KeyboardActions(onDone = { onDone?.invoke() }),
        colors = OutlinedTextFieldDefaults.colors(
            focusedTextColor = motif.text,
            unfocusedTextColor = motif.text,
            cursorColor = motif.accent,
            focusedBorderColor = motif.chipLine,
            unfocusedBorderColor = motif.chipLine,
            focusedContainerColor = motif.ground,
            unfocusedContainerColor = motif.ground,
        ),
    )
}
