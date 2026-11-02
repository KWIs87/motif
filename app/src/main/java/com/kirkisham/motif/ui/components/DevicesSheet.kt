package com.kirkisham.motif.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Cast
import androidx.compose.material.icons.outlined.Computer
import androidx.compose.material.icons.outlined.Smartphone
import androidx.compose.material.icons.outlined.Speaker
import androidx.compose.material.icons.outlined.Tv
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kirkisham.motif.MotifApp
import com.kirkisham.motif.data.SpDevice
import com.kirkisham.motif.ui.motif
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Spotify Connect picker (0.10, item 5): every device with a type icon and its name, the active
 * one in the accent. Tap transfers playback there. Refreshes every 5 s while open.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DevicesSheet(app: MotifApp, onDismiss: () -> Unit) {
    val devices by app.devices.devices.collectAsStateWithLifecycle()
    val line by app.devices.line.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        while (true) { app.devices.refresh(); delay(5_000) }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = motif.card,
        contentColor = motif.text,
        dragHandle = null,
        shape = RoundedCornerShape(topStart = 14.dp, topEnd = 14.dp),
    ) {
        CompositionLocalProvider(LocalContentColor provides motif.text) {
            Column(Modifier.fillMaxWidth().padding(vertical = 8.dp).navigationBarsPadding()) {
                Text(
                    "Play on",
                    style = MaterialTheme.typography.bodyMedium, color = motif.textMuted,
                    modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 14.dp, bottom = 10.dp),
                )
                Hairline(Modifier.padding(horizontal = 20.dp))
                val list = devices
                when {
                    list == null && line == null -> PlainLine("Looking for devices", Modifier.padding(horizontal = 20.dp))
                    list.isNullOrEmpty() -> PlainLine(line ?: "No devices. Open Spotify on the device you want.", Modifier.padding(horizontal = 20.dp))
                    else -> list.forEach { d ->
                        DeviceRow(d, onClick = {
                            scope.launch { if (app.devices.transfer(d)) { delay(800); app.devices.refresh() } }
                        })
                    }
                }
                if (!list.isNullOrEmpty()) line?.let { PlainLine(it, Modifier.padding(horizontal = 20.dp)) }
                Spacer(Modifier.height(8.dp))
            }
        }
    }
}

@Composable
private fun DeviceRow(d: SpDevice, onClick: () -> Unit) {
    val color = if (d.is_active) motif.accent else motif.text
    Row(
        Modifier.fillMaxWidth().height(52.dp).clickable(enabled = d.id != null && !d.is_restricted, onClick = onClick).padding(horizontal = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Icon(deviceIcon(d.type), null, tint = if (d.is_active) motif.accent else motif.textMuted, modifier = Modifier.size(20.dp))
        Text(d.name.ifBlank { d.type }, style = MaterialTheme.typography.bodyLarge, color = color, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        if (d.is_active) Text("Playing", style = MaterialTheme.typography.bodySmall, color = motif.accent)
    }
}

/** Thin outline icon per Spotify device type. */
fun deviceIcon(type: String): ImageVector = when (type.lowercase()) {
    "smartphone", "tablet" -> Icons.Outlined.Smartphone
    "computer" -> Icons.Outlined.Computer
    "tv", "stb", "gameconsole" -> Icons.Outlined.Tv
    "castvideo", "castaudio", "avr" -> Icons.Outlined.Cast
    else -> Icons.Outlined.Speaker
}
