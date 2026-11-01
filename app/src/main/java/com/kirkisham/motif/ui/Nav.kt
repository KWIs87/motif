package com.kirkisham.motif.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.kirkisham.motif.MotifApp
import com.kirkisham.motif.data.LastEpisode
import com.kirkisham.motif.ui.components.AddTarget
import com.kirkisham.motif.ui.components.AddToHost
import com.kirkisham.motif.ui.components.PlainLine
import com.kirkisham.motif.ui.components.TopBar
import com.kirkisham.motif.ui.home.HomeScreen
import com.kirkisham.motif.ui.nowplaying.NowPlayingScreen
import com.kirkisham.motif.ui.playlist.PlaylistScreen
import com.kirkisham.motif.ui.playlists.AllPlaylistsScreen
import com.kirkisham.motif.ui.settings.SettingsScreen

/** Route names. Screens are added here as the build order reaches them (CLAUDE.md). */
object Routes {
    const val HOME = "home"
    const val ALL = "all"
    const val PLAYLIST = "playlist/{id}"
    const val NOW = "now"
    const val SETTINGS = "settings"
    const val ALARM = "alarm"
    fun playlist(id: String) = "playlist/$id"
}

@Composable
fun MotifNavHost(app: MotifApp, nav: NavHostController = rememberNavController()) {
    val playback by app.remote.playback.collectAsStateWithLifecycle()
    var plusTarget by remember { mutableStateOf<AddTarget?>(null) }
    val onPlus: () -> Unit = { playback?.let { plusTarget = AddTarget(it.uri, it.title) } }

    // Remember the last episode Spotify played so Home can offer it again (spec §3.3).
    LaunchedEffect(playback?.uri, playback?.isPaused) {
        val p = playback ?: return@LaunchedEffect
        if (p.isEpisode) app.prefs.setLastEpisode(
            LastEpisode(p.uri, p.title, p.album.ifBlank { p.artist }, p.imageUri, p.durationMs, p.positionNow(), System.currentTimeMillis()),
        )
    }

    NavHost(nav, startDestination = Routes.HOME) {
        composable(Routes.HOME) {
            HomeScreen(
                app = app,
                onOpenPlaylist = { nav.navigate(Routes.playlist(it)) },
                onOpenAll = { nav.navigate(Routes.ALL) },
                onOpenNowPlaying = { nav.navigate(Routes.NOW) },
                onOpenAlarm = { nav.navigate(Routes.ALARM) },
                onOpenSettings = { nav.navigate(Routes.SETTINGS) },
                onPlus = onPlus,
            )
        }
        composable(Routes.ALL) {
            AllPlaylistsScreen(
                app = app,
                onBack = { nav.popBackStack() },
                onOpenPlaylist = { nav.navigate(Routes.playlist(it)) },
                onOpenNowPlaying = { nav.navigate(Routes.NOW) },
                onPlus = onPlus,
            )
        }
        composable(Routes.PLAYLIST) { entry ->
            val id = entry.arguments?.getString("id") ?: return@composable
            PlaylistScreen(
                app = app,
                playlistId = id,
                onBack = { nav.popBackStack() },
                onOpenNowPlaying = { nav.navigate(Routes.NOW) },
                onPlus = onPlus,
                onDeleted = { nav.popBackStack() },
            )
        }
        composable(Routes.NOW) { NowPlayingScreen(app, onBack = { nav.popBackStack() }) }
        composable(Routes.SETTINGS) { SettingsScreen(app, onBack = { nav.popBackStack() }) }
        composable(Routes.ALARM) {
            // Stage 3 replaces this with the Alarm editor.
            Column(Modifier.fillMaxSize().statusBarsPadding().padding(horizontal = 16.dp)) {
                TopBar("Alarm", onBack = { nav.popBackStack() })
                PlainLine("Alarms arrive in the next build.", Modifier.padding(start = 8.dp))
            }
        }
    }

    AddToHost(app, plusTarget, null, onDismiss = { plusTarget = null })
}
