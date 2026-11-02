package com.kirkisham.motif.ui.playlist

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.DragHandle
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kirkisham.motif.MotifApp
import com.kirkisham.motif.data.Library
import com.kirkisham.motif.data.SpPlaylistItem
import com.kirkisham.motif.ui.components.AddTarget
import com.kirkisham.motif.ui.components.AddToHost
import com.kirkisham.motif.ui.components.Cover
import com.kirkisham.motif.ui.components.Hairline
import com.kirkisham.motif.ui.components.PlainLine
import com.kirkisham.motif.ui.components.formatClock
import com.kirkisham.motif.ui.motif
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

private val Red = Color(0xFFE07B7B)
private val RedLine = Color(0xFF5A3030)

/**
 * Mass edit (board Playlist mass edit): checkboxes to select, drag handles to reorder, a bottom
 * bar with the count, Add to playlist and Remove. Reorders and removes are kept locally and
 * written to Spotify in order when Done is tapped; Cancel discards them. Adding the selection
 * to another playlist happens at once through the usual add sheet.
 */
@Composable
fun MassEditScreen(app: MotifApp, playlistId: String, onClose: () -> Unit) {
    val source by app.library.tracksFlow(playlistId).collectAsStateWithLifecycle()
    val scope = androidx.compose.runtime.rememberCoroutineScope()

    var order by remember { mutableStateOf<List<SpPlaylistItem>?>(null) }
    val ops = remember { mutableListOf<Library.EditOp>() }
    var selected by remember { mutableStateOf<Set<String>>(emptySet()) }   // row keys
    var addTarget by remember { mutableStateOf<AddTarget?>(null) }
    var saving by remember { mutableStateOf(false) }
    var line by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(playlistId) { if (source == null) app.library.refreshTracks(playlistId) }
    // Take the list once; later cache changes (another screen) do not disturb the edit in progress.
    LaunchedEffect(source) { if (order == null && source != null) order = source }

    val rows = order ?: emptyList()
    fun keyOf(i: Int, item: SpPlaylistItem) = "${item.item?.uri}#${item.added_at}#$i"

    // Drag by handle: same mechanics as the Playlist screen's long-press drag.
    val density = LocalDensity.current
    val rowPx = with(density) { 60.dp.toPx() }
    var dragIndex by remember { mutableIntStateOf(-1) }
    var dragShift by remember { mutableIntStateOf(0) }
    var dragOffset by remember { mutableFloatStateOf(0f) }

    fun finishDrag() {
        val from = dragIndex
        val to = (from + dragShift).coerceIn(0, rows.lastIndex.coerceAtLeast(0))
        dragIndex = -1; dragShift = 0; dragOffset = 0f
        if (from < 0 || from == to || rows.isEmpty()) return
        val keysBefore = rows.mapIndexed { i, it -> keyOf(i, it) }
        val selectedItems = rows.filterIndexed { i, it -> keysBefore[i] in selected }.toSet()
        val cur = rows.toMutableList()
        val item = cur.removeAt(from)
        cur.add(to, item)
        order = cur
        ops += Library.EditOp.Move(from, to)
        // Keys carry the index, so re-point the selection at the moved rows.
        selected = cur.mapIndexedNotNull { i, it -> if (it in selectedItems) keyOf(i, it) else null }.toSet()
    }

    fun removeSelected() {
        val uris = rows.filterIndexed { i, it -> keyOf(i, it) in selected }.mapNotNull { it.item?.uri }.distinct()
        if (uris.isEmpty()) return
        val set = uris.toSet()
        // Spotify removes by URI, so every copy of a selected song goes, selected or not.
        order = rows.filterNot { it.item?.uri in set }
        ops += Library.EditOp.Remove(uris)
        selected = emptySet()
    }

    fun done() {
        if (ops.isEmpty()) { onClose(); return }
        saving = true
        val snapshot = ops.toList()
        app.appScope.launch {
            val ok = app.library.applyEdits(playlistId, snapshot)
            if (!ok) line = "Spotify did not save every change. The list was reloaded."
            saving = false
            if (ok) onClose()
        }
    }

    val selectedCount = selected.size
    Box(Modifier.fillMaxSize()) {
        LazyColumn(
            Modifier.fillMaxSize().statusBarsPadding(),
            contentPadding = PaddingValues(start = 16.dp, end = 24.dp, bottom = 110.dp),
            userScrollEnabled = dragIndex < 0,
        ) {
            item {
                Row(Modifier.fillMaxWidth().height(52.dp), verticalAlignment = Alignment.CenterVertically) {
                    TextButton44("Cancel", muted = true, enabled = !saving, onClick = onClose)
                    Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                        Text("Edit playlist", style = MaterialTheme.typography.titleMedium, color = motif.text)
                    }
                    TextButton44(if (saving) "Saving" else "Done", muted = false, enabled = !saving, onClick = ::done)
                }
                Spacer(Modifier.height(8.dp))
                line?.let { PlainLine(it, Modifier.padding(start = 8.dp)) }
                if (order == null) PlainLine("Loading songs", Modifier.padding(start = 8.dp))
                else if (rows.isEmpty()) PlainLine("No songs in this playlist.", Modifier.padding(start = 8.dp))
            }
            itemsIndexed(rows, key = { i, it -> keyOf(i, it) }) { index, item ->
                val track = item.item ?: return@itemsIndexed
                val key = keyOf(index, item)
                val isSelected = key in selected
                val isDragging = dragIndex == index
                val shiftPx = when {
                    dragIndex < 0 || isDragging -> 0f
                    dragShift > 0 && index > dragIndex && index <= dragIndex + dragShift -> -rowPx
                    dragShift < 0 && index < dragIndex && index >= dragIndex + dragShift -> rowPx
                    else -> 0f
                }
                Column(
                    Modifier
                        .fillMaxWidth()
                        .zIndex(if (isDragging) 1f else 0f)
                        .graphicsLayer { translationY = if (isDragging) dragOffset else shiftPx }
                        .then(if (isDragging || isSelected) Modifier.clip(RoundedCornerShape(6.dp)).background(motif.surface) else Modifier),
                ) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .height(60.dp)
                            .clickable(enabled = !saving) { selected = if (isSelected) selected - key else selected + key }
                            .padding(start = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        CheckBox20(isSelected)
                        Cover(track.smallCoverUrl, 36.dp, radius = 3.dp)
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Text(track.name, style = MaterialTheme.typography.labelLarge, color = motif.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text("${track.artistNames} · ${formatClock(track.duration_ms)}", style = MaterialTheme.typography.bodySmall, color = motif.textMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        Box(
                            Modifier
                                .size(44.dp)
                                .pointerInput(index, rows.size) {
                                    detectDragGestures(
                                        onDragStart = { dragIndex = index; dragShift = 0; dragOffset = 0f },
                                        onDrag = { change, delta ->
                                            change.consume()
                                            dragOffset += delta.y
                                            dragShift = (dragOffset / rowPx).roundToInt().coerceIn(-index, rows.lastIndex - index)
                                        },
                                        onDragEnd = { finishDrag() },
                                        onDragCancel = { finishDrag() },
                                    )
                                },
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(Icons.Outlined.DragHandle, "Drag to reorder", tint = if (isDragging || isSelected) motif.text else motif.textMuted, modifier = Modifier.size(18.dp))
                        }
                    }
                    Hairline()
                }
            }
        }

        // Bottom bar: count, Add to playlist, Remove.
        Row(
            Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(horizontal = 12.dp, vertical = 16.dp)
                .fillMaxWidth()
                .height(64.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(motif.card)
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                if (selectedCount == 0) "Select songs" else "$selectedCount selected",
                style = MaterialTheme.typography.bodyMedium, color = motif.textMuted, modifier = Modifier.weight(1f), maxLines = 1,
            )
            BarButton("Add to", enabled = selectedCount > 0 && !saving, color = motif.text, lineColor = motif.chipLine) {
                val uris = rows.filterIndexed { i, it -> keyOf(i, it) in selected }.mapNotNull { it.item?.uri }.distinct()
                addTarget = AddTarget(uris, if (uris.size == 1) rows.first { it.item?.uri == uris[0] }.item?.name ?: "1 song" else "${uris.size} songs")
            }
            BarButton("Remove", enabled = selectedCount > 0 && !saving, color = Red, lineColor = RedLine, onClick = ::removeSelected)
        }
    }

    AddToHost(app, addTarget, null, onDismiss = { addTarget = null }, onDone = { line = it })
}

@Composable
private fun TextButton44(label: String, muted: Boolean, enabled: Boolean, onClick: () -> Unit) {
    Box(Modifier.height(44.dp).clip(RoundedCornerShape(8.dp)).clickable(enabled = enabled, onClick = onClick).padding(horizontal = 12.dp), contentAlignment = Alignment.Center) {
        Text(
            label,
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = if (muted) FontWeight.Normal else FontWeight.Medium,
            color = (if (muted) motif.textMuted else motif.text).copy(alpha = if (enabled) 1f else 0.5f),
        )
    }
}

/** 20 dp square: hairline when off, accent fill with a check when on. */
@Composable
private fun CheckBox20(checked: Boolean) {
    val shape = RoundedCornerShape(4.dp)
    Box(
        Modifier.size(20.dp).clip(shape).background(if (checked) motif.accent else Color.Transparent).border(1.5.dp, if (checked) motif.accent else motif.chipLine, shape),
        contentAlignment = Alignment.Center,
    ) {
        if (checked) Icon(Icons.Outlined.Check, null, tint = motif.ground, modifier = Modifier.size(14.dp))
    }
}

@Composable
private fun BarButton(label: String, enabled: Boolean, color: Color, lineColor: Color, onClick: () -> Unit) {
    val shape = RoundedCornerShape(50)
    val alpha = if (enabled) 1f else 0.4f
    Box(
        Modifier.height(40.dp).clip(shape).border(1.dp, lineColor.copy(alpha = alpha), shape).clickable(enabled = enabled, onClick = onClick).padding(horizontal = 16.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Medium, color = color.copy(alpha = alpha), maxLines = 1)
    }
}
