package com.kirkisham.motif.ui.info

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
import com.kirkisham.motif.MotifApp
import com.kirkisham.motif.data.SpArtist
import com.kirkisham.motif.data.SpTrack
import com.kirkisham.motif.ui.components.Cover
import com.kirkisham.motif.ui.components.IconButton44
import com.kirkisham.motif.ui.components.PlainLine
import com.kirkisham.motif.ui.motif

/** What the info screen was opened for: a track (art tap on Now playing) or an artist (Go to artist). */
enum class InfoKind { TRACK, ARTIST }

/**
 * Song and artist info (board SongInfo). 0.6 ships the header only; the Credits, Songs like this,
 * Artist and Near you sections arrive in 0.7.
 */
@Composable
fun InfoScreen(app: MotifApp, kind: InfoKind, id: String, onBack: () -> Unit) {
    var track by remember { mutableStateOf<SpTrack?>(null) }
    var artist by remember { mutableStateOf<SpArtist?>(null) }
    var failed by remember { mutableStateOf(false) }

    LaunchedEffect(kind, id) {
        when (kind) {
            InfoKind.TRACK -> {
                val t = app.api.track(id)
                track = t
                failed = t == null
                t?.artists?.firstOrNull { it.id != null }?.id?.let { artist = app.api.artist(it) }
            }
            InfoKind.ARTIST -> {
                val a = app.api.artist(id)
                artist = a
                failed = a == null
            }
        }
    }

    val title = track?.name ?: artist?.name ?: ""
    val subtitle = when {
        track != null -> listOfNotNull(track?.artistNames?.takeIf { it.isNotBlank() }, track?.album?.year).joinToString(" · ")
        artist != null -> artist?.genres?.take(2)?.joinToString(", ").orEmpty()
        else -> ""
    }
    val art = track?.smallCoverUrl ?: artist?.smallImageUrl

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
            Spacer(Modifier.height(16.dp))
            if (failed) PlainLine("Spotify did not answer.", Modifier.padding(start = 8.dp))
        }
    }
}
