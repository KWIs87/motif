package com.kirkisham.motif.ui.nowplaying

import android.content.Intent
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.IosShare
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.MusicNote
import androidx.compose.material.icons.outlined.Replay
import androidx.compose.material.icons.outlined.Speaker
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kirkisham.motif.MotifApp
import com.kirkisham.motif.data.LyricLine
import com.kirkisham.motif.data.SpotifyRemote
import com.kirkisham.motif.ui.components.AddTarget
import com.kirkisham.motif.ui.components.AddToHost
import com.kirkisham.motif.ui.components.Chip
import com.kirkisham.motif.ui.components.IconButton44
import com.kirkisham.motif.ui.components.MotifTextField
import com.kirkisham.motif.ui.components.PillButton
import com.kirkisham.motif.ui.components.PlainLine
import com.kirkisham.motif.ui.components.SectionLabel
import com.kirkisham.motif.ui.components.combinedClickableCompat
import com.kirkisham.motif.ui.components.formatClock
import com.kirkisham.motif.ui.motif
import com.spotify.protocol.types.Image
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private val SPEEDS = listOf(1f, 1.25f, 1.5f, 2f)

/**
 * Now playing (boards NowPlaying and NowPlayingPodcast). One screen, two layouts chosen by the
 * current item: songs get previous/next, plus and Lyrics; episodes get 15 s skips, speed and sleep.
 */
@Composable
fun NowPlayingScreen(app: MotifApp, onBack: () -> Unit, onOpenInfo: (String) -> Unit, onDevices: () -> Unit) {
    val playback by app.remote.playback.collectAsStateWithLifecycle()
    val contextTitle by app.remote.contextTitle.collectAsStateWithLifecycle()
    val notice by app.player.notice.collectAsStateWithLifecycle()
    val devices by app.devices.devices.collectAsStateWithLifecycle()
    val p = playback

    // Connect (0.10, item 5): know where playback is so the header can name a device that is not this phone.
    LaunchedEffect(Unit) { while (true) { app.devices.refresh(); delay(15_000) } }
    val elsewhere = remember(devices) { app.devices.activeElsewhere() }

    if (p == null) {
        Column(Modifier.fillMaxSize().statusBarsPadding().padding(horizontal = 16.dp)) {
            IconButton44(Icons.Outlined.KeyboardArrowDown, "Back", onBack)
            PlainLine("Nothing is playing. Pick a playlist on Home.", Modifier.padding(horizontal = 12.dp))
        }
        return
    }

    // Position ticker: 4 Hz while playing.
    var positionMs by remember { mutableLongStateOf(p.positionNow()) }
    LaunchedEffect(p.uri, p.isPaused, p.positionMs) {
        while (true) { positionMs = p.positionNow(); delay(250) }
    }

    val art = com.kirkisham.motif.ui.components.rememberArt(app.remote, p.imageUri, Image.Dimension.LARGE)
    val context = LocalContext.current
    val share = {
        val id = p.uri.substringAfterLast(':')
        val kind = if (p.isEpisode) "episode" else "track"
        val text = "${p.title} — ${p.artist}\nhttps://open.spotify.com/$kind/$id"
        val send = Intent(Intent.ACTION_SEND).apply { type = "text/plain"; putExtra(Intent.EXTRA_TEXT, text) }
        context.startActivity(Intent.createChooser(send, null))
    }

    Column(
        Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(start = 28.dp, end = 28.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        val speaker: @Composable () -> Unit = { SpeakerAction(elsewhere?.name, onDevices) }
        if (p.isEpisode) PodcastBody(app, p, positionMs, contextTitle, art, onBack, share, speaker)
        else SongBody(app, p, positionMs, contextTitle, art, onBack, share, speaker, onOpenInfo = { onOpenInfo(p.uri.substringAfterLast(':')) })
        notice?.let { PlainLine(it) }
    }
}

@Composable
private fun Header(contextLabel: String?, onBack: () -> Unit, actions: @Composable () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 52.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton44(Icons.Outlined.KeyboardArrowDown, "Back", onBack)
        Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
            if (contextLabel != null) SectionLabel(contextLabel)
        }
        Row(verticalAlignment = Alignment.CenterVertically) { actions() }
    }
}

/** Speaker icon (Connect sheet); when playback is on another device its name sits under the icon in small muted text. */
@Composable
private fun SpeakerAction(elsewhereName: String?, onDevices: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        IconButton44(Icons.Outlined.Speaker, "Play on", onDevices, tint = if (elsewhereName != null) motif.accent else motif.textMuted, size = 20.dp)
        if (elsewhereName != null) {
            Text(
                elsewhereName, fontSize = 9.sp, color = motif.textMuted, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.width(64.dp).padding(top = 0.dp), textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun Scrubber(p: SpotifyRemote.Playback, positionMs: Long, onSeek: (Long) -> Unit, rightLabel: String) {
    var dragging by remember { mutableStateOf(false) }
    var dragValue by remember { mutableFloatStateOf(0f) }
    val dur = p.durationMs.coerceAtLeast(1)
    val value = if (dragging) dragValue else (positionMs.toFloat() / dur).coerceIn(0f, 1f)
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Slider(
            value = value,
            onValueChange = { dragging = true; dragValue = it },
            onValueChangeFinished = { dragging = false; onSeek((dragValue * dur).toLong()) },
            enabled = p.canSeek,
            modifier = Modifier.fillMaxWidth().height(24.dp),
            colors = SliderDefaults.colors(
                thumbColor = motif.text,
                activeTrackColor = motif.text,
                inactiveTrackColor = motif.card,
                disabledThumbColor = motif.textMuted,
                disabledActiveTrackColor = motif.textMuted,
                disabledInactiveTrackColor = motif.card,
            ),
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(formatClock(if (dragging) (dragValue * dur).toLong() else positionMs), fontSize = 11.sp, color = motif.textMuted)
            Text(rightLabel, fontSize = 11.sp, color = motif.textMuted)
        }
    }
}

@Composable
private fun BigPlay(paused: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.size(72.dp).clip(CircleShape).background(motif.text).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(if (paused) Icons.Filled.PlayArrow else Icons.Filled.Pause, if (paused) "Play" else "Pause", tint = motif.ground, modifier = Modifier.size(28.dp))
    }
}

// ---------------------------------------------------------------- Song

@Composable
private fun ColumnScope.SongBody(
    app: MotifApp,
    p: SpotifyRemote.Playback,
    positionMs: Long,
    contextTitle: String?,
    art: android.graphics.Bitmap?,
    onBack: () -> Unit,
    onShare: () -> Unit,
    speaker: @Composable () -> Unit,
    onOpenInfo: () -> Unit,
) {
    var lyrics by remember { mutableStateOf<List<LyricLine>?>(null) }
    var showLyrics by remember { mutableStateOf(false) }
    var addTarget by remember { mutableStateOf<AddTarget?>(null) }
    var newPlaylistFor by remember { mutableStateOf<AddTarget?>(null) }
    var line by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(p.uri) {
        lyrics = null
        showLyrics = false
        lyrics = app.lyrics.forTrack(p.uri, p.title, p.artist, p.album, p.durationMs)
    }

    Header(contextTitle, onBack) {
        if (lyrics != null) IconButton44(Icons.Outlined.MusicNote, "Lyrics", { showLyrics = !showLyrics }, tint = if (showLyrics) motif.accent else motif.textMuted, size = 20.dp)
        speaker()
        IconButton44(Icons.Outlined.IosShare, "Share", onShare, tint = motif.textMuted, size = 20.dp)
    }

    // Tap on the art opens Song and artist info (spec §3.6); while lyrics show, tapping does nothing.
    // Swipe left = next, right = previous (0.10, item 12); the art slides out the way it was pushed.
    val density = LocalDensity.current
    val swipeThreshold = with(density) { 72.dp.toPx() }
    var swipeDx by remember { mutableFloatStateOf(0f) }
    var swipeDir by remember { mutableIntStateOf(0) } // +1 next, -1 previous, 0 none
    Box(
        Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(8.dp)).background(motif.card)
            .clickable(enabled = !showLyrics && p.uri.startsWith("spotify:track:"), onClick = onOpenInfo)
            .pointerInput(p.canSkipNext, p.canSkipPrev) {
                detectHorizontalDragGestures(
                    onDragStart = { swipeDx = 0f },
                    onHorizontalDrag = { _, dx -> swipeDx += dx },
                    onDragEnd = {
                        when {
                            swipeDx < -swipeThreshold && p.canSkipNext -> { swipeDir = 1; app.player.next() }
                            swipeDx > swipeThreshold && p.canSkipPrev -> { swipeDir = -1; app.player.previous() }
                        }
                    },
                )
            },
    ) {
        AnimatedContent(
            targetState = p.uri,
            transitionSpec = {
                val dir = swipeDir
                if (dir == 0) fadeIn(tween(200)) togetherWith fadeOut(tween(200))
                else (slideInHorizontally(tween(260)) { w -> dir * w } + fadeIn(tween(200))) togetherWith
                    (slideOutHorizontally(tween(260)) { w -> -dir * w } + fadeOut(tween(200)))
            },
            label = "art",
        ) { _ ->
            Box(Modifier.fillMaxSize()) {
                val l = lyrics
                if (showLyrics && l != null) LyricsView(l, positionMs, Modifier.fillMaxSize())
                else if (art != null) Image(art.asImageBitmap(), null, Modifier.fillMaxSize())
            }
        }
    }
    LaunchedEffect(p.uri) { delay(400); swipeDir = 0 }

    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(p.title, style = MaterialTheme.typography.titleLarge, color = motif.text, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Text(p.artist, style = MaterialTheme.typography.bodyMedium, color = motif.textMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        line?.let { PlainLine(it) }
    }

    Scrubber(p, positionMs, onSeek = { app.player.seekTo(it) }, rightLabel = "-" + formatClock(p.durationMs - positionMs))

    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(56.dp).clip(CircleShape).clickable(enabled = p.canSkipPrev) { app.player.previous() }, contentAlignment = Alignment.Center) {
            Icon(Icons.Filled.SkipPrevious, "Previous", tint = motif.text.copy(alpha = if (p.canSkipPrev) 1f else 0.35f), modifier = Modifier.size(30.dp))
        }
        BigPlay(p.isPaused) { app.player.togglePlayPause() }
        Box(Modifier.size(56.dp).clip(CircleShape).clickable(enabled = p.canSkipNext) { app.player.next() }, contentAlignment = Alignment.Center) {
            Icon(Icons.Filled.SkipNext, "Next", tint = motif.text.copy(alpha = if (p.canSkipNext) 1f else 0.35f), modifier = Modifier.size(30.dp))
        }
        Box(
            Modifier
                .size(56.dp)
                .clip(CircleShape)
                .border(1.dp, motif.chipLine, CircleShape)
                .combinedClickableCompat(
                    enabled = true,
                    onClick = { addTarget = AddTarget(p.uri, p.title) },
                    onLongClick = { newPlaylistFor = AddTarget(p.uri, p.title) },
                ),
            contentAlignment = Alignment.Center,
        ) { Icon(Icons.Outlined.Add, "Add to playlist", tint = motif.text, modifier = Modifier.size(24.dp)) }
    }

    AddToHost(app, addTarget, newPlaylistFor, onDismiss = { addTarget = null; newPlaylistFor = null }, onDone = { line = it })
}

/** Synced lyrics: current line in text color, others muted; keeps the current line near the top third. */
@Composable
private fun LyricsView(lines: List<LyricLine>, positionMs: Long, modifier: Modifier) {
    val state = rememberLazyListState()
    val current = remember(lines, positionMs) {
        var idx = -1
        for (i in lines.indices) if (lines[i].timeMs <= positionMs) idx = i else break
        idx
    }
    LaunchedEffect(current) {
        if (current >= 0) state.animateScrollToItem((current - 2).coerceAtLeast(0))
    }
    LazyColumn(modifier.padding(horizontal = 20.dp, vertical = 16.dp), state = state, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        itemsIndexed(lines) { i, l ->
            val active = i == current
            Text(
                l.text.ifBlank { "♪" },
                fontSize = (if (active) 20 else 17).sp * motif.textScale,
                fontWeight = if (active) FontWeight.Medium else FontWeight.Normal,
                color = if (active) motif.text else motif.textMuted.copy(alpha = 0.8f),
                lineHeight = 26.sp,
            )
        }
        item { Spacer(Modifier.height(120.dp)) }
    }
}

// ---------------------------------------------------------------- Podcast

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ColumnScope.PodcastBody(
    app: MotifApp,
    p: SpotifyRemote.Playback,
    positionMs: Long,
    contextTitle: String?,
    art: android.graphics.Bitmap?,
    onBack: () -> Unit,
    onShare: () -> Unit,
    speaker: @Composable () -> Unit,
) {
    val speeds by app.prefs.podcastSpeeds.collectAsStateWithLifecycle(emptyMap())
    val sleepEndsAt by app.player.sleepEndsAt.collectAsStateWithLifecycle()
    val showUri = p.albumUri
    val savedSpeed = showUri?.let { speeds[it] } ?: 1f
    var askSleep by remember { mutableStateOf(false) }
    var sleepText by remember { mutableStateOf("") }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(sleepEndsAt) { while (sleepEndsAt != null) { now = System.currentTimeMillis(); delay(1000) } }

    // Apply the remembered speed when a show starts playing here.
    LaunchedEffect(showUri) { if (showUri != null && savedSpeed != 1f) app.player.setPodcastSpeed(savedSpeed, null) }

    Header(contextTitle ?: p.album.takeIf { it.isNotBlank() }, onBack) {
        speaker()
        IconButton44(Icons.Outlined.IosShare, "Share", onShare, tint = motif.textMuted, size = 20.dp)
    }

    Box(Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(8.dp)).background(motif.card)) {
        if (art != null) Image(art.asImageBitmap(), null, Modifier.fillMaxSize())
    }

    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(p.title, style = MaterialTheme.typography.titleLarge, color = motif.text, maxLines = 3, overflow = TextOverflow.Ellipsis)
        Text(p.album.ifBlank { p.artist }, style = MaterialTheme.typography.bodyMedium, color = motif.textMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }

    val leftMin = ((p.durationMs - positionMs) / 60_000).coerceAtLeast(0)
    Scrubber(p, positionMs, onSeek = { app.player.seekTo(it) }, rightLabel = "$leftMin min left")

    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Chip(speedLabel(savedSpeed), selected = false, height = 32.dp, onClick = {
            val next = SPEEDS[(SPEEDS.indexOfFirst { it >= savedSpeed - 0.01f }.coerceAtLeast(0) + 1) % SPEEDS.size]
            app.player.setPodcastSpeed(next, showUri)
        })
        SkipButton(forward = false, "Back 15 seconds") { app.player.seekRelative(-15_000) }
        BigPlay(p.isPaused) { app.player.togglePlayPause() }
        SkipButton(forward = true, "Forward 15 seconds") { app.player.seekRelative(15_000) }
        val remaining = sleepEndsAt?.let { ((it - now) / 60_000 + 1).coerceAtLeast(0) }
        Chip(
            label = remaining?.let { "☾ $it" } ?: "☾",
            selected = remaining != null,
            height = 32.dp,
            onClick = { sleepText = ""; askSleep = true },
            onLongClick = { app.player.setSleepMinutes(null) },
        )
    }

    if (askSleep) {
        ModalBottomSheet(
            onDismissRequest = { askSleep = false },
            containerColor = motif.card, contentColor = motif.text, dragHandle = null,
            shape = RoundedCornerShape(topStart = 14.dp, topEnd = 14.dp),
        ) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 16.dp).navigationBarsPadding(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Sleep in how many minutes?", style = MaterialTheme.typography.bodyMedium, color = motif.textMuted)
                MotifTextField(sleepText, { sleepText = it.filter(Char::isDigit).take(3) }, placeholder = "Minutes",
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    onDone = { sleepText.toIntOrNull()?.let { app.player.setSleepMinutes(it) }; askSleep = false })
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                    if (sleepEndsAt != null) PillButton("Cancel timer", filled = false) { app.player.setSleepMinutes(null); askSleep = false }
                    PillButton("Start", enabled = sleepText.toIntOrNull() != null) { app.player.setSleepMinutes(sleepText.toIntOrNull()); askSleep = false }
                }
            }
        }
    }
}

/** Circular arrow with "15" inside; the forward one is the same glyph mirrored. */
@Composable
private fun SkipButton(forward: Boolean, label: String, onClick: () -> Unit) {
    Box(Modifier.size(56.dp).clip(CircleShape).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        Icon(Icons.Outlined.Replay, label, tint = motif.text, modifier = Modifier.size(30.dp).graphicsLayer { if (forward) scaleX = -1f })
        Text("15", fontSize = 9.sp, fontWeight = FontWeight.Medium, color = motif.text, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 3.dp))
    }
}

private fun speedLabel(s: Float): String = when {
    s == 1f -> "1x"
    s == 2f -> "2x"
    else -> "${s}x".replace(".0x", "x")
}
