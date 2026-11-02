package com.kirkisham.motif.ui

import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.kirkisham.motif.MotifApp
import com.kirkisham.motif.data.LastEpisode
import com.kirkisham.motif.ui.components.AddTarget
import com.kirkisham.motif.ui.components.AddToHost
import com.kirkisham.motif.ui.alarm.AlarmScreen
import com.kirkisham.motif.ui.home.HomeScreen
import com.kirkisham.motif.ui.info.InfoKind
import com.kirkisham.motif.ui.info.InfoScreen
import com.kirkisham.motif.ui.nowplaying.NowPlayingScreen
import com.kirkisham.motif.ui.playlist.MassEditScreen
import com.kirkisham.motif.ui.playlist.PlaylistScreen
import com.kirkisham.motif.ui.playlists.AllPlaylistsScreen
import com.kirkisham.motif.ui.search.SearchScreen
import com.kirkisham.motif.ui.settings.SettingsScreen
import androidx.navigation.NavType
import androidx.navigation.navArgument

/** Route names. Screens are added here as the build order reaches them (CLAUDE.md). */
object Routes {
    const val HOME = "home"
    const val ALL = "all"
    const val PLAYLIST = "playlist/{id}"
    const val NOW = "now"
    const val SETTINGS = "settings"
    const val ALARM = "alarm"
    const val SEARCH = "search?q={q}"
    const val MASS_EDIT = "massedit/{id}"
    const val INFO = "info/{kind}/{id}"
    fun playlist(id: String) = "playlist/$id"
    fun search(query: String = "") = "search?q=" + Uri.encode(query)
    fun massEdit(id: String) = "massedit/$id"
    fun infoTrack(id: String) = "info/track/$id"
    fun infoArtist(id: String) = "info/artist/$id"
}

@Composable
fun MotifNavHost(
    app: MotifApp,
    nav: NavHostController = rememberNavController(),
    pendingRoute: String? = null,
    onRouteConsumed: () -> Unit = {},
) {
    val playback by app.remote.playback.collectAsStateWithLifecycle()
    LaunchedEffect(pendingRoute) {
        if (pendingRoute != null) { nav.navigate(pendingRoute) { launchSingleTop = true }; onRouteConsumed() }
    }
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
                onOpenSearch = { nav.navigate(Routes.search()) },
                onPlus = onPlus,
            )
        }
        composable(Routes.SEARCH, arguments = listOf(navArgument("q") { type = NavType.StringType; defaultValue = "" })) { entry ->
            SearchScreen(
                app = app,
                initialQuery = entry.arguments?.getString("q").orEmpty(),
                onBack = { nav.popBackStack() },
                onOpenPlaylist = { nav.navigate(Routes.playlist(it)) },
                onOpenArtist = { a -> a.id?.let { nav.navigate(Routes.infoArtist(it)) } },
                onOpenNowPlaying = { nav.navigate(Routes.NOW) },
                onPlus = onPlus,
            )
        }
        composable(Routes.MASS_EDIT) { entry ->
            val id = entry.arguments?.getString("id") ?: return@composable
            MassEditScreen(app, id, onClose = { nav.popBackStack() })
        }
        composable(Routes.INFO) { entry ->
            val id = entry.arguments?.getString("id") ?: return@composable
            val kind = if (entry.arguments?.getString("kind") == "artist") InfoKind.ARTIST else InfoKind.TRACK
            InfoScreen(app, kind, id, onBack = { nav.popBackStack() }, onSearch = { nav.navigate(Routes.search(it)) })
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
                onMassEdit = { nav.navigate(Routes.massEdit(id)) },
                onOpenArtist = { a -> a.id?.let { nav.navigate(Routes.infoArtist(it)) } },
            )
        }
        composable(Routes.NOW) {
            NowPlayingScreen(app, onBack = { nav.popBackStack() }, onOpenInfo = { nav.navigate(Routes.infoTrack(it)) })
        }
        composable(Routes.SETTINGS) { SettingsScreen(app, onBack = { nav.popBackStack() }) }
        composable(Routes.ALARM) { AlarmScreen(app, onBack = { nav.popBackStack() }) }
    }

    AddToHost(app, plusTarget, null, onDismiss = { plusTarget = null })
}
