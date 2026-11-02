package com.kirkisham.motif.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.kirkisham.motif.data.UndoManager
import com.kirkisham.motif.ui.motif

/** Where the pill is on screen, so a press on it is not taken as "touch elsewhere". */
private val pillBounds = mutableStateOf<Rect?>(null)

/**
 * "Removed · Undo" (0.10, item 1): a small pill above the ribbon. Tap it to undo; it leaves on its
 * own after three seconds or at the first touch anywhere else (see [dismissUndoOnTouch]).
 */
@Composable
fun UndoPill(undo: UndoManager, entry: UndoManager.Entry?) {
    if (entry == null) { pillBounds.value = null; return }
    val shape = RoundedCornerShape(50)
    Row(
        Modifier
            .padding(bottom = 10.dp)
            .height(36.dp)
            .clip(shape)
            .background(motif.text)
            .clickable { undo.undo() }
            .onGloballyPositioned { pillBounds.value = it.boundsInRoot() }
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(entry.label, style = MaterialTheme.typography.labelLarge, color = motif.ground)
        Text(" · ", style = MaterialTheme.typography.labelLarge, color = motif.ground.copy(alpha = 0.6f))
        Text("Undo", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Medium, color = motif.ground)
    }
}

/**
 * Put on the root: any press outside the pill (tap, scroll start, swipe start) dismisses the
 * pending undo, without consuming the event, so the touch still does what it was meant to do.
 */
fun Modifier.dismissUndoOnTouch(undo: UndoManager): Modifier = pointerInput(undo) {
    awaitPointerEventScope {
        while (true) {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            if (event.type == PointerEventType.Press && undo.current.value != null) {
                val pos = event.changes.firstOrNull()?.position
                val inside = pos != null && pillBounds.value?.contains(pos) == true
                if (!inside) undo.dismiss()
            }
        }
    }
}
